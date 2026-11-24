package com.auraos.music.next

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraos.music.NativePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The doorway to the C++ bridge (src/next/cpp/strata_discord.cpp), which talks to Discord's official Social SDK. */
object DiscordBridge {
    @Volatile var loaded = false; private set
    fun load(): Boolean {
        if (loaded) return true
        return try { System.loadLibrary("discord_partner_sdk"); System.loadLibrary("strata_discord"); loaded = true; true } catch (_: Throwable) { false }
    }
    @JvmStatic external fun nInit(appId: Long): Boolean
    @JvmStatic external fun nTick()
    @JvmStatic external fun nUpdate(name: String, details: String, state: String, startMs: Long, endMs: Long, largeImage: String, largeText: String, displayType: Int)
    @JvmStatic external fun nClear()
    @JvmStatic external fun nResult(): String
    @JvmStatic external fun nShutdown()
}

/**
 * Discord Rich Presence through Discord's official Social SDK (1.10+), the supported way on Android.
 *
 * No sign-in and no token: with the Discord app on the same phone, the SDK hands the activity to it over Android's RPC service,
 * and Discord shows "Listening to ..." on your profile. (The first version used a user token, which is a self-bot and breaks
 * Discord's rules; it is gone and [init] erases any token an older build kept.)
 *
 * All SDK calls happen on one worker thread, which also runs the SDK's callbacks once a second. It is off until Rich Presence
 * is switched on in Settings, and it is simply "unavailable" in a build that does not carry the SDK files.
 */
object DiscordRpc {
    /** Strata Music in the Discord Developer Portal (public, not a secret) */
    const val APPLICATION_ID = "1573427664281407609"

    val loggedIn = MutableStateFlow(false)      // kept for the settings screens: there is no account to sign in to any more
    val username = MutableStateFlow("")
    val status = MutableStateFlow("Off")

    @Volatile private var ok = false
    val available: Boolean get() = ok

    private var worker: Handler? = null
    private var ticking = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastKey = ""; private var lastStart = 0L; private var shown = false
    private val covers = java.util.concurrent.ConcurrentHashMap<String, String>()   // song id -> cover web address
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())
    private val coverTick = MutableStateFlow(0)

    fun init(ctx: Context) {
        // erase the account token (and name) that earlier builds kept on the phone
        try { ctx.getSharedPreferences("strata_discord_private", Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) { }
        loggedIn.value = false; username.value = ""
        if (started) return
        if (!DiscordBridge.load()) { status.value = "Not in this build"; return }
        try {
            val c = Class.forName("com.discord.socialsdk.DiscordSocialSdkInit")
            (ctx as? Activity)?.let { c.getMethod("setEngineActivity", Activity::class.java).invoke(null, it) }
        } catch (_: Throwable) { status.value = "Not in this build"; return }
        started = true; ok = true
        val th = HandlerThread("strata-discord").also { it.start() }
        worker = Handler(th.looper).also { h -> h.post { try { DiscordBridge.nInit(APPLICATION_ID.toLong()) } catch (t: Throwable) { ok = false } } }
        status.value = if (Prefs.discordRpc.value) "Waiting for Discord" else "Off"
        scope.launch {
            // one stream of everything the status depends on; it only reaches Discord when something visible changed
            kotlinx.coroutines.flow.combine(NativePlayer.state, Prefs.discordRpc, Prefs.discordAppName, Prefs.discordFormat,
                kotlinx.coroutines.flow.combine(Prefs.discordShowTimeline, Prefs.discordHidePaused, Prefs.discordShowCover, coverTick, Prefs.discordLook) { a, b, c, _, l -> Pair(Triple(a, b, c), l) }) { st, on, name, fmt, tl -> arrayOf(st, on, name, fmt, tl) }
                .collectLatest { a ->
                    @Suppress("UNCHECKED_CAST")
                    val o = a[4] as Pair<Triple<Boolean, Boolean, Boolean>, List<Int>>
                    push(a[0] as NativePlayer.St, a[1] as Boolean, a[2] as String, a[3] as Int, o.first.first, o.first.second, o.first.third, o.second)
                }
        }
    }

    private fun push(st: NativePlayer.St, on: Boolean, name: String, fmt: Int, timeline: Boolean, hidePaused: Boolean, showCover: Boolean, look: List<Int>) {
        val h = worker ?: return
        val song = if (st.id.isNotEmpty()) Store.song(st.id) else null
        if (!on || song == null || (!st.playing && hidePaused)) {
            if (shown) { shown = false; lastKey = ""; h.post { try { DiscordBridge.nClear() } catch (_: Throwable) { } } }
            if (!on) { ticking = false; status.value = "Off" } else if (song == null) status.value = "Nothing playing"
            return
        }
        // Discord draws presence text in its own font and colour (apps can't change that), so the look comes from what is
        // typed: a Unicode text style (bold, italic, script...) and a marker in front (a coloured dot, a note...)
        val t = DiscordLook.mark(look[2]) + DiscordLook.style(song.title, look[0])
        val ar = if (song.artist.isBlank()) "" else DiscordLook.mark(look[3]) + DiscordLook.style(song.artist, look[1])
        val details: String; val state: String
        when (fmt) {
            1 -> { details = t; state = listOf(ar, song.album).filter { it.isNotBlank() }.joinToString(" · ") }
            2 -> { details = listOf(ar, t).filter { it.isNotBlank() }.joinToString(" — "); state = song.album }
            else -> { details = t; state = ar }
        }
        val tip = when (look[5]) { 1 -> song.title; 2 -> song.artist.ifBlank { song.title }; 3 -> name; else -> song.album.ifBlank { song.title } }.take(128).padEnd(2)
        val shownBy = look[4].coerceIn(0, 2)
        val now = System.currentTimeMillis()
        val dur = if (st.dur > 0) st.dur else song.durMs
        val start = if (st.playing && timeline) now - st.pos else 0L
        // resend when what is shown changed, or when you seeked (the play time drifted from where Discord expects it)
        // Discord cannot read files on this phone, so the cover is looked up online (once per album) and added as soon as it is found
        val img = if (showCover) covers[song.id].orEmpty() else ""
        if (showCover && img.isEmpty() && asked.add(song.id)) scope.launch(Dispatchers.IO) {
            val u = try { Covers.webUrl(song) } catch (_: Exception) { null }
            if (u != null) { covers[song.id] = u; coverTick.value++ } else { kotlinx.coroutines.delay(60_000); asked.remove(song.id) }   // none yet: ask again in a minute, not in a loop
        }
        val key = listOf(song.id, st.playing, details, state, name, timeline, img, tip, shownBy).joinToString("|")
        val seeked = st.playing && timeline && lastStart != 0L && kotlin.math.abs(start - lastStart) > 3000
        if (key == lastKey && !seeked) return
        lastKey = key; lastStart = start; shown = true
        val end = if (start > 0 && dur > 0) start + dur else 0L
        val title = if (st.playing) name else "$name (paused)"
        h.post { try { DiscordBridge.nUpdate(title, details.take(128), state.take(128), start, end, img, tip, shownBy) } catch (_: Throwable) { ok = false } }
        if (!ticking) {
            ticking = true
            h.post(object : Runnable {
                override fun run() {
                    if (!ticking) return
                    try {
                        DiscordBridge.nTick()
                        val r = DiscordBridge.nResult()
                        status.value = when (r) { "ok" -> "Showing on your Discord profile"; "pending" -> "Waiting for Discord"; else -> "Discord said: $r" }
                    } catch (_: Throwable) { ok = false; ticking = false }
                    h.postDelayed(this, 1000)
                }
            })
        }
    }

    fun onToggle(enabled: Boolean) { }          // the stream above reacts to the switch itself
    fun onSettingsChanged() { }
    fun onPlayback(song: Song?, playing: Boolean, posMs: Long, durMs: Long) { }
}

// ------------------------------------------------------------------ UI

@Composable
fun DiscordAccountSheet() {
    SheetScroll {
        T("Discord Rich Presence", 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
        T(
            "Strata Music no longer asks for your Discord token: that broke Discord's rules and could get your account banned. " +
                "Rich Presence comes back on Discord's official SDK, with a normal \"Authorize\" screen and nothing to copy or paste.",
            13.5.sp, color = C.sub, lines = 6,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
        )
        Act(Ic.Spark, "Customize Discord Status", "App name, format, live timeline and pause behavior") {
            Dialogs.sheet { DiscordCustomizeSheet() }
        }
    }
}

@Composable
fun DiscordCustomizeSheet() {
    val rpc by Prefs.discordRpc.collectAsState()
    val appName by Prefs.discordAppName.collectAsState()
    val format by Prefs.discordFormat.collectAsState()
    val timeline by Prefs.discordShowTimeline.collectAsState()
    val hidePaused by Prefs.discordHidePaused.collectAsState()

    SheetScroll {
        T("Discord Rich Presence", 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        SSwitch(SG.Pulse, "Enable Rich Presence", "Show what you're listening to on Discord", rpc) { Prefs.setDiscordRpc(it) }
        SRow(SG.Customize, "Activity Name", "Shown after \"Listening to...\"", value = appName, chevron = true) {
            Dialogs.show {
                Prompt("Activity Name", appName, "Save") { name -> Prefs.setDiscordAppName(name) }
            }
        }
        SChoice("Display Format", listOf("Title / Artist", "Title / Artist · Album", "Artist — Title"), format) {
            Prefs.setDiscordFormat(it)
        }
        SSwitch(SG.Playback, "Show Live Timeline", "Displays the progress bar and time remaining", timeline) {
            Prefs.setDiscordShowTimeline(it)
        }
        SSwitch(SG.Motion, "Hide When Paused", "Clears your Discord status while music is paused", hidePaused) {
            Prefs.setDiscordHidePaused(it)
        }
        val look by Prefs.discordLook.collectAsState()
        T("Text colour isn't something Discord lets apps change — it always draws presence text in its own colour. What can change is the writing style and a marker in front of each line:", 13.sp, color = C.sub, lines = 4, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        SChoice("Song name style", DiscordLook.styles, look[0]) { Prefs.setDiscordLook(0, it) }
        SChoice("Artist style", DiscordLook.styles, look[1]) { Prefs.setDiscordLook(1, it) }
        SChoice("Marker before the song name (colour dots, notes)", DiscordLook.marks, look[2]) { Prefs.setDiscordLook(2, it) }
        SChoice("Marker before the artist", DiscordLook.marks, look[3]) { Prefs.setDiscordLook(3, it) }
        SChoice("Shown in your status next to your name", listOf("App name", "Artist", "Song name"), look[4]) { Prefs.setDiscordLook(4, it) }
        SChoice("Text when someone hovers the cover", listOf("Album", "Song name", "Artist", "App name"), look[5]) { Prefs.setDiscordLook(5, it) }
        DiscordLook.Preview(look)
        val cover by Prefs.discordShowCover.collectAsState()
        SSwitch(SG.Cover, "Show Album Cover", "Discord can't read files on your phone, so the cover is looked up online (iTunes, Deezer) from the song and artist names", cover) {
            Prefs.setDiscordShowCover(it)
        }
    }
}
