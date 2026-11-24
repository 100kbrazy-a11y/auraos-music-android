package com.auraos.music.next

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Keeps downloads going when you leave the app. While the queue has work, this runs as a foreground service (so Android
 * doesn't stop the app, and the audio fetches along with it) and shows one quiet progress notification. It ends itself when
 * the queue is empty.
 */
class DownloadService : Service() {
    private var job: Job? = null
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    private fun act(name: String, label: String): Notification.Action {
        val pi = PendingIntent.getService(this, name.hashCode(), Intent(this, DownloadService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun build(title: String, text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        if (total > 0) b.setProgress(total, done, false)
        b.addAction(if (Downloader.paused.value) act("resume", "Resume") else act("pause", "Pause")); b.addAction(act("stop", "Stop"))
        return b.build()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        when (i?.action) { "pause" -> Downloader.pause(); "resume" -> Downloader.resume(); "stop" -> Downloader.stop() }
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val first = build("Downloading music", "Getting ready…", 0, 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, first)
        if (job == null) {
            try {
                lock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "strata:downloads").apply { setReferenceCounted(false); acquire(3 * 3600 * 1000L) }
            } catch (_: Exception) { }
            job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                // the notification is refreshed about once a second (not on every progress tick): every refresh costs battery
                kotlinx.coroutines.flow.combine(Downloader.items, Downloader.paused) { l, _ -> l }.conflate().collect { l ->
                    val live = l.filter { !it.failed }
                    val left = live.filter { !it.done }
                    if (left.isEmpty()) { finish(); return@collect }
                    val done = live.size - left.size
                    val now = left.firstOrNull { it.status != "Waiting" } ?: left.first()
                    nm.notify(ID, build(if (Downloader.paused.value) "Paused · ${done} of ${live.size} downloaded" else "Downloading ${done + 1} of ${live.size}", listOf(now.track.title, now.status).filter { it.isNotBlank() }.joinToString(" · "), done, live.size))
                    kotlinx.coroutines.delay(800)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        job?.cancel(); job = null
        try { lock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { job?.cancel(); try { lock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }; super.onDestroy() }

    companion object {
        private const val CH = "downloads"; private const val ID = 4107
        fun start(c: Context) {
            try { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java)) }
            catch (e: Exception) { Diag.log("download service: ${e.message}") }
        }
    }
}
