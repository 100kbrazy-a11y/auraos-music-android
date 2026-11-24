package com.auraos.music.next

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraos.music.AuraExport
import com.auraos.music.FlacSink
import com.auraos.music.WavSink
import com.auraos.music.WebmOpus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a song can be exported as. */
enum class ExFmt(val label: String, val ext: String, val mime: String, val lossy: Boolean) {
    Original("Original", "", "audio/*", false),
    Opus("Opus", "opus", "audio/ogg", true),
    M4a("M4A (AAC)", "m4a", "audio/mp4", true),
    Flac("FLAC", "flac", "audio/flac", false),
    Wav("WAV", "wav", "audio/wav", false),
}

object Exporter {
    val kbpsChoices = listOf(128, 192, 256)

    fun packTags(s: Song): Map<String, String> = tags(s)
    private fun tags(s: Song): Map<String, String> = LinkedHashMap<String, String>().also { m ->
        if (s.title.isNotBlank()) m["TITLE"] = s.title
        if (s.artist.isNotBlank()) m["ARTIST"] = s.artist
        if (s.album.isNotBlank()) m["ALBUM"] = s.album
        if (s.albumartist.isNotBlank()) m["ALBUMARTIST"] = s.albumartist
        if (s.year.isNotBlank()) m["DATE"] = s.year
        if (s.genre.isNotBlank()) m["GENRE"] = s.genre
        if (s.track > 0) m["TRACKNUMBER"] = s.track.toString()
        if (s.disc > 0) m["DISCNUMBER"] = s.disc.toString()
    }

    fun coverBytes(s: Song): ByteArray? = if (s.cover.isEmpty()) null else try { Store.coverFile(s.cover).takeIf { it.exists() }?.readBytes() } catch (_: Exception) { null }

    /** the file the song becomes (in the cache); copied, remuxed or re-encoded only when it has to be */
    suspend fun make(s: Song, fmt: ExFmt, kbps: Int, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val src = File(Store.app.filesDir, s.path)
        if (!src.exists()) throw RuntimeException("The song's file is missing")
        val dir = File(Store.app.cacheDir, "export").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val base = "${safeName(s.artist.ifBlank { "Unknown Artist" })} - ${safeName(s.title)}"
        val se = s.ext
        fun copy(ext: String): File = File(dir, "$base.$ext").also { src.copyTo(it, true) }
        val isOgg = se == "opus" || se == "ogg"
        when (fmt) {
            ExFmt.Original -> copy(se.ifEmpty { "m4a" })
            ExFmt.Opus -> when {
                se == "opus" -> copy("opus")
                WebmOpus.isWebm(src) && WebmOpus.hasOpus(src) -> File(dir, "$base.opus").also { WebmOpus.toOgg(src, it, tags(s), coverBytes(s)) }      // the same audio, no re-encoding
                !isOgg && AuraExport.canEncodeOpus() -> File(dir, "$base.opus").also { AuraExport.encode(src, it, true, kbps) }
                else -> throw RuntimeException("This phone can't make Opus files from this song (it needs Android 10 or newer). Pick M4A, FLAC or WAV.")
            }
            ExFmt.M4a -> if (se == "m4a" || se == "mp4") copy("m4a") else File(dir, "$base.m4a").also { AuraExport.encode(src, it, false, kbps) }
            ExFmt.Flac -> if (se == "flac") copy("flac") else File(dir, "$base.flac").also { out ->
                AuraExport.decodeTo(src, { r, c -> FlacSink(out, r, c, tags(s), coverBytes(s)) }) { onProgress(it) }
            }
            ExFmt.Wav -> if (se == "wav") copy("wav") else File(dir, "$base.wav").also { out ->
                AuraExport.decodeTo(src, { r, c -> WavSink(out, r, c, tags(s)) }) { onProgress(it) }
            }
        }
    }

    /** into the phone's Music folder (Music/Strata Music) where every player finds it; false before Android 10 (use Share there) */
    suspend fun saveToMusic(ctx: Context, f: File, mime: String, s: Song): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext false
        val r = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Strata Music")
            put(MediaStore.Audio.Media.TITLE, s.title)
            if (s.artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, s.artist)
            if (s.album.isNotBlank()) put(MediaStore.Audio.Media.ALBUM, s.album)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: throw RuntimeException("The phone wouldn't make the file in Music")
        try {
            r.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            r.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { try { r.delete(uri, null, null) } catch (_: Exception) { }; throw e }
        true
    }
}

/** Export a song in the format you want: the same file, Opus, M4A, FLAC or WAV. */
@Composable
fun ExportSheet(s: Song) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var fi by remember { mutableStateOf(0) }
    var qi by remember { mutableStateOf(1) }
    var working by remember { mutableStateOf(false) }
    var prog by remember { mutableStateOf(0f) }
    val fmt = ExFmt.values()[fi]
    val webmOpus = remember(s.id) { s.ext == "webm" && try { WebmOpus.hasOpus(File(Store.app.filesDir, s.path)) } catch (_: Exception) { false } }
    fun go(share: Boolean) {
        if (working) return
        working = true; prog = 0f
        scope.launch {
            try {
                val f = Exporter.make(s, fmt, Exporter.kbpsChoices[qi]) { prog = it }
                val mime = if (fmt == ExFmt.Original) (s.j.optString("type").ifBlank { "audio/*" }) else fmt.mime
                if (share) { Dialogs.close(); Packs.share(ctx, f, mime) }
                else if (Exporter.saveToMusic(ctx, f, mime, s)) { Dialogs.close(); Toasts.show("Saved to Music › Strata Music") }
                else { Dialogs.close(); Packs.share(ctx, f, mime) }
            } catch (e: Exception) { Diag.log("export: ${e.message}"); Toasts.show("Couldn't export: ${e.message}") }
            working = false
        }
    }
    SheetScroll {
        T("Export", 22.sp, W700, modifier = Modifier.padding(start = 24.dp))
        T("${s.title}${if (s.artist.isNotBlank()) " · ${s.artist}" else ""}", 14.sp, color = C.sub, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp))
        SChoice("Format", ExFmt.values().map { it.label }, fi) { fi = it }
        if (fmt.lossy) SChoice("Quality", Exporter.kbpsChoices.map { "$it kbps" }, qi) { qi = it }
        val note = when (fmt) {
            ExFmt.Original -> "The file exactly as it is in your library (${s.ext.ifEmpty { "audio" }}). Nothing is converted."
            ExFmt.Opus -> if (s.ext == "opus") "It is already Opus: the file is copied as it is."
                else if (webmOpus) "This song's audio is Opus inside a .webm. It is moved into an .opus file without re-encoding, so there is no quality loss, and the title, artist and cover go in."
                else "Re-encoded to Opus with the phone's encoder (Android 10 or newer). Small files, very good quality."
            ExFmt.M4a -> if (s.ext == "m4a" || s.ext == "mp4") "It is already M4A: the file is copied as it is." else "Re-encoded to AAC in an .m4a with the phone's encoder."
            ExFmt.Flac -> "Lossless, with the title, artist, album and cover inside. A song that is already lossy won't sound better, only bigger."
            ExFmt.Wav -> "Uncompressed. Large files, readable by anything. A song that is already lossy won't sound better."
        }
        T(note, 13.sp, color = C.sub, lines = 4, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        if (working) T(if (prog > 0f) "Working… ${(prog * 100).toInt()}%" else "Working…", 14.sp, W650, color = accent, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn(if (Build.VERSION.SDK_INT >= 29) "Save to Music" else "Share", if (Build.VERSION.SDK_INT >= 29) Ic.Download else Ic.Share, enabled = !working) { go(false) }
            if (Build.VERSION.SDK_INT >= 29) Btn("Share", Ic.Share, tonal = true, enabled = !working) { go(true) }
        }
    }
}
