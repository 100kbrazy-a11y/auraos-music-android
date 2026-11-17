@file:OptIn(ExperimentalFoundationApi::class)

package com.auraos.music.next

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraos.music.NativePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Strata Music, all native (Kotlin + Compose), drawn to look and behave like the WebView app. */
class NextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Store.init(this)
        Prefs.init(this)
        NativePlayer.start(this)
        if (savedInstanceState == null) Ctl.restore(this)
        handle(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = C.bg, surface = C.surface, onSurface = C.text, onBackground = C.text)) { App() }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) { val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return; Nav.stack.clear(); Nav.open(Page.Search); Yt.link(t) }
    }
    override fun onStop() { super.onStop(); Ctl.save(); Store.save(true) }
}

object Snack { val text = mutableStateOf<String?>(null) }

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by Store.songs.collectAsState()
    val st by NativePlayer.state.collectAsState()
    val q by Ctl.queue.collectAsState()
    val busy by Store.busy.collectAsState()
    val awake by Prefs.keepAwake.collectAsState()
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    var full by NpState.open
    val cur = q.getOrNull(st.index)?.let { s -> songs.firstOrNull { it.id == s.id } ?: s }
    val pager = rememberPagerState(initialPage = Nav.tab.value) { 4 }
    // the whole app takes the playing song's cover colour (like --accent)
    val target = remember(cur?.cover) { cur?.cover?.takeIf { it.isNotEmpty() }?.let { Accents.of(Store.coverFile(it)) } } ?: C.defAccent
    val acc by animateColorAsState(target, tween(600), label = "acc")
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(Unit) { Toasts.flow.collect { Snack.text.value = it } }
    LaunchedEffect(Snack.text.value) { if (Snack.text.value != null) { delay(3200); Snack.text.value = null } }
    LaunchedEffect(Unit) {
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
    LaunchedEffect(cur?.id) {
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
        when { full -> full = false; Sel.ids.isNotEmpty() -> Sel.ids.clear(); else -> Nav.back() }
    }
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    CompositionLocalProvider(LocalAccent provides acc, LocalBottom provides (if (cur != null) 163.dp else 100.dp) + nav) {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            Aurora(motion && !saving)
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                when (page) { 0 -> AlbumsScreen(); 1 -> ArtistsScreen(); 2 -> SongsScreen(); else -> PlaylistsScreen() }
            }
            Nav.stack.forEachIndexed { i, p ->
                key(i, p) {
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    AnimatedVisibility(shown, enter = slideInHorizontally(tween(320, easing = Ease)) { it / 3 } + fadeIn(tween(200)), exit = fadeOut()) {
                        Box(Modifier.fillMaxSize().background(C.bg)) {
                            when (p) {
                                is Page.AlbumP -> AlbumPage(p.key); is Page.ArtistP -> ArtistPage(p.name); is Page.PlaylistP -> PlaylistPage(p.name)
                                Page.Favorites -> FavoritesPage(); Page.Search -> SearchPage(); Page.Settings -> SettingsPage()
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(Sel.ids.isNotEmpty(), enter = slideInVertically { -it }, exit = slideOutVertically { -it }) { SelectionBar() }
            Column(Modifier.align(Alignment.BottomCenter)) {
                if (busy.isNotEmpty()) Box(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(14.dp)).background(C.surface2).padding(horizontal = 14.dp, vertical = 8.dp)) { T(busy, 13.sp, W650) }
                AnimatedVisibility(Snack.text.value != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.shadow(30.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(Color(0xFFECECF0)).heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                            T(Snack.text.value ?: "", 15.sp, W550, Color(0xFF19191D), lines = 3)
                        }
                    }
                }
                Dock(cur, pager.currentPage, { i -> Nav.stack.clear(); Nav.tab.value = i; scope.launch { pager.animateScrollToPage(i) } }) { full = true }
            }
            AnimatedVisibility(full && cur != null, enter = slideInVertically(tween(450, easing = Ease)) { it }, exit = slideOutVertically(tween(450, easing = Ease)) { it }) {
                cur?.let { FullPlayer(it) { full = false } }
            }
            SheetHost()
        }
    }
}
