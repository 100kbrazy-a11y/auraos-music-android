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
    class Line(val ms: Long, val text: String, val wordMs: LongArray? = null)
    class Lyrics(val synced: List<Line>, val plain: String, val instrumental: Boolean, val none: Boolean)

    fun parseLrc(t: String): List<Line> {
        val out = ArrayList<Line>(); var offset = 0L
        for (raw in t.lines()) {
            Regex("^\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(raw)?.let { offset = it.groupValues[1].toLong() }
            val stamps = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]").findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val rawText = raw.substring(stamps.last().range.last + 1)
            val tags = Regex("<(\\d+):(\\d+(?:\\.\\d+)?)>").findAll(rawText).toList()
            var text = rawText.trim(); var wordAt: LongArray? = null
            if (tags.isNotEmpty()) {           // "enhanced" lyrics carry a time for every word: use them exactly
                val sb = StringBuilder(); val marks = ArrayList<Pair<Int, Long>>()
                val first = rawText.substring(0, tags[0].range.first)
                if (first.isNotBlank()) sb.append(first)
                tags.forEachIndexed { i, m ->
                    val seg = rawText.substring(m.range.last + 1, if (i + 1 < tags.size) tags[i + 1].range.first else rawText.length)
                    marks.add(sb.length to (m.groupValues[1].toLong() * 60000 + (m.groupValues[2].toDouble() * 1000).toLong() - offset).coerceAtLeast(0))
                    sb.append(seg)
                }
                val lead = sb.length - sb.toString().trimStart().length
                text = sb.toString().trim()
                val starts = ArrayList<Long>(); var at = 0
                val base = (stamps.first().groupValues[1].toLong() * 60000 + (stamps.first().groupValues[2].toDouble() * 1000).toLong() - offset).coerceAtLeast(0)
                for (w in text.split(" ")) {
                    val ci = at + lead
                    starts.add(marks.lastOrNull { it.first <= ci }?.second ?: base)
                    at += w.length + 1
                }
                wordAt = starts.toLongArray()
            }
            for (m in stamps) out.add(Line((m.groupValues[1].toLong() * 60000 + (m.groupValues[2].toDouble() * 1000).toLong() - offset).coerceAtLeast(0), text, wordAt))
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

    /** LRCLIB: the first record (synced, plain or instrumental) found by the usual set of lookups */
    private fun lrclib(s: Song): JSONObject? {
        val artist = s.artist.split(",")[0].trim(); val title = s.title; val c = clean(title); val d = Math.round(s.durMs / 1000.0).toString()
        val tries = ArrayList<() -> JSONObject?>()
        if (s.album.isNotBlank() && s.durMs > 0) tries.add { lrc("get", mapOf("track_name" to title, "artist_name" to artist, "album_name" to s.album, "duration" to d)) as? JSONObject }
        if (s.durMs > 0) tries.add { lrc("get", mapOf("track_name" to title, "artist_name" to artist, "duration" to d)) as? JSONObject }
        if (c != title && s.durMs > 0) tries.add { lrc("get", mapOf("track_name" to c, "artist_name" to artist, "duration" to d)) as? JSONObject }
        tries.add { best(lrc("search", mapOf("track_name" to title, "artist_name" to artist)), s) }
        if (c != title) tries.add { best(lrc("search", mapOf("track_name" to c, "artist_name" to artist)), s) }
        tries.add { best(lrc("search", mapOf("q" to "$c $artist".trim())), s) }
        for (t in tries) { val r = t(); if (r != null && (r.optString("syncedLyrics").isNotBlank() || r.optString("plainLyrics").isNotBlank() || r.optBoolean("instrumental"))) return r.put("source", "LRCLIB") }
        return null
    }

    // ---- more lyric sources. Each searches by title + artist and only accepts a result whose title, artist and length fit the song.
    private class Cand(val id: String, val title: String, val artist: String, val ms: Long)
    private fun choose(cs: List<Cand>, s: Song): Cand? {
        val t = clean(s.title); val a = primaryArtist(s.artist)
        return cs.filter { same(clean(it.title), t) && (a.isBlank() || it.artist.isBlank() || same(primaryArtist(it.artist), a) || same(it.artist, a)) && (s.durMs <= 0 || it.ms <= 0 || Math.abs(it.ms - s.durMs) <= 8000) }
            .minByOrNull { if (s.durMs > 0 && it.ms > 0) Math.abs(it.ms - s.durMs) else 4000L }
    }
    private fun names(a: JSONArray?, key: String = "name") = if (a == null) "" else (0 until a.length()).joinToString(", ") { a.optJSONObject(it)?.optString(key) ?: "" }
    private fun hdr(ref: String) = mapOf("Referer" to ref, "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
    private fun query(s: Song) = "${clean(s.title)} ${primaryArtist(s.artist)}".trim()
    private fun unescape(t: String) = t.replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }.replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun netease(s: Song): String? {
        val h = hdr("https://music.163.com/")
        val songs = (Net.json("https://music.163.com/api/search/get?type=1&limit=10&s=${Net.q(query(s))}", h, 9000) as? JSONObject)?.optJSONObject("result")?.optJSONArray("songs") ?: return null
        val c = choose((0 until songs.length()).map { val o = songs.getJSONObject(it); Cand(o.optLong("id").toString(), o.optString("name"), names(o.optJSONArray("artists")), o.optLong("duration")) }, s) ?: return null
        return (Net.json("https://music.163.com/api/song/lyric?id=${c.id}&lv=1&kv=1&tv=-1", h, 9000) as? JSONObject)?.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
    }
    private fun qq(s: Song): String? {
        val list = (Net.json("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=10&p=1&cr=1&t=0&w=${Net.q(query(s))}", hdr("https://y.qq.com/"), 9000) as? JSONObject)
            ?.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null
        val c = choose((0 until list.length()).map { val o = list.getJSONObject(it); Cand(o.optString("songmid"), o.optString("songname"), names(o.optJSONArray("singer")), o.optLong("interval") * 1000) }, s) ?: return null
        val t = (Net.json("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid=${c.id}", hdr("https://y.qq.com/portal/player.html"), 9000) as? JSONObject)?.optString("lyric") ?: return null
        return unescape(t).takeIf { it.isNotBlank() }
    }
    private fun kugou(s: Song): String? {
        val info = (Net.json("https://mobileservice.kugou.com/api/v3/search/song?format=json&page=1&pagesize=10&keyword=${Net.q(query(s))}", emptyMap(), 9000) as? JSONObject)?.optJSONObject("data")?.optJSONArray("info") ?: return null
        val c = choose((0 until info.length()).map { val o = info.getJSONObject(it); Cand(o.optString("hash"), o.optString("songname"), o.optString("singername"), o.optLong("duration") * 1000) }, s) ?: return null
        val cand = (Net.json("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${Net.q(query(s))}&duration=${c.ms}&hash=${c.id}", emptyMap(), 9000) as? JSONObject)?.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val b64 = (Net.json("https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=${cand.optString("id")}&accesskey=${cand.optString("accesskey")}", emptyMap(), 9000) as? JSONObject)?.optString("content") ?: return null
        return String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8).removePrefix("\uFEFF").takeIf { it.isNotBlank() }
    }
    private fun fromLrc(text: String?, src: String): JSONObject? {
        if (text.isNullOrBlank()) return null
        return if (parseLrc(text).size >= 2) JSONObject().put("syncedLyrics", text).put("source", src) else JSONObject().put("plainLyrics", text).put("source", src)
    }

    fun fetchLyrics(s: Song, force: Boolean = false): Lyrics {
        if (Store.meta.optJSONObject("lyrics")?.optJSONObject(s.id)?.optBoolean("own") == true) cachedLyrics(s.id)?.let { return it }   // a file you added stays
        if (!force) cachedLyrics(s.id)?.let { return it }
        var got: JSONObject? = null; var plainOnly: JSONObject? = null
        for (p in LrcProviders.order.value.ifEmpty { listOf("LRCLIB") }) {
            val r: JSONObject? = try {
                when (p) { "LRCLIB" -> lrclib(s); "NetEase" -> fromLrc(netease(s), "NetEase"); "QQ Music" -> fromLrc(qq(s), "QQ Music"); "Kugou" -> fromLrc(kugou(s), "Kugou"); else -> null }
            } catch (e: Exception) { Diag.log("lyrics $p: ${e.message}"); null }
            if (r == null) continue
            if (r.optBoolean("instrumental") || r.optString("syncedLyrics").let { it.isNotBlank() && it != "null" }) { got = r; break }
            if (plainOnly == null && r.optString("plainLyrics").let { it.isNotBlank() && it != "null" }) plainOnly = r      // keep looking for a synced one elsewhere
        }
        return save(s.id, got ?: plainOnly)
    }

    /** lyrics from a file you picked (an .lrc with timestamps, or plain text); it stays for that song */
    fun saveOwn(id: String, text: String): Lyrics {
        val synced = parseLrc(text).size >= 2
        val rec = JSONObject().put("synced", if (synced) text else "").put("plain", if (synced) "" else text).put("instrumental", false).put("source", "Your file").put("own", true)
        Store.meta.getJSONObject("lyrics").put(id, rec); Store.save()
        return Lyrics(parseLrc(rec.optString("synced")), rec.optString("plain"), false, false)
    }

    fun searchLyrics(q: String): List<JSONObject> { val a = lrc("search", mapOf("q" to q)) as? JSONArray ?: return emptyList(); return (0 until a.length()).map { a.getJSONObject(it) } }

    fun save(id: String, got: JSONObject?): Lyrics {
        val rec = if (got != null) JSONObject().put("synced", got.optString("syncedLyrics").let { if (it == "null") "" else it }).put("plain", got.optString("plainLyrics").let { if (it == "null") "" else it })
            .put("instrumental", got.optBoolean("instrumental")).put("source", got.optString("source").ifBlank { "LRCLIB" })
        else JSONObject().put("none", true).put("at", System.currentTimeMillis())
        Store.meta.getJSONObject("lyrics").put(id, rec); Store.save()
        return Lyrics(parseLrc(rec.optString("synced")), rec.optString("plain"), rec.optBoolean("instrumental"), rec.optBoolean("none"))
    }
}

/** which lyric sources are used, and in what order */
object LrcProviders {
    val ALL = listOf("LRCLIB", "NetEase", "QQ Music", "Kugou")
    val order = kotlinx.coroutines.flow.MutableStateFlow(ALL)
    fun load() {
        val a = Store.settings().optJSONArray("lrcProviders")
        order.value = if (a == null) ALL else (0 until a.length()).map { a.optString(it) }.filter { it in ALL }.distinct().ifEmpty { ALL }
    }
    fun set(l: List<String>) { if (l.isEmpty()) return; order.value = l; Store.settings().put("lrcProviders", org.json.JSONArray(l)); Store.save() }
}
