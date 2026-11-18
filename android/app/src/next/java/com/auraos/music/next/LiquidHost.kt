package com.auraos.music.next

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import kotlin.math.roundToInt

/**
 * Liquid glass from QWEA0/Liquid-Glass-Android under every clear element.
 *
 * The screen is three layers: the library and pages (bottom, a ComposeView), the glass (middle, real LiquidGlassViews), and
 * the controls (top, a ComposeView: tab headers, the dock, menus, the player). Anything on the top layer marked with
 * Modifier.liquid(id) gets a pane of glass under it, kept the same size and place; the glass reads only the bottom layer,
 * so it refracts the music behind it, never the controls on it.
 *
 * Liquid Glass on (Settings › Look & Feel): the library's lens (SDF refraction, dispersion, tilt-driven highlight).
 * Off: the same view as plain frosted glass (blur only).
 */
/** tl, tr, br, bl corner radii in px; scrim = a plain dark layer (dims the glass below it too, never the glass above) */
class GlassSpec(val rect: Rect, val radii: FloatArray, val tint: Int, val z: Int, val alpha: Float, val scrim: Boolean = false)

object Liquid {
    val specs = mutableStateMapOf<String, GlassSpec>()
    private var host: FrameLayout? = null
    private var source: View? = null
    private val views = HashMap<String, View>()
    private var order = ""

    fun attach(h: FrameLayout, src: View) { host = h; source = src }

    fun sync(ctx: Context, all: Map<String, GlassSpec>, liquid: Boolean) {
        val h = host ?: return
        for (id in views.keys.toList()) if (id !in all) { h.removeView(views[id]); views.remove(id) }
        for ((id, s) in all) {
            val v = views.getOrPut(id) {
                (if (s.scrim) View(ctx).also { it.setBackgroundColor(android.graphics.Color.BLACK) } else LiquidGlassView(ctx).also { g ->
                    g.backdropSource = source; g.enableDynamicBackground = true
                }).also { g ->
                    g.isClickable = false; g.isFocusable = false; g.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    h.addView(g, FrameLayout.LayoutParams(1, 1))
                }
            }
            val w = s.rect.width.roundToInt().coerceAtLeast(1); val ht = s.rect.height.roundToInt().coerceAtLeast(1)
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w || lp.height != ht) { lp.width = w; lp.height = ht; v.layoutParams = lp }
            v.translationX = s.rect.left; v.translationY = s.rect.top
            v.alpha = s.alpha; v.visibility = if (s.alpha <= 0.01f || s.rect.isEmpty) View.INVISIBLE else View.VISIBLE
            val g = v as? LiquidGlassView ?: continue
            if (g.cornerRadiusTopLeft != s.radii[0] || g.cornerRadiusTopRight != s.radii[1] || g.cornerRadiusBottomRight != s.radii[2] || g.cornerRadiusBottomLeft != s.radii[3]) {
                g.cornerRadiusTopLeft = s.radii[0]; g.cornerRadiusTopRight = s.radii[1]; g.cornerRadiusBottomRight = s.radii[2]; g.cornerRadiusBottomLeft = s.radii[3]
            }
            if (g.glassTint != s.tint) g.glassTint = s.tint
            if (liquid) {
                if (g.refractionHeight != 60f) { g.material = GlassMaterial.REGULAR; g.refractionHeight = 60f; g.bevelWidth = 36f; g.dispersionStrength = 0.16f; g.enableSensorHighlight = true }
            } else if (g.refractionHeight != 0f) { g.refractionHeight = 0f; g.bevelWidth = 2f; g.dispersionStrength = 0f; g.enableSensorHighlight = false; g.blurAmount = 0.12f }
        }
        val o = all.entries.sortedBy { it.value.z }.joinToString(",") { it.key }
        if (o != order) { order = o; for (id in o.split(",")) views[id]?.bringToFront() }
    }
}

/** put a pane of glass under this element (top layer only). [corners]: tl, tr, br, bl in dp (null = all [corner]) */
fun Modifier.liquid(id: String, corner: Dp, tint: Color = Color.Transparent, z: Int = 0, alpha: Float = 1f, on: Boolean = true,
                    corners: List<Dp>? = null, scrim: Boolean = false): Modifier = composed {
    val d = LocalDensity.current
    var r by remember { mutableStateOf<Rect?>(null) }
    val radii = with(d) { (corners ?: listOf(corner, corner, corner, corner)).map { it.toPx() }.toFloatArray() }
    val t = tint.toArgb()
    SideEffect { val rr = r; if (on && rr != null) Liquid.specs[id] = GlassSpec(rr, radii, t, z, alpha, scrim) else if (!on) Liquid.specs.remove(id) }
    DisposableEffect(id) { onDispose { Liquid.specs.remove(id) } }
    this.onGloballyPositioned { r = it.boundsInRoot() }
}
