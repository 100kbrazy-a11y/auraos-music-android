package com.auraos.music.next

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.auraos.music.WebmOpus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Share as Music Pack: the same .zip format as the WebView app (musicpack.json, Music/Artist/Album/NN Title.ext, cover.jpg). */
object Packs {
    suspend fun build(title: String, songs: List<Song>, playlist: String?): File = withContext(Dispatchers.IO) {
        val out = File(Store.app.cacheDir, "share/${safeName(title, "Music")}-musicpack.zip")
        out.parentFile?.mkdirs(); out.delete()
        val names = HashSet<String>(); val coverDone = HashSet<String>()
        val man = JSONObject().put("format", "auraos-musicpack").put("version", 1).put("title", title)
            .put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())).put("made_with", "Strata Music for Android")
            .put("playlist", playlist ?: JSONObject.NULL)
        val list = JSONArray()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            songs.forEachIndexed { i, s ->
                Store.busy.value = "Packing ${i + 1} of ${songs.size}…"
                val f = File(Store.app.filesDir, s.path)
                if (!f.exists()) return@forEachIndexed
                // a YouTube song is Opus inside a .webm: in a pack it is an .opus (the same audio, moved into an Ogg file, with its tags and cover)
                val asOpus = s.ext == "webm" && try { WebmOpus.isWebm(f) && WebmOpus.hasOpus(f) } catch (_: Exception) { false }
                var rel = Store.organizedPath(s.albumartist, s.artist, s.album, s.track, s.disc, s.title, if (asOpus) "opus" else s.ext.ifEmpty { "m4a" })
                val base = rel.substringBeforeLast('.'); val ext = rel.substringAfterLast('.'); var n = 2
                while (!names.add(rel.lowercase())) { rel = "$base ($n).$ext"; n++ }
                var tmp: File? = null
                var data = f
                if (asOpus) try {
                    val t = File(Store.app.cacheDir, "share/pack-${System.nanoTime()}.opus"); t.parentFile?.mkdirs()
                    WebmOpus.toOgg(f, t, Exporter.packTags(s), Exporter.coverBytes(s)); tmp = t; data = t
                } catch (_: Exception) { rel = rel.substringBeforeLast('.') + ".webm" }      // couldn't move it: keep the original file
                z.putNextEntry(ZipEntry("Music/$rel")); data.inputStream().use { it.copyTo(z) }; z.closeEntry()
                tmp?.delete()
                val folder = rel.substringBeforeLast('/')
                if (s.cover.isNotEmpty() && coverDone.add(folder)) {
                    val cf = Store.coverFile(s.cover)
                    if (cf.exists()) { z.putNextEntry(ZipEntry("Music/$folder/cover.jpg")); cf.inputStream().use { it.copyTo(z) }; z.closeEntry() }
                }
                val o = JSONObject()
                for (k in listOf("title", "artist", "album", "albumartist", "track", "disc", "year", "genre")) { val v = s.j.opt(k); if (v != null && v != "" && v != 0) o.put(k, v) }
                o.put("file", rel).put("duration", Math.round(s.durMs / 10.0) / 100.0)
                list.put(o)
            }
            if (list.length() == 0) throw RuntimeException("There's nothing to share.")
            man.put("songs", list)
            z.putNextEntry(ZipEntry("musicpack.json")); z.write(man.toString(1).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("README.txt"))
            z.write("$title — a music pack made with Strata Music.\n\nTo add it: open Strata Music → Settings → Add Music Pack.\nOr just unzip it: the songs are in the Music folder, sorted by artist and album.\n".toByteArray())
            z.closeEntry()
        }
        Store.busy.value = ""
        out
    }

    fun share(c: Context, f: File, mime: String = "application/zip") {
        val u = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** a single song, shared as its real audio file */
    fun shareSong(c: Context, s: Song) {
        val src = File(Store.app.filesDir, s.path)
        val f = File(Store.app.cacheDir, "share/${safeName(s.artist.ifBlank { "Unknown" })} - ${safeName(s.title)}.${s.ext}")
        f.parentFile?.mkdirs(); src.copyTo(f, true)
        share(c, f, s.j.optString("type", "audio/*"))
    }
}
