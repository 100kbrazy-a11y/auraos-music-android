package com.auraos.music.next

import org.json.JSONArray
import java.util.zip.InflaterInputStream

/**
 * Word-timed lyrics from the sources that have them (NetEase "yrc", Kugou "krc", Musixmatch "richsync"), turned into the
 * enhanced LRC the app already reads: `[mm:ss.xx]<mm:ss.xx>word <mm:ss.xx>word<mm:ss.xx>` (a time with no word after it
 * is where the word before it stops being sung).
 */
object WordLyrics {
    private class W(val text: String, val start: Long, val end: Long)
    private fun st(ms: Long) = WordTiming.stamp(ms / 1000.0)

    private fun render(lines: List<Pair<Long, List<W>>>): String? {
        val sb = StringBuilder(); var n = 0
        for ((start, ws) in lines.sortedBy { it.first }) {
            val words = ws.filter { it.text.isNotEmpty() }
            if (words.isEmpty()) continue
            sb.append('[').append(st(start)).append(']')
            words.forEachIndexed { i, w ->
                sb.append('<').append(st(w.start)).append('>').append(w.text)
                val nextStart = if (i + 1 < words.size) words[i + 1].start else Long.MAX_VALUE
                if (w.end > w.start && (i == words.size - 1 || w.end < nextStart - 40)) sb.append('<').append(st(w.end)).append('>')
            }
            sb.append('\n'); n++
        }
        return if (n >= 2) sb.toString().trimEnd() else null
    }

    // ---- NetEase: [lineStart,lineDur](wordStart,wordDur,0)word(wordStart,wordDur,0)word ... (all times in ms from the start of the song)
    private val yrcHead = Regex("^\\[(\\d+),(\\d+)]")
    private val yrcWord = Regex("\\((\\d+),(\\d+),-?\\d+\\)")
    fun yrc(t: String): String? {
        val lines = ArrayList<Pair<Long, List<W>>>()
        for (raw in t.lines()) {
            val line = raw.trimStart()
            val h = yrcHead.find(line) ?: continue
            val rest = line.substring(h.range.last + 1)
            val ms = yrcWord.findAll(rest).toList()
            if (ms.isEmpty()) continue
            val ws = ms.mapIndexed { i, x ->
                val txt = rest.substring(x.range.last + 1, if (i + 1 < ms.size) ms[i + 1].range.first else rest.length)
                val s0 = x.groupValues[1].toLong()
                W(txt, s0, s0 + x.groupValues[2].toLong())
            }
            lines.add(h.groupValues[1].toLong() to ws)
        }
        return render(lines)
    }

    // ---- Kugou: the .krc file is "krc1" + XOR(key) + zlib; inside, [lineStart,lineDur]<offset,dur,0>word<offset,dur,0>word (offsets from the line's start)
    private val krcKey = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
    fun krcDecode(b: ByteArray): String? {
        if (b.size < 8) return null
        val d = ByteArray(b.size - 4) { i -> (b[i + 4].toInt() xor krcKey[i % 16]).toByte() }
        return try { InflaterInputStream(d.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8).removePrefix("﻿") } catch (_: Exception) { null }
    }
    private val krcWord = Regex("<(\\d+),(\\d+),-?\\d+>")
    fun krc(t: String): String? {
        val lines = ArrayList<Pair<Long, List<W>>>()
        for (raw in t.lines()) {
            val line = raw.trimStart()
            val h = yrcHead.find(line) ?: continue
            val base = h.groupValues[1].toLong()
            val rest = line.substring(h.range.last + 1)
            val ms = krcWord.findAll(rest).toList()
            if (ms.isEmpty()) continue
            val ws = ms.mapIndexed { i, x ->
                val txt = rest.substring(x.range.last + 1, if (i + 1 < ms.size) ms[i + 1].range.first else rest.length)
                val s0 = base + x.groupValues[1].toLong()
                W(txt, s0, s0 + x.groupValues[2].toLong())
            }
            lines.add(base to ws)
        }
        return render(lines)
    }

    // ---- Musixmatch: "richsync" is [{ts, te, l:[{c:"word", o:secondsIntoLine}], x:"the line"}]; plain subtitles are [{text, time:{total:seconds}}]
    fun richsync(body: String): String? {
        val a = try { JSONArray(body) } catch (_: Exception) { return null }
        val lines = ArrayList<Pair<Long, List<W>>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val ts = o.optDouble("ts", Double.NaN); val te = o.optDouble("te", Double.NaN)
            val l = o.optJSONArray("l") ?: continue
            if (ts.isNaN()) continue
            val ws = ArrayList<W>()
            for (k in 0 until l.length()) {
                val w = l.optJSONObject(k) ?: continue
                val c = w.optString("c")
                if (c.isEmpty()) continue
                if (c.isBlank()) { if (ws.isNotEmpty()) { val p = ws.removeAt(ws.size - 1); ws.add(W(p.text + c, p.start, p.end)) }; continue }
                ws.add(W(c, ((ts + w.optDouble("o", 0.0)) * 1000).toLong(), -1))
            }
            if (ws.isEmpty()) continue
            if (!te.isNaN()) { val p = ws.removeAt(ws.size - 1); ws.add(W(p.text, p.start, (te * 1000).toLong())) }
            lines.add((ts * 1000).toLong() to ws)
        }
        return render(lines)
    }
    fun mxmLines(body: String): String? {
        val a = try { JSONArray(body) } catch (_: Exception) { return null }
        val sb = StringBuilder(); var n = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val total = o.optJSONObject("time")?.optDouble("total", Double.NaN) ?: Double.NaN
            if (total.isNaN()) continue
            sb.append('[').append(WordTiming.stamp(total)).append(']').append(o.optString("text")).append('\n'); n++
        }
        return if (n >= 2) sb.toString().trimEnd() else null
    }
}
