package com.auraos.music.next

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

enum class Ic { Play, Pause, Next, Prev, Shuffle, Repeat, RepeatOne, Heart, HeartFill, Search, Note, Disc, Person, List, Download, Gear, Close, Down, More, Queue, Timer, Plus, Trash, Back, Check, Sort, Info, Folder, Eq, Lyrics, Sun, Moon, Radio, Share, Edit, Select, Up, Image, Wand, Spark, Pack, Cut, Link }

/** Every icon is drawn here with Canvas paths (24 x 24 grid): no icon fonts, no image files, no extra libraries. */
@Composable
fun Icon(ic: Ic, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val sw = 2f * u
        val st = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(tint, p(a, b), p(c, d), sw, StrokeCap.Round)
        fun Path.m(x: Float, y: Float) = moveTo(x * u, y * u)
        fun Path.l(x: Float, y: Float) = lineTo(x * u, y * u)
        fun Path.c(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) = cubicTo(a * u, b * u, c * u, d * u, e * u, f * u)
        fun stroke(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = st)
        fun fill(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = Fill)
        fun ring(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, p(x, y), style = st)
        fun dot(x: Float, y: Float, r: Float) = drawCircle(tint, r * u, p(x, y))
        fun heart(): Path.() -> Unit = {
            m(12f, 20.5f); c(5f, 15f, 3f, 11.5f, 3f, 8.5f); c(3f, 5.5f, 5.5f, 3.5f, 8f, 3.5f); c(10f, 3.5f, 11.4f, 4.6f, 12f, 6f)
            c(12.6f, 4.6f, 14f, 3.5f, 16f, 3.5f); c(18.5f, 3.5f, 21f, 5.5f, 21f, 8.5f); c(21f, 11.5f, 19f, 15f, 12f, 20.5f); close()
        }
        when (ic) {
            Ic.Play -> fill { m(7f, 4f); l(20f, 12f); l(7f, 20f); close() }
            Ic.Pause -> { drawRoundRect(tint, p(6f, 5f), Size(4f * u, 14f * u), CornerRadius(1f * u)); drawRoundRect(tint, p(14f, 5f), Size(4f * u, 14f * u), CornerRadius(1f * u)) }
            Ic.Next -> { fill { m(6f, 5f); l(16f, 12f); l(6f, 19f); close() }; line(18.5f, 5f, 18.5f, 19f) }
            Ic.Prev -> { fill { m(18f, 5f); l(8f, 12f); l(18f, 19f); close() }; line(5.5f, 5f, 5.5f, 19f) }
            Ic.Shuffle -> {
                stroke { m(3f, 7f); l(8f, 7f); l(16f, 17f); l(20f, 17f) }; stroke { m(3f, 17f); l(8f, 17f); l(11f, 13.5f) }
                stroke { m(13.5f, 10.5f); l(16f, 7f); l(20f, 7f) }; stroke { m(18f, 5f); l(20.5f, 7f); l(18f, 9f) }; stroke { m(18f, 15f); l(20.5f, 17f); l(18f, 19f) }
            }
            Ic.Repeat, Ic.RepeatOne -> {
                stroke { m(4f, 11f); l(4f, 8f); l(7f, 5f); l(19f, 5f) }; stroke { m(16f, 2.5f); l(19f, 5f); l(16f, 7.5f) }
                stroke { m(20f, 13f); l(20f, 16f); l(17f, 19f); l(5f, 19f) }; stroke { m(8f, 16.5f); l(5f, 19f); l(8f, 21.5f) }
                if (ic == Ic.RepeatOne) stroke { m(10.5f, 10.5f); l(12f, 9.5f); l(12f, 14.5f) }
            }
            Ic.Heart -> stroke(heart())
            Ic.HeartFill -> fill(heart())
            Ic.Search -> { ring(10.5f, 10.5f, 6.5f); line(15.5f, 15.5f, 20f, 20f) }
            Ic.Note -> { dot(8f, 18f, 3f); line(11f, 18f, 11f, 4f); line(11f, 4f, 18f, 6.5f) }
            Ic.Disc -> { ring(12f, 12f, 9f); ring(12f, 12f, 2.5f) }
            Ic.Person -> { ring(12f, 8f, 4f); stroke { m(4f, 21f); c(4f, 16f, 8f, 14f, 12f, 14f); c(16f, 14f, 20f, 16f, 20f, 21f) } }
            Ic.List -> { line(8f, 6f, 20f, 6f); line(8f, 12f, 20f, 12f); line(8f, 18f, 20f, 18f); dot(4f, 6f, 1.2f); dot(4f, 12f, 1.2f); dot(4f, 18f, 1.2f) }
            Ic.Download -> { line(12f, 4f, 12f, 15f); stroke { m(7f, 10f); l(12f, 15f); l(17f, 10f) }; line(5f, 20f, 19f, 20f) }
            Ic.Gear -> {
                ring(12f, 12f, 5.5f); dot(12f, 12f, 1.6f)
                for (k in 0 until 8) { val a = k * Math.PI / 4; val ca = cos(a).toFloat(); val sa = sin(a).toFloat(); line(12f + 7.5f * ca, 12f + 7.5f * sa, 12f + 10f * ca, 12f + 10f * sa) }
            }
            Ic.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Ic.Down -> stroke { m(6f, 9f); l(12f, 15f); l(18f, 9f) }
            Ic.More -> { dot(12f, 5f, 1.8f); dot(12f, 12f, 1.8f); dot(12f, 19f, 1.8f) }
            Ic.Queue -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 11f, 18f); fill { m(14f, 15f); l(20f, 18.5f); l(14f, 22f); close() } }
            Ic.Timer -> { ring(12f, 13f, 8f); stroke { m(12f, 8.5f); l(12f, 13f); l(15.5f, 15f) }; line(9.5f, 2.5f, 14.5f, 2.5f) }
            Ic.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Ic.Trash -> { line(4f, 7f, 20f, 7f); line(9.5f, 4f, 14.5f, 4f); stroke { m(6.5f, 7f); l(7.5f, 20f); l(16.5f, 20f); l(17.5f, 7f) }; line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f) }
            Ic.Back -> stroke { m(15f, 5f); l(8f, 12f); l(15f, 19f) }
            Ic.Check -> stroke { m(5f, 12.5f); l(10f, 17.5f); l(19f, 7f) }
            Ic.Sort -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 15f, 12f); line(4f, 17f, 10f, 17f) }
            Ic.Info -> { ring(12f, 12f, 9f); line(12f, 11f, 12f, 17f); dot(12f, 7.5f, 1.3f) }
            Ic.Folder -> stroke { m(3f, 6f); l(9f, 6f); l(11f, 9f); l(21f, 9f); l(21f, 19f); l(3f, 19f); close() }
            Ic.Lyrics -> { stroke { m(4f, 5f); l(20f, 5f); l(20f, 16f); l(11f, 16f); l(6f, 20f); l(6f, 16f); l(4f, 16f); close() }; line(8f, 9f, 16f, 9f); line(8f, 12.5f, 13f, 12.5f) }
            Ic.Sun -> { ring(12f, 12f, 4f); for (k in 0 until 8) { val a = k * Math.PI / 4; val ca = cos(a).toFloat(); val sa = sin(a).toFloat(); line(12f + 7f * ca, 12f + 7f * sa, 12f + 9.5f * ca, 12f + 9.5f * sa) } }
            Ic.Moon -> stroke { m(20f, 14.5f); c(18.5f, 19f, 13f, 21.5f, 8.5f, 19f); c(4f, 16.5f, 2.5f, 10.5f, 5.5f, 6.5f); c(7f, 4.5f, 9f, 3.5f, 11f, 3.5f); c(8.5f, 8f, 10f, 13f, 14f, 15f); c(16f, 16f, 18.5f, 15.8f, 20f, 14.5f); close() }
            Ic.Radio -> { dot(12f, 12f, 2f); stroke { m(8f, 8f); c(6f, 10f, 6f, 14f, 8f, 16f) }; stroke { m(16f, 8f); c(18f, 10f, 18f, 14f, 16f, 16f) }; stroke { m(5f, 5f); c(1.5f, 8.5f, 1.5f, 15.5f, 5f, 19f) }; stroke { m(19f, 5f); c(22.5f, 8.5f, 22.5f, 15.5f, 19f, 19f) } }
            Ic.Share -> { line(12f, 3f, 12f, 15f); stroke { m(8f, 7f); l(12f, 3f); l(16f, 7f) }; stroke { m(5f, 11f); l(5f, 20f); l(19f, 20f); l(19f, 11f) } }
            Ic.Edit -> { stroke { m(4f, 20f); l(5f, 15.5f); l(15.5f, 5f); l(19f, 8.5f); l(8.5f, 19f); close() }; line(13f, 7.5f, 16.5f, 11f) }
            Ic.Select -> { ring(12f, 12f, 9f); stroke { m(8f, 12.5f); l(11f, 15.5f); l(16.5f, 9f) } }
            Ic.Up -> stroke { m(6f, 15f); l(12f, 9f); l(18f, 15f) }
            Ic.Image -> { stroke { m(4f, 5f); l(20f, 5f); l(20f, 19f); l(4f, 19f); close() }; stroke { m(4f, 17f); l(9f, 12f); l(13f, 16f); l(15.5f, 13.5f); l(20f, 18f) }; dot(15.5f, 9f, 1.5f) }
            Ic.Wand -> { line(4f, 20f, 15f, 9f); fill { m(17f, 2f); l(18f, 5f); l(21f, 6f); l(18f, 7f); l(17f, 10f); l(16f, 7f); l(13f, 6f); l(16f, 5f); close() } }
            Ic.Spark -> fill { m(12f, 2f); l(14f, 10f); l(22f, 12f); l(14f, 14f); l(12f, 22f); l(10f, 14f); l(2f, 12f); l(10f, 10f); close() }
            Ic.Pack -> { stroke { m(4f, 8f); l(12f, 4f); l(20f, 8f); l(20f, 17f); l(12f, 21f); l(4f, 17f); close() }; stroke { m(4f, 8f); l(12f, 12f); l(20f, 8f) }; line(12f, 12f, 12f, 21f) }
            Ic.Cut -> { ring(6f, 17f, 3f); ring(18f, 17f, 3f); line(8f, 15f, 17f, 4f); line(16f, 15f, 7f, 4f) }
            Ic.Link -> { stroke { m(10f, 14f); c(8f, 12f, 8f, 9.5f, 10f, 7.5f); l(12.5f, 5f); c(14.5f, 3f, 17f, 3f, 19f, 5f); c(21f, 7f, 21f, 9.5f, 19f, 11.5f); l(17.5f, 13f) }; stroke { m(14f, 10f); c(16f, 12f, 16f, 14.5f, 14f, 16.5f); l(11.5f, 19f); c(9.5f, 21f, 7f, 21f, 5f, 19f); c(3f, 17f, 3f, 14.5f, 5f, 12.5f); l(6.5f, 11f) } }
            Ic.Eq -> { line(6f, 4f, 6f, 20f); line(12f, 4f, 12f, 20f); line(18f, 4f, 18f, 20f); dot(6f, 14f, 2.6f); dot(12f, 8f, 2.6f); dot(18f, 16f, 2.6f) }
        }
    }
}
