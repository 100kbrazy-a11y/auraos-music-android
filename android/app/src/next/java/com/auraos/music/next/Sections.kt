package com.auraos.music.next

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hand-drawn section icons. Each one is a soft filled shape under a rounded line drawing (24 × 24 grid), and both take the
 * accent colour, so the whole set re-tints with the app.
 */
class Glyph(val line: String, val soft: String = "", val dots: List<Offset> = emptyList(), val w: Float = 1.8f)

enum class SG(val g: Glyph) {
    Customize(Glyph("M3 7h6M15 7h6M3 12h2M11 12h10M3 17h9M18 17h3M12 4.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4zM8 9.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4zM15 14.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z",
        "M12 4.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4zM8 9.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4zM15 14.8a2.2 2.2 0 1 0 0 4.4 2.2 2.2 0 0 0 0-4.4z")),
    Music(Glyph("M9 17.5V5.5l10-2v12M6 14.5a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM16 12.5a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
        "M6 14.5a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM16 12.5a3 3 0 1 0 0 6 3 3 0 0 0 0-6z")),
    Playback(Glyph("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM10 8.3v7.4l6-3.7z", "M10 8.3v7.4l6-3.7z M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z")),
    Look(Glyph("M12 3a9 9 0 0 0 0 18c1.6 0 2.2-1 2.2-2 0-.9-.6-1.4-.6-2.1 0-1 .8-1.6 1.9-1.6H18a3 3 0 0 0 3-3C21 7 17 3 12 3z",
        "M12 3a9 9 0 0 0 0 18c1.6 0 2.2-1 2.2-2 0-.9-.6-1.4-.6-2.1 0-1 .8-1.6 1.9-1.6H18a3 3 0 0 0 3-3C21 7 17 3 12 3z", listOf(Offset(7.6f, 11.4f), Offset(10.2f, 7.4f), Offset(15f, 7.6f)))),
    Account(Glyph("M12 3.5a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4.2 20.5c.5-3.6 3.6-6 7.8-6s7.3 2.4 7.8 6", "M12 3.5a4 4 0 1 0 0 8 4 4 0 0 0 0-8z")),
    Update(Glyph("M20 12a8 8 0 1 1-2.6-5.9M20.2 4.2v4.4h-4.4M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z", "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z")),
    Pulse(Glyph("M3 12h4l2.4-6 4 12 2.6-6H21", "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z")),
    Scan(Glyph("M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM14.5 11.5a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 0 0 0-4.8zM16.3 16.2l2 2", "M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")),
    Star(Glyph("M12 3l2.3 5.7 6 .5-4.6 3.9 1.4 5.9L12 16l-5.1 3 1.4-5.9L3.7 9.2l6-.5z", "M12 3l2.3 5.7 6 .5-4.6 3.9 1.4 5.9L12 16l-5.1 3 1.4-5.9L3.7 9.2l6-.5z")),
    Tabs(Glyph("M6 4.5h12a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-9a3 3 0 0 1 3-3zM3 14h18M9 14v5M15 14v5", "M3 14h18v2.5a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3z")),
    Library(Glyph("M6 3.5h12M4.5 6.5h15M6 10h12a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 18 20H6a1.5 1.5 0 0 1-1.5-1.5v-7A1.5 1.5 0 0 1 6 10zM10 13.5v3.5l3-1.7z", "M6 10h12a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 18 20H6a1.5 1.5 0 0 1-1.5-1.5v-7A1.5 1.5 0 0 1 6 10z")),
    Layout(Glyph("M6 3.5h12a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 11V5A1.5 1.5 0 0 1 6 3.5zM5 16h14M5 20h8", "M6 3.5h12a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 11V5A1.5 1.5 0 0 1 6 3.5z")),
    Cover(Glyph("M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM8.5 6.5h7v7h-7zM9 17h6", "M8.5 6.5h7v7h-7z")),
    Viz(Glyph("M5 19.5v-6M9.5 19.5v-12M14 19.5v-8M18.5 19.5v-14", "M4 19.5h16", w = 2.6f)),
    Lyrics(Glyph("M4 6h16M4 12h11M4 18h14", "M3 9h14a1.5 1.5 0 0 1 0 6H3z", w = 1.9f)),
    Buttons(Glyph("M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM10.8 9.8v4.4l3.5-2.2zM3.2 8v8M20.8 8v8", "M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z")),
    Bar(Glyph("M7.5 5.5h9a5 5 0 0 1 0 10h-9a5 5 0 0 1 0-10zM10.5 8.8v3.4l2.7-1.7zM8 20h8", "M7.5 5.5h9a5 5 0 0 1 0 10h-9a5 5 0 0 1 0-10z")),
    Shapes(Glyph("M8 3.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9zM13 12h8v8h-8zM3.5 21l4-7 4 7z", "M8 3.5a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9z")),
    Colour(Glyph("M12 3c-4 5-6.5 8-6.5 11.5a6.5 6.5 0 0 0 13 0C18.5 11 16 8 12 3zM9 15a3 3 0 0 0 3 3", "M12 3c-4 5-6.5 8-6.5 11.5a6.5 6.5 0 0 0 13 0C18.5 11 16 8 12 3z")),
    Feel(Glyph("M10 5.5h4a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-11a1 1 0 0 1 1-1zM6.2 8c-1.6 2.3-1.6 5.7 0 8M17.8 8c1.6 2.3 1.6 5.7 0 8M3.2 10c-.9 1.3-.9 2.7 0 4M20.8 10c.9 1.3.9 2.7 0 4", "M10 5.5h4a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-11a1 1 0 0 1 1-1z")),
    Folder(Glyph("M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")),
    Info(Glyph("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 11v5.5", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z", listOf(Offset(12f, 7.9f)))),
    Motion(Glyph("M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9zM18.5 15.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z", "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z")),
    Download(Glyph("M12 4v11M7.5 10.5 12 15l4.5-4.5M5 15v3a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3", "M5 15h14v3a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2z")),
    Upload(Glyph("M12 15V4M7.5 8.5 12 4l4.5 4.5M5 15v3a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3", "M5 15h14v3a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2z")),
    Reset(Glyph("M4 12a8 8 0 1 0 2.6-5.9M3.8 4.2v4.4h4.4M12 8v4.2l2.8 1.6", "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16z"))
}

private val parsedPaths = HashMap<String, Path>()
private fun pathOf(d: String): Path = parsedPaths.getOrPut(d) { PathParser().parsePathString(d).toPath() }

@Composable
fun SIcon(sg: SG, size: Dp = 28.dp, tint: Color = accent) {
    val g = sg.g
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            if (g.soft.isNotEmpty()) drawPath(pathOf(g.soft), tint.copy(alpha = 0.26f), style = Fill)
            drawPath(pathOf(g.line), tint, style = Stroke(g.w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            for (o in g.dots) drawCircle(tint, 1.35f, o)
        }
    }
}

/** the icon on its own softly tinted tile */
@Composable
fun SIconTile(sg: SG, tile: Dp = 46.dp, size: Dp = 28.dp) {
    val a = accent
    Box(Modifier.size(tile).clip(RoundedCornerShape(tile * 0.32f)).background(mix(a, 0.16f, C.surface2)), contentAlignment = Alignment.Center) { SIcon(sg, size, a) }
}

/** one section of Settings: its own button with its own icon */
@Composable
fun HubRow(sg: SG, title: String, sub: String, onClick: () -> Unit) {
    val r = RoundedCornerShape(Cfg.cardRadius.use().dp)
    Row(Modifier.padding(horizontal = 14.dp, vertical = 5.dp).fillMaxWidth().clip(r).background(C.surface).border(1.dp, Color.White.copy(alpha = 0.06f), r)
        .clickable { Hap.tap(); onClick() }.padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        SIcon(sg, 30.dp)
        Column(Modifier.weight(1f)) { T(title, 17.sp, W700); if (sub.isNotEmpty()) T(sub, 13.5.sp, color = C.sub, lines = 2) }
        Icon(Ic.Chevron, Modifier.rotate(-90f), 20.dp, C.sub)
    }
}

/** a square tile for the Customize centre, two to a row */
@Composable
fun HubTile(sg: SG, title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(Cfg.cardRadius.use().dp)).background(C.surface).clickable { Hap.tap(); onClick() }.padding(16.dp).heightIn(min = 118.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SIconTile(sg, 48.dp, 30.dp)
        Column { T(title, 16.sp, W700); T(sub, 12.5.sp, color = C.sub, lines = 2) }
    }
}
