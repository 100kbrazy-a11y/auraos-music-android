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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache
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
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.08).build() }
                .allowHardware(false)
                .build()
        )
        Prefs.init(this)
        if (savedInstanceState == null) Cfg.startTab.v.value.let { st -> if (st >= 0) Nav.tab.value = TabsCfg.order.value.indexOf(st).coerceAtLeast(0) }
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
        // the glass is moved right before each frame is drawn, after everything is laid out: it can't trail a frame behind
        root.viewTreeObserver.addOnPreDrawListener { Liquid.frame(this); true }
        setContentView(root)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private fun handle(i: Intent?) {
        i?.getStringExtra("open")?.let { o -> Tour.open(this, o); return }
        if (i?.action == Intent.ACTION_SEND) { val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return; Nav.stack.clear(); Nav.open(Page.Search); Yt.link(t) }
    }
    override fun onStart() { super.onStart(); VizHold.fg = true; VizHold.refresh(NativePlayer.state.value.playing, Prefs.motion.value && !Prefs.saving.value) }
    override fun onStop() { super.onStop(); VizHold.fg = false; VizHold.refresh(false, false); Ctl.save(); Store.save(true) }
}

object Snack { val text = mutableStateOf<String?>(null) }
/** each tab reports whether it's scrolled, so the header (on the controls layer) can darken */
object TabScroll { val v = mutableStateListOf(false, false, false, false) }
/** set by the controls layer: the bottom layer's pager follows it */
object Pager {
    var animateTo: ((Int) -> Unit)? = null
    /** where the tab pages are right now (2.5 = halfway from Songs to Playlists), and whether a finger is moving them */
    val pos = mutableFloatStateOf(0f)
    val user = mutableStateOf(false)
}

@Composable
fun Themed(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = C.bg, surface = C.surface, onSurface = C.text, onBackground = C.text)) {
        val songs by Store.songs.collectAsState()
        val st by NativePlayer.state.collectAsState()
        val q by Ctl.queue.collectAsState()
        val cur = q.getOrNull(st.index)?.let { s -> songs.firstOrNull { it.id == s.id } ?: s }
        // the whole app takes the playing song's cover colour (like --accent)
        val follow by Prefs.coverAccent.collectAsState(); val fixed by Prefs.accent.collectAsState()
        val fromCover = remember(cur?.cover) { cur?.cover?.takeIf { it.isNotEmpty() }?.let { Accents.of(Store.coverFile(it)) } }
        val target = if (follow) fromCover ?: C.defAccent else androidx.compose.ui.graphics.Color(fixed)
        val acc by animateColorAsState(target, tween(600), label = "acc")
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        CompositionLocalProvider(LocalAccent provides acc, LocalBottom provides (if (isLand()) (if (cur != null) 96.dp else 24.dp) else if (cur != null) 163.dp else 100.dp) + nav) { content() }
    }
}

/** the bottom layer: the living background, the four tabs and the pages opened over them (what the glass looks through) */
@Composable
fun BgApp() {
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val order by TabsCfg.order.collectAsState()
    val pager = rememberPagerState(initialPage = Nav.tab.value.coerceIn(0, order.size - 1)) { order.size }
    LaunchedEffect(order) { if (Nav.tab.value > order.size - 1) Nav.tab.value = order.size - 1 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Pager.animateTo = { i -> scope.launch { pager.animateScrollToPage(i) } } }
    LaunchedEffect(Nav.tab.value) { if (pager.currentPage != Nav.tab.value || pager.currentPageOffsetFraction != 0f) pager.animateScrollToPage(Nav.tab.value) }
    // the tab bubble rides along with the pages while you swipe them (not only once they land)
    val swiping by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(swiping) { if (swiping) Pager.user.value = true }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction }.collect { Pager.pos.floatValue = it } }
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress) { if (!pager.isScrollInProgress) Pager.user.value = false }
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress) { if (!pager.isScrollInProgress && Nav.tab.value != pager.settledPage) { Nav.tab.value = pager.settledPage; Hap.tick() } }   // only where a swipe lands
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Aurora(motion && !saving)
        val land = isLand()
        val railPad by animateDpAsState(if (land) Rail + 22.dp else 0.dp, tween(380), label = "rail")
        CompositionLocalProvider(LocalBgLayer provides true) {
        Box(Modifier.fillMaxSize().nestedScroll(Tuck.watch).padding(end = railPad)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            when (order.getOrElse(page) { 2 }) { 0 -> AlbumsScreen(); 1 -> ArtistsScreen(); 2 -> SongsScreen(); else -> PlaylistsScreen() }
        }
        Nav.stack.forEachIndexed { i, p ->
            key(i, p) {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                AnimatedVisibility(shown, enter = slideInHorizontally(tween(320, easing = Ease)) { it / 3 } + fadeIn(tween(200)), exit = fadeOut()) {
                    Box(Modifier.fillMaxSize().background(C.bg)) { CompositionLocalProvider(LocalPageIndex provides i) {
                        when (p) {
                            is Page.AlbumP -> AlbumPage(p.key); is Page.ArtistP -> ArtistPage(p.name); is Page.PlaylistP -> PlaylistPage(p.name)
                            Page.Favorites -> FavoritesPage(); Page.Search -> SearchPage(); Page.Settings -> SettingsPage(); Page.Scanner -> ScannerPage()
                            is Page.SettingsSection -> SettingsSectionPage(p.id)
                        }
                    } }
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
    val vMotion by Prefs.motion.collectAsState(); val vSaving by Prefs.saving.collectAsState()
    LaunchedEffect(st.playing, vMotion, vSaving) { VizHold.refresh(st.playing, vMotion && !vSaving) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(1400); Onboard.check() }
    val q by Ctl.queue.collectAsState()
    val busy by Store.busy.collectAsState()
    val awake by Prefs.keepAwake.collectAsState()
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    var full by NpState.open
    val cur = q.getOrNull(st.index)?.let { s -> songs.firstOrNull { it.id == s.id } ?: s }
    val acc = accent
    val liquid by Prefs.liquid.collectAsState()
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
    LaunchedEffect(Unit) { while (true) { val was = Prefs.saving.value; Prefs.saving.value = Prefs.autoSaver.value && Tasks.saver(ctx); if (Prefs.saving.value != was || was == false && Diag.lines.value.none { it.contains("motion check") }) Diag.log("motion check: ${Tasks.motionWhy(ctx)}"); delay(60000) } }
    LaunchedEffect(awake, full) {
        val w = (ctx as? Activity)?.window ?: return@LaunchedEffect
        if (awake && full) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    var lastId by remember { mutableStateOf("") }
    var lastCoverId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cur?.id) {
        val c = cur ?: return@LaunchedEffect
        if (lastId.isNotEmpty() && lastId != c.id && Sleep.endOfSong.value) { Sleep.endOfSong.value = false; NativePlayer.toggle() }
        if (lastId.isNotEmpty() && lastId != c.id) Store.update(listOf(c.id)) { put("plays", optInt("plays") + 1); put("lastPlayed", System.currentTimeMillis()) }
        lastId = c.id
        if (c.cover != lastCoverId) {
            lastCoverId = c.cover
            val bytes = if (c.cover.isEmpty()) null else withContext(Dispatchers.IO) { try { Store.coverFile(c.cover).readBytes() } catch (_: Exception) { null } }
            NativePlayer.artwork(c.id, bytes)
        }
        Ctl.save()
    }
    LaunchedEffect(full) { if (full) { Morph.closing = false; Morph.f.animateTo(1f, spring(0.82f, 320f)) } else if (Morph.f.value > 0f) Morph.toDock() }
    LaunchedEffect(Unit) { NativePlayer.errors.collect { Toasts.show("Couldn't play: $it"); Diag.log("playback: $it") } }
    LaunchedEffect(Unit) { while (true) { delay(5000); if (NativePlayer.state.value.playing) Ctl.save() } }
    BackHandler(enabled = full || Nav.stack.isNotEmpty() || Sel.ids.isNotEmpty()) {
        Hap.soft(); when { full -> full = false; Sel.ids.isNotEmpty() -> Sel.ids.clear(); else -> Nav.back() }
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { Morph.screen = it.boundsInRoot() }) {
        // the four tabs' header: title + the glass pill of tools (pages draw their own header)
        val land = isLand()
        Box(Modifier.padding(end = if (land) Rail + 22.dp else 0.dp)) {
        if (Nav.stack.isEmpty()) {
            val tid = TabsCfg.order.collectAsState().value.getOrElse(Nav.tab.value) { 2 }
            Head(TabsCfg.NAMES[tid], TabScroll.v[tid]) { Tools(sortTab = tid) }
        } else {
            // a page's header is drawn up here too, so its bar can be real glass over the page
            val top = Nav.stack.lastIndex
            PageHeads.specs[top]?.let { h -> key(top, Nav.stack.last()) { HeadBar(h.title, h.scrolled, true, h.actions) } }
        }
        }
        AnimatedVisibility(Sel.ids.isNotEmpty(), enter = slideInVertically { -it }, exit = slideOutVertically { -it }) { SelectionBar() }
        Column(Modifier.align(Alignment.BottomCenter).padding(end = if (land) Rail + 24.dp else 0.dp, bottom = if (land) 86.dp else 0.dp)) {
            if (busy.isNotEmpty()) Box(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).align(Alignment.CenterHorizontally).liquid("busy", 14.dp, C.surface2.copy(alpha = 0.4f), z = 4).clip(RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) { T(busy, 13.sp, W650) }
            AnimatedVisibility(Snack.text.value != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.liquid("snack", 14.dp, Color(0xFFECECF0).copy(alpha = 0.42f), z = 4).clip(RoundedCornerShape(14.dp)).heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                        T(Snack.text.value ?: "", 15.sp, W550, Color(0xFF19191D), lines = 3)
                    }
                }
            }
            if (!land) AnimatedVisibility(!Nav.stack.lastOrNull().hidesDock, enter = slideInVertically { it }, exit = slideOutVertically { it }) {   // the bottom bar steps away in Settings
                Dock(cur, Nav.tab.value, { i -> Hap.tick(); Nav.stack.clear(); Nav.tab.value = i }) { full = true }
            }
        }
        if (land) LandDock(cur, Nav.tab.value, { i -> Hap.tick(); Nav.stack.clear(); Nav.tab.value = i }) { full = true }
        if (cur != null && Morph.f.value > 0.001f) PlayerPanel(cur) { full = false }
        if (cur != null && Morph.carrying) FloatingMini(cur)
        OnboardingOverlay()
        SheetHost()
    }
}
