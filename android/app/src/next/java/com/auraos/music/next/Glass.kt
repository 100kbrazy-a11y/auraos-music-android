package com.auraos.music.next

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild

/** What the glass blurs: the library / pages layer (set up in App with Modifier.haze) */
val LocalHaze = compositionLocalOf<HazeState?> { null }

/**
 * A pane of glass over the app. Two looks (Settings › Look & Feel › Liquid Glass):
 *  - Liquid Glass: clear (lighter blur), a little brighter, a specular rim lit from the top-left, a shine across the top and a
 *    soft lens shadow along the bottom edge, like a drop of glass.
 *  - Frosted (off): the WebView app's frosted blur (strong blur, darker tint, a hairline border).
 * Blur is real (it reads what's behind) on Android 12+; older phones get the tint without the blur.
 */
fun Modifier.glass(shape: Shape, tint: Color, strong: Boolean = false): Modifier = composed {
    val liquid by Prefs.liquid.collectAsState()
    val haze = LocalHaze.current
    val base = if (haze != null) this.hazeChild(haze, shape, HazeStyle(
        tint = if (liquid) tint.copy(alpha = if (strong) 0.42f else 0.30f) else tint.copy(alpha = if (strong) 0.78f else 0.66f),
        blurRadius = if (liquid) 14.dp else 30.dp,
        noiseFactor = if (liquid) 0f else 0.08f,
    )) else this
    base.drawWithContent {
        drawContent()
        val o: Outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(o) }
        if (liquid) {
            clipPath(path) {
                // shine across the top, lens shadow at the bottom
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.35f to Color.White.copy(alpha = 0.03f), 0.5f to Color.Transparent, endY = size.height))
                drawRect(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.18f), endY = size.height))
            }
            // specular rim: bright where the light hits (top-left), fading round the sides, a faint return at the bottom-right
            drawPath(path, Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.55f), 0.25f to Color.White.copy(alpha = 0.12f), 0.6f to Color.White.copy(alpha = 0.04f), 1f to Color.White.copy(alpha = 0.22f),
                start = Offset.Zero, end = Offset(size.width, size.height)), style = Stroke(1.2.dp.toPx()))
        } else {
            drawPath(path, Color.White.copy(alpha = 0.09f), style = Stroke(1.dp.toPx()))
        }
    }
}
