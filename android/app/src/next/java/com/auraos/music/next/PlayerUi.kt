@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.auraos.music.next

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auraos.music.NativePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/** the spectrum, polled only while something on screen draws it */
@Composable
fun rememberLevels(on: Boolean): State<FloatArray> {
    val st = remember { mutableStateOf(FloatArray(40)) }
    LaunchedEffect(on) {
        if (!on) { NativePlayer.viz(false); st.value = FloatArray(40); return@LaunchedEffect }
        NativePlayer.viz(true)
        try {
            val prev = FloatArray(40)
            while (isActive) {
                val b = NativePlayer.levels()
                if (b != null && b.isNotEmpty()) {
                    val n = minOf(b.size, 256); val out = FloatArray(40)
                    for (i in 0 until 40) {
                        val a = (Math.pow(n.toDouble(), i / 40.0)).toInt().coerceIn(0, n - 1); val z = (Math.pow(n.toDouble(), (i + 1) / 40.0)).toInt().coerceIn(a + 1, n)
                        var m = 0; for (k in a until z) m = max(m, b[k].toInt() and 255)
                        out[i] = max(m / 255f, prev[i] * 0.82f); prev[i] = out[i]
                    }
                    st.value = out
                }
                delay(33)
            }
        } finally { NativePlayer.viz(false) }
    }
    return st
}

@Composable
fun MiniPlayer(s: Song, onOpen: () -> Unit) {
    val st by NativePlayer.state.collectAsState()
    val trig = with(LocalDensity.current) { 60.dp.toPx() }
    var hx by remember { mutableStateOf(0f) }
    var vy by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onOpen() }
        .pointerInput(Unit) { detectHorizontalDragGestures(onDragStart = { hx = 0f }, onDragEnd = { if (hx < -trig) NativePlayer.next() else if (hx > trig) NativePlayer.prev(); hx = 0f }, onDragCancel = { hx = 0f }) { c, a -> c.consume(); hx += a } }
        .pointerInput(Unit) { detectVerticalDragGestures(onDragStart = { vy = 0f }, onDragEnd = { if (vy < -trig) onOpen(); vy = 0f }, onDragCancel = { vy = 0f }) { c, a -> c.consume(); vy += a } }) {
        Row(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp).graphicsLayer { translationX = hx / 3 }, verticalAlignment = Alignment.CenterVertically) {
            Cover(s, 42.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = dim)
            }
            IconBtn(if (st.playing) Ic.Pause else Ic.Play, accentC, 26.dp) { NativePlayer.toggle() }
            IconBtn(Ic.Next, size = 22.dp) { NativePlayer.next() }
        }
        if (st.dur > 0) LinearProgressIndicator(progress = { (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(2.dp))
    }
}

/** two soft clouds in the cover's colours that drift, swell with the bass and flash on hard hits */
@Composable
fun LivingBackground(colors: IntArray, levels: FloatArray, moving: Boolean) {
    val t = if (moving) rememberInfiniteTransition(label = "bg").animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(24000, easing = LinearEasing)), label = "t").value else 0f
    val bass = (levels.take(6).average().toFloat()).coerceIn(0f, 1f)
    val hit = if (bass > 0.85f) (bass - 0.85f) * 3f else 0f
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFF09090C))
        val a = Color(colors[0]); val b = Color(colors[1])
        val r = size.maxDimension * (0.55f + bass * 0.12f)
        val c1 = Offset(size.width * (0.3f + 0.15f * sin(t)), size.height * (0.28f + 0.08f * sin(t * 2)))
        val c2 = Offset(size.width * (0.7f - 0.15f * sin(t + 1f)), size.height * (0.72f + 0.08f * sin(t * 1.5f + 2f)))
        drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.55f + hit), Color.Transparent), c1, r), r, c1)
        drawCircle(Brush.radialGradient(listOf(b.copy(alpha = 0.45f + hit), Color.Transparent), c2, r), r, c2)
        drawRect(Color.Black.copy(alpha = 0.25f))
    }
}

@Composable
fun Visualizer(levels: FloatArray, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = levels.size; val gap = size.width / n * 0.35f; val w = size.width / n - gap
        for (i in 0 until n) {
            val h = max(2f, levels[i] * size.height)
            drawRoundRect(color.copy(alpha = 0.35f + 0.65f * levels[i]), Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}

@Composable
fun FullPlayer(s: Song, onClose: () -> Unit, onQueue: () -> Unit) {
    val ctx = LocalContext.current
    val st by NativePlayer.state.collectAsState()
    var drag by remember { mutableStateOf<Float?>(null) }
    val rep by Prefs.repeat.collectAsState()
    val shuf by Prefs.shuffle.collectAsState()
    val favs by Store.favs.collectAsState()
    val awake by Prefs.keepAwake.collectAsState()
    val motion by Prefs.motion.collectAsState()
    val saving by Prefs.saving.collectAsState()
    val showLyrics by Prefs.npLyrics.collectAsState()
    val coverAccent by Prefs.coverAccent.collectAsState()
    val sleepAt by NativePlayer.sleepAt.collectAsState()
    val sleepEnd by Sleep.endOfSong.collectAsState()
    val ctxLabel by Ctl.context.collectAsState()
    val live = motion && !saving
    val levels by rememberLevels(live)
    val colors = remember(s.cover) { if (s.cover.isEmpty()) intArrayOf(0xFF3A2F5C.toInt(), 0xFF1F3A4A.toInt()) else Images.palette(Store.coverFile(s.cover)) }
    val accent = if (coverAccent) lighten(Color(colors[0])) else accentC
    val trig = with(LocalDensity.current) { 70.dp.toPx() }
    var hx by remember { mutableStateOf(0f) }
    var vy by remember { mutableStateOf(0f) }
    var heart by remember { mutableStateOf(0) }
    val heartA by animateFloatAsState(if (heart % 2 == 1) 1f else 0f, tween(if (heart % 2 == 1) 120 else 600), label = "h", finishedListener = { if (heart % 2 == 1) heart++ })
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent)) {
        Box(Modifier.fillMaxSize().offset { androidx.compose.ui.unit.IntOffset(0, vy.coerceAtLeast(0f).toInt()) }) {
            LivingBackground(colors, levels, live)
            Column(Modifier.fillMaxSize().systemBarsPadding().pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { vy = 0f }, onDragEnd = { if (vy > trig * 2) onClose(); vy = 0f }, onDragCancel = { vy = 0f }) { c, a -> c.consume(); vy += a }
            }.padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.Down, onClick = onClose)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING FROM", fontSize = 10.sp, color = dim, letterSpacing = 1.sp)
                        Text(ctxLabel.ifBlank { s.album.ifBlank { "Your library" } }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box { IconBtn(Ic.More) { menu = true }; SongMenu(s, menu, { menu = false }) }
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (showLyrics) LyricsPane(s, accent)
                    else Box(Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { translationX = hx / 2; rotationZ = hx / 60 }
                        .pointerInput(s.id) { detectTapGestures(onDoubleTap = { if (!Store.isFav(s.id)) Store.toggleFav(s.id); heart = if (heart % 2 == 0) heart + 1 else heart }) }
                        .pointerInput(s.id) { detectHorizontalDragGestures(onDragStart = { hx = 0f }, onDragEnd = { if (hx < -trig) NativePlayer.next() else if (hx > trig) NativePlayer.prev(); hx = 0f }, onDragCancel = { hx = 0f }) { c, a -> c.consume(); hx += a } },
                        contentAlignment = Alignment.Center) {
                        Cover(s, 400.dp, Modifier.fillMaxSize().scale(if (st.playing) 1f else 0.9f), RoundedCornerShape(16.dp))
                        if (heartA > 0.01f) Icon(Ic.HeartFill, Modifier.scale(0.6f + heartA).graphicsLayer { alpha = heartA }, size = 96.dp, tint = Color.White)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist.ifBlank { "Unknown Artist" }, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { onClose(); Nav.open(Page.ArtistP(primaryArtist(s.artist).ifBlank { s.albumArtist })) })
                    }
                    IconBtn(if (s.id in favs) Ic.HeartFill else Ic.Heart, if (s.id in favs) accent else MaterialTheme.colorScheme.onSurface) { Store.toggleFav(s.id) }
                }
                if (live) Visualizer(levels, accent, Modifier.fillMaxWidth().height(28.dp).padding(top = 6.dp))
                val frac = if (st.dur > 0) (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) else 0f
                Slider(value = drag ?: frac, onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { NativePlayer.seek((it * st.dur).toLong()) }; drag = null })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(((drag ?: frac) * st.dur).toLong()), fontSize = 12.sp, color = dim)
                    Text("-" + fmt(st.dur - ((drag ?: frac) * st.dur).toLong()), fontSize = 12.sp, color = dim)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.Shuffle, if (shuf) accent else dim) { Ctl.toggleShuffle(ctx) }
                    IconBtn(Ic.Prev, size = 32.dp) { NativePlayer.prev() }
                    Box(Modifier.size(72.dp).clip(CircleShape).background(accent).clickable { NativePlayer.toggle() }, contentAlignment = Alignment.Center) {
                        Icon(if (st.playing) Ic.Pause else Ic.Play, size = 34.dp, tint = Color(0xFF111014))
                    }
                    IconBtn(Ic.Next, size = 32.dp) { NativePlayer.next() }
                    IconBtn(if (rep == 2) Ic.RepeatOne else Ic.Repeat, if (rep > 0) accent else dim) { Ctl.cycleRepeat() }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.Lyrics, if (showLyrics) accent else dim) { Prefs.setNpLyrics(!showLyrics) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Ic.Timer, if (sleepAt > 0 || sleepEnd) accent else dim) { Dialogs.show { SleepDialog() } }
                        if (sleepAt > 0) SleepLeft(sleepAt, accent) else if (sleepEnd) Text("end of song", fontSize = 11.sp, color = accent)
                    }
                    IconBtn(if (awake) Ic.Sun else Ic.Moon, if (awake) accent else dim) { Prefs.setAwake(!awake); Toasts.show(if (!awake) "The screen stays on while the player is open" else "The screen sleeps as usual") }
                    IconBtn(Ic.Queue, dim, onClick = onQueue)
                }
            }
        }
    }
}

private fun lighten(c: Color): Color { val l = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue; val k = if (l < 0.55f) (0.62f - l) else 0f; return Color(c.red + (1 - c.red) * k * 1.3f, c.green + (1 - c.green) * k * 1.3f, c.blue + (1 - c.blue) * k * 1.3f) }

@Composable
fun SleepLeft(at: Long, c: Color) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (true) { now = System.currentTimeMillis(); delay(10000) } }
    Text("${((at - now) / 60000 + 1).coerceAtLeast(1)} min", fontSize = 11.sp, color = c)
}

object Sleep { val endOfSong = kotlinx.coroutines.flow.MutableStateFlow(false) }

@Composable
fun SleepDialog() {
    AlertDialog(onDismissRequest = { Dialogs.close() }, confirmButton = { TextButton(onClick = { Dialogs.close() }) { Text("Close") } }, title = { Text("Sleep Timer") },
        text = {
            Column {
                listOf(0 to "Off", 5 to "5 minutes", 10 to "10 minutes", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", 90 to "1½ hours").forEach { (m, l) ->
                    TextButton(onClick = { Sleep.endOfSong.value = false; NativePlayer.sleep(m); Dialogs.close() }) { Text(l) }
                }
                TextButton(onClick = { NativePlayer.sleep(0); Sleep.endOfSong.value = true; Dialogs.close() }) { Text("At the end of this song") }
            }
        })
}

/** synced lyrics that follow the song (tap a line to jump there); plain lyrics scroll */
@Composable
fun LyricsPane(s: Song, accent: Color) {
    val v by Lyr.v
    var ly by remember(s.id, v) { mutableStateOf(Online.cachedLyrics(s.id)) }
    var loading by remember(s.id, v) { mutableStateOf(ly == null) }
    LaunchedEffect(s.id, v) { if (ly == null) { ly = withContext(Dispatchers.IO) { try { Online.fetchLyrics(s) } catch (_: Exception) { null } }; loading = false } }
    val l = ly
    var pos by remember { mutableStateOf(0L) }
    val shift = if (Prefs.trimSilence.value) (s.trimStart * 1000).toLong() else 0L
    LaunchedEffect(s.id, l) { while (l != null && l.synced.isNotEmpty()) { pos = NativePlayer.livePos() + shift; delay(120) } }
    val ls = rememberLazyListState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            loading -> Text("Finding the lyrics…", color = dim)
            l == null -> TextButton(onClick = { Dialogs.show { LyricsSearch(s) } }) { Text("Couldn't load lyrics · search") }
            l.instrumental -> Text("♪ Instrumental", color = dim, fontSize = 18.sp)
            l.synced.isNotEmpty() -> {
                val cur = l.synced.indexOfLast { it.ms <= pos }.coerceAtLeast(0)
                LaunchedEffect(cur) { ls.animateScrollToItem((cur - 2).coerceAtLeast(0)) }
                LazyColumn(state = ls, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 40.dp)) {
                    itemsIndexed(l.synced) { i, line ->
                        Text(line.text.ifBlank { "♪" }, fontSize = if (i == cur) 24.sp else 21.sp, fontWeight = FontWeight.Bold,
                            color = if (i == cur) Color.White else if (i < cur) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth().clickable { NativePlayer.seek((line.ms - shift).coerceAtLeast(0)) }.padding(vertical = 8.dp))
                    }
                    item { TextButton(onClick = { Dialogs.show { LyricsSearch(s) } }) { Text("Wrong lyrics? Choose others", color = dim) } }
                }
            }
            l.plain.isNotBlank() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
                Text(l.plain, fontSize = 19.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { Dialogs.show { LyricsSearch(s) } }) { Text("Wrong lyrics? Choose others", color = dim) }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No lyrics found", color = dim)
                TextButton(onClick = { Dialogs.show { LyricsSearch(s) } }) { Text("Search for them") }
            }
        }
    }
}

@Composable
fun QueueScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val st by NativePlayer.state.collectAsState()
    val q by Ctl.queue.collectAsState()
    val ls = rememberLazyListState(initialFirstVisibleItemIndex = (st.index - 1).coerceAtLeast(0))
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                IconBtn(Ic.Back, onClick = onClose)
                Column(Modifier.weight(1f)) {
                    Text("Up Next", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${plural(q.size, "song")} · ${fmt(q.drop(st.index + 1).sumOf { it.durMs })} left", fontSize = 12.sp, color = dim)
                }
                TextButton(onClick = { Ctl.clearUpNext(ctx) }) { Text("Clear") }
            }
            LazyColumn(state = ls) {
                itemsIndexed(q, key = { i, s -> "$i-${s.id}" }) { i, s ->
                    val now = i == st.index
                    Row(Modifier.fillMaxWidth().background(if (now) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).clickable { NativePlayer.jump(i) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(s, 40.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (now) accentC else if (i < st.index) dim else MaterialTheme.colorScheme.onSurface)
                            Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = dim)
                        }
                        if (!now) {
                            IconBtn(Ic.Up, dim, 18.dp) { Ctl.move(ctx, i, (i - 1).coerceAtLeast(0)) }
                            IconBtn(Ic.Down, dim, 18.dp) { Ctl.move(ctx, i, (i + 1).coerceAtMost(q.size - 1)) }
                            IconBtn(Ic.Close, dim, 18.dp) { Ctl.removeAt(ctx, i) }
                        } else Text(fmt(s.durMs), fontSize = 12.sp, color = dim, modifier = Modifier.padding(end = 16.dp))
                    }
                }
            }
        }
    }
}
