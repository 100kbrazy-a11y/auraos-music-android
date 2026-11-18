package com.auraos.music.next

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Liquid glass from QWEA0/Liquid-Glass-Android under every floating control.
 *
 * The screen is three layers: the library and pages (bottom ComposeView), the glass (real LiquidGlassViews), and the controls
 * (top ComposeView). A control marked Modifier.liquid(id) IS a pane of glass: the pane is moved and resized to the control on
 * every frame (so it can never lag or stay behind), and the control draws no background of its own, only its content. The glass
 * reads only the bottom layer, so it bends the music behind it, never the controls on it.
 *
 * Settings › Look & Feel › Glass: thickness, bend, colour split, frost, opacity and the tilt shine. Liquid Glass off = the same
 * panes as plain frosted glass.
 */
class GlassStyle(val radii: FloatArray, val tint: Color, val z: Int, val alpha: Float, val scrim: Boolean)

object Liquid {
    /** how each pane looks (changes rarely) */
    val styles = mutableStateMapOf<String, GlassStyle>()
    /** where each pane is (changes every frame while things move; not snapshot state, so it costs no recomposition) */
    val rects = ConcurrentHashMap<String, Rect>()
    /** the controls themselves: read again right before every draw, so the glass is where the control is in this very frame */
    val coords = ConcurrentHashMap<String, LayoutCoordinates>()
    private var host: FrameLayout? = null
    private var source: View? = null
    private val views = HashMap<String, View>()
    private val applied = HashMap<String, String>()
    private var order = ""
    var density = 2.625f

    fun attach(h: FrameLayout, src: View) { host = h; source = src; density = h.resources.displayMetrics.density }

    /** called right before every frame is drawn (a pre-draw listener), after the controls have been laid out */
    fun frame(ctx: Context) {
        val h = host ?: return
        val all = styles.toMap()
        for (id in views.keys.toList()) if (id !in all) { h.removeView(views[id]); views.remove(id); applied.remove(id) }
        val liquid = Prefs.liquid.value
        val thick = Prefs.glassThick.value; val bend = Prefs.glassBend.value; val split = Prefs.glassSplit.value
        val frost = Prefs.glassFrost.value; val opacity = Prefs.glassOpacity.value; val shine = Prefs.glassShine.value
        for ((id, s) in all) {
            val r = coords[id]?.takeIf { it.isAttached }?.let { c -> runCatching { c.boundsInRoot() }.getOrNull() } ?: rects[id] ?: continue
            val v = views.getOrPut(id) {
                (if (s.scrim) View(ctx).also { it.setBackgroundColor(android.graphics.Color.BLACK) } else LiquidGlassView(ctx).also { g ->
                    g.backdropSource = source; g.enableDynamicBackground = true
                }).also { g ->
                    g.isClickable = false; g.isFocusable = false; g.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    h.addView(g, FrameLayout.LayoutParams(1, 1))
                }
            }
            val w = r.width.roundToInt().coerceAtLeast(1); val ht = r.height.roundToInt().coerceAtLeast(1)
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w || lp.height != ht || v.width != w || v.height != ht) {
                lp.width = w; lp.height = ht; v.layoutParams = lp
                // resize now, in this frame (waiting for the next layout pass is what made the glass trail behind)
                v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ht, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, ht)
            }
            if (v.translationX != r.left) v.translationX = r.left
            if (v.translationY != r.top) v.translationY = r.top
            val vis = if (s.alpha <= 0.01f || r.isEmpty) View.INVISIBLE else View.VISIBLE
            if (v.visibility != vis) v.visibility = vis
            if (v.alpha != s.alpha) v.alpha = s.alpha
            val g = v as? LiquidGlassView ?: continue
            val key = "${s.radii.joinToString()}|${s.tint}|$liquid|$thick|$bend|$split|$frost|$opacity|$shine"
            if (applied[id] == key) continue
            applied[id] = key
            g.cornerRadiusTopLeft = s.radii[0]; g.cornerRadiusTopRight = s.radii[1]; g.cornerRadiusBottomRight = s.radii[2]; g.cornerRadiusBottomLeft = s.radii[3]
            val t = s.tint
            g.glassTint = t.copy(alpha = (t.alpha * opacity * 2f).coerceIn(0f, 0.95f)).toArgb()
            g.material = GlassMaterial.REGULAR
            if (liquid) {
                g.bevelWidth = (thick * density).coerceIn(2f, 200f)
                g.refractionHeight = (bend * thick * density * 2.2f).coerceIn(0f, 300f)
                g.dispersionStrength = split
                g.blurAmount = frost * 0.25f
                g.enableSensorHighlight = shine
            } else {
                g.bevelWidth = 2f; g.refractionHeight = 0f; g.dispersionStrength = 0f
                g.blurAmount = (0.35f + frost) * 0.25f
                g.enableSensorHighlight = false
            }
        }
        val o = all.entries.sortedBy { it.value.z }.joinToString(",") { it.key }
        if (o != order) { order = o; for (id in o.split(",")) views[id]?.bringToFront() }
    }
}

/** this control is a pane of glass. [corners]: tl, tr, br, bl in dp (null = all [corner]); [scrim] = a dark layer instead */
fun Modifier.liquid(id: String, corner: Dp, tint: Color = Color(0xFF1A1A20).copy(alpha = 0.4f), z: Int = 0, alpha: Float = 1f, on: Boolean = true,
                    corners: List<Dp>? = null, scrim: Boolean = false): Modifier = composed {
    val d = LocalDensity.current
    val radii = with(d) { (corners ?: listOf(corner, corner, corner, corner)).map { it.toPx() }.toFloatArray() }
    SideEffect { if (on) Liquid.styles[id] = GlassStyle(radii, tint, z, alpha, scrim) else { Liquid.styles.remove(id); Liquid.rects.remove(id); Liquid.coords.remove(id) } }
    DisposableEffect(id) { onDispose { Liquid.styles.remove(id); Liquid.rects.remove(id); Liquid.coords.remove(id) } }
    this.onGloballyPositioned { Liquid.coords[id] = it; Liquid.rects[id] = it.boundsInRoot() }
}
