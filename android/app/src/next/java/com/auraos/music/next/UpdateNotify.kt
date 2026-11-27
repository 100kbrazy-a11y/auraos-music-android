package com.auraos.music.next

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hearing about updates without opening Settings:
 *  - on a fresh start the app asks GitHub and, if there is a newer version, shows a popup (with Update / Later)
 *  - every 10 minutes (an inexact alarm that is allowed to run while the phone dozes) the newest release is looked up
 *    and a notification says so, once per version. Android may stretch the gap on a phone that is dozing or has battery
 *    limits on this app, and nothing runs after "Force stop" until the app is opened again.
 */
object UpdateNotify {
    private const val PREFS = "update_notify"
    private const val CH = "updates"
    private const val EVERY_MS = 10 * 60 * 1000L
    const val ACTION = "com.auraos.music.next.UPDATE_CHECK"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply(); if (on) schedule(c) else cancel(c) }

    private fun pending(c: Context): PendingIntent =
        PendingIntent.getBroadcast(c, 77, Intent(c, UpdateReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** the next look in 10 minutes (the receiver schedules the one after that) */
    fun schedule(c: Context) {
        if (!enabled(c)) return
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + EVERY_MS
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c)) else am.set(AlarmManager.RTC_WAKEUP, at, pending(c))
    }
    fun cancel(c: Context) { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c)) }

    /** looks once (blocking, no app data needed) and posts the notification if this version hasn't been announced yet */
    fun checkAndNotify(c: Context) {
        val rel = try { Updater.latest() } catch (e: Exception) { android.util.Log.w("UpdateNotify", "check failed: ${e.message}"); null } ?: return
        if (rel.code <= Updater.installedCode(c)) return
        if (prefs(c).getString("announced", "") == rel.version) return
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Updates", NotificationManager.IMPORTANCE_HIGH).apply { description = "New versions of Strata Music" })
        val open = PendingIntent.getActivity(c, 78, Intent(c, NextActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = (if (rel.name.isNotBlank()) rel.name else "A new version is ready") + " · tap to update"
        val n = NotificationCompat.Builder(c, CH).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("New update for Strata ${rel.version} out now!! 😎🔥😁")
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body + (rel.notes.firstOrNull()?.let { "\n• $it" } ?: "")))
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        nm.notify(7001, n)
        prefs(c).edit().putString("announced", rel.version).apply()
    }

    /** a fresh start: look now and, if there's something newer, say so in a popup */
    suspend fun onLaunch(ctx: Context) {
        schedule(ctx)
        Updater.check(ctx, quiet = true)
        val s = Updater.state.value
        if (s !is Updater.St.Available) return
        while (Dialogs.cur.value != null) delay(600)          // (wait until nothing else is on screen)
        Dialogs.show { UpdatePopup(s.rel) }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val app = c.applicationContext
        UpdateNotify.schedule(app)                     // (the chain goes on whatever happens next: also after a restart or an update of the app)
        if (i.action != UpdateNotify.ACTION) return
        val r = goAsync()
        Thread { try { UpdateNotify.checkAndNotify(app) } finally { r.finish() } }.start()
    }
}

/** "New update": the version, its name and notes, and an Update button that downloads and then installs */
@Composable
fun UpdatePopup(rel: Updater.Rel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    val a = accent
    DTitle("New update · ${rel.version}")
    if (rel.name.isNotBlank()) T(rel.name, 17.sp, W800, a, lines = 2, modifier = Modifier.padding(bottom = 8.dp))
    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
        for (n in rel.notes.take(8)) Row(Modifier.padding(vertical = 3.dp)) { T("•", 14.sp, W700, a, modifier = Modifier.width(16.dp)); T(n, 14.sp, color = C.text.copy(alpha = 0.85f), lines = 6) }
    }
    Box(Modifier.height(12.dp))
    when (val s = st) {
        is Updater.St.Downloading -> {
            T("Downloading… ${(s.frac * 100).toInt()}%", 13.sp, color = C.sub)
            Box(Modifier.padding(top = 6.dp, bottom = 12.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.surface3)) { Box(Modifier.fillMaxHeight().fillMaxWidth(s.frac).background(a)) }
            DButtons("Hide" to { Dialogs.close() })
        }
        is Updater.St.Ready -> DButtons("Later" to { Dialogs.close() }, "Install" to { Updater.install(ctx, s.file) })
        else -> DButtons("Later" to { Dialogs.close() }, "Update" to { scope.launch { Updater.download(ctx, rel) } })
    }
}
