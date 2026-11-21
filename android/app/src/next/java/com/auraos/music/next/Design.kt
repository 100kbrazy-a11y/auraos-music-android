package com.auraos.music.next

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The WebView app's design tokens (style.css :root), one to one. */
object C {
    val bg = Color(0xFF0B0B0E); val surface = Color(0xFF151519); val surface2 = Color(0xFF1E1E24); val surface3 = Color(0xFF29292F)
    val line = Color.White.copy(alpha = 0.08f); val text = Color(0xFFF4F4F6); val sub = Color(0xFFA3A3AD); val faint = Color(0xFF6D6D77)
    val defAccent = Color.hsl(345f, 0.90f, 0.72f); val onAccent = Color(0xFF1A0B10); val danger = Color(0xFFFF8A80)
}
val W550 = FontWeight(550); val W600 = FontWeight(600); val W650 = FontWeight(650); val W700 = FontWeight.Bold; val W750 = FontWeight(750); val W800 = FontWeight.ExtraBold

/** CSS color-mix(in srgb, a p%, b) */
fun mix(a: Color, p: Float, b: Color): Color = lerp(b, a, p)

val LocalAccent = compositionLocalOf { C.defAccent }
val accent: Color @Composable get() = LocalAccent.current

/** placeholder colour from a name (nameColor in main.js): hsl(hash 45% 45%) */
fun nameColor(s: String): Color { var x = 0; for (ch in s) x = (x * 31 + ch.code) % 360; return Color.hsl(x.toFloat(), 0.45f, 0.45f) }

/** coverColor() from tags.js, ported: the cover's strongest hue, made into a usable accent; cached per file */
object Accents {
    private val cache = ConcurrentHashMap<String, Color>()
    fun of(f: File?): Color? {
        if (f == null || !f.exists()) return null
        cache[f.path]?.let { return it }
        val c = try { compute(f) } catch (_: Throwable) { null } ?: return null
        cache[f.path] = c; return c
    }
    fun forget() { cache.clear(); pals.clear() }
    private val pals = ConcurrentHashMap<String, Pair<Color, Color>>()
    /** the fog's two colours: the average colour of the cover's top half and of its bottom half */
    fun pal(f: File?): Pair<Color, Color>? {
        if (f == null || !f.exists()) return null
        pals[f.path]?.let { return it }
        val p = try {
            val b0 = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return null
            val n = 24; val b = android.graphics.Bitmap.createScaledBitmap(b0, n, n, true)
            fun avg(y0: Int, y1: Int): Color { var r = 0.0; var g = 0.0; var bl = 0.0; var t = 0.0
                for (y in y0 until y1) for (x in 0 until n) { val px = b.getPixel(x, y); val rr = ((px shr 16) and 255) / 255.0; val gg = ((px shr 8) and 255) / 255.0; val bb = (px and 255) / 255.0
                    val mx = max(rr, max(gg, bb)); val mn = min(rr, min(gg, bb)); val wt = 0.15 + (if (mx > 0) (mx - mn) / mx else 0.0) * mx
                    r += rr * wt; g += gg * wt; bl += bb * wt; t += wt }
                return Color((r / t).toFloat(), (g / t).toFloat(), (bl / t).toFloat()) }
            val res = avg(0, n / 2) to avg(n / 2, n); b.recycle(); b0.recycle(); res
        } catch (_: Throwable) { null } ?: return null
        pals[f.path] = p; return p
    }
    private fun compute(f: File): Color? {
        val b0 = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return null
        val n = 40; val b = android.graphics.Bitmap.createScaledBitmap(b0, n, n, true)
        val B = 36; val w = DoubleArray(B); val rr = DoubleArray(B); val gg = DoubleArray(B); val bb = DoubleArray(B)
        var colorful = 0; var nr = 0.0; var ng = 0.0; var nb = 0.0; var nn = 0
        for (y in 0 until n) for (x in 0 until n) {
            val p = b.getPixel(x, y); val r = ((p shr 16) and 255) / 255.0; val g = ((p shr 8) and 255) / 255.0; val bl = (p and 255) / 255.0
            val mx = max(r, max(g, bl)); val mn = min(r, min(g, bl)); val sat = if (mx > 0) (mx - mn) / mx else 0.0
            if (mx < 0.14) continue
            if (sat < 0.2) { nr += r; ng += g; nb += bl; nn++; continue }
            val d = mx - mn
            var h = if (mx == r) (g - bl) / d + (if (g < bl) 6 else 0) else if (mx == g) (bl - r) / d + 2 else (r - g) / d + 4
            h /= 6
            val wt = sat.pow(1.4) * mx.pow(0.8); val k = min(B - 1, floor(h * B).toInt())
            w[k] += wt; rr[k] += r * wt; gg[k] += g * wt; bb[k] += bl * wt; colorful++
        }
        b.recycle(); b0.recycle()
        if (colorful < n * n * 0.06) { val l = if (nn > 0) (nr + ng + nb) / (3 * nn) else 0.6; return Color.hsl(230f, 0.06f, (min(0.72, max(0.58, l))).toFloat()) }
        var best = 0; var bestW = -1.0
        for (k in 0 until B) { val v = w[k] + 0.5 * (w[(k + B - 1) % B] + w[(k + 1) % B]); if (v > bestW) { bestW = v; best = k } }
        var r = 0.0; var g = 0.0; var bl = 0.0; var t = 0.0
        for (k in listOf((best + B - 1) % B, best, (best + 1) % B)) { r += rr[k]; g += gg[k]; bl += bb[k]; t += w[k] }
        return hslFix(r / t, g / t, bl / t)
    }
    private fun hslFix(r: Double, g: Double, b: Double): Color {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); var h = 0.0; var s = 0.0; val l = (mx + mn) / 2
        if (mx != mn) {
            val d = mx - mn; s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            h = if (mx == r) (g - b) / d + (if (g < b) 6 else 0) else if (mx == g) (b - r) / d + 2 else (r - g) / d + 4; h /= 6
        }
        return Color.hsl((h * 360).toFloat().coerceIn(0f, 360f), min(0.88, max(0.4, s)).toFloat(), min(0.72, max(0.56, l)).toFloat())
    }
}
