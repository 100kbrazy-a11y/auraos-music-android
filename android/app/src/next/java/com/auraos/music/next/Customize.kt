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
    val leaves = B("cfgLeaves", true)          // the Thanksgiving Update: leaves falling behind the app
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
    val npSpacing = I("cfgNpSpacing", 1)       // how much air the player has: compact · normal · roomy
    val btnShape = I("cfgBtnShape", 1)         // the play button: rounded · circle · sharp
    val observe = B("cfgObserve", false)       // the lyrics page shows a big visualizer instead of the lyrics
    val lyricSync = B("cfgLySync", true)       // follow the timestamps; off shows the lyrics as plain text
    val lyricPos = I("cfgLyPos", 1)            // where the current line sits: top · centre · bottom
    val dlWorkers = I("cfgDlWorkers", 3)       // songs downloaded at the same time
    val vizLast = I("cfgVizLast", 0)           // the style to come back to when the visualizer is switched on again

    fun load() { for (l in loaders) l(); TabsCfg.load(); Sorting.load(); PlayerLayout.load(); LrcProviders.load() }
    fun resetAll() { for (r in resets) r(); TabsCfg.set(listOf(0, 1, 2, 3)); Sorting.reset(); PlayerLayout.reset(); LrcProviders.set(LrcProviders.ALL) }
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
        hidden.value = if (h == null) emptySet() else (0 until h.length()).map { h.optInt(it) }.filter { it in 0..2 || it == 4 }.toSet()
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

