@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.auraos.music.next

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gitems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auraos.music.NativePlayer
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

val LocalBottom = compositionLocalOf { 100.dp }
@Composable fun topPad(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp
val LazyListState.scrolled get() = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 4
val LazyGridState.scrolled get() = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 4

/** the Filter sheet's choices (genre, artist, decade, length, favorites only, added this month) */
object Filt {
    val genre = mutableStateListOf<String>(); val artist = mutableStateListOf<String>(); val decade = mutableStateListOf<String>(); val length = mutableStateListOf<String>()
    val favs = mutableStateOf(false); val recent = mutableStateOf(false)
    fun active() = genre.isNotEmpty() || artist.isNotEmpty() || decade.isNotEmpty() || length.isNotEmpty() || favs.value || recent.value
    fun clear() { genre.clear(); artist.clear(); decade.clear(); length.clear(); favs.value = false; recent.value = false }
    fun dec(s: Song) = s.year.take(4).toIntOrNull()?.let { "${it / 10 * 10}s" } ?: ""
    fun len(s: Song) = (s.durMs / 60000).let { if (it < 3) "Under 3 min" else if (it < 5) "3 – 5 min" else if (it < 8) "5 – 8 min" else "8 min +" }
    fun pass(s: Song, favSet: Set<String>): Boolean {
        if (genre.isNotEmpty() && s.genre !in genre) return false
        if (artist.isNotEmpty() && primaryArtist(s.artist).ifBlank { s.albumArtist } !in artist && s.albumArtist !in artist) return false
        if (decade.isNotEmpty() && dec(s) !in decade) return false
        if (length.isNotEmpty() && len(s) !in length) return false
        if (favs.value && s.id !in favSet) return false
        if (recent.value && System.currentTimeMillis() - s.added > 31L * 86400000) return false
        return true
    }
}

@Composable
fun FilterSheet() {
    val songs by Store.songs.collectAsState()
    val favs by Store.favs.collectAsState()
    fun counts(f: (Song) -> String) = songs.map(f).filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(16)
    val n = songs.count { Filt.pass(it, favs.keys) }
    SheetScroll {
        Row(Modifier.padding(start = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Ic.Filter, tint = accent); T("Filter", 22.sp, W700, modifier = Modifier.weight(1f)); if (Filt.active()) Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { Filt.clear() }.padding(10.dp)) { T("Clear", 15.sp, W700, accent) } }
        @Composable fun group(title: String, list: List<Map.Entry<String, Int>>, sel: MutableList<String>) {
            if (list.isEmpty()) return
            T(title.uppercase(), 12.sp, W750, C.sub, spacing = 0.8.sp, modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 6.dp))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, c) in list) Chip(k, k in sel, c) { if (k in sel) sel.remove(k) else sel.add(k) }
            }
        }
        group("Genre", counts { it.genre }, Filt.genre)
        group("Artist", counts { primaryArtist(it.artist).ifBlank { it.albumArtist } }, Filt.artist)
        group("Decade", counts { Filt.dec(it) }.sortedByDescending { it.key }, Filt.decade)
        group("Length", counts { Filt.len(it) }, Filt.length)
        T("OTHER", 12.sp, W750, C.sub, spacing = 0.8.sp, modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 6.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Favorites only", Filt.favs.value, ic = Ic.Heart) { Filt.favs.value = !Filt.favs.value }
            Chip("Added this month", Filt.recent.value) { Filt.recent.value = !Filt.recent.value }
        }
        Btn("Show ${plural(n, "song")}", modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp)) { Dialogs.close() }
    }
}

@Composable
fun AddMusicEmpty() = Empty(Ic.Note, "Add your music", "Add songs from your phone, a music pack someone shared with you, or a YouTube link.", "Add Music" to { Dialogs.sheet { AddMusicSheet() } })

@Composable
fun AlbumsScreen() {
    val songs by Store.songs.collectAsState()
    val favs by Store.favs.collectAsState()
    val sortK by Sorting.key[0].collectAsState(); val sortD by Sorting.desc[0].collectAsState(); val size = Cfg.albumSize.use()
    val gs = rememberLazyGridState()
    val albums = remember(songs, sortK, sortD, favs, Filt.active(), Filt.genre.toList(), Filt.artist.toList(), Filt.decade.toList(), Filt.length.toList(), Filt.favs.value, Filt.recent.value) {
        Sorting.albums(Store.albums(songs.filter { Filt.pass(it, favs.keys) }))
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(listOf(110, 150, 190)[size.coerceIn(0, 2)].dp), state = gs, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = topPad() + 6.dp, bottom = LocalBottom.current),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (songs.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { AddMusicEmpty() }
            gitems(albums, key = { it.key }) { AlbumCard(it) }
        }
        val sc = gs.scrolled; LaunchedEffect(sc) { TabScroll.v[0] = sc }
    }
}

@Composable
fun ArtistsScreen() {
    val songs by Store.songs.collectAsState()
    val favA by Store.favArtists.collectAsState()
    val favs by Store.favs.collectAsState()
    Store.ver.collectAsState().value
    val sortK by Sorting.key[1].collectAsState(); val sortD by Sorting.desc[1].collectAsState()
    val ls = rememberLazyListState()
    val artists = remember(songs, favA, favs, Filt.active(), sortK, sortD) { Sorting.artists(Store.artists(songs.filter { Filt.pass(it, favs.keys) }).entries.toList(), favA) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            if (songs.isEmpty()) item { AddMusicEmpty() }
            items(artists, key = { it.key }) { (name, l) ->
                Row(Modifier.fillMaxWidth().press(0.98f, onLong = { Dialogs.sheet { ArtistSheet(name) } }, onDouble = { Store.toggleFavArtist(name); likeToast(name.lowercase() in Store.favArtists.value, "favorite artists") }) { Nav.open(Page.ArtistP(name)) }.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Art(Store.artistInfo(name)?.optString("image")?.takeIf { Store.coverFile(it).exists() } ?: l.firstOrNull { it.cover.isNotEmpty() }?.cover ?: "", 56.dp, shape = CircleShape, ph = Ic.Person, phName = name)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { T(name, 15.sp, W700); if (name.lowercase() in favA) Icon(Ic.HeartFill, size = 14.dp, tint = accent) }
                        T(plural(l.size, "song"), 13.sp, color = C.sub)
                    }
                }
            }
        }
        val sc = ls.scrolled; LaunchedEffect(sc) { TabScroll.v[1] = sc }
    }
}

@Composable
fun SongsScreen() {
    val songs by Store.songs.collectAsState()
    val favs by Store.favs.collectAsState()
    val sortK by Sorting.key[2].collectAsState(); val sortD by Sorting.desc[2].collectAsState()
    val ls = rememberLazyListState()
    val list = remember(songs, sortK, sortD, favs, Filt.active(), Filt.genre.toList(), Filt.artist.toList(), Filt.decade.toList(), Filt.length.toList(), Filt.favs.value, Filt.recent.value) { Sorting.songs(songs.filter { Filt.pass(it, favs.keys) }) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            if (songs.isEmpty()) item { AddMusicEmpty() }
            else item { Plays(list, "Songs", "${plural(list.size, "song")} · ${fmtLong(list.sumOf { it.durMs })} · ${Sorting.label(2)}" + if (Filt.active()) " · filtered" else "") }
            items(list, key = { it.id }) { s -> SongRow(s, list, "Songs") }
        }
        val sc = ls.scrolled; LaunchedEffect(sc) { TabScroll.v[2] = sc }
    }
}

@Composable
fun PlaylistsScreen() {
    val pls by Store.playlists.collectAsState()
    val favs by Store.favs.collectAsState()
    val songs by Store.songs.collectAsState()
    val by = remember(songs) { songs.associateBy { it.id } }
    val sortK by Sorting.key[3].collectAsState(); val sortD by Sorting.desc[3].collectAsState()
    val plNames = remember(pls, sortK, sortD) { Sorting.playlists(pls.keys.toList()) { n -> pls[n]?.size ?: 0 } }
    val ls = rememberLazyListState()
    val a = accent
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            item {
                PlRow({ Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(mix(a, 0.18f, C.surface2)), contentAlignment = Alignment.Center) { Icon(Ic.Plus, tint = a) } }, "New Playlist", null) {
                    Dialogs.show { Prompt("New Playlist", "", "Create") { n -> Store.newPlaylist(n) } }
                }
                PlRow({ Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(a, mix(a, 0.6f, Color(0xFF7A1A3A))))), contentAlignment = Alignment.Center) { Icon(Ic.HeartFill, tint = C.onAccent) } },
                    "Favorite Songs", plural(favs.keys.count { it in by }, "song")) { Nav.open(Page.Favorites) }
            }
            items(plNames, key = { it }) { name ->
                val ids = pls[name] ?: emptyList()
                PlRow({ Art(ids.firstNotNullOfOrNull { by[it]?.cover?.takeIf { c -> c.isNotEmpty() } } ?: "", 56.dp, shape = RoundedCornerShape(10.dp), ph = Ic.List, phName = name) },
                    name, plural(ids.count { it in by }, "song")) { Nav.open(Page.PlaylistP(name)) }
            }
        }
        val sc = ls.scrolled; LaunchedEffect(sc) { TabScroll.v[3] = sc }
    }
}
@Composable
private fun PlRow(art: @Composable () -> Unit, title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().press(0.98f) { onClick() }.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        art(); Column { T(title, 15.sp, W700); if (sub != null) T(sub, 13.sp, color = C.sub) }
    }
}

// ------------------------------------------------------------------ pages
/** an album / artist page takes its own cover's colour, and a soft wash of it at the top */
@Composable
fun Tinted(cover: String, content: @Composable () -> Unit) {
    val c = remember(cover) { Accents.of(if (cover.isEmpty()) null else Store.coverFile(cover)) } ?: accent
    CompositionLocalProvider(LocalAccent provides c) {
        Box(Modifier.fillMaxSize().background(C.bg)) {
            Box(Modifier.fillMaxWidth().height(460.dp).background(Brush.verticalGradient(listOf(mix(c, 0.32f, C.bg), C.bg))))
            content()
        }
    }
}

@Composable
fun Hero(cover: String, phName: String, title: String, by: String?, info: String, round: Boolean = false, onBy: () -> Unit = {}, acts: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth(0.64f).widthIn(max = 280.dp).shadow(30.dp, if (round) CircleShape else RoundedCornerShape(20.dp))) {
            Art(cover, null, Modifier.fillMaxWidth(), if (round) CircleShape else RoundedCornerShape(20.dp), if (round) Ic.Person else Ic.Disc, phName)
        }
        T(title, 24.sp, W800, lines = 2, align = TextAlign.Center, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
        if (by != null) T(by, 15.sp, W650, accent, modifier = Modifier.clickable { onBy() })
        T(info, 13.sp, color = C.sub, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = acts)
    }
}

@Composable
fun AlbumPage(key: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by Store.songs.collectAsState()
    val a = remember(songs, key) { Store.albums(songs.filter { it.albumKey == key }).firstOrNull() }
    if (a == null) { LaunchedEffect(Unit) { Nav.back() }; return }
    val ls = rememberLazyListState()
    val multiDisc = a.songs.map { it.disc }.distinct().size > 1
    Tinted(a.cover?.cover ?: "") {
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            item {
                Hero(a.cover?.cover ?: "", a.title, a.title, a.artist, listOf(a.year, plural(a.songs.size, "song"), fmtLong(a.durMs)).filter { it.isNotBlank() }.joinToString(" · "), onBy = { Nav.open(Page.ArtistP(a.artist)) }) {
                    Btn("Play", Ic.Play, modifier = Modifier.widthIn(min = 130.dp)) { Ctl.play(ctx, a.songs, 0, a.title, false) }
                    Btn("Shuffle", Ic.Shuffle, tonal = true, modifier = Modifier.widthIn(min = 130.dp)) { Ctl.shuffleAll(ctx, a.songs, a.title) }
                }
            }
            var lastDisc = -1
            a.songs.forEach { s ->
                if (multiDisc && s.disc != lastDisc) { val d = s.disc; item(key = "disc$d") { T("Disc ${if (d > 0) d else 1}", 13.sp, W700, C.sub, modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp)) } }
                lastDisc = s.disc
                item(key = s.id) { SongRow(s, a.songs, a.title, number = s.track) }
            }
            item { a.songs.firstNotNullOfOrNull { it.j.optString("copyright").ifBlank { null } }?.let { T(it, 12.sp, color = C.faint, lines = 3, modifier = Modifier.padding(18.dp)) } }
        }
        Head(if (ls.scrolled) a.title else "", ls.scrolled, back = true) {
            IB(Ic.More, modifier = Modifier.padding(4.dp).clip(CircleShape).background(mix(accent, 0.28f, Color(0x661E1E24)))) {
                Dialogs.sheet { AlbumSheet(a) { Nav.back() } }
            }
        }
    }
}

@Composable
fun ArtistPage(name: String) {
    val ctx = LocalContext.current
    val songs by Store.songs.collectAsState()
    val favA by Store.favArtists.collectAsState()
    Store.ver.collectAsState().value
    val mine = remember(songs, name) { Store.artists(songs)[name] ?: emptyList() }
    val own = remember(mine) { Store.albums(mine.filter { it.albumArtist.equals(name, true) }).sortedByDescending { it.year.ifBlank { "0" } } }
    val featured = remember(mine) { Store.albums(mine.filter { !it.albumArtist.equals(name, true) }) }
    val info = Store.artistInfo(name)
    val photo = info?.optString("image")?.takeIf { Store.coverFile(it).exists() } ?: mine.firstOrNull { it.cover.isNotEmpty() }?.cover ?: ""
    val top = remember(mine) { mine.sortedWith(compareByDescending<Song> { it.j.optInt("plays") }.thenByDescending { Store.isFav(it.id) }.thenByDescending { it.added }) }
    var all by remember { mutableStateOf(false) }
    val ls = rememberLazyListState()
    val fav = name.lowercase() in favA
    Tinted(photo) {
        LazyColumn(state = ls, contentPadding = PaddingValues(bottom = LocalBottom.current)) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(1.05f).clipToBounds()) {
                    Art(photo, null, Modifier.fillMaxSize(), RoundedCornerShape(0.dp), Ic.Person, name, square = false)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.35f to Color.Transparent, 1f to C.bg)))
                    T(name, 36.sp, W800, lines = 2, spacing = (-0.6).sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 8.dp))
                }
                T("${plural(mine.size, "song")} · ${plural(own.size, "album")}", 13.sp, color = C.sub, modifier = Modifier.padding(start = 18.dp, bottom = 14.dp))
                Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Shuffle", Ic.Shuffle, modifier = Modifier.weight(1f)) { Ctl.shuffleAll(ctx, mine, name) }
                    Btn("Radio", Ic.Spark, tonal = true, modifier = Modifier.weight(1f)) { Radio.artist(ctx, name, mine) }
                }
                Shelf("Songs") {}
            }
            items(if (all) top else top.take(5), key = { it.id }) { s -> SongRow(s, top, name) }
            item {
                if (top.size > 5) Box(Modifier.padding(start = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { all = !all }.padding(10.dp)) { T(if (all) "Show fewer" else "See all ${top.size}", 15.sp, W700, accent) }
                AlbumShelf("Albums", own.filter { it.songs.size > 3 })
                AlbumShelf("Singles & EPs", own.filter { it.songs.size <= 3 })
                AlbumShelf("Featured on", featured)
                Shelf("More from $name") {
                    Box(Modifier.padding(horizontal = 18.dp)) { Btn("Find on YouTube Music", Ic.Globe, tonal = true, modifier = Modifier.fillMaxWidth()) { Yt.findArtist(name, info?.optString("channel") ?: ""); Nav.open(Page.Search); Yt.mode.value = true } }
                }
                val about = info?.optString("about") ?: ""
                if (about.isNotBlank()) { var more by remember { mutableStateOf(false) }; Shelf("About") { T(about, 15.sp, color = C.sub, lines = if (more) 400 else 5, lh = 22.sp, modifier = Modifier.clickable { more = !more }.padding(horizontal = 18.dp)) } }
            }
        }
        Head(if (ls.scrolled) name else "", ls.scrolled, back = true) { IB(if (fav) Ic.HeartFill else Ic.Heart, on = fav) { Store.toggleFavArtist(name) } }
    }
}

@Composable
fun PlaylistPage(name: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pls by Store.playlists.collectAsState()
    val songs by Store.songs.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val ids = pls[name]
    if (ids == null) { LaunchedEffect(Unit) { Nav.back() }; return }
    val by = remember(songs) { songs.associateBy { it.id } }
    val list = ids.mapNotNull { by[it] }
    val ls = rememberLazyListState()
    val cover = list.firstOrNull { it.cover.isNotEmpty() }?.cover ?: ""
    Tinted(cover) {
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            item {
                Hero(cover, name, name, null, "${plural(list.size, "song")} · ${fmtLong(list.sumOf { it.durMs })}") {
                    Btn("Play", Ic.Play, modifier = Modifier.widthIn(min = 130.dp), enabled = list.isNotEmpty()) { Ctl.play(ctx, list, 0, name, false) }
                    Btn("Shuffle", Ic.Shuffle, tonal = true, modifier = Modifier.widthIn(min = 130.dp), enabled = list.isNotEmpty()) { Ctl.shuffleAll(ctx, list, name) }
                }
                if (list.isEmpty()) T("Empty. Use ⋮ › Add to Playlist on any song.", 15.sp, color = C.sub, align = TextAlign.Center, lines = 2, modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
            if (editing) itemsIndexed(list, key = { i, s -> "$i${s.id}" }) { i, s ->
                Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Art(s.cover, 48.dp, phName = s.album)
                    Column(Modifier.weight(1f)) { T(s.title, 15.sp, W650); T(s.artist, 13.sp, color = C.sub) }
                    IB(Ic.Up, if (i > 0) C.text else C.faint, 20.dp) { if (i > 0) Store.setPlaylist(name, list.map { it.id }.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) }
                    IB(Ic.Down, if (i < list.size - 1) C.text else C.faint, 20.dp) { if (i < list.size - 1) Store.setPlaylist(name, list.map { it.id }.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) }
                    IB(Ic.Close, C.sub, 20.dp) { Store.setPlaylist(name, list.map { it.id }.filterIndexed { k, _ -> k != i }) }
                }
            } else items(list, key = { it.id }) { s -> SongRow(s, list, name, playlist = name) }
        }
        Head(if (ls.scrolled) name else "", ls.scrolled, back = true) {
            IB(Ic.Edit, on = editing) { editing = !editing }
            IB(Ic.More) {
                Dialogs.sheet {
                    SheetScroll {
                        SheetHead(cover, name, plural(list.size, "song"), name)
                        Act(Ic.PlayNext, "Play Next") { Ctl.playNext(ctx, list) }
                        Act(Ic.AddQ, "Add to Queue") { Ctl.addToQueue(ctx, list) }
                        Act(Ic.Edit, "Rename…") { Dialogs.show { Prompt("Rename Playlist", name, "Rename") { n -> Store.renamePlaylist(name, n); Nav.back() } } }
                        Act(Ic.Pack, "Share as Music Pack…") { scope.launch { sharePack(ctx, name, list, name) } }
                        Act(Ic.Trash, "Delete Playlist", danger = true) { Dialogs.show { Confirm("Delete “$name”?", "The songs stay in your library.", "Delete") { Store.deletePlaylist(name); Nav.back() } } }
                    }
                }
            }
        }
    }
}

@Composable
fun FavoritesPage() {
    val favs by Store.favs.collectAsState()
    val songs by Store.songs.collectAsState()
    val list = remember(songs, favs) { Store.favorites() }
    val ls = rememberLazyListState()
    Tinted(list.firstOrNull { it.cover.isNotEmpty() }?.cover ?: "") {
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            item {
                val a = accent
                Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth(0.5f).aspectRatio(1f).shadow(30.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(a, mix(a, 0.6f, Color(0xFF7A1A3A))))), contentAlignment = Alignment.Center) { Icon(Ic.HeartFill, size = 72.dp, tint = C.onAccent) }
                    T("Favorite Songs", 24.sp, W800, modifier = Modifier.padding(top = 18.dp)); T("${plural(list.size, "song")} · ${fmtLong(list.sumOf { it.durMs })}", 13.sp, color = C.sub, modifier = Modifier.padding(top = 4.dp))
                }
                Plays(list, "Favorite Songs")
                if (list.isEmpty()) T("Swipe a song to the right, or double-tap the cover in the player, to add it here.", 15.sp, color = C.sub, lines = 3, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
            items(list, key = { it.id }) { s -> SongRow(s, list, "Favorite Songs") }
        }
        Head(if (ls.scrolled) "Favorite Songs" else "", ls.scrolled, back = true)
    }
}

/** Search: your library (songs, albums, artists, playlists as card rows), with recent searches; and YouTube Music */
@Composable
fun SearchPage() {
    var q by remember { mutableStateOf("") }
    val songs by Store.songs.collectAsState()
    val pls by Store.playlists.collectAsState()
    var recent by remember { mutableStateOf(Store.recent()) }
    val yt by Yt.mode
    LaunchedEffect(yt) { if (yt) Engine.warm() }
    val t = q.trim()
    LaunchedEffect(t) { kotlinx.coroutines.delay(1800); if (t.length > 1 && !yt) { Store.addRecent(t); recent = Store.recent() } }
    val hitSongs = remember(songs, t) { if (t.isEmpty()) emptyList() else songs.filter { it.title.contains(t, true) || it.artist.contains(t, true) || it.album.contains(t, true) || it.genre.contains(t, true) }.take(40) }
    val hitAlbums = remember(songs, t) { if (t.isEmpty()) emptyList() else Store.albums(songs).filter { it.title.contains(t, true) || it.artist.contains(t, true) }.take(20) }
    val hitArtists = remember(songs, t) { if (t.isEmpty()) emptyList() else Store.artists(songs).entries.filter { it.key.contains(t, true) }.take(20) }
    val hitPls = pls.keys.filter { t.isNotEmpty() && it.contains(t, true) }
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(C.bg)) {
        LazyColumn(contentPadding = PaddingValues(top = topPad(), bottom = LocalBottom.current)) {
            item {
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); try { focus.requestFocus() } catch (_: Exception) { } }
                SearchBox(if (yt) Yt.q.value else q, { if (yt) Yt.q.value = it else q = it }, if (yt) "Search YouTube Music, or paste a link" else "Songs, albums, artists, genres", { if (yt) Yt.run() }, Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp), focus)

            }
            if (yt) { ytResults(this); return@LazyColumn }
            if (t.isEmpty() && recent.isNotEmpty()) item {
                Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("Recent", 20.sp, W800, modifier = Modifier.weight(1f)); Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { Store.clearRecent(); recent = emptyList() }.padding(10.dp)) { T("Clear", 15.sp, W700, accent) }
                }
                recent.forEach { r -> Row(Modifier.fillMaxWidth().clickable { q = r }.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) { Icon(Ic.History, tint = C.sub); T(r) } }
            }
            if (t.isNotEmpty() && hitSongs.isEmpty() && hitAlbums.isEmpty() && hitArtists.isEmpty() && hitPls.isEmpty()) item {
                Empty(Ic.Search, "No matches", "Nothing in your library matches “$t”.", "Search YouTube Music" to { Yt.mode.value = true; Yt.q.value = t; Yt.run() })
            }
            if (hitSongs.isNotEmpty()) item {
                Shelf("Songs") {
                    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(hitSongs, key = { it.id }) { s ->
                            Column(Modifier.width(150.dp).press(0.96f) { Ctl.play(ctx, hitSongs, hitSongs.indexOf(s), "Search") }) {
                                Art(s.cover, null, Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), phName = s.album); T(s.title, 14.sp, W700, modifier = Modifier.padding(top = 8.dp)); T(s.artist, 13.sp, color = C.sub)
                            }
                        }
                    }
                }
            }
            if (hitArtists.isNotEmpty()) item {
                Shelf("Artists") {
                    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(hitArtists, key = { it.key }) { (n, l) ->
                            Column(Modifier.width(150.dp).press(0.96f, onLong = { Dialogs.sheet { ArtistSheet(n) } }) { Nav.open(Page.ArtistP(n)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Art(Store.artistInfo(n)?.optString("image")?.takeIf { Store.coverFile(it).exists() } ?: l.firstOrNull { it.cover.isNotEmpty() }?.cover ?: "", null, Modifier.fillMaxWidth(), CircleShape, Ic.Person, n)
                                T(n, 14.sp, W700, modifier = Modifier.padding(top = 8.dp)); T(plural(l.size, "song"), 13.sp, color = C.sub)
                            }
                        }
                    }
                }
            }
            if (hitAlbums.isNotEmpty()) item { AlbumShelf("Albums", hitAlbums) }
            if (hitPls.isNotEmpty()) item { Shelf("Playlists") { hitPls.forEach { p -> PlRow({ Art("", 56.dp, shape = RoundedCornerShape(10.dp), ph = Ic.List, phName = p) }, p, null) { Nav.open(Page.PlaylistP(p)) } } } }
        }
        Head(if (yt) "YouTube Music" else "Search", false, back = true) {
            IB(Ic.Gear, modifier = Modifier.padding(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                Dialogs.sheet {
                    SheetScroll {
                        T("Search In", 22.sp, W700, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
                        Act(Ic.Note, "Your Library", if (!yt) "Searching here now" else "") { Yt.mode.value = false }
                        Act(Ic.Globe, "YouTube Music", if (yt) "Searching here now" else "Find songs, albums and artists to add") { Yt.mode.value = true; if (Yt.q.value.isEmpty()) Yt.q.value = q }
                    }
                }
            }
        }
    }
}


/** the album menu (hold an album, or the ⋮ on its page) */
@Composable
fun AlbumSheet(a: Album, onRemoved: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val favs by Store.favs.collectAsState()
    val allFav = a.songs.all { it.id in favs }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) Store.scope.launch { try { ctx.contentResolver.openInputStream(u)?.use { Store.setCover(a.songs.map { it.id }, it.readBytes()) }; Accents.forget(); Toasts.show("Cover updated") } catch (_: Exception) { } }
    }
    SheetScroll {
        SheetHead(a.cover?.cover ?: "", a.title, "${a.artist} · ${plural(a.songs.size, "song")}")
        Act(Ic.Play, "Play") { Ctl.play(ctx, a.songs, 0, a.title, false) }
        Act(Ic.Shuffle, "Shuffle") { Ctl.shuffleAll(ctx, a.songs, a.title) }
        Act(Ic.PlayNext, "Play Next") { Ctl.playNext(ctx, a.songs); Toasts.show("Playing next") }
        Act(Ic.AddQ, "Add to Queue") { Ctl.addToQueue(ctx, a.songs); Toasts.show("Added to queue") }
        Act(Ic.List, "Add to Playlist…") { Dialogs.sheet { PlaylistPicker(a.songs.map { it.id }) } }
        Act(if (allFav) Ic.HeartFill else Ic.Heart, if (allFav) "Remove All from Favorites" else "Favorite All Songs") { Store.setFav(a.songs.map { it.id }, !allFav); likeToast(!allFav) }
        Act(Ic.Person, "Go to Artist") { Nav.open(Page.ArtistP(a.artist)) }
        Act(Ic.Search, "Look Up Album Info…") { Dialogs.sheet { LookupAlbum(a, false) } }
        Act(Ic.Image, "Find Cover Automatically", if (a.cover == null) "This album has no cover yet" else "Replaces the current cover") { Tasks.findCovers(listOf(a)) }
        Act(Ic.Image, "Choose Cover Online…") { Dialogs.sheet { LookupAlbum(a, true) } }
        Act(Ic.Folder, "Choose Cover from Phone…") { pick.launch("image/*") }
        Act(Ic.Pack, "Share as Music Pack…") { scope.launch { sharePack(ctx, a.title, a.songs, null) } }
        Act(Ic.Trash, "Remove Album…", danger = true) { Dialogs.show { Confirm("Remove “${a.title}”?", "${plural(a.songs.size, "song")} are deleted from this phone.", "Remove") { Store.remove(a.songs.map { it.id }); onRemoved() } } }
    }
}

/** the artist menu (hold an artist) */
@Composable
fun ArtistSheet(name: String) {
    val ctx = LocalContext.current
    val songs by Store.songs.collectAsState()
    val favA by Store.favArtists.collectAsState()
    val mine = remember(songs, name) { Store.artists(songs)[name] ?: emptyList() }
    val fav = name.lowercase() in favA
    val info = Store.artistInfo(name)
    val photo = info?.optString("image")?.takeIf { Store.coverFile(it).exists() } ?: mine.firstOrNull { it.cover.isNotEmpty() }?.cover ?: ""
    val noCover = remember(mine) { Store.albums(mine).filter { it.cover == null } }
    SheetScroll {
        SheetHead(photo, name, plural(mine.size, "song"), name, round = true)
        if (mine.isNotEmpty()) {
            Act(Ic.Play, "Play") { Ctl.play(ctx, mine, 0, name, false) }
            Act(Ic.Shuffle, "Shuffle") { Ctl.shuffleAll(ctx, mine, name) }
            Act(Ic.Spark, "Radio", "Similar songs from YouTube Music") { Radio.artist(ctx, name, mine) }
            Act(Ic.PlayNext, "Play Next") { Ctl.playNext(ctx, mine); Toasts.show("Playing next") }
            Act(Ic.AddQ, "Add to Queue") { Ctl.addToQueue(ctx, mine); Toasts.show("Added to queue") }
            Act(Ic.List, "Add to Playlist…") { Dialogs.sheet { PlaylistPicker(mine.map { it.id }) } }
        }
        Act(if (fav) Ic.HeartFill else Ic.Heart, if (fav) "Unfavorite (unpin)" else "Favorite (pin to the top)") { Store.toggleFavArtist(name); likeToast(name.lowercase() in Store.favArtists.value, "favorite artists") }
        Act(Ic.Globe, "Get More from YouTube Music") { Yt.findArtist(name, info?.optString("channel") ?: ""); Nav.open(Page.Search); Yt.mode.value = true }
        if (noCover.isNotEmpty()) Act(Ic.Image, "Find Missing Covers", plural(noCover.size, "album") + " without a cover") { Tasks.findCovers(noCover) }
    }
}
