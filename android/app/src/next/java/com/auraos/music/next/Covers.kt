package com.auraos.music.next

import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Finds album covers online. It asks four places (iTunes, Deezer, MusicBrainz + the Cover Art Archive), looks for the album
 * and for the individual songs, forgives accents, "(Deluxe)", "- Single", "feat." and punctuation, scores every answer on
 * how well the title and artist match, and takes the best one.
 */
object Covers {
    enum class Result { FOUND, NONE, OFFLINE }
    private class Cand(val title: String, val artist: String, val album: String, val cover: String, val src: String)

    // ---------------------------------------------------------------- matching
    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace("&", " and ").replace("’", "'")
    private val EDITION = "deluxe|edition|remaster(ed)?|explicit|clean|bonus|single|ep|anniversary|version|expanded|extended|official|lyrics?|video|audio|visuali[sz]er|hd|hq|4k|mono|stereo|soundtrack|ost|live|radio|mix|remix|feat\\.?|ft\\.?|featuring|with|prod\\.?"
    /** a title boiled down to what identifies it */
    fun norm(t: String): String {
        var s = fold(t)
        s = s.replace(Regex("[(\\[{][^)\\]}]*\\b($EDITION)\\b[^)\\]}]*[)\\]}]"), " ")
        s = s.replace(Regex("\\s[-–—:]\\s*(the\\s+)?(\\w+\\s+){0,2}($EDITION)\\b.*$"), " ")
        s = s.replace(Regex("\\b(feat\\.?|ft\\.?|featuring)\\s.*$"), " ")
        s = s.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("^(the|a)\\s+"), "").replace(Regex("\\s+"), " ").trim()
        return s
    }
    private fun toks(s: String) = norm(s).split(' ').filter { it.isNotEmpty() }.toSet()
    /** 0..1: how alike two titles are */
    fun sim(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.replace(" ", "") == y.replace(" ", "")) return 0.97
        val tx = toks(a); val ty = toks(b)
        val inter = tx.intersect(ty).size.toDouble()
        val jac = inter / tx.union(ty).size
        val contain = if (x.length >= 4 && y.length >= 4 && (x.contains(y) || y.contains(x))) 0.82 else 0.0
        return maxOf(jac, contain, lev(x, y))
    }
    private fun lev(a: String, b: String): Double {
        if (a.length > 40 || b.length > 40) return 0.0
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) { var p = d[0]; d[0] = i
            for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, p + if (a[i - 1] == b[j - 1]) 0 else 1); p = t } }
        return 1.0 - d[b.length].toDouble() / maxOf(a.length, b.length)
    }
    /** an artist field can be "A, B & C": best match against any of them */
    private fun artistSim(cand: String, mine: List<String>): Double {
        if (cand.isBlank() || mine.isEmpty()) return 0.6
        val cs = splitArtists(cand).ifEmpty { listOf(cand) } + cand
        return mine.maxOf { m -> cs.maxOf { sim(it, m) } }
    }

    // ---------------------------------------------------------------- providers
    private fun jsonRetry(url: String, headers: Map<String, String> = emptyMap()): Any? {
        for (i in 0..1) try {
            val t = Net.get(url, headers, 15000).toString(Charsets.UTF_8).trim()
            return if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
        } catch (e: Exception) {
            val m = e.message ?: ""
            if (i == 0 && (m.contains("429") || m.contains("403") || m.contains("5") && m.startsWith("HTTP 5"))) Thread.sleep(900) else if (i == 1) throw e else if (!m.startsWith("HTTP")) throw e else return null
        }
        return null
    }
    private fun big(u: String) = u.replace(Regex("/\\d+x\\d+bb\\."), "/1200x1200bb.")
    private var offline = true       // reset by each lookup: did anything answer at all?

    private fun itunes(term: String, entity: String): List<Cand> = try {
        val r = jsonRetry("https://itunes.apple.com/search?media=music&entity=$entity&limit=12&term=${Net.q(term)}") as? JSONObject
        offline = false
        val a = r?.optJSONArray("results") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("artworkUrl100").isNotBlank() }.map { o ->
            Cand(if (entity == "song") o.optString("trackName") else o.optString("collectionName"), o.optString("artistName"), o.optString("collectionName"), big(o.optString("artworkUrl100")), "iTunes")
        }
    } catch (e: Exception) { Diag.log("iTunes: ${e.message}"); emptyList() }

    private fun deezer(term: String, kind: String): List<Cand> = try {
        val r = jsonRetry("https://api.deezer.com/search/$kind?limit=12&q=${Net.q(term)}") as? JSONObject
        offline = false
        val a = r?.optJSONArray("data") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
            val al = if (kind == "track") o.optJSONObject("album") ?: JSONObject() else o
            val cv = al.optString("cover_xl").ifBlank { al.optString("cover_big") }
            if (cv.isBlank() || cv.contains("/images/cover//") || cv.contains("/cover/1000x1000-")) null    // (Deezer's blank placeholder)
            else Cand(if (kind == "track") o.optString("title") else o.optString("title"), o.optJSONObject("artist")?.optString("name") ?: "", al.optString("title"), cv, "Deezer")
        }
    } catch (e: Exception) { Diag.log("Deezer: ${e.message}"); emptyList() }

    private var lastMb = 0L
    private fun musicBrainz(album: String, artist: String): List<Cand> = try {
        val wait = 1100 - (System.currentTimeMillis() - lastMb); if (wait > 0) Thread.sleep(wait)
        lastMb = System.currentTimeMillis()
        val q = "release:\"${album.replace("\"", "")}\"" + if (artist.isNotBlank()) " AND artist:\"${artist.replace("\"", "")}\"" else ""
        val r = jsonRetry("https://musicbrainz.org/ws/2/release/?fmt=json&limit=8&query=${Net.q(q)}", mapOf("Accept" to "application/json")) as? JSONObject
        offline = false
        val a = r?.optJSONArray("releases") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val ar = o.optJSONArray("artist-credit")?.let { c -> (0 until c.length()).joinToString(", ") { c.getJSONObject(it).optString("name") } } ?: ""
            Cand(o.optString("title"), ar, o.optString("title"), "https://coverartarchive.org/release/${o.optString("id")}/front-1200", "MusicBrainz")
        }
    } catch (e: Exception) { Diag.log("MusicBrainz: ${e.message}"); emptyList() }

    // ---------------------------------------------------------------- the search
    private class Scored(val c: Cand, val score: Double)

    private fun pick(cands: List<Cand>, score: (Cand) -> Double, min: Double): List<Scored> =
        cands.map { Scored(it, score(it) + if (it.src == "iTunes") 0.02 else 0.0) }.filter { it.score >= min }.sortedByDescending { it.score }

    /** a decent image, or null */
    private fun download(c: Cand): ByteArray? = try {
        val b = Net.get(c.cover, timeout = 25000)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth >= 250 && o.outHeight >= 250) b else null
    } catch (_: Exception) { null }

    /** tidy a messy song title ("Artist - Title (Official Video)") into possible (title, artist) pairs */
    private fun titleVariants(s: Song): List<Pair<String, String>> {
        val artist = primaryArtist(s.artist).ifBlank { s.albumArtist.takeIf { it != "Unknown Artist" } ?: "" }
        val out = LinkedHashSet<Pair<String, String>>()
        out.add(norm(s.title) to artist)
        val parts = s.title.split(Regex("\\s[-–—|]\\s"), 2)
        if (parts.size == 2) { out.add(norm(parts[1]) to norm(parts[0])); out.add(norm(parts[1]) to artist) }
        return out.filter { it.first.isNotBlank() }
    }

    /** the best cover for one song (its album's artwork), or null */
    private suspend fun forSong(s: Song): Cand? {
        var best: Scored? = null
        for ((title, artist) in titleVariants(s)) {
            val artists = (splitArtists(artist) + splitArtists(s.artist)).distinct()
            val cands = coroutineScope {
                listOf(
                    async(Dispatchers.IO) { itunes("$title $artist".trim(), "song") },
                    async(Dispatchers.IO) { deezer("$title $artist".trim(), "track") },
                ).awaitAll().flatten()
            }
            val got = pick(cands, { c ->
                val t = sim(c.title, title); val ar = artistSim(c.artist, artists)
                val al = if (s.album.isNotBlank()) sim(c.album, s.album) else 0.0
                if (t < 0.72 || ar < 0.55) 0.0 else t * 0.5 + ar * 0.35 + al * 0.15
            }, 0.7).firstOrNull()
            if (got != null && (best == null || got.score > best.score)) best = got
            if (best != null && best.score > 0.93) break
        }
        return best?.c
    }

    /** the best cover for an album */
    private suspend fun forAlbumCand(a: Album): Cand? {
        val title = norm(a.title).ifBlank { a.title }
        val artists = (splitArtists(a.artist) + a.songs.flatMap { splitArtists(it.artist) }).filter { it != "Unknown Artist" }.distinct()
        val lead = artists.firstOrNull() ?: ""
        val terms = LinkedHashSet<String>().apply { add("$title $lead".trim()); if (lead.isNotEmpty()) add(title); if (artists.size > 1) add("$title ${artists[1]}") }
        val scoreAlbum = { c: Cand ->
            val al = sim(c.album.ifBlank { c.title }, title); val ar = artistSim(c.artist, artists)
            if (al < 0.7 || ar < 0.5) 0.0 else al * 0.65 + ar * 0.35
        }
        var best: Scored? = null
        for (t in terms) {
            val cands = coroutineScope {
                listOf(async(Dispatchers.IO) { itunes(t, "album") }, async(Dispatchers.IO) { deezer(t, "album") }).awaitAll().flatten()
            }
            val got = pick(cands, scoreAlbum, 0.72).firstOrNull()
            if (got != null && (best == null || got.score > best.score)) best = got
            if (best != null && best.score > 0.9) return best.c
        }
        if (best != null) return best.c
        // the songs themselves (the album name may be spelled differently, or the album isn't listed as an album)
        for (s in a.songs.take(4)) forSong(s)?.let { return it }
        // last: MusicBrainz + the Cover Art Archive
        val mb = withIO { musicBrainz(title, lead) }
        for (g in pick(mb, scoreAlbum, 0.78).take(3)) { if (download(g.c) != null) return g.c }
        return null
    }
    private suspend fun <T> withIO(f: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { f() }

    private fun looksLikeNoAlbum(a: Album) = a.title == "Unknown Album" || a.title.equals("singles", true) || a.title.equals("single", true)

    /** Looks for the cover of [a] and saves it for every song in it. */
    suspend fun forAlbum(a: Album): Result {
        offline = true
        return try {
            if (looksLikeNoAlbum(a)) {
                // no real album: each song gets the cover of its own single
                var n = 0
                for (s in a.songs.filter { it.cover.isEmpty() }) {
                    val c = forSong(s) ?: continue
                    val b = withIO { download(c) } ?: continue
                    Store.setCover(listOf(s.id), b); n++
                }
                if (n > 0) Result.FOUND else if (offline) Result.OFFLINE else Result.NONE
            } else {
                val cands = listOfNotNull(forAlbumCand(a))
                for (c in cands) { val b = withIO { download(c) } ?: continue; Store.setCover(a.songs.map { it.id }, b); return Result.FOUND }
                if (offline) Result.OFFLINE else Result.NONE
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Diag.log("covers ${a.title}: ${e.message}"); Result.OFFLINE }
    }

    /** several albums at once (three lookups in flight at a time); [progress] is told how far along it is */
    suspend fun forAlbums(list: List<Album>, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Triple<Int, List<Album>, Boolean> {
        val gate = Semaphore(3); var done = 0; var found = 0; var offlineN = 0
        val missed = java.util.Collections.synchronizedList(ArrayList<Album>())
        coroutineScope {
            list.map { a -> async(Dispatchers.IO) {
                gate.withPermit {
                    when (forAlbum(a)) { Result.FOUND -> synchronized(this@Covers) { found++ }; Result.NONE -> missed.add(a); Result.OFFLINE -> { missed.add(a); synchronized(this@Covers) { offlineN++ } } }
                    synchronized(this@Covers) { done++; progress(done, list.size) }
                }
            } }.awaitAll()
        }
        return Triple(found, missed.toList(), offlineN == list.size && list.isNotEmpty())
    }
}
