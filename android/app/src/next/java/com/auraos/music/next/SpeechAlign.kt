package com.auraos.music.next

import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Word timing from a speech recogniser (no Android in this file, so it can be run and tested on a computer).
// A small CTC model (NeMo Conformer, 40 ms steps) says, for every 40 ms of the song, how likely each piece of a word is to be sounding.
// The lyrics are already known, so the question is only "where does each word of each line fall": found by the best path through those
// probabilities that spells the lyrics in order, with every line kept near the time its lyrics file gives it.

/** where the words of one line were found: seconds of the song, a start and an end for each word (NaN = this word has nothing to find, e.g. a number) */
class SpeechLine(val starts: DoubleArray, val ends: DoubleArray, val conf: Double)

/** 22.05 kHz stereo -> 16 kHz mono (the middle of the stereo picture, where the voice usually is), in 16-bit sample size */
object Resample16 {
    private const val IN = WordTiming.RATE
    private const val OUT = 16000
    private const val HALF = 16
    private const val PHASES = 512
    private val table: Array<FloatArray> by lazy {
        val fc = 0.5 * OUT * 0.97 / IN            // cut-off (cycles per input sample): a little under the new Nyquist
        Array(PHASES) { ph ->
            val frac = ph / PHASES.toDouble()
            FloatArray(2 * HALF) { k ->
                val x = (k - HALF + 1) - frac       // distance from the output position, in input samples
                val sinc = if (abs(x) < 1e-9) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val u = x / HALF
                val w = if (abs(u) >= 1) 0.0 else 0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u)     // Blackman
                (sinc * w).toFloat()
            }
        }
    }

    /** from [fromSample] to [toSample] of the 22.05 kHz audio; sample 0 of the result is the song's time fromSample/22050 */
    fun mono(pcm: Pcm, fromSample: Int = 0, toSample: Int = pcm.n): FloatArray {
        val a = fromSample.coerceIn(0, pcm.n); val b = toSample.coerceIn(a, pcm.n)
        val nOut = ((b - a).toLong() * OUT / IN).toInt()
        val out = FloatArray(nOut)
        val step = IN.toDouble() / OUT
        val t = table
        for (k in 0 until nOut) {
            val p = a + k * step
            val i0 = p.toInt()
            val ph = ((p - i0) * PHASES).toInt().coerceIn(0, PHASES - 1)
            val f = t[ph]
            var acc = 0f
            var j = i0 - HALF + 1
            for (m in 0 until 2 * HALF) {
                if (j >= 0 && j < pcm.n) acc += f[m] * (pcm.left[j] + pcm.right[j]) * 0.5f
                j++
            }
            out[k] = acc
        }
        return out
    }
}

/** Kaldi-style log mel features (80 bins, 25 ms / 10 ms), made the way the model was trained, then each bin centred and scaled over the clip */
object Fbank {
    const val BINS = 80
    private const val FRAME = 400
    private const val SHIFT = 160
    private const val NFFT = 512
    private val window = DoubleArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).pow(0.85) }
    private val cosT = DoubleArray(NFFT / 2) { cos(2 * PI * it / NFFT) }
    private val sinT = DoubleArray(NFFT / 2) { -sin(2 * PI * it / NFFT) }
    private val rev = IntArray(NFFT).also { r -> val bits = 9; for (i in 0 until NFFT) { var x = i; var y = 0; for (b in 0 until bits) { y = (y shl 1) or (x and 1); x = x shr 1 }; r[i] = y } }

    // the mel filters: triangles that are 1 at their own centre, over the first 256 FFT bins
    private val filters: Array<Pair<Int, FloatArray>> by lazy {
        fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val low = 20.0; val high = 8000.0
        val mLow = mel(low); val mHigh = mel(high); val delta = (mHigh - mLow) / (BINS + 1)
        val binW = 16000.0 / NFFT
        Array(BINS) { b ->
            val left = mLow + b * delta; val center = left + delta; val right = center + delta
            var first = -1; val w = ArrayList<Float>()
            for (i in 0 until NFFT / 2) {
                val m = mel(binW * i)
                val v = if (m > left && m < right) (if (m <= center) (m - left) / (center - left) else (right - m) / (right - center)) else 0.0
                if (v > 0 && first < 0) first = i
                if (first >= 0) w.add(v.toFloat())
            }
            while (w.isNotEmpty() && w.last() == 0f) w.removeAt(w.size - 1)
            (if (first < 0) 0 else first) to w.toFloatArray()
        }
    }

    /** [x]: 16 kHz samples in 16-bit scale. Returns frames x 80 (frame-major), normalised per bin. */
    fun compute(x: FloatArray): Pair<FloatArray, Int> {
        val n = x.size
        val frames = (n + SHIFT / 2) / SHIFT
        if (frames <= 0) return FloatArray(0) to 0
        val out = FloatArray(frames * BINS)
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        val buf = DoubleArray(FRAME)
        val fl = filters
        for (f in 0 until frames) {
            val start = f * SHIFT + SHIFT / 2 - FRAME / 2
            var mean = 0.0
            for (i in 0 until FRAME) {
                var s = start + i
                while (s < 0 || s >= n) { if (s < 0) s = -s - 1; if (s >= n) s = 2 * n - 1 - s }
                buf[i] = x[s].toDouble(); mean += buf[i]
            }
            mean /= FRAME
            for (i in 0 until FRAME) buf[i] -= mean
            for (i in FRAME - 1 downTo 1) buf[i] -= 0.97 * buf[i - 1]
            buf[0] -= 0.97 * buf[0]
            for (i in 0 until NFFT) { val v = if (i < FRAME) buf[i] * window[i] else 0.0; re[rev[i]] = v; im[rev[i]] = 0.0 }
            fft(re, im)
            for (b in 0 until BINS) {
                val (first, w) = fl[b]
                var e = 0.0
                for (k in w.indices) { val i = first + k; e += w[k] * (re[i] * re[i] + im[i] * im[i]) }
                out[f * BINS + b] = ln(max(e, 1.1920929e-07)).toFloat()
            }
        }
        // per-bin centring and scaling over this clip
        for (b in 0 until BINS) {
            var m = 0.0; for (f in 0 until frames) m += out[f * BINS + b]; m /= frames
            var v = 0.0; for (f in 0 until frames) { val d = out[f * BINS + b] - m; v += d * d }
            val sd = sqrt(v / frames)
            for (f in 0 until frames) out[f * BINS + b] = ((out[f * BINS + b] - m) / (sd + 1e-5)).toFloat()
        }
        return out to frames
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        var size = 2
        while (size <= NFFT) {
            val half = size / 2; val stepT = NFFT / size
            var i = 0
            while (i < NFFT) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosT[k]; val s = sinT[k]
                    val tr = re[j + half] * c - im[j + half] * s
                    val ti = re[j + half] * s + im[j + half] * c
                    re[j + half] = re[j] - tr; im[j + half] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += stepT
                }
                i += size
            }
            size *= 2
        }
    }
}

/** the model's word pieces (tokens.txt: "piece id" per line, the last one is the blank) */
class Vocab(lines: List<String>) {
    private val pieces = ArrayList<String>()
    private val index = HashMap<String, Int>()
    val blank: Int
    init {
        for (l in lines) { val t = l.trimEnd(); if (t.isEmpty()) continue; pieces.add(t.substring(0, t.lastIndexOf(' '))) }
        blank = pieces.size - 1
        for (i in 0 until blank) index[pieces[i]] = i
    }
    val size: Int get() = pieces.size

    /** a word as the model would spell it: lower case, letters and apostrophes only (accents removed) */
    fun norm(w: String): String {
        val d = Normalizer.normalize(w, Normalizer.Form.NFKD).replace("’", "'").lowercase()
        val sb = StringBuilder()
        for (c in d) if ((c in 'a'..'z') || c == '\'') sb.append(c)
        return sb.toString()
    }

    /** the fewest pieces that spell the word (null when it can't be spelled) */
    fun encode(word: String): IntArray? {
        val w = norm(word)
        if (w.isEmpty()) return null
        val s = "▁$w"; val n = s.length
        val INF = 1 shl 20
        val best = IntArray(n + 1) { INF }; val prv = IntArray(n + 1); best[0] = 0
        for (i in 0 until n) {
            if (best[i] >= INF) continue
            for (j in i + 1..min(n, i + 12)) {
                val id = index[s.substring(i, j)] ?: continue
                if (best[i] + 1 < best[j]) { best[j] = best[i] + 1; prv[j] = i }
            }
        }
        if (best[n] >= INF) return null
        val out = ArrayList<Int>(); var j = n
        while (j > 0) { val i = prv[j]; out.add(index[s.substring(i, j)]!!); j = i }
        out.reverse()
        return out.toIntArray()
    }
}

/** the model's answer for the whole song: a row of log-probabilities every 40 ms. Frames nobody has listened to yet say "nothing is sung". */
class LogProbs(val frames: Int, val vocab: Int, blank: Int) {
    val data = FloatArray(frames * vocab)
    val heard = BooleanArray(frames)
    init {
        java.util.Arrays.fill(data, -30f)
        for (f in 0 until frames) data[f * vocab + blank] = 0f
    }
    companion object { const val STEP = 0.04 }
}

object CtcAlign {
    /** a word's time is the model's first sign of it, a little before (the model answers a moment after the sound starts) */
    const val LEAD = 0.04
    private const val NEG = -1e30f

    /** Finds the words of the lines that can be placed. [bases]: where each line is expected to start in the song's audio time (the lyrics' time plus the shift found),
     *  [skip]: lines that stay with the other method (ad-libs sung over another line). Lines are given as their words. */
    fun align(lp: LogProbs, vocab: Vocab, words: List<List<String>>, bases: DoubleArray, skip: BooleanArray, early: Double = 2.0, late: Double = 1.5): Map<Int, SpeechLine> {
        val T = lp.frames; val V = lp.vocab
        if (T < 4) return emptyMap()
        // the pieces of every word, line after line
        val tok = ArrayList<Int>(); val tokLine = ArrayList<Int>(); val tokWord = ArrayList<Int>()
        val lo = ArrayList<Int>(); val hi = ArrayList<Int>()
        val wordTok = HashMap<Long, IntArray>()           // (line, word) -> first token index, count
        val active = ArrayList<Int>()
        for (i in words.indices) if (!skip[i] && words[i].isNotEmpty()) active.add(i)
        for ((n, i) in active.withIndex()) {
            val nextBase = if (n + 1 < active.size) bases[active[n + 1]] else bases[i] + 8.0
            val l = max(0, ((bases[i] - early) / LogProbs.STEP).toInt())
            var h = min(T - 1, ((max(nextBase, bases[i] + 0.5) + late) / LogProbs.STEP).toInt())
            for ((wi, w) in words[i].withIndex()) {
                val e = vocab.encode(w) ?: continue
                wordTok[i.toLong() shl 20 or wi.toLong()] = intArrayOf(tok.size, e.size)
                for (t in e) { tok.add(t); tokLine.add(i); tokWord.add(wi); lo.add(l); hi.add(h) }
            }
        }
        val L = tok.size
        if (L == 0) return emptyMap()
        // a line must have room for its pieces (about two frames each)
        for (j in 0 until L) if (hi[j] - lo[j] < 3) hi[j] = min(T - 1, lo[j] + 3)
        val S = 2 * L + 1
        if (T.toLong() * S > 60_000_000L) return emptyMap()          // too long to keep the paths in memory
        val lab = IntArray(S) { if (it % 2 == 1) tok[(it - 1) / 2] else lp.vocab - 1 }
        // the frames each state may be on: a piece, only within its own line's stretch; a blank, anywhere between its two neighbours' stretches
        val slo = IntArray(S); val shi = IntArray(S)
        for (s in 0 until S) {
            if (s % 2 == 1) { val j = (s - 1) / 2; slo[s] = lo[j]; shi[s] = hi[j] }
            else {
                val j = s / 2
                slo[s] = if (j == 0) 0 else lo[j - 1]
                shi[s] = if (j == L) T - 1 else if (j == 0) hi[0] else max(hi[j - 1], hi[j])
            }
        }
        val skipOk = BooleanArray(S) { it >= 3 && it % 2 == 1 && tok[(it - 1) / 2] != tok[(it - 3) / 2] }
        var prev = FloatArray(S) { NEG }; var cur = FloatArray(S) { NEG }
        val bp = ByteArray(T * S)
        fun at(t: Int, s: Int) = lp.data[t * V + lab[s]]
        prev[0] = at(0, 0)
        if (S > 1 && slo[1] <= 0) prev[1] = at(0, 1)
        var sA = 0; var sB = 0
        for (t in 1 until T) {
            java.util.Arrays.fill(cur, NEG)
            while (sA < S - 1 && shi[sA] < t) sA++
            while (sB + 1 < S && slo[sB + 1] <= t) sB++
            for (s in sA..sB) {
                if (t > shi[s] || t < slo[s]) continue
                var b = prev[s]; var k = 0
                if (s >= 1 && prev[s - 1] > b) { b = prev[s - 1]; k = 1 }
                if (skipOk[s] && prev[s - 2] > b) { b = prev[s - 2]; k = 2 }
                if (b > NEG / 2) { cur[s] = b + at(t, s); bp[t * S + s] = k.toByte() }
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        // the path must end after the last piece (or in the blank after it)
        var s = if (prev[S - 1] >= prev[S - 2]) S - 1 else S - 2
        if (prev[s] <= NEG / 2) return emptyMap()
        val first = IntArray(L) { -1 }; val last = IntArray(L) { -1 }; val bestLp = FloatArray(L) { -1e9f }
        for (t in T - 1 downTo 0) {
            if (s % 2 == 1) { val j = (s - 1) / 2; first[j] = t; if (last[j] < 0) last[j] = t; val v = lp.data[t * V + tok[j]]; if (v > bestLp[j]) bestLp[j] = v }
            s -= bp[t * S + s].toInt()
            if (s < 0) break
        }
        val out = HashMap<Int, SpeechLine>()
        for (i in active) {
            val ws = words[i]
            val st = DoubleArray(ws.size) { Double.NaN }; val en = DoubleArray(ws.size) { Double.NaN }
            var sum = 0.0; var cnt = 0; var timed = 0
            for (wi in ws.indices) {
                val r = wordTok[i.toLong() shl 20 or wi.toLong()] ?: continue
                val a = r[0]; val b = r[0] + r[1] - 1
                if (first[a] < 0 || last[b] < 0) continue
                st[wi] = first[a] * LogProbs.STEP - LEAD
                en[wi] = (last[b] + 1) * LogProbs.STEP
                timed++
                for (j in a..b) { sum += bestLp[j]; cnt++ }
            }
            if (timed == 0 || cnt == 0) continue
            // (frames nobody listened to carry -30, which sinks the line's score: it stays with the other method)
            out[i] = SpeechLine(st, en, sum / cnt)
        }
        return out
    }

    /** the lowest average log-probability a line may have for its words to be trusted */
    const val TRUST = -4.0
}
