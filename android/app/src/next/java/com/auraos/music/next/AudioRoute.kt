package com.auraos.music.next

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bluetooth earbuds and speakers play the sound a fraction of a second after the phone has handed it over (often 150-250 ms), and the phone can't measure
 * how long. While one is connected the lyrics are held back by a set amount (Settings > Lyrics) so the words light up when they are heard, not when they are sent.
 */
object AudioRoute {
    val bluetooth = MutableStateFlow(false)
    private var started = false
    // A2DP, hearing aid, BLE headset / speaker / broadcast (written as numbers so older Androids don't mind)
    private val BT_TYPES = setOf(8, 23, 26, 27, 30)

    fun start(ctx: Context) {
        if (started) return
        started = true
        try {
            val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            fun update() { bluetooth.value = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BT_TYPES } }
            update()
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = update()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = update()
            }, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) { }
    }

    /** how far behind the phone's clock the listener hears the song, in ms */
    fun delayMs(): Long = if (bluetooth.value) Cfg.btDelay.v.value.toLong() else 0L
}
