package com.auraos.music.next

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.auraos.music.NativePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The background jobs, one song at a time, at background priority, and only when there is something to do:
 * cutting silence + finding the beat (for crossfades), compressing to Opus, filling in missing info, finding covers,
 * and on-device forced lyrics alignment.
 * They pause in Battery Saver. Nothing runs again for a song once it's done (each song is marked).
 */
object Tasks {
    val status = MutableStateFlow("")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun poke() { if (job?.isActive != true) job = scope.launch { delay(3000); run() } }

    /** battery percent (-1 unknown) and whether it's charging */
    fun battery(c: Context): Pair<Int, Boolean> {
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
        val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100); val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return (if (lvl >= 0) lvl * 100 / sc else -1) to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    }
    /** why motion is (not) running, in words */
    fun motionWhy(c: Context): String {
        val (pct, chg) = battery(c)
        val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return when {
            !Prefs.motion.value -> "Motion Effects is switched off (Settings › Look & Feel)"
            Prefs.saving.value && pm?.isPowerSaveMode == true -> "Android's battery saver is on, so motion is paused"
            Prefs.saving.value -> "battery is at $pct% and not charging, so motion is paused (Settings › Look & Feel › Battery Saver)"
            else -> "motion is running (battery ${if (pct >= 0) "$pct%" else "unknown"}${if (chg) ", charging" else ""})"
        }
    }
    fun saver(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isPowerSaveMode == true) return true
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return !charging && lvl >= 0 && lvl * 100 / sc <= 20
    }

    /** compression is heavy: it only runs while the phone is charging */
    fun charging(c: Context): Boolean {
        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    private suspend fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        while (true) {
            if (saver(Store.app)) { status.value = ""; return }
            val all = Store.songs.value
            val wantSil = Prefs.trimSilence.value || Prefs.beatMatch.value
            val sil = if (wantSil) all.firstOrNull { it.j.optInt("silenceChecked", 0) == 0 } else null
            if (sil != null) { silence(sil, all); delay(150); continue }
            val comp = if (Prefs.compress.value && charging(Store.app)) all.firstOrNull { needsCompress(it) } else null
            if (comp != null) { compress(comp, all); delay(300); continue }
            val fill = if (Prefs.autofill.value) all.firstOrNull { (it.genre.isBlank() || it.year.isBlank()) && it.j.optInt("autofilled", 0) == 0 } else null
            if (fill != null) { autofill(fill); delay(1200); continue }
            val covAlbum = if (Prefs.autofill.value) Store.albums(all.filter { it.cover.isEmpty() && it.j.optInt("coverTry", 0) < 2 }).firstOrNull() else null
            if (covAlbum != null) {
                status.value = "Finding cover · ${covAlbum.title}"
                val r = Covers.forAlbum(covAlbum)
                if (r == Covers.Result.OFFLINE) { status.value = ""; return }       // try again later
                Store.update(covAlbum.songs.map { it.id }) { put("coverTry", 2) }
                if (r == Covers.Result.FOUND) Accents.forget()
                delay(500); continue
            }
            // a cover that is too small to look good on a big screen is swapped for a sharp one (tried once per album)
            val lowAlbum = if (Prefs.autofill.value) Store.albums(all.filter { Covers.isLow(it) && it.j.optInt("coverQ", 0) == 0 }).firstOrNull() else null
            if (lowAlbum != null) {
                status.value = "Sharper cover · ${lowAlbum.title}"
                val r = Covers.forAlbum(lowAlbum, upgrade = true)
                if (r == Covers.Result.OFFLINE) { status.value = ""; return }
                Store.update(lowAlbum.songs.map { it.id }) { put("coverQ", 1) }
                if (r == Covers.Result.FOUND) Accents.forget()
                delay(500); continue
            }
            val alignSong = all.firstOrNull { ForcedAligner.needsAlign(it) }
            if (alignSong != null) {
                status.value = "Aligning lyrics · ${alignSong.title}"
                ForcedAligner.alignSong(alignSong)
                delay(250); continue
            }
            status.value = ""; return
        }
    }

    private fun silence(s: Song, all: List<Song>) {
        status.value = "Checking for silence · ${all.count { it.j.optInt("silenceChecked", 0) != 0 } + 1} of ${all.size}"
        try {
            val f = File(Store.app.filesDir, s.path)
            val r = NativePlayer.scanSilence(f, s.durMs / 1000.0)
            val dur = if (r[2] > 0) r[2] else s.durMs / 1000.0
            fun tg(a: Double, b: Double, c: Double) = if (a > 0) org.json.JSONArray().put(a).put(b).put(c) else null
            Store.update(listOf(s.id)) {
                put("silenceChecked", 1); put("tempoChecked", 1)
                put("trimStart", if (r[0] > 0.25) Math.max(0.0, r[0] - 0.02) else 0.0)
                put("trimEnd", if (dur > 0 && r[1] > 0 && dur - r[1] > 0.25) Math.min(dur, r[1] + 0.05) else 0.0)
                put("tempo", org.json.JSONObject().put("s", tg(r[3], r[4], r[5]) ?: org.json.JSONObject.NULL).put("e", tg(r[6], r[7], r[8]) ?: org.json.JSONObject.NULL))
            }
        } catch (e: Throwable) {
            Diag.log("silence: ${s.title}: ${e.message}")
            Store.update(listOf(s.id)) { put("silenceChecked", -1); put("tempoChecked", -1) }
        }
    }

    private fun needsCompress(s: Song): Boolean {
        if (s.ext in setOf("opus", "ogg", "webm", "weba")) return false
        val c = s.j.optInt("compressed", 0)
        return c == 0
    }

    private fun compress(s: Song, all: List<Song>) {
        if (Ctl.queue.value.getOrNull(NativePlayer.state.value.index)?.id == s.id) { Store.update(listOf(s.id)) { put("compressed", 0) }; return }
        status.value = "Compressing · ${s.title}"
        try {
            val f = File(Store.app.filesDir, s.path)
            val own = if (s.durMs > 0) (f.length() * 8 / s.durMs).toInt() else 999
            val kbps = minOf(160, maxOf(48, own))
            val base = File(Store.app.cacheDir, "comp-${s.id}").path
            val r = NativePlayer.compress(f, base, kbps)
            val out = File(r[0] as String).let { if (it.isAbsolute) it else File(Store.app.filesDir, it.path) }
            val size = r[1] as Long
            if (!out.exists() || size <= 0 || size >= f.length()) { out.delete(); Store.update(listOf(s.id)) { put("compressed", 2) }; return }
            val ext = out.extension
            val rel = s.file.substringBeforeLast('.') + "." + ext
            val dst = File(Store.app.filesDir, "Songs/$rel")
            if (!out.renameTo(dst)) { out.copyTo(dst, true); out.delete() }
            val saved = f.length() - size
            if (dst.path != f.path) f.delete()
            Store.update(listOf(s.id)) { put("file", rel); put("size", size); put("type", r[3] as String); put("codec", r[2] as String); put("compressed", 1) }
            Store.meta.put("compressSaved", Store.meta.optLong("compressSaved") + saved); Store.save()
        } catch (e: Throwable) {
            Diag.log("compress: ${s.title}: ${e.message}")
            Store.update(listOf(s.id)) { put("compressed", -1) }
        }
    }

    private fun autofill(s: Song) {
        status.value = "Filling in info · ${s.title}"
        try {
            val h = Online.bestSong(s)
            val f = HashMap<String, Any>()
            if (h != null) { if (s.genre.isBlank() && h.genre.isNotBlank()) f["genre"] = h.genre; if (s.year.isBlank() && h.year.isNotBlank()) f["year"] = h.year }
            if (f.isNotEmpty()) Store.edit(s.id, f)
            Store.update(listOf(s.id)) { put("autofilled", 1) }
        } catch (e: Throwable) { Store.update(listOf(s.id)) { put("autofilled", -1) } }
    }

    /** Settings › Find Missing Covers (or Find Cover Online on albums you pick) */
    fun findCovers(only: List<Album>? = null) {
        scope.launch {
            val missing = only ?: Store.albums().filter { it.cover == null }
            if (missing.isEmpty()) { Toasts.show("Every album already has a cover"); return@launch }
            status.value = "Finding covers · 0 of ${missing.size}"
            val (n, missed, offline) = Covers.forAlbums(missing) { d, t -> status.value = "Finding covers · $d of $t" }
            status.value = ""
            Store.update(missing.flatMap { a -> a.songs.map { it.id } }) { put("coverTry", 2) }
            Accents.forget()
            if (offline) Toasts.show("Couldn't reach the internet to find covers")
            else Toasts.show(if (missed.isEmpty()) "Found all $n covers" else "Found $n of ${missing.size} · ${missed.size} couldn't be matched (try Find Cover Online on them)")
            missed.forEach { Diag.log("no cover found: ${it.artist} — ${it.title}") }
        }
    }
}
