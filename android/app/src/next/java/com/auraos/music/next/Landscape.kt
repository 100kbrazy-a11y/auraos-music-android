package com.auraos.music.next

import android.content.res.Configuration
import com.auraos.music.NativePlayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** sideways (and wide enough): tabs become a vertical glass pill on the right, the media bar its own bubble along the bottom */
@Composable
fun isLand(): Boolean { val c = LocalConfiguration.current; return c.orientation == Configuration.ORIENTATION_LANDSCAPE && c.screenWidthDp >= 640 }
val Rail = 68.dp

/**
 * Sideways, the media bar tucks away while you scroll: it wraps down to its album cover and slides in under the tab pill (the
 * pill shrinks and moves up to make room), all at once. It comes back after the Scroll Timer; in Settings, or with Keep Media
 * Bar Tucked on, it stays tucked.
 */
object Tuck {
    val on = mutableStateOf(false)
    var land = false; var inSettings = false
    var scope: CoroutineScope? = null
    private var job: Job? = null
    fun scrolled() { if (!land) return; if (!on.value) Hap.soft(); on.value = true; restart() }
    fun restart() {
        job?.cancel()
        if (inSettings || Prefs.tuck.value) return
        job = scope?.launch { delay((Prefs.scrollTimer.value * 1000).toLong().coerceAtLeast(300)); if (on.value) { on.value = false; Hap.soft() } }
    }
    fun sync(land: Boolean, settings: Boolean) {
        this.land = land; inSettings = settings
        if (!land) { job?.cancel(); on.value = false; return }
        if (settings || Prefs.tuck.value) { job?.cancel(); on.value = true } else if (on.value) restart()
    }
    /** any list scrolling anywhere in the library or pages */
    val watch = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset { if (kotlin.math.abs(consumed.y) > 2f) scrolled(); return Offset.Zero }
    }
}

private val TuckEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun LandDock(cur: Song?, tab: Int, onTab: (Int) -> Unit, onOpen: () -> Unit) {
    val a = accent
    val d = LocalDensity.current
    val st by NativePlayer.state.collectAsState()
    val settings = Nav.stack.lastOrNull() == Page.Settings
    val pinned by Prefs.tuck.collectAsState()
    val scope = rememberCoroutineScope()
    Tuck.scope = scope
    LaunchedEffect(settings, pinned) { Tuck.sync(true, settings) }
    DisposableEffect(Unit) { onDispose { Tuck.sync(false, false) } }
    val tucked = Tuck.on.value && cur != null
    val t by animateFloatAsState(if (tucked) 1f else 0f, tween(420, easing = TuckEase), label = "tuck")
    val tMove by animateFloatAsState(if (tucked) 1f else 0f, tween(460, easing = CubicBezierEasing(0.3f, 1.15f, 0.6f, 1f)), label = "tuckm")
    val fv = Morph.f.value
    val away = Morph.carrying || fv > 0.001f
    val dockA = if (Morph.closing) 1f - smooth(Morph.fDock, Morph.fDock + 0.3f, fv) else 1f - smooth(0.05f, 0.45f, fv)
    val sheetUp = Dialogs.cur.value != null && Dialogs.isSheet.value
    val fade by animateFloatAsState(if (sheetUp) 0f else 1f, tween(200), label = "lf")
    val tint = mix(a, 0.10f, Color(0xFF1A1A20)).copy(alpha = 0.40f)
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).graphicsLayer { alpha = fade }) {
        val W = with(d) { maxWidth.toPx() }; val H = with(d) { maxHeight.toPx() }
        fun px(x: Dp) = with(d) { x.toPx() }
        val itemH = 60.dp - 9.dp * t
        val pillH = itemH * 4 + 8.dp + 6.dp
        val pillL = W - px(12.dp) - px(Rail)
        val pillT = (H - px(pillH)) / 2 - px(40.dp) * t - Morph.tug.value
        Box(Modifier.offset { IntOffset(pillL.roundToInt(), pillT.roundToInt()) }.size(Rail, pillH)
            .liquid("dock-tabs", Rail / 2, tint, z = 1, alpha = dockA * fade).clip(RoundedCornerShape(50))) { VTabs(tab, onTab, itemH) }
        if (cur != null) {
            // the bar's two shapes: the full bubble along the bottom, and just its cover tucked under the tab pill
            val l0 = px(12.dp); val r0 = W - px(Rail + 24.dp); val b0 = H - px(10.dp); val h0 = px(66.dp)
            val side = px(68.dp)
            val pillBottom1 = (H - px(51.dp * 4 + 14.dp)) / 2 - px(40.dp) + px(51.dp * 4 + 14.dp)
            val l1 = pillL + (px(Rail) - side) / 2; val t1 = minOf(pillBottom1 + px(8.dp), H - px(6.dp) - side)
            val L = l0 + (l1 - l0) * tMove; val T = (b0 - h0) + (t1 - (b0 - h0)) * tMove - Morph.tug.value
            val Wd = (r0 - l0) + (side - (r0 - l0)) * t; val Hd = h0 + (side - h0) * t
            val miniA = if (away) 0f else 1f
            Box(Modifier.offset { IntOffset(L.roundToInt(), T.roundToInt()) }.size(with(d) { Wd.toDp() }, with(d) { Hd.toDp() })
                .liquid("dock-mini", 22.dp, tint, z = 1, alpha = miniA * fade * dockA).graphicsLayer { alpha = miniA }.clip(RoundedCornerShape(22.dp))) {
                if (t < 0.55f) Box(Modifier.requiredSize(with(d) { (r0 - l0).toDp() }, 66.dp).align(Alignment.TopStart).graphicsLayer { alpha = 1f - smooth(0f, 0.55f, t) }) { MiniBar(cur, st, onOpen) }
                if (t > 0.3f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = smooth(0.3f, 0.8f, t) }
                    .clickable(remember { MutableInteractionSource() }, null) { Hap.tap(); Morph.tapOpen() }) {
                    Art(cur.cover, null, Modifier.fillMaxSize(), RoundedCornerShape(22.dp), phName = cur.album)
                }
            }
        }
    }
}

/** the vertical tab pill: a round bubble that slides (and can be dragged) between the four tabs */
@Composable
private fun VTabs(tab: Int, onTab: (Int) -> Unit, itemH: Dp) {
    val a = accent
    val d = LocalDensity.current
    val liquid by Prefs.liquid.collectAsState()
    val cell = with(d) { (itemH + 2.dp).toPx() }
    val top0 = with(d) { 4.dp.toPx() }
    var drag by remember { mutableStateOf<Float?>(null) }
    val follow = drag == null && Pager.user.value
    val target = drag ?: if (follow) Pager.pos.floatValue * cell else tab * cell
    val direct = drag != null || follow
    val ay = remember { Animatable(0f) }
    LaunchedEffect(target, direct) { if (direct) ay.snapTo(target) else ay.animateTo(target, if (liquid) spring(0.55f, 300f) else spring(0.72f, 420f)) }
    val y = if (direct) target else ay.value
    val grow by animateFloatAsState(if (drag != null) 1f else 0f, spring(0.55f, 420f), label = "vg")
    val hover = drag?.let { ((it + cell / 2) / cell).toInt().coerceIn(0, 3) } ?: if (follow) kotlin.math.round(Pager.pos.floatValue).toInt().coerceIn(0, 3) else null
    val cellS by rememberUpdatedState(cell); val onTabS by rememberUpdatedState(onTab); val tabS by rememberUpdatedState(tab)
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dragging = false
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                if (!dragging && kotlin.math.abs(ch.position.y - down.position.y) > viewConfiguration.touchSlop) { dragging = true; Hap.press() }
                if (dragging) {
                    ch.consume()
                    val before = drag?.let { ((it + cellS / 2) / cellS).toInt().coerceIn(0, 3) }
                    drag = (ch.position.y - top0 - cellS / 2).coerceIn(-cellS * 0.15f, cellS * 3.15f)
                    val now = ((drag!! + cellS / 2) / cellS).toInt().coerceIn(0, 3); if (before != null && before != now) Hap.tick()
                }
            }
            if (dragging) { val i = (((drag ?: tabS * cellS) + cellS / 2) / cellS).toInt().coerceIn(0, 3); drag = null; onTabS(i) }
        }
    }) {
        val bw = with(d) { 60.dp.toPx() } * (1f + 0.1f * grow); val bh = with(d) { itemH.toPx() } * (1f + 0.14f * grow)
        val cx = with(d) { (Rail / 2).toPx() }; val cy = top0 + y + with(d) { itemH.toPx() } / 2
        Box(Modifier.offset { IntOffset((cx - bw / 2).roundToInt(), (cy - bh / 2).roundToInt()) }.size(with(d) { bw.toDp() }, with(d) { bh.toDp() })
            .liquid("tab-bubble", with(d) { (minOf(bw, bh) / 2).toDp() }, mix(a, 0.30f, Color(0xFF2A2830)).copy(alpha = if (drag != null) 0.25f else 0.45f), z = 2))
        Column(Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("Albums" to Ic.Disc, "Artists" to Ic.Person, "Songs" to Ic.Note, "Playlists" to Ic.List).forEachIndexed { i, (label, ic) ->
                val on = (hover ?: tab) == i
                val sc by animateFloatAsState(if (on) 1.08f else 1f, tween(350, easing = Spring), label = "v$i")
                Column(Modifier.fillMaxWidth().height(itemH).clickable(remember { MutableInteractionSource() }, null) { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    Box(Modifier.height(26.dp).scale(sc), contentAlignment = Alignment.Center) { Icon(ic, tint = if (on) a else C.sub) }
                    T(label, 9.5.sp, W650, if (on) C.text else C.sub)
                }
            }
        }
    }
}
