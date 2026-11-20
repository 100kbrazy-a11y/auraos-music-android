package com.auraos.music.next

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs

/**
 * Standalone Discord Gateway v10 Rich Presence client.
 * Connects directly to wss://gateway.discord.gg/?v=10&encoding=json with zero extra Gradle dependencies,
 * maintains heartbeats, and broadcasts Listening activity + live start/end timestamps.
 */
object DiscordRpc {
    private const val PREF_FILE = "strata_discord_private"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER = "user"

    val loggedIn = MutableStateFlow(false)
    val username = MutableStateFlow("")
    val status = MutableStateFlow("Not signed in")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connJob: Job? = null
    private var hbJob: Job? = null
    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()

    @Volatile private var token = ""
    @Volatile private var ready = false
    @Volatile private var lastSeq: Int? = null

    private var curSong: Song? = null
    private var curPlaying = false
    private var curPosMs = 0L
    private var curDurMs = 0L
    private var lastSentPosMs = -1L
    private var lastSentAt = 0L
    private var lastSentSongId = ""
    private var lastSentPlaying = false

    fun init(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        token = sp.getString(KEY_TOKEN, "") ?: ""
        username.value = sp.getString(KEY_USER, "") ?: ""
        loggedIn.value = token.isNotBlank()
        if (loggedIn.value && Prefs.discordRpc.value) connect()
        else status.value = if (loggedIn.value) "Signed in (paused in settings)" else "Not signed in"
    }

    fun openLoginInBrowser(ctx: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com/login")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toasts.show("Couldn't open browser: ${e.message}")
        }
    }

    fun login(ctx: Context, rawToken: String) {
        val clean = rawToken.trim().removeSurrounding("\"").removePrefix("Bot ").removePrefix("Bearer ").trim()
        if (clean.length < 20) {
            Toasts.show("That doesn't look like a valid Discord token")
            return
        }
        token = clean
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, clean).apply()
        loggedIn.value = true
        Prefs.setDiscordRpc(true)
        disconnect()
        connect()
        Toasts.show("Connecting to Discord…")
    }

    fun logout(ctx: Context) {
        disconnect()
        token = ""
        username.value = ""
        loggedIn.value = false
        status.value = "Not signed in"
        ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit().clear().apply()
        Toasts.show("Signed out of Discord")
    }

    fun onToggle(enabled: Boolean) {
        if (!loggedIn.value) return
        if (enabled) connect() else {
            clearPresence()
            disconnect()
            status.value = "Off"
        }
    }

    fun onSettingsChanged() {
        if (ready && Prefs.discordRpc.value) sendPresence(force = true)
    }

    fun onPlayback(song: Song?, playing: Boolean, posMs: Long, durMs: Long) {
        val now = System.currentTimeMillis()
        val expectedPos = if (lastSentPlaying) lastSentPosMs + (now - lastSentAt) else lastSentPosMs
        val jumped = abs(posMs - expectedPos) > 2500L
        val changed = song?.id != lastSentSongId || playing != lastSentPlaying || jumped

        curSong = song
        curPlaying = playing
        curPosMs = posMs
        curDurMs = durMs

        if (changed && ready && Prefs.discordRpc.value) {
            sendPresence(force = false)
        }
    }

    private fun connect() {
        if (token.isBlank() || !Prefs.discordRpc.value) return
        if (connJob?.isActive == true) return
        connJob = scope.launch {
            var backoff = 2000L
            while (isActive && token.isNotBlank() && Prefs.discordRpc.value) {
                try {
                    status.value = "Connecting…"
                    runSession()
                    backoff = 2000L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Diag.log("discord rpc: ${e.message}")
                    status.value = "Reconnecting…"
                } finally {
                    ready = false
                    hbJob?.cancel()
                    closeSocket()
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun disconnect() {
        ready = false
        hbJob?.cancel()
        connJob?.cancel()
        closeSocket()
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        out = null
    }

    private suspend fun runSession() {
        val s = SSLSocketFactory.getDefault().createSocket("gateway.discord.gg", 443) as SSLSocket
        s.soTimeout = 0
        s.startHandshake()
        socket = s
        val inp = BufferedInputStream(s.inputStream)
        val os = s.outputStream
        out = os

        // WebSocket HTTP Upgrade handshake
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wsKey = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
        val req = buildString {
            append("GET /?v=10&encoding=json HTTP/1.1\r\n")
            append("Host: gateway.discord.gg\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $wsKey\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("User-Agent: StrataMusic/1.0\r\n\r\n")
        }
        os.write(req.toByteArray(Charsets.UTF_8))
        os.flush()

        // Read HTTP response headers
        val hdr = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = inp.read()
            if (b < 0) throw RuntimeException("closed during handshake")
            hdr.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                else -> 0
            }
        }
        val firstLine = hdr.toString("UTF-8").lineSequence().firstOrNull() ?: ""
        if (!firstLine.contains("101")) throw RuntimeException("handshake rejected: $firstLine")

        // Read WebSocket frames
        while (currentCoroutineContext().isActive) {
            val b0 = inp.read(); if (b0 < 0) break
            val b1 = inp.read(); if (b1 < 0) break
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                len = ((inp.read() shl 8) or inp.read()).toLong()
            } else if (len == 127L) {
                len = 0L
                repeat(8) { len = (len shl 8) or (inp.read().toLong() and 0xFF) }
            }
            val mask = if (masked) ByteArray(4) { inp.read().toByte() } else null
            val payload = ByteArray(len.toInt())
            var read = 0
            while (read < payload.size) {
                val n = inp.read(payload, read, payload.size - read)
                if (n < 0) throw RuntimeException("unexpected EOF")
                read += n
            }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

            when (opcode) {
                0x1 -> handleMessage(String(payload, Charsets.UTF_8))
                0x8 -> break // Close frame
                0x9 -> sendFrame(0xA, payload) // Ping -> Pong
            }
        }
    }

    private fun handleMessage(text: String) {
        val j = JSONObject(text)
        if (!j.isNull("s")) lastSeq = j.optInt("s")
        when (j.optInt("op", -1)) {
            10 -> { // Hello -> start heartbeat + Identify
                val interval = j.optJSONObject("d")?.optLong("heartbeat_interval", 41250L) ?: 41250L
                hbJob?.cancel()
                hbJob = scope.launch {
                    delay((interval * 0.5).toLong())
                    while (isActive) {
                        sendJson(JSONObject().put("op", 1).put("d", lastSeq ?: JSONObject.NULL))
                        delay(interval)
                    }
                }
                val props = JSONObject()
                    .put("os", "Android")
                    .put("browser", "Discord Android")
                    .put("device", "Strata Music")
                val d = JSONObject()
                    .put("token", token)
                    .put("intents", 0)
                    .put("properties", props)
                sendJson(JSONObject().put("op", 2).put("d", d))
            }
            0 -> { // Dispatch
                if (j.optString("t") == "READY") {
                    ready = true
                    val u = j.optJSONObject("d")?.optJSONObject("user")
                    val name = u?.optString("global_name")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: u?.optString("username") ?: "Discord User"
                    username.value = name
                    status.value = "Connected"
                    Store.app.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit().putString(KEY_USER, name).apply()
                    sendPresence(force = true)
                }
            }
            7 -> closeSocket() // Reconnect requested
            9 -> { // Invalid session (bad/expired token)
                ready = false
                status.value = "Session expired — sign in again"
                closeSocket()
            }
        }
    }

    private fun sendPresence(force: Boolean) {
        if (!ready) return
        val s = curSong
        val activities = JSONArray()
        if (s != null && (curPlaying || !Prefs.discordHidePaused.value)) {
            val appName = Prefs.discordAppName.value.ifBlank { "Strata Music" }
            val artist = s.artist.ifBlank { "Unknown Artist" }
            val album = s.album.ifBlank { "Strata Music" }

            val (details, state) = when (Prefs.discordFormat.value) {
                1 -> s.title to listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
                2 -> "$artist — ${s.title}" to album
                else -> s.title to "by $artist"
            }

            val act = JSONObject()
                .put("name", appName)
                .put("type", 2) // 2 = Listening
                .put("details", details.take(128))
                .put("state", if (curPlaying) state.take(128) else "$state (Paused)".take(128))

            if (curPlaying && Prefs.discordShowTimeline.value && curDurMs > 0) {
                val now = System.currentTimeMillis()
                val start = (now - curPosMs).coerceAtLeast(0L)
                val end = start + curDurMs
                act.put("timestamps", JSONObject().put("start", start).put("end", end))
            }
            activities.put(act)
        }

        val d = JSONObject()
            .put("since", 0)
            .put("activities", activities)
            .put("status", "online")
            .put("afk", false)

        lastSentSongId = s?.id ?: ""
        lastSentPlaying = curPlaying
        lastSentPosMs = curPosMs
        lastSentAt = System.currentTimeMillis()
        scope.launch { sendJson(JSONObject().put("op", 3).put("d", d)) }
    }

    private fun clearPresence() {
        if (!ready) return
        val d = JSONObject().put("since", 0).put("activities", JSONArray()).put("status", "online").put("afk", false)
        scope.launch { sendJson(JSONObject().put("op", 3).put("d", d)) }
    }

    private fun sendJson(j: JSONObject) = sendFrame(0x1, j.toString().toByteArray(Charsets.UTF_8))

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val os = out ?: return
        synchronized(writeLock) {
            try {
                val frame = ByteArrayOutputStream()
                frame.write(0x80 or opcode)
                val len = payload.size
                when {
                    len < 126 -> frame.write(0x80 or len)
                    len < 65536 -> {
                        frame.write(0x80 or 126)
                        frame.write((len shr 8) and 0xFF)
                        frame.write(len and 0xFF)
                    }
                    else -> {
                        frame.write(0x80 or 127)
                        for (shift in 56 downTo 0 step 8) frame.write(((len.toLong() shr shift) and 0xFF).toInt())
                    }
                }
                val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
                frame.write(mask)
                for (i in payload.indices) frame.write(payload[i].toInt() xor mask[i % 4].toInt())
                os.write(frame.toByteArray())
                os.flush()
            } catch (_: Exception) { }
        }
    }
}

// ------------------------------------------------------------------ UI: Account Sheet, Browser Login & Customization

@Composable
fun DiscordAccountSheet() {
    val ctx = LocalContext.current
    val loggedIn by DiscordRpc.loggedIn.collectAsState()
    val user by DiscordRpc.username.collectAsState()
    val status by DiscordRpc.status.collectAsState()

    SheetScroll {
        T("Discord Account", 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
        T(
            if (loggedIn) "Signed in as ${user.ifBlank { "Discord User" }} · $status"
            else "Sign in to broadcast what you're listening to on Strata Music with a live timeline.",
            13.5.sp, color = C.sub, lines = 3,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
        )
        if (!loggedIn) {
            Act(Ic.Globe, "Sign in with Discord", "Opens Discord in your default browser") {
                DiscordRpc.openLoginInBrowser(ctx)
                Dialogs.show { DiscordBrowserLoginDialog() }
            }
            Act(Ic.Edit, "Paste Discord Token Manually", "Already have your token? Paste it directly") {
                Dialogs.show {
                    Prompt("Discord Token", "", "Connect") { tok -> DiscordRpc.login(ctx, tok) }
                }
            }
        } else {
            Act(Ic.Spark, "Customize Discord Status", "App name, format, live timeline and pause behavior") {
                Dialogs.sheet { DiscordCustomizeSheet() }
            }
            Act(Ic.Close, "Sign Out", "Disconnects Rich Presence and clears your token", danger = true) {
                DiscordRpc.logout(ctx)
            }
        }
    }
}

@Composable
fun DiscordBrowserLoginDialog() {
    val ctx = LocalContext.current
    var tok by remember { mutableStateOf("") }

    DTitle("Connect Discord")
    DText("Sign in to Discord in your browser, copy your token, and paste it below to connect Rich Presence.")
    Field(tok, { tok = it }, hint = "Paste Discord token", onDone = {
        if (tok.isNotBlank()) {
            DiscordRpc.login(ctx, tok)
            Dialogs.close()
        }
    })
    DButtons(
        "Open Browser" to { DiscordRpc.openLoginInBrowser(ctx) },
        "Cancel" to { Dialogs.close() },
        "Connect" to {
            if (tok.isNotBlank()) {
                DiscordRpc.login(ctx, tok)
                Dialogs.close()
            }
        }
    )
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
