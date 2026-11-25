package com.auraos.music.next

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// The pure part of word timing (no Android in it, so it can be run and tested on a computer): lines the lyrics up against what the voice finder reports.

/** the whole song as 22.05 kHz stereo 16-bit */
class Pcm(val left: ShortArray, val right: ShortArray, val n: Int) {
    /** how the file was read (codec, rates, where the first sample sits): for the diagnostics and the timing report */
    var info: String = ""
}

/** how many syllables a word has, and which words are sung fast and short */
object Syllables {
    private val marks = Regex("\\p{M}+")
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase()

    /** English-like words go through the C counter (silent e, "-ing", "-ed", a table of oddities like "everyone"); scripts without spaces get one per character */
    fun count(w: String): Int {
        val t = w.trim()
        val letters = t.filter { it.isLetter() }
        if (letters.isEmpty()) return 1
        // an accented e is always sounded ("café"), so it isn't a silent final e once the accent is gone
        var sounded = t
        for (c in listOf("é", "è", "ê", "ë", "É", "È", "Ê", "Ë")) sounded = sounded.replace(c, "ey")
        val plain = fold(sounded)
        val ascii = plain.count { it.code < 128 && it.isLetter() }
        if (ascii == 0 || plain.any { it.code > 0x24F && it.isLetter() }) return max(1, letters.length)
        return Voice.nSyllables(plain).coerceAtLeast(1)
    }

    /** what a word is, for spotting a repeat */
    fun key(w: String): String = fold(w).filter { it.isLetter() }

    /** 1 for a word that repeats the one before or after it ("eh eh", "la la la", "I I I"): those are sung quickly, so they get a shorter share of the line */
    fun quick(parts: List<String>): IntArray {
        val k = parts.map { key(it) }
        return IntArray(k.size) { i -> if (k[i].isEmpty()) 0 else if ((i > 0 && k[i] == k[i - 1]) || (i + 1 < k.size && k[i] == k[i + 1])) 1 else 0 }
    }
}

object WordTiming {
    const val RATE = 22050

    fun stamp(t0: Double): String {
        val t = max(0.0, t0)
        val m = (t / 60).toInt()
        return String.format(Locale.US, "%02d:%05.2f", m, t - m * 60)
    }
    internal fun sign(x: Double) = String.format(Locale.US, "%+.2f", x)
    fun isAdlib(s: String): Boolean { val t = s.trim(); return t.length > 2 && t.startsWith("(") && t.endsWith(")") }

    /** the slot a line has in the lyrics (audio time): from its stamp to the next line's. A "(ad-lib)" line sung over the top doesn't end the line before it. */
    internal fun slot(lines: List<Online.Line>, i: Int, offset: Double): Pair<Double, Double> {
        val line = lines[i]
        val base = line.ms / 1000.0 + offset
        var k = i + 1
        if (!isAdlib(line.text)) while (k < lines.size && isAdlib(lines[k].text)) k++
        val end = if (k < lines.size) lines[k].ms / 1000.0 + offset else base + min(8.0, 1.5 + line.text.length * 0.12)
        return base to max(end, base + 0.4)
    }

    /** the stretch of the song searched for a line's singing: its own slot (a hair early, in case the stamp is a touch late) */
    private fun window(lines: List<Online.Line>, i: Int, offset: Double, seconds: Double): Pair<Double, Double>? {
        val line = lines[i]
        if (line.text.isEmpty() || line.words.isEmpty()) return null
        val sl = slot(lines, i, offset)
        val ws = max(0.0, sl.first - 0.15)
        val we = min(sl.second, seconds)
        return if (we - ws > 0.4) ws to we else null
    }

    /** the pieces of a line that get their own time: its words (or, for a line written without spaces in Latin letters, the line as a whole) */
    internal fun pieces(line: Online.Line): List<String> {
        if (!line.text.contains(" ") && line.text.all { it.code <= 0x24F }) return listOf(line.text)
        return line.words.map { it.text }
    }

    /** what the voice finder made of a song, line by line, before it is written out (so lines can be swapped for better ones without listening again) */
    class Timed(val lines: List<Online.Line>, val shift: Double, val residual: Double, val used: Int,
                private val parts: List<List<String>>, private val s: List<DoubleArray>, private val e: List<DoubleArray>, private val found: BooleanArray) {
        /** how many lines the voice finder placed */
        val placed: Int get() = found.count { it }

        /** the enhanced .lrc. [speech]: lines the speech model heard well enough (they replace the voice finder's guess for that line) */
        fun render(speech: Map<Int, SpeechLine>?): String {
            val out = StringBuilder("[strata:shift=${sign(shift)} found=${sign(residual)} from=$used]\n")
            var prevStamp = 0.0
            for ((i, line) in lines.withIndex()) {
                val base = line.ms / 1000.0 + shift
                val ps = parts[i]
                val sp = speech?.get(i)?.takeIf { it.conf >= CtcAlign.TRUST && it.starts.size == ps.size && ps.isNotEmpty() && it.starts.count { v -> !v.isNaN() } * 2 >= ps.size }
                val sl = slot(lines, i, shift)
                if (sp != null) {
                    val n = ps.size
                    val st = DoubleArray(n); val en = DoubleArray(n)
                    // a word with nothing to find (a number, a sign) takes the place just after the word before it
                    var last = Double.NaN
                    for (k in 0 until n) {
                        if (!sp.starts[k].isNaN()) { st[k] = sp.starts[k]; en[k] = sp.ends[k] }
                        else { st[k] = if (last.isNaN()) Double.NaN else last + 0.05; en[k] = st[k] + 0.2 }
                        if (!st[k].isNaN()) last = en[k]
                    }
                    for (k in n - 1 downTo 0) if (st[k].isNaN()) { st[k] = if (k + 1 < n) st[k + 1] - 0.2 else sl.first; en[k] = st[k] + 0.2 }
                    for (k in 1 until n) st[k] = max(st[k], st[k - 1] + 0.04)         // words never pile up on the same instant
                    val first = max(prevStamp, min(sl.first, st[0]))
                    prevStamp = first
                    out.append("[").append(stamp(first)).append("]")
                    for (k in 0 until n) {
                        out.append("<").append(stamp(max(st[k], first))).append(">").append(ps[k])
                        val nextStart = if (k + 1 < n) st[k + 1] else max(sl.second, st[k] + 0.4)
                        // sung straight into the next word: the word lasts until it; a pause after it: the word stops a little after the model last heard it
                        val stop = if (k + 1 < n && nextStart - en[k] < 0.5) nextStart else min(max(en[k] + 0.2, st[k] + 0.1), nextStart)
                        if (stop < nextStart - 0.15 || k + 1 == n) out.append("<").append(stamp(stop)).append(">")
                    }
                    out.append("\n"); continue
                }
                if (found[i]) {
                    val ss = s[i].copyOf(); val ee = e[i]
                    // inside the line's own slot, and never before the one before it
                    val lastOK = max(sl.first, sl.second - 0.12)
                    val first = max(sl.first, prevStamp)
                    for (k in ss.indices) {
                        ss[k] = max(ss[k] + 0.03, first)
                        if (k > 0) ss[k] = max(ss[k], ss[k - 1] + 0.06)        // words never pile up on the same instant
                    }
                    val lastS = ss.last()
                    if (lastS > lastOK && lastS > first) for (k in ss.indices) ss[k] = first + (ss[k] - first) * (lastOK - first) / (lastS - first)
                    prevStamp = first
                    out.append("[").append(stamp(first)).append("]")
                    for ((k, p) in ps.withIndex()) {
                        out.append("<").append(stamp(ss[k])).append(">").append(p)
                        // where the singing of a word stops (a breath, a pause, the end of the line), written as a time with no word after it, so nothing is lit while nobody sings
                        val nextStart = if (k + 1 < ss.size) ss[k + 1] else sl.second
                        val stop = min(max(ee[k] + 0.03, ss[k] + 0.1), nextStart)
                        if (stop < nextStart - 0.15 || k + 1 == ss.size) out.append("<").append(stamp(stop)).append(">")
                    }
                } else {
                    val st = max(prevStamp, base)
                    prevStamp = st
                    out.append("[").append(stamp(st)).append("]")
                    if (line.tagged) { for (wd in line.words) { out.append("<").append(stamp(wd.start / 1000.0 + shift)).append(">").append(wd.text); if (wd.end >= 0) out.append("<").append(stamp(wd.end / 1000.0 + shift)).append(">") } }
                    else out.append(line.text)
                }
                out.append("\n")
            }
            return out.toString()
        }
    }

    /** a song with nothing placed yet, only here so lines the speech model heard can be written out before the voice finder has listened */
    fun blank(lines: List<Online.Line>, offset: Double): Timed =
        Timed(lines, offset, 0.0, 0, lines.map { pieces(it) }, lines.map { DoubleArray(0) }, lines.map { DoubleArray(0) }, BooleanArray(lines.size))

    /** the words of each line as they get their own times (what the speech model has to be given, too) */
    fun wordsOf(lines: List<Online.Line>): List<List<String>> = lines.map { pieces(it) }

    /** listens to a whole song for the voice: where each line's words fall, or null if no line could be lined up */
    fun align(pcm: Pcm, lines: List<Online.Line>, offset: Double, progress: (Float) -> Unit = {}): Timed? {
        val seconds = pcm.n.toDouble() / RATE
        progress(0.35f)
        val voice = Voice.nAnalyze(pcm.left, pcm.right, pcm.n, RATE)
        if (voice == 0L) return null
        try {
            progress(0.7f)
            // The lyrics' times say which line is which, but a file can be a little early or late as a whole (or carry an old nudge of yours that no longer fits).
            // Where the voice comes back in after each pause says how far off it is; if that is steady, every line moves by the same amount.
            val bases = lines.filter { it.text.isNotEmpty() && it.words.isNotEmpty() && !isAdlib(it.text) }.map { it.ms / 1000.0 + offset }.toDoubleArray()
            val used = IntArray(1)
            val residual = if (bases.isEmpty()) 0.0 else Voice.nResidual(voice, bases, used)
            val shift = (offset + residual).coerceIn(-3.0, 3.0)
            val allParts = ArrayList<List<String>>(); val allS = ArrayList<DoubleArray>(); val allE = ArrayList<DoubleArray>(); val found = BooleanArray(lines.size)
            var prevEnd = 0.0; var done = 0
            for ((i, line) in lines.withIndex()) {
                if (i % 4 == 0) progress(0.7f + 0.3f * i / lines.size)
                val base = line.ms / 1000.0 + shift
                val parts = pieces(line)
                val s = DoubleArray(parts.size); val e = DoubleArray(parts.size)
                val w = window(lines, i, shift, seconds)
                if (w != null && parts.isNotEmpty()) {
                    val syll = IntArray(parts.size) { Syllables.count(parts[it]) }
                    val quick = Syllables.quick(parts)
                    val endOut = DoubleArray(1)
                    // a (bracketed ad-lib) line is sung over the line before it; any other line starts after the one before it stopped
                    val after = if (isAdlib(line.text) || prevEnd > base + 0.25) 0.0 else prevEnd
                    val q = Voice.nAlignLine(voice, w.first, w.second, after, base, 0.2, syll, quick, s, e, endOut)
                    if (q > 0) { found[i] = true; done++; prevEnd = endOut[0] }
                }
                allParts.add(parts); allS.add(s); allE.add(e)
            }
            progress(1f)
            return if (done > 0) Timed(lines, shift, residual, used[0], allParts, allS, allE, found) else null
        } finally { Voice.nFree(voice) }
    }
}
