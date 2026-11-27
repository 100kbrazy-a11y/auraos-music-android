@file:OptIn(ExperimentalLayoutApi::class)

package com.auraos.music.next

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraos.music.NativePlayer
import kotlinx.coroutines.delay

/** the nudge a song's lyrics get (seconds, + = the words come later), kept per song */
object LyricTiming {
    val rev = mutableIntStateOf(0)
    fun offset(id: String): Double = Store.settings().optJSONObject("offsets")?.optDouble(id, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
    fun setOffset(id: String, v: Double) {
        val x = Math.round(v.coerceIn(-10.0, 10.0) * 100) / 100.0
        val o = Store.settings().optJSONObject("offsets") ?: org.json.JSONObject().also { Store.settings().put("offsets", it) }
        if (x == 0.0) o.remove(id) else o.put(id, x)
        Store.save(); rev.intValue++
    }
}

/** Lyric timing: a fine offset (steps of 0.05 s), tap along to time the lines yourself, run the word timing again */
@Composable
fun TimingSheet(s: Song, canResync: Boolean) {
    val a = accent
    @Suppress("UNUSED_VARIABLE") val r = LyricTiming.rev.intValue
    val off = LyricTiming.offset(s.id)
    SheetScroll {
        Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Ic.Timer, tint = a); T("Lyric timing", 22.sp, W700, modifier = Modifier.weight(1f)); T(if (off == 0.0) "On time" else "%+.2f s".format(off), 17.sp, W700, a)
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            T("Words light up too early? Slide right. Too late? Slide left. It only changes this song.", 13.sp, color = C.sub, lines = 3, modifier = Modifier.padding(bottom = 4.dp))
            Slide(off.toFloat(), -5f..5f, 0.05f) { LyricTiming.setOffset(s.id, it.toDouble()) }
        }
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((t, d) in listOf("−0.5" to -0.5, "−0.1" to -0.1, "−0.05" to -0.05, "+0.05" to 0.05, "+0.1" to 0.1, "+0.5" to 0.5)) Chip("$t s", false) { LyricTiming.setOffset(s.id, off + d) }
            Chip("Reset", off == 0.0) { LyricTiming.setOffset(s.id, 0.0) }
        }
        Act(Ic.Timer, "Tap along to time the lines", "Play the song and tap as each line starts. The words are timed again from your taps.") { Dialogs.sheet { MarkSheet(s) } }
        if (canResync) Act(Ic.Wand, "Time the words again", "Listens to the song again and times every word from the lines' times") { Hap.tick(); ForcedAligner.resync(s) }
    }
}

/**
 * Tap along (like QuickLRC): the line to sing next is shown big; tap the button the moment it starts. Each tap is the time of that line.
 * When every line has a time it is saved as the song's own lyrics and the words are timed from it.
 */
@Composable
fun MarkSheet(s: Song) {
    val a = accent
    val lines = remember(s.id) {
        val ly = Online.cachedLyrics(s.id)
        (if (ly != null && ly.synced.isNotEmpty()) ly.synced.map { it.text } else (ly?.plain ?: "").lines()).map { it.trim() }.filter { it.isNotEmpty() }
    }
    val marks: SnapshotStateList<Long> = remember(s.id) { mutableStateListOf() }
    val st by NativePlayer.state.collectAsState()
    var last by remember { mutableStateOf(0L) }
    if (lines.size < 2) {
        SheetScroll {
            T("Tap along", 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
            T("This song has no lyrics to time yet. Find them first with “Wrong lyrics?”.", 15.sp, color = C.sub, lines = 3, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
        return
    }
    val n = marks.size
    val done = n >= lines.size
    // what is being heard right now, in the lyrics' own time (a little before the tap: it takes about a sixth of a second to react)
    fun now(): Long = (NativePlayer.livePos() + (if (Prefs.trimSilence.value) (s.trimStart * 1000).toLong() else 0L) - AudioRoute.delayMs() - 170L).coerceAtLeast(0)
    SheetScroll {
        Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("Tap along", 22.sp, W700, modifier = Modifier.weight(1f)); T("$n / ${lines.size}", 15.sp, W700, a)
        }
        Column(Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface2).padding(16.dp)) {
            if (!done) {
                T("NEXT LINE", 11.sp, W750, a, spacing = 0.5.sp)
                T(lines[n], 22.sp, W800, lines = 3, modifier = Modifier.padding(top = 4.dp))
                if (n + 1 < lines.size) T(lines[n + 1], 15.sp, color = C.sub, lines = 2, modifier = Modifier.padding(top = 8.dp))
            } else T("Every line has a time. Save it and the words are timed from your taps.", 17.sp, W700, lines = 3)
        }
        if (!done) {
            Box(Modifier.padding(horizontal = 24.dp, vertical = 14.dp).fillMaxWidth().height(96.dp).clip(RoundedCornerShape(24.dp)).background(a)
                .clickable { val t = now(); if (t >= (marks.lastOrNull() ?: -1L) && System.currentTimeMillis() - last > 120) { last = System.currentTimeMillis(); Hap.press(); marks.add(t) } }, contentAlignment = Alignment.Center) {
                T(if (st.playing) "Tap when it starts" else "Play the song, then tap", 20.sp, W800, Color.Black, align = TextAlign.Center)
            }
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (st.playing) "Pause" else "Play", false) { NativePlayer.toggle() }
            Chip("Back 5 s", false) { NativePlayer.seek((NativePlayer.livePos() - 5000).coerceAtLeast(0)) }
            Chip("Undo", false) { if (marks.isNotEmpty()) marks.removeAt(marks.size - 1) }
            Chip("Start over", false) { marks.clear(); NativePlayer.seek(0) }
        }
        if (done) Box(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
            Btn("Save", Ic.Check, modifier = Modifier.fillMaxWidth()) {
                val text = lines.indices.joinToString("\n") { "[${WordTiming.stamp(marks[it] / 1000.0)}]${lines[it]}" }
                LyricTiming.setOffset(s.id, 0.0)
                Online.saveOwn(s.id, text); Lyr.bump(); Dialogs.close(); Toasts.show("Saved. Timing the words from your taps")
                ForcedAligner.startNow(s)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
