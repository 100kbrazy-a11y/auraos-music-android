package com.auraos.music.next

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// The pure part of word timing (no Android in it, so it can be run and tested on a computer): lines the lyrics up against what the voice finder reports.

/** the whole song as 22.05 kHz stereo 16-bit */
class Pcm(val left: ShortArray, val right: ShortArray, val n: Int)

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
    private fun sign(x: Double) = String.format(Locale.US, "%+.2f", x)
    fun isAdlib(s: String): Boolean { val t = s.trim(); return t.length > 2 && t.startsWith("(") && t.endsWith(")") }

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
    fun align(pcm: Pcm, lines: List<Online.Line>, offset: Double, progress: (Float) -> Unit = {}): String? {
        val seconds = pcm.n.toDouble() / RATE
        progress(0.35f)
        val voice = Voice.nAnalyze(pcm.left, pcm.right, pcm.n, RATE)
        if (voice == 0L) return null
        progress(0.7f)
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
                if (i % 4 == 0) progress(0.7f + 0.3f * i / lines.size)
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
            progress(1f)
            return if (done > 0) out.toString() else null
        } finally { Voice.nFree(voice) }
    }
}
