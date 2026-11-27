package com.auraos.music.next

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from GitHub Releases. Each update is a release tagged next-vMAJOR.MINOR.PATCH whose title carries its name
 * ("Strata Music 2.1.0 — The Mirror Update") and whose notes are "- " lines; the .apk is attached. The app compares the
 * newest one with its own version, shows the name and notes, downloads the .apk and hands it to Android to install.
 */
object Updater {
    class Rel(val version: String, val name: String, val notes: List<String>, val date: String, val apk: String = "", val size: Long = 0) {
        val code: Int get() = code(version)
    }
    sealed class St {
        object Idle : St(); object Checking : St(); object UpToDate : St()
        class Available(val rel: Rel) : St(); class Downloading(val rel: Rel, val frac: Float) : St(); class Ready(val rel: Rel, val file: File) : St()
        class Failed(val msg: String) : St()
    }
    val state = MutableStateFlow<St>(St.Idle)
    private const val API = "https://api.github.com/repos/100kbrazy-a11y/auraos-music-android/releases?per_page=30"

    fun code(v: String): Int { val p = v.trim().removePrefix("v").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }; return p.getOrElse(0) { 0 } * 10000 + p.getOrElse(1) { 0 } * 100 + p.getOrElse(2) { 0 } }

    /** what's installed: the notes bundled in this build */
    fun history(ctx: Context): List<Rel> = try {
        val a = JSONObject(ctx.assets.open("release.json").bufferedReader().use { it.readText() }).getJSONArray("releases")
        (0 until a.length()).map { val o = a.getJSONObject(it); Rel(o.getString("version"), o.optString("name"), list(o.optJSONArray("notes")), o.optString("date")) }
    } catch (_: Exception) { emptyList() }
    fun current(ctx: Context): Rel {
        val v = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        return history(ctx).firstOrNull { it.version == v } ?: Rel(v, "", emptyList(), "")
    }
    private fun list(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    /** the newest release on GitHub that has an .apk (blocking; throws when GitHub can't be reached). Touches nothing but the network, so the background check can use it too. */
    fun latest(): Rel? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json"); c.connectTimeout = 15000; c.readTimeout = 20000
        if (c.responseCode != 200) throw RuntimeException("GitHub said ${c.responseCode}")
        val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("tag_name").startsWith("next-v") && !it.optBoolean("draft") }.mapNotNull { o ->
            val assets = o.optJSONArray("assets") ?: JSONArray()
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: return@mapNotNull null
            val title = o.optString("name"); val name = title.substringAfter("—", "").trim()
            Rel(o.optString("tag_name").removePrefix("next-v"), name, o.optString("body").lines().filter { it.trimStart().startsWith("- ") }.map { it.trim().removePrefix("- ") },
                o.optString("published_at").take(10), apk.optString("browser_download_url"), apk.optLong("size"))
        }.maxByOrNull { it.code }
    }

    /** the version code of the installed app */
    fun installedCode(ctx: Context): Int = try { val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0); if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode } catch (_: Exception) { 0 }

    suspend fun check(ctx: Context, quiet: Boolean = false) {
        if (state.value is St.Downloading) return
        if (!quiet) state.value = St.Checking
        val r = withContext(Dispatchers.IO) {
            try { latest() }
            catch (e: Exception) { Diag.log("update check: ${e.message}"); if (!quiet) state.value = St.Failed("Couldn't reach GitHub: ${e.message}"); return@withContext null }
        } ?: return
        Store.settings().put("updateChecked", System.currentTimeMillis()); Store.save()
        if (r.code > installedCode(ctx)) state.value = St.Available(r)
        else if (!quiet) state.value = St.UpToDate
    }

    /** a quiet check at most every 12 hours */
    suspend fun autoCheck(ctx: Context) { if (System.currentTimeMillis() - Store.settings().optLong("updateChecked") > 12 * 3600000L) check(ctx, quiet = true) }

    suspend fun download(ctx: Context, rel: Rel) {
        state.value = St.Downloading(rel, 0f)
        try {
            val f = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "Strata-Music-${rel.version}.apk")
                fetchApk(rel.apk, rel.size, out) { got, total -> state.value = St.Downloading(rel, if (total > 0) (got.toFloat() / total).coerceIn(0f, 1f) else 0f) }
                out
            }
            state.value = St.Ready(rel, f)
            install(ctx, f)
        } catch (e: Exception) { Diag.log("update download: ${e.message}"); state.value = St.Failed("Download failed: ${e.message}") }
    }

    /**
     * One connection to GitHub's download server is slow; several pieces at once, straight into place in the file, are much faster.
     * A first small request finds out the size and whether ranges are allowed; if not (or anything is odd), it is one plain download.
     */
    private fun fetchApk(url: String, expected: Long, out: File, onProg: (Long, Long) -> Unit) {
        var total = 0L
        try {
            val p = URL(url).openConnection() as HttpURLConnection
            p.instanceFollowRedirects = true; p.connectTimeout = 15000; p.readTimeout = 20000; p.setRequestProperty("Range", "bytes=0-0")
            if (p.responseCode == 206) total = p.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: 0L
            p.disconnect()
        } catch (x: Exception) { Diag.log("update probe: ${x.message}") }
        if (total <= 0 || (expected > 0 && total != expected)) { fetchPlain(url, expected, out, onProg); return }
        try { fetchPieces(url, total, out, onProg) } catch (x: Exception) { Diag.log("update pieces: ${x.message}, one piece instead"); out.delete(); fetchPlain(url, expected, out, onProg) }
    }

    private fun fetchPieces(url: String, size: Long, out: File, onProg: (Long, Long) -> Unit) {
        val piece = 2L * 1024 * 1024
        val n = ((size + piece - 1) / piece).toInt()
        val next = java.util.concurrent.atomic.AtomicInteger(0); val got = java.util.concurrent.atomic.AtomicLong(0)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null); val tick = java.util.concurrent.atomic.AtomicLong(0)
        java.io.RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(size); val ch = raf.channel
            val threads = (0 until minOf(6, n)).map {
                Thread {
                    while (failure.get() == null) {
                        val i = next.getAndIncrement(); if (i >= n) break
                        val from = i * piece; val to = minOf(size - 1, from + piece - 1)
                        var ok = false; var err: Throwable? = null
                        for (attempt in 0..2) {
                            var mine = 0L
                            try {
                                if (attempt > 0) Thread.sleep(400L * attempt)
                                val c = URL(url).openConnection() as HttpURLConnection
                                c.instanceFollowRedirects = true; c.connectTimeout = 15000; c.readTimeout = 20000; c.setRequestProperty("Range", "bytes=$from-$to")
                                if (c.responseCode != 206) throw RuntimeException("download refused (${c.responseCode})")
                                c.inputStream.use { inp ->
                                    val buf = ByteArray(128 * 1024); var pos = from
                                    while (true) {
                                        val r = inp.read(buf); if (r < 0) break
                                        ch.write(java.nio.ByteBuffer.wrap(buf, 0, r), pos); pos += r; mine += r
                                        val all = got.addAndGet(r.toLong()); val now = System.currentTimeMillis(); val lt = tick.get()
                                        if (now - lt > 120 && tick.compareAndSet(lt, now)) onProg(all, size)
                                    }
                                    if (pos <= to) throw RuntimeException("short piece")
                                }
                                ok = true; break
                            } catch (x: Throwable) { err = x; got.addAndGet(-mine) }
                        }
                        if (!ok) { failure.compareAndSet(null, err ?: RuntimeException("piece failed")); break }
                    }
                }.also { it.start() }
            }
            threads.forEach { it.join() }
        }
        failure.get()?.let { throw RuntimeException(it.message ?: "download failed") }
        if (out.length() < size) throw RuntimeException("the download stopped early")
        onProg(size, size)
    }

    private fun fetchPlain(url: String, expected: Long, out: File, onProg: (Long, Long) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 20000; c.readTimeout = 40000
        if (c.responseCode !in 200..299) throw RuntimeException("download refused (${c.responseCode})")
        val total = if (expected > 0) expected else c.contentLengthLong
        var got = 0L; var tick = 0L
        c.inputStream.use { i -> out.outputStream().use { o ->
            val buf = ByteArray(128 * 1024)
            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); got += n
                val now = System.currentTimeMillis(); if (total > 0 && now - tick > 120) { tick = now; onProg(got, total) } }
        } }
        if (total > 0 && got < total) throw RuntimeException("the download stopped early")
    }

    /** Android asks to confirm; the first time it also asks to allow Strata Music to install updates */
    fun install(ctx: Context, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            Toasts.show("Allow Strata Music to install updates, then come back and tap Install")
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
