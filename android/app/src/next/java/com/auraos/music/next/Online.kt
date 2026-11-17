package com.auraos.music.next

import org.json.JSONArray
import org.json.JSONObject

/** Song / album details and covers from iTunes and Deezer (no account needed), and lyrics from LRCLIB. */
object Online {
    class Hit(
        val title: String, val artist: String, val album: String, val year: String, val genre: String, val track: Int, val disc: Int,
        val cover: String, val albumId: String, val src: String,
    )

    private fun big(u: String) = u.replace(Regex("/\\d+x\\d+bb\\."), "/1200x1200bb.")

    fun songs(term: String): List<Hit> {
        val out = ArrayList<Hit>()
        try {
            val r = Net.json("https://itunes.apple.com/search?media=music&entity=song&limit=15&term=${Net.q(term)}") as JSONObject
            val a = r.optJSONArray("results") ?: JSONArray()
            for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                out.add(Hit(o.optString("trackName"), o.optString("artistName"), o.optString("collectionName"), o.optString("releaseDate").take(4), o.optString("primaryGenreName"),
                    o.optInt("trackNumber"), o.optInt("discNumber"), big(o.optString("artworkUrl100")), o.optString("collectionId"), "iTunes")) }
        } catch (e: Exception) { Diag.log("iTunes: ${e.message}") }
        try {
            val r = Net.json("https://api.deezer.com/search/track?limit=10&q=${Net.q(term)}") as JSONObject
            val a = r.optJSONArray("data") ?: JSONArray()
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); val al = o.optJSONObject("album") ?: JSONObject()
                out.add(Hit(o.optString("title"), o.optJSONObject("artist")?.optString("name") ?: "", al.optString("title"), "", "", 0, 0, al.optString("cover_xl"), "", "Deezer")) }
        } catch (e: Exception) { Diag.log("Deezer: ${e.message}") }
        return out
    }

    fun albums(term: String): List<Hit> {
        val out = ArrayList<Hit>()
        try {
            val r = Net.json("https://itunes.apple.com/search?media=music&entity=album&limit=20&term=${Net.q(term)}") as JSONObject
            val a = r.optJSONArray("results") ?: JSONArray()
            for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                out.add(Hit(o.optString("collectionName"), o.optString("artistName"), o.optString("collectionName"), o.optString("releaseDate").take(4), o.optString("primaryGenreName"),
                    0, 0, big(o.optString("artworkUrl100")), o.optString("collectionId"), "iTunes")) }
        } catch (e: Exception) { Diag.log("iTunes: ${e.message}") }
        try {
            val r = Net.json("https://api.deezer.com/search/album?limit=10&q=${Net.q(term)}") as JSONObject
            val a = r.optJSONArray("data") ?: JSONArray()
            for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                out.add(Hit(o.optString("title"), o.optJSONObject("artist")?.optString("name") ?: "", o.optString("title"), "", "", 0, 0, o.optString("cover_xl"), "", "Deezer")) }
        } catch (e: Exception) { Diag.log("Deezer: ${e.message}") }
        return out
    }

    /** an iTunes album's tracks: title -> (track, disc) */
    fun albumTracks(id: String): List<Hit> {
        if (id.isBlank()) return emptyList()
        return try {
            val r = Net.json("https://itunes.apple.com/lookup?entity=song&id=$id") as JSONObject
            val a = r.optJSONArray("results") ?: JSONArray()
            (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("wrapperType") == "track" }.map { o ->
                Hit(o.optString("trackName"), o.optString("artistName"), o.optString("collectionName"), o.optString("releaseDate").take(4), o.optString("primaryGenreName"),
                    o.optInt("trackNumber"), o.optInt("discNumber"), big(o.optString("artworkUrl100")), id, "iTunes")
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun clean(t: String) = t.replace(Regex("\\s*[(\\[](feat|ft|with|prod|remaster|explicit|clean|bonus|live|radio)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "").trim()
    private fun same(a: String, b: String): Boolean { val x = a.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), ""); val y = b.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), ""); return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x)) }

    fun bestSong(s: Song): Hit? = songs("${clean(s.title)} ${primaryArtist(s.artist)}").firstOrNull { same(it.title, clean(s.title)) && same(it.artist, primaryArtist(s.artist)) && it.src == "iTunes" }
    fun bestAlbum(a: Album): Hit? = albums("${a.title} ${a.artist}").firstOrNull { same(it.album, clean(a.title)) && same(it.artist, a.artist) }

    /** Applies an album lookup to every song in [a]: album, year, genre, track numbers (matched by title) and the cover */
    fun applyAlbum(a: Album, h: Hit, withCover: Boolean = true) {
        val tracks = albumTracks(h.albumId)
        for (s in a.songs) {
            val t = tracks.firstOrNull { same(clean(it.title), clean(s.title)) }
            val f = HashMap<String, Any>()
            if (h.year.isNotBlank()) f["year"] = h.year
            if (h.genre.isNotBlank()) f["genre"] = h.genre
            if (t != null) { f["track"] = t.track; if (t.disc > 0) f["disc"] = t.disc }
            if (f.isNotEmpty()) Store.edit(s.id, f)
        }
        if (withCover && h.cover.isNotBlank()) try { Store.setCover(a.songs.map { it.id }, Net.get(h.cover)) } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- lyrics
    class Line(val ms: Long, val text: String)
    class Lyrics(val synced: List<Line>, val plain: String, val instrumental: Boolean, val none: Boolean)

    fun parseLrc(t: String): List<Line> {
        val out = ArrayList<Line>(); var offset = 0L
        for (raw in t.lines()) {
            Regex("^\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(raw)?.let { offset = it.groupValues[1].toLong() }
            val stamps = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]").findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) out.add(Line((m.groupValues[1].toLong() * 60000 + (m.groupValues[2].toDouble() * 1000).toLong() - offset).coerceAtLeast(0), text))
        }
        return out.sortedBy { it.ms }
    }

    fun cachedLyrics(id: String): Lyrics? {
        val c = Store.meta.optJSONObject("lyrics")?.optJSONObject(id) ?: return null
        if (c.optBoolean("none") && System.currentTimeMillis() - c.optLong("at") > 3 * 86400000L) return null
        if (c.optBoolean("error")) return null
        return Lyrics(parseLrc(c.optString("synced")), c.optString("plain"), c.optBoolean("instrumental"), c.optBoolean("none"))
    }

    private fun lrc(path: String, q: Map<String, String>): Any? = try {
        Net.json("https://lrclib.net/api/$path?" + q.entries.joinToString("&") { "${it.key}=${Net.q(it.value)}" })
    } catch (_: Exception) { null }

    private fun best(a: Any?, s: Song): JSONObject? {
        val arr = a as? JSONArray ?: return null
        val d = s.durMs / 1000.0
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("syncedLyrics").isNotBlank() || it.optString("plainLyrics").isNotBlank() || it.optBoolean("instrumental") }
            .minByOrNull { (if (it.optString("syncedLyrics").isBlank()) 30.0 else 0.0) + (if (d > 0) Math.abs(it.optDouble("duration", d) - d) else 0.0) }
    }

    fun fetchLyrics(s: Song, force: Boolean = false): Lyrics {
        if (!force) cachedLyrics(s.id)?.let { return it }
        val artist = s.artist.split(",")[0].trim(); val title = s.title; val c = clean(title); val d = Math.round(s.durMs / 1000.0).toString()
        var got: JSONObject? = null
        val tries = ArrayList<() -> JSONObject?>()
        if (s.album.isNotBlank() && s.durMs > 0) tries.add { lrc("get", mapOf("track_name" to title, "artist_name" to artist, "album_name" to s.album, "duration" to d)) as? JSONObject }
        if (s.durMs > 0) tries.add { lrc("get", mapOf("track_name" to title, "artist_name" to artist, "duration" to d)) as? JSONObject }
        if (c != title && s.durMs > 0) tries.add { lrc("get", mapOf("track_name" to c, "artist_name" to artist, "duration" to d)) as? JSONObject }
        tries.add { best(lrc("search", mapOf("track_name" to title, "artist_name" to artist)), s) }
        if (c != title) tries.add { best(lrc("search", mapOf("track_name" to c, "artist_name" to artist)), s) }
        tries.add { best(lrc("search", mapOf("q" to "$c $artist".trim())), s) }
        for (t in tries) { val r = t(); if (r != null && (r.optString("syncedLyrics").isNotBlank() || r.optString("plainLyrics").isNotBlank() || r.optBoolean("instrumental"))) { got = r; break } }
        return save(s.id, got)
    }

    fun searchLyrics(q: String): List<JSONObject> { val a = lrc("search", mapOf("q" to q)) as? JSONArray ?: return emptyList(); return (0 until a.length()).map { a.getJSONObject(it) } }

    fun save(id: String, got: JSONObject?): Lyrics {
        val rec = if (got != null) JSONObject().put("synced", got.optString("syncedLyrics").let { if (it == "null") "" else it }).put("plain", got.optString("plainLyrics").let { if (it == "null") "" else it })
            .put("instrumental", got.optBoolean("instrumental")).put("source", "LRCLIB")
        else JSONObject().put("none", true).put("at", System.currentTimeMillis())
        Store.meta.getJSONObject("lyrics").put(id, rec); Store.save()
        return Lyrics(parseLrc(rec.optString("synced")), rec.optString("plain"), rec.optBoolean("instrumental"), rec.optBoolean("none"))
    }
}
