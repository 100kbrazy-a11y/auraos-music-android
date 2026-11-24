@file:OptIn(ExperimentalFoundationApi::class)

package com.auraos.music.next

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auraos.music.NativePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
fun fmtLong(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }
fun plural(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"
fun fmtSize(b: Long): String { var n = b.toDouble(); for (u in listOf("B", "KB", "MB", "GB")) { if (n < 1024 || u == "GB") return if (n < 10 && u != "B") "%.1f %s".format(n, u) else "${n.roundToInt()} $u"; n /= 1024 }; return "" }

@Composable
fun T(text: String, size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Normal, color: Color = C.text, modifier: Modifier = Modifier, lines: Int = 1, align: TextAlign? = null, spacing: TextUnit = TextUnit.Unspecified, lh: TextUnit = TextUnit.Unspecified) =
    Text(text, modifier, color = color, fontSize = size, fontWeight = weight, maxLines = lines, overflow = TextOverflow.Ellipsis, textAlign = align, letterSpacing = spacing, lineHeight = lh)

/** Pages opened over the tabs (back closes them) */
sealed class Page {
    data class AlbumP(val key: String) : Page()
    data class ArtistP(val name: String) : Page()
    data class PlaylistP(val name: String) : Page()
    object Favorites : Page()
    object Search : Page()
    object Settings : Page()
    object Scanner : Page()
    data class SettingsSection(val id: String) : Page()
}
/** pages that cover the whole screen, so the tab bar and media bar step aside */
val Page?.hidesDock: Boolean get() = this is Page.Settings || this is Page.Scanner || this is Page.SettingsSection
object Nav {
    val stack = mutableStateListOf<Page>()
    val tab = mutableStateOf(0)
    fun open(p: Page) { Hap.tap(); stack.add(p) }
    fun back(): Boolean { if (stack.isEmpty()) return false; stack.removeAt(stack.size - 1); return true }
}
object Sel { val ids = mutableStateListOf<String>(); fun toggle(id: String) { if (id in ids) ids.remove(id) else ids.add(id) } }

/** bottom sheets (#sheet) and centered dialogs (#sheet.dialog), one at a time */
object Dialogs {
    val cur = mutableStateOf<(@Composable () -> Unit)?>(null)
    val isSheet = mutableStateOf(true)
    fun show(d: @Composable () -> Unit) { Hap.soft(); isSheet.value = false; cur.value = d }
    fun sheet(d: @Composable () -> Unit) { Hap.tap(); isSheet.value = true; cur.value = d }
    fun close() { if (cur.value != null) Hap.soft(); cur.value = null }
}

@Composable
fun SheetHost() {
    val d = Dialogs.cur.value
    AnimatedVisibility(d != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        // the dimming sits under the glass (so the menu's glass isn't dimmed); over the open player it's drawn here instead
        Box(Modifier.fillMaxSize().then(if (NpState.open.value) Modifier.background(Color.Black.copy(alpha = 0.5f)) else Modifier.liquid("scrim", 0.dp, Color.Black, z = 7, alpha = 0.5f, scrim = true)).clickable(remember { MutableInteractionSource() }, null) { Dialogs.close() })
    }
    if (d != null) BackHandler { Dialogs.close() }
    val sheet = Dialogs.isSheet.value
    AnimatedVisibility(d != null && sheet, enter = slideInVertically(tween(380, easing = Ease)) { it }, exit = slideOutVertically(tween(250)) { it }) {
        val scope = rememberCoroutineScope()
        val off = remember { Animatable(0f) }
        val closeAt = with(LocalDensity.current) { 110.dp.toPx() }
        fun settle(v: Float) { scope.launch { if (off.value > closeAt || v > 1800f) { Dialogs.close(); off.snapTo(0f) } else off.animateTo(0f, spring(0.8f, 500f)) } }
        val conn = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0 && off.value > 0f) { val c = maxOf(available.y, -off.value); scope.launch { off.snapTo(off.value + c) }; return Offset(0f, c) }
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0 && source == NestedScrollSource.Drag) { scope.launch { off.snapTo(off.value + available.y) }; return Offset(0f, available.y) }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity { if (off.value > 0f) { settle(available.y); return available }; return Velocity.Zero }
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f).wrapContentHeight(Alignment.Bottom).offset { IntOffset(0, off.value.roundToInt()) }
                .liquid("sheet", 28.dp, C.surface.copy(alpha = 0.42f), z = 8, on = !NpState.open.value, corners = listOf(28.dp, 28.dp, 0.dp, 0.dp))
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).then(if (NpState.open.value) Modifier.background(C.surface) else Modifier).clickable(remember { MutableInteractionSource() }, null) { }
                .nestedScroll(conn)
                .draggable(rememberDraggableState { d -> scope.launch { off.snapTo((off.value + d).coerceAtLeast(0f)) } }, Orientation.Vertical, onDragStopped = { v -> settle(v) })
                .navigationBarsPadding().imePadding().padding(top = 8.dp, bottom = 14.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF55555C)))
                }
                Dialogs.cur.value?.invoke()
            }
        }
    }
    AnimatedVisibility(d != null && !sheet, enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.94f), exit = fadeOut(tween(150))) {
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().liquid("dialog", 28.dp, C.surface.copy(alpha = 0.45f), z = 9, on = !NpState.open.value).clip(RoundedCornerShape(28.dp)).then(if (NpState.open.value) Modifier.background(C.surface) else Modifier).clickable(remember { MutableInteractionSource() }, null) { }.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 14.dp)) {
                Dialogs.cur.value?.invoke()
            }
        }
    }
}
val Ease = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
val Spring = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f)

/** a pressable that shrinks a little, like the WebView's :active { transform: scale(.95) } */
@Composable
fun Modifier.press(scaleTo: Float = 0.95f, onLong: (() -> Unit)? = null, onDouble: (() -> Unit)? = null, give: Boolean = scaleTo <= 0.95f, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val p by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (p) scaleTo else 1f, tween(150, easing = Spring), label = "p")
    return this.then(if (give) Modifier.rubber() else Modifier).scale(s).combinedClickable(src, null, onLongClick = onLong?.let { f -> { Hap.press(); f() } }, onDoubleClick = onDouble) { Hap.tap(); onClick() }
}

// ------------------------------------------------------------------ building blocks
/**
 * a round icon button. While your finger is on it a circle stays behind it (even as you drag it about) — a little pane of
 * liquid glass — and the button leans after your finger.
 */
@Composable
fun IB(ic: Ic, tint: Color = C.text, size: Dp = 24.dp, box: Dp = 44.dp, on: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    var held by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (held) 0.92f else 1f, spring(0.5f, 600f), label = "ib")
    val ring by animateFloatAsState(if (held) 1f else 0f, tween(if (held) 90 else 260), label = "ibr")
    val id = remember { "ib" + (++ibN) }
    val glass = Prefs.liquid.collectAsState().value && !NpState.open.value
    Box(modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final); held = true
            while (true) { val ev = awaitPointerEvent(PointerEventPass.Final); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (!ch.pressed || (ch.isConsumed && ch.positionChanged())) break }
            held = false
        }
    }.rubber(5.dp).size(box).scale(s), contentAlignment = Alignment.Center) {
        if (ring > 0.01f) {
            if (glass) Box(Modifier.matchParentSize().liquid(id, box / 2, Color.White.copy(alpha = 0.10f), z = 11, alpha = ring))
            else Box(Modifier.matchParentSize().graphicsLayer { alpha = ring }.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)))
        }
        Box(Modifier.matchParentSize().clip(CircleShape).clickable(src, null) { if (ic == Ic.Back) Hap.soft() else Hap.tap(); onClick() }, contentAlignment = Alignment.Center) {
            Icon(ic, size = size, tint = if (on) accent else tint)
        }
    }
}
private var ibN = 0

/** true inside the bottom layer (the library and pages, what the glass looks through) */
val LocalBgLayer = staticCompositionLocalOf { false }
/** which page of the stack this is (-1 = not a page) */
val LocalPageIndex = staticCompositionLocalOf { -1 }
class HeadSpec(val title: String, val scrolled: Boolean, val actions: @Composable RowScope.() -> Unit)
/** pages hand their header up to the controls layer, so its bar can be a pane of glass over the page */
object PageHeads { val specs = mutableStateMapOf<Int, HeadSpec>() }

/** .head: title (28 / 800) with the tool buttons; once the page scrolls a bar of glass with round bottom corners fades in under it */
@Composable
fun Head(title: String, scrolled: Boolean = false, back: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    val page = LocalPageIndex.current
    if (page >= 0) {
        SideEffect { PageHeads.specs[page] = HeadSpec(title, scrolled, actions) }
        DisposableEffect(page) { onDispose { PageHeads.specs.remove(page) } }
        return
    }
    HeadBar(title, scrolled, back, actions)
}

@Composable
fun HeadBar(title: String, scrolled: Boolean, back: Boolean, actions: @Composable RowScope.() -> Unit) {
    val sc by animateFloatAsState(if (scrolled && !back) 0.72f else 1f, tween(260, easing = Ease), label = "h")
    val barA by animateFloatAsState(if (scrolled) 1f else 0f, tween(300, easing = Ease), label = "hb")
    val drop = with(LocalDensity.current) { 14.dp.toPx() }
    Box(Modifier.fillMaxWidth()) {
        // the glass bar reaches up past the top of the screen, so its top edge and rim are never seen (only the round bottom)
        val up = with(LocalDensity.current) { 28.dp.roundToPx() }
        Box(Modifier.matchParentSize().layout { m, c ->
            val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(c.maxWidth, c.maxHeight + up))
            layout(c.maxWidth, c.maxHeight) { p.place(0, -up) }
        }.graphicsLayer { translationY = -(1f - barA) * (drop + up) }
            .liquid("head", 24.dp, Color(0xFF0B0B0E).copy(alpha = 0.42f), z = 1, alpha = barA, corners = listOf(0.dp, 0.dp, 24.dp, 24.dp)))
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp).padding(start = if (back) 8.dp else 18.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (back) IB(Ic.Back) { Nav.back() }
            Box(Modifier.weight(1f)) {
                T(title, if (back) 20.sp else 28.sp, W800, spacing = (-0.02).em(if (back) 20f else 28f), modifier = Modifier.graphicsScale(sc))
            }
            actions()
        }
    }
}

/**
 * iOS-style give: while a finger is on it, the control leans a few dp after the finger (with resistance, it never really moves)
 * and springs back when you let go or the list starts scrolling.
 */
fun Modifier.rubber(max: Dp = 5.dp): Modifier = composed {
    val mx = with(LocalDensity.current) { max.toPx() }
    var t by remember { mutableStateOf(Offset.Zero) }
    val a by animateOffsetAsState(t, if (t == Offset.Zero) spring(0.45f, 500f) else spring(1f, 2500f), label = "rb")
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            fun r(v: Float) = mx * kotlin.math.tanh(v / (mx * 7f))
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Final)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed || ch.isConsumed) break            // let go, or a scroll took the finger
                val d = ch.position - down.position
                t = Offset(r(d.x), r(d.y))
            }
            t = Offset.Zero
        }
    }.graphicsLayer { translationX = a.x; translationY = a.y }
}

fun Double.em(px: Float): TextUnit = (this * px).sp
fun Modifier.graphicsScale(s: Float): Modifier = this.graphicsLayer { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0.5f) }

/** the glass pill of tool buttons at the top right: search, filter, settings */
@Composable
fun Tools(filter: (() -> Unit)? = null, sortTab: Int? = null) {
    Row(Modifier.liquid("tools", 22.dp, Color(0xFF2A272E).copy(alpha = 0.35f), z = 2).clip(RoundedCornerShape(22.dp)).padding(horizontal = 2.dp)) {
        IB(Ic.Search, box = 42.dp) { Nav.open(Page.Search) }
        if (sortTab != null) IB(Ic.UpDown, box = 42.dp) { Dialogs.sheet { SortSheet(sortTab) } }
        IB(Ic.Filter, box = 42.dp, on = Filt.active()) { if (filter != null) filter() else Dialogs.sheet { FilterSheet() } }
        IB(Ic.Gear, box = 42.dp) { Nav.open(Page.Settings) }
    }
}

/** .btn (accent) / .btn.tonal */
@Composable
fun Btn(text: String, ic: Ic? = null, tonal: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val a = accent
    Row(modifier.height(44.dp).press { if (enabled) onClick() }.clip(RoundedCornerShape(22.dp)).background(if (tonal) mix(a, 0.22f, C.surface2) else a).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val fg = if (tonal) C.text else C.onAccent
        if (ic != null) { Icon(ic, size = 20.dp, tint = fg); Spacer(Modifier.width(8.dp)) }
        T(text, 15.sp, W700, fg)
    }
}

/** .chip */
@Composable
fun Chip(label: String, on: Boolean, n: Int? = null, ic: Ic? = null, onClick: () -> Unit) {
    val a = accent
    Row(Modifier.rubber(4.dp).height(34.dp).clip(RoundedCornerShape(10.dp)).background(if (on) mix(a, 0.30f, C.surface2) else Color.Transparent)
        .then(if (on) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))).clickable { Hap.tick(); onClick() }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ic != null) Icon(ic, size = 16.dp)
        T(label, 13.5.sp, W650)
        if (n != null) T("$n", 13.5.sp, W600, C.text.copy(alpha = 0.6f))
    }
}

/**
 * .switch, like iOS: tap it, or drag the handle across. While your finger is on it the handle swells into a lens of liquid glass
 * (real glass over the page, a glassy bubble inside menus) and the track colour follows the handle.
 */
@Composable
fun Switch(on: Boolean, onChange: (Boolean) -> Unit) {
    val a = accent
    val d = LocalDensity.current
    val bgLayer = LocalBgLayer.current
    val liquid by Prefs.liquid.collectAsState()
    val id = remember { "sw" + (++swN) }
    val pos = remember { Animatable(if (on) 1f else 0f) }
    var held by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(if (held) 1f else 0f, spring(0.55f, 520f), label = "swg")
    val scope = rememberCoroutineScope()
    LaunchedEffect(on) { if (!held) pos.animateTo(if (on) 1f else 0f, spring(0.62f, 480f)) }
    val onS by rememberUpdatedState(on); val change by rememberUpdatedState(onChange)
    val kw = 24.dp + 14.dp * grow; val kh = 24.dp + 6.dp * grow
    val travel = with(d) { (52.dp - 8.dp - kw).toPx() }
    val p = pos.value.coerceIn(0f, 1f)
    val track by animateColorAsState(if (p > 0.5f) a else C.surface3, tween(180), label = "swt")
    Box(Modifier.size(52.dp, 32.dp).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            held = true; Hap.tick()
            val start = pos.value; var moved = false; var gaveUp = false
            val full = (52.dp - 8.dp - 38.dp).toPx()
            var side = start > 0.5f
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) { ch.consume(); break }
                val dx = ch.position.x - down.position.x; val dy = ch.position.y - down.position.y
                if (!moved && kotlin.math.abs(dy) > viewConfiguration.touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) { gaveUp = true; break }   // it's a scroll
                if (!moved && kotlin.math.abs(dx) > viewConfiguration.touchSlop / 2) moved = true
                if (moved) {
                    ch.consume()
                    var v = start + dx / full
                    v = when { v < 0f -> v * 0.25f; v > 1f -> 1f + (v - 1f) * 0.25f; else -> v }      // resists past the ends
                    // the handle glides after your finger, and is pulled towards whichever side it's nearer (the iOS snap)
                    val pull = if (v > 0.5f) (1f - v) * 0.18f else -v * 0.18f
                    scope.launch { pos.animateTo(v + pull, spring(0.85f, 1400f)) }
                    val now = v > 0.5f; if (now != side) { side = now; Hap.press() }
                }
            }
            held = false
            if (gaveUp) { scope.launch { pos.animateTo(if (onS) 1f else 0f, spring(0.62f, 480f)) }; return@awaitEachGesture }
            val next = if (moved) pos.value > 0.5f else !onS
            scope.launch { pos.animateTo(if (next) 1f else 0f, spring(0.55f, 700f)) }
            if (next != onS) { Hap.thud(); change(next) } else Hap.tick()
        }
    }) {
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).background(track)
            .then(if (p < 0.5f) Modifier.border(2.dp, Color(0xFF55555C).copy(alpha = 1f - p * 2f), RoundedCornerShape(16.dp)) else Modifier))
        val knob = Modifier.align(Alignment.CenterStart).offset { IntOffset((4.dp.toPx() + travel * pos.value).roundToInt(), 0) }.size(kw, kh)
        // the glass handle (only while held): real glass when the switch is on the page; a glassy bubble when it's in a menu
        if (grow > 0.01f) {
            if (bgLayer && liquid) Box(knob.liquid(id, kh / 2, Color.White.copy(alpha = 0.06f), z = 3, alpha = grow))
            else Box(knob.graphicsLayer { alpha = grow }.clip(RoundedCornerShape(50)).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.12f))))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.15f))), RoundedCornerShape(50)))
        }
        Box(knob.graphicsLayer { alpha = 1f - grow }.clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.lerp(Color(0xFF9A9AA2), C.onAccent, p)))
    }
}
private var swN = 0

/** cover art, or the placeholder: a gradient of a colour from the name with a note / disc / person */
@Composable
fun Art(cover: String, size: Dp?, modifier: Modifier = Modifier, shape: Shape = artShapeDefault(), ph: Ic = Ic.Note, phName: String = "", square: Boolean = true) {
    val m = (if (size != null) modifier.size(size) else if (square) modifier.aspectRatio(1f) else modifier).clip(shape)
    val f = if (cover.isNotEmpty()) Store.coverFile(cover) else null
    if (f != null && f.exists()) {
        val ctx = LocalContext.current
        val px = with(LocalDensity.current) { (size ?: 260.dp).roundToPx().coerceIn(64, 768) }
        val req = remember(f.path, px) {
            ImageRequest.Builder(ctx)
                .data(f)
                .size(px, px)
                .bitmapConfig(if (px <= 160) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
                .crossfade(false)
                .build()
        }
        AsyncImage(req, null, m.background(C.surface2), contentScale = ContentScale.Crop)
    } else {
        val c = nameColor(phName.ifEmpty { cover })
        BoxWithConstraints(m.background(Brush.linearGradient(listOf(mix(c, 0.7f, Color.Black), mix(c, 0.35f, Color.Black)))), contentAlignment = Alignment.Center) {
            Icon(ph, size = maxWidth * 0.38f, tint = Color.White.copy(alpha = 0.55f))
        }
    }
}

/** .empty */
@Composable
fun Empty(ic: Ic, title: String, text: String, action: Pair<String, () -> Unit>? = null) {
    val a = accent
    Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 50.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(mix(a, 0.18f, C.surface2)), contentAlignment = Alignment.Center) { Icon(ic, size = 46.dp, tint = a) }
        T(title, 22.sp, W700, modifier = Modifier.padding(top = 6.dp))
        T(text, 15.sp, color = C.sub, lines = 4, align = TextAlign.Center)
        if (action != null) Btn(action.first, Ic.Plus, modifier = Modifier.padding(top = 10.dp).widthIn(max = 300.dp).fillMaxWidth()) { action.second() }
    }
}

/** .plays: info line + Play / Shuffle */
@Composable
fun Plays(list: List<Song>, label: String, info: String? = null) {
    val ctx = LocalContext.current
    Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp)) {
        if (info != null) T(info, 13.sp, color = C.sub, modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Play", Ic.Play, modifier = Modifier.weight(1f), enabled = list.isNotEmpty()) { Ctl.play(ctx, list, 0, label, false) }
            Btn("Shuffle", Ic.Shuffle, tonal = true, modifier = Modifier.weight(1f), enabled = list.isNotEmpty()) { Ctl.shuffleAll(ctx, list, label) }
        }
    }
}

/** the little bars over the playing song's cover */
@Composable
fun EqBars(playing: Boolean, color: Color = Color.White) {
    val t = rememberInfiniteTransition(label = "eq")
    val hs = (0 until 3).map { i -> t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420 + i * 130, easing = LinearEasing), RepeatMode.Reverse), label = "b$i") }
    Canvas(Modifier.size(13.dp, 14.dp)) {
        val w = size.width / 4.3f
        for (i in 0 until 3) { val h = size.height * (if (playing) hs[i].value else 0.3f); drawRoundRect(color, Offset(i * (w + w / 2.5f), size.height - h), Size(w, h), CornerRadius(w / 2)) }
    }
}

// ------------------------------------------------------------------ song rows
@Composable
fun SongRow(s: Song, list: List<Song>, label: String, number: Int? = null, playlist: String? = null, showArt: Boolean = number == null) {
    val ctx = LocalContext.current
    val st by NativePlayer.state.collectAsState()
    val favs by Store.favs.collectAsState()
    val a = accent
    val dens = Cfg.rowDensity.use(); val cfgArt = Cfg.rowArt.use(); val cfgAlbum = Cfg.rowAlbum.use(); val cfgDur = Cfg.rowDur.use()
    val textOnly = number == null && !cfgArt
    val artSz = if (dens == 2) 56.dp else if (dens == 0) 44.dp else 48.dp
    val now = Ctl.queue.value.getOrNull(st.index)?.id == s.id
    val selecting = Sel.ids.isNotEmpty(); val picked = s.id in Sel.ids
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(if (dens == 0) 54.dp else if (dens == 2) 76.dp else 64.dp).clip(RoundedCornerShape(14.dp))
            .background(if (picked) mix(a, 0.35f, C.bg) else Color.Transparent)
            .combinedClickable(onLongClick = { Hap.press(); Sel.toggle(s.id) }, onDoubleClick = { if (!selecting) { val on = Store.toggleFav(s.id); if (on) Hap.success() else Hap.tap(); likeToast(on) } }) { if (selecting) { Sel.toggle(s.id); if (s.id in Sel.ids) Hap.tick() else Hap.soft() } else { Hap.tap(); Ctl.play(ctx, list, list.indexOf(s).coerceAtLeast(0), label) } }
            .padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (textOnly) Spacer(Modifier.width(4.dp)) else if (showArt) Box(Modifier.size(artSz), contentAlignment = Alignment.Center) {
                Art(s.cover, artSz, phName = s.album.ifEmpty { s.title })
                if (now) { Box(Modifier.matchParentSize().clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.45f))); EqBars(st.playing) }
                if (picked) Box(Modifier.matchParentSize().clip(RoundedCornerShape(9.dp)).background(mix(a, 0.35f, Color.Transparent)).border(3.dp, a, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { Icon(Ic.Check, size = 18.dp, strokeWidth = 3f) }
            } else Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) { if (now) EqBars(st.playing, a) else T(if ((number ?: 0) > 0) "$number" else "–", 15.sp, W600, C.sub) }
            Column(Modifier.weight(1f)) {
                T(s.title, 15.sp, W650, if (now) a else C.text)
                T((if (showArt && cfgAlbum) listOf(s.artist, s.album) else listOf(s.artist)).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Unknown Artist" }, 13.sp, color = C.sub)
            }
            if (s.id in favs) Icon(Ic.HeartFill, size = 16.dp, tint = a)
            if (s.durMs > 0 && cfgDur) T(fmt(s.durMs), 12.5.sp, color = C.faint)
            IB(Ic.More, C.sub) { Dialogs.sheet { SongSheet(s, playlist) } }
        }
    }
}

fun likeToast(on: Boolean, what: String = "Favorites") { Toasts.show(if (on) "Added to $what" else "Removed from $what") }

// ------------------------------------------------------------------ sheets
@Composable
fun SheetHead(cover: String, title: String, sub: String, phName: String = title, round: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Art(cover, 52.dp, shape = if (round) CircleShape else RoundedCornerShape(10.dp), phName = phName)
        Column(Modifier.weight(1f)) { T(title, 15.sp, W750); T(sub, 13.sp, color = C.sub) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line)); Spacer(Modifier.height(6.dp))
}
@Composable
fun Act(ic: Ic, label: String, sub: String = "", danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { if (danger) Hap.thud() else Hap.tap(); Dialogs.close(); onClick() }.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(ic, tint = if (danger) C.danger else C.sub)
        Column { T(label, 15.5.sp, W550, if (danger) C.danger else C.text); if (sub.isNotEmpty()) T(sub, 13.sp, color = C.sub, lines = 2) }
    }
}
@Composable fun SheetScroll(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.verticalScroll(rememberScrollState()), content = content)

@Composable
fun SongSheet(s: Song, playlist: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    SheetScroll {
        SheetHead(s.cover, s.title, listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" · "), s.album)
        Act(Ic.PlayNext, "Play Next") { Ctl.playNext(ctx, listOf(s)) }
        Act(Ic.AddQ, "Add to Queue") { Ctl.addToQueue(ctx, listOf(s)) }
        Act(Ic.Search, "Look Up Song Info…") { Dialogs.sheet { LookupSong(s) } }
        Act(Ic.Select, "Select Songs") { Sel.ids.clear(); Sel.ids.add(s.id) }
        Act(if (Store.isFav(s.id)) Ic.HeartFill else Ic.Heart, if (Store.isFav(s.id)) "Remove from Favorites" else "Add to Favorites") { likeToast(Store.toggleFav(s.id)) }
        Act(Ic.List, "Add to Playlist…") { Dialogs.sheet { PlaylistPicker(listOf(s.id)) } }
        if (playlist != null) Act(Ic.Close, "Remove from This Playlist") { Store.setPlaylist(playlist, (Store.playlists.value[playlist] ?: emptyList()).filter { it != s.id }) }
        Act(Ic.Disc, "Go to Album") { NpState.open.value = false; Nav.open(Page.AlbumP(s.albumKey)) }
        Act(Ic.Person, "Go to Artist") { NpState.open.value = false; Nav.open(Page.ArtistP(primaryArtist(s.artist).ifBlank { s.albumArtist })) }
        Act(Ic.Share, "Share as Music Pack…") { scope.launch { sharePack(ctx, s.title, listOf(s), null) } }
        Act(Ic.Trash, "Remove Song…", danger = true) { Dialogs.show { Confirm("Remove “${s.title}”?", "It's deleted from this phone.", "Remove") { Store.remove(listOf(s.id)) } } }
    }
}

object Radio {
    /** YouTube Music's radio for a song: similar songs download one by one and join the queue as they arrive */
    fun start(ctx: Context, s: Song) {
        Ctl.play(ctx, listOf(s), 0, "Radio · ${s.title}", false)
        Toasts.show("Finding similar songs…")
        Store.scope.launch {
            try {
                val arg = org.json.JSONObject(); val yt = s.j.optString("yt")
                if (yt.isNotBlank()) arg.put("id", yt) else arg.put("q", "${s.title} ${primaryArtist(s.artist)}")
                val tr = Engine.radio(arg); Downloader.enqueue(tr, play = true); Toasts.show("Radio: ${tr.size} songs on the way")
            } catch (e: Exception) { Toasts.show("Radio: ${e.message}") }
        }
    }
    fun artist(ctx: Context, name: String, songs: List<Song>) { songs.shuffled().firstOrNull()?.let { start(ctx, it) } ?: Toasts.show("No songs by $name yet") }
}

// ------------------------------------------------------------------ dialogs (centered)
@Composable fun DTitle(t: String) = T(t, 22.sp, W700, lines = 2, modifier = Modifier.padding(bottom = 6.dp))
@Composable fun DText(t: String) = T(t, 15.sp, color = C.sub, lines = 6, modifier = Modifier.padding(bottom = 14.dp))
@Composable
fun DButtons(vararg b: Pair<String, () -> Unit>) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
        for ((l, f) in b) Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).clickable { f() }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { T(l, 15.sp, W700, accent) }
    }
}
@Composable
fun Field(v: String, onChange: (String) -> Unit, hint: String = "", number: Boolean = false, onDone: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val a = accent
    var focused by remember { mutableStateOf(false) }
    BasicTextField(v, onChange, modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(C.surface2).then(if (focused) Modifier.border(2.dp, a, RoundedCornerShape(14.dp)) else Modifier)
        .onFocusChangedCompat { focused = it }.padding(horizontal = 16.dp),
        textStyle = TextStyle(color = C.text, fontSize = 16.sp), singleLine = true, cursorBrush = SolidColor(a),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { if (v.isEmpty()) T(hint, 16.sp, color = C.faint); inner() } })
}
fun Modifier.onFocusChangedCompat(f: (Boolean) -> Unit): Modifier = this.onFocusChanged { f(it.isFocused) }

@Composable
fun Confirm(title: String, text: String, yes: String, onYes: () -> Unit) {
    DTitle(title); DText(text)
    DButtons("Cancel" to { Dialogs.close() }, yes to { Dialogs.close(); onYes() })
}
@Composable
fun Prompt(title: String, initial: String, yes: String, onYes: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    DTitle(title); Field(t, { t = it }, onDone = { if (t.isNotBlank()) { Dialogs.close(); onYes(t.trim()) } })
    DButtons("Cancel" to { Dialogs.close() }, yes to { if (t.isNotBlank()) { Dialogs.close(); onYes(t.trim()) } })
}

@Composable
fun PlaylistPicker(ids: List<String>) {
    val pls by Store.playlists.collectAsState()
    val songs by Store.songs.collectAsState()
    val by = remember(songs) { songs.associateBy { it.id } }
    SheetScroll {
        T("Add to Playlist", 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        Act(Ic.Plus, "New Playlist…") { Dialogs.show { Prompt("New Playlist", "", "Create") { n -> val name = Store.newPlaylist(n, ids); Toasts.show("Added to “$name”") } } }
        for ((name, l) in pls) {
            Row(Modifier.fillMaxWidth().clickable { val n = Store.addToPlaylist(name, ids); Dialogs.close(); Toasts.show(if (n == 0) "Already in “$name”" else "Added to “$name”") }.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Art(l.firstNotNullOfOrNull { by[it]?.cover?.takeIf { c -> c.isNotEmpty() } } ?: "", 48.dp, shape = RoundedCornerShape(10.dp), ph = Ic.List, phName = name)
                Column { T(name, 15.sp, W650); T(plural(l.count { it in by }, "song"), 13.sp, color = C.sub) }
            }
        }
    }
}

@Composable
fun SongInfo(s: Song) {
    val f = File(Store.app.filesDir, s.path)
    DTitle(s.title)
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        @Composable fun kv(k: String, v: String) { if (v.isNotBlank() && v != "0") Row(Modifier.fillMaxWidth().drawBehind { drawLine(Color.White.copy(alpha = 0.1f), Offset.Zero, Offset(size.width, 0f)) }.padding(vertical = 7.dp)) { T(k, 13.5.sp, color = C.text.copy(alpha = 0.6f)); Spacer(Modifier.weight(1f)); T(v, 13.5.sp, lines = 3, align = TextAlign.End) } }
        kv("Artist", s.artist); kv("Album", s.album); kv("Album artist", s.albumartist); kv("Track", if (s.track > 0) "${s.track}" else ""); kv("Disc", if (s.disc > 0) "${s.disc}" else "")
        kv("Year", s.year); kv("Genre", s.genre); kv("Length", fmt(s.durMs)); kv("Format", s.j.optString("codec").ifBlank { s.ext.uppercase() }); kv("Size", fmtSize(f.length()))
        kv("Added", if (s.added > 0) DateFormat.getDateInstance().format(Date(s.added)) else ""); kv("Played", if (s.j.optInt("plays") > 0) plural(s.j.optInt("plays"), "time") else "")
        s.j.optJSONObject("tempo")?.optJSONArray("s")?.let { if (it.optDouble(0) > 0) kv("Tempo", "%.0f BPM".format(it.optDouble(0))) }
        if (s.trimStart > 0 || s.trimEnd > 0) kv("Silence cut", "%.1f s start · %.1f s end".format(s.trimStart, if (s.trimEnd > 0) s.durMs / 1000.0 - s.trimEnd else 0.0))
    }
    DButtons("Close" to { Dialogs.close() })
}

@Composable
fun EditInfo(s: Song) {
    val keys = listOf("title" to "Title", "artist" to "Artist", "album" to "Album", "albumartist" to "Album artist", "track" to "Track", "disc" to "Disc", "year" to "Year", "genre" to "Genre")
    val vals = remember { mutableStateMapOf<String, String>().apply { for ((k, _) in keys) put(k, s.j.optString(k).let { if (it == "0") "" else it }) } }
    DTitle("Edit Info")
    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, l) in keys) { T(l, 13.sp, color = C.faint, modifier = Modifier.padding(start = 4.dp)); Field(vals[k] ?: "", { vals[k] = it }, number = k == "track" || k == "disc") }
    }
    DButtons("Cancel" to { Dialogs.close() }, "Save" to {
        val f = HashMap<String, Any>(); for ((k, _) in keys) { val v = (vals[k] ?: "").trim(); f[k] = if (k == "track" || k == "disc") (v.toIntOrNull() ?: 0) else v }
        Dialogs.close(); Store.scope.launch { Store.edit(s.id, f); Toasts.show("Info updated") }
    })
}

/** the little bubble beside the search bar: one circle for your library (four squares), one for YouTube Music (the globe) */
@Composable
fun SourceBubble(yt: Boolean, onPick: (Boolean) -> Unit) {
    val a = accent
    Row(Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.surface2).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(false to Ic.Apps, true to Ic.Globe).forEach { (isYt, ic) ->
            val on = yt == isYt
            val bg by androidx.compose.animation.animateColorAsState(if (on) mix(a, 0.30f, C.surface2) else Color.Transparent, tween(220), label = "srcBg")
            Box(Modifier.size(40.dp).clip(CircleShape).background(bg).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { if (!on) Hap.tap(); onPick(isYt) }, contentAlignment = Alignment.Center) {
                Icon(ic, size = 22.dp, tint = if (on) a else C.sub)
            }
        }
    }
}

@Composable
fun SearchBox(q: String, onChange: (String) -> Unit, hint: String, onGo: () -> Unit = {}, modifier: Modifier = Modifier, focus: androidx.compose.ui.focus.FocusRequester? = null) {
    Row(modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.surface2).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Ic.Search, tint = C.sub)
        BasicTextField(q, onChange, Modifier.weight(1f).then(if (focus != null) Modifier.focusRequester(focus) else Modifier), textStyle = TextStyle(color = C.text, fontSize = 16.sp), singleLine = true, cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onGo() }),
            decorationBox = { inner -> Box { if (q.isEmpty()) T(hint, 16.sp, color = C.faint); inner() } })
        if (q.isNotEmpty()) Box(Modifier.clip(CircleShape).clickable { onChange("") }) { Icon(Ic.Close, size = 18.dp, tint = C.sub) }
    }
}

@Composable
fun LookupSong(s: Song) {
    var q by remember { mutableStateOf("${s.title} ${primaryArtist(s.artist)}".trim()) }
    var hits by remember { mutableStateOf<List<Online.Hit>?>(null) }
    val scope = rememberCoroutineScope()
    fun go() { hits = null; scope.launch { hits = withContext(Dispatchers.IO) { Online.songs(q) } } }
    LaunchedEffect(Unit) { go() }
    Column(Modifier.padding(horizontal = 20.dp)) {
        T("Look Up Song Info", 22.sp, W700, modifier = Modifier.padding(bottom = 10.dp))
        SearchBox(q, { q = it }, "Song and artist", { go() })
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Edit by Hand", false, ic = Ic.Edit) { Dialogs.show { EditInfo(s) } }
            Chip("Song Info", false, ic = Ic.Info) { Dialogs.show { SongInfo(s) } }
        }
        T(if (hits == null) "Searching iTunes and Deezer…" else if (hits!!.isEmpty()) "Nothing found" else "Best match first · tap to use", 13.sp, color = C.sub, modifier = Modifier.padding(vertical = 10.dp))
    }
    LazyColumn(Modifier.heightIn(max = 440.dp)) {
        items(hits ?: emptyList()) { h ->
            Row(Modifier.fillMaxWidth().clickable {
                Dialogs.show {
                    DTitle("Use This Info?"); DText(listOf(h.title, h.artist, h.album, h.year, h.genre).filter { it.isNotBlank() }.joinToString("\n"))
                    DButtons("Cancel" to { Dialogs.close() }, "Info only" to { Dialogs.close(); apply(s, h, false) }, "Apply" to { Dialogs.close(); apply(s, h, true) })
                }
            }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AsyncImage(h.cover, null, Modifier.size(48.dp).clip(RoundedCornerShape(9.dp)).background(C.surface2), contentScale = ContentScale.Crop)
                Column { T(h.title, 15.sp, W650); T(listOf(h.artist, h.album, h.year, h.src).filter { it.isNotBlank() }.joinToString(" · "), 13.sp, color = C.sub) }
            }
        }
    }
}
private fun apply(s: Song, h: Online.Hit, cover: Boolean) {
    Store.scope.launch {
        val f = HashMap<String, Any>()
        if (h.title.isNotBlank()) f["title"] = h.title; if (h.artist.isNotBlank()) f["artist"] = h.artist; if (h.album.isNotBlank()) f["album"] = h.album
        if (h.year.isNotBlank()) f["year"] = h.year; if (h.genre.isNotBlank()) f["genre"] = h.genre; if (h.track > 0) f["track"] = h.track; if (h.disc > 0) f["disc"] = h.disc
        Store.edit(s.id, f)
        if (cover && h.cover.isNotBlank()) try { Store.setCover(listOf(s.id), Net.get(h.cover)); Accents.forget() } catch (_: Exception) { }
        Toasts.show("Info updated")
    }
}

/** Look Up Album Info (everything + cover) or Find Cover Online (cover only) */
@Composable
fun LookupAlbum(a: Album, coverOnly: Boolean) {
    var q by remember { mutableStateOf("${a.title} ${a.artist}".trim()) }
    var hits by remember { mutableStateOf<List<Online.Hit>?>(null) }
    val scope = rememberCoroutineScope()
    fun go() { hits = null; scope.launch { hits = withContext(Dispatchers.IO) { Online.albums(q) } } }
    LaunchedEffect(Unit) { go() }
    Column(Modifier.padding(horizontal = 20.dp)) {
        T(if (coverOnly) "Find Cover Online" else "Look Up Album Info", 22.sp, W700, modifier = Modifier.padding(bottom = 10.dp))
        SearchBox(q, { q = it }, "Album and artist", { go() })
        T(if (hits == null) "Searching iTunes and Deezer…" else if (hits!!.isEmpty()) "Nothing found" else "Tap one to use it", 13.sp, color = C.sub, modifier = Modifier.padding(vertical = 10.dp))
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(hits ?: emptyList()) { h ->
            Column(Modifier.width(150.dp).press {
                Dialogs.close()
                Store.scope.launch {
                    try { if (coverOnly) Store.setCover(a.songs.map { it.id }, Net.get(h.cover)) else Online.applyAlbum(a, h); Accents.forget(); Toasts.show(if (coverOnly) "Cover updated" else "Album info updated") }
                    catch (e: Exception) { Toasts.show("Couldn't: ${e.message}") }
                }
            }) {
                AsyncImage(h.cover, null, Modifier.size(150.dp).clip(RoundedCornerShape(14.dp)).background(C.surface2), contentScale = ContentScale.Crop)
                T(h.album, 14.sp, W700, modifier = Modifier.padding(top = 8.dp)); T(listOf(h.artist, h.year).filter { it.isNotBlank() }.joinToString(" · "), 13.sp, color = C.sub)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
fun LyricsSearch(s: Song) {
    var q by remember { mutableStateOf("${s.title} ${primaryArtist(s.artist)}".trim()) }
    var hits by remember { mutableStateOf<List<org.json.JSONObject>?>(null) }
    val scope = rememberCoroutineScope()
    fun go() { hits = null; scope.launch { hits = withContext(Dispatchers.IO) { Online.searchLyrics(q) } } }
    LaunchedEffect(Unit) { go() }
    val importLrc = rememberLrcPicker(s)
    Column(Modifier.padding(horizontal = 20.dp)) {
        T("Wrong Lyrics?", 22.sp, W700, modifier = Modifier.padding(bottom = 10.dp))
        SearchBox(q, { q = it }, "Song and artist", { go() })
        T(if (hits == null) "Searching…" else if (hits!!.isEmpty()) "Nothing found" else "Pick the right ones", 13.sp, color = C.sub, modifier = Modifier.padding(vertical = 10.dp))
    }
    LazyColumn(Modifier.heightIn(max = 440.dp)) {
        item { Act(Ic.Up, "Import your own .lrc file", "Pick a lyrics file from your phone for this song") { importLrc() } }
        item { Act(Ic.Close, "This song has no lyrics") { Online.save(s.id, null); Lyr.bump() } }
        items(hits ?: emptyList()) { h ->
            val synced = h.optString("syncedLyrics").let { it.isNotBlank() && it != "null" }
            Column(Modifier.fillMaxWidth().clickable { Online.save(s.id, h); Lyr.bump(); Dialogs.close(); Toasts.show("Lyrics saved") }.padding(horizontal = 24.dp, vertical = 10.dp)) {
                T(h.optString("trackName"), 15.sp, W650)
                T(listOf(h.optString("artistName"), h.optString("albumName"), fmt((h.optDouble("duration", 0.0) * 1000).toLong()), if (synced) "synced" else "plain").filter { it.isNotBlank() }.joinToString(" · "), 13.sp, color = C.sub)
            }
        }
    }
}
object Lyr { val v = mutableStateOf(0); fun bump() { v.value++ } }

// ------------------------------------------------------------------ cards
@Composable
fun AlbumCard(a: Album, modifier: Modifier = Modifier) {
    Column(modifier.press(0.96f, onLong = { Dialogs.sheet { AlbumSheet(a) } }, onDouble = { val on = !a.songs.all { Store.isFav(it.id) }; Store.setFav(a.songs.map { it.id }, on); likeToast(on) }) { Nav.open(Page.AlbumP(a.key)) }) {
        Art(a.cover?.cover ?: "", null, Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), Ic.Disc, a.title)
        T(a.title, 14.sp, W700, modifier = Modifier.padding(top = 8.dp))
        T(a.artist, 13.sp, color = C.sub)
    }
}
@Composable
fun Shelf(title: String, content: @Composable () -> Unit) {
    T(title, 20.sp, W800, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 10.dp))
    content()
}
@Composable
fun AlbumShelf(title: String, albums: List<Album>) {
    if (albums.isEmpty()) return
    Shelf(title) { LazyRow(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(albums, key = { it.key }) { AlbumCard(it, Modifier.width(150.dp)) } } }
}

/** #selbar: across the top while songs are selected */
@Composable
fun SelectionBar() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by Store.songs.collectAsState()
    val by = songs.associateBy { it.id }
    val list = Sel.ids.mapNotNull { by[it] }
    val a = accent
    Row(Modifier.fillMaxWidth().shadow(20.dp).liquid("selbar", 0.dp, mix(a, 0.22f, C.surface).copy(alpha = 0.45f), z = 5).statusBarsPadding().height(60.dp).padding(start = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        IB(Ic.Close) { Sel.ids.clear() }
        T("${list.size} selected", 17.sp, W750, modifier = Modifier.weight(1f).padding(start = 4.dp))
        IB(Ic.HeartFill.takeIf { list.all { Store.isFav(it.id) } } ?: Ic.Heart) { Store.setFav(list.map { it.id }); Sel.ids.clear() }
        IB(Ic.List) { val ids = list.map { it.id }; Dialogs.sheet { PlaylistPicker(ids) }; Sel.ids.clear() }
        IB(Ic.More) {
            Dialogs.sheet {
                SheetScroll {
                    T(plural(list.size, "song"), 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
                    Act(Ic.Play, "Play") { Ctl.play(ctx, list, 0, "Selection"); Sel.ids.clear() }
                    Act(Ic.PlayNext, "Play Next") { Ctl.playNext(ctx, list); Sel.ids.clear() }
                    Act(Ic.AddQ, "Add to Queue") { Ctl.addToQueue(ctx, list); Sel.ids.clear() }
                    Act(Ic.Select, "Select All") { Sel.ids.clear(); Sel.ids.addAll(songs.map { it.id }) }
                    Act(Ic.Pack, "Share as Music Pack…") { val l = list; Sel.ids.clear(); Dialogs.show { Prompt("Name the Music Pack", "My Music", "Share") { n -> scope.launch { sharePack(ctx, n, l, null) } } } }
                    Act(Ic.Trash, "Remove…", danger = true) { val ids = list.map { it.id }; Dialogs.show { Confirm("Remove ${plural(ids.size, "song")}?", "They're deleted from this phone.", "Remove") { Store.remove(ids); Sel.ids.clear() } } }
                }
            }
        }
    }
}

suspend fun sharePack(ctx: Context, title: String, songs: List<Song>, playlist: String?) {
    try { val f = Packs.build(title, songs, playlist); Packs.share(ctx, f) } catch (e: Exception) { Store.busy.value = ""; Toasts.show("Couldn't make the pack: ${e.message}") }
}
