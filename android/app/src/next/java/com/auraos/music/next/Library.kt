@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.auraos.music.next

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gitems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun sortSongs(l: List<Song>, sort: Int): List<Song> = when (sort) {
    1 -> l.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.track }))
    2 -> l.sortedWith(compareBy({ it.album.lowercase() }, { it.disc }, { it.track }))
    3 -> l.sortedByDescending { it.added }
    4 -> l.sortedByDescending { it.durMs }
    5 -> l.sortedByDescending { it.j.optInt("plays") }
    else -> l.sortedWith(compareBy({ it.title.lowercase() }, { it.artist.lowercase() }))
}

/** filter chips by genre / decade, shared by Songs and Albums */
@Composable
fun Filters(songs: List<Song>, genre: String, onGenre: (String) -> Unit) {
    val genres = remember(songs) { songs.map { it.genre }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).map { it.key } }
    if (genres.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = genre.isEmpty(), onClick = { onGenre("") }, label = { Text("All") })
        for (g in genres) FilterChip(selected = genre == g, onClick = { onGenre(if (genre == g) "" else g) }, label = { Text(g) })
    }
}

@Composable
fun SortButton() {
    val sort by Prefs.sort.collectAsState()
    var m by remember { mutableStateOf(false) }
    Box {
        IconBtn(Ic.Sort) { m = true }
        DropdownMenu(expanded = m, onDismissRequest = { m = false }) {
            Prefs.SORTS.forEachIndexed { i, n -> DropdownMenuItem(leadingIcon = { if (i == sort) Icon(Ic.Check, size = 18.dp) else Spacer(Modifier.size(18.dp)) }, text = { Text(n) }, onClick = { Prefs.setSort(i); m = false }) }
        }
    }
}

@Composable
fun SongsScreen() {
    val songs by Store.songs.collectAsState()
    val sort by Prefs.sort.collectAsState()
    var genre by remember { mutableStateOf("") }
    val list = remember(songs, sort, genre) { sortSongs(songs.filter { genre.isEmpty() || it.genre == genre }, sort) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Header("Songs", "${plural(list.size, "song")} · ${fmt(list.sumOf { it.durMs })}") {
                IconBtn(Ic.Search) { Nav.open(Page.Search) }; IconBtn(Ic.Select) { if (Sel.ids.isEmpty() && list.isNotEmpty()) Sel.ids.add(list[0].id) else Sel.ids.clear() }; SortButton()
            }
            Filters(songs, genre) { genre = it }
            PlayShuffle(list, "Songs")
            if (songs.isEmpty()) Empty(Ic.Note, "No music yet. Add some in Settings › Add Music, or find some in Get.")
        }
        items(list, key = { it.id }) { s -> SongRow(s, list, "Songs") }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AlbumsScreen() {
    val songs by Store.songs.collectAsState()
    val sort by Prefs.sort.collectAsState()
    var genre by remember { mutableStateOf("") }
    val albums = remember(songs, sort, genre) {
        val a = Store.albums(songs.filter { genre.isEmpty() || it.genre == genre })
        when (sort) { 1 -> a.sortedBy { it.artist.lowercase() }; 3 -> a.sortedByDescending { it.added }; 4 -> a.sortedByDescending { it.durMs }; 5 -> a.sortedByDescending { x -> x.songs.sumOf { it.j.optInt("plays") } }; else -> a }
    }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Header("Albums", "${plural(albums.size, "album")} · ${plural(songs.size, "song")}") { IconBtn(Ic.Search) { Nav.open(Page.Search) }; SortButton() }
                Filters(songs, genre) { genre = it }
                if (songs.isEmpty()) Empty(Ic.Disc, "Nothing here yet")
            }
        }
        gitems(albums, key = { it.key }) { a -> AlbumCard(a) }
    }
}

@Composable
fun ArtistsScreen() {
    val songs by Store.songs.collectAsState()
    val favA by Store.favArtists.collectAsState()
    Store.ver.collectAsState().value
    val artists = remember(songs) { Store.artists(songs).entries.toList() }
    val sorted = remember(artists, favA) { artists.sortedWith(compareBy({ it.key.lowercase() !in favA }, { it.key.lowercase() })) }
    LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column { Header("Artists", plural(artists.size, "artist")) { IconBtn(Ic.Search) { Nav.open(Page.Search) } }; if (songs.isEmpty()) Empty(Ic.Person, "Nothing here yet") }
        }
        gitems(sorted, key = { it.key }) { (name, l) ->
            Column(Modifier.clickable { Nav.open(Page.ArtistP(name)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                CoverId(Store.artistInfo(name)?.optString("image")?.takeIf { Store.coverFile(it).exists() } ?: l.firstOrNull { it.cover.isNotEmpty() }?.cover ?: "", Modifier.fillMaxWidth(), CircleShape, Ic.Person)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    if (name.lowercase() in favA) Icon(Ic.HeartFill, size = 12.dp, tint = accentC)
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
                Text(plural(l.size, "song"), fontSize = 12.sp, color = dim)
            }
        }
    }
}

@Composable
fun PlaylistsScreen() {
    val pls by Store.playlists.collectAsState()
    val favs by Store.favs.collectAsState()
    val songs by Store.songs.collectAsState()
    val by = remember(songs) { songs.associateBy { it.id } }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Header("Playlists", plural(pls.size, "playlist")) { IconBtn(Ic.Plus) { Dialogs.show { Prompt("New Playlist", "", "Create") { Store.newPlaylist(it) } } } } }
        item {
            Row(Modifier.fillMaxWidth().clickable { Nav.open(Page.Favorites) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Icon(Ic.HeartFill, tint = accentC) }
                Column(Modifier.padding(start = 14.dp)) { Text("Favorites", fontWeight = FontWeight.Medium); Text(plural(favs.keys.count { it in by }, "song"), fontSize = 12.sp, color = dim) }
            }
        }
        items(pls.keys.toList(), key = { it }) { name ->
            val ids = pls[name] ?: emptyList()
            Row(Modifier.fillMaxWidth().clickable { Nav.open(Page.PlaylistP(name)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverId(ids.firstNotNullOfOrNull { by[it]?.cover?.takeIf { c -> c.isNotEmpty() } } ?: "", Modifier.size(56.dp), placeholder = Ic.List)
                Column(Modifier.padding(start = 14.dp)) { Text(name, fontWeight = FontWeight.Medium); Text("${plural(ids.count { it in by }, "song")} · ${fmt(ids.sumOf { by[it]?.durMs ?: 0 })}", fontSize = 12.sp, color = dim) }
            }
        }
    }
}

// ------------------------------------------------------------------ pages
@Composable
fun AlbumPage(key: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by Store.songs.collectAsState()
    val a = remember(songs, key) { Store.albums(songs.filter { it.albumKey == key }).firstOrNull() }
    var menu by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null && a != null) Store.scope.launch { try { ctx.contentResolver.openInputStream(u)?.use { Store.setCover(a.songs.map { it.id }, it.readBytes()) }; Toasts.show("Cover updated") } catch (_: Exception) { } }
    }
    if (a == null) { LaunchedEffect(Unit) { Nav.back() }; return }
    val multiDisc = a.songs.map { it.disc }.distinct().size > 1
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item {
            Header("", back = true) {
                Box {
                    IconBtn(Ic.More) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Item(Ic.Play, "Play Next") { menu = false; Ctl.playNext(ctx, a.songs) }
                        Item(Ic.Queue, "Add to Queue") { menu = false; Ctl.addToQueue(ctx, a.songs) }
                        Item(Ic.List, "Add to Playlist…") { menu = false; Dialogs.show { PlaylistPicker(a.songs.map { it.id }) } }
                        Item(Ic.HeartFill, "Favorite All Songs") { menu = false; Store.setFav(a.songs.map { it.id }, true); Toasts.show("Added to Favorites") }
                        Item(Ic.Person, "Go to Artist") { menu = false; Nav.open(Page.ArtistP(a.artist)) }
                        Item(Ic.Wand, "Look Up Album Info…") { menu = false; Dialogs.show { LookupAlbum(a, false) } }
                        Item(Ic.Image, "Find Cover Online…") { menu = false; Dialogs.show { LookupAlbum(a, true) } }
                        Item(Ic.Folder, "Choose Cover from Phone…") { menu = false; pick.launch("image/*") }
                        Item(Ic.Pack, "Share as Music Pack…") { menu = false; scope.launch { sharePack(ctx, a.title, a.songs, null) } }
                        Item(Ic.Trash, "Remove Album…") { menu = false; Dialogs.show { Confirm("Remove “${a.title}”?", "${plural(a.songs.size, "song")} are deleted from this phone.", "Remove") { Store.remove(a.songs.map { it.id }); Nav.back() } } }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CoverId(a.cover?.cover ?: "", Modifier.fillMaxWidth(0.72f))
                Spacer(Modifier.height(14.dp))
                Text(a.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(a.artist, color = accentC, modifier = Modifier.clickable { Nav.open(Page.ArtistP(a.artist)) }.padding(4.dp))
                Text(listOf(a.songs.firstOrNull { it.genre.isNotBlank() }?.genre ?: "", a.year, plural(a.songs.size, "song"), fmt(a.durMs)).filter { it.isNotBlank() }.joinToString(" · "), color = dim, fontSize = 13.sp)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PlayShuffle(a.songs, a.title) }
        }
        var lastDisc = -1
        a.songs.forEach { s ->
            if (multiDisc && s.disc != lastDisc) { val d = s.disc; item(key = "disc$d") { Text("Disc ${if (d > 0) d else 1}", color = dim, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp)) } }
            lastDisc = s.disc
            item(key = s.id) { SongRow(s, a.songs, a.title, showCover = false, number = s.track) }
        }
        item {
            val c = a.songs.firstNotNullOfOrNull { it.j.optString("copyright").ifBlank { null } }
            if (c != null) Text(c, fontSize = 11.sp, color = dim, modifier = Modifier.padding(16.dp))
            Spacer(Modifier.height(24.dp))
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
    val own = remember(mine) { Store.albums(mine.filter { it.albumArtist.equals(name, true) }).sortedBy { it.year.ifBlank { "9999" } } }
    val featured = remember(mine) { Store.albums(mine.filter { !it.albumArtist.equals(name, true) }) }
    val info = Store.artistInfo(name)
    var all by remember { mutableStateOf(false) }
    val top = remember(mine) { mine.sortedWith(compareByDescending<Song> { it.j.optInt("plays") }.thenByDescending { Store.isFav(it.id) }.thenByDescending { it.added }) }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item {
            Header("", back = true) {
                IconBtn(if (name.lowercase() in favA) Ic.HeartFill else Ic.Heart, if (name.lowercase() in favA) accentC else MaterialTheme.colorScheme.onSurface) { Store.toggleFavArtist(name) }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CoverId(info?.optString("image")?.takeIf { Store.coverFile(it).exists() } ?: mine.firstOrNull { it.cover.isNotEmpty() }?.cover ?: "", Modifier.fillMaxWidth(0.55f), CircleShape, Ic.Person)
                Text(name, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
                Text("${plural(mine.size, "song")} · ${plural(own.size, "album")}", color = dim, fontSize = 13.sp)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PlayShuffle(mine, name) { OutlinedButton(onClick = { Radio.artist(ctx, name, mine) }) { Icon(Ic.Radio, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("Radio") } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { GetState.findArtist(name, info?.optString("channel") ?: ""); Nav.stack.clear(); Nav.tab.value = 4 }) { Icon(Ic.Download, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("More from $name on YouTube Music") }
            }
            Text("Songs", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        }
        items(if (all) top else top.take(5), key = { it.id }) { s -> SongRow(s, top, name) }
        if (top.size > 5) item { TextButton(onClick = { all = !all }, modifier = Modifier.padding(start = 8.dp)) { Text(if (all) "Show fewer" else "See all ${top.size}") } }
        item {
            AlbumRow("Albums", own.filter { it.songs.size > 3 })
            AlbumRow("Singles & EPs", own.filter { it.songs.size <= 3 })
            AlbumRow("Featured on", featured)
            val about = info?.optString("about") ?: ""
            if (about.isNotBlank()) {
                var more by remember { mutableStateOf(false) }
                Text("About", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
                Text(about, color = dim, fontSize = 14.sp, maxLines = if (more) 200 else 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { more = !more }.padding(16.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun PlaylistPage(name: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pls by Store.playlists.collectAsState()
    val songs by Store.songs.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val ids = pls[name]
    if (ids == null) { LaunchedEffect(Unit) { Nav.back() }; return }
    val by = remember(songs) { songs.associateBy { it.id } }
    val list = ids.mapNotNull { by[it] }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item {
            Header(name, "${plural(list.size, "song")} · ${fmt(list.sumOf { it.durMs })}", back = true) {
                IconBtn(Ic.Edit, if (editing) accentC else MaterialTheme.colorScheme.onSurface) { editing = !editing }
                Box {
                    IconBtn(Ic.More) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Item(Ic.Play, "Play Next") { menu = false; Ctl.playNext(ctx, list) }
                        Item(Ic.Queue, "Add to Queue") { menu = false; Ctl.addToQueue(ctx, list) }
                        Item(Ic.Edit, "Rename…") { menu = false; Dialogs.show { Prompt("Rename Playlist", name, "Rename") { n -> Store.renamePlaylist(name, n); Nav.back() } } }
                        Item(Ic.Pack, "Share as Music Pack…") { menu = false; scope.launch { sharePack(ctx, name, list, name) } }
                        Item(Ic.Trash, "Delete Playlist") { menu = false; Dialogs.show { Confirm("Delete “$name”?", "The songs stay in your library.", "Delete") { Store.deletePlaylist(name); Nav.back() } } }
                    }
                }
            }
            PlayShuffle(list, name)
            if (list.isEmpty()) Empty(Ic.List, "Empty. Use ⋯ › Add to Playlist on any song.")
        }
        if (editing) itemsIndexed(list, key = { i, s -> "$i${s.id}" }) { i, s ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(s, 40.dp)
                Text(s.title, Modifier.weight(1f).padding(horizontal = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconBtn(Ic.Up, if (i > 0) MaterialTheme.colorScheme.onSurface else dim, 20.dp) { if (i > 0) Store.setPlaylist(name, list.map { it.id }.toMutableList().also { java.util.Collections.swap(it, i, i - 1) }) }
                IconBtn(Ic.Down, if (i < list.size - 1) MaterialTheme.colorScheme.onSurface else dim, 20.dp) { if (i < list.size - 1) Store.setPlaylist(name, list.map { it.id }.toMutableList().also { java.util.Collections.swap(it, i, i + 1) }) }
                IconBtn(Ic.Close, dim, 20.dp) { Store.setPlaylist(name, list.map { it.id }.filterIndexed { k, _ -> k != i }) }
            }
        } else items(list, key = { it.id }) { s -> SongRow(s, list, name, playlist = name) }
    }
}

@Composable
fun FavoritesPage() {
    Store.favs.collectAsState().value
    val songs by Store.songs.collectAsState()
    val list = remember(songs, Store.favs.value) { Store.favorites() }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item { Header("Favorites", "${plural(list.size, "song")} · ${fmt(list.sumOf { it.durMs })}", back = true); PlayShuffle(list, "Favorites"); if (list.isEmpty()) Empty(Ic.Heart, "Swipe a song right, or double-tap the cover in the player, to add it here.") }
        items(list, key = { it.id }) { s -> SongRow(s, list, "Favorites") }
    }
}

/** search your library, with recent searches */
@Composable
fun SearchPage() {
    var q by remember { mutableStateOf("") }
    val songs by Store.songs.collectAsState()
    val pls by Store.playlists.collectAsState()
    var recent by remember { mutableStateOf(Store.recent()) }
    val t = q.trim()
    val hitSongs = remember(songs, t) { if (t.isEmpty()) emptyList() else songs.filter { it.title.contains(t, true) || it.artist.contains(t, true) || it.album.contains(t, true) || it.genre.contains(t, true) }.take(60) }
    val hitAlbums = remember(songs, t) { if (t.isEmpty()) emptyList() else Store.albums(songs).filter { it.title.contains(t, true) || it.artist.contains(t, true) }.take(20) }
    val hitArtists = remember(songs, t) { if (t.isEmpty()) emptyList() else Store.artists(songs).keys.filter { it.contains(t, true) }.take(20) }
    val hitPls = pls.keys.filter { t.isNotEmpty() && it.contains(t, true) }
    fun keep() { Store.addRecent(t); recent = Store.recent() }
    LaunchedEffect(t) { kotlinx.coroutines.delay(1800); if (t.length > 1) { Store.addRecent(t); recent = Store.recent() } }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp, top = 6.dp)) {
                IconBtn(Ic.Back) { Nav.back() }
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Songs, albums, artists, genres") }, singleLine = true,
                    leadingIcon = { Icon(Ic.Search, size = 20.dp, tint = dim) }, trailingIcon = { if (q.isNotEmpty()) IconBtn(Ic.Close, dim, 18.dp) { q = "" } })
            }
            if (t.isEmpty() && recent.isNotEmpty()) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); TextButton(onClick = { Store.clearRecent(); recent = emptyList() }) { Text("Clear") }
                }
                recent.forEach { r -> Row(Modifier.fillMaxWidth().clickable { q = r }.padding(horizontal = 16.dp, vertical = 10.dp)) { Icon(Ic.Timer, size = 18.dp, tint = dim); Spacer(Modifier.width(12.dp)); Text(r) } }
            }
            if (t.isNotEmpty() && hitSongs.isEmpty() && hitAlbums.isEmpty() && hitArtists.isEmpty() && hitPls.isEmpty()) Empty(Ic.Search, "Nothing matches “$t”")
        }
        if (hitArtists.isNotEmpty()) item {
            Text("Artists", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            hitArtists.forEach { a -> Row(Modifier.fillMaxWidth().clickable { keep(); Nav.open(Page.ArtistP(a)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Ic.Person, tint = dim); Spacer(Modifier.width(12.dp)); Text(a) } }
        }
        if (hitAlbums.isNotEmpty()) item { AlbumRow("Albums", hitAlbums) }
        if (hitPls.isNotEmpty()) item {
            Text("Playlists", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            hitPls.forEach { p -> Row(Modifier.fillMaxWidth().clickable { keep(); Nav.open(Page.PlaylistP(p)) }.padding(horizontal = 16.dp, vertical = 10.dp)) { Icon(Ic.List, tint = dim); Spacer(Modifier.width(12.dp)); Text(p) } }
        }
        if (hitSongs.isNotEmpty()) item { Text("Songs", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
        items(hitSongs, key = { "s" + it.id }) { s -> SongRow(s, hitSongs, "Search") }
    }
}
