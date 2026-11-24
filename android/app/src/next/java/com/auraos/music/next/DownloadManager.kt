package com.auraos.music.next

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Settings › Downloads: what is coming down right now (each song with its own progress, speed and number of connections),
 * what is waiting, what failed and why, and the controls: pause, stop, retry, clear, and how many songs go at once.
 */
internal fun downloadBlocks(): List<@Composable () -> Unit> = listOf(
    { DlSummary() },
    { Group("Speed") { SPick(SG.Download, "Songs at once", (1..8).map { "$it" }, Cfg.dlWorkers.use() - 1, sub = "More goes faster, until YouTube starts refusing. 3 is a good balance.") { Cfg.dlWorkers.set(it + 1) } } },
    { DlList("Downloading now") { !it.done && !it.failed && it.status != "Waiting" && !it.status.startsWith("Waiting") } },
    { DlList("Waiting", limit = 25) { !it.done && !it.failed && it.status.startsWith("Waiting") } },
    { DlList("Needs attention") { it.failed } },
    { DlList("Finished", limit = 15, newestFirst = true) { it.done } },
)

private fun mb(b: Long): String = when {
    b >= 1_000_000_000L -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000L -> "%.1f MB".format(b / 1e6)
    b >= 1_000L -> "%d KB".format(b / 1000)
    else -> "$b B"
}

@Composable
private fun Bar(frac: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.surface3)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(accent))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) { T(value, 20.sp, W750); T(label, 12.5.sp, color = C.sub) }
}

@Composable
private fun DlSummary() {
    val items by Downloader.items.collectAsState(); val paused by Downloader.paused.collectAsState()
    val running = items.filter { !it.done && !it.failed }
    val live = items.filter { !it.failed }
    val done = live.count { it.done }
    val speed = running.sumOf { it.speed }; val streams = running.sumOf { it.conns }
    Group("Downloads") {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Stat("speed", if (paused) "Paused" else if (speed > 0) mb(speed) + "/s" else "–", Modifier.weight(1.3f))
                Stat("streams", "$streams", Modifier.weight(1f))
                Stat("done", if (live.isEmpty()) "0" else "$done of ${live.size}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Bar(if (live.isEmpty()) 0f else done.toFloat() / live.size)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running.isNotEmpty()) {
                    Btn(if (paused) "Resume" else "Pause", tonal = true, modifier = Modifier.weight(1f)) { if (paused) Downloader.resume() else Downloader.pause() }
                    Btn("Stop all", tonal = true, modifier = Modifier.weight(1f)) { Downloader.stop() }
                }
                if (items.any { it.failed }) Btn("Retry failed", tonal = true, modifier = Modifier.weight(1f)) { Downloader.retryFailed() }
                if (items.any { it.done || it.failed }) Btn("Clear", tonal = true, modifier = Modifier.weight(1f)) { Downloader.clearFinished() }
            }
            if (items.isEmpty()) T("Nothing is downloading. Songs you add from YouTube Music show up here.", 13.5.sp, color = C.sub, lines = 3, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        }
    }
}

@Composable
private fun DlList(title: String, limit: Int = 60, newestFirst: Boolean = false, pick: (Dl) -> Boolean) {
    val items by Downloader.items.collectAsState()
    val all = items.filter(pick).let { if (newestFirst) it.asReversed() else it }
    if (all.isEmpty()) return
    Group("$title · ${all.size}") {
        all.take(limit).forEachIndexed { i, d -> if (i > 0) Div(); DlRow(d) }
        if (all.size > limit) T("and ${all.size - limit} more", 13.sp, color = C.sub, modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 12.dp))
    }
}

@Composable
private fun DlRow(d: Dl) {
    val running = !d.done && !d.failed && !d.status.startsWith("Waiting")
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(d.track.thumb.ifBlank { null }, null, Modifier.size(46.dp).clip(RoundedCornerShape(9.dp)).background(C.surface2), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(d.track.title.ifBlank { "Song" }, 15.sp, W650)
            T(listOf(d.track.artist, d.status).filter { it.isNotBlank() }.joinToString(" · "), 12.5.sp, color = if (d.failed) C.danger else C.sub)
            if (running && d.status == "Downloading…") {
                Spacer(Modifier.height(4.dp)); Bar(d.frac)
                T(buildString {
                    append(if (d.size > 0) "${mb(d.bytes)} of ${mb(d.size)}" else mb(d.bytes))
                    if (d.speed > 0) append(" · ${mb(d.speed)}/s")
                    if (d.conns > 1) append(" · ${d.conns} streams")
                }, 11.5.sp, color = C.sub)
            }
        }
        when {
            d.failed -> TextBtn("Retry") { Downloader.retry(d.track.id) }
            !d.done -> TextBtn("Cancel") { Downloader.cancel(d.track.id) }
        }
        if (d.failed) TextBtn("Remove") { Downloader.remove(d.track.id) }
    }
}
