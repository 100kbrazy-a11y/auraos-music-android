package com.auraos.music.next

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/** The WebView app's own icon set: the same SVG paths (24 × 24, stroke 2, round caps), drawn natively. */
enum class Ic(val d: String, val fill: Boolean = false, val sw: Float = 2f) {
    Play("M8 5.5v13l11-6.5z", true), Pause("M6.5 5h4v14h-4zM13.5 5h4v14h-4z", true), Next("M5 6l9 6-9 6zM15.5 6H18.5v12h-3z", true),
    Prev("M19 6l-9 6 9 6zM5.5 6h3v12h-3z", true),
    Shuffle("M16 4h4v4M4 20 20 4M20 16v4h-4M14.5 14.5 20 20M4 4l5 5"), Repeat("M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3"),
    RepeatOne("M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3M11.5 10.5l1-0.5v4.5"),
    Heart("M12 20.5s-7.5-4.6-9.8-9.1C.7 8.2 2.6 4 6.6 4c2.3 0 3.9 1.4 5.4 3.3C13.5 5.4 15.1 4 17.4 4c4 0 5.9 4.2 4.4 7.4-2.3 4.5-9.8 9.1-9.8 9.1z"),
    HeartFill("M12 20.5s-7.5-4.6-9.8-9.1C.7 8.2 2.6 4 6.6 4c2.3 0 3.9 1.4 5.4 3.3C13.5 5.4 15.1 4 17.4 4c4 0 5.9 4.2 4.4 7.4-2.3 4.5-9.8 9.1-9.8 9.1z", true),
    Lyrics("M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H10l-5 4v-4H5A1.5 1.5 0 0 1 3.5 16V6A1.5 1.5 0 0 1 5 4.5zM7.5 8.5h9M7.5 11h9M7.5 13.5h6"),
    Queue("M4 6h12M4 11h12M4 16h7M16 13.5v7l5.5-3.5z"), More("M12 5h.01M12 12h.01M12 19h.01", sw = 3.2f),
    Search("M10.5 17.5a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20.5 20.5l-5-5"), Gear("M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4"),
    Back("M15 5l-7 7 7 7"), Down("M6 9.5l6 6 6-6"), Plus("M12 5v14M5 12h14"), Up("M6 14.5l6-6 6 6"),
    Disc("M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 14.2a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4z"),
    Person("M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4.5 21c0-4 3.4-6.5 7.5-6.5s7.5 2.5 7.5 6.5"),
    Note("M9 18V6l11-2v12M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"),
    List("M4 6h12M4 11h12M4 16h7M19.5 8v8.5M19.5 16.5a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z"),
    Share("M12 15V4M7.5 8.5 12 4l4.5 4.5M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4"),
    Download("M12 4v11M7.5 10.5 12 15l4.5-4.5M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4"),
    Trash("M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"), Check("M5 12.5l4.5 4.5L19 7.5"), Close("M6 6l12 12M18 6 6 18"),
    Eq("M5 20v-6M5 10V4M12 20v-9M12 7V4M19 20v-4M19 12V4M2.5 14h5M9.5 7h5M16.5 16h5"),
    Pack("M21 8l-9-5-9 5 9 5 9-5zM3 8v8l9 5 9-5V8M12 13v8"), PlayNext("M4 6h11M4 11h11M4 16h6M15 13.5l6 3.5-6 3.5z"),
    AddQ("M4 6h12M4 11h12M4 16h8M18 13v7M14.5 16.5h7"), Image("M4 5h16v14H4zM4 16l5-5 4 4 2-2 5 5M15 9h.01"),
    Info("M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5M12 8h.01"),
    Spark("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z"),
    Edit("M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4"),
    Apps("M4.5 4.5h6v6h-6zM13.5 4.5h6v6h-6zM4.5 13.5h6v6h-6zM13.5 13.5h6v6h-6z"), Globe("M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c2.5 2.5 3.5 5.5 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.5-3.5-9s1-6.5 3.5-9z"),
    History("M12 21a9 9 0 1 0-8.5-6M3 9.5V15h5.5M12 7.5V12l3 2"), Browse("M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM15.5 8.5l-2 5-5 2 2-5z"),
    Chevron("M7 10l5 5 5-5"), Logout("M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 16l-4-4 4-4M6 12h10"),
    Filter("M4 6h16M7 12h10M10 18h4"), Tag("M20.6 13.4l-7.2 7.2a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8zM7.5 7.5h.01"),
    Cut("M6 6.5a2.5 2.5 0 1 0 0 .01M6 17.5a2.5 2.5 0 1 0 0 .01M8.1 8 20 18M8.1 16 20 6"), Compress("M4 14h6v6M20 10h-6V4M14 10l6.5-6.5M3.5 20.5 10 14"),
    Sun("M12 7.6a4.4 4.4 0 1 0 0 8.8 4.4 4.4 0 0 0 0-8.8zM12 2.2v2.2M12 19.6v2.2M4.9 4.9l1.6 1.6M17.5 17.5l1.6 1.6M2.2 12h2.2M19.6 12h2.2M4.9 19.1l1.6-1.6M17.5 6.5l1.6-1.6"),
    Moon("M20.2 14.6A8.4 8.4 0 0 1 9.4 3.8a8.4 8.4 0 1 0 10.8 10.8z"), Pin("M9 3.5h6l-.8 6.2 3.8 3.3v2H6v-2l3.8-3.3zM12 15v5.5"),
    Folder("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"), Select("M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 17l2 2 4-4"),
    Timer("M12 21a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM12 9v4l2.5 1.5M9.5 2.5h5"),
    Radio("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z"),
    Link("M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c2.5 2.5 3.5 5.5 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.5-3.5-9s1-6.5 3.5-9z"),
    Wand("M10.5 17.5a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20.5 20.5l-5-5"), Sort("M4 6h16M7 12h10M10 18h4"),
    UpDown("M7 4v16M3.5 16.5 7 20l3.5-3.5M17 20V4M13.5 7.5 17 4l3.5 3.5"),
    /** four bars of different heights: the visualizer */
    Bars("M5 19v-6M9.7 19V5M14.3 19V9M19 19v-9", sw = 2.8f);

    val path: Path by lazy { PathParser().parsePathString(d).toPath() }
}

@Composable
fun Icon(ic: Ic, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = C.text, strokeWidth: Float = ic.sw) {
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            if (ic.fill) drawPath(ic.path, tint, style = Fill)
            else drawPath(ic.path, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
