package com.auraos.music.next

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Discord Rich Presence, token-free.
 *
 * The first version signed in to Discord's gateway with the user's own account token. That is a self-bot: it breaks Discord's
 * rules, can get the account banned, and asked people to copy a secret out of their browser. It is gone, and init() erases any
 * token an earlier build stored.
 *
 * The official route on Android is the Discord Social SDK (1.10 or newer). It needs a Discord application ID plus the SDK files
 * from the Developer Portal (Social SDK > Downloads, they are not on Maven), so it can only be switched on once those exist:
 *   - [APPLICATION_ID] below holds the ID;
 *   - the SDK's .aar goes in android/app/libs/;
 *   - [available] flips to true and onPlayback() forwards to the SDK's UpdateRichPresence.
 * Everything the user can already set (name, format, timeline, hide when paused) is kept in Prefs for that day.
 */
object DiscordRpc {
    /** Discord application ID from the Developer Portal. Empty until it exists. */
    const val APPLICATION_ID = ""

    val loggedIn = MutableStateFlow(false)
    val username = MutableStateFlow("")
    val status = MutableStateFlow("Waiting for Discord's official SDK")

    val available: Boolean get() = APPLICATION_ID.isNotBlank()

    fun init(ctx: Context) {
        // erase the account token (and name) that earlier builds kept on the phone
        try { ctx.getSharedPreferences("strata_discord_private", Context.MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) { }
        loggedIn.value = false; username.value = ""
        status.value = if (available) "Ready" else "Waiting for Discord's official SDK"
    }

    fun onToggle(enabled: Boolean) { }
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
    }
}
