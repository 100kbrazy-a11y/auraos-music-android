package com.auraos.music.next

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * A song record. It is the SAME JSON the WebView app keeps in IndexedDB (id, file, title, artist, album, albumartist,
 * track, disc, year, genre, duration (s), cover, added, size, type, trimStart, trimEnd, tempo …), kept whole, so a
 * migration is a straight copy and no field is ever dropped. The audio lives at filesDir/Songs/<file>.
 */
class Song(val j: JSONObject) {
    val id: String = j.optString("id")
    val file: String = j.optString("file")
    val path: String get() = "Songs/$file"
    val title: String = j.optString("title").ifBlank { file.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Untitled" } }
    val artist: String = j.optString("artist")
    val album: String = j.optString("album")
    val albumartist: String = j.optString("albumartist")
    val track: Int = j.optInt("track")
    val disc: Int = j.optInt("disc")
    val year: String = j.optString("year").let { if (it == "0") "" else it }
    val genre: String = j.optString("genre")
    val durMs: Long = j.optDouble("duration", 0.0).let { if (it.isNaN()) 0L else (it * 1000).toLong() }
    val cover: String = j.optString("cover")
    val coverPath: String get() = if (cover.isEmpty()) "" else "covers/$cover.jpg"
    val added: Long = j.optLong("added")
    val trimStart: Double = j.optDouble("trimStart", 0.0)
    val trimEnd: Double = j.optDouble("trimEnd", 0.0)
    val albumArtist: String get() = primaryArtist(albumartist).ifBlank { primaryArtist(artist) }.ifBlank { "Unknown Artist" }
    val albumName: String get() = album.ifBlank { "Unknown Album" }
    val albumKey: String get() = "$albumArtist\u0001$albumName"
    val ext: String get() = file.substringAfterLast('.', "").lowercase()
    fun copy(f: JSONObject.() -> Unit): Song = Song(JSONObject(j.toString()).apply(f))
}

class Album(val key: String, val title: String, val artist: String, val songs: List<Song>) {
    val cover: Song? = songs.firstOrNull { it.cover.isNotEmpty() }
    val year: String = songs.firstOrNull { it.year.isNotBlank() }?.year ?: ""
    val added: Long = songs.maxOfOrNull { it.added } ?: 0
    val durMs: Long = songs.sumOf { it.durMs }
}

class Meta(
    val title: String? = null, val artist: String? = null, val album: String? = null, val track: Int? = null, val cover: ByteArray? = null,
    val albumartist: String? = null, val year: String? = null, val genre: String? = null, val disc: Int? = null, val yt: String? = null,
)

private val ONE_ARTIST = listOf("Tyler, The Creator", "Earth, Wind & Fire", "Earth, Wind and Fire", "AC/DC", "Simon & Garfunkel", "Hall & Oates",
    "Brooks & Dunn", "Crosby, Stills, Nash & Young", "Crosby, Stills & Nash", "Blood, Sweat & Tears", "Emerson, Lake & Palmer",
    "Florence + The Machine", "Florence & The Machine", "Mumford & Sons", "Of Monsters and Men", "Bell Biv DeVoe", "Sly & The Family Stone",
    "Kool & The Gang", "Huey Lewis & The News", "Prince & The Revolution", "Smokey Robinson & The Miracles", "Ike & Tina Turner")
private val SPLIT = Regex("\\s*[,;]\\s*|\\s+(?:&|\\+|×|/|x)\\s+|\\s+(?:[Ff]eat\\.?|[Ff]t\\.?|[Ff]eaturing|FEAT\\.?|FT\\.?)\\s+")
/** splitArtists() from tags.js, 1:1: "A, B" / "A & B" / "A x B" / "A feat. B" / "A; B" → [A, B]; known one-artist names stay whole */
fun splitArtists(credit: String): List<String> {
    var s = credit.replace(Regex("[()\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim()
    if (s.isEmpty()) return emptyList()
    val keep = ArrayList<String>()
    for (name in ONE_ARTIST) {
        val i = s.lowercase().indexOf(name.lowercase())
        if (i >= 0) { keep.add(s.substring(i, i + name.length)); s = s.substring(0, i) + "\u0000${keep.size - 1}\u0000" + s.substring(i + name.length) }
    }
    val out = ArrayList<String>(); val seen = HashSet<String>()
    for (raw in s.split(SPLIT)) {
        val part = raw.replace(Regex("\u0000(\\d+)\u0000")) { keep[it.groupValues[1].toInt()] }.trim()
        if (part.isNotEmpty() && seen.add(part.lowercase())) out.add(part)
    }
    return out
}
fun primaryArtist(a: String): String = splitArtists(a).firstOrNull() ?: ""

/** The WebView app's hash (FNV-1a, base 36): album cover ids and artist-photo ids match it. */
fun fnv(s: String): String {
    var h = -2128831035
    for (ch in s) { h = h xor ch.code; h *= 16777619 }
    return (h.toLong() and 0xffffffffL).toString(36)
}

fun safeName(s: String, fallback: String = "Unknown"): String =
    s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').ifEmpty { fallback }.take(80)

object Store {
    lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val songs = MutableStateFlow<List<Song>>(emptyList())
    val playlists = MutableStateFlow>>(emptyMap())
    val favs = MutableStateFlow>(emptyMap())
    val favArtists = MutableStateFlow<Set<String>>(emptySet())
    /** bumps whenever something in meta changes (lyrics, artist info, settings) */
    val ver = MutableStateFlow(0)
    val busy = MutableStateFlow("")
    @Volatile var meta = JSONObject()
    private val lock = Any()
    private var saveJob: Job? = null
    val root: File get() = File(app.filesDir, "strata").apply { mkdirs() }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        try {
            val lf = File(root, "songs.json")
            if (lf.exists()) {
                val a = JSONArray(lf.readText())
                songs.value = (0 until a.length()).map { Song(a.getJSONObject(it)) }
            }
            val mf = File(root, "meta.json")
            if (mf.exists()) meta = JSONObject(mf.readText())
        } catch (e: Exception) { Diag.log("library: couldn't read (${e.message}); the song files are still on disk") }
        ensureMeta()
        convertTestLibrary()
        publishMeta()
    }

    private fun ensureMeta() {
        for (k in listOf("playlists", "favorites", "settings", "lyrics", "artistInfo")) if (meta.optJSONObject(k) == null) meta.put(k, JSONObject())
        if (meta.optJSONArray("favArtists") == null) meta.put("favArtists", JSONArray())
    }
    fun settings(): JSONObject = meta.optJSONObject("settings") ?: JSONObject().also { meta.put("settings", it) }

    private fun publishMeta() {
        val pl = LinkedHashMap<String, List<String>>()
        val p = meta.getJSONObject("playlists")
        for (k in p.keys()) { val a = p.optJSONArray(k) ?: continue; pl[k] = (0 until a.length()).map { a.optString(it) } }
        playlists.value = pl
        val f = meta.getJSONObject("favorites"); val fm = HashMap<String, Long>()
        for (k in f.keys()) { val v = f.opt(k); if (v != null && v != false) fm[k] = f.optLong(k, 1) }
        favs.value = fm
        val fa = meta.getJSONArray("favArtists")
        favArtists.value = (0 until fa.length()).map { fa.optString(it) }.toSet()
        ver.value++
    }

    /** Saves a moment after the last change, atomically (write a temp file, then rename). */
    fun save(now: Boolean = false) {
        saveJob?.cancel()
        saveJob = scope.launch {
            if (!now) delay(1500)
            synchronized(lock) {
                try {
                    write(File(root, "songs.json"), JSONArray().also { a -> songs.value.forEach { a.put(it.j) } }.toString())
                    write(File(root, "meta.json"), meta.toString())
                } catch (e: Exception) { Diag.log("library: save failed: ${e.message}") }
            }
        }
    }
    private fun write(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }
    fun metaChanged() { publishMeta(); save() }

    /** The first native test builds kept a different file (next-library.json): carried over once, then left alone. */
    private fun convertTestLibrary() {
        val old = File(app.filesDir, "next-library.json")
        if (!old.exists() || File(root, "songs.json").exists()) return
        try {
            val j = JSONObject(old.readText())
            val sa = j.optJSONArray("songs") ?: JSONArray()
            val out = ArrayList<Song>()
            for (i in 0 until sa.length()) {
                val o = sa.getJSONObject(i)
                val n = JSONObject().put("id", o.getString("id")).put("file", o.getString("path").removePrefix("Songs/"))
                    .put("title", o.optString("title")).put("artist", o.optString("artist")).put("album", o.optString("album"))
                    .put("track", o.optInt("track")).put("duration", o.optLong("dur") / 1000.0).put("added", o.optLong("added")).put("store", "disk")
                val cv = o.optString("cover")
                if (cv.isNotEmpty()) { val id = cv.removePrefix("covers/").removeSuffix(".jpg"); n.put("cover", id) }
                out.add(Song(n))
            }
            songs.value = out
            val pa = j.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until pa.length()) { val p = pa.getJSONObject(i); meta.getJSONObject("playlists").put(p.optString("name"), p.optJSONArray("ids") ?: JSONArray()) }
            val fa = j.optJSONArray("favs") ?: JSONArray()
            for (i in 0 until fa.length()) meta.getJSONObject("favorites").put(fa.getString(i), System.currentTimeMillis() - i)
            save(true)
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ reading
    fun song(id: String): Song? = songs.value.firstOrNull { it.id == id }
    fun albums(list: List<Song> = songs.value): List<Album> =
        list.groupBy { it.albumKey }.map { (k, l) ->
            Album(k, l[0].albumName, l[0].albumArtist, l.sortedWith(compareBy({ it.disc }, { it.track }, { it.title.lowercase() })))
        }.sortedBy { it.title.lowercase() }
    fun artists(list: List<Song> = songs.value): Map<String, List<Song>> {
        val m = java.util.TreeMap<String, MutableList<Song>>(String.CASE_INSENSITIVE_ORDER)
        for (s in list) {
            val names = (splitArtists(s.artist) + listOf(s.albumArtist)).filter { it != "Unknown Artist" || s.artist.isBlank() }.distinctBy { it.lowercase() }
            for (n in names.ifEmpty { listOf("Unknown Artist") }) m.getOrPut(n) { mutableListOf() }.add(s)
        }
        return m
    }
    fun artistInfo(name: String): JSONObject? = meta.optJSONObject("artistInfo")?.optJSONObject(name.lowercase())
    fun coverFile(id: String): File = File(app.filesDir, "covers/$id.jpg")

    // ------------------------------------------------------------------ adding
    /** the id of the song [register] last turned away as a duplicate (so a scan can still put it in a playlist) */
    @Volatile var lastDupId: String? = null
    /** is a song with this title and artist already in the library? (the scanner greys these out) */
    fun haveSong(title: String, artist: String): Boolean = findSame(title, artist, "") != null
    /** one file from the scanner: copies it in and adds it. Returns its song id (added now, or already here), or null when it failed */
    fun importUri(u: Uri, folderCover: ByteArray?): Pair<String, Boolean>? {
        return try {
            val name = displayName(u)
            val tmp = copyIn(u, name)
            lastDupId = null
            val song = register(tmp, tmp.extension, if (folderCover != null && hasNoPicture(tmp)) Meta(cover = folderCover) else null)
            if (song != null) song.id to true else lastDupId?.let { it to false }
        } catch (e: Exception) { Diag.log("scan import: ${e.message}"); null }
    }
    private fun findSame(title: String, artist: String, album: String): Song? {
        val t = norm(title); val a = norm(primaryArtist(artist)); val al = norm(album)
        if (t.isEmpty()) return null
        return songs.value.firstOrNull { norm(it.title) == t && norm(primaryArtist(it.artist)) == a && (al.isEmpty() || norm(it.album).isEmpty() || norm(it.album) == al) }
    }
    private fun norm(s: String) = s.lowercase().replace(Regex("\\s*[(\\[](feat|ft|with|prod|remaster|explicit)[^)\\]]*[)\\]]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), "")

    fun organizedPath(albumartist: String, artist: String, album: String, track: Int, disc: Int, title: String, ext: String): String {
        val who = primaryArtist(albumartist).ifBlank { primaryArtist(artist) }.ifBlank { "Unknown Artist" }
        val pre = if (track > 0) (if (disc > 1) "$disc-%02d ".format(track) else "%02d ".format(track)) else ""
        return "${safeName(who, "Unknown Artist")}/${safeName(album.ifBlank { "Unknown Album" }, "Unknown Album")}/$pre${safeName(title, "Untitled")}.$ext"
    }
    private fun uniqueFile(rel: String): String {
        var r = rel; var n = 2
        val base = rel.substringBeforeLast('.'); val ext = rel.substringAfterLast('.')
        while (File(app.filesDir, "Songs/$r").exists() || songs.value.any { it.file.equals(r, true) }) { r = "$base ($n).$ext"; n++ }
        return r
    }

    /** Saves a cover (once per album, same id scheme as the WebView app) and returns its id. */
    fun storeCover(albumKey: String, bytes: ByteArray?): String {
        if (bytes == null || bytes.size < 200) return ""
        val id = fnv(albumKey)
        val f = coverFile(id)
        if (!f.exists()) { f.parentFile?.mkdirs(); f.writeBytes(Images.squareJpeg(bytes, 1000) ?: bytes) }
        return id
    }
    fun setCover(ids: Collection<String>, bytes: ByteArray) {
        val first = songs.value.firstOrNull { it.id in ids } ?: return
        val id = fnv(first.albumKey + "\u0002" + System.currentTimeMillis())
        val f = coverFile(id); f.parentFile?.mkdirs(); f.writeBytes(Images.squareJpeg(bytes, 1000) ?: bytes)
        update(ids) { put("cover", id) }
        Images.forget()
    }

    /** Reads the tags of [tmp], moves it into Songs/Artist/Album/NN Title.ext, adds it. Returns null when it's a duplicate. */
    fun register(tmp: File, ext: String, hint: Meta? = null): Song? {
        var title = hint?.title; var artist = hint?.artist; var album = hint?.album; var track = hint?.track; var disc = hint?.disc
        var albumartist = hint?.albumartist; var year = hint?.year; var genre = hint?.genre
        var cover = hint?.cover; var dur = 0L; var bitrate = 0
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(tmp.path)
            fun md(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            if (title.isNullOrBlank()) title = md(MediaMetadataRetriever.METADATA_KEY_TITLE)
            if (artist.isNullOrBlank()) artist = md(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            if (albumartist.isNullOrBlank()) albumartist = md(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            if (album.isNullOrBlank()) album = md(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            if (track == null) track = md(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull()
            if (disc == null) disc = md(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull()
            if (year.isNullOrBlank()) year = md(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: md(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)
            if (genre.isNullOrBlank()) genre = md(MediaMetadataRetriever.METADATA_KEY_GENRE)
            dur = md(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            bitrate = md(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            if (cover == null) cover = r.embeddedPicture
        } catch (_: Exception) {
        } finally { try { r.release() } catch (_: Exception) { } }
        val fromName = tmp.nameWithoutExtension.replace(Regex("^(imp|pack|yt)-[\\w-]+?-"), "")
        val t = title?.takeIf { it.isNotBlank() } ?: fromName
        val a = artist?.takeIf { it.isNotBlank() } ?: ""
        val al = album?.takeIf { it.isNotBlank() } ?: ""
        findSame(t, a, al)?.let { lastDupId = it.id; tmp.delete(); return null }
        val rel = uniqueFile(organizedPath(albumartist ?: "", a, al, track ?: 0, disc ?: 0, t, ext))
        val dest = File(app.filesDir, "Songs/$rel")
        dest.parentFile?.mkdirs()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        val j = JSONObject()
            .put("id", "n" + java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e9).toLong(), 36))
            .put("file", rel).put("title", t).put("artist", a).put("album", al).put("albumartist", albumartist ?: "")
            .put("track", track ?: 0).put("disc", disc ?: 0).put("year", year ?: "").put("genre", genre ?: "")
            .put("duration", dur / 1000.0).put("size", dest.length()).put("added", System.currentTimeMillis())
            .put("type", mimeFor(ext)).put("store", "disk")
        if (bitrate > 0) j.put("codec", "${ext.uppercase()} · ${bitrate / 1000} kbps")
        if (!hint?.yt.isNullOrBlank()) j.put("yt", hint?.yt)
        val tmpSong = Song(j)
        val cid = storeCover(tmpSong.albumKey, cover) .ifEmpty { songs.value.firstOrNull { it.albumKey == tmpSong.albumKey && it.cover.isNotEmpty() }?.cover ?: "" }
        if (cid.isNotEmpty()) j.put("cover", cid)
        val s = Song(j)
        synchronized(lock) { songs.value = songs.value + s }
        save()
        Tasks.poke()
        return s
    }

    fun mimeFor(ext: String) = when (ext) {
        "mp3" -> "audio/mpeg"; "m4a", "mp4", "aac", "alac" -> "audio/mp4"; "flac" -> "audio/flac"; "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"; "webm", "weba" -> "audio/webm"; "aif", "aiff" -> "audio/aiff"; else -> "audio/*"
    }
    val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aif", "aiff", "wma", "alac", "mp4", "weba", "webm")

    private fun displayName(u: Uri): String =
        try { app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } catch (_: Exception) { null }
            ?: u.lastPathSegment?.substringAfterLast('/') ?: "song"

    private fun copyIn(u: Uri, name: String): File {
        val ext = name.substringAfterLast('.', "m4a").lowercase().take(5)
        val tmp = File(app.cacheDir, "imp-${System.nanoTime()}-${safeName(name.substringBeforeLast('.'))}.$ext")
        app.contentResolver.openInputStream(u)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        return tmp
    }

    /** Songs picked in the system file picker */
    fun importSongs(uris: List<Uri>, folderCover: ByteArray? = null, onDone: ((Int, Int) -> Unit)? = null) {
        scope.launch {
            var added = 0; var skipped = 0
            uris.forEachIndexed { i, u ->
                busy.value = "Adding ${i + 1} of ${uris.size}…"
                try {
                    val name = displayName(u)
                    val tmp = copyIn(u, name)
                    if (register(tmp, tmp.extension, if (folderCover != null && hasNoPicture(tmp)) Meta(cover = folderCover) else null) != null) added++ else skipped++
                } catch (e: Exception) { Diag.log("import: ${e.message}") }
            }
            busy.value = ""
            onDone?.invoke(added, skipped)
        }
    }
    private fun hasNoPicture(f: File): Boolean {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(f.path); r.embeddedPicture == null } catch (_: Exception) { true } finally { try { r.release() } catch (_: Exception) { } }
    }

    /** A whole folder (and its sub-folders) picked with the system folder picker; a cover.jpg / folder.jpg is used for its songs */
    fun importFolder(tree: Uri) {
        scope.launch {
            busy.value = "Looking through the folder…"
            val found = ArrayList<Pair<Uri, ByteArray?>>()
            fun walk(docId: String, depth: Int) {
                if (depth > 8) return
                val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val files = ArrayList<Triple<String, String, String>>()
                try {
                    app.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                        while (c.moveToNext()) files.add(Triple(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: ""))
                    }
                } catch (_: Exception) { }
                val coverDoc = files.firstOrNull { Regex("^(cover|folder|front)\\.(jpe?g|png)$", RegexOption.IGNORE_CASE).matches(it.second) }
                val cover = coverDoc?.let { try { app.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, it.first))?.use { s -> s.readBytes() } } catch (_: Exception) { null } }
                for ((id, name, mime) in files) {
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, depth + 1)
                    else if (name.substringAfterLast('.', "").lowercase() in AUDIO) found.add(DocumentsContract.buildDocumentUriUsingTree(tree, id) to cover)
                }
            }
            try { walk(DocumentsContract.getTreeDocumentId(tree), 0) } catch (e: Exception) { Diag.log("folder: ${e.message}") }
            var added = 0
            found.forEachIndexed { i, (u, cov) ->
                busy.value = "Adding ${i + 1} of ${found.size}…"
                try {
                    val tmp = copyIn(u, displayName(u))
                    if (register(tmp, tmp.extension, if (cov != null && hasNoPicture(tmp)) Meta(cover = cov) else null) != null) added++
                } catch (e: Exception) { Diag.log("folder: ${e.message}") }
            }
            busy.value = ""
            Toasts.show("Added $added songs" + if (found.size > added) " (${found.size - added} were already here)" else "")
        }
    }

    /** A Music Pack (.zip: musicpack.json + Music/Artist/Album/NN Title.ext + cover.jpg per folder; may carry a playlist) */
    fun importPack(u: Uri) {
        scope.launch {
            busy.value = "Opening the Music Pack…"
            var manifest = JSONObject()
            val covers = HashMap<String, ByteArray>()
            val audio = ArrayList<Pair<String, File>>()
            try {
                app.contentResolver.openInputStream(u)?.use { raw ->
                    ZipInputStream(raw.buffered()).use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            val nm = e.name
                            if (e.isDirectory || nm.contains("__MACOSX")) continue
                            val base = nm.substringAfterLast('/')
                            when {
                                nm == "musicpack.json" -> manifest = try { JSONObject(z.readBytes().toString(Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                                Regex("^(cover|folder|front)\\.(jpe?g|png)$", RegexOption.IGNORE_CASE).matches(base) -> covers[nm.substringBeforeLast('/', "")] = z.readBytes()
                                base.substringAfterLast('.', "").lowercase() in AUDIO -> {
                                    val tmp = File(app.cacheDir, "pack-${System.nanoTime()}-${safeName(base.substringBeforeLast('.'))}.${base.substringAfterLast('.').lowercase()}")
                                    tmp.outputStream().use { o -> z.copyTo(o) }
                                    audio.add(nm to tmp); busy.value = "Unpacking ${audio.size}…"
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { Diag.log("pack: ${e.message}"); Toasts.show("Couldn't read that Music Pack") }
            val info = HashMap<String, JSONObject>()
            manifest.optJSONArray("songs")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { info[it.optString("file")] = it } }
            val order = ArrayList<String>(); var added = 0; var skipped = 0
            audio.forEachIndexed { i, (nm, tmp) ->
                busy.value = "Adding ${i + 1} of ${audio.size}…"
                try {
                    val m = info[nm.removePrefix("Music/")]
                    val cov = covers[nm.substringBeforeLast('/', "")]
                    val hint = Meta(
                        title = m?.optString("title")?.ifBlank { null }, artist = m?.optString("artist")?.ifBlank { null }, album = m?.optString("album")?.ifBlank { null },
                        albumartist = m?.optString("albumartist")?.ifBlank { null }, track = m?.optInt("track")?.takeIf { it > 0 }, disc = m?.optInt("disc")?.takeIf { it > 0 },
                        year = m?.optString("year")?.ifBlank { null }, genre = m?.optString("genre")?.ifBlank { null }, cover = if (cov != null && hasNoPicture(tmp)) cov else null,
                    )
                    val before = order.size
                    val s = register(tmp, tmp.extension, hint)
                    if (s != null) { added++; order.add(s.id) } else { skipped++; findSame(hint.title ?: "", hint.artist ?: "", hint.album ?: "")?.let { order.add(it.id) } }
                    // a pack can carry synced lyrics (LRC text) for a song, so songs that LRCLIB doesn't know still get lyrics
                    val lrcText = m?.optString("lyrics").orEmpty()
                    if (lrcText.isNotBlank() && order.size > before) {
                        meta.getJSONObject("lyrics").put(order.last(), JSONObject().put("synced", lrcText).put("plain", "").put("instrumental", false).put("source", "Music Pack")); metaChanged()
                    }
                } catch (e: Exception) { Diag.log("pack: ${e.message}") }
            }
            val pl = manifest.optString("playlist")
            if (pl.isNotBlank() && pl != "null" && order.isNotEmpty()) { val name = uniquePlaylist(pl); meta.getJSONObject("playlists").put(name, JSONArray(order)); metaChanged() }
            busy.value = ""
            Toasts.show("Added $added songs" + (if (skipped > 0) " · $skipped were already here" else "") + (if (pl.isNotBlank() && pl != "null") " · playlist “$pl”" else ""))
        }
    }

    // ------------------------------------------------------------------ changing
    fun update(ids: Collection<String>, f: JSONObject.() -> Unit) {
        synchronized(lock) { songs.value = songs.value.map { if (it.id in ids) it.copy(f) else it } }
        save()
    }

    /** Changes a song's details; if artist / album / title / track changed, the file moves to its new organized place */
    fun edit(id: String, fields: Map<String, Any>) {
        val s = song(id) ?: return
        val n = s.copy { for ((k, v) in fields) put(k, v) }
        val rel = organizedPath(n.albumartist, n.artist, n.album, n.track, n.disc, n.title, s.ext.ifEmpty { "m4a" })
        var file = s.file
        if (!rel.equals(s.file, true)) {
            val to = uniqueFile(rel)
            val src = File(app.filesDir, s.path); val dst = File(app.filesDir, "Songs/$to")
            dst.parentFile?.mkdirs()
            if (src.exists() && src.renameTo(dst)) file = to
        }
        val oldKey = s.albumKey
        update(listOf(id)) { for ((k, v) in fields) put(k, v); put("file", file) }
        // a song moved to another album takes that album's cover if it has one
        val now = song(id) ?: return
        if (now.albumKey != oldKey) songs.value.firstOrNull { it.id != id && it.albumKey == now.albumKey && it.cover.isNotEmpty() }?.let { o -> update(listOf(id)) { put("cover", o.cover) } }
    }

    fun remove(ids: Collection<String>) {
        val gone = songs.value.filter { it.id in ids }
        for (s in gone) File(app.filesDir, s.path).delete()
        synchronized(lock) { songs.value = songs.value.filter { it.id !in ids } }
        val still = songs.value.map { it.cover }.toSet()
        for (c in gone.map { it.cover }.toSet()) if (c.isNotEmpty() && c !in still) coverFile(c).delete()
        val p = meta.getJSONObject("playlists")
        for (k in p.keys().asSequence().toList()) { val a = p.optJSONArray(k) ?: continue; p.put(k, JSONArray((0 until a.length()).map { a.optString(it) }.filter { it !in ids })) }
        val f = meta.getJSONObject("favorites"); for (id in ids) f.remove(id)
        val ly = meta.getJSONObject("lyrics"); for (id in ids) ly.remove(id)
        metaChanged()
    }
    fun removeAll() {
        File(app.filesDir, "Songs").deleteRecursively(); File(app.filesDir, "covers").deleteRecursively()
        songs.value = emptyList()
        meta.put("playlists", JSONObject()); meta.put("favorites", JSONObject()); meta.put("lyrics", JSONObject())
        metaChanged()
    }

    fun isFav(id: String) = favs.value.containsKey(id)
    fun setFav(ids: List<String>, on: Boolean? = null): Boolean {
        val f = meta.getJSONObject("favorites")
        val v = on ?: !ids.all { isFav(it) }
        val now = System.currentTimeMillis()
        ids.forEachIndexed { i, id -> if (v) { if (!f.has(id)) f.put(id, now - i) } else f.remove(id) }
        metaChanged(); return v
    }
    fun toggleFav(id: String) = setFav(listOf(id))
    fun favorites(): List<Song> { val by = songs.value.associateBy { it.id }; return favs.value.entries.sortedByDescending { it.value }.mapNotNull { by[it.key] } }
    fun toggleFavArtist(name: String) {
        val k = name.lowercase(); val a = meta.getJSONArray("favArtists")
        val l = (0 until a.length()).map { a.optString(it) }.toMutableList()
        if (k in l) l.remove(k) else l.add(k)
        meta.put("favArtists", JSONArray(l)); metaChanged()
    }

    fun uniquePlaylist(base: String): String {
        val p = meta.getJSONObject("playlists"); var n = base.ifBlank { "Playlist" }; var i = 2
        while (p.has(n)) { n = "$base $i"; i++ }
        return n
    }
    fun newPlaylist(name: String, ids: List<String> = emptyList()): String { val n = uniquePlaylist(name.trim()); meta.getJSONObject("playlists").put(n, JSONArray(ids)); metaChanged(); return n }
    fun addToPlaylist(name: String, ids: List<String>): Int {
        val p = meta.getJSONObject("playlists"); val a = p.optJSONArray(name) ?: JSONArray().also { p.put(name, it) }
        val have = (0 until a.length()).map { a.optString(it) }.toMutableSet(); var n = 0
        for (id in ids) if (have.add(id)) { a.put(id); n++ }
        metaChanged(); return n
    }
    fun setPlaylist(name: String, ids: List<String>) { meta.getJSONObject("playlists").put(name, JSONArray(ids)); metaChanged() }
    fun renamePlaylist(from: String, to: String) {
        val p = meta.getJSONObject("playlists"); val a = p.optJSONArray(from) ?: return
        val n = if (to.isBlank() || to == from) return else uniquePlaylist(to.trim())
        // keep the order of playlists
        val o = JSONObject(); for (k in p.keys().asSequence().toList()) o.put(if (k == from) n else k, if (k == from) a else p.get(k))
        meta.put("playlists", o); metaChanged()
    }
    fun deletePlaylist(name: String) { meta.getJSONObject("playlists").remove(name); metaChanged() }

    fun setArtistInfo(name: String, fields: Map<String, String>, pic: ByteArray?) {
        val k = name.lowercase(); val ai = meta.getJSONObject("artistInfo")
        val info = ai.optJSONObject(k) ?: JSONObject().also { ai.put(k, it) }
        if (pic != null && pic.size > 2000) {
            val id = "artist-" + fnv(k); val f = coverFile(id); f.parentFile?.mkdirs(); f.writeBytes(Images.squareJpeg(pic, 1000) ?: pic); info.put("image", id); Images.forget()
        }
        for ((f, v) in fields) if (v.isNotBlank()) info.put(f, v)
        metaChanged()
    }

    fun recent(): List<String> { val a = meta.optJSONArray("recent") ?: return emptyList(); return (0 until a.length()).map { a.optString(it) } }
    fun addRecent(q: String) { if (q.isBlank()) return; meta.put("recent", JSONArray((listOf(q.trim()) + recent().filter { !it.equals(q.trim(), true) }).take(12))); save() }
    fun clearRecent() { meta.put("recent", JSONArray()); save() }

    fun storageBytes(): Long = File(app.filesDir, "Songs").walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
