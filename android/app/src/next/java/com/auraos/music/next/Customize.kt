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
    val coverShape = I("cfgCoverShape", 0)     // rounded · circle · sharp
    val coverRadius = F("cfgCoverR", 22f)
    val coverSize = F("cfgCoverSz", 1f)
    val lyricSize = F("cfgLySize", 27f)
    val lyricAlign = I("cfgLyAlign", 0)        // left · centre
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

    fun load() { for (l in loaders) l(); TabsCfg.load(); Sorting.load() }
    fun resetAll() { for (r in resets) r(); TabsCfg.set(listOf(0, 1, 2, 3)); Sorting.reset() }
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

@Composable
fun CustomizePage() {
    val motion by Prefs.motion.collectAsState(); val saving by Prefs.saving.collectAsState()
    val ls = rememberLazyListState()
    val a = accent
    Box(Modifier.fillMaxSize().background(C.bg)) {
        Aurora(motion && !saving)
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            item {
                T("Make it yours. Everything here changes live and is kept with your library.", 14.sp, color = C.sub, lines = 3, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))

                // ---- tabs
                Group("Tabs") {
                    val order by TabsCfg.order.collectAsState()
                    val all = order + (0..3).filter { it !in order }
                    T("Choose which tabs show and put them in the order you like.", 13.sp, color = C.sub, lines = 2, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 4.dp))
                    all.forEach { id ->
                        val idx = order.indexOf(id); val shown = idx >= 0
                        Item(TabsCfg.ICONS[id], TabsCfg.NAMES[id], trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (shown && idx > 0) IB(Ic.Up, C.sub, 20.dp, 36.dp) { val l = order.toMutableList(); l[idx] = l[idx - 1]; l[idx - 1] = id; TabsCfg.set(l) }
                                if (shown && idx < order.size - 1) IB(Ic.Down, C.sub, 20.dp, 36.dp) { val l = order.toMutableList(); l[idx] = l[idx + 1]; l[idx + 1] = id; TabsCfg.set(l) }
                                Spacer(Modifier.width(6.dp))
                                Switch(shown) { on -> if (on) TabsCfg.set(order + id) else if (order.size > 1) TabsCfg.set(order - id) }
                            }
                        })
                    }
                    val st by Cfg.startTab.v.collectAsState()
                    Choice("Open on", listOf("First tab") + TabsCfg.NAMES, st + 1) { Cfg.startTab.set(it - 1) }
                }

                // ---- library layout
                Group("Library") {
                    Choice("Album size", listOf("Small", "Medium", "Large"), Cfg.albumSize.use()) { Cfg.albumSize.set(it) }
                    Choice("Song rows", listOf("Compact", "Normal", "Roomy"), Cfg.rowDensity.use()) { Cfg.rowDensity.set(it) }
                    Tog(Ic.Image, "Album art in song rows", "Off: song rows show just text", Cfg.rowArt)
                    Tog(Ic.Disc, "Album name in song rows", "Under the artist's name", Cfg.rowAlbum)
                    Tog(Ic.Timer, "Song length in rows", "The time on the right", Cfg.rowDur)
                    Item(Ic.UpDown, "Sorting", "Every tab has its own sort. Tap the sort button at the top of a tab.")
                }

                // ---- player look
                Group("Player · background") {
                    Choice("Background", listOf("Living fog", "Blurred cover", "Accent glow", "Black"), Cfg.bgStyle.use()) { Cfg.bgStyle.set(it) }
                }
                Group("Player · visualizer") {
                    Choice("Style", listOf("Bars", "Mirror", "Wave", "Dots", "Off"), Cfg.vizStyle.use()) { Cfg.vizStyle.set(it) }
                    Choice("Colour", listOf("Accent", "Cover colours", "Rainbow", "White"), Cfg.vizColor.use()) { Cfg.vizColor.set(it) }
                    val op = Cfg.vizOpacity.use(); val hh = Cfg.vizHeight.use(); val nb = Cfg.vizBars.use()
                    Sl("Strength", "How bold it is", "${(op * 100).toInt()}%", op, 0.1f..1f, 0.05f) { Cfg.vizOpacity.set(it) }
                    Sl("Height", "How much of the screen it fills", "${(hh * 100).toInt()}%", hh, 0.2f..1f, 0.05f) { Cfg.vizHeight.set(it) }
                    Sl("Bars", "How many bars or dots", "$nb", nb.toFloat(), 16f..96f, 4f) { Cfg.vizBars.set(it.toInt()) }
                }
                Group("Player · cover") {
                    val shape = Cfg.coverShape.use()
                    Choice("Cover shape", listOf("Rounded", "Circle", "Sharp"), shape) { Cfg.coverShape.set(it) }
                    if (shape == 0) { val r = Cfg.coverRadius.use(); Sl("Corner roundness", "", "${r.toInt()} dp", r, 0f..48f, 2f) { Cfg.coverRadius.set(it) } }
                    val sz = Cfg.coverSize.use()
                    Sl("Cover size", "", "${(sz * 100).toInt()}%", sz, 0.6f..1f, 0.05f) { Cfg.coverSize.set(it) }
                }
                Group("Player · lyrics") {
                    val ls2 = Cfg.lyricSize.use()
                    Sl("Text size", "", "${ls2.toInt()}", ls2, 18f..38f, 1f) { Cfg.lyricSize.set(it) }
                    Choice("Alignment", listOf("Left", "Centre"), Cfg.lyricAlign.use()) { Cfg.lyricAlign.set(it) }
                    Tog(Ic.Spark, "Glow behind the current line", "A soft light in the song's colour", Cfg.lyricGlow)
                    Tog(Ic.Eq, "Soften the other lines", "Lines you've passed fade and blur", Cfg.lyricBlur)
                }
                Group("Player · controls") {
                    Choice("Play button", listOf("White", "Accent", "Outline"), Cfg.playStyle.use()) { Cfg.playStyle.set(it) }
                    Tog(Ic.Lyrics, "Lyrics button", "", Cfg.btnLyrics)
                    Tog(Ic.Eq, "Equalizer button", "", Cfg.btnEq)
                    Tog(Ic.Queue, "Queue button", "", Cfg.btnQueue)
                    Tog(Ic.Timer, "Sleep timer button", "", Cfg.btnSleep)
                    Tog(Ic.Play, "Time under the seek bar", "Elapsed and remaining", Cfg.showTimes)
                    val th = Cfg.seekThick.use()
                    Sl("Seek bar thickness", "", "${th.toInt()} dp", th, 2f..12f, 1f) { Cfg.seekThick.set(it) }
                }
                Group("Media bar") {
                    Tog(Ic.Play, "Progress line", "The thin line along the bottom of the media bar", Cfg.miniProgress)
                    Tog(Ic.Next, "Next button", "", Cfg.miniNext)
                }
                Group("Colour") {
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
                Group(null) {
                    Item(Ic.History, "Reset all customization", "Back to how it looked at the start", onClick = { Dialogs.show { Confirm("Reset customization?", "Tabs, sorting, player and library looks go back to the defaults. Your music is not touched.", "Reset") { Cfg.resetAll(); Toasts.show("Back to the defaults") } } })
                }
            }
        }
        Head("Customize", ls.scrolled, back = true)
    }
}
