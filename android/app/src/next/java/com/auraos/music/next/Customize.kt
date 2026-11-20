@file:OptIn(ExperimentalLayoutApi::class)

package com.auraos.music.next

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

typealias AE = Map.Entry<String, List<Song>>

/**
 * The customization centre (Settings › Customize). Every option lives in the library's settings next to the others, so it is
 * kept with the library (and in Music Packs' backups) and survives updates. Each [I] / [F] / [B] is one option: read it with
 * `.use()` inside a composable, change it with `.set()`.
 */
object Cfg {
    internal val loaders = ArrayList<() -> Unit>()
    internal val resets = ArrayList<() -> Unit>()

    class I(val k: String, val def: Int) {
        val v = MutableStateFlow(def)
        init { loaders.add { v.value = Store.settings().optInt(k, def) }; resets.add { set(def) } }
        fun set(x: Int) { v.value = x; Store.settings().put(k, x); Store.save() }
        @Composable fun use(): Int = v.collectAsState().value
    }
    class F(val k: String, val def: Float) {
        val v = MutableStateFlow(def)
        init { loaders.add { v.value = Store.settings().optDouble(k, def.toDouble()).toFloat() }; resets.add { set(def) } }
        fun set(x: Float) { v.value = x; Store.settings().put(k, x.toDouble()); Store.save() }
        @Composable fun use(): Float = v.collectAsState().value
    }
    class B(val k: String, val def: Boolean) {
        val v = MutableStateFlow(def)
        init { loaders.add { v.value = Store.settings().optBoolean(k, def) }; resets.add { set(def) } }
        fun set(x: Boolean) { v.value = x; Store.settings().put(k, x); Store.save() }
        @Composable fun use(): Boolean = v.collectAsState().value
    }

    // ---- the full player
    val bgStyle = I("cfgBg", 0)                // living fog · blurred cover · accent glow · black
    val vizStyle = I("cfgVizStyle", 0)         // bars · mirror · wave · dots · off
    val vizColor = I("cfgVizColor", 0)         // accent · cover · rainbow · white
    val vizOpacity = F("cfgVizOp", 0.42f)
    val vizHeight = F("cfgVizH", 0.58f)
    val vizBars = I("cfgVizBars", 40)
    val coverSwipe = I("cfgCoverSwipe", 1)     // swiping the cover: skips songs · changes pages (lyrics / artist)
    val coverShape = I("cfgCoverShape", 0)     // rounded · circle · sharp
    val coverRadius = F("cfgCoverR", 22f)
    val coverSize = F("cfgCoverSz", 1f)
    val lyricSize = F("cfgLySize", 27f)
    val lyricAlign = I("cfgLyAlign", 0)        // left · centre
    val lyricShift = F("cfgLyShift", 0f)       // milliseconds: nudges the word highlight earlier (+) or later (-)
    val lyricSweep = B("cfgLySweep", true)     // words fill in as they are sung
    val lyricGlow = B("cfgLyGlow", true)
    val lyricBlur = B("cfgLyBlur", true)
    val playStyle = I("cfgPlayStyle", 0)       // white · accent · outline
    val btnLyrics = B("cfgBtnLyrics", true)
    val btnEq = B("cfgBtnEq", true)
    val btnQueue = B("cfgBtnQueue", true)
    val btnSleep = B("cfgBtnSleep", false)
    val showTimes = B("cfgTimes", true)
    val seekThick = F("cfgSeek", 5f)
    // ---- the media bar
    val miniProgress = B("cfgMiniProg", true)
    val miniNext = B("cfgMiniNext", true)
    // ---- the library
    val albumSize = I("cfgAlbumSize", 1)       // small · medium · large
    val rowDensity = I("cfgRowDensity", 1)     // compact · normal · roomy
    val rowArt = B("cfgRowArt", true)
    val rowDur = B("cfgRowDur", true)
    val rowAlbum = B("cfgRowAlbum", true)
    val startTab = I("cfgStart", -1)           // -1 = the first tab, else a tab id
    // ---- shapes everywhere
    val cardRadius = F("cfgCardR", 22f)        // the rounded cards in Settings and Customize
    val artShape = I("cfgArtShape", 0)         // album art in lists: rounded · circle · square
    val artRadius = F("cfgArtR", 9f)
    val dockRadius = F("cfgDockR", 26f)        // the media bar and the tab bar
    val miniArt = I("cfgMiniArt", 0)           // the cover in the media bar: rounded · circle · square
    val miniArtist = B("cfgMiniArtist", true)
    val tabLabels = B("cfgTabLabels", true)
    // ---- the full player, laid out your way
    val titleAlign = I("cfgTitleAlign", 0)     // left · centre
    val titleSize = F("cfgTitleSize", 22f)
    val btnHeart = B("cfgBtnHeart", true)
    val ctlScale = F("cfgCtlScale", 1f)        // how big the transport buttons are
    val haptics = B("cfgHaptics", true)

    fun load() { for (l in loaders) l(); TabsCfg.load(); Sorting.load(); PlayerLayout.load() }
    fun resetAll() { for (r in resets) r(); TabsCfg.set(listOf(0, 1, 2, 3)); Sorting.reset(); PlayerLayout.reset() }
}

/** which tabs show, and in which order (ids: 0 Albums · 1 Artists · 2 Songs · 3 Playlists) */
object TabsCfg {
    val NAMES = listOf("Albums", "Artists", "Songs", "Playlists")
    val ICONS = listOf(Ic.Disc, Ic.Person, Ic.Note, Ic.List)
    val order = MutableStateFlow(listOf(0, 1, 2, 3))
    fun load() {
        val a = Store.settings().optJSONArray("cfgTabs")
        val l = if (a == null) listOf(0, 1, 2, 3) else (0 until a.length()).map { a.optInt(it) }.filter { it in 0..3 }.distinct()
        order.value = l.ifEmpty { listOf(0, 1, 2, 3) }
    }
    fun set(l: List<Int>) {
        if (l.isEmpty()) return
        order.value = l; Store.settings().put("cfgTabs", JSONArray(l)); Store.save()
    }
}

/** the full player's blocks, in the order you want them (ids: 0 cover · 1 title · 2 seek bar · 3 buttons · 4 extras) */
object PlayerLayout {
    val NAMES = listOf("Cover", "Title and artist", "Seek bar", "Play buttons", "Extra buttons")
    val ICONS = listOf(Ic.Image, Ic.Note, Ic.Timer, Ic.Play, Ic.Queue)
    val order = MutableStateFlow(listOf(0, 1, 2, 3, 4))
    val hidden = MutableStateFlow(setOf<Int>())
    fun load() {
        val s = Store.settings()
        val a = s.optJSONArray("cfgNpOrder")
        val l = if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it) }.filter { it in 0..4 }.distinct()
        order.value = l + (0..4).filter { it !in l }
        val h = s.optJSONArray("cfgNpHide")
        hidden.value = if (h == null) emptySet() else (0 until h.length()).map { h.optInt(it) }.filter { it in 1..2 || it == 4 }.toSet()
    }
    private fun save() { val s = Store.settings(); s.put("cfgNpOrder", JSONArray(order.value)); s.put("cfgNpHide", JSONArray(hidden.value.toList())); Store.save() }
    fun move(id: Int, by: Int) {
        val l = order.value.toMutableList(); val i = l.indexOf(id); val j = i + by
        if (i < 0 || j !in l.indices) return
        l.removeAt(i); l.add(j, id); order.value = l; Hap.tick(); save()
    }
    fun show(id: Int, on: Boolean) { hidden.value = if (on) hidden.value - id else hidden.value + id; Hap.tick(); save() }
    fun reset() { order.value = listOf(0, 1, 2, 3, 4); hidden.value = emptySet(); save() }
}

/** the shape album art takes in lists, from the shapes you picked */
@Composable
fun artShapeDefault(): androidx.compose.ui.graphics.Shape = when (Cfg.artShape.use()) {
    1 -> CircleShape
    2 -> androidx.compose.ui.graphics.RectangleShape
    else -> RoundedCornerShape(Cfg.artRadius.use().dp)
}

/** per-tab sorting, ascending or descending, like Musicolet's sort menu */
object Sorting {
    val songKeys = listOf("Title", "Artist", "Album", "Duration", "Date Added", "Year", "Genre", "Most Played", "Last Played")
    val albumKeys = listOf("Title", "Artist", "Year", "Date Added", "Duration", "Number of Songs")
    val artistKeys = listOf("Name", "Number of Songs", "Date Added", "Duration")
    val playlistKeys = listOf("Order Created", "Name", "Number of Songs")
    fun keys(tab: Int) = when (tab) { 0 -> albumKeys; 1 -> artistKeys; 2 -> songKeys; else -> playlistKeys }
    val key = List(4) { MutableStateFlow(0) }
    val desc = List(4) { MutableStateFlow(false) }
    /** these start high-to-low when you pick them */
    private fun naturalDesc(tab: Int, i: Int) = when (tab) { 2 -> i == 4 || i == 7 || i == 8; 0 -> i == 3; 1 -> i == 2; else -> false }

    fun load() {
        val s = Store.settings()
        val old = s.optInt("nativeSort", 0)
        for (t in 0..3) {
            if (s.has("sortKey$t")) { key[t].value = s.optInt("sortKey$t"); desc[t].value = s.optBoolean("sortDesc$t") }
            else when (t) {                       // the old single sort setting
                2 -> when (old) { 1 -> key[2].value = 1; 2 -> key[2].value = 2; 3 -> { key[2].value = 4; desc[2].value = true }; 4 -> { key[2].value = 3; desc[2].value = true }; 5 -> { key[2].value = 7; desc[2].value = true } }
                0 -> when (old) { 1 -> key[0].value = 1; 3 -> { key[0].value = 3; desc[0].value = true } }
            }
        }
    }
    private fun save(t: Int) { val s = Store.settings(); s.put("sortKey$t", key[t].value); s.put("sortDesc$t", desc[t].value); Store.save() }
    fun pick(t: Int, i: Int) { if (key[t].value != i) { key[t].value = i; desc[t].value = naturalDesc(t, i); Hap.tick(); save(t) } }
    fun setDesc(t: Int, d: Boolean) { if (desc[t].value != d) { desc[t].value = d; Hap.tick(); save(t) } }
    fun reset() { for (t in 0..3) { key[t].value = 0; desc[t].value = false; save(t) } }
    fun label(t: Int) = keys(t).getOrElse(key[t].value) { keys(t)[0] } + if (desc[t].value) " ↓" else " ↑"

    private fun <T> dir(c: Comparator<T>, d: Boolean): Comparator<T> = if (d) c.reversed() else c

    fun songs(l: List<Song>): List<Song> {
        val c: Comparator<Song> = when (key[2].value) {
            1 -> compareBy<Song>({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.track })
            2 -> compareBy<Song>({ it.album.lowercase() }, { it.disc }, { it.track })
            3 -> compareBy<Song> { it.durMs }
            4 -> compareBy<Song> { it.added }
            5 -> compareBy<Song> { it.year.take(4).toIntOrNull() ?: 0 }
            6 -> compareBy<Song>({ it.genre.lowercase() }, { it.title.lowercase() })
            7 -> compareBy<Song> { it.j.optInt("plays") }
            8 -> compareBy<Song> { it.j.optLong("lastPlayed") }
            else -> compareBy<Song>({ it.title.lowercase() }, { it.artist.lowercase() })
        }
        return l.sortedWith(dir(c, desc[2].value))
    }
    fun albums(l: List<Album>): List<Album> {
        val c: Comparator<Album> = when (key[0].value) {
            1 -> compareBy<Album>({ it.artist.lowercase() }, { it.title.lowercase() })
            2 -> compareBy<Album> { it.year.take(4).toIntOrNull() ?: 0 }
            3 -> compareBy<Album> { it.added }
            4 -> compareBy<Album> { it.durMs }
            5 -> compareBy<Album> { it.songs.size }
            else -> compareBy<Album> { it.title.lowercase() }
        }
        return l.sortedWith(dir(c, desc[0].value))
    }
    /** favourite artists stay on top; the rest follow the chosen order */
    fun artists(l: List<AE>, fav: Set<String>): List<AE> {
        val base: Comparator<AE> = when (key[1].value) {
            1 -> compareBy<AE> { it.value.size }
            2 -> compareBy<AE> { e -> e.value.maxOfOrNull { it.added } ?: 0L }
            3 -> compareBy<AE> { e -> e.value.sumOf { it.durMs } }
            else -> compareBy<AE> { it.key.lowercase() }
        }
        val first: Comparator<AE> = compareBy<AE> { it.key.lowercase() !in fav }
        return l.sortedWith(first.then(dir(base, desc[1].value)))
    }
    fun playlists(names: List<String>, size: (String) -> Int): List<String> = when (key[3].value) {
        1 -> names.sortedWith(dir(compareBy<String> { it.lowercase() }, desc[3].value))
        2 -> names.sortedWith(dir(compareBy<String> { size(it) }, desc[3].value))
        else -> if (desc[3].value) names.reversed() else names
    }
}

/** the sort menu of a tab: what to sort by, and which way */
@Composable
fun SortSheet(tab: Int) {
    val k by Sorting.key[tab].collectAsState(); val d by Sorting.desc[tab].collectAsState()
    val a = accent
    SheetScroll {
        Row(Modifier.padding(start = 24.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Ic.UpDown, tint = a); T("Sort ${TabsCfg.NAMES[tab].lowercase()}", 22.sp, W700, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Ascending", !d, ic = Ic.Up) { Sorting.setDesc(tab, false) }
            Chip("Descending", d, ic = Ic.Down) { Sorting.setDesc(tab, true) }
        }
        Sorting.keys(tab).forEachIndexed { i, name ->
            Row(Modifier.fillMaxWidth().clickable { Sorting.pick(tab, i) }.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                T(name, 15.5.sp, W550, if (i == k) a else C.text, modifier = Modifier.weight(1f))
                if (i == k) Icon(Ic.Check, tint = a)
            }
        }
    }
}

// ------------------------------------------------------------------ the page
@Composable
private fun Choice(title: String, options: List<String>, sel: Int, set: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
        T(title, 15.sp, W650, modifier = Modifier.padding(bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o -> Chip(o, i == sel) { set(i) } }
        }
    }
}

@Composable
private fun Sl(title: String, sub: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, step: Float, set: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
        Row { Column(Modifier.weight(1f)) { T(title, 15.sp, W650); if (sub.isNotEmpty()) T(sub, 13.sp, color = C.sub, lines = 2) }; T(value, 15.sp, W700, accent) }
        Slide(v, range, step, set)
    }
}

@Composable
private fun Tog(ic: Ic, title: String, sub: String, p: Cfg.B) = SwitchItem(ic, title, sub, p.use()) { p.set(it) }

private data class Sec(val id: String, val sg: SG, val title: String, val sub: String)
private val SECTIONS = listOf(
    Sec("tabs", SG.Tabs, "Tabs", "Which tabs, in what order, and the tab bar"),
    Sec("library", SG.Library, "Library", "Album size, song rows, what each row shows"),
    Sec("layout", SG.Layout, "Player layout", "Move and hide the parts of the full player"),
    Sec("look", SG.Cover, "Player look", "Background, cover shape and size"),
    Sec("viz", SG.Viz, "Visualizer", "Style, colour, strength, height"),
    Sec("lyrics", SG.Lyrics, "Lyrics", "Size, alignment, fill-in, glow"),
    Sec("buttons", SG.Buttons, "Buttons", "Play button, extra buttons, seek bar"),
    Sec("bar", SG.Bar, "Media bar", "The little player above the tabs"),
    Sec("shapes", SG.Shapes, "Shapes", "Corners and shapes across the app"),
    Sec("colour", SG.Colour, "Colour", "Follow the cover, or pick an accent"),
    Sec("feel", SG.Feel, "Motion & touch", "Haptics, motion, battery check")
)

/** the Customize centre: every area is a tile that opens its own page */
@Composable
fun CustomizePage() {
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val ls = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Aurora(motion && !saving && !ls.isScrollInProgress)
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            item {
                T("Make it yours. Everything changes live and is kept with your library.", 14.sp, color = C.sub, lines = 3, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SECTIONS.chunked(2).forEach { row ->
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { sc -> HubTile(sc.sg, sc.title, sc.sub, Modifier.weight(1f).fillMaxHeight()) { Nav.open(Page.CustomizeSection(sc.id)) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Group(null) {
                    Item(Ic.History, "Reset all customization", "Back to how it looked at the start", onClick = { Dialogs.show { Confirm("Reset customization?", "Tabs, sorting, player and library looks go back to the defaults. Your music is not touched.", "Reset") { Cfg.resetAll(); Toasts.show("Back to the defaults") } } })
                }
            }
        }
        Head("Customize", ls.scrolled, back = true)
    }
}

@Composable
private fun MoveRow(ic: Ic, name: String, i: Int, n: Int, shown: Boolean, canHide: Boolean, onMove: (Int) -> Unit, onShow: (Boolean) -> Unit) {
    Item(ic, name, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (shown && i > 0) IB(Ic.Up, C.sub, 20.dp, 36.dp) { onMove(-1) }
            if (shown && i < n - 1) IB(Ic.Down, C.sub, 20.dp, 36.dp) { onMove(1) }
            if (canHide) { Spacer(Modifier.width(6.dp)); Switch(shown) { onShow(it) } }
        }
    })
}

/** one area of Customize, on a page of its own */
@Composable
fun CustomizeSectionPage(id: String) {
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val ls = rememberLazyListState()
    val sec = SECTIONS.firstOrNull { it.id == id } ?: SECTIONS[0]
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Aurora(motion && !saving && !ls.isScrollInProgress)
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            item {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SIconTile(sec.sg, 44.dp, 28.dp); T(sec.sub, 14.sp, color = C.sub, lines = 2, modifier = Modifier.weight(1f))
                }
                when (id) {
                    "tabs" -> {
                        Group("Tabs") {
                            val order by TabsCfg.order.collectAsState()
                            val all = order + (0..3).filter { it !in order }
                            T("Choose which tabs show and put them in the order you like.", 13.sp, color = C.sub, lines = 2, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 4.dp))
                            all.forEach { tid ->
                                val idx = order.indexOf(tid); val shown = idx >= 0
                                MoveRow(TabsCfg.ICONS[tid], TabsCfg.NAMES[tid], idx, order.size, shown, true,
                                    onMove = { by -> val l = order.toMutableList(); l[idx] = l[idx + by]; l[idx + by] = tid; TabsCfg.set(l) },
                                    onShow = { on -> if (on) TabsCfg.set(order + tid) else if (order.size > 1) TabsCfg.set(order - tid) })
                            }
                            val st by Cfg.startTab.v.collectAsState()
                            Choice("Open on", listOf("First tab") + TabsCfg.NAMES, st + 1) { Cfg.startTab.set(it - 1) }
                        }
                        Group("Tab bar") { Tog(Ic.Tag, "Show tab names", "Off: just the icons", Cfg.tabLabels) }
                    }
                    "library" -> Group("Library") {
                        Choice("Album size", listOf("Small", "Medium", "Large"), Cfg.albumSize.use()) { Cfg.albumSize.set(it) }
                        Choice("Song rows", listOf("Compact", "Normal", "Roomy"), Cfg.rowDensity.use()) { Cfg.rowDensity.set(it) }
                        Tog(Ic.Image, "Album art in song rows", "Off: song rows show just text", Cfg.rowArt)
                        Tog(Ic.Disc, "Album name in song rows", "Under the artist's name", Cfg.rowAlbum)
                        Tog(Ic.Timer, "Song length in rows", "The time on the right", Cfg.rowDur)
                        Item(Ic.UpDown, "Sorting", "Every tab has its own sort. Tap the sort button at the top of a tab.")
                    }
                    "layout" -> {
                        Group("Order of the player") {
                            val order by PlayerLayout.order.collectAsState(); val hidden by PlayerLayout.hidden.collectAsState()
                            T("Move each part up or down, and hide the ones you don't use. This is the portrait player.", 13.sp, color = C.sub, lines = 3, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 4.dp))
                            order.forEachIndexed { i, bid ->
                                MoveRow(PlayerLayout.ICONS[bid], PlayerLayout.NAMES[bid], i, order.size, bid !in hidden, bid == 1 || bid == 2 || bid == 4,
                                    onMove = { by -> PlayerLayout.move(bid, by) }, onShow = { on -> PlayerLayout.show(bid, on) })
                            }
                        }
                        Group("Title and artist") {
                            Choice("Alignment", listOf("Left", "Centre"), Cfg.titleAlign.use()) { Cfg.titleAlign.set(it) }
                            val ts = Cfg.titleSize.use()
                            Sl("Size", "", "${ts.toInt()}", ts, 16f..32f, 1f) { Cfg.titleSize.set(it) }
                            Tog(Ic.Heart, "Heart button", "Next to the title", Cfg.btnHeart)
                        }
                    }
                    "look" -> {
                        Group("Background") { Choice("Behind the player", listOf("Living fog", "Blurred cover", "Accent glow", "Black"), Cfg.bgStyle.use()) { Cfg.bgStyle.set(it) } }
                        Group("Cover") {
                            val shape = Cfg.coverShape.use()
                            Choice("Cover shape", listOf("Rounded", "Circle", "Sharp"), shape) { Cfg.coverShape.set(it) }
                            Choice("Swiping the cover", listOf("Skips songs", "Changes pages"), Cfg.coverSwipe.use()) { Cfg.coverSwipe.set(it) }
                            if (shape == 0) { val r = Cfg.coverRadius.use(); Sl("Corner roundness", "", "${r.toInt()} dp", r, 0f..48f, 2f) { Cfg.coverRadius.set(it) } }
                            val sz = Cfg.coverSize.use()
                            Sl("Cover size", "", "${(sz * 100).toInt()}%", sz, 0.6f..1f, 0.05f) { Cfg.coverSize.set(it) }
                        }
                    }
                    "viz" -> Group("Visualizer") {
                        Choice("Style", listOf("Bars", "Mirror", "Wave", "Dots", "Off"), Cfg.vizStyle.use()) { Cfg.vizStyle.set(it) }
                        Choice("Colour", listOf("Accent", "Cover colours", "Rainbow", "White"), Cfg.vizColor.use()) { Cfg.vizColor.set(it) }
                        val op = Cfg.vizOpacity.use(); val hh = Cfg.vizHeight.use(); val nb = Cfg.vizBars.use()
                        Sl("Strength", "How bold it is", "${(op * 100).toInt()}%", op, 0.1f..1f, 0.05f) { Cfg.vizOpacity.set(it) }
                        Sl("Height", "How much of the screen it fills", "${(hh * 100).toInt()}%", hh, 0.2f..1f, 0.05f) { Cfg.vizHeight.set(it) }
                        Sl("Bars", "How many bars or dots", "$nb", nb.toFloat(), 16f..96f, 4f) { Cfg.vizBars.set(it.toInt()) }
                    }
                    "lyrics" -> Group("Lyrics") {
                        val ls2 = Cfg.lyricSize.use()
                        Sl("Text size", "", "${ls2.toInt()}", ls2, 18f..38f, 1f) { Cfg.lyricSize.set(it) }
                        Choice("Alignment", listOf("Left", "Centre"), Cfg.lyricAlign.use()) { Cfg.lyricAlign.set(it) }
                        Tog(Ic.Lyrics, "Words fill in as they are sung", "Each word lights up as the singer reaches it", Cfg.lyricSweep)
                        val sh = Cfg.lyricShift.use()
                        Sl("Highlight timing", "Slide right if the words light up too late, left if too early", if (sh == 0f) "On time" else "%+d ms".format(sh.toInt()), sh, -600f..600f, 20f) { Cfg.lyricShift.set(it) }
                        Tog(Ic.Spark, "Glow behind the current line", "A soft light in the song's colour", Cfg.lyricGlow)
                        Tog(Ic.Eq, "Soften the other lines", "Lines you've passed fade and blur", Cfg.lyricBlur)
                    }
                    "buttons" -> Group("Buttons") {
                        Choice("Play button", listOf("White", "Accent", "Outline"), Cfg.playStyle.use()) { Cfg.playStyle.set(it) }
                        val cs = Cfg.ctlScale.use()
                        Sl("Button size", "Play, next, previous, shuffle, repeat", "${(cs * 100).toInt()}%", cs, 0.8f..1.2f, 0.05f) { Cfg.ctlScale.set(it) }
                        Tog(Ic.Lyrics, "Lyrics button", "", Cfg.btnLyrics)
                        Tog(Ic.Eq, "Equalizer button", "", Cfg.btnEq)
                        Tog(Ic.Queue, "Queue button", "", Cfg.btnQueue)
                        Tog(Ic.Timer, "Sleep timer button", "", Cfg.btnSleep)
                        Tog(Ic.Play, "Time under the seek bar", "Elapsed and remaining", Cfg.showTimes)
                        val th = Cfg.seekThick.use()
                        Sl("Seek bar thickness", "", "${th.toInt()} dp", th, 2f..12f, 1f) { Cfg.seekThick.set(it) }
                    }
                    "bar" -> Group("Media bar") {
                        Tog(Ic.Play, "Progress line", "The thin line along the bottom of the media bar", Cfg.miniProgress)
                        Tog(Ic.Next, "Next button", "", Cfg.miniNext)
                        Tog(Ic.Person, "Artist name", "Under the song title", Cfg.miniArtist)
                        Choice("Cover shape", listOf("Rounded", "Circle", "Square"), Cfg.miniArt.use()) { Cfg.miniArt.set(it) }
                        val dr = Cfg.dockRadius.use()
                        Sl("Bar roundness", "The media bar and the tab bar", "${dr.toInt()} dp", dr, 8f..31f, 1f) { Cfg.dockRadius.set(it) }
                    }
                    "shapes" -> {
                        Group("Preview") {
                            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Art("", 56.dp, phName = "Strata"); Art("", 56.dp, phName = "Music"); Art("", 56.dp, phName = "Mix")
                                Box(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(Cfg.dockRadius.use().dp)).background(mix(accent, 0.25f, C.surface2)), contentAlignment = Alignment.Center) { T("Media bar", 13.sp, W650) }
                            }
                        }
                        Group("Corners") {
                            val cr = Cfg.cardRadius.use()
                            Sl("Cards", "Settings and Customize", "${cr.toInt()} dp", cr, 0f..36f, 2f) { Cfg.cardRadius.set(it) }
                            val dr = Cfg.dockRadius.use()
                            Sl("Media bar and tabs", "", "${dr.toInt()} dp", dr, 8f..31f, 1f) { Cfg.dockRadius.set(it) }
                        }
                        Group("Album art in lists") {
                            val sh = Cfg.artShape.use()
                            Choice("Shape", listOf("Rounded", "Circle", "Square"), sh) { Cfg.artShape.set(it) }
                            if (sh == 0) { val r = Cfg.artRadius.use(); Sl("Corner roundness", "", "${r.toInt()} dp", r, 0f..24f, 1f) { Cfg.artRadius.set(it) } }
                            Choice("Cover in the media bar", listOf("Rounded", "Circle", "Square"), Cfg.miniArt.use()) { Cfg.miniArt.set(it) }
                        }
                    }
                    "colour" -> Group("Colour") {
                        val follow by Prefs.coverAccent.collectAsState(); val cur by Prefs.accent.collectAsState()
                        SwitchItem(Ic.Image, "Follow the cover", "The whole app takes the playing song's colour", follow) { Prefs.setCoverAccent(it) }
                        if (!follow) {
                            FlowRow(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Prefs.ACCENTS.forEach { c ->
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(c)).then(if (c == cur) Modifier.border(3.dp, Color.White, CircleShape) else Modifier).clickable { Hap.tick(); Prefs.setAccent(c) })
                                }
                            }
                        }
                    }
                    else -> {
                        Group("Touch") { Tog(Ic.Spark, "Haptics", "Little buzzes when you tap, drag and release", Cfg.haptics) }
                        Group("Motion") {
                            SwitchItem(Ic.Spark, "Motion Effects", "Sliding pages, glow, living background and the visualizer", motion) { Prefs.setMotion(it) }
                        }
                        MotionStatus()
                    }
                }
            }
        }
        Head(sec.title, ls.scrolled, back = true)
    }
}


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
