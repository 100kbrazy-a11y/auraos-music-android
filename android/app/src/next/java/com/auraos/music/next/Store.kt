package com.auraos.music.next

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

data class Song(
    val id: String, val title: String, val artist: String, val album: String, val track: Int,
    val durMs: Long, val path: String, val cover: String, val added: Long,
)
data class Playlist(val id: String, val name: String, val ids: List<String>)
class Meta(val title: String? = null, val artist: String? = null, val album: String? = null, val track: Int? = null, val cover: ByteArray? = null)

/**
 * The native library: one small JSON file (next-library.json) plus the song files under filesDir/Songs/Artist/Album/NN Title.ext
 * (same folder layout as the WebView app, so Music Packs and a later data migration line up).
 */
object Store {
    lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val songs = MutableStateFlow<List<Song>>(emptyList())
    val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val favs = MutableStateFlow<Set<String>>(emptySet())
    val busy = MutableStateFlow("")
    private val lock = Any()
    private val file get() = File(app.filesDir, "next-library.json")

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        try {
            if (!file.exists()) return
            val j = JSONObject(file.readText())
            val sa = j.optJSONArray("songs") ?: JSONArray()
            songs.value = (0 until sa.length()).map { i ->
                val o = sa.getJSONObject(i)
                Song(o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optInt("track"),
                    o.optLong("dur"), o.getString("path"), o.optString("cover"), o.optLong("added"))
            }
            val pa = j.optJSONArray("playlists") ?: JSONArray()
            playlists.value = (0 until pa.length()).map { i ->
                val o = pa.getJSONObject(i)
                val ia = o.optJSONArray("ids") ?: JSONArray()
                Playlist(o.getString("id"), o.optString("name"), (0 until ia.length()).map { ia.getString(it) })
            }
            val fa = j.optJSONArray("favs") ?: JSONArray()
            favs.value = (0 until fa.length()).map { fa.getString(it) }.toSet()
        } catch (_: Exception) { /* unreadable file: start empty, the song files are still on disk */ }
    }

    private fun save() {
        scope.launch {
            synchronized(lock) {
                try {
                    val j = JSONObject()
                    j.put("version", 1)
                    j.put("songs", JSONArray().also { a ->
                        for (s in songs.value) a.put(JSONObject().put("id", s.id).put("title", s.title).put("artist", s.artist).put("album", s.album)
                            .put("track", s.track).put("dur", s.durMs).put("path", s.path).put("cover", s.cover).put("added", s.added))
                    })
                    j.put("playlists", JSONArray().also { a ->
                        for (p in playlists.value) a.put(JSONObject().put("id", p.id).put("name", p.name).put("ids", JSONArray(p.ids)))
                    })
                    j.put("favs", JSONArray(favs.value.toList()))
                    val tmp = File(app.filesDir, "next-library.json.tmp")
                    tmp.writeText(j.toString())
                    if (!tmp.renameTo(file)) { file.writeText(j.toString()); tmp.delete() }
                } catch (_: Exception) { }
            }
        }
    }

    fun safe(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').ifEmpty { "Unknown" }.take(80)

    /** Reads the tags of [tmp], moves it into Songs/Artist/Album/NN Title.ext and adds it to the library. */
    fun register(tmp: File, ext: String, hint: Meta? = null): Song? {
        var title = hint?.title; var artist = hint?.artist; var album = hint?.album; var track = hint?.track
        var cover = hint?.cover; var dur = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(tmp.path)
            if (title.isNullOrBlank()) title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            if (artist.isNullOrBlank()) artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            if (album.isNullOrBlank()) album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            if (track == null) track = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull()
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (cover == null) cover = r.embeddedPicture
        } catch (_: Exception) {
        } finally { try { r.release() } catch (_: Exception) { } }
        val name = tmp.nameWithoutExtension
        val t = title?.takeIf { it.isNotBlank() } ?: name
        val a = artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
        val al = album?.takeIf { it.isNotBlank() } ?: "Unknown Album"
        val nn = if (track != null && track > 0) String.format("%02d ", track) else ""
        val dir = File(app.filesDir, "Songs/${safe(a)}/${safe(al)}")
        dir.mkdirs()
        var dest = File(dir, "$nn${safe(t)}.$ext")
        var n = 2
        while (dest.exists()) { dest = File(dir, "$nn${safe(t)} ($n).$ext"); n++ }
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        val id = UUID.randomUUID().toString()
        var coverPath = ""
        if (cover != null && cover.isNotEmpty()) {
            try {
                val cf = File(app.filesDir, "covers/$id.jpg"); cf.parentFile?.mkdirs(); cf.writeBytes(cover)
                coverPath = "covers/$id.jpg"
            } catch (_: Exception) { }
        }
        val s = Song(id, t, a, al, track ?: 0, dur, dest.relativeTo(app.filesDir).path, coverPath, System.currentTimeMillis())
        synchronized(lock) { songs.value = songs.value + s }
        save()
        return s
    }

    private fun displayName(u: Uri): String =
        try { app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } catch (_: Exception) { null }
            ?: u.lastPathSegment ?: "song"

    /** Songs picked in the system file picker */
    fun importSongs(uris: List<Uri>) {
        scope.launch {
            var done = 0
            for (u in uris) {
                busy.value = "Adding ${done + 1} of ${uris.size}…"
                try {
                    val name = displayName(u)
                    val ext = name.substringAfterLast('.', "m4a").lowercase().take(5)
                    val tmp = File(app.cacheDir, "imp-${System.nanoTime()}.$ext")
                    app.contentResolver.openInputStream(u)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    register(tmp, ext)
                } catch (_: Exception) { }
                done++
            }
            busy.value = ""
        }
    }

    /** A Music Pack (.zip: Music/Artist/Album/NN Title.ext) */
    fun importPack(u: Uri) {
        scope.launch {
            busy.value = "Opening the Music Pack…"
            var n = 0
            try {
                app.contentResolver.openInputStream(u)?.use { raw ->
                    ZipInputStream(raw.buffered()).use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            val nm = e.name
                            if (e.isDirectory || !nm.startsWith("Music/")) continue
                            val ext = nm.substringAfterLast('.', "").lowercase()
                            if (ext !in setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "webm", "wma")) continue
                            val tmp = File(app.cacheDir, "pack-${System.nanoTime()}.$ext")
                            tmp.outputStream().use { o -> z.copyTo(o) }
                            n++; busy.value = "Added $n songs…"
                            register(tmp, ext)
                        }
                    }
                }
            } catch (_: Exception) { }
            busy.value = ""
        }
    }

    fun remove(s: Song) {
        File(app.filesDir, s.path).delete()
        if (s.cover.isNotEmpty()) File(app.filesDir, s.cover).delete()
        songs.value = songs.value.filter { it.id != s.id }
        playlists.value = playlists.value.map { it.copy(ids = it.ids.filter { x -> x != s.id }) }
        favs.value = favs.value - s.id
        save()
    }
    fun toggleFav(id: String) { favs.value = if (id in favs.value) favs.value - id else favs.value + id; save() }
    fun newPlaylist(name: String) { playlists.value = playlists.value + Playlist(UUID.randomUUID().toString(), name.ifBlank { "Playlist" }, emptyList()); save() }
    fun addToPlaylist(pid: String, sid: String) {
        playlists.value = playlists.value.map { if (it.id == pid && sid !in it.ids) it.copy(ids = it.ids + sid) else it }; save()
    }
    fun deletePlaylist(pid: String) { playlists.value = playlists.value.filter { it.id != pid }; save() }
}
