package com.auraos.music.next

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Precise word timing, made at home and light on purpose: no model to download (the same engine as the Mac app).
// 1. the song is read as 22.05 kHz stereo; cpp/voice keeps the part that is sung (centred, steady, not a drum hit) and finds where the voice is and where its syllables begin
// 2. each lyric line is lined up with that: its words are matched to the syllable starts in the stretch where it is sung
// The lyrics' own line times are the authority: a line never starts before its stamp or runs past the next line's, so words can't be mixed up between lines.
// The words' times are written as an enhanced .lrc (a time before each word, and a time with no word after it where the singing stops).

/** the whole song as 22.05 kHz stereo 16-bit */
class Pcm(val left: ShortArray, val right: ShortArray, val n: Int)

object PcmLoader {
    const val RATE = 22050
    const val MAX_SECONDS = 6 * 60

    fun load(file: File): Pcm {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("no audio in this file")
            ex.selectTrack(track)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durUs > MAX_SECONDS * 1_000_000L) throw IllegalStateException("too long to analyse")
            var cap = ((durUs / 1_000_000.0) * RATE).toInt() + RATE * 2
            if (cap < RATE * 20) cap = RATE * 60
            var left = ShortArray(cap); var right = ShortArray(cap); var n = 0
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var isFloat = false
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()
            // box-filter resampling to 22.05 kHz: every input sample adds to the output sample it falls in, weighted by how much of it falls there
            var binWidth = inRate / RATE.toDouble()
            var filled = 0.0; var accL = 0.0; var accR = 0.0
            val info = MediaCodec.BufferInfo()
            var eosIn = false; var eosOut = false
            val deadline = System.currentTimeMillis() + 180_000L
            while (!eosOut) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("decoding took too long")
                if (!eosIn) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosIn = true }
                        else { dec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = dec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    binWidth = inRate / RATE.toDouble()
                } else if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                    val ob = dec.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val sh = if (isFloat) null else ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val fl = if (isFloat) ob.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer() else null
                        val total = if (sh != null) sh.remaining() else fl!!.remaining()
                        var i = 0
                        while (i + ch <= total) {
                            val l = if (sh != null) sh.get(i).toDouble() else fl!!.get(i) * 32768.0
                            val r = if (ch > 1) (if (sh != null) sh.get(i + 1).toDouble() else fl!!.get(i + 1) * 32768.0) else l
                            i += ch
                            var w = 1.0
                            while (w > 1e-9) {
                                val take = min(w, binWidth - filled)
                                accL += l * take; accR += r * take; filled += take; w -= take
                                if (filled >= binWidth - 1e-9) {
                                    if (n >= left.size) { left = left.copyOf(left.size + RATE * 30); right = right.copyOf(right.size + RATE * 30) }
                                    left[n] = (accL / binWidth).roundToInt().coerceIn(-32768, 32767).toShort()
                                    right[n] = (accR / binWidth).roundToInt().coerceIn(-32768, 32767).toShort()
                                    n++; accL = 0.0; accR = 0.0; filled = 0.0
                                }
                            }
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                }
            }
            return Pcm(left, right, n)
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) { }
            try { ex.release() } catch (_: Exception) { }
        }
    }
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

object ForcedAligner {
    /** bump to have every song's word timing made again */
    const val GEN = 2
    private const val MAX_MS = PcmLoader.MAX_SECONDS * 1000L
    private val judged = HashMap<String, Int>()     // song id -> hash of the lyrics we looked at and decided there was nothing to do for

    private fun stamp(t0: Double): String {
        val t = max(0.0, t0)
        val m = (t / 60).toInt()
        return String.format(Locale.US, "%02d:%05.2f", m, t - m * 60)
    }
    private fun sign(x: Double) = String.format(Locale.US, "%+.2f", x)

    private fun isAdlib(s: String): Boolean { val t = s.trim(); return t.length > 2 && t.startsWith("(") && t.endsWith(")") }

    /** the lyrics as the source gave them: the original line times, no word tags */
    private fun sourceOf(rec: JSONObject): String? {
        val kept = rec.optString("syncedSrc")
        if (kept.isNotBlank()) return kept
        val synced = rec.optString("synced")
        if (synced.isBlank() || synced == "null") return null
        if (!synced.contains("<")) return synced
        // made by the first version of this: its line stamps are still the originals, so the plain lines can be rebuilt. Anything else with word times came that way, leave it.
        if (rec.optBoolean("aligned")) return Online.parseLrc(synced).joinToString("\n") { "[${stamp(it.ms / 1000.0)}]${it.text}" }
        return null
    }

    /** Is there a song with synced lyrics that hasn't had its words timed by this version yet? */
    fun needsAlign(s: Song): Boolean {
        val rec = Store.meta.optJSONObject("lyrics")?.optJSONObject(s.id) ?: return false
        val h = rec.optString("synced").hashCode()
        if (judged[s.id] == h) return false
        fun no(): Boolean { judged[s.id] = h; return false }
        if (rec.optInt("alignGen", 0) >= GEN || rec.optBoolean("instrumental") || rec.optBoolean("none")) return no()
        if (s.durMs < 8_000L || s.durMs > MAX_MS) return no()
        if (!Voice.load()) return no()
        if (Prefs.saving.value) return false                    // battery saver: not now (try again later)
        if (!File(Store.app.filesDir, s.path).exists()) return no()
        val src = sourceOf(rec) ?: return no()
        return if (Online.parseLrc(src).size >= 2) true else no()
    }

    /** the slot a line has in the lyrics (audio time): from its stamp to the next line's. A "(ad-lib)" line sung over the top doesn't end the line before it. */
    private fun slot(lines: List<Online.Line>, i: Int, offset: Double): Pair<Double, Double> {
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
    private fun pieces(line: Online.Line): List<String> {
        if (!line.text.contains(" ") && line.text.all { it.code <= 0x24F }) return listOf(line.text)
        return line.words.map { it.text }
    }

    /** aligns a whole song: an enhanced .lrc in the song's own audio time, or null if no line could be lined up */
    private fun align(pcm: Pcm, lines: List<Online.Line>, offset: Double): String? {
        val seconds = pcm.n.toDouble() / PcmLoader.RATE
        val voice = Voice.nAnalyze(pcm.left, pcm.right, pcm.n, PcmLoader.RATE)
        if (voice == 0L) return null
        try {
            // The lyrics' times say which line is which, but a file can be a little early or late as a whole (or carry an old nudge of yours that no longer fits).
            // Where the voice comes back in after each pause says how far off it is; if that is steady, every line moves by the same amount.
            val bases = lines.filter { it.text.isNotEmpty() && it.words.isNotEmpty() && !isAdlib(it.text) }.map { it.ms / 1000.0 + offset }.toDoubleArray()
            val used = IntArray(1)
            val residual = if (bases.isEmpty()) 0.0 else Voice.nResidual(voice, bases, used)
            val shift = (offset + residual).coerceIn(-3.0, 3.0)
            val out = StringBuilder("[strata:shift=${sign(shift)} found=${sign(residual)} from=${used[0]}]\n")
            var prevStamp = 0.0; var prevEnd = 0.0; var done = 0
            for ((i, line) in lines.withIndex()) {
                val base = line.ms / 1000.0 + shift
                val parts = pieces(line)
                var s = DoubleArray(parts.size); var e = DoubleArray(parts.size)
                var found = false
                val w = window(lines, i, shift, seconds)
                if (w != null && parts.isNotEmpty()) {
                    val syll = IntArray(parts.size) { Syllables.count(parts[it]) }
                    val quick = Syllables.quick(parts)
                    val endOut = DoubleArray(1)
                    // a (bracketed ad-lib) line is sung over the line before it; any other line starts after the one before it stopped
                    val after = if (isAdlib(line.text) || prevEnd > base + 0.25) 0.0 else prevEnd
                    val q = Voice.nAlignLine(voice, w.first, w.second, after, base, 0.2, syll, quick, s, e, endOut)
                    if (q > 0) { found = true; prevEnd = endOut[0] }
                }
                if (found) {
                    // inside the line's own slot, and never before the one before it
                    val sl = slot(lines, i, shift)
                    val lastOK = max(sl.first, sl.second - 0.12)
                    val first = max(sl.first, prevStamp)
                    for (k in s.indices) {
                        s[k] = max(s[k] + 0.03, first)
                        if (k > 0) s[k] = max(s[k], s[k - 1] + 0.06)        // words never pile up on the same instant
                    }
                    val last = s.last()
                    if (last > lastOK && last > first) for (k in s.indices) s[k] = first + (s[k] - first) * (lastOK - first) / (last - first)
                    done++
                    prevStamp = first
                    out.append("[").append(stamp(first)).append("]")
                    for ((k, p) in parts.withIndex()) {
                        out.append("<").append(stamp(s[k])).append(">").append(p)
                        // where the singing of a word stops (a breath, a pause, the end of the line), written as a time with no word after it, so nothing is lit while nobody sings
                        val nextStart = if (k + 1 < s.size) s[k + 1] else sl.second
                        val stop = min(max(e[k] + 0.03, s[k] + 0.1), nextStart)
                        if (stop < nextStart - 0.15 || k + 1 == s.size) out.append("<").append(stamp(stop)).append(">")
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
            return if (done > 0) out.toString() else null
        } finally { Voice.nFree(voice) }
    }

    /** Times the words of one song on this phone and saves them (as enhanced LRC) in place of the lyrics' own line-only version. Returns true when the words were timed. */
    suspend fun alignSong(s: Song): Boolean = withContext(Dispatchers.Default) {
        val rec = Store.meta.optJSONObject("lyrics")?.optJSONObject(s.id) ?: return@withContext false
        fun mark() { rec.put("alignGen", GEN); Store.save() }       // a failed try isn't repeated either
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val src = sourceOf(rec)
            val lines = if (src != null) Online.parseLrc(src) else emptyList()
            if (src == null || lines.size < 2 || !Voice.load()) { mark(); return@withContext false }
            val offsets = Store.settings().optJSONObject("offsets")
            val offset = offsets?.optDouble(s.id, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
            val pcm = PcmLoader.load(File(Store.app.filesDir, s.path))
            if (pcm.n < PcmLoader.RATE * 8) { mark(); return@withContext false }
            val out = align(pcm, lines, offset)
            if (out == null) { mark(); return@withContext false }
            rec.put("syncedSrc", src); rec.put("synced", out.trimEnd()); rec.put("aligned", true); rec.put("alignGen", GEN)
            offsets?.remove(s.id)            // the times are in the song's own time now: any nudge made for the old ones is already in them
            Store.save()
            withContext(Dispatchers.Main) { Lyr.bump() }
            true
        } catch (e: Throwable) {
            Diag.log("word timing ${s.title}: ${e.message}")
            mark()
            false
        }
    }
}
