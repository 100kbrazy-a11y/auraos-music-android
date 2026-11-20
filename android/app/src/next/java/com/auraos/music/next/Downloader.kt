package com.auraos.music.next

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Dl(val track: Track, val status: String = "Waiting", val frac: Float = 0f, val done: Boolean = false, val failed: Boolean = false, val play: Boolean = false, val how: String = "", val batch: String = "")

/** Download queue. The hidden browser is only asked for the audio *address*; the file itself is fetched natively. */
object Downloader {
    val items = MutableStateFlow<List<Dl>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    @Volatile private var stopAll = false

    /** [play]: each song joins the queue as soon as it's downloaded (radio / mixes).
     *  [how]: "now" (the first song to land starts playing, the rest follow it), "next" (plays next) or "queue" (added to the queue). */
    fun enqueue(ts: List<Track>, play: Boolean = false, how: String = "") {
        val batch = if (how == "now") java.util.UUID.randomUUID().toString() else ""
        // something asked for again (say Play on a song that's already on its way): it just gets the new instruction
        if (how.isNotEmpty()) items.value = items.value.map { d -> if (!d.failed && !d.done && ts.any { it.id == d.track.id }) d.copy(how = how, batch = batch) else d }
        val have = items.value.filter { !it.failed }.map { it.track.id }.toSet()
        val fresh = ts.filter { it.id !in have }.map { Dl(it, play = play, how = how, batch = batch) }
        if (fresh.isEmpty()) return
        items.value = items.value.filter { !it.failed } + fresh
        stopAll = false
        kick()
    }
    private val started = java.util.Collections.synchronizedSet(HashSet<String>())
    /** a finished song goes where it was asked to go */
    private suspend fun deliver(d: Dl, s: Song) = withContext(Dispatchers.Main) {
        when {
            d.how == "now" -> if (d.batch.isNotEmpty() && started.add(d.batch)) Ctl.play(Store.app, listOf(s), 0, "YouTube Music") else Ctl.append(Store.app, s)
            d.how == "next" -> Ctl.playNext(Store.app, listOf(s))
            d.how == "queue" -> Ctl.append(Store.app, s)
            d.play -> Ctl.append(Store.app, s)
        }
    }
    fun clearFinished() { items.value = items.value.filter { !it.done && !it.failed } }
    fun stop() { stopAll = true; items.value = items.value.map { if (!it.done && !it.failed) it.copy(failed = true, status = "Stopped") else it } }
    fun retryFailed() { items.value = items.value.map { if (it.failed) it.copy(failed = false, status = "Waiting", frac = 0f) else it }; stopAll = false; kick() }

    /** three songs at a time: while one is being fetched, the next is already asking YouTube for its address */
    private const val WORKERS = 3
    private val claimLock = Any()
    private val registerLock = Any()
    private fun claim(): Dl? = synchronized(claimLock) {
        val d = items.value.firstOrNull { !it.done && !it.failed && it.status == "Waiting" } ?: return null
        items.value = items.value.map { if (it.track.id == d.track.id && !it.done && !it.failed) it.copy(status = "Starting…") else it }
        d
    }
    private fun waiting() = items.value.firstOrNull { !it.done && !it.failed && it.status == "Waiting" }
    private fun kick() {
        DownloadService.start(Store.app)
        if (worker?.isActive == true) { extra() ; return }
        worker = scope.launch {
            try {
                Engine.use { e ->
                    kotlinx.coroutines.coroutineScope {
                        repeat(WORKERS) { launch { while (!stopAll) { val next = claim() ?: break; process(e, next) } } }
                    }
                }
            } catch (x: Exception) {
                items.value = items.value.map { if (!it.done && !it.failed) it.copy(failed = true, status = "Couldn't start: ${x.message ?: "unknown"}") else it }
            }
            if (!stopAll && waiting() != null) { worker = null; kick() }
        }
    }
    /** songs added while the workers are running are picked up by whichever worker is free; if all three have already finished, start again */
    private fun extra() { scope.launch { delay(400); if (worker?.isActive != true && waiting() != null) kick() } }

    private fun upd(id: String, f: (Dl) -> Dl) { items.value = items.value.map { if (it.track.id == id && !it.done && !it.failed) f(it) else it } }

    private suspend fun process(e: YtEngine, d: Dl) = kotlinx.coroutines.coroutineScope {
        val t = d.track
        // already in the library (same YouTube song): nothing to download
        Store.songs.value.firstOrNull { it.j.optString("yt") == t.id }?.let { s ->
            upd(t.id) { it.copy(status = "Already in your library", frac = 1f, done = true) }
            deliver(items.value.firstOrNull { it.track.id == t.id } ?: d, s)
            return@coroutineScope
        }
        // the cover is fetched at the same time as the audio, not after it
        val coverJob = if (t.thumb.isNotBlank()) async(Dispatchers.IO) { try { Net.get(t.thumb.replace(Regex("=w\\d+-h\\d+"), "=w1200-h1200")) } catch (_: Exception) { null } } else null
        var last = ""
        for (attempt in 1..2) {
            try {
                upd(t.id) { it.copy(status = if (attempt == 1) "Asking YouTube…" else "Trying again…", frac = 0f) }
                val j = JSONObject(e.call("audio", JSONObject.quote(t.id)))
                val ext = j.optString("ext", "m4a")
                val tmp = File(Store.app.cacheDir, "yt-${t.id}-${safeName(t.title.ifBlank { j.optString("title") })}.$ext")
                upd(t.id) { it.copy(status = "Downloading…") }
                fetch(j.getString("url"), j.optLong("size"), j.optString("ua"), j.optString("origin"), tmp) { f -> upd(t.id) { it.copy(frac = f) } }
                val cover = coverJob?.await()
                val title = t.title.ifBlank { j.optString("title") }
                val artist = t.artist.ifBlank { j.optString("artist") }
                val s = synchronized(registerLock) {
                    Store.register(tmp, ext, Meta(title = title, artist = artist, album = t.album.ifBlank { null }, albumartist = t.albumArtist.ifBlank { null },
                        track = t.track.takeIf { it > 0 }, year = t.year.ifBlank { null }, cover = cover, yt = t.id))
                }
                Store.update(listOf(s?.id ?: "")) { put("codec", "${if (ext == "webm") "Opus" else "AAC"}${if (j.optInt("kbps") > 0) " · ${j.optInt("kbps")} kbps" else ""} · from YouTube") }
                upd(t.id) { it.copy(status = if (s != null) "Added" else "Already in your library", frac = 1f, done = true) }
                val song = s ?: Store.songs.value.firstOrNull { it.title.equals(title, true) && primaryArtist(it.artist).equals(primaryArtist(artist), true) }
                if (song != null) deliver(items.value.firstOrNull { it.track.id == t.id } ?: d, song)
                return@coroutineScope
            } catch (x: kotlinx.coroutines.CancellationException) { throw x
            } catch (x: Exception) { last = x.message ?: x.javaClass.simpleName; if (stopAll) break }
        }
        Diag.log("download ${t.id}: $last")
        upd(t.id) { it.copy(status = last.replace(Regex("https://\\S+"), "(address)"), failed = true) }
    }

    private fun open(url: String, ua: String, origin: String, timeout: Int = 40000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = timeout
        if (ua.isNotBlank()) c.setRequestProperty("User-Agent", ua)
        if (origin.isNotBlank()) { c.setRequestProperty("Origin", origin); c.setRequestProperty("Referer", "$origin/") }
        return c
    }

    /**
     * YouTube hands out audio slowly on one long connection and quickly on several short ones, so the file is cut into pieces
     * and four pieces are fetched at once, straight into place in the file. If that's refused, it's one plain download.
     */
    private fun fetch(url: String, size: Long, ua: String, origin: String, out: File, onFrac: (Float) -> Unit) {
        if (size >= 1_500_000L) {
            try { fetchParallel(url, size, ua, origin, out, onFrac); return } catch (x: Exception) {
                if (stopAll) throw x
                Diag.log("parallel download: ${x.message} — going one piece"); out.delete()
            }
        }
        fetchSingle(url, size, ua, origin, out, onFrac)
    }

    private fun fetchParallel(url: String, size: Long, ua: String, origin: String, out: File, onFrac: (Float) -> Unit) {
        val piece = maxOf(1_000_000L, minOf(4_000_000L, (size + 3) / 4))
        val n = ((size + piece - 1) / piece).toInt()
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val got = java.util.concurrent.atomic.AtomicLong(0)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val lastTick = java.util.concurrent.atomic.AtomicLong(0)
        java.io.RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(size)
            val ch = raf.channel
            val threads = (0 until minOf(4, n)).map {
                Thread {
                    while (failure.get() == null && !stopAll) {
                        val i = next.getAndIncrement(); if (i >= n) break
                        val from = i * piece; val to = minOf(size - 1, from + piece - 1)
                        var ok = false; var err: Throwable? = null
                        for (try_ in 0..2) {
                            if (try_ > 0) Thread.sleep(300L * try_)
                            var base = 0L
                            try {
                                val c = open("$url&range=$from-$to", ua, origin, 25000)
                                val code = c.responseCode
                                if (code !in 200..299) throw RuntimeException("download refused (HTTP $code)")
                                c.inputStream.use { inp ->
                                    val buf = ByteArray(32 * 1024)
                                    var pos = from
                                    while (true) {
                                        if (stopAll) throw RuntimeException("Stopped")
                                        val r = inp.read(buf); if (r < 0) break
                                        ch.write(java.nio.ByteBuffer.wrap(buf, 0, r), pos); pos += r; base += r
                                        val all = got.addAndGet(r.toLong())
                                        val now = System.currentTimeMillis(); val lt = lastTick.get()
                                        if (now - lt > 150 && lastTick.compareAndSet(lt, now)) onFrac((all.toFloat() / size).coerceIn(0f, 1f))
                                    }
                                    if (pos <= to) throw RuntimeException("short piece")
                                }
                                ok = true; break
                            } catch (x: Throwable) { err = x; got.addAndGet(-base); if (stopAll) break }
                        }
                        if (!ok) { failure.compareAndSet(null, err ?: RuntimeException("piece failed")); break }
                    }
                }.also { it.start() }
            }
            threads.forEach { it.join() }
        }
        failure.get()?.let { throw RuntimeException(it.message ?: "download failed") }
        if (stopAll) throw RuntimeException("Stopped")
        if (out.length() < size) { out.delete(); throw RuntimeException("download refused (incomplete)") }
        onFrac(1f)
    }

    private fun fetchSingle(url: String, size: Long, ua: String, origin: String, out: File, onFrac: (Float) -> Unit) {
        val full = if (size > 0) "$url&range=0-${size - 1}" else url
        val c = open(full, ua, origin)
        val code = c.responseCode
        if (code !in 200..299) throw RuntimeException("download refused (HTTP $code)")
        val total = if (size > 0) size else c.contentLengthLong
        var got = 0L; var lastTick = 0L
        c.inputStream.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (stopAll) throw RuntimeException("Stopped")
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); got += n
                    val now = System.currentTimeMillis()
                    if (total > 0 && now - lastTick > 150) { lastTick = now; onFrac((got.toFloat() / total).coerceIn(0f, 1f)) }
                }
            }
        }
        if (got < 20000 || (size > 0 && got < size * 0.9)) { out.delete(); throw RuntimeException("download refused ($got bytes)") }
    }
}
