@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.auraos.music.next

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraos.music.NativePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Strata Music, all native (Kotlin + Compose). */
class NextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Prefs.init(this)
        NativePlayer.start(this)
        if (savedInstanceState == null) Ctl.restore(this)
        handle(intent)
        setContent { Theme { App() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) { val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return; Nav.stack.clear(); Nav.tab.value = 4; GetState.link(t) }
    }
    override fun onStop() { super.onStop(); Ctl.save(); Store.save(true) }
}

@Composable
fun Theme(content: @Composable () -> Unit) {
    val accent by Prefs.accent.collectAsState()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(accent), onPrimary = Color(0xFF14101E), background = Color(0xFF0B0B0E),
            surface = Color(0xFF14141A), onSurface = Color(0xFFEDEDF2), onBackground = Color(0xFFEDEDF2),
            surfaceVariant = Color(0xFF1E1E26), onSurfaceVariant = Color(0xFFA8A8B8), secondaryContainer = Color(0xFF2A2638),
        ),
    ) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by Store.songs.collectAsState()
    val st by NativePlayer.state.collectAsState()
    val q by Ctl.queue.collectAsState()
    val busy by Store.busy.collectAsState()
    val awake by Prefs.keepAwake.collectAsState()
    var full by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    val cur = q.getOrNull(st.index)?.let { s -> songs.firstOrNull { it.id == s.id } ?: s }
    val pager = rememberPagerState(initialPage = Nav.tab.value) { 6 }
    val snack = remember { SnackbarHostState() }
    val dialog by Dialogs.cur
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(Unit) { Toasts.flow.collect { scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it) } } }
    LaunchedEffect(Unit) {                     // first start: bring everything over from the WebView app (once)
        if (!Migrate.done() && Store.songs.value.isEmpty()) { val r = Migrate.run(); if (r.isNotBlank() && !r.startsWith("There was nothing")) Toasts.show(r) }
        Tasks.poke()
    }
    LaunchedEffect(Unit) { while (true) { Prefs.saving.value = Prefs.autoSaver.value && Tasks.saver(ctx); delay(60000) } }
    LaunchedEffect(Nav.tab.value) { if (pager.currentPage != Nav.tab.value) pager.animateScrollToPage(Nav.tab.value) }
    LaunchedEffect(pager.currentPage) { Nav.tab.value = pager.currentPage }
    LaunchedEffect(awake, full) {
        val w = (ctx as? Activity)?.window ?: return@LaunchedEffect
        if (awake && full) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    var lastId by remember { mutableStateOf("") }
    LaunchedEffect(cur?.id) {                  // a new song: lock-screen picture, play count, "sleep at the end of the song"
        val c = cur ?: return@LaunchedEffect
        if (lastId.isNotEmpty() && lastId != c.id && Sleep.endOfSong.value) { Sleep.endOfSong.value = false; NativePlayer.toggle() }
        if (lastId.isNotEmpty() && lastId != c.id) Store.update(listOf(c.id)) { put("plays", optInt("plays") + 1); put("lastPlayed", System.currentTimeMillis()) }
        lastId = c.id
        val bytes = if (c.cover.isEmpty()) null else withContext(Dispatchers.IO) { try { Store.coverFile(c.cover).readBytes() } catch (_: Exception) { null } }
        NativePlayer.artwork(c.id, bytes)
        Ctl.save()
    }
    LaunchedEffect(Unit) { NativePlayer.errors.collect { Toasts.show("Couldn't play: $it"); Diag.log("playback: $it") } }
    LaunchedEffect(Unit) { while (true) { delay(5000); if (NativePlayer.state.value.playing) Ctl.save() } }

    BackHandler(enabled = full || Nav.stack.isNotEmpty() || Sel.ids.isNotEmpty()) {
        when { queueOpen -> queueOpen = false; full -> full = false; Sel.ids.isNotEmpty() -> Sel.ids.clear(); else -> Nav.back() }
    }
    val tabs = listOf("Songs" to Ic.Note, "Albums" to Ic.Disc, "Artists" to Ic.Person, "Playlists" to Ic.List, "Get" to Ic.Download, "Settings" to Ic.Gear)
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Column {
                if (busy.isNotEmpty()) Text(busy, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), fontSize = 12.sp)
                if (Sel.ids.isNotEmpty()) SelectionBar() else if (cur != null) MiniPlayer(cur) { full = true }
                NavigationBar {
                    tabs.forEachIndexed { i, (t, ic) ->
                        NavigationBarItem(selected = pager.currentPage == i && Nav.stack.isEmpty(), onClick = { Nav.stack.clear(); Nav.tab.value = i; scope.launch { pager.animateScrollToPage(i) } },
                            icon = { Icon(ic, size = 22.dp) }, label = { Text(t, fontSize = 10.sp, maxLines = 1) })
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> SongsScreen(); 1 -> AlbumsScreen(); 2 -> ArtistsScreen(); 3 -> PlaylistsScreen(); 4 -> GetScreen(); else -> SettingsScreen()
                }
            }
            val top = Nav.stack.lastOrNull()
            if (top != null) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                when (top) {
                    is Page.AlbumP -> AlbumPage(top.key)
                    is Page.ArtistP -> ArtistPage(top.name)
                    is Page.PlaylistP -> PlaylistPage(top.name)
                    Page.Favorites -> FavoritesPage()
                    Page.Search -> SearchPage()
                }
            }
        }
    }
    AnimatedVisibility(full && cur != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        cur?.let { FullPlayer(it, onClose = { full = false }, onQueue = { queueOpen = true }) }
    }
    if (full && queueOpen) QueueScreen { queueOpen = false }
    dialog?.invoke()
}
