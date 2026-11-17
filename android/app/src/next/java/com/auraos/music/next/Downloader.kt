package com.auraos.music.next

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Dl(val track: Track, val status: String = "Waiting", val frac: Float = 0f, val done: Boolean = false, val failed: Boolean = false)

/** Download queue. The hidden browser is only asked for the audio *address*; the file itself is fetched natively. */
object Downloader {
    val items = MutableStateFlow<List<Dl>>(emptyList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    fun enqueue(ts: List<Track>) {
        val have = items.value.filter { !it.failed }.map { it.track.id }.toSet()
        val fresh = ts.filter { it.id !in have }.map { Dl(it) }
        if (fresh.isEmpty()) return
        items.value = items.value.filter { !it.failed } + fresh
        kick()
    }
    fun clearFinished() { items.value = items.value.filter { !it.done && !it.failed } }

    private fun waiting() = items.value.firstOrNull { !it.done && !it.failed && it.status == "Waiting" }
    private fun kick() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            try {
                Engine.use { e ->
                    while (true) {
                        val next = waiting() ?: break
                        process(e, next.track)
                    }
                }
            } catch (x: Exception) {
                items.value = items.value.map { if (!it.done && !it.failed) it.copy(failed = true, status = "Couldn't start: ${x.message ?: "unknown"}") else it }
            }
            if (waiting() != null) { worker = null; kick() }
        }
    }

    private fun upd(id: String, f: (Dl) -> Dl) { items.value = items.value.map { if (it.track.id == id && !it.done && !it.failed) f(it) else it } }

    private suspend fun process(e: YtEngine, t: Track) {
        var last = ""
        for (attempt in 1..2) {
            try {
                upd(t.id) { it.copy(status = if (attempt == 1) "Asking YouTube…" else "Trying again…", frac = 0f) }
                val j = JSONObject(e.call("audio", JSONObject.quote(t.id)))
                val ext = j.optString("ext", "m4a")
                val tmp = File(Store.app.cacheDir, "yt-${t.id}.$ext")
                upd(t.id) { it.copy(status = "Downloading…") }
                fetch(j.getString("url"), j.optLong("size"), j.optString("ua"), j.optString("origin"), tmp) { f -> upd(t.id) { it.copy(frac = f) } }
                val cover = if (t.thumb.isNotBlank()) try { URL(t.thumb).readBytes() } catch (_: Exception) { null } else null
                val title = t.title.ifBlank { j.optString("title") }
                val artist = t.artist.ifBlank { j.optString("artist") }
                Store.register(tmp, ext, Meta(title = title, artist = artist, album = t.album.ifBlank { null }, track = t.track.takeIf { it > 0 }, cover = cover))
                upd(t.id) { it.copy(status = "Added", frac = 1f, done = true) }
                return
            } catch (x: Exception) { last = x.message ?: x.javaClass.simpleName }
        }
        upd(t.id) { it.copy(status = last.replace(Regex("https://\\S+"), "(address)"), failed = true) }
    }

    private fun fetch(url: String, size: Long, ua: String, origin: String, out: File, onFrac: (Float) -> Unit) {
        val full = if (size > 0) "$url&range=0-${size - 1}" else url
        val c = URL(full).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 40000
        if (ua.isNotBlank()) c.setRequestProperty("User-Agent", ua)
        if (origin.isNotBlank()) { c.setRequestProperty("Origin", origin); c.setRequestProperty("Referer", "$origin/") }
        val code = c.responseCode
        if (code !in 200..299) throw RuntimeException("download refused (HTTP $code)")
        val total = if (size > 0) size else c.contentLengthLong
        var got = 0L; var lastTick = 0L
        c.inputStream.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
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
