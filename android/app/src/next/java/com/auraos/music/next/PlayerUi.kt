@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.auraos.music.next

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auraos.music.NativePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** the spectrum, polled only while something on screen draws it */
@Composable
fun rememberLevels(on: Boolean, bars: Int = 48): State<FloatArray> {
    val st = remember { mutableStateOf(FloatArray(bars)) }
    LaunchedEffect(on) {
        if (!on) { st.value = FloatArray(bars); return@LaunchedEffect }
        NativePlayer.viz(true)
        try {
            val prev = FloatArray(bars)
            while (isActive) {
                val b = NativePlayer.levels()
                if (b != null && b.isNotEmpty()) {
                    val n = minOf(b.size, 256); val out = FloatArray(bars)
                    for (i in 0 until bars) {
                        val a = Math.pow(n.toDouble(), i / bars.toDouble()).toInt().coerceIn(0, n - 1); val z = Math.pow(n.toDouble(), (i + 1) / bars.toDouble()).toInt().coerceIn(a + 1, n)
                        var m = 0; for (k in a until z) m = max(m, b[k].toInt() and 255)
                        out[i] = max(m / 255f, prev[i] * 0.91f); prev[i] = out[i]
                    }
                    st.value = out
                }
                withFrameNanos { }
            }
        } finally { NativePlayer.viz(VizHold.keep) }
    }
    return st
}

@Composable
fun Bars(levels: FloatArray, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = levels.size; val w = size.width / n; val bw = w * 0.62f
        for (i in 0 until n) { val h = max(2f, levels[i] * size.height); drawRoundRect(color, Offset(i * w + (w - bw) / 2, size.height - h), Size(bw, h), CornerRadius(bw / 2)) }
    }
}

/** the soft living background (#aurora): three accent clouds that drift */
@Composable
fun Aurora(moving: Boolean) {
    val a = accent
    // the drift pauses where it is (never snaps back) while the page scrolls, and is read only when drawing, not every recomposition
    val tA = remember { Animatable(0f) }
    var up by remember { mutableStateOf(true) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        while (true) { val to = if (up) 1f else 0f; tA.animateTo(to, tween((22000 * kotlin.math.abs(to - tA.value)).toInt().coerceAtLeast(300), easing = LinearEasing)); up = !up }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f }) {
        val t = tA.value
        val v = size.maxDimension
        fun blob(cx: Float, cy: Float, op: Float, k: Float) { val r = v * 0.35f * (1f + 0.15f * t * k); val c = Offset(cx + v * 0.08f * t * k, cy + v * 0.06f * t * k); drawCircle(Brush.radialGradient(listOf(a.copy(alpha = op), Color.Transparent), c, r), r, c) }
        blob(-v * 0.30f + v * 0.35f, -v * 0.35f + v * 0.35f, 0.22f, 1f)
        blob(size.width + v * 0.35f - v * 0.35f, size.height * 0.1f + v * 0.35f, 0.14f, 0.8f)
        blob(v * 0.35f, size.height + v * 0.45f - v * 0.35f, 0.12f, 0.6f)
    }
}

/** #dock: the media bar and the tab pill share one floating glass bubble; the visualizer glows behind both */
fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

@Composable
fun Dock(cur: Song?, tab: Int, onTab: (Int) -> Unit, onOpen: () -> Unit) {
    val a = accent
    val st by NativePlayer.state.collectAsState()
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val fv = Morph.f.value
    // the media bar is off the dock while you carry it, or while the player panel is up: its slot empties and the tabs stand alone
    val away = Morph.carrying || fv > 0.001f
    val landing = Morph.closing && fv < Morph.fDock + 0.12f          // the panel is nearly the bar again: the dock rejoins it
    val tint = mix(a, 0.10f, Color(0xFF1A1A20)).copy(alpha = 0.40f)
    val r = Cfg.dockRadius.use().dp
    // the corners ease (never snap) between "one bubble" and "tabs on their own"
    val joinBase by animateDpAsState(if (cur != null && (!away || landing)) 0.dp else r, tween(260, easing = FastOutSlowInEasing), label = "join")
    // the tug: only the media bar lifts (resisting), the tabs stay put, so a gap opens between them and their corners round off
    val tugPx = Morph.tug.value
    val sep = smooth(0.12f, 0.7f, (tugPx / with(LocalDensity.current) { 20.dp.toPx() }).coerceIn(0f, 1f))
    val join = maxOf(joinBase, r * sep)
    val miniBottom = r * sep
    val dockA = if (Morph.closing) 1f - smooth(Morph.fDock, Morph.fDock + 0.3f, fv) else 1f - smooth(0.05f, 0.45f, fv)
    val sheetUp = Dialogs.cur.value != null && Dialogs.isSheet.value
    val fade by animateFloatAsState(if (sheetUp) 0f else 1f, tween(200), label = "dockfade")
    Column(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth()
        .graphicsLayer { alpha = fade * dockA }) {
        if (cur != null) {
            val miniA = if (away) 0f else 1f
            Box(Modifier.fillMaxWidth().height(62.dp)
                .offset { IntOffset(0, -Morph.tug.value.roundToInt()) }                  // the bar resists upward, away from the tabs
                .liquid("dock-mini", r, tint, z = 1, alpha = miniA * fade, corners = listOf(r, r, miniBottom, miniBottom))
                .graphicsLayer { alpha = miniA }.clip(RoundedCornerShape(topStart = r, topEnd = r, bottomStart = miniBottom, bottomEnd = miniBottom))) {
                MiniBar(cur, st, onOpen)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f * (1f - sep))))
            }
        }
        Box(Modifier.fillMaxWidth()
            .offset { IntOffset(0, (Morph.tug.value * 0.12f).roundToInt()) }              // the tabs give just a touch
            .liquid("dock-tabs", r, tint, z = 1, alpha = fade * dockA, corners = listOf(join, join, r, r))
            .clip(RoundedCornerShape(topStart = join, topEnd = join, bottomStart = r, bottomEnd = r))) {
            Tabs(tab, onTab)
        }
    }
}

/**
 * The media bar → player reveal. Pull the bar up: the whole dock gives a little, like you're tugging something stuck, with a
 * buzz that grows the harder you pull. Then it lets go (a thud): the media bar comes off the dock and jumps to your finger, and a
 * panel of liquid glass slides up fast from the bottom of the screen to meet it. It stays glass until it's fully the player.
 * Let go high (or flick up) and it opens; low and it drops back into the dock.
 *  tug: the dock's give (px)   carrying: the bar is in your hand   miniY: where the carried bar is   f: the panel, 0 = gone, 1 = full
 */
object Morph {
    val tug = Animatable(0f)
    val f = Animatable(0f)
    val miniY = Animatable(0f)
    var carrying by mutableStateOf(false)
    /** on the way back down: the panel shrinks into the media bar's own slot and the dock comes back around it */
    var closing by mutableStateOf(false)
    var mini = Rect.Zero; var screen = Rect.Zero
    /** the tucked media bar's little square (sideways), and where the player is currently growing from (Zero = the bar / full width) */
    var sq = Rect.Zero
    var origin by mutableStateOf(Rect.Zero)
    /** f at which the panel's top sits exactly on the media bar's slot */
    val fDock: Float get() = (1f - mini.top / screen.height.coerceAtLeast(1f)).coerceIn(0.02f, 0.5f)
    /** back into the dock: the panel glides down to the slot, becomes the bar, and the bar is in the dock again */
    fun toDock() {
        closing = true
        scope?.launch {
            if (origin != Rect.Zero) f.animateTo(0f, tween(460, easing = Ease))      // back into the little square it grew from
            else { f.animateTo(fDock, tween(420, easing = Ease)); f.snapTo(0f) }
            closing = false; origin = Rect.Zero
        }
    }
    /** set by the app's screen: animations need its frame clock (a plain MainScope crashes) */
    var scope: kotlinx.coroutines.CoroutineScope? = null
    fun snap(v: Float) { scope?.launch { f.snapTo(v.coerceIn(0f, 1f)) } }
    fun to(v: Float) { scope?.launch { f.animateTo(v, if (v > 0.5f) spring(0.82f, 320f) else tween(380, easing = Ease)) } }
    /** a tap: a little tug, then the glass panel slides up and opens */
    fun tapOpen(fromSquare: Boolean = false) {
        origin = if (fromSquare && sq != Rect.Zero) sq else Rect.Zero
        scope?.launch {
            launch { tug.animateTo(10f * Liquid.density, tween(90, easing = FastOutSlowInEasing)); tug.animateTo(0f, spring(0.45f, 600f)) }
            delay(90); Hap.thud(); NpState.open.value = true
        }
    }
}

/**
 * Swipe a song sideways: it follows the finger, the next / previous song slides in beside it, and letting go past a quarter of the
 * width finishes the move and skips; otherwise it springs back. [content] draws one song.
 */
@Composable
fun SongSwiper(modifier: Modifier, gap: Dp = 16.dp, onDouble: (() -> Unit)? = null, onTap: (() -> Unit)? = null, curve: Boolean = false, content: @Composable (Song) -> Unit) {
    val st by NativePlayer.state.collectAsState(); val q by Ctl.queue.collectAsState()
    val rep by Prefs.repeat.collectAsState()
    val i = st.index
    val cur = q.getOrNull(i) ?: return
    val prev = q.getOrNull(i - 1) ?: if (rep == 1) q.lastOrNull()?.takeIf { q.size > 1 } else null
    val next = q.getOrNull(i + 1) ?: if (rep == 1) q.firstOrNull()?.takeIf { q.size > 1 } else null
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    var w by remember { mutableStateOf(1f) }
    val g = with(LocalDensity.current) { gap.toPx() }
    LaunchedEffect(cur.id) { dx.snapTo(0f) }
    val hasPrev by rememberUpdatedState(prev != null); val hasNext by rememberUpdatedState(next != null)
    val idx by rememberUpdatedState(i); val size by rememberUpdatedState(q.size)
    Box(modifier.onSizeChanged { w = it.width.toFloat() }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    scope.launch {
                        val t = w * 0.25f
                        // the full player's cover swings off on a curve (it tilts and shrinks as it goes, the next one swings in)
                        val sp = if (curve) w * 1.15f else w + g
                        val spec = if (curve) tween<Float>(520, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)) else tween(220, easing = FastOutSlowInEasing)
                        if (dx.value < -t && hasNext) { Hap.tap(); dx.animateTo(-sp, spec); NativePlayer.jump(if (idx + 1 < size) idx + 1 else 0) }
                        else if (dx.value > t && hasPrev) { Hap.tap(); dx.animateTo(sp, spec); NativePlayer.jump(if (idx > 0) idx - 1 else size - 1) }
                        else { dx.animateTo(0f, spring(0.75f, 400f)); return@launch }
                        delay(900); if (dx.value != 0f) dx.snapTo(0f)          // in case the player didn't move
                    }
                },
                onDragCancel = { scope.launch { dx.animateTo(0f) } },
            ) { c, d ->
                c.consume()
                val v = dx.value + d
                val edge = (v > 0 && !hasPrev) || (v < 0 && !hasNext)
                val past = kotlin.math.abs(v) > w * 0.25f && !edge; if (past != (kotlin.math.abs(dx.value) > w * 0.25f)) { if (past) Hap.tick() else Hap.soft() }
                scope.launch { dx.snapTo(if (edge) dx.value + d * 0.3f else v) }
            }
        }
        .then(if (onDouble != null || onTap != null) Modifier.pointerInput(onTap != null) {
            detectTapGestures(onDoubleTap = onDouble?.let { f -> { _: Offset -> f() } }, onTap = onTap?.let { f -> { _: Offset -> f() } })
        } else Modifier)) {
        val sp = if (curve) w * 1.15f else w + g
        fun Modifier.slot(k: Int): Modifier = if (!curve) this.offset { IntOffset((dx.value + k * sp).roundToInt(), 0) } else this.graphicsLayer {
            val r = (dx.value + k * sp) / sp
            val ar = kotlin.math.abs(r).coerceAtMost(1f)
            translationX = r * sp; rotationZ = r * 7f
            val sc = 1f - 0.12f * ar; scaleX = sc; scaleY = sc
            alpha = 1f - ar * ar
        }
        if (prev != null && dx.value > 0f) Box(Modifier.matchParentSize().slot(-1)) { content(prev) }
        Box(Modifier.matchParentSize().slot(0)) { content(cur) }
        if (next != null && dx.value < 0f) Box(Modifier.matchParentSize().slot(1)) { content(next) }
    }
}

/** the media bar's row: cover + title (they swipe sideways to skip), play / pause and next */
@Composable
fun MiniRow(s: Song, st: NativePlayer.St, swipe: Boolean = true) {
    Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val body: @Composable (Song) -> Unit = { x ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Art(x.cover, 44.dp, shape = when (Cfg.miniArt.use()) { 1 -> CircleShape; 2 -> androidx.compose.ui.graphics.RectangleShape; else -> RoundedCornerShape(10.dp) }, phName = x.album)
                Column(Modifier.weight(1f)) { T(x.title, 15.sp, W700); if (Cfg.miniArtist.use()) T(x.artist.ifBlank { "Unknown Artist" }, 13.sp, color = C.sub) }
            }
        }
        if (swipe) SongSwiper(Modifier.weight(1f).fillMaxHeight().clipToBounds(), 24.dp) { x -> body(x) } else Box(Modifier.weight(1f).fillMaxHeight()) { body(s) }
        IB(if (st.playing) Ic.Pause else Ic.Play) { Hap.press(); NativePlayer.toggle() }
        if (Cfg.miniNext.use()) IB(Ic.Next) { NativePlayer.next() }
    }
}

@Composable
internal fun MiniBar(s: Song, st: NativePlayer.St, onOpen: () -> Unit) {
    val a = accent
    val d = LocalDensity.current
    val tugMax = with(d) { 20.dp.toPx() }; val tugK = with(d) { 55.dp.toPx() }; val breakAt = with(d) { 120.dp.toPx() }; val barH = with(d) { 62.dp.toPx() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { if (!Morph.carrying) Morph.mini = it.boundsInRoot() }
        .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { Hap.tap(); Morph.tapOpen(false) }
        .pointerInput(Unit) {
            val vt = androidx.compose.ui.input.pointer.util.VelocityTracker()
            var up = 0f; var broke = false; var lastBuzz = 0f; var origin = 0f
            fun chase(fingerY: Float) {
                val top = fingerY - barH / 2
                val h = Morph.screen.height.coerceAtLeast(1f)
                Morph.scope?.launch { Morph.miniY.animateTo(top, spring(1f, 1600f)) }                       // the bar sticks to your finger
                Morph.scope?.launch { Morph.f.animateTo((1f - (top - 8f * Liquid.density) / h).coerceIn(0f, 1f), spring(0.9f, 700f)) }   // the panel slides up to it
            }
            detectVerticalDragGestures(
                onDragStart = { Morph.origin = Rect.Zero; up = 0f; broke = false; lastBuzz = 0f; vt.resetTracking(); origin = Morph.mini.top },
                onDragEnd = {
                    val v = vt.calculateVelocity().y
                    if (broke) {
                        Morph.carrying = false
                        if (v < -700f || Morph.f.value > 0.42f) { Hap.soft(); Morph.closing = false; onOpen() } else { Hap.soft(); Morph.toDock() }
                    } else Morph.scope?.launch { Morph.tug.animateTo(0f, spring(0.4f, 600f)) }
                },
                onDragCancel = { Morph.carrying = false; Morph.scope?.launch { Morph.tug.animateTo(0f, spring(0.4f, 600f)); Morph.f.animateTo(0f) } },
            ) { c, dy ->
                c.consume(); vt.addPosition(c.uptimeMillis, c.position); up -= dy
                val fingerY = origin + c.position.y
                if (!broke) {
                    val k = (up / breakAt).coerceIn(0f, 1f)
                    Morph.scope?.launch { Morph.tug.snapTo(tugMax * (1f - kotlin.math.exp(-kotlin.math.max(up, 0f) / tugK))) }
                    // the tug: buzzes come closer together and get stronger as you pull
                    if (up > 0 && up - lastBuzz > with(d) { (18.dp - 13.dp * k).toPx() }) { lastBuzz = up; Hap.tug(k) }
                    if (up > breakAt) {
                        broke = true; Hap.thud()
                        Morph.scope?.launch { Morph.miniY.snapTo(Morph.mini.top - Morph.tug.value); Morph.f.snapTo(0f) }
                        Morph.carrying = true
                        Morph.scope?.launch { Morph.tug.animateTo(0f, spring(0.35f, 700f)) }                       // the dock springs back
                        chase(fingerY)
                    }
                } else chase(fingerY)
            }
        }) {
        MiniRow(s, st)
        if (Cfg.miniProgress.use()) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 1.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (st.dur > 0) (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) else 0f).background(a))
        }
    }
}

/** the media bar, off the dock, in your hand (only while you carry it: let go and it's gone, so there are never two covers) */
@Composable
fun FloatingMini(s: Song) {
    val st by NativePlayer.state.collectAsState()
    val d = LocalDensity.current
    val m = Morph.mini
    if (m == Rect.Zero || !Morph.carrying) return
    Box(Modifier.offset { IntOffset(m.left.roundToInt(), Morph.miniY.value.roundToInt()) }.size(with(d) { m.width.toDp() }, with(d) { m.height.toDp() })
        .liquid("mini-float", 22.dp, mix(accent, 0.10f, Color(0xFF1A1A20)).copy(alpha = 0.40f), z = 10).clip(RoundedCornerShape(22.dp))) {
        MiniRow(s, st, swipe = false)
    }
}

/**
 * The player panel: liquid glass sliding up from the bottom while it rises (the music behind it bends through), turning into the
 * player as it nears the top. Its top corners ease from round to square in the last stretch.
 */
@Composable
fun PlayerPanel(cur: Song, onClose: () -> Unit) {
    val st by NativePlayer.state.collectAsState()
    val d = LocalDensity.current
    val fv = Morph.f.value
    val scr = Morph.screen; val mini = Morph.mini
    val h = scr.height.coerceAtLeast(1f)
    val top = h * (1f - fv)
    // m: 1 = a full-width panel, 0 = exactly the media bar's slot (only on the way back down, so it lands in the dock)
    val fd = Morph.fDock
    val m = if (Morph.closing && mini != Rect.Zero) smooth(fd, fd + 0.16f, fv) else 1f
    val org = Morph.origin
    val grow = org != Rect.Zero                    // opened from the tucked square: it expands out of it, and shrinks back into it
    val gp = fv.coerceIn(0f, 1f)
    var left = mini.left * (1f - m); var right = mini.right + (scr.width - mini.right) * m
    var bottom = (mini.bottom + (h - mini.bottom) * m).coerceAtLeast(top + mini.height)
    var topY = top
    if (grow) { left = org.left * (1f - gp); right = org.right + (scr.width - org.right) * gp; topY = org.top * (1f - gp); bottom = org.bottom + (h - org.bottom) * gp }
    val radius = if (grow) 22.dp * (1f - smooth(0.55f, 1f, gp)) else (Cfg.dockRadius.v.value.dp + 4.dp * m) * (1f - smooth(0.82f, 1f, fv))
    val rb = if (grow) radius else 0.dp
    val solid = if (m < 1f) 0f else smooth(0.62f, 0.98f, fv)
    val tint = mix(accent, 0.14f, Color(0xFF1A1A1F))
    // one set of UI at a time: the bar's row while it's (nearly) the bar again, otherwise the player
    val asBar = !grow && (Morph.carrying || m < 0.6f)
    // pulling the open player down: it holds on for a moment (a few growing buzzes, like the media bar's tug), then lets go with a
    // thud and follows your finger. The gesture lives on a layer that never moves or goes away, so a slow drag can't get lost.
    val resistMax = with(d) { 16.dp.toPx() }; val resistK = with(d) { 40.dp.toPx() }; val breakAt = with(d) { 64.dp.toPx() }
    val closeAt = with(d) { 140.dp.toPx() }
    val onCloseS by rememberUpdatedState(onClose)
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        val vt = androidx.compose.ui.input.pointer.util.VelocityTracker()
        var raw = 0f; var broke = false; var lastBuzz = 0f; var active = false; var base = 0f
        detectVerticalDragGestures(
            onDragStart = { raw = 0f; broke = false; lastBuzz = 0f; vt.resetTracking(); active = NpState.open.value && !Morph.carrying && Morph.f.value > 0.9f },
            onDragEnd = {
                if (active) {
                    val v = vt.calculateVelocity().y
                    val pulled = (1f - Morph.f.value) * Morph.screen.height
                    if (broke && (v > 900f || pulled > closeAt)) { Hap.soft(); Morph.closing = true; onCloseS() }
                    else { if (broke) Hap.soft(); Morph.closing = false; Morph.to(1f) }
                }
                active = false
            },
            onDragCancel = { if (active) { Morph.closing = false; Morph.to(1f) }; active = false },
        ) { c, dy ->
            if (!active) return@detectVerticalDragGestures
            c.consume(); vt.addPosition(c.uptimeMillis, c.position)
            raw = (raw + dy).coerceAtLeast(0f)
            val hh = Morph.screen.height.coerceAtLeast(1f)
            val eff = if (!broke) {
                val k = (raw / breakAt).coerceIn(0f, 1f)
                if (raw - lastBuzz > (14f - 10f * k) * Liquid.density) { lastBuzz = raw; Hap.tug(k) }
                if (raw > breakAt) { broke = true; Hap.thud(); base = resistMax * (1f - kotlin.math.exp(-breakAt / resistK)) - breakAt }
                resistMax * (1f - kotlin.math.exp(-raw / resistK))
            } else raw + base
            Morph.closing = broke
            Morph.snap(1f - eff.coerceAtLeast(0f) / hh)
        }
    }) {
    Box(Modifier.fillMaxSize().liquid("np-dim", 0.dp, Color.Black, z = 5, alpha = 0.45f * smooth(fd, 0.6f, fv) * m, scrim = true))
    Box(Modifier.offset { IntOffset(left.roundToInt(), topY.roundToInt()) }.size(with(d) { (right - left).toDp() }, with(d) { (bottom - topY).toDp() })
        .liquid("np-panel", radius, tint.copy(alpha = 0.40f), z = 6, alpha = 1f - smooth(0.9f, 1f, fv), corners = listOf(radius, radius, rb, rb))
        .clip(RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = rb, bottomEnd = rb)).background(tint.copy(alpha = if (grow) smooth(0.2f, 0.7f, gp) else solid))) {
        if (grow) Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - smooth(0.15f, 0.55f, gp) }) { Art(cur.cover, null, Modifier.fillMaxSize(), RoundedCornerShape(radius), phName = cur.album) }
        if (!asBar) {
            Box(Modifier.requiredSize(with(d) { scr.width.toDp() }, with(d) { h.toDp() }).align(Alignment.TopCenter)) {
                FullPlayer(cur, if (Morph.carrying) 0f else if (grow) smooth(0.45f, 0.9f, gp) else smooth(0.3f, 0.75f, fv), false) { Morph.closing = true; onClose() }
            }
            if (!grow) Box(Modifier.fillMaxWidth().padding(top = 8.dp).graphicsLayer { alpha = 1f - smooth(0.6f, 0.9f, fv) }, contentAlignment = Alignment.TopCenter) {
                Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f)))
            }
        } else if (!Morph.carrying) Box(Modifier.fillMaxWidth().height(with(d) { mini.height.toDp() })) { MiniRow(cur, st, swipe = false) }
    }
    }
}

/**
 * The tab pill: Albums · Artists · Songs · Playlists, with an iOS-style bubble that slides (and can be dragged) between them.
 * Drag it and it swells into a lens: the icons under it are magnified (sharp in the middle) and split into red, green and blue
 * towards its edge, like looking through a drop of glass.
 */
@Composable
private fun Tabs(tab: Int, onTab: (Int) -> Unit) {
    val a = accent
    val d = LocalDensity.current
    var w by remember { mutableStateOf(0f) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val order by TabsCfg.order.collectAsState(); val n = order.size
    val cell = if (w > 0) w / n else 0f
    val liquid by Prefs.liquid.collectAsState()
    // under your finger exactly while you drag it; riding with the pages while you swipe them; otherwise it springs to the tab
    val follow = drag == null && Pager.user.value
    val pagePos = Pager.pos.floatValue
    val direct = drag != null || follow
    val target = drag ?: if (follow) pagePos * cell else tab * cell
    val ax = remember { Animatable(0f) }
    LaunchedEffect(target, direct, cell) { if (direct || cell == 0f) ax.snapTo(target) else ax.animateTo(target, if (liquid) spring(0.55f, 300f) else spring(0.72f, 420f)) }
    val x = if (direct) target else ax.value          // no animation step in between while it's under your finger: zero lag
    // liquid: the bubble stretches in the direction it moves (and squashes a little), then wobbles back into shape
    var vel by remember { mutableStateOf(0f) }
    var lastX by remember { mutableStateOf(0f) }
    LaunchedEffect(x) { vel = x - lastX; lastX = x }
    val stretchT = if (liquid && cell > 0) (kotlin.math.abs(vel) / cell * 2.2f).coerceAtMost(0.35f) else 0f
    val stretch by animateFloatAsState(stretchT, spring(0.4f, 600f), label = "st")
    val grow by animateFloatAsState(if (drag != null) 1f else 0f, spring(0.55f, 420f), label = "grow")
    val hover = drag?.let { ((it + cell / 2) / cell).toInt().coerceIn(0, n - 1) } ?: if (follow) kotlin.math.round(pagePos).toInt().coerceIn(0, n - 1) else null
    val cellS by rememberUpdatedState(cell); val onTabS by rememberUpdatedState(onTab); val tabS by rememberUpdatedState(tab)
    // the bubble: a pill, wider than tall, that swells while held
    val padPx = with(d) { 4.dp.toPx() }
    val bw = cell * (1f + stretch) * (1f + 0.16f * grow)
    val bh = with(d) { 52.dp.toPx() } * (1f - stretch * 0.3f) * (1f + 0.1f * grow)
    val cx = x + cell / 2; val cy = with(d) { 31.dp.toPx() }
    val bubble = androidx.compose.ui.geometry.Rect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
    val lens = liquid && grow > 0.02f && cell > 0
    val items: @Composable (Int?) -> Unit = { hv ->
        Row(Modifier.fillMaxSize()) {
            order.map { TabsCfg.NAMES[it] to TabsCfg.ICONS[it] }.forEachIndexed { i, (label, ic) ->
                val on = (hv ?: tab) == i
                val sc by animateFloatAsState(if (on) 1.08f else 1f, tween(350, easing = Spring), label = "t$i")
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    Box(Modifier.height(28.dp).scale(sc), contentAlignment = Alignment.Center) { Icon(ic, tint = if (on) a else C.sub) }
                    if (Cfg.tabLabels.use()) T(label, 10.5.sp, W650, if (on) C.text else C.sub)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 4.dp).onSizeChanged { w = it.width.toFloat() }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var dragging = false
                // press on the highlighted tab and slide, or slide anywhere along the bar: the bubble follows the finger
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    if (!dragging && kotlin.math.abs(ch.position.x - down.position.x) > viewConfiguration.touchSlop) dragging = true
                    if (dragging) { ch.consume(); val before = drag?.let { ((it + cellS / 2) / cellS).toInt() }; drag = (ch.position.x - cellS / 2).coerceIn(-cellS * 0.15f, cellS * (n - 1 + 0.15f)); val now = ((drag!! + cellS / 2) / cellS).toInt().coerceIn(0, n - 1); if (before != null && before.coerceIn(0, n - 1) != now) Hap.tap() else if (before == null) Hap.press() }
                }
                if (dragging) { val i = (((drag ?: tabS * cellS) + cellS / 2) / cellS).toInt().coerceIn(0, n - 1); drag = null; Hap.thud(); onTabS(i) }
            }
        }) {
        if (cell > 0) Box(Modifier.offset { IntOffset(bubble.left.roundToInt(), bubble.top.roundToInt()) }.size(with(d) { bubble.width.toDp() }, with(d) { bubble.height.toDp() })
            .liquid("tab-bubble", with(d) { (bubble.height / 2).toDp() }, mix(a, if (drag != null) 0.22f else 0.30f, Color(0xFF2A2830)).copy(alpha = if (drag != null) 0.22f else 0.45f), z = 2))
        // the icons; while the lens is up, the ones under it are drawn by the lens instead
        val pill = RoundedCornerShape(50)
        Box(Modifier.fillMaxSize().then(if (lens) Modifier.drawWithContent {
            val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(bubble, androidx.compose.ui.geometry.CornerRadius(bubble.height / 2))) }
            clipPath(path, androidx.compose.ui.graphics.ClipOp.Difference) { this@drawWithContent.drawContent() }
        } else Modifier)) {
            Row(Modifier.fillMaxSize()) { repeat(n) { i -> Box(Modifier.weight(1f).fillMaxHeight().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { onTab(i) }) } }
            Box(Modifier.fillMaxSize()) { items(hover) }
        }
        if (lens) {
            // three passes (red, green, blue), each magnified a little differently: sharp in the centre, split at the rim
            Box(Modifier.offset { IntOffset(bubble.left.roundToInt(), bubble.top.roundToInt()) }.size(with(d) { bubble.width.toDp() }, with(d) { bubble.height.toDp() })
                .graphicsLayer { alpha = smooth(0.02f, 0.4f, grow); compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.clip(pill)) {
                val mag = 1f + 0.22f * grow
                val split = Prefs.glassSplit.collectAsState().value.coerceIn(0f, 2f)
                listOf(0 to 1f + 0.045f * split * grow, 1 to 1f, 2 to 1f - 0.045f * split * grow).forEach { (ch, k) ->
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                        .offset { IntOffset(-bubble.left.roundToInt(), -bubble.top.roundToInt()) }
                        .size(with(d) { w.toDp() }, 62.dp)
                        .graphicsLayer {
                            val s2 = mag * k; scaleX = s2; scaleY = s2
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin((cx / w).coerceIn(0f, 1f), cy / size.height.coerceAtLeast(1f))
                        }
                        .drawWithContent {
                            drawIntoCanvas { c ->
                                c.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint().apply { colorFilter = ColorFilter.colorMatrix(chan(ch)); blendMode = androidx.compose.ui.graphics.BlendMode.Plus })
                                drawContent(); c.restore()
                            }
                        }) { items(hover) }
                }
            }
        }
    }
}
/** keep one colour channel (0 r, 1 g, 2 b) */
private fun chan(i: Int) = ColorMatrix(FloatArray(20).also { m -> m[i * 5 + i] = 1f; m[18] = 1f })

// ------------------------------------------------------------------ now playing (#np)
@Composable
fun FullPlayer(s: Song, fade: Float, hideCover: Boolean, onClose: () -> Unit) {
    val a = accent
    val st by NativePlayer.state.collectAsState()
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val bgStyle = Cfg.bgStyle.use(); val vizStyle = Cfg.vizStyle.use(); val vizBars = Cfg.vizBars.use()
    val live = motion && !saving
    val levels by rememberLevels(live && st.playing && (bgStyle == 0 || vizStyle != 4), vizBars)
    val land = isLand()
    val ctxD = LocalContext.current
    LaunchedEffect(live, vizStyle, bgStyle) {
        Diag.log("player: ${Tasks.motionWhy(ctxD)} · background=$bgStyle visualizer=$vizStyle bars=$vizBars")
    }
    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade }.background(Color(0xFF08080A)).clipToBounds()) {
        // .bg: the cover itself, hugely blurred and darkened; .bg2: vignette; .glowpulse: the accent breathing with the bass
        val f = if (s.cover.isNotEmpty()) Store.coverFile(s.cover) else null
        val blurred = bgStyle == 1 || (bgStyle == 0 && !live)
        if (bgStyle == 3) Unit
        else if (bgStyle == 2) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(mix(a, 0.5f, Color.Black), mix(a, 0.2f, Color.Black), Color(0xFF08080A)))))
        else if (blurred) {
            if (f != null && Build.VERSION.SDK_INT >= 31) AsyncImage(f, null, Modifier.fillMaxSize().scale(1.4f).blur(60.dp), contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.6f) }.also { it.timesAssign(ColorMatrix(floatArrayOf(0.55f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))) }))
            else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(mix(a, 0.45f, Color.Black), mix(a, 0.18f, Color.Black), Color(0xFF08080A)))))
        }
        if (bgStyle != 3) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        }
        if (live && bgStyle == 0) {
            Fog(s, levels, st.playing)
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
        }
        if (live && vizStyle != 4) VizBg(levels, if (Cfg.vizColor.use() == 1) (remember(s.cover) { (if (s.cover.isNotEmpty()) Accents.pal(Store.coverFile(s.cover))?.first else null) } ?: a) else a, Modifier.fillMaxWidth().fillMaxHeight(Cfg.vizHeight.use()).align(Alignment.BottomCenter))
      }
      if (land) NpLand(s, fade, hideCover, onClose) else NpPages(s, fade, hideCover, onClose)
    }
}

/** the song's cover: swipe to skip, tap to turn it over, double-tap to like. Fills what it's given (up to [maxSide]). */
@Composable
private fun NpCover(s: Song, hideCover: Boolean, maxSide: Dp, swipeToPages: Boolean = false) {
    val st by NativePlayer.state.collectAsState()
    val shape = Cfg.coverShape.use(); val rad = Cfg.coverRadius.use(); val csz = Cfg.coverSize.use()
    val cs: Shape = when (shape) { 1 -> CircleShape; 2 -> RoundedCornerShape(2.dp); else -> RoundedCornerShape(rad.dp) }
    var heart by remember { mutableStateOf(false) }
    val heartA by animateFloatAsState(if (heart) 1f else 0f, tween(if (heart) 140 else 650), label = "h", finishedListener = { if (heart) heart = false })
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight, maxSide) * csz
        // tap the cover: it turns over to show the song's info (tap again, or skip, to turn it back)
        var flipped by remember(s.id) { mutableStateOf(false) }
        val rot by animateFloatAsState(if (flipped) 180f else 0f, spring(0.72f, 160f), label = "flip")
        val dens = LocalDensity.current.density
        Box(Modifier.size(side).graphicsLayer { alpha = if (hideCover) 0f else 1f; val k = if (st.playing) 1f else 0.92f; scaleX = k; scaleY = k; rotationY = rot; cameraDistance = 14f * dens }, contentAlignment = Alignment.Center) {
            val plain = swipeToPages && Cfg.coverSwipe.use() == 1
            if (rot <= 90f && plain) Box(Modifier.fillMaxSize().shadow(50.dp, cs).pointerInput(s.id) { detectTapGestures(onDoubleTap = { if (!Store.isFav(s.id)) Store.toggleFav(s.id); Hap.success(); heart = true }, onTap = { Hap.soft(); flipped = true }) }) { Art(s.cover, null, Modifier.fillMaxSize(), cs, phName = s.album) }
            else if (rot <= 90f) SongSwiper(Modifier.fillMaxSize(), 28.dp, onDouble = { if (!Store.isFav(s.id)) Store.toggleFav(s.id); Hap.success(); heart = true },
                onTap = { Hap.soft(); flipped = true }, curve = true) { x ->
                Box(Modifier.fillMaxSize().shadow(50.dp, cs)) { Art(x.cover, null, Modifier.fillMaxSize(), cs, phName = x.album) }
            } else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }.shadow(50.dp, cs).clip(cs).background(Color(0xFF111111))
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { Hap.soft(); flipped = false }) { CoverBack(s) }
            if (heartA > 0.01f) Icon(Ic.HeartFill, Modifier.scale(0.6f + heartA * 0.6f).graphicsLayer { alpha = heartA }, 110.dp, Color.White)
        }
    }
}

/** the player's header: close, where it's playing from, (sideways: the extra buttons), keep-awake and the menu */
@Composable
private fun NpTopBar(s: Song, onClose: () -> Unit, extrasHere: Boolean, lyr: Boolean, onLyrics: () -> Unit = { Prefs.setNpLyrics(!lyr) }) {
    val awake by Prefs.keepAwake.collectAsState(); val ctxLabel by Ctl.context.collectAsState()
    Row(Modifier.fillMaxWidth().height(if (extrasHere) 46.dp else 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (extrasHere) 0.dp else 6.dp)) {
        IB(Ic.Down, onClick = onClose)
        Column(Modifier.weight(1f).padding(start = if (extrasHere) 4.dp else 0.dp), horizontalAlignment = if (extrasHere) Alignment.Start else Alignment.CenterHorizontally) {
            T("PLAYING FROM", if (extrasHere) 11.sp else 12.sp, color = Color.White.copy(alpha = 0.7f)); T(ctxLabel.ifBlank { s.album.ifBlank { "Your Library" } }, if (extrasHere) 12.5.sp else 13.5.sp, W700, Color.White)
        }
        if (extrasHere) npExtras(lyr, onLyrics).forEach { it() }
        IB(if (awake) Ic.Sun else Ic.Moon, on = awake) { if (!awake) Hap.success() else Hap.soft(); Prefs.setAwake(!awake); Toasts.show(if (!awake) "The screen stays on while the player is open" else "The screen sleeps as usual") }
        IB(Ic.More) { Dialogs.sheet { NpSheet(s) } }
    }
}

/** the buttons you chose in Customize: lyrics, equalizer, sleep timer, queue */
@Composable
private fun npExtras(lyr: Boolean, onLyrics: () -> Unit): List<@Composable () -> Unit> {
    val a = accent
    val l = mutableListOf<@Composable () -> Unit>()
    val eos by Sleep.endOfSong.collectAsState()
    if (Cfg.btnLyrics.use()) l.add { Box(Modifier.size(44.dp).clip(CircleShape).background(if (lyr) Color.White.copy(alpha = 0.14f) else Color.Transparent).clickable { onLyrics() }, contentAlignment = Alignment.Center) { Icon(Ic.Lyrics, tint = if (lyr) Color.White else Color.White.copy(alpha = 0.7f)) } }
    if (Cfg.btnEq.use()) l.add { IB(Ic.Eq, Color.White.copy(alpha = 0.7f)) { Dialogs.sheet { EqSheet() } } }
    if (Cfg.btnSleep.use()) l.add { IB(Ic.Timer, if (eos) a else Color.White.copy(alpha = 0.7f)) { Dialogs.sheet { SleepSheet() } } }
    if (Cfg.btnQueue.use()) l.add { IB(Ic.Queue, Color.White.copy(alpha = 0.7f)) { Dialogs.sheet { QueueSheet() } } }
    return l
}

/** .seek: a track that thickens while dragged, with the times under it (or beside it when [inline]) */
@Composable
private fun NpSeek(inline: Boolean) {
    val st by NativePlayer.state.collectAsState()
    val thick = Cfg.seekThick.use(); val times = Cfg.showTimes.use()
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = if (st.dur > 0) (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) else 0f
    val shown = drag ?: frac
    var sw by remember { mutableStateOf(1f) }
    val th by animateFloatAsState(if (drag != null) 1.8f else 1f, tween(150), label = "th")
    val track: @Composable (Modifier) -> Unit = { m ->
        Box(m.height(34.dp).onSizeChanged { sw = it.width.toFloat() }
            .pointerInput(st.dur) {
                detectTapGestures { o -> NativePlayer.seek(((o.x / sw).coerceIn(0f, 1f) * st.dur).toLong()) }
            }
            .pointerInput(st.dur) {
                detectHorizontalDragGestures(onDragStart = { o -> drag = (o.x / sw).coerceIn(0f, 1f); Hap.press() }, onDragEnd = { drag?.let { NativePlayer.seek((it * st.dur).toLong()) }; drag = null; Hap.soft() }, onDragCancel = { drag = null }) { c, d -> c.consume(); val before = ((drag ?: 0f) * 10).toInt(); drag = ((drag ?: 0f) + d / sw).coerceIn(0f, 1f); if ((drag!! * 10).toInt() != before) Hap.tick() }
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(thick.dp).graphicsLayer { scaleY = th }.clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(RoundedCornerShape(3.dp)).background(Color.White)) }
        }
    }
    val left = fmt((shown * st.dur).toLong()); val right = "-" + fmt(st.dur - (shown * st.dur).toLong())
    if (inline) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (times) T(left, 12.sp, color = Color.White.copy(alpha = 0.6f))
        track(Modifier.weight(1f).padding(horizontal = if (times) 10.dp else 0.dp))
        if (times) T(right, 12.sp, color = Color.White.copy(alpha = 0.6f))
    } else {
        track(Modifier.padding(top = 8.dp).fillMaxWidth())
        if (times) Row(Modifier.fillMaxWidth().offset(y = (-4).dp)) { T(left, 12.sp, color = Color.White.copy(alpha = 0.6f)); Spacer(Modifier.weight(1f)); T(right, 12.sp, color = Color.White.copy(alpha = 0.6f)) }
    }
}

/** .ctrls: shuffle, previous, play / pause, next, repeat */
@Composable
private fun NpControls(playSize: Dp, modifier: Modifier) {
    val ctx = LocalContext.current
    val a = accent
    val st by NativePlayer.state.collectAsState()
    val rep by Prefs.repeat.collectAsState(); val shuf by Prefs.shuffle.collectAsState()
    val style = Cfg.playStyle.use()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IB(Ic.Shuffle, if (shuf) a else Color.White.copy(alpha = 0.55f), 22.dp) { Ctl.toggleShuffle(ctx) }
        IB(Ic.Prev, size = 28.dp) { NativePlayer.prev() }
        val ppSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val pressed by ppSrc.collectIsPressedAsState()
        val pps by animateFloatAsState(if (pressed) 0.9f else 1f, tween(200, easing = Spring), label = "pp")
        val bg = if (style == 1) a else if (style == 2) Color.Transparent else Color.White
        val fg = if (style == 1) C.onAccent else if (style == 2) Color.White else Color(0xFF111111)
        Box(Modifier.size(playSize).scale(pps).then(if (style == 2) Modifier else Modifier.shadow(30.dp, CircleShape, ambientColor = a, spotColor = a)).clip(CircleShape).background(bg)
            .then(if (style == 2) Modifier.border(2.5.dp, Color.White, CircleShape) else Modifier).clickable(ppSrc, null) { Hap.press(); NativePlayer.toggle() }, contentAlignment = Alignment.Center) {
            Icon(if (st.playing) Ic.Pause else Ic.Play, size = playSize * 0.45f, tint = fg)
        }
        IB(Ic.Next, size = 28.dp) { NativePlayer.next() }
        IB(if (rep == 2) Ic.RepeatOne else Ic.Repeat, if (rep > 0) a else Color.White.copy(alpha = 0.55f), 22.dp) { Ctl.cycleRepeat() }
    }
}

/** the player in portrait: lyrics are one swipe to the left of it, and the artist is one swipe to the right */
@Composable
private fun NpPages(s: Song, fade: Float, hideCover: Boolean, onClose: () -> Unit) {
    val a = accent
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = 1) { 3 }
    val scope = rememberCoroutineScope()
    val go: (Int) -> Unit = { p -> scope.launch { pager.animateScrollToPage(p) } }
    var last by remember { mutableStateOf(1) }
    LaunchedEffect(pager.settledPage) { if (pager.settledPage != last) { last = pager.settledPage; Hap.soft() } }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade }) {
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            Box(Modifier.fillMaxSize().graphicsLayer {
                val off = kotlin.math.abs(pager.currentPage - page + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                alpha = 1f - 0.65f * off; val k = 1f - 0.04f * off; scaleX = k; scaleY = k
            }) {
                when (page) { 0 -> NpLyricsPage(s, onClose, go); 1 -> NpPortrait(s, hideCover, onClose, go); else -> NpArtistPage(s, onClose, go) }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(3) { i ->
                val on = pager.currentPage == i
                val w by animateDpAsState(if (on) 16.dp else 6.dp, tween(220), label = "dotw")
                Box(Modifier.height(6.dp).width(w).clip(CircleShape).background(Color.White.copy(alpha = if (on) 0.9f else 0.35f)).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { go(i) })
            }
        }
    }
}

@Composable
private fun NpPortrait(s: Song, hideCover: Boolean, onClose: () -> Unit, go: (Int) -> Unit) {
    val a = accent
    val favs by Store.favs.collectAsState()
    val order by PlayerLayout.order.collectAsState(); val hidden by PlayerLayout.hidden.collectAsState()
    val center = Cfg.titleAlign.use() == 1; val tSize = Cfg.titleSize.use(); val cs = Cfg.ctlScale.use(); val heart = Cfg.btnHeart.use()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 18.dp)) {
        NpTopBar(s, onClose, false, false) { go(0) }
        order.forEach { id ->
            if (id !in hidden) when (id) {
                0 -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { NpCover(s, hideCover, 420.dp, true) }
                1 -> Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
                        T(s.title, tSize.sp, W800, spacing = (-0.22).sp, align = if (center) TextAlign.Center else TextAlign.Start)
                        T(s.artist.ifBlank { "Unknown Artist" }, (tSize * 0.73f).sp, color = Color.White.copy(alpha = 0.7f), align = if (center) TextAlign.Center else TextAlign.Start, modifier = Modifier.clickable { go(2) })
                    }
                    if (heart) IB(if (s.id in favs) Ic.HeartFill else Ic.Heart, if (s.id in favs) a else C.text) { if (Store.toggleFav(s.id)) Hap.success() else Hap.soft() }
                }
                2 -> NpSeek(false)
                3 -> NpControls((76 * cs).dp, Modifier.fillMaxWidth().padding(top = 8.dp).height((84 * cs).dp))
                4 -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { npExtras(false) { go(0) }.forEach { it() } }
            }
        }
    }
}

/** the full-screen lyrics: the song stays one tap away (the little header), and the seek bar and buttons stay under them */
@Composable
private fun NpLyricsPage(s: Song, onClose: () -> Unit, go: (Int) -> Unit) {
    val a = accent
    val favs by Store.favs.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IB(Ic.Down, onClick = onClose)
            Box(Modifier.rubber(4.dp).shadow(20.dp, RoundedCornerShape(10.dp)).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { Hap.soft(); go(1) }) { Art(s.cover, 48.dp, shape = RoundedCornerShape(10.dp), phName = s.album) }
            Column(Modifier.weight(1f).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { Hap.soft(); go(1) }) { T(s.title, 15.sp, W800); T(s.artist, 13.sp, color = Color.White.copy(alpha = 0.7f)) }
            IB(if (s.id in favs) Ic.HeartFill else Ic.Heart, if (s.id in favs) a else C.text) { if (Store.toggleFav(s.id)) Hap.success() else Hap.soft() }
            IB(Ic.More) { Dialogs.sheet { NpSheet(s) } }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { LyricsPane(s) }
        NpSeek(false)
        NpControls(64.dp, Modifier.fillMaxWidth().padding(top = 4.dp).height(72.dp))
    }
}

/** the artist: their picture, what you have of theirs, and what they're about */
@Composable
private fun NpArtistPage(s: Song, onClose: () -> Unit, go: (Int) -> Unit) {
    val ctx = LocalContext.current
    val a = accent
    val songs by Store.songs.collectAsState()
    Store.ver.collectAsState().value
    val name = primaryArtist(s.artist).ifBlank { s.albumArtist.ifBlank { s.artist.ifBlank { "Unknown Artist" } } }
    val mine = remember(songs, name) { Store.artists(songs)[name] ?: emptyList() }
    val albums = remember(mine) { Store.albums(mine).sortedByDescending { it.year.ifBlank { "0" } } }
    val info = Store.artistInfo(name)
    val photo = info?.optString("image")?.takeIf { Store.coverFile(it).exists() } ?: mine.firstOrNull { it.cover.isNotEmpty() }?.cover ?: s.cover
    val top = remember(mine) { mine.sortedWith(compareByDescending<Song> { it.j.optInt("plays") }.thenByDescending { Store.isFav(it.id) }).take(5) }
    val about = info?.optString("about") ?: ""
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IB(Ic.Down, onClick = onClose)
            T("ABOUT THE ARTIST", 12.sp, W700, Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f), align = TextAlign.Center)
            Spacer(Modifier.size(44.dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(200.dp).shadow(40.dp, CircleShape)) { Art(photo, null, Modifier.fillMaxSize(), CircleShape, Ic.Person, name) }
        T(name, 30.sp, W800, spacing = (-0.5).sp, lines = 2, align = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        T("${plural(mine.size, "song")} · ${plural(albums.size, "album")} · ${fmtLong(mine.sumOf { it.durMs })}", 14.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Play", Ic.Play, modifier = Modifier.weight(1f)) { Ctl.play(ctx, mine.sortedBy { it.title }, 0, name, false) }
            Btn("Shuffle", Ic.Shuffle, tonal = true, modifier = Modifier.weight(1f)) { Ctl.shuffleAll(ctx, mine, name) }
        }
        Spacer(Modifier.height(10.dp))
        Btn("Open Artist Page", tonal = true, modifier = Modifier.fillMaxWidth()) { onClose(); Nav.open(Page.ArtistP(name)) }
        if (about.isNotBlank()) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                T("About", 18.sp, W800, modifier = Modifier.padding(bottom = 6.dp))
                T(about, 15.sp, color = Color.White.copy(alpha = 0.78f), lines = if (more) 400 else 6, lh = 22.sp, modifier = Modifier.clickable { more = !more })
            }
        } else Box(Modifier.padding(top = 14.dp)) { Btn("Get Artist Info from YouTube Music", Ic.Globe, tonal = true, modifier = Modifier.fillMaxWidth()) { onClose(); Yt.findArtist(name, info?.optString("channel") ?: ""); Nav.open(Page.Search) } }
        if (top.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            T("Top songs", 18.sp, W800, modifier = Modifier.padding(bottom = 6.dp))
            top.forEach { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Hap.tap(); Ctl.play(ctx, top, top.indexOf(t), name, false) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Art(t.cover, 44.dp, shape = RoundedCornerShape(8.dp), phName = t.album)
                    Column(Modifier.weight(1f)) { T(t.title, 15.sp, W650); T(t.album, 13.sp, color = Color.White.copy(alpha = 0.6f)) }
                    if (t.id == s.id) Icon(Ic.Play, size = 16.dp, tint = a)
                }
            }
        }
        if (albums.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
            T("Albums", 18.sp, W800, modifier = Modifier.padding(bottom = 8.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(albums, key = { it.key }) { al ->
                    Column(Modifier.width(120.dp).clickable { onClose(); Nav.open(Page.AlbumP(al.key)) }) {
                        Art(al.cover?.cover ?: "", 120.dp, shape = RoundedCornerShape(12.dp), phName = al.title)
                        T(al.title, 13.sp, W700, modifier = Modifier.padding(top = 6.dp)); T(al.year.ifBlank { plural(al.songs.size, "song") }, 12.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

/**
 * Sideways: the big cover on the left (with the title under it while lyrics are showing), and on the right the header and
 * its buttons, the lyrics (or the title, large) and the seek bar and controls along the bottom.
 */
@Composable
private fun NpLand(s: Song, fade: Float, hideCover: Boolean, onClose: () -> Unit) {
    val a = accent
    val favs by Store.favs.collectAsState()
    val lyr by Prefs.npLyrics.collectAsState()
    Row(Modifier.fillMaxSize().graphicsLayer { alpha = fade }.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical))
        .padding(start = 28.dp, end = 24.dp, top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Column(Modifier.weight(0.42f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { NpCover(s, hideCover, 520.dp) }
            if (lyr) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { T(s.title, 16.sp, W800); T(s.artist.ifBlank { "Unknown Artist" }, 13.sp, color = Color.White.copy(alpha = 0.7f)) }
                IB(if (s.id in favs) Ic.HeartFill else Ic.Heart, if (s.id in favs) a else C.text) { if (Store.toggleFav(s.id)) Hap.success() else Hap.soft() }
            }
        }
        Column(Modifier.weight(0.58f).fillMaxHeight()) {
            NpTopBar(s, onClose, true, lyr)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (lyr) LyricsPane(s)
                else Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            T(s.title, 28.sp, W800, spacing = (-0.3).sp, lines = 2)
                            T(s.artist.ifBlank { "Unknown Artist" }, 18.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.clickable { onClose(); Nav.open(Page.ArtistP(primaryArtist(s.artist).ifBlank { s.albumArtist })) })
                            if (s.album.isNotBlank()) T(s.album, 14.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(top = 2.dp))
                        }
                        IB(if (s.id in favs) Ic.HeartFill else Ic.Heart, if (s.id in favs) a else C.text) { if (Store.toggleFav(s.id)) Hap.success() else Hap.soft() }
                    }
                }
            }
            NpSeek(true)
            NpControls(60.dp, Modifier.fillMaxWidth().height(68.dp))
        }
    }
}

@Composable
fun NpSheet(s: Song) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    SheetScroll {
        SheetHead(s.cover, s.title, listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" · "), s.album)
        Act(Ic.PlayNext, "Play Next") { Ctl.playNext(ctx, listOf(s)) }
        Act(Ic.AddQ, "Add to Queue") { Ctl.addToQueue(ctx, listOf(s)) }
        Act(Ic.Search, "Look Up Song Info…") { Dialogs.sheet { LookupSong(s) } }
        Act(if (Store.isFav(s.id)) Ic.HeartFill else Ic.Heart, if (Store.isFav(s.id)) "Remove from Favorites" else "Add to Favorites") { likeToast(Store.toggleFav(s.id)) }
        Act(Ic.List, "Add to Playlist…") { Dialogs.sheet { PlaylistPicker(listOf(s.id)) } }
        Act(Ic.Disc, "Go to Album") { NpState.open.value = false; Nav.open(Page.AlbumP(s.albumKey)) }
        Act(Ic.Person, "Go to Artist") { NpState.open.value = false; Nav.open(Page.ArtistP(primaryArtist(s.artist).ifBlank { s.albumArtist })) }
        Act(Ic.Share, "Share as Music Pack…") { scope.launch { sharePack(ctx, s.title, listOf(s), null) } }
        Act(Ic.Trash, "Remove Song…", danger = true) { Dialogs.show { Confirm("Remove “${s.title}”?", "It's deleted from this phone.", "Remove") { Store.remove(listOf(s.id)) } } }
    }
}
object NpState { val open = mutableStateOf(false) }
object Sleep { val endOfSong = kotlinx.coroutines.flow.MutableStateFlow(false) }

@Composable
private fun SleepSheet() {
    SheetScroll {
        T("Sleep Timer", 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        listOf(0 to "Off", 5 to "5 minutes", 10 to "10 minutes", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", 90 to "1½ hours").forEach { (m, l) ->
            Act(Ic.Timer, l) { Sleep.endOfSong.value = false; NativePlayer.sleep(m) }
        }
        Act(Ic.Timer, "At the end of this song") { NativePlayer.sleep(0); Sleep.endOfSong.value = true }
    }
}

/** lyrics that follow the song (tap a line to jump there), with Earlier / Later and Wrong lyrics? */
@Composable
fun LyricsPane(s: Song) {
    val v by Lyr.v
    var ly by remember(s.id, v) { mutableStateOf(Online.cachedLyrics(s.id)) }
    var loading by remember(s.id, v) { mutableStateOf(ly == null) }
    var failed by remember(s.id, v) { mutableStateOf(false) }
    LaunchedEffect(s.id, v) { if (ly == null) { ly = withContext(Dispatchers.IO) { try { Online.fetchLyrics(s) } catch (_: Exception) { failed = true; null } }; loading = false } }
    var off by remember(s.id) { mutableStateOf(Store.settings().optJSONObject("offsets")?.optDouble(s.id, 0.0) ?: 0.0) }
    fun nudge(d: Double) {
        off = Math.round((off + d) * 10) / 10.0
        val o = Store.settings().optJSONObject("offsets") ?: org.json.JSONObject().also { Store.settings().put("offsets", it) }
        if (off == 0.0) o.remove(s.id) else o.put(s.id, off); Store.save()
    }
    val l = ly
    var pos by remember { mutableStateOf(0L) }
    val shift = if (Prefs.trimSilence.value) (s.trimStart * 1000).toLong() else 0L
    LaunchedEffect(s.id, l) { while (l != null && l.synced.isNotEmpty()) { pos = NativePlayer.livePos() + shift; delay(100) } }
    val ls = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                loading -> T("Finding the lyrics…", 18.sp, W700, Color.White.copy(alpha = 0.6f))
                failed || l == null -> T("Couldn't look up lyrics — check your internet connection.", 20.sp, W800, Color.White.copy(alpha = 0.7f), lines = 3, align = TextAlign.Center)
                l.instrumental -> T("♪ Instrumental", 22.sp, W800, Color.White.copy(alpha = 0.7f))
                l.synced.isNotEmpty() -> {
                    val offMs = (off * 1000).toLong()
                    val cur = l.synced.indexOfLast { it.ms + offMs <= pos }.coerceAtLeast(0)
                    SyncedLyrics(l.synced, cur, { NativePlayer.livePos() + shift - offMs }) { i -> NativePlayer.seek((l.synced[i].ms + offMs - shift).coerceAtLeast(0)) }
                }
                l.plain.isNotBlank() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) { T(l.plain, 22.sp, W800, Color.White.copy(alpha = 0.85f), lines = 1000, lh = 32.sp) }
                else -> T("No lyrics for this song", 20.sp, W800, Color.White.copy(alpha = 0.7f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            T(if (off != 0.0) "Lyrics · %+.1f s".format(off) else "Lyrics", 12.sp, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.weight(1f))
            for ((t, f) in listOf("Earlier" to { nudge(-0.5) }, "Later" to { nudge(0.5) }, "Wrong lyrics?" to { Dialogs.sheet { LyricsSearch(s) } }))
                Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { f() }.padding(horizontal = 10.dp, vertical = 6.dp)) { T(t, 12.sp, W700, Color.White.copy(alpha = 0.8f)) }
        }
    }
}

@Composable
fun QueueSheet() {
    val ctx = LocalContext.current
    val st by NativePlayer.state.collectAsState()
    val q by Ctl.queue.collectAsState()
    val a = accent
    val start = st.index.coerceAtLeast(0)
    val rowH = with(LocalDensity.current) { 60.dp.toPx() }
    var dragI by remember { mutableStateOf(-1) }; var dragY by remember { mutableStateOf(0f) }
    Column(Modifier.padding(start = 20.dp, end = 12.dp, bottom = 10.dp)) { T("Up Next", 15.sp, W750); T(plural((q.size - start).coerceAtLeast(0), "song"), 13.sp, color = C.sub) }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    LazyColumn(Modifier.heightIn(max = 600.dp)) {
        itemsIndexed(q.drop(start), key = { k, s -> "${k + start}-${s.id}" }) { k, s ->
            val i = k + start; val now = i == st.index
            var dx by remember { mutableStateOf(0f) }
            Row(Modifier.fillMaxWidth().height(60.dp).zIndexCompat(if (dragI == i) 1f else 0f)
                .graphicsLayer { translationY = if (dragI == i) dragY else 0f; translationX = dx; alpha = 1f - (kotlin.math.abs(dx) / 600f).coerceIn(0f, 0.7f) }
                .background(if (dragI == i) C.surface2 else Color.Transparent)
                .clickable { NativePlayer.jump(i) }
                .then(if (now) Modifier else Modifier
                    .pointerInput(i) { detectDragGesturesAfterLongPress(onDragStart = { Hap.press(); dragI = i; dragY = 0f }, onDragEnd = { val to = (i + Math.round(dragY / rowH)).coerceIn(start + 1, q.size - 1); dragI = -1; dragY = 0f; if (to != i) Ctl.move(ctx, i, to) }, onDragCancel = { dragI = -1; dragY = 0f }) { c, d -> c.consume(); dragY += d.y } }
                    .pointerInput(i) { detectHorizontalDragGestures(onDragEnd = { if (dx < -220f) { Hap.thud(); Ctl.removeAt(ctx, i) }; dx = 0f }, onDragCancel = { dx = 0f }) { c, d -> c.consume(); val was = dx < -220f; dx = (dx + d).coerceAtMost(0f); if (was != dx < -220f) { if (!was) Hap.tick() else Hap.soft() } } })
                .padding(start = 18.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(contentAlignment = Alignment.Center) { Art(s.cover, 40.dp, shape = RoundedCornerShape(8.dp), phName = s.album); if (now) { Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f))); EqBars(st.playing) } }
                Column(Modifier.weight(1f)) { T(s.title, 15.sp, W650, if (now) a else C.text); T(s.artist, 13.sp, color = C.sub) }
            }
        }
    }
}
fun Modifier.zIndexCompat(z: Float): Modifier = this.then(androidx.compose.ui.Modifier.zIndex(z))

/** the equalizer: presets as chips, the curve as ten bars with draggable knobs (.eqviz) */
@Composable
fun EqSheet() {
    val name by Prefs.eqName.collectAsState(); Prefs.eqCustom.collectAsState().value
    val g = Prefs.eqGains()
    val a = accent
    SheetScroll {
        Row(Modifier.padding(start = 24.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Ic.Eq, tint = a); T("Equalizer", 22.sp, W700, modifier = Modifier.weight(1f)); T(name, 15.sp, W700, a) }
        Row(Modifier.fillMaxWidth().height(160.dp).padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            g.forEachIndexed { i, v ->
                var h by remember { mutableStateOf(1f) }
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f).width(28.dp).onSizeChanged { h = it.height.toFloat() }.pointerInput(i, h) {
                        detectDragGestures(onDragStart = { o -> Hap.press(); Prefs.setEqBand(i, ((0.5f - o.y / h) * 24f).coerceIn(-12f, 12f).let { Math.round(it * 2) / 2f }) }) { c, _ -> c.consume(); val nv = ((0.5f - c.position.y / h) * 24f).coerceIn(-12f, 12f).let { Math.round(it * 2) / 2f }; if (nv != Prefs.eqGains()[i]) { Hap.tick(); Prefs.setEqBand(i, nv) } }
                    }, contentAlignment = Alignment.TopCenter) {
                        Canvas(Modifier.fillMaxSize()) {
                            val cx = size.width / 2; val bw = 6.dp.toPx(); val mid = size.height / 2; val y = mid - (v / 12f) * mid
                            drawRoundRect(C.surface3, Offset(cx - bw / 2, 0f), Size(bw, size.height), CornerRadius(bw / 2))
                            drawRoundRect(a, Offset(cx - bw / 2, minOf(mid, y)), Size(bw, kotlin.math.abs(mid - y).coerceAtLeast(1f)), CornerRadius(bw / 2))
                            drawCircle(C.text, 7.dp.toPx(), Offset(cx, y))
                        }
                    }
                    T(Prefs.EQ_BANDS[i], 10.sp, color = C.faint)
                }
            }
        }
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (Prefs.EQ_PRESETS.keys + "Custom").forEach { n -> Chip(n, name == n) { Prefs.setEq(n) } }
        }
    }
}


/**
 * The player's live background (the web app's "storm in the fog"): two colours from the cover drift as big soft clouds with
 * wisps passing through; they swell with the bass, and a hard hit flashes a light patch somewhere. Motion off: the blurred cover.
 */
@Composable
fun Fog(s: Song, levels: FloatArray, playing: Boolean) {
    val pal = remember(s.cover) { (if (s.cover.isNotEmpty()) Accents.pal(Store.coverFile(s.cover)) else null) ?: (Color(150, 110, 140) to Color(70, 70, 95)) }
    val ca by animateColorAsState(pal.first, tween(1500), label = "fa"); val cb by animateColorAsState(pal.second, tween(1500), label = "fb")
    var t by remember { mutableStateOf(0f) }
    val st = remember { FloatArray(5) }       // bassS, avg, flash, fx, fy
    LaunchedEffect(Unit) { val t0 = System.nanoTime(); while (true) { withFrameNanos { n -> t = (n - t0) / 1e9f } } }
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
        val bass = if (playing && levels.isNotEmpty()) levels.take(4).average().toFloat() else 0f
        st[0] += (bass - st[0]) * 0.25f
        val spike = bass - st[1]; st[1] += (bass - st[1]) * 0.06f
        if (playing && spike > 0.13f && bass > 0.42f && st[2] < 0.25f) { st[2] = minOf(1f, 0.55f + spike * 2.2f); st[3] = 0.15f + Math.random().toFloat() * 0.7f; st[4] = 0.08f + Math.random().toFloat() * 0.55f }
        st[2] *= 0.9f
        val e = if (playing) st[0] else 0f
        val W = size.width; val H = size.height
        drawRect(Color(ca.red * 0.1f + cb.red * 0.06f, ca.green * 0.1f + cb.green * 0.06f, ca.blue * 0.1f + cb.blue * 0.06f))
        fun blob(x: Float, y: Float, r: Float, c: Color, al: Float) {
            drawCircle(Brush.radialGradient(0f to c.copy(alpha = al.coerceIn(0f, 1f)), 0.55f to c.copy(alpha = (al * 0.45f).coerceIn(0f, 1f)), 1f to c.copy(alpha = 0f), center = Offset(x, y), radius = r), r, Offset(x, y), blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
        }
        blob(W * (0.5f + 0.34f * kotlin.math.sin(t * 0.13f)), H * (0.28f + 0.12f * kotlin.math.sin(t * 0.17f + 1f)), W * (0.95f + 0.3f * e), ca, 0.34f + 0.46f * e)
        blob(W * (0.5f + 0.36f * kotlin.math.cos(t * 0.11f + 2f)), H * (0.64f + 0.14f * kotlin.math.cos(t * 0.09f)), W * (1.0f + 0.25f * e), cb, 0.3f + 0.42f * e)
        for (i in 0 until 3) {
            val c = if (i % 2 == 1) ca else cb
            blob(W * (0.5f + 0.5f * kotlin.math.sin(t * (0.21f + i * 0.07f) + i * 2.1f)), H * (0.5f + 0.42f * kotlin.math.sin(t * (0.15f + i * 0.05f) + i)), W * 0.45f, c, 0.07f + 0.08f * e)
        }
        if (st[2] > 0.02f) blob(W * st[3], H * st[4], W * (0.55f + 0.4f * st[2]), Color(minOf(1f, ca.red * 0.6f + 0.43f), minOf(1f, ca.green * 0.6f + 0.43f), minOf(1f, ca.blue * 0.6f + 0.43f)), st[2] * 0.5f)
    }
}

/** keeps the analyser warm while the app is in front and playing, so the player's visualizer has data the instant it opens */
object VizHold {
    @Volatile var keep = false
    var fg = false
    fun refresh(playing: Boolean, allowed: Boolean) { keep = fg && playing && allowed; NativePlayer.viz(keep) }
}

/** the full-screen visualizer behind the player: bars, mirror, wave or dots, in the colour you chose (Customize) */
@Composable
fun VizBg(levels: FloatArray, color: Color, modifier: Modifier) {
    val style = Cfg.vizStyle.use(); val colMode = Cfg.vizColor.use(); val op = Cfg.vizOpacity.use()
    Canvas(modifier.graphicsLayer { alpha = op; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
        val n = levels.size.coerceAtLeast(1); val bw = size.width / n
        fun col(i: Int): Color = when (colMode) { 2 -> Color.hsv((i * 360f / n + 0f) % 360f, 0.55f, 1f); 3 -> Color.White; else -> color }
        for (i in 0 until n) {
            val lv = levels.getOrElse(i) { 0f }
            val c = col(i)
            when (style) {
                1 -> { // mirror: grows up and down from a centre line
                    val bh = maxOf(3f, lv * size.height * 0.45f); val cy = size.height * 0.62f
                    drawRoundRect(Brush.verticalGradient(listOf(c.copy(alpha = 0f), c.copy(alpha = 0.8f), c.copy(alpha = 0f)), startY = cy - bh, endY = cy + bh),
                        Offset(i * bw + bw * 0.2f, cy - bh), Size(bw * 0.6f, bh * 2), CornerRadius(2.dp.toPx()))
                }
                2 -> { // wave: a soft line through the tops
                    if (i > 0) { val x0 = (i - 1) * bw + bw / 2; val x1 = i * bw + bw / 2; val y0 = size.height - maxOf(3f, levels.getOrElse(i - 1) { 0f } * size.height * 0.9f); val y1 = size.height - maxOf(3f, lv * size.height * 0.9f)
                        drawLine(c.copy(alpha = 0.9f), Offset(x0, y0), Offset(x1, y1), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(Brush.verticalGradient(listOf(c.copy(alpha = 0.22f), Color.Transparent), startY = minOf(y0, y1), endY = size.height), Offset(x1, y1), Offset(x1, size.height), bw) }
                }
                3 -> { // dots: a stack of dots per bar
                    val rows = (lv * 12).toInt().coerceIn(1, 12); val d = bw * 0.5f
                    for (r in 0 until rows) drawCircle(c.copy(alpha = 0.35f + 0.6f * (r / 12f)), d / 2, Offset(i * bw + bw / 2, size.height - d * 0.9f - r * d * 1.5f))
                }
                else -> {
                    val bh = maxOf(3f, lv * size.height * 0.95f)
                    drawRoundRect(Brush.verticalGradient(listOf(c.copy(alpha = 0.75f), Color.White.copy(alpha = 0f)), startY = size.height - bh, endY = size.height),
                        Offset(i * bw + bw * 0.18f, size.height - bh), Size(bw * 0.64f, bh), CornerRadius(2.dp.toPx()))
                }
            }
        }
        // the mask: transparent at the top, solid from 45% down
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }
}


/** the back of the cover: the song's details over its blurred, mirrored artwork (#np .face.back) */
@Composable
fun CoverBack(s: Song) {
    val f = if (s.cover.isNotEmpty()) Store.coverFile(s.cover) else null
    if (f != null && Build.VERSION.SDK_INT >= 31) AsyncImage(f, null, Modifier.fillMaxSize().scale(1.2f).graphicsLayer { scaleX = -1.2f }.blur(28.dp), contentScale = ContentScale.Crop,
        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.5f) }.also { it.timesAssign(ColorMatrix(floatArrayOf(0.4f, 0f, 0f, 0f, 0f, 0f, 0.4f, 0f, 0f, 0f, 0f, 0f, 0.4f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))) }))
    val j = s.j
    val al = Store.songs.value.count { it.album == s.album && s.album.isNotBlank() }
    fun o(k: String) = j.optString(k).takeIf { it.isNotBlank() && it != "0" && it != "null" } ?: ""
    val rows = listOf(
        "Album" to s.album, "Album artist" to (if (s.albumartist.isNotBlank() && s.albumartist != s.artist) s.albumartist else ""),
        "Track" to (if (s.track > 0) "${s.track}${if (al > 0) " of $al" else ""}" else ""), "Disc" to (if (s.disc > 0) "${s.disc}" else ""),
        "Released" to s.year, "Genre" to s.genre, "Composer" to o("composer"), "Label" to o("label"), "Copyright" to o("copyright"), "ISRC" to o("isrc"), "BPM" to o("bpm"),
        "Length" to (if (s.durMs > 0) fmt(s.durMs) else ""), "Format" to o("codec"), "File size" to (j.optLong("size").takeIf { it > 0 }?.let { fmtSize(it) } ?: ""),
        "Added" to (j.optLong("added").takeIf { it > 0 }?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) } ?: ""),
    ).filter { it.second.isNotBlank() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        T(s.title, 19.sp, W800, lines = 3); T(s.artist, 13.5.sp, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
        rows.forEach { (k, v) ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                T(k, 13.5.sp, color = Color.White.copy(alpha = 0.6f)); T(v, 13.5.sp, align = TextAlign.End, lines = 4, modifier = Modifier.weight(1f))
            }
        }
    }
}


/**
 * Time-synced lyrics like the web app: the current line sits in the middle, and the list glides there on a quick, smooth
 * spring (never jumps). Lines you've passed dim and blur away above it; the next ones wait below. Drag to look around (every
 * line sharp, a tick per line); a moment after you let go it glides back to the song. Tap a line to jump there.
 */
/**
 * Estimated syllables in a word. Lyrics only carry a time for each LINE, so inside a line we have to guess when each word
 * lands; a singer spends time per syllable, not per letter, so this is a far better yardstick than the word's length.
 */
private fun syllables(w: String): Int {
    val s = w.lowercase().filter { it.isLetter() }
    if (s.isEmpty()) return 1
    var n = 0; var prev = false
    for (c in s) { val v = c in "aeiouyáàâäãéèêëíìîïóòôöõúùûü"; if (v && !prev) n++; prev = v }
    if (n > 1 && s.endsWith("e") && !s.endsWith("le") && !s.endsWith("ee")) n--
    return n.coerceAtLeast(1)
}

/** where each word of a line falls (0..1 of the time it is sung): by syllables, with a beat of rest after commas and full stops */
private class WordPlan(val words: List<String>, val a: FloatArray, val b: FloatArray, val syl: Int)
private fun planOf(text: String): WordPlan {
    val words = text.split(" ")
    val n = words.size
    val wt = FloatArray(n); val rest = FloatArray(n)
    var syl = 0
    words.forEachIndexed { k, w ->
        val s = syllables(w); syl += s
        wt[k] = s + 0.3f
        if (k < n - 1) rest[k] = when {
            w.endsWith("...") || w.endsWith("…") || w.endsWith("—") -> 0.9f
            w.lastOrNull() in listOf('.', '!', '?', ';', ':') -> 0.8f
            w.endsWith(",") -> 0.45f
            else -> 0f
        }
    }
    val total = (wt.sum() + rest.sum()).coerceAtLeast(0.01f)
    val a = FloatArray(n); val b = FloatArray(n)
    var acc = 0f
    for (k in 0 until n) { a[k] = acc / total; acc += wt[k]; b[k] = acc / total; acc += rest[k] }
    return WordPlan(words, a, b, syl)
}

/** the line being sung: its words fill in as the singer reaches them */
@Composable
private fun KaraokeLine(text: String, from: Long, to: Long, now: () -> Long, size: Float, center: Boolean, wordMs: LongArray? = null) {
    var t by remember(text) { mutableLongStateOf(0L) }
    val shift = Cfg.lyricShift.use().toLong()
    LaunchedEffect(text, from, shift) {
        var last = 0L
        while (true) withFrameNanos { n -> if (n - last > 30_000_000L) { last = n; t = now() - from + shift + 70L } }   // a hair early: late always looks worse than early
    }
    val plan = remember(text) { planOf(text) }
    val lineLen = (to - from).coerceIn(400L, 12000L).toFloat()
    // a line followed by a long gap isn't sung for the whole gap: nobody takes a second a syllable, so the fill finishes by then
    val sung = minOf(lineLen * 0.96f, plan.syl * 900f + 400f)
    val exact = wordMs != null && wordMs.size == plan.words.size
    val str = androidx.compose.ui.text.buildAnnotatedString {
        plan.words.forEachIndexed { k, w ->
            val s0: Float; val s1: Float
            if (exact) {
                s0 = (wordMs!![k] - from).toFloat()
                s1 = if (k + 1 < wordMs.size) (wordMs[k + 1] - from).toFloat() else minOf(lineLen, s0 + 350f + 300f * syllables(w))
            } else { s0 = plan.a[k] * sung; s1 = plan.b[k] * sung }
            val x = ((t - s0) / (s1 - s0).coerceAtLeast(70f)).coerceIn(0f, 1f)
            val wp = x * x * (3f - 2f * x)
            withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.5f + 0.5f * wp))) { append(w) }
            if (k < plan.words.size - 1) append(" ")
        }
    }
    androidx.compose.material3.Text(str, Modifier.fillMaxWidth(), fontSize = size.sp, fontWeight = W800, lineHeight = (size * 1.22f).sp, letterSpacing = (-0.4).sp, textAlign = if (center) TextAlign.Center else TextAlign.Start, maxLines = 6)
}

@Composable
private fun SyncedLyrics(lines: List<Online.Line>, cur: Int, now: () -> Long, onSeek: (Int) -> Unit) {
    val a = accent
    val n = lines.size
    val scroll = remember { Animatable(cur.toFloat()) }
    var browsing by remember { mutableStateOf(false) }
    var lastDrag by remember { mutableStateOf(0L) }
    val bf by animateFloatAsState(if (browsing) 1f else 0f, tween(260), label = "bf")
    val scope = rememberCoroutineScope()
    LaunchedEffect(cur, browsing, lastDrag) {
        if (browsing) { delay(2500); browsing = false; return@LaunchedEffect }
        val far = kotlin.math.abs(cur - scroll.value) > 3
        scroll.animateTo(cur.toFloat(), spring(1f, if (far) 55f else 150f))
    }
    var avgH by remember { mutableStateOf(100f) }
    val blurOk = Build.VERSION.SDK_INT >= 31
    val dens = LocalDensity.current.density
    val sweep = Cfg.lyricSweep.use()
    val lySize = Cfg.lyricSize.use(); val center = Cfg.lyricAlign.use() == 1; val glow = Cfg.lyricGlow.use(); val soft = Cfg.lyricBlur.use()
    val gutter = with(LocalDensity.current) { 18.dp.roundToPx() }
    androidx.compose.ui.layout.Layout(content = {
        lines.forEachIndexed { i, ln ->
            Box(Modifier.fillMaxWidth()
                .graphicsLayer {
                    val dd = i - scroll.value
                    val focus = (1f - kotlin.math.abs(dd)).coerceIn(0f, 1f)
                    val dim = if (dd < 0) 0.3f else 0.42f
                    val win = if (dd < 0) (1f - (-dd - 1.2f) / 2.4f).coerceIn(0f, 1f) else (1f - (dd - 3.5f) / 2.5f).coerceIn(0f, 1f)
                    val aSong = (dim + (1f - dim) * focus) * win
                    val aBrowse = if (i == cur) 1f else 0.55f
                    alpha = aSong + (aBrowse - aSong) * bf
                    val sc = 0.94f + 0.06f * focus; scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (center) 0.5f else 0f, 0.5f)
                    val br = if (!soft) 0f else (if (dd < -0.15f) ((-dd - 0.15f) * 3.2f).coerceAtMost(7f) else if (dd > 1.5f) ((dd - 1.5f) * 1.2f).coerceAtMost(3f) else 0f) * (1f - bf)
                    renderEffect = if (blurOk && br > 0.3f) androidx.compose.ui.graphics.BlurEffect(br * dens, br * dens, androidx.compose.ui.graphics.TileMode.Decal) else null
                }
                .drawBehind {
                    if (i == cur && glow) {
                        val c = Offset(size.width * 0.5f, size.height / 2); val rx = size.width * 0.5f
                        scale(1f, (size.height / 2 + 40f) / rx, c) { drawCircle(Brush.radialGradient(0f to a.copy(alpha = 0.4f), 0.55f to a.copy(alpha = 0.16f), 1f to Color.Transparent, center = c, radius = rx), rx, c) }
                    }
                }
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { Hap.tick(); onSeek(i) }
                .padding(horizontal = 22.dp, vertical = 16.dp)) {
                if (sweep && i == cur && ln.text.isNotBlank()) KaraokeLine(ln.text, ln.ms, lines.getOrNull(i + 1)?.ms ?: (ln.ms + 5000), now, lySize, center, ln.wordMs)
                else T(ln.text.ifBlank { "♪" }, lySize.sp, W800, Color.White, lines = 6, lh = (lySize * 1.22f).sp, align = if (center) TextAlign.Center else TextAlign.Start, spacing = (-0.4).sp, modifier = Modifier.fillMaxWidth())
            }
        }
    }, modifier = Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        // feather every edge: no hard line where the lyrics end, and the blur / glow never shows a cut
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.10f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.04f to Color.Black, 0.96f to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }.pointerInput(n) {
        var lastLine = 0
        detectVerticalDragGestures(onDragStart = { browsing = true; lastLine = scroll.value.roundToInt(); Hap.soft() }, onDragEnd = { lastDrag = System.currentTimeMillis() }, onDragCancel = { lastDrag = System.currentTimeMillis() }) { c, dy ->
            c.consume(); browsing = true
            val v = (scroll.value - dy / avgH).coerceIn(0f, (n - 1).toFloat())
            scope.launch { scroll.snapTo(v) }
            val ln = v.roundToInt(); if (ln != lastLine) { lastLine = ln; Hap.tick() }
        }
    }) { ms, c ->
        val ps = ms.map { it.measure(c.copy(minWidth = c.maxWidth + gutter * 2, maxWidth = c.maxWidth + gutter * 2, minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)) }
        val H = c.maxHeight.toFloat()
        val tops = FloatArray(ps.size); var acc = 0f
        ps.forEachIndexed { i, p -> tops[i] = acc; acc += p.height }
        if (ps.isNotEmpty()) avgH = (acc / ps.size).coerceAtLeast(1f)
        layout(c.maxWidth, c.maxHeight) {
            if (ps.isEmpty()) return@layout
            val sv = scroll.value.coerceIn(0f, (ps.size - 1).toFloat())
            val lo = sv.toInt(); val hi = minOf(ps.size - 1, lo + 1); val fr = sv - lo
            fun anchor(k: Int) = H * 0.42f - ps[k].height / 2f - tops[k]
            val shift = anchor(lo) + (anchor(hi) - anchor(lo)) * fr        // continuous: never jumps mid-move
            ps.forEachIndexed { i, p -> val y = shift + tops[i]; if (y < H && y + p.height > 0) p.placeRelative(-gutter, y.roundToInt()) }
        }
    }
}
