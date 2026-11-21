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

    suspend fun check(ctx: Context, quiet: Boolean = false) {
        if (state.value is St.Downloading) return
        if (!quiet) state.value = St.Checking
        val r = withContext(Dispatchers.IO) {
            try {
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
            } catch (e: Exception) { Diag.log("update check: ${e.message}"); if (!quiet) state.value = St.Failed("Couldn't reach GitHub: ${e.message}"); return@withContext null }
        } ?: return
        Store.settings().put("updateChecked", System.currentTimeMillis()); Store.save()
        val mine = try { val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0); if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode } catch (_: Exception) { 0 }
        if (r.code > mine) { state.value = St.Available(r); if (quiet) Toasts.show("Update available: ${r.version}${if (r.name.isNotBlank()) " · ${r.name}" else ""} — Settings › Updates") }
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
                val c = URL(rel.apk).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true; c.connectTimeout = 20000; c.readTimeout = 40000
                if (c.responseCode !in 200..299) throw RuntimeException("download refused (${c.responseCode})")
                val total = if (rel.size > 0) rel.size else c.contentLengthLong
                var got = 0L; var tick = 0L
                c.inputStream.use { i -> out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); got += n
                        val now = System.currentTimeMillis(); if (total > 0 && now - tick > 120) { tick = now; state.value = St.Downloading(rel, got.toFloat() / total) } }
                } }
                if (total > 0 && got < total) throw RuntimeException("the download stopped early")
                out
            }
            state.value = St.Ready(rel, f)
            install(ctx, f)
        } catch (e: Exception) { Diag.log("update download: ${e.message}"); state.value = St.Failed("Download failed: ${e.message}") }
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
