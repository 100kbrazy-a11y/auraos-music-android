@file:OptIn(ExperimentalFoundationApi::class)

package com.auraos.music.next

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** .group: a rounded card with an accent caption */
@Composable
fun Group(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.surface)) {
        if (title != null) T(title.uppercase(), 13.sp, W750, accent, spacing = 0.26.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp))
        content()
    }
}
/** .item */
@Composable
fun Item(ic: Ic, title: String, sub: String = "", value: String? = null, chevron: Boolean = false, open: Boolean = false, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(ic, tint = C.sub)
        Column(Modifier.weight(1f)) { T(title, 15.sp, W650); if (sub.isNotEmpty()) T(sub, 13.sp, color = C.sub, lines = 3) }
        if (value != null) T(value, 15.sp, W700, accent)
        if (chevron) { val r by animateFloatAsState(if (open) 180f else 0f, label = "c"); Icon(Ic.Chevron, Modifier.rotate(r), 20.dp, C.sub) }
        trailing?.invoke()
    }
}
@Composable fun SwitchItem(ic: Ic, title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) = Item(ic, title, sub, onClick = { set(!on) }) { Switch(on, set) }

/** a plain accent slider (track + knob), for the crossfade */
@Composable
fun Slide(v: Float, range: ClosedFloatingPointRange<Float>, step: Float, set: (Float) -> Unit) {
    val a = accent
    var w by remember { mutableStateOf(1f) }
    fun at(x: Float) = (range.start + (x / w).coerceIn(0f, 1f) * (range.endInclusive - range.start)).let { Math.round(it / step) * step }
    Box(Modifier.fillMaxWidth().height(36.dp).onSizeChanged { w = it.width.toFloat() }
        .pointerInput(w) { detectTapGestures { set(at(it.x)) } }
        .pointerInput(w) { detectHorizontalDragGestures { c, _ -> c.consume(); set(at(c.position.x)) } }) {
        Canvas(Modifier.fillMaxSize()) {
            val f = (v - range.start) / (range.endInclusive - range.start); val y = size.height / 2
            drawLine(C.surface3, Offset(0f, y), Offset(size.width, y), 6.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(a, Offset(0f, y), Offset(size.width * f, y), 6.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(C.text, 9.dp.toPx(), Offset(size.width * f, y))
        }
    }
}

@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    val songs by Store.songs.collectAsState(); val pls by Store.playlists.collectAsState()
    val fade by Prefs.crossfade.collectAsState(); val match by Prefs.beatMatch.collectAsState(); val trim by Prefs.trimSilence.collectAsState()
    val awake by Prefs.keepAwake.collectAsState(); val eqName by Prefs.eqName.collectAsState()
    val motion by Prefs.motion.collectAsState(); val saver by Prefs.autoSaver.collectAsState(); val saving by Prefs.saving.collectAsState()
    val comp by Prefs.compress.collectAsState(); val fill by Prefs.autofill.collectAsState()
    val task by Tasks.status.collectAsState(); val mig by Migrate.state.collectAsState()
    var bytes by remember { mutableStateOf(0L) }
    var openFade by remember { mutableStateOf(false) }
    LaunchedEffect(songs.size) { bytes = withContext(Dispatchers.IO) { Store.storageBytes() } }
    val albums = remember(songs) { Store.albums(songs) }
    val artists = remember(songs) { Store.artists(songs).size }
    val noCover = albums.count { it.cover == null }; val noGenre = songs.count { it.genre.isBlank() }
    val saved = Store.meta.optLong("compressSaved")
    val ls = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Aurora(motion && !saving)
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            item {
                Group(null) {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        for ((n, l) in listOf(songs.size to "songs", albums.size to "albums", artists to "artists", pls.size to "playlists"))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { T("$n", 22.sp, W800); T(l, 12.sp, color = C.sub) }
                    }
                    T("${fmtSize(bytes)} on this phone", 12.sp, color = C.sub, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp))
                }
                Group("Your Music") {
                    Item(Ic.Plus, "Add Music", "Songs, a folder, music packs or a YouTube link", onClick = { Dialogs.sheet { AddMusicSheet() } })
                    Item(Ic.Folder, "Library", task.ifEmpty { listOf(if (noCover > 0) plural(noCover, "missing cover") else "", if (noGenre > 0) "$noGenre without a genre" else "").filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Covers, genres, compression, clean-up · all set" } },
                        onClick = { Dialogs.sheet { LibrarySheet() } })
                    Item(Ic.Info, "Diagnostics", "What the app did recently — share it when something goes wrong", onClick = { Dialogs.sheet { DiagSheet(ctx) } })
                    if (mig.isNotEmpty()) T(mig, 13.sp, color = accent, modifier = Modifier.padding(start = 58.dp, bottom = 12.dp))
                }
                Group("Playback") {
                    Item(Ic.PlayNext, "Crossfade", value = if (fade < 0.5f) "Off" else "%.0f s".format(fade), chevron = true, open = openFade, onClick = { openFade = !openFade })
                    AnimatedVisibility(openFade) {
                        Column(Modifier.padding(start = 58.dp, end = 18.dp, bottom = 6.dp)) {
                            T("Blend the end of each song into the next", 13.sp, color = C.sub)
                            Slide(fade, 0f..12f, 1f) { Prefs.setFade(it) }
                            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { T("Match Beats", 15.sp, W650); T("Line the beats up while crossfading", 13.sp, color = C.sub) }; Switch(match) { Prefs.setMatch(it) } }
                        }
                    }
                    Item(Ic.Eq, "Equalizer", value = eqName, chevron = true, onClick = { Dialogs.sheet { EqSheet() } })
                    SwitchItem(Ic.Cut, "Cut Silence", if (trim) "Songs start at their first sound and end at their last (also for crossfades and lyrics)" else "Off · songs play from their very first second", trim) { Prefs.setTrim(it) }
                    SwitchItem(Ic.Sun, "Keep the Screen On", "While the full player is open", awake) { Prefs.setAwake(it) }
                }
                Group("Look & Feel") {
                    SwitchItem(Ic.Spark, "Motion Effects", "Sliding pages, glow, living background", motion) { Prefs.setMotion(it) }
                    SwitchItem(Ic.Spark, "Save Battery Automatically", if (saving) "Saving now · the moving background is paused" else "Pause the moving background in Battery Saver or below 20%", saver) { Prefs.setAutoSaver(it) }
                }
                Group("Accounts & Sharing") {
                    Item(Ic.Share, "Share Music", "Any song (as its file), album or playlist (as a music pack) from its ⋮ menu")
                    Item(Ic.Globe, "From YouTube", "YouTube › Share › Strata Music adds a link straight away")
                }
                T("Strata Music · music packs work on iPhone, Android and the desktop app", 12.sp, color = C.sub, align = TextAlign.Center, lines = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            }
        }
        Head("Settings", ls.scrolled, back = true)
    }
}

@Composable
private fun LibrarySheet() {
    val comp by Prefs.compress.collectAsState(); val fill by Prefs.autofill.collectAsState()
    val songs by Store.songs.collectAsState()
    val noCover = remember(songs) { Store.albums(songs).count { it.cover == null } }
    val saved = Store.meta.optLong("compressSaved")
    SheetScroll {
        T("Library", 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        Act(Ic.Image, "Find Missing Covers", if (noCover > 0) plural(noCover, "album") + " without a cover" else "Every album already has a cover") { Tasks.findCovers() }
        Item(Ic.Tag, "Fill In Info Automatically", "Genre and year for songs missing them", onClick = { Prefs.setAutofill(!fill) }) { Switch(fill) { Prefs.setAutofill(it) } }
        Item(Ic.Compress, "Compress Music", if (comp) "Everything becomes Opus (near-lossless, a fraction of the size) · only while charging" + (if (saved > 0) " · ${fmtSize(saved)} saved" else "") else "Off · songs are kept as they are", onClick = { Prefs.setCompress(!comp) }) { Switch(comp) { Prefs.setCompress(it) } }
        Act(Ic.Spark, "Bring Over from Strata Music", if (Migrate.done()) "Done · run again to pick up anything new" else "Your music, playlists and settings from the older app") { Store.scope.launch { Toasts.show(Migrate.run(force = true)) } }
        Act(Ic.Trash, "Remove All Music…", danger = true) { Dialogs.show { Confirm("Remove all music?", "Every song, cover and playlist is deleted from this phone. This can't be undone.", "Remove All") { Store.removeAll() } } }
    }
}

@Composable
private fun DiagSheet(ctx: Context) {
    val lines by Diag.lines.collectAsState()
    Row(Modifier.padding(start = 24.dp, end = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T("Diagnostics", 22.sp, W700, modifier = Modifier.weight(1f))
        TextBtn("Copy") { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Strata log", lines.joinToString("\n"))); Toasts.show("Copied") }
    }
    LazyColumn(Modifier.heightIn(max = 520.dp).padding(horizontal = 20.dp)) {
        if (lines.isEmpty()) item { T("Nothing yet", 15.sp, color = C.sub) }
        items(lines.reversed()) { androidx.compose.material3.Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = C.sub) }
    }
}
