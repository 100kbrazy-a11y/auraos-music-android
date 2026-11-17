package com.auraos.music.next

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class Track(val id: String, val title: String, val artist: String, val album: String, val thumb: String, val track: Int = 0)

/**
 * The tiny hidden browser. One WebView, loaded as https://www.youtube.com, running assets/engine/engine.js (YouTube.js +
 * the proof-of-origin check). It is created by [Engine] only when a search/download needs it and destroyed right after.
 * Its network traffic goes out through native HttpURLConnection; the audio file itself is downloaded natively too.
 */
class YtEngine(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private val ready = CompletableDeferred<Unit>()
    private val calls = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val seq = AtomicInteger(1)
    private var net: ExecutorService? = Executors.newFixedThreadPool(4)
    @Volatile private var started = false
    @Volatile private var opening = false

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun open() {
        if (opening) { withTimeout(60000) { ready.await() }; return }
        opening = true
        val js = withContext(Dispatchers.IO) { ctx.assets.open("engine/engine.js").bufferedReader().use { it.readText() } }
        withContext(Dispatchers.Main) {
            val w = WebView(ctx.applicationContext)
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.addJavascriptInterface(Bridge(), "Android")
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (started) return
                    started = true
                    view.evaluateJavascript(js) { ready.complete(Unit) }
                }
            }
            web = w
            w.loadDataWithBaseURL("https://www.youtube.com/", "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
        }
        withTimeout(60000) { ready.await() }
    }

    suspend fun call(fn: String, argJson: String, timeoutMs: Long = 120000): String {
        val id = seq.getAndIncrement()
        val d = CompletableDeferred<String>()
        calls[id] = d
        withContext(Dispatchers.Main) {
            web?.evaluateJavascript("__eng.run($id,${JSONObject.quote(fn)},${JSONObject.quote(argJson)})", null)
                ?: d.completeExceptionally(RuntimeException("The YouTube page is closed"))
        }
        try { return withTimeout(timeoutMs) { d.await() } } finally { calls.remove(id) }
    }

    fun close() {
        for ((_, d) in calls) d.completeExceptionally(RuntimeException("closed"))
        calls.clear()
        net?.shutdownNow(); net = null
        main.post {
            web?.let { w ->
                try { w.stopLoading(); w.removeJavascriptInterface("Android"); w.loadUrl("about:blank"); w.clearHistory(); w.destroy() } catch (_: Exception) { }
            }
            web = null
        }
    }

    private fun send(js: String) { main.post { web?.evaluateJavascript(js, null) } }

    private inner class Bridge {
        @JavascriptInterface fun result(id: Int, json: String) { calls[id]?.complete(json) }
        @JavascriptInterface fun fail(id: Int, msg: String) { calls[id]?.completeExceptionally(RuntimeException(msg)) }
        @JavascriptInterface fun log(m: String) { Log.d("StrataEngine", m) }
        @JavascriptInterface fun fetch(id: Int, url: String, method: String, headers: String, body: String) {
            net?.execute {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.requestMethod = method
                    c.connectTimeout = 20000; c.readTimeout = 40000
                    val h = JSONObject(headers)
                    for (k in h.keys()) {
                        val lk = k.lowercase()
                        if (lk == "accept-encoding" || lk == "host" || lk == "content-length" || lk == "connection") continue
                        c.setRequestProperty(k, h.getString(k))
                    }
                    if (method != "GET" && method != "HEAD") {
                        c.doOutput = true
                        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = c.responseCode
                    val stream = if (code >= 400) c.errorStream else c.inputStream
                    val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                    val hs = JSONObject()
                    for ((k, v) in c.headerFields) if (k != null && v != null && v.isNotEmpty()) hs.put(k, v.joinToString(", "))
                    send("__fx($id,$code,${JSONObject.quote(hs.toString())},${JSONObject.quote(Base64.encodeToString(bytes, Base64.NO_WRAP))})")
                } catch (e: Exception) {
                    send("__fxe($id,${JSONObject.quote(e.message ?: e.javaClass.simpleName)})")
                }
            }
        }
    }
}

/** Hands out the hidden browser: opened on first use, closed [IDLE_MS] after the last job. Otherwise nothing runs. */
object Engine {
    private const val IDLE_MS = 8000L
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var eng: YtEngine? = null
    private var users = 0
    private var closer: Job? = null

    suspend fun <T> use(block: suspend (YtEngine) -> T): T {
        val e = mutex.withLock {
            closer?.cancel(); closer = null
            users++
            eng ?: YtEngine(Store.app).also { eng = it }
        }
        try {
            e.open()
            return block(e)
        } catch (x: Exception) {
            mutex.withLock { if (eng === e) { e.close(); eng = null } }
            throw x
        } finally {
            mutex.withLock {
                users--
                if (users == 0) closer = scope.launch {
                    delay(IDLE_MS)
                    mutex.withLock { if (users == 0) { eng?.close(); eng = null } }
                }
            }
        }
    }

    suspend fun search(q: String, type: String): List<JSONObject> = use { e ->
        val arr = org.json.JSONArray(e.call("search", JSONObject().put("q", q).put("type", type).toString()))
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    suspend fun tracks(arg: JSONObject): Pair<String, List<Track>> = use { e ->
        val j = JSONObject(e.call("tracks", arg.toString()))
        val a = j.optJSONArray("tracks") ?: org.json.JSONArray()
        j.optString("title") to (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Track(o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optString("thumb"), o.optInt("track"))
        }
    }

    suspend fun artist(id: String): JSONObject = use { e -> JSONObject(e.call("artist", JSONObject.quote(id))) }
}
