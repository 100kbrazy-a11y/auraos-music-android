package com.auraos.music.next

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Thanksgiving Update: brown, yellow and red leaves drifting down behind the app.
 *
 * One shared clock ([LeafClock]) is advanced by [LeafClockDriver] (about 30 frames a second, and only while motion is on and
 * the phone is not in battery saver). [FallingLeaves] only draws from that clock, so a page that slides over the library
 * shows the leaves at exactly the same place, and nothing is animated twice. Each leaf's position is worked out from the
 * clock alone (no per-leaf state), and the drawing reads the clock inside the draw step, so the screen is redrawn
 * without being recomposed.
 */
object LeafClock { var t by mutableFloatStateOf(0f) }

private class Leaf(
    val x: Float, val y0: Float, val fall: Float, val sway: Float, val freq: Float, val phase: Float,
    val spin: Float, val flip: Float, val size: Float, val fill: Color, val rib: Color, val alpha: Float
)

// (leaf, vein) pairs: browns, yellows and reds
private val PALETTE = listOf(
    Color(0xFF8D5524) to Color(0xFF55320F), Color(0xFF6F4E37) to Color(0xFF3F2A1B), Color(0xFFA0522D) to Color(0xFF5E2D14),
    Color(0xFFF2C14E) to Color(0xFFB9861A), Color(0xFFE9A820) to Color(0xFF9C6A0B), Color(0xFFF7D774) to Color(0xFFC49A28),
    Color(0xFFC0392B) to Color(0xFF731A12), Color(0xFFA93226) to Color(0xFF5E1610), Color(0xFFD64933) to Color(0xFF86261A)
)

private val LEAVES: List<Leaf> by lazy {
    val r = Random(1123)
    List(22) { i ->
        val (fill, rib) = PALETTE[i % PALETTE.size]
        Leaf(
            x = r.nextFloat(), y0 = r.nextFloat() * 1.25f, fall = 0.045f + r.nextFloat() * 0.06f,
            sway = 0.02f + r.nextFloat() * 0.05f, freq = 0.5f + r.nextFloat() * 0.9f, phase = r.nextFloat() * 6.28f,
            spin = (if (r.nextBoolean()) 1f else -1f) * (0.25f + r.nextFloat() * 0.6f), flip = 0.8f + r.nextFloat() * 1.6f,
            size = 9f + r.nextFloat() * 12f, fill = fill, rib = rib, alpha = 0.40f + r.nextFloat() * 0.30f
        )
    }
}

// a leaf one unit long (tip at -1, stem at +1), scaled when drawn
private val LEAF_PATH: Path by lazy {
    Path().apply {
        moveTo(0f, -1f); cubicTo(0.95f, -0.55f, 0.85f, 0.55f, 0f, 1f)
        cubicTo(-0.85f, 0.55f, -0.95f, -0.55f, 0f, -1f); close()
    }
}
private val RIB_PATH: Path by lazy { Path().apply { moveTo(0f, -0.82f); lineTo(0f, 1.35f) } }

/** Advances [LeafClock]. Call it once (from the background layer). */
@Composable
fun LeafClockDriver(moving: Boolean) {
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                if (last == 0L) last = n
                val dt = (n - last) / 1_000_000_000f
                if (dt >= 1f / 32f) { LeafClock.t += dt.coerceAtMost(0.1f); last = n }
            }
        }
    }
}

/** Draws the leaves. Does not receive touches, so everything under it keeps working. */
@Composable
fun FallingLeaves(modifier: Modifier = Modifier.fillMaxSize()) {
    val stroke = remember { Stroke(width = 0.07f, cap = StrokeCap.Round) }
    Canvas(modifier) {
        val t = LeafClock.t
        val w = size.width; val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val span = 1.25f
        for (lf in LEAVES) {
            val p = ((t * lf.fall + lf.y0) % span + span) % span          // 0..1.25 of the screen height, then starts again at the top
            val y = (p - 0.12f) * h
            val x = lf.x * w + sin(t * lf.freq + lf.phase) * lf.sway * w + p * 0.06f * w
            val rot = sin(t * lf.freq * 0.8f + lf.phase) * 50f + t * lf.spin * 18f
            val flip = (0.28f + 0.72f * abs(cos(t * lf.flip + lf.phase)))     // turning over as it falls
            val s = lf.size * density
            withTransform({ translate(x, y); rotate(rot, Offset.Zero); scale(s * flip, s, Offset.Zero) }) {
                drawPath(LEAF_PATH, lf.fill, alpha = lf.alpha)
                drawPath(RIB_PATH, lf.rib, alpha = lf.alpha, style = stroke)
            }
        }
    }
}
