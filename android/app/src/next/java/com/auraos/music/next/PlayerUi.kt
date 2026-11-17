@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.auraos.music.next

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
                        out[i] = max(m / 255f, prev[i] * 0.84f); prev[i] = out[i]
                    }
                    st.value = out
                }
                delay(33)
            }
        } finally { NativePlayer.viz(false) }
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
    val t = if (moving) rememberInfiniteTransition(label = "au").animateFloat(0f, 1f, infiniteRepeatable(tween(22000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a").value else 0f
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f }) {
        val v = size.maxDimension
        fun blob(cx: Float, cy: Float, op: Float, k: Float) { val r = v * 0.35f * (1f + 0.15f * t * k); val c = Offset(cx + v * 0.08f * t * k, cy + v * 0.06f * t * k); drawCircle(Brush.radialGradient(listOf(a.copy(alpha = op), Color.Transparent), c, r), r, c) }
        blob(-v * 0.30f + v * 0.35f, -v * 0.35f + v * 0.35f, 0.22f, 1f)
        blob(size.width + v * 0.35f - v * 0.35f, size.height * 0.1f + v * 0.35f, 0.14f, 0.8f)
        blob(v * 0.35f, size.height + v * 0.45f - v * 0.35f, 0.12f, 0.6f)
    }
}

/** #dock: the media bar and the tab pill share one floating glass bubble; the visualizer glows behind both */
@Composable
fun Dock(cur: Song?, tab: Int, onTab: (Int) -> Unit, onOpen: () -> Unit) {
    val a = accent
    val st by NativePlayer.state.collectAsState()
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val levels by rememberLevels(cur != null && st.playing && motion && !saving, 40)
    Box(Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp).fillMaxWidth()
        .shadow(40.dp, RoundedCornerShape(26.dp), ambientColor = Color.Black, spotColor = Color.Black)
        .glass(RoundedCornerShape(26.dp), mix(a, 0.09f, Color(0xFF1A1A20))).clip(RoundedCornerShape(26.dp))) {
        if (cur != null && motion && !saving) Bars(levels, a.copy(alpha = 0.32f), Modifier.matchParentSize().graphicsLayer { alpha = 0.9f })
        Column {
            if (cur != null) MiniBar(cur, st, onOpen)
            if (cur != null) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
            Tabs(tab, onTab)
        }
    }
}

/** the media bar → full player growth: 0 = bar, 1 = full screen. The bar's rect, its cover and the big cover's rect are measured live. */
object Morph {
    val p = Animatable(0f)
    var mini = Rect.Zero; var miniArt = Rect.Zero; var cover = Rect.Zero; var screen = Rect.Zero
    /** set by the app's screen: animations need its frame clock (a plain MainScope crashes) */
    var scope: kotlinx.coroutines.CoroutineScope? = null
    fun snap(v: Float) { scope?.launch { p.snapTo(v.coerceIn(0f, 1f)) } }
    fun to(v: Float) { scope?.launch { p.animateTo(v, tween(if (v > 0.5f) 460 else 400, easing = Ease)) } }
}
class MorphShape(private val r: Rect, private val rad: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) = Outline.Rounded(RoundRect(r, CornerRadius(rad)))
}

/**
 * Swipe a song sideways: it follows the finger, the next / previous song slides in beside it, and letting go past a quarter of the
 * width finishes the move and skips; otherwise it springs back. [content] draws one song.
 */
@Composable
fun SongSwiper(modifier: Modifier, gap: Dp = 16.dp, onDouble: (() -> Unit)? = null, content: @Composable (Song) -> Unit) {
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
                        if (dx.value < -t && hasNext) { dx.animateTo(-(w + g), tween(220, easing = FastOutSlowInEasing)); NativePlayer.jump(if (idx + 1 < size) idx + 1 else 0) }
                        else if (dx.value > t && hasPrev) { dx.animateTo(w + g, tween(220, easing = FastOutSlowInEasing)); NativePlayer.jump(if (idx > 0) idx - 1 else size - 1) }
                        else { dx.animateTo(0f, spring(0.75f, 400f)); return@launch }
                        delay(900); if (dx.value != 0f) dx.snapTo(0f)          // in case the player didn't move
                    }
                },
                onDragCancel = { scope.launch { dx.animateTo(0f) } },
            ) { c, d ->
                c.consume()
                val v = dx.value + d
                val edge = (v > 0 && !hasPrev) || (v < 0 && !hasNext)
                scope.launch { dx.snapTo(if (edge) dx.value + d * 0.3f else v) }
            }
        }
        .then(if (onDouble != null) Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDouble() }) } else Modifier)) {
        if (prev != null && dx.value > 0f) Box(Modifier.matchParentSize().offset { IntOffset((dx.value - w - g).roundToInt(), 0) }) { content(prev) }
        Box(Modifier.matchParentSize().offset { IntOffset(dx.value.roundToInt(), 0) }) { content(cur) }
        if (next != null && dx.value < 0f) Box(Modifier.matchParentSize().offset { IntOffset((dx.value + w + g).roundToInt(), 0) }) { content(next) }
    }
}

@Composable
private fun MiniBar(s: Song, st: NativePlayer.St, onOpen: () -> Unit) {
    val a = accent
    val pv = Morph.p.value
    var vy by remember { mutableStateOf(0f) }
    val h = Morph.screen.height.coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth().height(62.dp).onGloballyPositioned { Morph.mini = it.boundsInRoot() }
        .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { onOpen() }
        .pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { vy = 0f }, onDragEnd = { if (Morph.p.value > 0.22f) onOpen() else Morph.to(0f); vy = 0f }, onDragCancel = { Morph.to(0f); vy = 0f }) { c, d ->
                c.consume(); vy += d; Morph.snap(-vy / (h * 0.65f))
            }
        }.graphicsLayer { alpha = (1f - pv * 4f).coerceIn(0f, 1f) }) {
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SongSwiper(Modifier.weight(1f).fillMaxHeight().clipToBounds(), 24.dp) { x ->
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.then(if (x.id == s.id) Modifier.onGloballyPositioned { Morph.miniArt = it.boundsInRoot() } else Modifier)) { Art(x.cover, 44.dp, shape = RoundedCornerShape(10.dp), phName = x.album) }
                    Column(Modifier.weight(1f)) { T(x.title, 15.sp, W700); T(x.artist.ifBlank { "Unknown Artist" }, 13.sp, color = C.sub) }
                }
            }
            IB(if (st.playing) Ic.Pause else Ic.Play) { NativePlayer.toggle() }
            IB(Ic.Next) { NativePlayer.next() }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (st.dur > 0) (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) else 0f).background(a))
        }
    }
}

/** the tab pill: Albums · Artists · Songs · Playlists, with a highlight that slides (and can be dragged) between them */
@Composable
private fun Tabs(tab: Int, onTab: (Int) -> Unit) {
    val a = accent
    val tabs = listOf("Albums" to Ic.Disc, "Artists" to Ic.Person, "Songs" to Ic.Note, "Playlists" to Ic.List)
    var w by remember { mutableStateOf(0f) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val cell = if (w > 0) w / 4 else 0f
    val liquid by Prefs.liquid.collectAsState()
    val x by animateFloatAsState(drag ?: (tab * cell), if (drag != null) snap() else if (liquid) spring(0.55f, 300f) else spring(0.72f, 420f), label = "ind")
    // liquid: the bubble stretches in the direction it moves (and squashes a little), then wobbles back into shape
    var vel by remember { mutableStateOf(0f) }
    var lastX by remember { mutableStateOf(0f) }
    LaunchedEffect(x) { vel = x - lastX; lastX = x }
    val stretchT = if (liquid && cell > 0) (kotlin.math.abs(vel) / cell * 2.2f).coerceAtMost(0.45f) else 0f
    val stretch by animateFloatAsState(stretchT, spring(0.4f, 600f), label = "st")
    val hover = drag?.let { ((it + cell / 2) / cell).toInt().coerceIn(0, 3) }
    val cellS by rememberUpdatedState(cell); val onTabS by rememberUpdatedState(onTab); val tabS by rememberUpdatedState(tab)
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
                    if (dragging) { ch.consume(); drag = (ch.position.x - cellS / 2).coerceIn(0f, cellS * 3) }
                }
                if (dragging) { val i = (((drag ?: tabS * cellS) + cellS / 2) / cellS).toInt().coerceIn(0, 3); drag = null; onTabS(i) }
            }
        }) {
        if (cell > 0) Box(Modifier.offset { IntOffset(x.roundToInt(), 0) }.padding(top = 5.dp).width(with(LocalDensity.current) { cell.toDp() }).height(52.dp)
            .graphicsLayer { val k = if (drag != null) 1.06f else 1f; scaleX = k * (1f + stretch); scaleY = k * (1f - stretch * 0.35f) }
            .then(if (liquid) Modifier.clip(RoundedCornerShape(20.dp)).background(mix(a, if (drag != null) 0.30f else 0.22f, Color.White.copy(alpha = 0.08f)))
                .drawWithContent { drawContent(); drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.45f to Color.Transparent)); drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.25f))), cornerRadius = CornerRadius(20.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx())) }
            else Modifier.clip(RoundedCornerShape(20.dp)).background(mix(a, if (drag != null) 0.32f else 0.24f, Color.White.copy(alpha = 0.06f))).border(1.dp, Color.White.copy(alpha = if (drag != null) 0.16f else 0.08f), RoundedCornerShape(20.dp))))
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, (label, ic) ->
                val on = (hover ?: tab) == i
                val sc by animateFloatAsState(if (on) 1.08f else 1f, tween(350, easing = Spring), label = "t$i")
                Column(Modifier.weight(1f).fillMaxHeight().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    Box(Modifier.height(28.dp).scale(sc), contentAlignment = Alignment.Center) { Icon(ic, tint = if (on) a else C.sub) }
                    T(label, 10.5.sp, W650, if (on) C.text else C.sub)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ now playing (#np)
@Composable
fun FullPlayer(s: Song, fade: Float, hideCover: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val a = accent
    val st by NativePlayer.state.collectAsState()
    val rep by Prefs.repeat.collectAsState(); val shuf by Prefs.shuffle.collectAsState()
    val favs by Store.favs.collectAsState(); val awake by Prefs.keepAwake.collectAsState()
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val lyr by Prefs.npLyrics.collectAsState(); val ctxLabel by Ctl.context.collectAsState()
    val live = motion && !saving
    val levels by rememberLevels(live && st.playing)
    val trig = with(LocalDensity.current) { 70.dp.toPx() }
    var vy by remember { mutableStateOf(0f) }
    var heart by remember { mutableStateOf(false) }
    val heartA by animateFloatAsState(if (heart) 1f else 0f, tween(if (heart) 140 else 650), label = "h", finishedListener = { if (heart) heart = false })
    var drag by remember { mutableStateOf<Float?>(null) }
    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade }.background(Color(0xFF08080A)).clipToBounds()) {
        // .bg: the cover itself, hugely blurred and darkened; .bg2: vignette; .glowpulse: the accent breathing with the bass
        val f = if (s.cover.isNotEmpty()) Store.coverFile(s.cover) else null
        if (f != null && Build.VERSION.SDK_INT >= 31) AsyncImage(f, null, Modifier.fillMaxSize().scale(1.4f).blur(60.dp), contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.6f) }.also { it.timesAssign(ColorMatrix(floatArrayOf(0.55f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))) }))
        else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(mix(a, 0.45f, Color.Black), mix(a, 0.18f, Color.Black), Color(0xFF08080A)))))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        if (live) {
            val bass = levels.take(6).average().toFloat()
            Canvas(Modifier.fillMaxSize()) { val r = size.width * 0.45f * (1f + bass * 0.25f); val c = Offset(size.width / 2, size.height * 0.32f); drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.25f + bass * 0.25f), Color.Transparent), c, r), r, c) }
        }
      }
        Column(Modifier.fillMaxSize().graphicsLayer { alpha = fade }.statusBarsPadding().navigationBarsPadding().pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { vy = 0f }, onDragEnd = { if (vy > trig * 1.5f) onClose() else Morph.to(1f); vy = 0f }, onDragCancel = { Morph.to(1f); vy = 0f }) { c, d ->
                c.consume(); vy += d; Morph.snap(1f - vy.coerceAtLeast(0f) / Morph.screen.height.coerceAtLeast(1f) * 1.4f)
            }
        }.padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IB(Ic.Down, onClick = onClose)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    T("PLAYING FROM", 12.sp, color = Color.White.copy(alpha = 0.7f)); T(ctxLabel.ifBlank { s.album.ifBlank { "Your Library" } }, 13.5.sp, W700, Color.White)
                }
                IB(if (awake) Ic.Sun else Ic.Moon, on = awake) { Prefs.setAwake(!awake); Toasts.show(if (!awake) "The screen stays on while the player is open" else "The screen sleeps as usual") }
                IB(Ic.More) { Dialogs.sheet { NpSheet(s) } }
            }
            if (lyr) {
                Row(Modifier.padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.shadow(20.dp, RoundedCornerShape(10.dp))) { Art(s.cover, 56.dp, shape = RoundedCornerShape(10.dp), phName = s.album) }
                    Column(Modifier.weight(1f)) { T(s.title, 15.sp, W800); T(s.artist, 13.sp, color = Color.White.copy(alpha = 0.7f)) }
                    IB(if (s.id in favs) Ic.HeartFill else Ic.Heart, if (s.id in favs) a else C.text) { Store.toggleFav(s.id) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { LyricsPane(s) }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val side = minOf(maxWidth, maxHeight, 420.dp)
                        Box(Modifier.size(side).onGloballyPositioned { Morph.cover = it.boundsInRoot() }.graphicsLayer { alpha = if (hideCover) 0f else 1f; val k = if (st.playing) 1f else 0.92f; scaleX = k; scaleY = k }, contentAlignment = Alignment.Center) {
                            SongSwiper(Modifier.fillMaxSize(), 28.dp, onDouble = { if (!Store.isFav(s.id)) Store.toggleFav(s.id); heart = true }) { x ->
                                Box(Modifier.fillMaxSize().shadow(50.dp, RoundedCornerShape(22.dp))) { Art(x.cover, null, Modifier.fillMaxSize(), RoundedCornerShape(22.dp), phName = x.album) }
                            }
                            if (heartA > 0.01f) Icon(Ic.HeartFill, Modifier.scale(0.6f + heartA * 0.6f).graphicsLayer { alpha = heartA }, 110.dp, Color.White)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 22.dp).heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        T(s.title, 22.sp, W800, spacing = (-0.22).sp)
                        T(s.artist.ifBlank { "Unknown Artist" }, 16.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.clickable { onClose(); Nav.open(Page.ArtistP(primaryArtist(s.artist).ifBlank { s.albumArtist })) })
                    }
                    IB(if (s.id in favs) Ic.HeartFill else Ic.Heart, if (s.id in favs) a else C.text) { Store.toggleFav(s.id) }
                }
            }
            // .seek: a 5px track that thickens while dragged
            val frac = if (st.dur > 0) (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) else 0f
            val shown = drag ?: frac
            var sw by remember { mutableStateOf(1f) }
            val th by animateFloatAsState(if (drag != null) 1.8f else 1f, tween(150), label = "th")
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(34.dp).onSizeChanged { sw = it.width.toFloat() }
                .pointerInput(st.dur) {
                    detectTapGestures { o -> NativePlayer.seek(((o.x / sw).coerceIn(0f, 1f) * st.dur).toLong()) }
                }
                .pointerInput(st.dur) {
                    detectHorizontalDragGestures(onDragStart = { o -> drag = (o.x / sw).coerceIn(0f, 1f) }, onDragEnd = { drag?.let { NativePlayer.seek((it * st.dur).toLong()) }; drag = null }, onDragCancel = { drag = null }) { c, d -> c.consume(); drag = ((drag ?: 0f) + d / sw).coerceIn(0f, 1f) }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(5.dp).graphicsLayer { scaleY = th }.clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(RoundedCornerShape(3.dp)).background(Color.White)) }
            }
            Row(Modifier.fillMaxWidth().offset(y = (-4).dp)) {
                T(fmt((shown * st.dur).toLong()), 12.sp, color = Color.White.copy(alpha = 0.6f)); Spacer(Modifier.weight(1f)); T("-" + fmt(st.dur - (shown * st.dur).toLong()), 12.sp, color = Color.White.copy(alpha = 0.6f))
            }
            // .ctrls with the visualizer behind
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(84.dp).offset(x = 0.dp)) {
                if (live) Bars(levels, a.copy(alpha = 0.55f), Modifier.fillMaxWidth().height(70.dp).align(Alignment.BottomCenter).offset(y = 4.dp))
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IB(Ic.Shuffle, if (shuf) a else Color.White.copy(alpha = 0.55f), 22.dp) { Ctl.toggleShuffle(ctx) }
                    IB(Ic.Prev, size = 28.dp) { NativePlayer.prev() }
                    val ppSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val pressed by ppSrc.collectIsPressedAsState()
                    val pps by animateFloatAsState(if (pressed) 0.9f else 1f, tween(200, easing = Spring), label = "pp")
                    Box(Modifier.size(76.dp).scale(pps).shadow(30.dp, CircleShape, ambientColor = a, spotColor = a).clip(CircleShape).background(Color.White).clickable(ppSrc, null) { NativePlayer.toggle() }, contentAlignment = Alignment.Center) {
                        Icon(if (st.playing) Ic.Pause else Ic.Play, size = 34.dp, tint = Color(0xFF111111))
                    }
                    IB(Ic.Next, size = 28.dp) { NativePlayer.next() }
                    IB(if (rep == 2) Ic.RepeatOne else Ic.Repeat, if (rep > 0) a else Color.White.copy(alpha = 0.55f), 22.dp) { Ctl.cycleRepeat() }
                }
            }
            // .extras
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (lyr) Color.White.copy(alpha = 0.14f) else Color.Transparent).clickable { Prefs.setNpLyrics(!lyr) }, contentAlignment = Alignment.Center) { Icon(Ic.Lyrics, tint = if (lyr) Color.White else Color.White.copy(alpha = 0.7f)) }
                IB(Ic.Eq, Color.White.copy(alpha = 0.7f)) { Dialogs.sheet { EqSheet() } }
                IB(Ic.Queue, Color.White.copy(alpha = 0.7f)) { Dialogs.sheet { QueueSheet() } }
            }
        }
    }
}

@Composable
private fun NpSheet(s: Song) {
    val ctx = LocalContext.current
    val sleepAt by NativePlayer.sleepAt.collectAsState(); val sleepEnd by Sleep.endOfSong.collectAsState()
    SheetScroll {
        SheetHead(s.cover, s.title, listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" · "), s.album)
        Act(Ic.PlayNext, "Play Next") { Ctl.playNext(ctx, listOf(s)) }
        Act(Ic.AddQ, "Add to Queue") { Ctl.addToQueue(ctx, listOf(s)) }
        Act(Ic.Search, "Look Up Song Info…") { Dialogs.sheet { LookupSong(s) } }
        Act(if (Store.isFav(s.id)) Ic.HeartFill else Ic.Heart, if (Store.isFav(s.id)) "Remove from Favorites" else "Add to Favorites") { Store.toggleFav(s.id) }
        Act(Ic.List, "Add to Playlist…") { Dialogs.sheet { PlaylistPicker(listOf(s.id)) } }
        Act(Ic.Disc, "Go to Album") { Nav.open(Page.AlbumP(s.albumKey)); NpState.open.value = false; Morph.to(0f) }
        Act(Ic.Person, "Go to Artist") { Nav.open(Page.ArtistP(primaryArtist(s.artist).ifBlank { s.albumArtist })); NpState.open.value = false; Morph.to(0f) }
        Act(Ic.Spark, "Start Radio") { Radio.start(ctx, s) }
        Act(Ic.Timer, "Sleep Timer", if (sleepAt > 0) "Pausing in ${((sleepAt - System.currentTimeMillis()) / 60000 + 1).coerceAtLeast(1)} min" else if (sleepEnd) "At the end of this song" else "Off") { Dialogs.sheet { SleepSheet() } }
        Act(Ic.Info, "Song Info") { Dialogs.show { SongInfo(s) } }
        Act(Ic.Share, "Share Song…") { try { com.auraos.music.next.Packs.shareSong(ctx, s) } catch (e: Exception) { Toasts.show("Couldn't share: ${e.message}") } }
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
                    LaunchedEffect(cur) { ls.animateScrollToItem((cur - 2).coerceAtLeast(0)) }
                    LazyColumn(state = ls, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 60.dp)) {
                        itemsIndexed(l.synced) { i, line ->
                            val c by animateColorAsState(if (i == cur) Color.White else Color.White.copy(alpha = if (i < cur) 0.32f else 0.5f), tween(300), label = "l")
                            T(line.text.ifBlank { "♪" }, 26.sp, W800, c, lines = 6, lh = 32.sp, modifier = Modifier.fillMaxWidth().clickable { NativePlayer.seek((line.ms + offMs - shift).coerceAtLeast(0)) }.padding(vertical = 9.dp))
                        }
                    }
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
    val ls = rememberLazyListState(initialFirstVisibleItemIndex = st.index.coerceAtLeast(0))
    Row(Modifier.padding(start = 20.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T("Up Next", 15.sp, W750); T("${plural(q.size, "song")} · ${fmtLong(q.drop(st.index + 1).sumOf { it.durMs })} left", 13.sp, color = C.sub) }
        TextBtn("Clear") { Ctl.clearUpNext(ctx) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    LazyColumn(state = ls, modifier = Modifier.heightIn(max = 600.dp)) {
        itemsIndexed(q, key = { i, s -> "$i-${s.id}" }) { i, s ->
            val now = i == st.index
            Row(Modifier.fillMaxWidth().height(60.dp).clickable { NativePlayer.jump(i) }.padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(contentAlignment = Alignment.Center) { Art(s.cover, 44.dp, shape = RoundedCornerShape(9.dp), phName = s.album); if (now) { Box(Modifier.size(44.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.45f))); EqBars(st.playing) } }
                Column(Modifier.weight(1f)) { T(s.title, 15.sp, W650, if (now) a else if (i < st.index) C.faint else C.text); T(s.artist, 13.sp, color = C.sub) }
                if (!now) { IB(Ic.Up, C.faint, 18.dp, 36.dp) { Ctl.move(ctx, i, i - 1) }; IB(Ic.Down, C.faint, 18.dp, 36.dp) { Ctl.move(ctx, i, i + 1) }; IB(Ic.Close, C.faint, 18.dp, 36.dp) { Ctl.removeAt(ctx, i) } }
            }
        }
    }
}

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
                        detectDragGestures(onDragStart = { o -> Prefs.setEqBand(i, ((0.5f - o.y / h) * 24f).coerceIn(-12f, 12f).let { Math.round(it * 2) / 2f }) }) { c, _ -> c.consume(); Prefs.setEqBand(i, ((0.5f - c.position.y / h) * 24f).coerceIn(-12f, 12f).let { Math.round(it * 2) / 2f }) }
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
