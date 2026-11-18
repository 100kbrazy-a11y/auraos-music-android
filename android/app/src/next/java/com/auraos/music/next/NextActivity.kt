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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.animation.core.spring
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraos.music.NativePlayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Strata Music, all native (Kotlin + Compose), drawn to look and behave like the WebView app. */
class NextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val crashFile = java.io.File(filesDir, "last-crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { crashFile.writeText("Strata Music crashed (${java.util.Date()}):\n" + android.util.Log.getStackTraceString(e)) } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Store.init(this)
        Prefs.init(this)
        if (intent?.getBooleanExtra("seedDemo", false) == true) Demo.seed()
        if (intent?.hasExtra("liquid") == true) Prefs.setLiquid(intent.getBooleanExtra("liquid", true))
        NativePlayer.start(this)
        if (savedInstanceState == null) Ctl.restore(this)
        handle(intent)
        Hap.init(this)
        // three layers: library & pages | liquid glass | controls (see LiquidHost.kt)
        val root = android.widget.FrameLayout(this)
        val bg = androidx.compose.ui.platform.ComposeView(this).apply { setContent { Themed { BgApp() } } }
        val glass = android.widget.FrameLayout(this)
        val fg = androidx.compose.ui.platform.ComposeView(this).apply { setContent { Themed { FgApp() } } }
        val mp = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(bg, mp, mp); root.addView(glass, mp, mp); root.addView(fg, mp, mp)
        Liquid.attach(glass, bg)
        setContentView(root)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private fun handle(i: Intent?) {
        i?.getStringExtra("open")?.let { o -> Tour.open(this, o); return }
        if (i?.action == Intent.ACTION_SEND) { val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return; Nav.stack.clear(); Nav.open(Page.Search); Yt.link(t) }
    }
    override fun onStop() { super.onStop(); Ctl.save(); Store.save(true) }
}

object Snack { val text = mutableStateOf<String?>(null) }
/** each tab reports whether it's scrolled, so the header (on the controls layer) can darken */
object TabScroll { val v = mutableStateListOf(false, false, false, false) }
/** set by the controls layer: the bottom layer's pager follows it */
object Pager { var animateTo: ((Int) -> Unit)? = null }

@Composable
fun Themed(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = C.bg, surface = C.surface, onSurface = C.text, onBackground = C.text)) {
        val songs by Store.songs.collectAsState()
        val st by NativePlayer.state.collectAsState()
        val q by Ctl.queue.collectAsState()
        val cur = q.getOrNull(st.index)?.let { s -> songs.firstOrNull { it.id == s.id } ?: s }
        // the whole app takes the playing song's cover colour (like --accent)
        val target = remember(cur?.cover) { cur?.cover?.takeIf { it.isNotEmpty() }?.let { Accents.of(Store.coverFile(it)) } } ?: C.defAccent
        val acc by animateColorAsState(target, tween(600), label = "acc")
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        CompositionLocalProvider(LocalAccent provides acc, LocalBottom provides (if (cur != null) 163.dp else 100.dp) + nav) { content() }
    }
}

/** the bottom layer: the living background, the four tabs and the pages opened over them (what the glass looks through) */
@Composable
fun BgApp() {
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val pager = rememberPagerState(initialPage = Nav.tab.value) { 4 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Pager.animateTo = { i -> scope.launch { pager.animateScrollToPage(i) } } }
    LaunchedEffect(Nav.tab.value) { if (pager.currentPage != Nav.tab.value) pager.animateScrollToPage(Nav.tab.value) }
    LaunchedEffect(pager.settledPage) { if (Nav.tab.value != pager.settledPage) { Nav.tab.value = pager.settledPage; Hap.tick() } }   // only where it lands
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
    }
}

/** the controls layer: tab headers, selection bar, messages, the dock, the player panel, menus and dialogs */
@Composable
fun FgApp() {

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Morph.scope = scope
    val songs by Store.songs.collectAsState()
    val st by NativePlayer.state.collectAsState()
    val q by Ctl.queue.collectAsState()
    val busy by Store.busy.collectAsState()
    val awake by Prefs.keepAwake.collectAsState()
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    var full by NpState.open
    val cur = q.getOrNull(st.index)?.let { s -> songs.firstOrNull { it.id == s.id } ?: s }
    val acc = accent
    val liquid by Prefs.liquid.collectAsState()
    LaunchedEffect(liquid) { snapshotFlow { Liquid.specs.toMap() }.collect { Liquid.sync(ctx, it, liquid) } }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(Unit) { Toasts.flow.collect { Snack.text.value = it; Hap.forMessage(it) } }
    LaunchedEffect(Unit) {                     // the last crash, if there was one: shown once, with a Copy button
        val f = java.io.File(ctx.filesDir, "last-crash.txt")
        if (f.exists()) {
            val t = f.readText(); f.delete(); Diag.log(t)
            Dialogs.show {
                DTitle("Strata Music crashed last time")
                androidx.compose.foundation.layout.Box(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) { T(t, 11.sp, color = C.sub, lines = 400) }
                DButtons("Copy" to { (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("crash", t)); Toasts.show("Copied") }, "Close" to { Dialogs.close() })
            }
        }
    }
    LaunchedEffect(Snack.text.value) { if (Snack.text.value != null) { delay(3200); Snack.text.value = null } }
    LaunchedEffect(Unit) {
        if (!Migrate.done() && Store.songs.value.isEmpty()) { val r = Migrate.run(); if (r.isNotBlank() && !r.startsWith("There was nothing")) Toasts.show(r) }
        Tasks.poke()
        Updater.autoCheck(ctx)
    }
    LaunchedEffect(Unit) { while (true) { Prefs.saving.value = Prefs.autoSaver.value && Tasks.saver(ctx); delay(60000) } }
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
    LaunchedEffect(full) { if (full) Morph.f.animateTo(1f, spring(0.82f, 320f)) else Morph.f.animateTo(0f, tween(380, easing = Ease)) }
    LaunchedEffect(Unit) { NativePlayer.errors.collect { Toasts.show("Couldn't play: $it"); Diag.log("playback: $it") } }
    LaunchedEffect(Unit) { while (true) { delay(5000); if (NativePlayer.state.value.playing) Ctl.save() } }
    BackHandler(enabled = full || Nav.stack.isNotEmpty() || Sel.ids.isNotEmpty()) {
        Hap.soft(); when { full -> full = false; Sel.ids.isNotEmpty() -> Sel.ids.clear(); else -> Nav.back() }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { Morph.screen = it.boundsInRoot() }) {
        // the four tabs' header: title + the glass pill of tools (pages draw their own header)
        if (Nav.stack.isEmpty()) {
            val t = Nav.tab.value
            Head(listOf("Albums", "Artists", "Songs", "Playlists")[t], TabScroll.v[t]) { Tools() }
        }
        AnimatedVisibility(Sel.ids.isNotEmpty(), enter = slideInVertically { -it }, exit = slideOutVertically { -it }) { SelectionBar() }
        Column(Modifier.align(Alignment.BottomCenter)) {
            if (busy.isNotEmpty()) Box(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).align(Alignment.CenterHorizontally).liquid("busy", 14.dp, C.surface2.copy(alpha = 0.5f), z = 4).clip(RoundedCornerShape(14.dp)).background(C.surface2.copy(alpha = 0.35f)).padding(horizontal = 14.dp, vertical = 8.dp)) { T(busy, 13.sp, W650) }
            AnimatedVisibility(Snack.text.value != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.liquid("snack", 14.dp, Color(0x99ECECF0), z = 4).clip(RoundedCornerShape(14.dp)).background(Color(0xFFECECF0).copy(alpha = 0.55f)).heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                        T(Snack.text.value ?: "", 15.sp, W550, Color(0xFF19191D), lines = 3)
                    }
                }
            }
            AnimatedVisibility(Nav.stack.lastOrNull() != Page.Settings, enter = slideInVertically { it }, exit = slideOutVertically { it }) {   // the bottom bar steps away in Settings
                Dock(cur, Nav.tab.value, { i -> Hap.tick(); Nav.stack.clear(); Nav.tab.value = i; Pager.animateTo?.invoke(i) }) { full = true }
            }
        }
        if (cur != null && Morph.lift.value > 0.5f && Morph.f.value <= 0.001f) FloatingMini(cur)
        if (cur != null && Morph.f.value > 0.001f) PlayerPanel(cur) { full = false }
        SheetHost()
    }
}
