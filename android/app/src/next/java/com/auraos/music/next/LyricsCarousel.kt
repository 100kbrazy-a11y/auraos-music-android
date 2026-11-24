package com.auraos.music.next

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** which lines are lit: normally one, two when an ad-lib is sung over the end of a line; or a quiet stretch (shown as dots) instead */
data class LyricFocus(val main: Int = -1, val extra: Int = -1, val gap: Int = -1) {
    /** where the wheel is turned to: a quiet stretch sits between the lines around it */
    val target: Float get() = if (gap >= 0) gap - 0.5f else max(main, 0).toFloat()
    fun lit(i: Int) = i == main || i == extra
}

object LyricTiming {
    /** the stretches with nothing sung that are long enough to show dots for: key = the line that follows (the line count = after the last line) */
    fun gaps(lines: List<Online.Line>, durMs: Long): Map<Int, LongRange> {
        val out = HashMap<Int, LongRange>()
        val first = lines.firstOrNull() ?: return out
        val last = lines.last()
        if (first.ms >= 4000 && first.text.isNotEmpty()) out[0] = 0L..first.ms
        for (i in 0 until lines.size - 1) {
            if (lines[i].text.isEmpty() || lines[i + 1].text.isEmpty()) continue
            val a = lines[i].sungEnd; val b = lines[i + 1].ms
            if (b - a >= 7000) out[i + 1] = (a + 400)..b
        }
        if (last.text.isNotEmpty() && durMs - last.sungEnd >= 8000) out[lines.size] = (last.sungEnd + 400)..durMs
        return out
    }

    /** the last line that has started by [t] (-1 before the first) */
    fun index(lines: List<Online.Line>, t: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) { val mid = (lo + hi) / 2; if (lines[mid].ms <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
        return ans
    }

    fun focus(lines: List<Online.Line>, gaps: Map<Int, LongRange>, t: Long): LyricFocus {
        if (lines.isEmpty()) return LyricFocus()
        val i = index(lines, t)
        if (i < 0) return if (gaps.containsKey(0)) LyricFocus(gap = 0) else LyricFocus()
        // an earlier line that is still being sung while this one has started (an ad-lib over its end)
        var j = i - 1
        while (j >= 0 && j >= i - 2) {
            if (lines[j].sungEnd > t && lines[j].sungEnd > lines[i].ms + 300) return LyricFocus(main = j, extra = i)
            j--
        }
        val g = gaps[i + 1]
        if (t > lines[i].sungEnd && g != null && t in g) return LyricFocus(gap = i + 1)
        return LyricFocus(main = i)
    }
}

private class Row(val line: Int, val gap: Boolean)

/**
 * Lyrics like the Mac app's: lines sit on a gentle curve like a wheel (the further from the line being sung, the smaller, dimmer and further
 * left), the line being sung is lit word by word with the word being sung a little bigger, two lines are lit when an ad-lib overlaps, and
 * a long quiet stretch shows three dots that fill as the next line comes closer. Drag to look around; a moment after you let go it glides back.
 * [now] is the song's position in lyric time (the lyrics' own offset already taken off).
 */
@Composable
fun CarouselLyrics(lines: List<Online.Line>, durMs: Long, now: () -> Long, onSeek: (Int) -> Unit) {
    val n = lines.size
    // the caller's clock can change (the lyrics are nudged earlier or later): everything below asks the latest one
    val nowU by rememberUpdatedState(now)
    val live: () -> Long = remember { { nowU() } }
    val gaps = remember(lines, durMs) { LyricTiming.gaps(lines, durMs) }
    val rows = remember(lines, gaps) {
        val l = ArrayList<Row>()
        for (i in 0 until n) { if (gaps.containsKey(i)) l.add(Row(i, true)); l.add(Row(i, false)) }
        if (gaps.containsKey(n)) l.add(Row(n, true))
        l
    }
    val rowOfLine = remember(rows) { IntArray(n + 1) { 0 }.also { a -> rows.forEachIndexed { r, row -> if (!row.gap) a[row.line] = r } } }
    val rowOfGap = remember(rows) { IntArray(n + 1) { 0 }.also { a -> rows.forEachIndexed { r, row -> if (row.gap) a[row.line] = r } } }
    var tick by remember { mutableLongStateOf(live()) }
    LaunchedEffect(Unit) { while (true) { tick = live(); delay(100) } }
    val focus by remember(lines, gaps) { derivedStateOf { LyricTiming.focus(lines, gaps, tick + 200) } }
    val focusRow = if (focus.gap >= 0) rowOfGap[focus.gap.coerceIn(0, n)] else rowOfLine[max(focus.main, 0).coerceIn(0, n)]

    val scroll = remember { Animatable(focusRow.toFloat()) }
    val wheel = remember { Animatable(focus.target) }
    var browsing by remember { mutableStateOf(false) }
    var lastDrag by remember { mutableLongStateOf(0L) }
    val bf by animateFloatAsState(if (browsing) 1f else 0f, tween(260), label = "bf")
    val scope = rememberCoroutineScope()
    // the wheel turns with a little bounce, and the page follows it
    LaunchedEffect(focus) { wheel.animateTo(focus.target, spring(0.62f, 103f)) }
    LaunchedEffect(focusRow, browsing, lastDrag) {
        if (browsing) { delay(2500); browsing = false; return@LaunchedEffect }
        val far = abs(focusRow - scroll.value) > 3
        scroll.animateTo(focusRow.toFloat(), spring(0.78f, if (far) 55f else 80f))
    }
    var avgH by remember { mutableStateOf(100f) }
    val dens = LocalDensity.current.density
    val sweep = Cfg.lyricSweep.use()
    val fontSize = Cfg.lyricSize.use(); val center = Cfg.lyricAlign.use() == 1
    val shift = Cfg.lyricShift.use().toLong()
    val gutter = with(LocalDensity.current) { 18.dp.roundToPx() }
    val posF = when (Cfg.lyricPos.use()) { 0 -> 0.27f; 2 -> 0.62f; else -> 0.42f }

    androidx.compose.ui.layout.Layout(content = {
        rows.forEach { row ->
            val lit = if (row.gap) focus.gap == row.line else focus.lit(row.line)
            val index = if (row.gap) row.line - 0.5f else row.line.toFloat()
            val base = Modifier.fillMaxWidth().graphicsLayer {
                val d = if (lit) 0f else index - wheel.value
                val a = min(abs(d), 4f) * (1f - bf)
                val sc = 1f - a * 0.03f
                scaleX = sc; scaleY = sc
                transformOrigin = TransformOrigin(if (center) 0.5f else 0f, 0.5f)
                cameraDistance = 14f * dens
                rotationY = d.coerceIn(-3f, 3f) * 3f * (1f - bf)
                if (!center) translationX = -(1f - cos(a * 0.5f)) * 64f * dens
                // browsing: every line is sharp and readable
                alpha = (0.16f + 0.84f * exp(-a * 0.9f)) * (1f - bf) + (if (lit) 1f else 0.7f) * bf
            }
            if (row.gap) {
                val done = if (focus.gap >= 0) focus.gap > row.line else focus.main >= row.line
                Box(base.padding(horizontal = 22.dp)) { GapDots(gaps[row.line]!!, focus.gap == row.line, done, live, center) }
            } else {
                val ln = lines[row.line]
                Box(base.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { Hap.tick(); onSeek(row.line) }.padding(horizontal = 22.dp, vertical = 12.dp)) {
                    if (lit && sweep && ln.words.isNotEmpty() && ln.text.isNotBlank()) LiveLine(ln, live, fontSize, center, shift)
                    else Text(ln.text.ifBlank { "♪" }, Modifier.fillMaxWidth(), color = Color.White, fontSize = fontSize.sp, fontWeight = W800, lineHeight = (fontSize * 1.22f).sp, letterSpacing = (-0.4).sp,
                        textAlign = if (center) TextAlign.Center else TextAlign.Start, maxLines = 6)
                }
            }
        }
    }, modifier = Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        // feather every edge: no hard line where the lyrics end
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.04f to Color.Black, 0.96f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }.pointerInput(rows.size) {
        var lastRow = 0
        detectVerticalDragGestures(onDragStart = { browsing = true; lastRow = scroll.value.roundToInt(); Hap.soft() }, onDragEnd = { lastDrag = System.currentTimeMillis() }, onDragCancel = { lastDrag = System.currentTimeMillis() }) { c, dy ->
            c.consume(); browsing = true
            val v = (scroll.value - dy / avgH).coerceIn(0f, (rows.size - 1).coerceAtLeast(0).toFloat())
            scope.launch { scroll.snapTo(v) }
            val r = v.roundToInt(); if (r != lastRow) { lastRow = r; Hap.tick() }
        }
    }) { ms, c ->
        val ps = ms.map { it.measure(c.copy(minWidth = c.maxWidth + gutter * 2, maxWidth = c.maxWidth + gutter * 2, minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)) }
        val h = c.maxHeight.toFloat()
        val tops = FloatArray(ps.size); var acc = 0f
        ps.forEachIndexed { i, p -> tops[i] = acc; acc += p.height }
        if (ps.isNotEmpty()) avgH = (acc / ps.size).coerceAtLeast(1f)
        layout(c.maxWidth, c.maxHeight) {
            if (ps.isEmpty()) return@layout
            val sv = scroll.value.coerceIn(0f, (ps.size - 1).toFloat())
            val lo = sv.toInt(); val hi = min(ps.size - 1, lo + 1); val fr = sv - lo
            fun anchor(k: Int) = h * posF - ps[k].height / 2f - tops[k]
            val top = anchor(lo) + (anchor(hi) - anchor(lo)) * fr        // continuous: never jumps mid-move
            ps.forEachIndexed { i, p -> val y = top + tops[i]; if (y < h && y + p.height > 0) p.placeRelative(-gutter, y.roundToInt()) }
        }
    }
}

/** the line being sung, lit word by word; it follows the player's clock every frame, so each word is reached at the moment it is sung */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveLine(line: Online.Line, now: () -> Long, fontSize: Float, center: Boolean, shift: Long) {
    val sh by rememberUpdatedState(shift)
    val t = remember { mutableLongStateOf(now() + 50 + shift) }
    LaunchedEffect(line) { while (true) androidx.compose.runtime.withFrameNanos { t.longValue = now() + 50 + sh } }
    val ws = line.words
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start, verticalArrangement = Arrangement.spacedBy((fontSize * 0.06f).dp)) {
        ws.forEachIndexed { i, w ->
            val following = if (i + 1 < ws.size) ws[i + 1].start else (if (line.sungEnd > w.start + 300) line.sungEnd else max(w.start + 400, line.end - 100))
            // a word is lit while it is sung: up to where the file says it stops, not across the pause after it
            val next = if (w.end >= 0) min(max(w.end, w.start + 100), following) else following
            // a word held through a pause still finishes quickly
            val dur = max(80L, min(next - w.start, 1600L))
            WordView(w.text.trim(), if (w.text.endsWith(" ")) (fontSize * 0.28f).dp else 0.dp, w.start, dur, fontSize, t)
        }
    }
}

/** one word: dim until it is sung, then filled from left to right at the speed it is sung; the word being sung is a little bigger */
@Composable
private fun WordView(text: String, trailing: Dp, start: Long, dur: Long, fontSize: Float, t: LongState) {
    val prog = { ((t.longValue - start).toFloat() / dur).coerceIn(0f, 1f) }
    val active by remember { derivedStateOf { val p = prog(); p > 0f && p < 1f } }
    val grow = animateFloatAsState(if (active) 1.09f else 1f, spring(0.5f, 330f), label = "w")
    Box(Modifier.padding(end = trailing).graphicsLayer { scaleX = grow.value; scaleY = grow.value; transformOrigin = TransformOrigin(0f, 1f) }) {
        Text(text, color = Color.White.copy(alpha = 0.38f), fontSize = fontSize.sp, fontWeight = W800, lineHeight = (fontSize * 1.22f).sp, letterSpacing = (-0.4).sp, maxLines = 1, softWrap = false)
        Text(text, color = Color.White, fontSize = fontSize.sp, fontWeight = W800, lineHeight = (fontSize * 1.22f).sp, letterSpacing = (-0.4).sp, maxLines = 1, softWrap = false,
            modifier = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                val p = prog()
                if (p >= 1f) drawContent()
                else if (p > 0f) {
                    drawContent()
                    drawRect(Brush.horizontalGradient(0f to Color.Black, p to Color.Black, min(1f, p + 0.22f) to Color.Transparent, startX = 0f, endX = this.size.width), blendMode = BlendMode.DstIn)
                }
            })
    }
}

/** "• • •" for a stretch with nothing sung (the start and end of a song, a long break): the dots fill one by one as the next line gets closer */
@Composable
private fun GapDots(range: LongRange, live: Boolean, done: Boolean, now: () -> Long, center: Boolean) {
    val t = remember { mutableLongStateOf(0L) }
    if (live) LaunchedEffect(range) { while (true) androidx.compose.runtime.withFrameNanos { t.longValue = now() + 50 } }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (center) Alignment.Center else Alignment.CenterStart) {
        Canvas(Modifier.padding(vertical = 12.dp).size(88.dp, 24.dp)) {
            val p = if (live) ((t.longValue - range.first).toFloat() / max(500L, range.last - range.first)).coerceIn(0f, 1f) else if (done) 1f else 0f
            val r = 10.dp.toPx(); val sp = 14.dp.toPx()
            for (k in 0 until 3) {
                val f = (p * 3f - k).coerceIn(0f, 1f)
                drawCircle(Color.White.copy(alpha = 0.28f + 0.72f * f), r * (0.8f + 0.3f * f), Offset(r + k * (2 * r + sp), this.size.height / 2f))
            }
        }
    }
}
