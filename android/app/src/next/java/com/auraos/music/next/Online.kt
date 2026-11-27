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
    /** a piece of a line (a word with the space after it) and when it is sung; [end] = when it stops being sung, -1 when the file doesn't say */
    class Chunk(val text: String, val start: Long, val end: Long = -1L)
    class Line(val ms: Long, val text: String, val wordMs: LongArray? = null) {
        /** the words, each with a time: from the file, or spread over the line when the file only has a time for the line */
        var words: List<Chunk> = emptyList()
        var tagged = false          // the file gave a time for each word
        var end = 0L                // when the next line starts
        var sungEnd = 0L            // when the last word has been sung (it can be after the next line has started, for ad-libs)
    }
    class Lyrics(val synced: List<Line>, val plain: String, val instrumental: Boolean, val none: Boolean)

    /** how many beats a word takes to sing: syllables for letters we can count, one per character for scripts without spaces */
    private fun beats(word: String): Double {
        val t = word.trim()
        val letters = t.filter { it.isLetter() }
        if (letters.isEmpty()) return 0.4
        if (t.any { it.code > 0x24F }) return letters.length.toDouble()
        val vowels = "aeiouyáàâäãåéèêëíìîïóòôöõúùûüœæ"
        val lower = letters.lowercase()
        var groups = 0; var inGroup = false
        for (ch in lower) { if (ch in vowels) { if (!inGroup) { groups++; inGroup = true } } else inGroup = false }
        if (groups > 1 && lower.endsWith("e") && !lower.endsWith("le")) groups--       // a silent final e
        if (lower.endsWith("ed") && groups > 1 && !(lower.endsWith("ted") || lower.endsWith("ded"))) groups--
        var w = maxOf(groups, 1).toDouble()
        if (t.endsWith(",") || t.endsWith(";")) w += 0.5
        if (t.endsWith(".") || t.endsWith("!") || t.endsWith("?") || t.endsWith("…")) w += 0.9
        return w
    }

    /**
     * No word times in the file: the words are spread over the part of the line that is actually sung. A line that runs into the next one
     * fills the gap (less a breath); a line followed by a long pause is sung at a natural pace and then stops.
     */
    fun estimateSpan(text: String, a: Long, b: Long): Pair<List<Chunk>, Long> {
        if (text.isEmpty()) return emptyList<Chunk>() to a
        val pieces: List<String> = if (text.contains(" ")) {
            val ws = text.split(" ").filter { it.isNotEmpty() }
            ws.mapIndexed { i, w -> if (i < ws.size - 1) "$w " else w }
        } else text.map { it.toString() }
        val weights = pieces.map { beats(it) }.toMutableList()
        weights[weights.size - 1] += 0.6                 // the last note of a line is usually held
        val total = weights.sum()
        val gap = maxOf(b - a, 500L) / 1000.0
        val natural = total * 0.26 + 0.25
        val span = if (gap <= natural * 1.7) maxOf(gap - 0.18, gap * 0.8) else minOf(gap * 0.9, natural * 1.3)
        var acc = 0.0
        val out = ArrayList<Chunk>()
        pieces.forEachIndexed { i, p -> out.add(Chunk(p, a + (span * 1000.0 * acc / total).toLong())); acc += weights[i] }
        return out to a + (span * 1000.0).toLong()
    }

    private fun adlib(s: String): Boolean { val t = s.trim(); return t.length > 2 && t.startsWith("(") && t.endsWith(")") }

    fun parseLrc(t: String): List<Line> {
        class Raw(val ms: Long, val text: String, val chunks: List<Chunk>)
        val raws = ArrayList<Raw>(); var offset = 0L
        val offRe = Regex("^\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE)
        val stampRe = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]")
        val tagRe = Regex("<(\\d+):(\\d+(?:\\.\\d+)?)>")
        fun at(m: MatchResult) = (m.groupValues[1].toLong() * 60000 + (m.groupValues[2].toDouble() * 1000).toLong() - offset).coerceAtLeast(0)
        for (raw in t.lines()) {
            offRe.find(raw)?.let { offset = it.groupValues[1].toLong() }
            val stamps = stampRe.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val rawText = raw.substring(stamps.last().range.last + 1)
            val tags = tagRe.findAll(rawText).toList()
            var text = rawText.trim(); val chunks = ArrayList<Chunk>()
            if (tags.isNotEmpty()) {           // "enhanced" lyrics carry a time for every word: use them exactly
                val sb = StringBuilder()
                tags.forEachIndexed { i, m ->
                    val seg = rawText.substring(m.range.last + 1, if (i + 1 < tags.size) tags[i + 1].range.first else rawText.length)
                    val tm = at(m)
                    if (seg.isNotEmpty()) { chunks.add(Chunk(seg, tm)); sb.append(seg) }
                    // a time with no word after it: where the word before it stops being sung
                    else if (chunks.isNotEmpty() && chunks.last().end < 0 && tm > chunks.last().start) chunks[chunks.size - 1] = Chunk(chunks.last().text, chunks.last().start, tm)
                }
                text = sb.toString().trim()
            }
            for (m in stamps) raws.add(Raw(at(m), text, chunks))
        }
        raws.sortBy { it.ms }
        val out = ArrayList<Line>()
        raws.forEachIndexed { i, o ->
            val next = if (i + 1 < raws.size) raws[i + 1].ms else o.ms + 5000
            // a line is sung right through the (bracketed) ad-libs that come after it, not cut off where they start
            var limit = next
            if (!adlib(o.text)) {
                var k = i + 1
                while (k < raws.size && adlib(raws[k].text)) k++
                limit = if (k < raws.size) raws[k].ms else maxOf(next, raws.last().ms + 3000)
            }
            val l = Line(o.ms, o.text)
            l.end = next
            if (o.chunks.isEmpty()) { val e = estimateSpan(o.text, o.ms, limit); l.words = e.first; l.sungEnd = e.second }
            else {
                l.tagged = true; l.words = o.chunks
                val last = o.chunks.last()
                l.sungEnd = if (last.end > last.start) last.end else last.start + minOf(1200L, maxOf(450L, next - last.start))
            }
            out.add(l)
        }
        return out
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
        // the version with a time for every word ("yrc") first, if this song has one
        for (u in listOf("https://music.163.com/api/song/lyric/v1?id=${c.id}&cp=false&tv=0&lv=0&rv=0&kv=0&yv=0&ytv=0&yrv=0", "https://music.163.com/api/song/lyric?id=${c.id}&lv=1&kv=1&tv=-1&yv=1")) {
            try {
                val j = Net.json(u, h, 9000) as? JSONObject
                j?.optJSONObject("yrc")?.optString("lyric")?.takeIf { it.isNotBlank() }?.let { WordLyrics.yrc(it) }?.let { return it }
            } catch (e: Exception) { Diag.log("lyrics NetEase words: ${e.message}") }
        }
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
        val id = cand.optString("id"); val key = cand.optString("accesskey")
        // the .krc (a time for every word) first, then the plain .lrc
        try {
            val k = (Net.json("https://lyrics.kugou.com/download?ver=1&client=pc&fmt=krc&charset=utf8&id=$id&accesskey=$key", emptyMap(), 9000) as? JSONObject)?.optString("content")
            if (!k.isNullOrBlank()) WordLyrics.krcDecode(android.util.Base64.decode(k, android.util.Base64.DEFAULT))?.let { WordLyrics.krc(it) }?.let { return it }
        } catch (e: Exception) { Diag.log("lyrics Kugou words: ${e.message}") }
        val b64 = (Net.json("https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=$id&accesskey=$key", emptyMap(), 9000) as? JSONObject)?.optString("content") ?: return null
        return String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8).removePrefix("\uFEFF").takeIf { it.isNotBlank() }
    }

    // ---- Musixmatch (the desktop app's own address: it hands out a short-lived token, then lyrics with a time for every word where it has them)
    private val mxmHdr = mapOf("Cookie" to "AWSELB=0; AWSELBCORS=0", "Accept" to "application/json", "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
    @Volatile private var mxmTok = ""
    @Volatile private var mxmAt = 0L
    private fun mxmToken(): String? {
        if (mxmTok.isNotEmpty() && System.currentTimeMillis() - mxmAt < 8 * 60000L) return mxmTok
        val t = (Net.json("https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0", mxmHdr, 9000) as? JSONObject)?.optJSONObject("message")?.optJSONObject("body")?.optString("user_token") ?: return null
        if (t.isBlank() || t.startsWith("UpgradeOnly")) return null
        mxmTok = t; mxmAt = System.currentTimeMillis()
        return t
    }
    private fun musixmatch(s: Song): JSONObject? {
        val tok = mxmToken() ?: return null
        val d = Math.round(s.durMs / 1000.0)
        val url = "https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get?format=json&namespace=lyrics_richsynched&subtitle_format=mxm&app_id=web-desktop-app-v1.0" +
            "&q_album=${Net.q(s.album)}&q_artist=${Net.q(primaryArtist(s.artist))}&q_artists=${Net.q(s.artist)}&q_track=${Net.q(clean(s.title))}&q_duration=$d&f_subtitle_length=$d&usertoken=${Net.q(tok)}&optional_calls=track.richsync"
        val calls = (Net.json(url, mxmHdr, 10000) as? JSONObject)?.optJSONObject("message")?.optJSONObject("body")?.optJSONObject("macro_calls") ?: return null
        fun body(k: String) = calls.optJSONObject(k)?.optJSONObject("message")?.optJSONObject("body")
        val track = body("matcher.track.get")?.optJSONObject("track")
        if (track != null) {                         // only the song itself: same title, and about the same length
            if (!same(clean(track.optString("track_name")), clean(s.title))) return null
            val len = track.optLong("track_length") * 1000
            if (s.durMs > 0 && len > 0 && Math.abs(len - s.durMs) > 8000) return null
        }
        body("track.richsync.get")?.optJSONObject("richsync")?.optString("richsync_body")?.takeIf { it.isNotBlank() }?.let { WordLyrics.richsync(it) }
            ?.let { return JSONObject().put("syncedLyrics", it).put("source", "Musixmatch") }
        body("track.subtitles.get")?.optJSONArray("subtitle_list")?.optJSONObject(0)?.optJSONObject("subtitle")?.optString("subtitle_body")?.takeIf { it.isNotBlank() }?.let { WordLyrics.mxmLines(it) }
            ?.let { return JSONObject().put("syncedLyrics", it).put("source", "Musixmatch") }
        return null
    }
    private fun fromLrc(text: String?, src: String): JSONObject? {
        if (text.isNullOrBlank()) return null
        return if (parseLrc(text).size >= 2) JSONObject().put("syncedLyrics", text).put("source", src) else JSONObject().put("plainLyrics", text).put("source", src)
    }

    private fun runProvider(p: String, s: Song): JSONObject? = try {
        when (p) { "LRCLIB" -> lrclib(s); "Musixmatch" -> musixmatch(s); "NetEase" -> fromLrc(netease(s), "NetEase"); "QQ Music" -> fromLrc(qq(s), "QQ Music"); "Kugou" -> fromLrc(kugou(s), "Kugou"); else -> null }
    } catch (e: Exception) { Diag.log("lyrics $p: ${e.message}"); null }

    /** how good a find is: a time for every word 300, timed lines 200 (less if it looks like another version of the song), plain text 100 */
    private fun grade(r: JSONObject, s: Song): Int {
        if (r.optBoolean("instrumental")) return 250
        val syn = r.optString("syncedLyrics").let { if (it == "null") "" else it }
        if (syn.isNotBlank()) {
            val ls = parseLrc(syn)
            if (ls.size < 2) return 100
            var g = if (ls.count { it.tagged } >= ls.size * 0.6) 300 else 200
            if (s.durMs > 0 && ls.last().ms > s.durMs + 20000) g -= 150          // runs past the end of the song: a different version
            if (ls.size < 4) g -= 40
            return g
        }
        return if (r.optString("plainLyrics").let { it.isNotBlank() && it != "null" }) 100 else 0
    }

    /** asks every source at once; stops early when one has a time for every word, or 4 s after the first timed lines came in */
    private fun gather(s: Song, order: List<String>): List<Pair<String, JSONObject>> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(order.size)
        val cs = java.util.concurrent.ExecutorCompletionService<Pair<String, JSONObject?>>(pool)
        order.forEach { p -> cs.submit { p to runProvider(p, s) } }
        val got = ArrayList<Pair<String, JSONObject>>()
        val t0 = System.currentTimeMillis(); var firstSynced = 0L; var n = 0
        try {
            while (n < order.size) {
                val now = System.currentTimeMillis()
                val left = 12000 - (now - t0)
                val wait = if (firstSynced > 0) minOf(left, 4000 - (now - firstSynced)) else left
                if (wait <= 0) break
                val f = cs.poll(wait, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                n++
                val pr = try { f.get() } catch (_: Exception) { continue }
                val r = pr.second ?: continue
                got.add(pr.first to r)
                val g = grade(r, s)
                if (g >= 300) break
                if (g >= 200 && firstSynced == 0L) firstSynced = System.currentTimeMillis()
            }
        } finally { pool.shutdownNow() }
        return got
    }

    fun fetchLyrics(s: Song, force: Boolean = false): Lyrics {
        if (Store.meta.optJSONObject("lyrics")?.optJSONObject(s.id)?.optBoolean("own") == true) cachedLyrics(s.id)?.let { return it }   // a file you added stays
        if (!force) cachedLyrics(s.id)?.let { return it }
        val order = LrcProviders.order.value.ifEmpty { listOf("LRCLIB") }
        val got = gather(s, order)
        val top = got.sortedWith(compareByDescending<Pair<String, JSONObject>> { grade(it.second, s) }.thenBy { order.indexOf(it.first) }).firstOrNull()
        Diag.log("lyrics ${s.title}: " + (got.joinToString { "${it.first} ${grade(it.second, s)}" }.ifEmpty { "nothing found" }) + (top?.let { " → ${it.first}" } ?: ""))
        return save(s.id, top?.second)
    }

    /** lyrics saved by an older version took the first source with timed lines: look again, once, and swap them only for a version with a time for every word */
    fun needsUpgrade(id: String): Boolean {
        val rec = Store.meta.optJSONObject("lyrics")?.optJSONObject(id) ?: return false
        return rec.optInt("agg") < 2 && !rec.optBoolean("own") && !rec.optBoolean("none") && !rec.optBoolean("instrumental") && rec.optString("synced").isNotBlank()
    }
    fun upgrade(s: Song): Boolean {
        if (!needsUpgrade(s.id)) return false
        val order = LrcProviders.order.value.ifEmpty { listOf("LRCLIB") }
        val best = gather(s, order).filter { grade(it.second, s) >= 300 }.minByOrNull { order.indexOf(it.first) }
        if (best == null) { Store.meta.getJSONObject("lyrics").optJSONObject(s.id)?.put("agg", 2); Store.save(); return false }
        Diag.log("lyrics ${s.title}: words from ${best.first}")
        save(s.id, best.second)
        return true
    }

    /** lyrics from a file you picked (an .lrc with timestamps, or plain text); it stays for that song */
    fun saveOwn(id: String, text: String): Lyrics {
        val synced = parseLrc(text).size >= 2
        val rec = JSONObject().put("synced", if (synced) text else "").put("plain", if (synced) "" else text).put("instrumental", false).put("source", "Your file").put("own", true)
        Store.meta.getJSONObject("lyrics").put(id, rec); Store.save()
        Tasks.poke()
        return Lyrics(parseLrc(rec.optString("synced")), rec.optString("plain"), false, false)
    }

    fun searchLyrics(q: String): List<JSONObject> { val a = lrc("search", mapOf("q" to q)) as? JSONArray ?: return emptyList(); return (0 until a.length()).map { a.getJSONObject(it) } }

    fun save(id: String, got: JSONObject?): Lyrics {
        val rec = if (got != null) JSONObject().put("synced", got.optString("syncedLyrics").let { if (it == "null") "" else it }).put("plain", got.optString("plainLyrics").let { if (it == "null") "" else it })
            .put("instrumental", got.optBoolean("instrumental")).put("source", got.optString("source").ifBlank { "LRCLIB" }).put("agg", 2)
        else JSONObject().put("none", true).put("at", System.currentTimeMillis()).put("agg", 2)
        Store.meta.getJSONObject("lyrics").put(id, rec); Store.save()
        Tasks.poke()
        return Lyrics(parseLrc(rec.optString("synced")), rec.optString("plain"), rec.optBoolean("instrumental"), rec.optBoolean("none"))
    }
}

/** which lyric sources are used, and in what order */
object LrcProviders {
    val ALL = listOf("LRCLIB", "Musixmatch", "NetEase", "QQ Music", "Kugou")
    val order = kotlinx.coroutines.flow.MutableStateFlow(ALL)
    fun load() {
        val st = Store.settings()
        val a = st.optJSONArray("lrcProviders")
        var l = if (a == null) ALL else (0 until a.length()).map { a.optString(it) }.filter { it in ALL }.distinct().ifEmpty { ALL }
        if (a != null && st.optInt("lrcProvidersV", 1) < 2) { if ("Musixmatch" !in l) l = l + "Musixmatch"; st.put("lrcProviders", org.json.JSONArray(l)); st.put("lrcProvidersV", 2); Store.save() }   // (new in 2.20: on for everyone once)
        order.value = l
    }
    fun set(l: List<String>) { if (l.isEmpty()) return; order.value = l; Store.settings().put("lrcProviders", org.json.JSONArray(l)); Store.settings().put("lrcProvidersV", 2); Store.save() }
}
