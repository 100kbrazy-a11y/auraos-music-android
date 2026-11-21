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
import com.auraos.music.NativePlayer
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp).fillMaxWidth().clip(RoundedCornerShape(Cfg.cardRadius.use().dp)).background(C.surface).border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(Cfg.cardRadius.use().dp))) {
        if (title != null) T(title.uppercase(), 13.sp, W750, accent, spacing = 0.26.sp, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 6.dp))
        content()
    }
}
/** .item */
@Composable
fun Item(ic: Ic, title: String, sub: String = "", value: String? = null, chevron: Boolean = false, open: Boolean = false, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).then(if (onClick != null) Modifier.clickable { Hap.tap(); onClick() } else Modifier).padding(horizontal = 18.dp, vertical = 14.dp),
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
    fun at(x: Float) = (range.start + (x / w).coerceIn(0f, 1f) * (range.endInclusive - range.start)).let { Math.round(it / step) * step }.also { if (it != v) Hap.tick() }
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
fun LibrarySheet() {
    val comp by Prefs.compress.collectAsState(); val fill by Prefs.autofill.collectAsState()
    val songs by Store.songs.collectAsState()
    val noCover = remember(songs) { Store.albums(songs).count { it.cover == null } }
    val saved = Store.meta.optLong("compressSaved")
    SheetScroll {
        T("Library", 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        Act(Ic.Image, "Find Missing Covers", if (noCover > 0) plural(noCover, "album") + " without a cover" else "Every album already has a cover") { Tasks.findCovers() }
        Item(Ic.Tag, "Fill In Info Automatically", "Genre and year for songs missing them", onClick = { Prefs.setAutofill(!fill) }) { Switch(fill) { Prefs.setAutofill(it) } }
        val trim by Prefs.trimSilence.collectAsState()
        Item(Ic.Cut, "Cut Silence", if (trim) "Songs start at their first sound and end at their last" else "Off · songs play from their very first second", onClick = { Prefs.setTrim(!trim) }) { Switch(trim) { Prefs.setTrim(it) } }
        Item(Ic.Compress, "Compress Music", if (comp) "Everything becomes Opus (near-lossless, a fraction of the size) · only while charging" + (if (saved > 0) " · ${fmtSize(saved)} saved" else "") else "Off · songs are kept as they are", onClick = { Prefs.setCompress(!comp) }) { Switch(comp) { Prefs.setCompress(it) } }
        Act(Ic.Spark, "Bring Over from Strata Music", if (Migrate.done()) "Done · run again to pick up anything new" else "Your music, playlists and settings from the older app") { Store.scope.launch { Toasts.show(Migrate.run(force = true)) } }
        Act(Ic.Trash, "Remove All Music…", danger = true) { Dialogs.show { Confirm("Remove all music?", "Every song, cover and playlist is deleted from this phone. This can't be undone.", "Remove All") { Store.removeAll() } } }
    }
}

@Composable
fun DiagSheet(ctx: Context) {
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

/** Settings › Updates: this version and its name, Check for Updates, the new one's notes + Update, and What's New */
@Composable
fun UpdatesGroup() {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    val cur = remember { Updater.current(ctx) }
    val history = remember { Updater.history(ctx) }
    var showNotes by remember { mutableStateOf(false) }
    Group("Updates") {
        Item(Ic.Spark, "Strata Music ${cur.version}", cur.name.ifBlank { "Native" } + if (cur.date.isNotBlank()) " · ${cur.date}" else "")
        when (val s = st) {
            is Updater.St.Available, is Updater.St.Downloading, is Updater.St.Ready -> {
                val rel = when (s) { is Updater.St.Available -> s.rel; is Updater.St.Downloading -> s.rel; is Updater.St.Ready -> s.rel; else -> null }!!
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(mix(accent, 0.16f, C.surface2)).padding(16.dp)) {
                    T("NEW · ${rel.version}", 12.sp, W750, accent, spacing = 0.5.sp)
                    T(rel.name.ifBlank { "Update" }, 20.sp, W800, lines = 2, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                    for (n in rel.notes) Row(Modifier.padding(vertical = 3.dp)) { T("•", 14.sp, W700, accent, modifier = Modifier.width(16.dp)); T(n, 14.sp, color = C.text.copy(alpha = 0.85f), lines = 4) }
                    Spacer(Modifier.height(12.dp))
                    when (s) {
                        is Updater.St.Downloading -> {
                            T("Downloading… ${(s.frac * 100).toInt()}%", 13.sp, color = C.sub)
                            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.surface3)) { Box(Modifier.fillMaxHeight().fillMaxWidth(s.frac).background(accent)) }
                        }
                        is Updater.St.Ready -> Btn("Install", Ic.Download, modifier = Modifier.fillMaxWidth()) { Updater.install(ctx, s.file) }
                        else -> Btn("Update", Ic.Download, modifier = Modifier.fillMaxWidth()) { scope.launch { Updater.download(ctx, rel) } }
                    }
                }
            }
            else -> {}
        }
        Item(Ic.Download, "Check for Updates", when (val s = st) {
            Updater.St.Checking -> "Checking…"; Updater.St.UpToDate -> "You're up to date"; is Updater.St.Failed -> s.msg
            is Updater.St.Available -> "${s.rel.version} is ready to download"; else -> "Looks for a newer version on GitHub"
        }, onClick = { scope.launch { Updater.check(ctx) } })
        Item(Ic.Lyrics, "What's New", "What each update added", chevron = true, open = showNotes, onClick = { showNotes = !showNotes })
        AnimatedVisibility(showNotes) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
                for (r in history) {
                    T("${r.version} · ${r.name}", 15.sp, W750, if (r.version == cur.version) accent else C.text, lines = 2, modifier = Modifier.padding(top = 10.dp))
                    if (r.date.isNotBlank()) T(r.date, 12.sp, color = C.faint)
                    for (n in r.notes) Row(Modifier.padding(top = 4.dp)) { T("•", 14.sp, W700, C.sub, modifier = Modifier.width(16.dp)); T(n, 14.sp, color = C.sub, lines = 4) }
                }
            }
        }
    }
}


/** Settings › Look & Feel › Glass: every pane of glass in the app changes live while you drag (this sheet is glass too) */
@Composable
fun GlassSheet() {
    val thick by Prefs.glassThick.collectAsState(); val bend by Prefs.glassBend.collectAsState(); val split by Prefs.glassSplit.collectAsState()
    val frost by Prefs.glassFrost.collectAsState(); val opacity by Prefs.glassOpacity.collectAsState(); val shine by Prefs.glassShine.collectAsState()
    val liquid by Prefs.liquid.collectAsState()
    SheetScroll {
        Row(Modifier.padding(start = 24.dp, end = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Eq, tint = accent); Spacer(Modifier.width(12.dp)); T("Glass", 22.sp, W700, modifier = Modifier.weight(1f))
            TextBtn("Reset") { Hap.soft(); Prefs.setGlass(24f, 0.6f, 0.16f, 0.25f, 0.5f, true) }
        }
        if (!liquid) T("Liquid Glass is off: only Frost and Opacity apply (classic frosted blur).", 13.sp, color = C.sub, lines = 2, modifier = Modifier.padding(horizontal = 24.dp))
        @Composable fun row(title: String, sub: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, step: Float, set: (Float) -> Unit) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
                Row { Column(Modifier.weight(1f)) { T(title, 15.sp, W650); T(sub, 13.sp, color = C.sub, lines = 2) }; T(value, 15.sp, W700, accent) }
                Slide(v, range, step, set)
            }
        }
        row("Thickness", "How thick the glass rim is — thicker glass bends more of the edge", "${thick.toInt()} dp", thick, 4f..60f, 1f) { Prefs.setGlass(thick = it) }
        row("Bend", "How strongly the glass refracts what's behind it", "${(bend * 100).toInt()}%", bend, 0f..1f, 0.05f) { Prefs.setGlass(bend = it) }
        row("Colour Split", "How much white light splits into red, green and blue at the edges", "${(split * 100).toInt()}%", split, 0f..0.5f, 0.02f) { Prefs.setGlass(split = it) }
        row("Frost", "How blurred the glass is", "${(frost * 100).toInt()}%", frost, 0f..1f, 0.05f) { Prefs.setGlass(frost = it) }
        row("Opacity", "How dark (and easy to read) the glass is", "${(opacity * 100).toInt()}%", opacity, 0f..1f, 0.05f) { Prefs.setGlass(opacity = it) }
        Row(Modifier.fillMaxWidth().clickable { Hap.tick(); Prefs.setGlass(shine = !shine) }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { T("Tilt Shine", 15.sp, W650); T("The highlight moves as you tilt your phone", 13.sp, color = C.sub) }
            Switch(shine) { Prefs.setGlass(shine = it) }
        }
    }
}


/** Settings › Visual Timing: a live visualizer right under the slider, to line it up with what you hear */
@Composable
fun TimingSheet() {
    val shift by Prefs.vizShift.collectAsState()
    val st by NativePlayer.state.collectAsState()
    val a = accent
    val levels by rememberLevels(st.playing, 48)
    val bass = if (levels.size >= 4) levels.take(4).average().toFloat() else 0f
    SheetScroll {
        Row(Modifier.padding(start = 24.dp, end = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Eq, tint = a); Spacer(Modifier.width(12.dp)); T("Visual Timing", 22.sp, W700, modifier = Modifier.weight(1f))
            TextBtn("Reset") { Hap.soft(); Prefs.setVizShift(0f) }
        }
        T("Play a song and watch the bars and the pulse below. If they hit after you hear the beat, move the slider to Earlier; if they hit before it, move it to Later. It applies to the player's visualizer and background.", 13.sp, color = C.sub, lines = 6, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        // the live view: bars, and a pulse that flashes on every bass hit
        Box(Modifier.padding(horizontal = 24.dp, vertical = 10.dp).fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0B0B0E))) {
            Bars(levels, a.copy(alpha = 0.85f), Modifier.matchParentSize().padding(horizontal = 6.dp, vertical = 6.dp))
            Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(22.dp).graphicsLayer { val k = (0.5f + bass * 1.4f).coerceIn(0.5f, 1.5f); scaleX = k; scaleY = k; alpha = (0.25f + bass * 1.2f).coerceIn(0.25f, 1f) }.clip(androidx.compose.foundation.shape.CircleShape).background(a))
            if (!st.playing) T("Play a song to see it", 14.sp, color = C.sub, modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
            Row {
                T("Earlier", 13.sp, color = C.sub); Spacer(Modifier.weight(1f))
                T(if (shift == 0f) "In time" else if (shift > 0) "${shift.toInt()} ms later" else "${(-shift).toInt()} ms earlier", 15.sp, W700, a)
                Spacer(Modifier.weight(1f)); T("Later", 13.sp, color = C.sub)
            }
            Slide(shift, -500f..500f, 10f) { Prefs.setVizShift(it) }
        }
        Spacer(Modifier.height(8.dp))
    }
}
