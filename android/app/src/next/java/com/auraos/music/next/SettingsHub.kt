@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.auraos.music.next

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ small building blocks

/** the play button's shape (Customize › Player Layout › Button Style) */
fun btnShapeOf(i: Int, size: Dp): Shape = when (i) { 0 -> RoundedCornerShape(size * 0.32f); 2 -> RoundedCornerShape(6.dp); else -> CircleShape }

/** a pill with the choices inside it; the chosen one is raised */
@Composable
fun Segmented(options: List<String>, sel: Int, set: (Int) -> Unit, modifier: Modifier = Modifier) {
    val a = accent
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.surface2).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == sel
            Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(18.dp)).background(if (on) mix(a, 0.30f, C.surface3) else Color.Transparent)
                .clickable { if (!on) { Hap.tick(); set(i) } }, contentAlignment = Alignment.Center) { T(o, 14.sp, if (on) W700 else W550, if (on) C.text else C.sub) }
        }
    }
}

@Composable
fun SChoice(title: String, options: List<String>, sel: Int, set: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        T(title, 15.sp, W650, modifier = Modifier.padding(bottom = 8.dp))
        if (options.size <= 3 && options.all { it.length <= 12 }) Segmented(options, sel, set)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { options.forEachIndexed { i, o -> Chip(o, i == sel) { set(i) } } }
    }
}

@Composable
fun SSlider(title: String, sub: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, step: Float, set: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row { Column(Modifier.weight(1f)) { T(title, 15.sp, W650); if (sub.isNotEmpty()) T(sub, 13.sp, color = C.sub, lines = 2) }; T(value, 15.sp, W700, accent) }
        Slide(v, range, step, set)
    }
}

@Composable internal fun Div() = Box(Modifier.padding(start = 70.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

/** a row of a settings card: tinted icon, title and sub-line, then a value, a chevron or a control */
@Composable
fun SRow(sg: SG, title: String, sub: String = "", value: String? = null, chevron: Boolean = false, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).then(if (onClick != null) Modifier.clickable { Hap.tap(); onClick() } else Modifier).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SIcon(sg, 26.dp)
        Column(Modifier.weight(1f)) { T(title, 15.5.sp, W650); if (sub.isNotEmpty()) T(sub, 13.sp, color = C.sub, lines = 3) }
        if (value != null) T(value, 15.sp, W600, C.sub)
        if (chevron) Icon(Ic.Chevron, Modifier.rotate(-90f), 18.dp, C.sub)
        trailing?.invoke()
    }
}
@Composable fun SSwitch(sg: SG, title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) = SRow(sg, title, sub, onClick = { set(!on) }, trailing = { Switch(on, set) })
@Composable fun SSwitch(sg: SG, title: String, sub: String, p: Cfg.B) = SSwitch(sg, title, sub, p.use()) { p.set(it) }

@Composable
private fun PickSheet(title: String, options: List<String>, sel: Int, set: (Int) -> Unit) {
    val a = accent
    SheetScroll {
        T(title, 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        options.forEachIndexed { i, o ->
            Row(Modifier.fillMaxWidth().clickable { Hap.tick(); set(i); Dialogs.close() }.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                T(o, 15.5.sp, W550, if (i == sel) a else C.text, modifier = Modifier.weight(1f)); if (i == sel) Icon(Ic.Check, tint = a)
            }
        }
    }
}
/** a row showing the current choice; tapping it lists the others */
@Composable
fun SPick(sg: SG, title: String, options: List<String>, sel: Int, sub: String = "", set: (Int) -> Unit) =
    SRow(sg, title, sub = sub, value = options.getOrElse(sel) { "" }, chevron = true, onClick = { Dialogs.sheet { PickSheet(title, options, sel, set) } })

@Composable
private fun SMove(sg: SG, name: String, i: Int, n: Int, shown: Boolean, canHide: Boolean, onMove: (Int) -> Unit, onShow: (Boolean) -> Unit) {
    SRow(sg, name, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (shown && i > 0) IB(Ic.Up, C.sub, 20.dp, 36.dp) { onMove(-1) }
            if (shown && i < n - 1) IB(Ic.Down, C.sub, 20.dp, 36.dp) { onMove(1) }
            if (canHide) { Spacer(Modifier.width(6.dp)); Switch(shown) { onShow(it) } }
        }
    })
}

@Composable private fun Label(t: String) = T(t, 16.sp, W700, modifier = Modifier.padding(start = 26.dp, end = 20.dp, top = 8.dp, bottom = 6.dp))

// ------------------------------------------------------------------ the hub

private fun open(id: String) = Nav.open(Page.SettingsSection(id))

private class Found(val sg: SG, val title: String, val where: String, val keys: String, val go: () -> Unit)
private fun searchIndex(): List<Found> = listOf(
    Found(SG.Library, "Tabs", "Library & Tabs", "tab bar order show hide start open on") { open("library") },
    Found(SG.Library, "Album size and song rows", "Library & Tabs", "grid density compact roomy art duration") { open("library") },
    Found(SG.Library, "Sorting", "Library & Tabs", "sort order title artist") { open("library") },
    Found(SG.Scan, "Scan folders", "Library & Tabs", "add music import folder permission") { Nav.open(Page.Scanner) },
    Found(SG.Music, "Add music", "Library & Tabs", "songs folder pack youtube link") { Dialogs.sheet { AddMusicSheet() } },
    Found(SG.Download, "Concurrent downloads", "Library & Tabs", "download songs at once workers speed") { open("library") },
    Found(SG.Pulse, "Discord Rich Presence", "Appearance", "discord rpc listening status presence customize") { open("appearance") },
    Found(SG.Account, "Discord Rich Presence status", "About", "discord login sign in account presence") { open("about") },
    Found(SG.Layout, "Player layout", "Player", "order move hide spacing compact roomy") { open("player") },
    Found(SG.Cover, "Cover shape and size", "Player", "album art circle rounded sharp swipe") { open("player") },
    Found(SG.Cover, "Player background", "Player", "fog blur glow black") { open("player") },
    Found(SG.Buttons, "Play button style", "Player", "button shape size rounded circle sharp control") { open("player") },
    Found(SG.Viz, "Visualizer", "Visualizer & Lyrics", "bars mirror wave dots equalizer colour strength") { open("vizlyrics") },
    Found(SG.Lyrics, "Lyrics", "Visualizer & Lyrics", "highlight word sync size alignment glow position") { open("vizlyrics") },
    Found(SG.Upload, "Import LRC file", "Visualizer & Lyrics", "lyrics lrc own file upload") { open("vizlyrics") },
    Found(SG.Lyrics, "LRC providers", "Visualizer & Lyrics", "lyrics sources lrclib netease qq kugou") { open("vizlyrics") },
    Found(SG.Buttons, "Buttons", "Appearance", "extra lyrics equalizer queue sleep seek bar media bar") { open("buttons") },
    Found(SG.Colour, "Colours and accent", "Appearance", "color theme follow cover") { open("colors") },
    Found(SG.Shapes, "Shapes and corners", "Appearance", "radius round cards art liquid glass") { open("shapes") },
    Found(SG.Shapes, "Liquid Glass", "Appearance", "glass blur frost bend") { open("shapes") },
    Found(SG.Motion, "Motion effects", "Appearance", "animation background battery saver timing") { open("motion") },
    Found(SG.Motion, "Falling leaves", "Appearance", "thanksgiving autumn fall leaf background") { open("motion") },
    Found(SG.Feel, "Haptics", "Appearance", "touch vibration buzz scroll timer tucked") { open("touch") },
    Found(SG.Download, "Downloads", "Library", "download manager queue speed pause stop retry songs at once") { open("downloads") },
    Found(SG.Playback, "Crossfade", "Playback", "blend beats gapless") { open("playback") },
    Found(SG.Playback, "Equalizer", "Playback", "eq bass treble") { open("playback") },
    Found(SG.Update, "Updates", "About", "check version what's new") { open("about") },
    Found(SG.Pulse, "Diagnostics", "About", "log problem report") { open("about") },
    Found(SG.Star, "Replay the intro", "About", "welcome tour onboarding") { open("about") },
    Found(SG.Reset, "Reset customization", "About", "defaults reset") { open("about") }
)

@Composable
fun SettingsPage() {
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val ls = rememberLazyListState()
    var searching by remember { mutableStateOf(false) }; var q by remember { mutableStateOf("") }
    var openApp by remember { mutableStateOf(false) }
    val a = accent
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Aurora(motion && !saving && !ls.isScrollInProgress)
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            item {
                T("Customize your experience.", 15.sp, color = C.sub, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp))
                if (searching) {
                    SearchBox(q, { q = it }, "Search settings", modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    val words = q.lowercase().split(" ").filter { it.isNotEmpty() }
                    val found = if (words.isEmpty()) emptyList() else searchIndex().filter { f -> val h = "${f.title} ${f.where} ${f.keys}".lowercase(); words.all { it in h } }
                    found.forEach { f ->
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.surface).clickable { Hap.tap(); f.go() }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            SIcon(f.sg, 26.dp); Column(Modifier.weight(1f)) { T(f.title, 15.5.sp, W650); T(f.where, 13.sp, color = C.sub) }
                        }
                    }
                    if (words.isNotEmpty() && found.isEmpty()) T("Nothing matches “$q”", 14.sp, color = C.sub, modifier = Modifier.padding(24.dp))
                } else {
                    HubRow(SG.Folder, "Library & Tabs", "Manage your library and tabs") { open("library") }
                    HubRow(SG.Download, "Downloads", "Queue, speed and controls") { open("downloads") }
                    HubRow(SG.Playback, "Player Layout & Player Look", "Customize the player experience") { open("player") }
                    HubRow(SG.Viz, "Visualizer & Lyrics", "Configure visualizer and lyrics") { open("vizlyrics") }
                    // Appearance: tap it for its page, or the arrow to unfold its sections right here
                    val r = RoundedCornerShape(Cfg.cardRadius.use().dp)
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp).fillMaxWidth().clip(r).background(C.surface).border(1.dp, Color.White.copy(alpha = 0.06f), r)) {
                        Row(Modifier.fillMaxWidth().clickable { Hap.tap(); open("appearance") }.padding(start = 18.dp, top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            SIcon(SG.Look, 30.dp)
                            Column(Modifier.weight(1f)) { T("Appearance", 17.sp, W700); T("Buttons, colors, shapes and more", 13.5.sp, color = C.sub, lines = 2) }
                            Box(Modifier.size(52.dp).clickable { Hap.tick(); openApp = !openApp }, contentAlignment = Alignment.Center) { Icon(Ic.Chevron, Modifier.rotate(if (openApp) 180f else 0f), 20.dp, C.sub) }
                        }
                        AnimatedVisibility(openApp) {
                            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.18f))) {
                                listOf(Triple(SG.Buttons, "Buttons", "buttons"), Triple(SG.Colour, "Colors", "colors"), Triple(SG.Shapes, "Shapes", "shapes"), Triple(SG.Motion, "Motion", "motion"), Triple(SG.Feel, "Touch", "touch")).forEachIndexed { i, (sg, t, id) ->
                                    if (i > 0) Div()
                                    SRow(sg, t, chevron = true, onClick = { open(id) })
                                }
                            }
                        }
                    }
                    HubRow(SG.Music, "Playback", "Crossfade and equalizer") { open("playback") }
                    HubRow(SG.Info, "About", "Updates, accounts and diagnostics") { open("about") }
                }
            }
        }
        Head("Settings", ls.scrolled, back = true) { IB(if (searching) Ic.Close else Ic.Search, tint = if (searching) a else C.text) { searching = !searching; q = "" } }
    }
}

// ------------------------------------------------------------------ section pages

@Composable
fun SettingsSectionPage(id: String) {
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val ls = rememberLazyListState()
    val title = when (id) {
        "library" -> "Library & Tabs"; "player" -> "Player Layout"; "vizlyrics" -> "Visualizer & Lyrics"; "appearance" -> "Appearance"; "buttons" -> "Buttons"
        "colors" -> "Colors"; "shapes" -> "Shapes"; "motion" -> "Motion"; "touch" -> "Touch"; "playback" -> "Playback"; "downloads" -> "Downloads"; else -> "About"
    }
    val blocks: List<@Composable () -> Unit> = when (id) {
        "library" -> libraryBlocks(); "player" -> playerBlocks(); "vizlyrics" -> vizLyricsBlocks(); "appearance" -> appearanceBlocks(); "buttons" -> buttonsBlocks()
        "colors" -> colorBlocks(); "shapes" -> shapeBlocks(); "motion" -> motionBlocks(); "touch" -> touchBlocks(); "playback" -> playbackBlocks(); "downloads" -> downloadBlocks(); else -> aboutBlocks()
    }
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Aurora(motion && !saving && !ls.isScrollInProgress)
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            items(blocks.size) { blocks[it]() }
        }
        Head(title, ls.scrolled, back = true)
    }
}

private fun libraryBlocks(): List<@Composable () -> Unit> = listOf(
    {
        val songs by Store.songs.collectAsState(); val task by Tasks.status.collectAsState(); val mig by Migrate.state.collectAsState()
        val albums = remember(songs) { Store.albums(songs) }
        val noCover = albums.count { it.cover == null }; val noGenre = songs.count { it.genre.isBlank() }
        Group("Your music") {
            SRow(SG.Music, "Add Music", "Songs, music packs or a YouTube link", chevron = true, onClick = { Dialogs.sheet { AddMusicSheet() } }); Div()
            SRow(SG.Scan, "Scan Folders", "Pick folders, choose which songs come in, optionally as playlists", chevron = true, onClick = { Nav.open(Page.Scanner) }); Div()
            SRow(SG.Folder, "Library", task.ifEmpty { listOf(if (noCover > 0) plural(noCover, "missing cover") else "", if (noGenre > 0) "$noGenre without a genre" else "").filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Covers, genres, compression, clean-up · all set" } },
                chevron = true, onClick = { Dialogs.sheet { LibrarySheet() } })
            if (mig.isNotEmpty()) T(mig, 13.sp, color = accent, modifier = Modifier.padding(start = 56.dp, bottom = 12.dp))
        }
    },
    {
        Group("Tabs") {
            val order by TabsCfg.order.collectAsState()
            val all = order + (0..3).filter { it !in order }
            T("Choose which tabs show and put them in the order you like.", 13.sp, color = C.sub, lines = 2, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp))
            val tabSg = listOf(SG.Library, SG.Account, SG.Music, SG.Layout)
            all.forEach { tid ->
                val idx = order.indexOf(tid); val shown = idx >= 0
                SMove(tabSg[tid], TabsCfg.NAMES[tid], idx, order.size, shown, true,
                    onMove = { by -> val l = order.toMutableList(); l[idx] = l[idx + by]; l[idx + by] = tid; TabsCfg.set(l) },
                    onShow = { on -> if (on) TabsCfg.set(order + tid) else if (order.size > 1) TabsCfg.set(order - tid) })
            }
            val st by Cfg.startTab.v.collectAsState()
            SChoice("Open on", listOf("First tab") + TabsCfg.NAMES, st + 1) { Cfg.startTab.set(it - 1) }
            SSwitch(SG.Tabs, "Show tab names", "Off: just the icons", Cfg.tabLabels)
        }
    },
    {
        Group("Library layout") {
            SChoice("Album size", listOf("Small", "Medium", "Large"), Cfg.albumSize.use()) { Cfg.albumSize.set(it) }
            SChoice("Song rows", listOf("Compact", "Normal", "Roomy"), Cfg.rowDensity.use()) { Cfg.rowDensity.set(it) }
            SSwitch(SG.Cover, "Album art in song rows", "Off: song rows show just text", Cfg.rowArt)
            SSwitch(SG.Library, "Album name in song rows", "Under the artist's name", Cfg.rowAlbum)
            SSwitch(SG.Pulse, "Song length in rows", "The time on the right", Cfg.rowDur)
            SRow(SG.Customize, "Sorting", "Every tab has its own sort. Tap the sort button at the top of a tab.")
        }
    },
    { Group("Downloads") { SPick(SG.Download, "Concurrent Downloads", (1..8).map { "$it" }, Cfg.dlWorkers.use() - 1, sub = "How many songs to download at once") { Cfg.dlWorkers.set(it + 1) } } }
)

@Composable
private fun PlayerPreview() {
    val hidden by PlayerLayout.hidden.collectAsState()
    val a = accent; val center = Cfg.titleAlign.use() == 1; val cs = Cfg.ctlScale.use(); val ps = Cfg.playStyle.use(); val shape = Cfg.btnShape.use()
    val cShape = Cfg.coverShape.use(); val th = Cfg.seekThick.use()
    val r = RoundedCornerShape(Cfg.cardRadius.use().dp)
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().clip(r).background(C.surface).border(1.dp, Color.White.copy(alpha = 0.06f), r).padding(16.dp)) {
        if (0 !in hidden || 1 !in hidden) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (0 !in hidden) Box(Modifier.size(64.dp).clip(when (cShape) { 1 -> CircleShape; 2 -> androidx.compose.ui.graphics.RectangleShape; else -> RoundedCornerShape((Cfg.coverRadius.use() * 0.45f).dp) }).background(mix(a, 0.30f, C.surface3)), contentAlignment = Alignment.Center) { Icon(Ic.Note, size = 28.dp, tint = a) }
            if (1 !in hidden) Column(Modifier.weight(1f), horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) { T("Song Title", 18.sp, W800); T("Artist Name", 14.sp, color = C.sub) }
        }
        if (2 !in hidden) {
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(th.dp).clip(RoundedCornerShape(6.dp)).background(C.surface3)) { Box(Modifier.fillMaxWidth(0.4f).fillMaxHeight().background(a)) }
            if (Cfg.showTimes.use()) Row(Modifier.padding(top = 4.dp)) { T("1:24", 12.sp, color = C.sub, modifier = Modifier.weight(1f)); T("3:12", 12.sp, color = C.sub) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Shuffle, tint = C.sub); Icon(Ic.Prev, size = 26.dp)
            val sz = (50 * cs).dp
            val bg = if (ps == 1) a else if (ps == 2) Color.Transparent else Color.White
            Box(Modifier.size(sz).clip(btnShapeOf(shape, sz)).background(bg).then(if (ps == 2) Modifier.border(2.dp, Color.White, btnShapeOf(shape, sz)) else Modifier), contentAlignment = Alignment.Center) {
                Icon(Ic.Pause, size = sz * 0.45f, tint = if (ps == 1) C.onAccent else if (ps == 2) Color.White else Color(0xFF111111))
            }
            Icon(Ic.Next, size = 26.dp); Icon(Ic.Repeat, tint = C.sub)
        }
    }
}

private fun playerBlocks(): List<@Composable () -> Unit> = listOf(
    { PlayerPreview() },
    {
        Label("Layout Style")
        Column(Modifier.padding(horizontal = 14.dp)) { Segmented(listOf("Compact", "Normal", "Roomy"), Cfg.npSpacing.use(), { Cfg.npSpacing.set(it) }) }
    },
    {
        val hidden by PlayerLayout.hidden.collectAsState()
        Group(null) {
            SSwitch(SG.Cover, "Show Album Art", "Display the album cover", 0 !in hidden) { PlayerLayout.show(0, it) }; Div()
            SSwitch(SG.Lyrics, "Show Title and Artist", "The song's name under the cover", 1 !in hidden) { PlayerLayout.show(1, it) }; Div()
            SSwitch(SG.Viz, "Show Seek Bar", "Show the progress bar", 2 !in hidden) { PlayerLayout.show(2, it) }; Div()
            SSwitch(SG.Layout, "Show Extra Buttons", "Lyrics, equalizer, queue and sleep timer", 4 !in hidden) { PlayerLayout.show(4, it) }; Div()
            SSwitch(SG.Info, "Heart button", "Next to the title", Cfg.btnHeart)
        }
    },
    {
        Group("Order of the player") {
            val order by PlayerLayout.order.collectAsState(); val hidden by PlayerLayout.hidden.collectAsState()
            T("Move each part up or down. This is the portrait player.", 13.sp, color = C.sub, lines = 2, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp))
            val sgs = listOf(SG.Cover, SG.Lyrics, SG.Viz, SG.Buttons, SG.Layout)
            order.forEachIndexed { i, bid -> SMove(sgs[bid], PlayerLayout.NAMES[bid], i, order.size, bid !in hidden, false, onMove = { by -> PlayerLayout.move(bid, by) }, onShow = {}) }
        }
    },
    {
        Group("Title and artist") {
            SChoice("Alignment", listOf("Left", "Centre"), Cfg.titleAlign.use()) { Cfg.titleAlign.set(it) }
            val ts = Cfg.titleSize.use()
            SSlider("Size", "", "${ts.toInt()}", ts, 16f..32f, 1f) { Cfg.titleSize.set(it) }
        }
    },
    {
        Group("Cover") {
            val shape = Cfg.coverShape.use()
            SChoice("Cover shape", listOf("Rounded", "Circle", "Sharp"), shape) { Cfg.coverShape.set(it) }
            SChoice("Swiping the cover", listOf("Skips songs", "Changes pages"), Cfg.coverSwipe.use()) { Cfg.coverSwipe.set(it) }
            if (shape == 0) { val r = Cfg.coverRadius.use(); SSlider("Corner roundness", "", "${r.toInt()} dp", r, 0f..48f, 2f) { Cfg.coverRadius.set(it) } }
            val sz = Cfg.coverSize.use()
            SSlider("Cover size", "", "${(sz * 100).toInt()}%", sz, 0.6f..1f, 0.05f) { Cfg.coverSize.set(it) }
        }
    },
    { Group("Background") { SChoice("Behind the player", listOf("Living fog", "Blurred cover", "Accent glow", "Black"), Cfg.bgStyle.use()) { Cfg.bgStyle.set(it) } } },
    {
        Label("Player Controls")
        Group(null) {
            SChoice("Button Style", listOf("Rounded", "Circle", "Sharp"), Cfg.btnShape.use()) { Cfg.btnShape.set(it) }
            val cs = Cfg.ctlScale.use()
            SSlider("Control Size", "", "${(cs * 100).toInt()}%", cs, 0.8f..1.2f, 0.05f) { Cfg.ctlScale.set(it) }
        }
    }
)

@Composable
private fun VizPreview() {
    val n = Cfg.vizBars.use().coerceIn(16, 96)
    val style = Cfg.vizStyle.use().let { if (it == 4) Cfg.vizLast.use() else it }
    val op = Cfg.vizOpacity.use().coerceIn(0.25f, 1f); val colour = Cfg.vizColor.use(); val a = accent
    val hs = remember(n) { FloatArray(n) { i -> val x = i / n.toFloat(); (0.22f + 0.78f * kotlin.math.abs(kotlin.math.sin(x * 9f + 1.3f) * kotlin.math.cos(x * 3.1f))) } }
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        val w = size.width / n; val H = size.height
        fun col(i: Int) = (when (colour) { 2 -> Color.hsv(i * 330f / n, 0.55f, 1f); 3 -> Color.White; else -> a }).copy(alpha = op)
        if (style == 2) {
            val p = Path(); for (i in 0 until n) { val x = i * w + w / 2; val y = H - hs[i] * H; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            drawPath(p, col(0), style = Stroke(4f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        } else for (i in 0 until n) {
            val h = hs[i] * H
            when (style) {
                1 -> drawRoundRect(col(i), Offset(i * w + w * 0.2f, (H - h) / 2), Size(w * 0.6f, h), CornerRadius(w * 0.3f))
                3 -> drawCircle(col(i), w * 0.32f, Offset(i * w + w / 2, H - h))
                else -> drawRoundRect(col(i), Offset(i * w + w * 0.2f, H - h), Size(w * 0.6f, h), CornerRadius(w * 0.3f))
            }
        }
    }
}

@Composable
fun ProvidersSheet() {
    val order by LrcProviders.order.collectAsState()
    val all = order + LrcProviders.ALL.filter { it !in order }
    val about = mapOf("LRCLIB" to "Open community lyrics", "NetEase" to "Very large catalogue, strong on rap and pop", "QQ Music" to "Large catalogue with timed lyrics", "Kugou" to "Extra cover for older and rarer songs")
    SheetScroll {
        T("LRC Providers", 22.sp, W700, modifier = Modifier.padding(start = 24.dp))
        T("Strata asks them in this order and uses the first that has timed lyrics.", 13.sp, color = C.sub, lines = 2, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 8.dp))
        all.forEach { p ->
            val i = order.indexOf(p); val on = i >= 0
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { T(p, 15.5.sp, W650); T(about[p] ?: "", 13.sp, color = C.sub, lines = 2) }
                if (on && i > 0) IB(Ic.Up, C.sub, 20.dp, 36.dp) { val l = order.toMutableList(); l[i] = l[i - 1]; l[i - 1] = p; LrcProviders.set(l) }
                if (on && i < order.size - 1) IB(Ic.Down, C.sub, 20.dp, 36.dp) { val l = order.toMutableList(); l[i] = l[i + 1]; l[i + 1] = p; LrcProviders.set(l) }
                Spacer(Modifier.width(6.dp)); Switch(on) { v -> if (v) LrcProviders.set(order + p) else if (order.size > 1) LrcProviders.set(order - p) }
            }
        }
    }
}

private fun vizLyricsBlocks(): List<@Composable () -> Unit> = listOf(
    {
        Group(null) {
            val on = Cfg.vizStyle.use() != 4
            SSwitch(SG.Viz, "Visualizer", "Bars that move with the music", on) { v -> if (v) Cfg.vizStyle.set(Cfg.vizLast.v.value.coerceIn(0, 3)) else Cfg.vizStyle.set(4) }
            if (on) {
                Div()
                SPick(SG.Customize, "Style", listOf("Bars", "Mirror", "Wave", "Dots"), Cfg.vizStyle.use().coerceIn(0, 3)) { Cfg.vizStyle.set(it); Cfg.vizLast.set(it) }; Div()
                SPick(SG.Colour, "Color", listOf("Accent", "Cover colours", "Rainbow", "White"), Cfg.vizColor.use()) { Cfg.vizColor.set(it) }
                VizPreview()
                val op = Cfg.vizOpacity.use(); val hh = Cfg.vizHeight.use(); val nb = Cfg.vizBars.use()
                SSlider("Strength", "How bold it is", "${(op * 100).toInt()}%", op, 0.1f..1f, 0.05f) { Cfg.vizOpacity.set(it) }
                SSlider("Height", "How much of the screen it fills", "${(hh * 100).toInt()}%", hh, 0.2f..1f, 0.05f) { Cfg.vizHeight.set(it) }
                SSlider("Bars", "How many bars or dots", "$nb", nb.toFloat(), 16f..96f, 4f) { Cfg.vizBars.set(it.toInt()) }
            }
        }
    },
    {
        val pick = rememberLrcPicker()
        val prov by LrcProviders.order.collectAsState()
        Group("Lyrics") {
            SSwitch(SG.Lyrics, "Highlight Current Word", "Highlight the word being sung", Cfg.lyricSweep); Div()
            SSwitch(SG.Update, "Sync with LRC", "Follow the timestamps. Off: plain scrolling text", Cfg.lyricSync); Div()
            SSwitch(SG.Lyrics, "Listen for the words", "A speech model listens to each song to time every word. English only; other songs use the older method. Downloads once (about 76 MB).", Cfg.speechSync); Div()
            run {
                val st = SpeechModel.state.collectAsState().value
                val ready = SpeechModel.ready
                val value = when { st is SpeechModel.St.Downloading -> "Downloading ${(st.fraction * 100).toInt()}%"; ready -> "Ready"; st is SpeechModel.St.Failed -> "Failed. Tap to retry"; else -> "Tap to download" }
                SRow(SG.Upload, "Speech model", if (ready) "On this phone (46 MB). Tap to remove it" else "Needed for word-by-word timing. It also downloads by itself on Wi-Fi", value = value,
                    onClick = { if (ready) SpeechModel.delete() else if (st !is SpeechModel.St.Downloading) ForcedAligner.downloadModelNow() })
            }; Div()
            SRow(SG.Library, "LRC Providers", "${prov.size} of ${LrcProviders.ALL.size} on · choose lyric sources", chevron = true, onClick = { Dialogs.sheet { ProvidersSheet() } }); Div()
            SRow(SG.Upload, "Import LRC File", "Upload your own .lrc. Name it “Artist - Title.lrc” to match songs automatically", onClick = { pick() }, trailing = { Icon(Ic.Up, size = 22.dp, tint = C.sub) })
        }
    },
    {
        Group("Look") {
            val ls2 = Cfg.lyricSize.use()
            SSlider("Text size", "", "${ls2.toInt()}", ls2, 18f..38f, 1f) { Cfg.lyricSize.set(it) }
            SChoice("Alignment", listOf("Left", "Centre"), Cfg.lyricAlign.use()) { Cfg.lyricAlign.set(it) }
            val sh = Cfg.lyricShift.use()
            SSlider("Highlight timing", "Slide right if words light up too late, left if too early", if (sh == 0f) "On time" else "%+d ms".format(sh.toInt()), sh, -600f..600f, 20f) { Cfg.lyricShift.set(it) }
            val bt = Cfg.btDelay.use()
            SSlider("Bluetooth delay", "Bluetooth plays the sound a moment late. While earbuds or a speaker are connected, the words wait this long. Slide right if they light up too early", "${bt.toInt()} ms", bt, 0f..400f, 10f) { Cfg.btDelay.set(it) }
        }
    },
    { Label("Lyrics Position"); Column(Modifier.padding(horizontal = 14.dp)) { Segmented(listOf("Top", "Center", "Bottom"), Cfg.lyricPos.use(), { Cfg.lyricPos.set(it) }) } }
)

private fun appearanceBlocks(): List<@Composable () -> Unit> = listOf(
    {
        Group(null) {
            SRow(SG.Buttons, "Buttons", "Shape, size and style", chevron = true, onClick = { open("buttons") }); Div()
            SRow(SG.Colour, "Colors", "Accent colors and themes", chevron = true, onClick = { open("colors") }); Div()
            SRow(SG.Shapes, "Shapes", "Corners and styling", chevron = true, onClick = { open("shapes") })
        }
    },
    {
        Group(null) {
            SRow(SG.Motion, "Motion", "Animations and transitions", chevron = true, onClick = { open("motion") }); Div()
            SRow(SG.Feel, "Touch", "Haptics and interactions", chevron = true, onClick = { open("touch") })
        }
    },
    {
        val rpc by Prefs.discordRpc.collectAsState()
        val loggedIn by DiscordRpc.loggedIn.collectAsState()
        val status by DiscordRpc.status.collectAsState()
        Group("Downloads") {
            SPick(SG.Download, "Concurrent Downloads", (1..8).map { "$it" }, Cfg.dlWorkers.use() - 1, sub = "How many songs to download at once") { Cfg.dlWorkers.set(it + 1) }
            Div()
            SSwitch(SG.Pulse, "Discord Rich Presence", status, rpc) { Prefs.setDiscordRpc(it) }
            Div()
            SRow(SG.Customize, "Customize Discord Status", "Activity name, format, live timeline and pause behavior", chevron = true, onClick = { Dialogs.sheet { DiscordCustomizeSheet() } })
        }
    }
)

private fun buttonsBlocks(): List<@Composable () -> Unit> = listOf(
    {
        Group("Player buttons") {
            SChoice("Play button", listOf("White", "Accent", "Outline"), Cfg.playStyle.use()) { Cfg.playStyle.set(it) }
            SSwitch(SG.Lyrics, "Lyrics button", "", Cfg.btnLyrics)
            SSwitch(SG.Viz, "Equalizer button", "", Cfg.btnEq)
            SSwitch(SG.Layout, "Queue button", "", Cfg.btnQueue)
            SSwitch(SG.Pulse, "Sleep timer button", "", Cfg.btnSleep)
            SSwitch(SG.Playback, "Speed button", "Play songs faster or slower", Cfg.btnSpeed)
            SSwitch(SG.Playback, "Time under the seek bar", "Elapsed and remaining", Cfg.showTimes)
            val th = Cfg.seekThick.use()
            SSlider("Seek bar thickness", "", "${th.toInt()} dp", th, 2f..12f, 1f) { Cfg.seekThick.set(it) }
        }
    },
    {
        Group("Media bar") {
            SSwitch(SG.Playback, "Progress line", "The thin line along the bottom of the media bar", Cfg.miniProgress)
            SSwitch(SG.Buttons, "Next button", "", Cfg.miniNext)
            SSwitch(SG.Account, "Artist name", "Under the song title", Cfg.miniArtist)
            SChoice("Cover shape", listOf("Rounded", "Circle", "Square"), Cfg.miniArt.use()) { Cfg.miniArt.set(it) }
        }
    }
)

private fun colorBlocks(): List<@Composable () -> Unit> = listOf({
    Group("Accent") {
        val follow by Prefs.coverAccent.collectAsState(); val cur by Prefs.accent.collectAsState()
        SSwitch(SG.Cover, "Follow the cover", "The whole app takes the playing song's colour", follow) { Prefs.setCoverAccent(it) }
        if (!follow) {
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Prefs.ACCENTS.forEach { c ->
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(c)).then(if (c == cur) Modifier.border(3.dp, Color.White, CircleShape) else Modifier).clickable { Hap.tick(); Prefs.setAccent(c) })
                }
            }
        }
    }
})

private fun shapeBlocks(): List<@Composable () -> Unit> = listOf(
    {
        Group("Preview") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Art("", 56.dp, phName = "Strata"); Art("", 56.dp, phName = "Music"); Art("", 56.dp, phName = "Mix")
                Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(Cfg.dockRadius.use().dp)).background(mix(accent, 0.25f, C.surface2)), contentAlignment = Alignment.Center) { T("Media bar", 13.sp, W650) }
            }
        }
    },
    {
        Group("Corners") {
            val cr = Cfg.cardRadius.use()
            SSlider("Cards", "Settings pages and sheets' cards", "${cr.toInt()} dp", cr, 0f..36f, 2f) { Cfg.cardRadius.set(it) }
            val dr = Cfg.dockRadius.use()
            SSlider("Media bar and tabs", "", "${dr.toInt()} dp", dr, 8f..31f, 1f) { Cfg.dockRadius.set(it) }
        }
    },
    {
        Group("Album art in lists") {
            val sh = Cfg.artShape.use()
            SChoice("Shape", listOf("Rounded", "Circle", "Square"), sh) { Cfg.artShape.set(it) }
            if (sh == 0) { val r = Cfg.artRadius.use(); SSlider("Corner roundness", "", "${r.toInt()} dp", r, 0f..24f, 1f) { Cfg.artRadius.set(it) } }
        }
    },
    {
        val liquid by Prefs.liquid.collectAsState()
        Group("Liquid Glass") {
            SSwitch(SG.Shapes, "Liquid Glass", if (liquid) "Real glass on every clear control. It bends the music behind it" else "Off · classic frosted blur", liquid) { Prefs.setLiquid(it) }; Div()
            SRow(SG.Customize, "Glass", "Thickness, bend, colour split, frost, opacity, tilt shine", chevron = true, onClick = { Dialogs.sheet { GlassSheet() } })
        }
    }
)

private fun motionBlocks(): List<@Composable () -> Unit> = listOf(
    {
        val motion by Prefs.motion.collectAsState(); val saver by Prefs.autoSaver.collectAsState(); val shift by Prefs.vizShift.collectAsState()
        Group(null) {
            SSwitch(SG.Motion, "Motion Effects", "Sliding pages, glow, living background and the visualizer", motion) { Prefs.setMotion(it) }; Div()
            SSwitch(SG.Motion, "Falling Leaves", "Brown, yellow and red leaves drift down the background (needs Motion Effects)", Cfg.leaves); Div()
            SSwitch(SG.Pulse, "Save Battery Automatically", "Pause the moving background in Battery Saver or below 20%", saver) { Prefs.setAutoSaver(it) }; Div()
            SRow(SG.Viz, "Visual Timing", "Line the visualizer and background up with the music", value = if (shift == 0f) "In time" else "%+d ms".format(shift.toInt()), chevron = true, onClick = { Dialogs.sheet { TimingSheet() } })
        }
    },
    { MotionStatus() }
)

private fun touchBlocks(): List<@Composable () -> Unit> = listOf({
    val tuck by Prefs.tuck.collectAsState(); val timer by Prefs.scrollTimer.collectAsState()
    Group(null) {
        SSwitch(SG.Feel, "Haptics", "Little buzzes when you tap, drag and release", Cfg.haptics); Div()
        SSwitch(SG.Bar, "Keep Media Bar Tucked", "Sideways: just the album cover, tucked under the tabs", tuck) { Prefs.setTuck(it) }
        SSlider("Scroll Timer", "How long after you stop scrolling the media bar comes back (sideways)", "%.1f s".format(timer), timer, 0.5f..5f, 0.5f) { Prefs.setScrollTimer(it) }
    }
})

private fun playbackBlocks(): List<@Composable () -> Unit> = listOf({
    val fade by Prefs.crossfade.collectAsState(); val match by Prefs.beatMatch.collectAsState(); val eqName by Prefs.eqName.collectAsState()
    Group(null) {
        SSlider("Crossfade", "Blend the end of each song into the next", if (fade < 0.5f) "Off" else "%.0f s".format(fade), fade, 0f..12f, 1f) { Prefs.setFade(it) }
        SSwitch(SG.Playback, "Match Beats", "Line the beats up while crossfading", match) { Prefs.setMatch(it) }; Div()
        SRow(SG.Viz, "Equalizer", value = eqName, chevron = true, onClick = { Dialogs.sheet { EqSheet() } })
    }
})

private fun aboutBlocks(): List<@Composable () -> Unit> = listOf(
    { UpdatesGroup() },
    {
        val ctx = LocalContext.current
        val dLoggedIn by DiscordRpc.loggedIn.collectAsState()
        val dUser by DiscordRpc.username.collectAsState()
        val dStatus by DiscordRpc.status.collectAsState()
        Group("More") {
            SRow(SG.Account, "YouTube Account", "Sign in if YouTube asks you to (for Add from Link)", chevron = true, onClick = {
                Dialogs.show { DTitle("YouTube Account"); DText("Signing in isn't built into the native app yet. Downloads work without it."); DButtons("OK" to { Dialogs.close() }) }
            }); Div()
            SRow(
                SG.Account,
                "Discord Rich Presence",
                dStatus,
                chevron = true,
                onClick = { Dialogs.sheet { DiscordAccountSheet() } }
            ); Div()
            SRow(SG.Pulse, "Diagnostics", "What the app did recently. Share it when something goes wrong", chevron = true, onClick = { Dialogs.sheet { DiagSheet(ctx) } }); Div()
            SRow(SG.Star, "Replay the intro", "The quick tour you get when you first open Strata Music", chevron = true, onClick = { Onboard.start() }); Div()
            SRow(SG.Reset, "Reset customization", "Back to how it looked at the start", onClick = { Dialogs.show { Confirm("Reset customization?", "Tabs, sorting, player and library looks go back to the defaults. Your music is not touched.", "Reset") { Cfg.resetAll(); Dialogs.close() } } })
        }
        T("Strata Music · music packs work on iPhone, Android and the desktop app", 12.sp, color = C.sub, align = TextAlign.Center, lines = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
    }
)

/** says plainly whether the living background and the visualizer can run right now, and what the analyser is hearing */
@Composable
private fun MotionStatus() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState(); val saver by Prefs.autoSaver.collectAsState()
    val st by com.auraos.music.NativePlayer.state.collectAsState()
    var heard by remember { mutableStateOf(-1f) }
    val why = remember(motion, saving) { Tasks.motionWhy(ctx) }
    LaunchedEffect(st.playing) {
        if (!st.playing) { heard = -1f; return@LaunchedEffect }
        com.auraos.music.NativePlayer.viz(true)
        try {
            while (true) {
                val b = com.auraos.music.NativePlayer.levels()
                heard = if (b == null) 0f else (b.maxOfOrNull { it.toInt() and 255 } ?: 0) / 255f
                kotlinx.coroutines.delay(400)
            }
        } finally { com.auraos.music.NativePlayer.viz(VizHold.keep) }
    }
    LaunchedEffect(why) { Diag.log("motion: $why") }
    val running = motion && !saving
    Group("Motion check") {
        Item(if (running) Ic.Check else Ic.Info, if (running) "Living background and visualizer are on" else "Living background and visualizer are paused", why)
        Item(Ic.Eq, "Audio analyser", when { !st.playing -> "Play a song to test it"; heard < 0f -> "Starting…"; heard < 0.02f -> "Running but hearing nothing yet (silence, or the volume is at zero)"; else -> "Hearing the music (level ${(heard * 100).toInt()}%)" })
        if (!running && motion && saver) Item(Ic.Spark, "Keep motion on anyway", "Turns off the automatic battery saver so the background and visualizer keep moving at low battery", onClick = { Prefs.setAutoSaver(false); Prefs.saving.value = false; Toasts.show("Battery saver is off — motion stays on") })
        if (!motion) Item(Ic.Spark, "Switch Motion Effects on", "Sliding pages, glow, living background and the visualizer", onClick = { Prefs.setMotion(true) })
    }
}
