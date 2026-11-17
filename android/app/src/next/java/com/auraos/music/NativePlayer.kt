package com.auraos.music

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.getcapacitor.JSObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The native UI's handle on the existing audio engine (AuraPlaybackService: two ExoPlayers, crossfade, beat matching,
 * EQ, silence cutting...). Lives in package com.auraos.music on purpose: the engine's methods are package-private, so
 * the very same code that the WebView app drives is driven from here, unchanged.
 */
object NativePlayer {
    class Item(val id: String, val path: String, val title: String, val artist: String, val album: String)
    data class St(val playing: Boolean = false, val index: Int = -1, val id: String = "", val pos: Long = 0, val dur: Long = 0, val count: Int = 0)

    val state = MutableStateFlow(St())
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var ticker: Job? = null

    fun start(ctx: Context) {
        if (started) return
        started = true
        val c = ctx.applicationContext
        // building a controller is what starts the playback service (same as the WebView app's plugin does)
        MediaController.Builder(c, SessionToken(c, ComponentName(c, AuraPlaybackService::class.java))).buildAsync()
        AuraPlaybackService.events = AuraPlaybackService.Events { name, data ->
            scope.launch {
                if (name == "error") errors.tryEmit(data.optString("message", "Playback error")) else publish(data)
            }
        }
        ticker = scope.launch {
            while (true) {
                delay(400)
                if (state.value.playing) AuraPlaybackService.instance?.let { publish(it.state()) }
            }
        }
    }

    private fun publish(d: JSObject) {
        state.value = St(d.optBoolean("playing"), d.optInt("index", -1), d.optString("id", ""), d.optLong("pos"), d.optLong("dur"), d.optInt("count"))
    }

    private suspend fun service(): AuraPlaybackService? {
        repeat(50) {
            AuraPlaybackService.instance?.let { return it }
            delay(100)
        }
        return null
    }

    suspend fun load(ctx: Context, items: List<Item>, index: Int, posMs: Long = 0, play: Boolean = true) {
        start(ctx)
        val s = service() ?: return
        val list = items.map {
            val o = JSObject()
            o.put("id", it.id); o.put("path", it.path); o.put("title", it.title); o.put("artist", it.artist); o.put("album", it.album)
            AuraPlaybackService.item(ctx.applicationContext, o)
        }
        s.crossfade = fadeSecs; s.beatMatch = fadeMatch
        s.setQueue(list, index, posMs, play, false)
    }

    fun toggle() {
        val s = AuraPlaybackService.instance ?: return
        if (state.value.playing) s.pause() else s.play()
    }
    fun next() {
        val s = AuraPlaybackService.instance ?: return
        val st = state.value
        if (st.index + 1 < st.count) s.skipTo(st.index + 1, 0, true)
    }
    fun prev() {
        val s = AuraPlaybackService.instance ?: return
        val st = state.value
        if (st.pos > 3000 || st.index <= 0) s.seek(0) else s.skipTo(st.index - 1, 0, true)
    }
    fun seek(ms: Long) { AuraPlaybackService.instance?.seek(ms) }
    /** 0 = off, 1 = all, 2 = one */
    fun repeat(mode: Int) {
        AuraPlaybackService.instance?.setRepeat(if (mode == 2) Player.REPEAT_MODE_ONE else if (mode == 1) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF)
    }
    private var fadeSecs = 0f
    private var fadeMatch = true
    fun crossfade(secs: Float, match: Boolean) { fadeSecs = secs; fadeMatch = match; AuraPlaybackService.instance?.let { it.crossfade = secs; it.beatMatch = match } }
    fun jump(i: Int) { AuraPlaybackService.instance?.skipTo(i, 0, true) }
    fun artwork(id: String, bytes: ByteArray?) { AuraPlaybackService.instance?.setArtwork(id, bytes) }
    val sleepAt = MutableStateFlow(0L)
    private var sleepJob: Job? = null
    /** Pause after [mins] minutes (0 = cancel) */
    fun sleep(mins: Int) {
        sleepJob?.cancel()
        if (mins <= 0) { sleepAt.value = 0; return }
        sleepAt.value = System.currentTimeMillis() + mins * 60000L
        sleepJob = scope.launch { delay(mins * 60000L); AuraPlaybackService.instance?.pause(); sleepAt.value = 0 }
    }
    fun eq(gains: FloatArray, pre: Float) { AuraAudioProcessor.setEq(gains, pre) }
}
