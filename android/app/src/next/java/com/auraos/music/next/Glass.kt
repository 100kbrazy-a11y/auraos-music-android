package com.auraos.music.next

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** The library / pages layer, recorded every time it draws, so glass on top can look through it. */
val LocalBackdrop = compositionLocalOf<GraphicsLayer?> { null }
object Backdrop { val frame = mutableIntStateOf(0) }

/** the recorded layer, drawn normally (the glass reads the same recording) */
fun Modifier.backdropSource(layer: GraphicsLayer): Modifier = this.drawWithContent {
    layer.record(this, layoutDirection, IntSize(size.width.toInt(), size.height.toInt())) { this@drawWithContent.drawContent() }
    drawLayer(layer)
    Backdrop.frame.intValue++
}

/**
 * Liquid glass lens (AGSL, Android 13+). Inside the rounded shape the backdrop is untouched; in a band along the edge
 * (the bezel) it's pulled inward along the edge's normal with a convex-lens profile, so things behind bend and swell at the rim.
 * Red, green and blue are bent by slightly different amounts (dispersion), so white splits into colour fringes like real glass.
 */
private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float corner;
uniform float bezel;
uniform float strength;
uniform float disp;
float sdf(float2 p) {
    float2 c = size * 0.5;
    float2 q = abs(p - c) - (c - corner);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - corner;
}
half4 main(float2 p) {
    float d = -sdf(p);
    if (d > bezel) return content.eval(p);
    float2 n = float2(sdf(p + float2(1.0, 0.0)) - sdf(p - float2(1.0, 0.0)), sdf(p + float2(0.0, 1.0)) - sdf(p - float2(0.0, 1.0)));
    n = n / max(length(n), 0.0001);
    float t = clamp(1.0 - max(d, 0.0) / bezel, 0.0, 1.0);
    float bend = (1.0 - sqrt(max(1.0 - t * t, 0.0))) * strength;
    float2 o = -n * bend;
    half4 g = content.eval(p + o);
    half r = content.eval(p + o * (1.0 + disp)).r;
    half b = content.eval(p + o * (1.0 - disp)).b;
    return half4(r, g.g, b, g.a);
}
"""

/**
 * A pane of glass. Settings › Look & Feel › Liquid Glass:
 *  on  – clear glass: a light blur, the lens refraction + colour split at the edges, a lit rim with a red / blue fringe, a shine
 *  off – frosted glass: a heavy blur and a darker tint (the WebView app's look)
 * [corner] is the shape's corner radius (the lens follows it). Blur needs Android 12+, refraction Android 13+.
 */
fun Modifier.glass(shape: Shape, tint: Color, strong: Boolean = false, corner: Dp = 24.dp): Modifier = composed {
    val liquid by Prefs.liquid.collectAsState()
    val src = LocalBackdrop.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    val lens = rememberGraphicsLayer()
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) RuntimeShader(LENS) else null }
    this.onGloballyPositioned { pos = it.positionInRoot() }.drawWithContent {
        Backdrop.frame.intValue                      // redraw whenever the backdrop does
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithContent)) }
        val w = size.width.toInt(); val h = size.height.toInt()
        if (src != null && w > 0 && h > 0 && Build.VERSION.SDK_INT >= 31) {
            lens.record(this, layoutDirection, IntSize(w, h)) { translate(-pos.x, -pos.y) { drawLayer(src) } }
            val bl = (if (liquid) 4.dp else 28.dp).toPx()
            val blur = android.graphics.RenderEffect.createBlurEffect(bl, bl, Shader.TileMode.CLAMP)
            val fx = if (liquid && shader != null) {
                val c = corner.toPx().coerceAtMost(minOf(size.width, size.height) / 2)
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("corner", c)
                shader.setFloatUniform("bezel", minOf(c * 1.1f, minOf(size.width, size.height) / 2, 22.dp.toPx()))
                shader.setFloatUniform("strength", 18.dp.toPx())
                shader.setFloatUniform("disp", 0.22f)
                android.graphics.RenderEffect.createChainEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"), blur)
            } else blur
            lens.renderEffect = fx.asComposeRenderEffect()
            clipPath(path) { drawLayer(lens) }
        }
        clipPath(path) {
            drawRect(if (liquid) tint.copy(alpha = if (strong) 0.40f else 0.26f) else tint.copy(alpha = if (strong) 0.80f else 0.68f))
            if (liquid) {
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.35f to Color.White.copy(alpha = 0.03f), 0.5f to Color.Transparent, endY = size.height))
                drawRect(Brush.verticalGradient(0.72f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.16f), endY = size.height))
            }
        }
        drawContent()
        if (liquid) {
            // the rim catches the light: white splits into red (up-left) and blue (down-right) fringes
            val s = 1.2.dp.toPx(); val o = 0.9.dp.toPx()
            translate(-o, -o) { drawPath(path, Brush.linearGradient(listOf(Color(0x99FF3B30), Color(0x22FF3B30), Color.Transparent, Color(0x33FF3B30)), Offset.Zero, Offset(size.width, size.height)), style = Stroke(s)) }
            translate(o, o) { drawPath(path, Brush.linearGradient(listOf(Color(0x993B7BFF), Color(0x223B7BFF), Color.Transparent, Color(0x553B7BFF)), Offset.Zero, Offset(size.width, size.height)), style = Stroke(s)) }
            drawPath(path, Brush.linearGradient(0f to Color.White.copy(alpha = 0.6f), 0.25f to Color.White.copy(alpha = 0.12f), 0.6f to Color.White.copy(alpha = 0.04f), 1f to Color.White.copy(alpha = 0.25f), start = Offset.Zero, end = Offset(size.width, size.height)), style = Stroke(s))
        } else drawPath(path, Color.White.copy(alpha = 0.09f), style = Stroke(1.dp.toPx()))
    }
}
