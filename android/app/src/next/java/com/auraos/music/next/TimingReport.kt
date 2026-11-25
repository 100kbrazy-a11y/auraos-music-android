package com.auraos.music.next

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * "Export timing report": 20 seconds of the song exactly as the word timing hears it, with the lyrics before and after timing and a text report
 * (the same idea as the Mac's). For when the highlighted words look wrong and someone has to see what happened there.
 */
object TimingReport {
    private const val SECONDS = 20.0

    // the work runs here, not in the menu's own scope: that one is cancelled the moment the menu closes, which silently killed the report in 2.15.4/2.15.5
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(ctx: Context, s: Song, posMs: Long) {
        val app = ctx.applicationContext
        Toasts.show("Making the timing report…")
        scope.launch {
            val f = try { export(app, s, posMs) } catch (e: Throwable) { Diag.log("timing report failed: ${e.javaClass.simpleName} ${e.message}"); Toasts.show("Couldn't make the report: ${e.message ?: e.javaClass.simpleName}"); return@launch }
            if (f == null) { Toasts.show("Couldn't read this song for the report"); return@launch }
            try { Packs.share(app, f) } catch (e: Throwable) { Diag.log("timing report share failed: ${e.message}"); Toasts.show("Couldn't open the share sheet: ${e.message}") }
        }
    }

    private fun clock(t0: Double): String { val t = max(0.0, t0); val m = (t / 60).toInt(); return String.format(Locale.US, "%d:%05.2f", m, t - m * 60) }
    private fun safe(s: String) = s.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), " ").trim().ifEmpty { "song" }.take(40)

    private fun wav(p: Pcm, from: Int, to: Int): ByteArray {
        val n = max(0, to - from)
        val b = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + n * 4); b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(2); b.putInt(PcmLoader.RATE); b.putInt(PcmLoader.RATE * 4); b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(n * 4)
        for (i in from until to) { b.putShort(p.left[i]); b.putShort(p.right[i]) }
        return b.array()
    }

    /** builds the zip (in the share cache) and returns it, or null when the song can't be read */
    suspend fun export(ctx: Context, s: Song, posMs: Long): File? = withContext(Dispatchers.IO) {
        val rec = Store.meta.optJSONObject("lyrics")?.optJSONObject(s.id)
        val synced = rec?.optString("synced").orEmpty().let { if (it == "null") "" else it }
        val timed = rec?.optBoolean("aligned") == true
        val source = rec?.optString("syncedSrc").orEmpty().ifBlank { if (timed) "" else synced }
        val lines = Online.parseLrc(synced)
        val pcm = PcmLoader.load(File(Store.app.filesDir, s.path))
        val total = pcm.n.toDouble() / PcmLoader.RATE
        // the part around where you are listening now, in the song's own time
        val a = max(0.0, min(posMs / 1000.0 - 8.0, total - SECONDS)); val b = min(total, a + SECONDS)
        val name = "Strata timing - ${safe(s.title)} ${a.toInt()}-${b.toInt()} s"
        val offsets = Store.settings().optJSONObject("offsets")
        val off = offsets?.optDouble(s.id, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
        val r = StringBuilder()
        r.append("Strata Music for Android - timing report\n")
        r.append("App version: $ver (timing generation ${ForcedAligner.GEN})\n")
        r.append("Song: ${s.title} - ${s.artist} - ${s.album}\n")
        r.append("Length: ${clock(s.durMs / 1000.0)}, file type: ${s.ext}\n")
        r.append("Audio read as: ${pcm.info}\n")
        r.append("\nclip.wav is this song from ${clock(a)} to ${clock(b)} (${"%.1f".format(Locale.US, b - a)} s, 22 050 Hz stereo), exactly the audio the word timing listened to. Every time below is a time in the song, not in the clip.\n")
        r.append("Lyrics from: ${rec?.optString("source").orEmpty().ifBlank { "?" }}${if (rec?.optBoolean("own") == true) " (your file)" else ""}\n")
        r.append("Lyrics shifted by you: ${"%+.2f".format(Locale.US, off)} s (this is cleared when the words are timed) · Highlight timing setting: ${Cfg.lyricShift.v.value.toInt()} ms · silence cut at the start: ${"%.2f".format(Locale.US, s.trimStart)} s (${if (Prefs.trimSilence.value) "on" else "off"})\n")
        r.append("Word timing for this song: ${if (timed) "made by this phone (lyrics-timed.lrc)" else "not made (words are spread evenly over each line)"}${if (ForcedAligner.isTimed(s)) "" else "; generation ${rec?.optInt("alignGen", 0) ?: 0}"}\n")
        r.append("Speech model: ${if (rec?.optBoolean("speech") == true) "heard this song (its words are timed from the speech)" else if (rec?.optBoolean("speechTried") == true) "listened, but nothing was clear enough to use" else "not used for this song"} · on this phone: ${SpeechModel.ready} · switched on: ${Cfg.speechSync.v.value} · Bluetooth delay ${if (AudioRoute.bluetooth.value) "${AudioRoute.delayMs()} ms (earbuds/speaker connected)" else "none (no Bluetooth output)"}\n")
        if (timed) r.append("Engine header: ${synced.lineSequence().firstOrNull { it.startsWith("[strata:") }.orEmpty()}\n")
        r.append("\nLines in this part. Each word is start-end in seconds of the song; \"?\" means the end isn't known and the word runs to the next one.\n\n")
        var any = false
        for ((i, l) in lines.withIndex()) {
            if (l.text.isEmpty()) continue
            val t = l.ms / 1000.0 + off
            val next = if (i + 1 < lines.size) lines[i + 1].ms / 1000.0 + off else t + 6
            val stop = max(next, l.sungEnd / 1000.0 + off)
            if (stop < a || t > b) continue
            any = true
            r.append("[${clock(t)}] ${l.text}\n")
            r.append("    sung until ${clock(l.sungEnd / 1000.0 + off)}, next line at ${clock(next)}, word times from the file: ${if (l.tagged) "yes" else "no (estimated)"}\n")
            r.append("    " + l.words.joinToString(" | ") { w -> "${w.text.trim()} ${"%.2f".format(Locale.US, w.start / 1000.0 + off)}-${if (w.end >= 0) "%.2f".format(Locale.US, w.end / 1000.0 + off) else "?"}" } + "\n")
        }
        if (!any) r.append("(no lyrics lines fall in this part)\n")
        r.append("\nRecent log:\n").append(Diag.lines.value.filter { "word timing" in it }.takeLast(12).joinToString("\n")).append("\n")
        val out = File(ctx.cacheDir, "share/$name.zip")
        out.parentFile?.mkdirs(); out.delete()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun put(n: String, data: ByteArray) { z.putNextEntry(ZipEntry("$name/$n")); z.write(data); z.closeEntry() }
            put("clip.wav", wav(pcm, (a * PcmLoader.RATE).toInt(), (b * PcmLoader.RATE).toInt()))
            if (source.isNotBlank()) put("lyrics-source.lrc", source.toByteArray())
            if (timed) put("lyrics-timed.lrc", synced.toByteArray())
            put("report.txt", r.toString().toByteArray())
        }
        out
    }
}
