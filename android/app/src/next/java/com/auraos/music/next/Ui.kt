@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.auraos.music.next

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auraos.music.NativePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
fun plural(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"
val dim @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val accentC @Composable get() = MaterialTheme.colorScheme.primary

/** Pages opened on top of the tabs (back closes them) */
sealed class Page {
    data class AlbumP(val key: String) : Page()
    data class ArtistP(val name: String) : Page()
    data class PlaylistP(val name: String) : Page()
    object Favorites : Page()
    object Search : Page()
}
object Nav {
    val stack = mutableStateListOf<Page>()
    val tab = mutableStateOf(0)
    fun open(p: Page) { stack.add(p) }
    fun back(): Boolean { if (stack.isEmpty()) return false; stack.removeAt(stack.size - 1); return true }
}

/** Selecting several songs: long-press a song, then tap more */
object Sel { val ids = mutableStateListOf<String>(); fun toggle(id: String) { if (id in ids) ids.remove(id) else ids.add(id) } }

/** One dialog at a time, anywhere */
object Dialogs { val cur = mutableStateOf<(@Composable () -> Unit)?>(null); fun show(d: @Composable () -> Unit) { cur.value = d }; fun close() { cur.value = null } }

@Composable
fun IconBtn(ic: Ic, tint: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 24.dp, onClick: () -> Unit) {
    Box(Modifier.size(size + 20.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) { Icon(ic, size = size, tint = tint) }
}

@Composable
fun Header(title: String, sub: String = "", back: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = if (back) 4.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back) IconBtn(Ic.Back) { Nav.back() }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = if (back) 20.sp else 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = dim, maxLines = 1)
        }
        actions()
    }
}

@Composable
fun Empty(ic: Ic, text: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(ic, size = 48.dp, tint = dim); Spacer(Modifier.height(12.dp)); Text(text, color = dim)
    }
}

@Composable
fun Cover(s: Song?, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val m = modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
    val p = s?.coverPath ?: ""
    if (p.isNotEmpty()) AsyncImage(File(Store.app.filesDir, p), null, m, contentScale = ContentScale.Crop)
    else Box(m, contentAlignment = Alignment.Center) { Icon(Ic.Note, size = size / 2, tint = dim) }
}
@Composable
fun CoverId(id: String, modifier: Modifier, shape: Shape = RoundedCornerShape(10.dp), placeholder: Ic = Ic.Disc) {
    val m = modifier.aspectRatio(1f).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
    if (id.isNotEmpty()) AsyncImage(Store.coverFile(id), null, m, contentScale = ContentScale.Crop)
    else Box(m, contentAlignment = Alignment.Center) { Icon(placeholder, size = 40.dp, tint = dim) }
}

@Composable
fun PlayShuffle(list: List<Song>, label: String, extra: @Composable RowScope.() -> Unit = {}) {
    val ctx = LocalContext.current
    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { Ctl.play(ctx, list, 0, label, false) }, enabled = list.isNotEmpty()) { Icon(Ic.Play, size = 18.dp, tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(6.dp)); Text("Play") }
        OutlinedButton(onClick = { Ctl.shuffleAll(ctx, list, label) }, enabled = list.isNotEmpty()) { Icon(Ic.Shuffle, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
        extra()
    }
}

// ------------------------------------------------------------------ song rows + the song menu
@Composable
fun SongRow(s: Song, list: List<Song>, label: String, showCover: Boolean = true, number: Int = 0, playlist: String? = null) {
    val ctx = LocalContext.current
    val st by NativePlayer.state.collectAsState()
    val favs by Store.favs.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val selecting = Sel.ids.isNotEmpty()
    val picked = s.id in Sel.ids
    val trig = with(LocalDensity.current) { 90.dp.toPx() }
    var dx by remember { mutableStateOf(0f) }
    val playing = Ctl.queue.value.getOrNull(st.index)?.id == s.id
    Box(Modifier.fillMaxWidth()) {
        if (dx > 8f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 20.dp), contentAlignment = Alignment.CenterStart) { Icon(if (s.id in favs) Ic.Heart else Ic.HeartFill, tint = accentC) }
        if (dx < -8f) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) { Icon(Ic.Queue, tint = accentC) }
        Row(Modifier.fillMaxWidth().offset { IntOffset(dx.roundToInt(), 0) }
            .background(if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
            .combinedClickable(onLongClick = { Sel.toggle(s.id) }) { if (selecting) Sel.toggle(s.id) else Ctl.play(ctx, list, list.indexOf(s).coerceAtLeast(0), label) }
            .pointerInput(s.id) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (dx > trig) Store.toggleFav(s.id) else if (dx < -trig) Ctl.playNext(ctx, listOf(s)); dx = 0f },
                    onDragCancel = { dx = 0f },
                ) { change, amount -> change.consume(); dx = (dx + amount).coerceIn(-trig * 1.6f, trig * 1.6f) }
            }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Box(Modifier.padding(end = 8.dp)) { Icon(if (picked) Ic.Select else Ic.Disc, size = 22.dp, tint = if (picked) accentC else dim) }
            if (showCover) Cover(s, 46.dp)
            else Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) { if (playing) Icon(Ic.Play, size = 14.dp, tint = accentC) else Text(if (number > 0) "$number" else "–", color = dim, fontSize = 13.sp) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) accentC else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                Text((if (showCover) listOf(s.artist, s.album) else listOf(s.artist)).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = dim)
            }
            if (s.id in favs) Icon(Ic.HeartFill, size = 14.dp, tint = accentC)
            if (s.durMs > 0) Text(fmt(s.durMs), fontSize = 12.sp, color = dim, modifier = Modifier.padding(start = 8.dp))
            Box {
                IconBtn(Ic.More, dim, 20.dp) { menu = true }
                SongMenu(s, menu, { menu = false }, playlist)
            }
        }
    }
}

@Composable
fun Item(ic: Ic, label: String, onClick: () -> Unit) = DropdownMenuItem(leadingIcon = { Icon(ic, size = 20.dp) }, text = { Text(label) }, onClick = onClick)

@Composable
fun SongMenu(s: Song, open: Boolean, close: () -> Unit, playlist: String? = null) {
    val ctx = LocalContext.current
    DropdownMenu(expanded = open, onDismissRequest = close) {
        Item(Ic.Play, "Play Next") { close(); Ctl.playNext(ctx, listOf(s)) }
        Item(Ic.Queue, "Add to Queue") { close(); Ctl.addToQueue(ctx, listOf(s)) }
        Item(if (Store.isFav(s.id)) Ic.HeartFill else Ic.Heart, if (Store.isFav(s.id)) "Remove from Favorites" else "Add to Favorites") { close(); Toasts.show(if (Store.toggleFav(s.id)) "Added to Favorites" else "Removed from Favorites") }
        Item(Ic.List, "Add to Playlist…") { close(); Dialogs.show { PlaylistPicker(listOf(s.id)) } }
        Item(Ic.Radio, "Start Radio") { close(); Radio.start(ctx, s) }
        Item(Ic.Disc, "Go to Album") { close(); Nav.open(Page.AlbumP(s.albumKey)) }
        Item(Ic.Person, "Go to Artist") { close(); Nav.open(Page.ArtistP(primaryArtist(s.artist).ifBlank { s.albumArtist })) }
        Item(Ic.Edit, "Edit Info…") { close(); Dialogs.show { EditInfo(s) } }
        Item(Ic.Wand, "Look Up Song Info…") { close(); Dialogs.show { LookupSong(s) } }
        Item(Ic.Lyrics, "Lyrics…") { close(); Dialogs.show { LyricsSearch(s) } }
        Item(Ic.Info, "Song Info") { close(); Dialogs.show { SongInfo(s) } }
        Item(Ic.Share, "Share Song…") { close(); try { Packs.shareSong(ctx, s) } catch (e: Exception) { Toasts.show("Couldn't share: ${e.message}") } }
        if (playlist != null) Item(Ic.Close, "Remove from This Playlist") { close(); Store.setPlaylist(playlist, (Store.playlists.value[playlist] ?: emptyList()).filter { it != s.id }) }
        Item(Ic.Trash, "Remove Song…") { close(); Dialogs.show { Confirm("Remove “${s.title}”?", "It's deleted from this phone.", "Remove") { Store.remove(listOf(s.id)) } } }
    }
}

object Radio {
    /** YouTube Music's radio for a song: similar songs are downloaded one by one and join the queue as they arrive */
    fun start(ctx: Context, s: Song) {
        Ctl.play(ctx, listOf(s), 0, "Radio · ${s.title}", false)
        Toasts.show("Finding similar songs…")
        Store.scope.launch {
            try {
                val arg = org.json.JSONObject(); val yt = s.j.optString("yt")
                if (yt.isNotBlank()) arg.put("id", yt) else arg.put("q", "${s.title} ${primaryArtist(s.artist)}")
                val tr = Engine.radio(arg)
                Downloader.enqueue(tr, play = true)
                Toasts.show("Radio: ${tr.size} songs on the way")
            } catch (e: Exception) { Toasts.show("Radio: ${e.message}") }
        }
    }
    fun artist(ctx: Context, name: String, songs: List<Song>) { songs.shuffled().firstOrNull()?.let { start(ctx, it) } ?: Toasts.show("No songs by $name yet") }
}

// ------------------------------------------------------------------ dialogs
@Composable
fun Confirm(title: String, text: String, yes: String, onYes: () -> Unit) {
    AlertDialog(onDismissRequest = { Dialogs.close() }, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { Dialogs.close(); onYes() }) { Text(yes, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { Dialogs.close() }) { Text("Cancel") } })
}

@Composable
fun Prompt(title: String, initial: String, yes: String, onYes: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = { Dialogs.close() }, title = { Text(title) }, text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = t.isNotBlank(), onClick = { Dialogs.close(); onYes(t.trim()) }) { Text(yes) } },
        dismissButton = { TextButton(onClick = { Dialogs.close() }) { Text("Cancel") } })
}

@Composable
fun PlaylistPicker(ids: List<String>) {
    val pls by Store.playlists.collectAsState()
    AlertDialog(onDismissRequest = { Dialogs.close() }, title = { Text("Add to Playlist") },
        confirmButton = { TextButton(onClick = { Dialogs.show { Prompt("New Playlist", "", "Create") { n -> val name = Store.newPlaylist(n, ids); Toasts.show("Added to “$name”") } } }) { Text("New Playlist…") } },
        dismissButton = { TextButton(onClick = { Dialogs.close() }) { Text("Cancel") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (pls.isEmpty()) item { Text("No playlists yet.", color = dim) }
                items(pls.keys.toList()) { name ->
                    Row(Modifier.fillMaxWidth().clickable { val n = Store.addToPlaylist(name, ids); Dialogs.close(); Toasts.show(if (n == 0) "Already in “$name”" else "Added to “$name”") }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ic.List, size = 20.dp); Spacer(Modifier.width(12.dp)); Text(name, Modifier.weight(1f)); Text("${pls[name]?.size ?: 0}", color = dim)
                    }
                }
            }
        })
}

@Composable
fun SongInfo(s: Song) {
    val f = File(Store.app.filesDir, s.path)
    AlertDialog(onDismissRequest = { Dialogs.close() }, confirmButton = { TextButton(onClick = { Dialogs.close() }) { Text("Close") } }, title = { Text(s.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                @Composable fun r(k: String, v: String) { if (v.isNotBlank() && v != "0") Row { Text(k, color = dim, modifier = Modifier.width(96.dp)); Text(v) } }
                r("Artist", s.artist); r("Album", s.album); r("Album artist", s.albumartist); r("Track", if (s.track > 0) "${s.track}" else ""); r("Disc", if (s.disc > 0) "${s.disc}" else "")
                r("Year", s.year); r("Genre", s.genre); r("Length", fmt(s.durMs)); r("Format", s.j.optString("codec").ifBlank { s.ext.uppercase() })
                r("Size", "%.1f MB".format(f.length() / 1048576.0)); r("Added", if (s.added > 0) DateFormat.getDateInstance().format(Date(s.added)) else "")
                r("Played", if (s.j.optInt("plays") > 0) plural(s.j.optInt("plays"), "time") else "")
                if (s.trimStart > 0 || s.trimEnd > 0) r("Silence cut", "%.1f s at the start · %.1f s at the end".format(s.trimStart, if (s.trimEnd > 0) s.durMs / 1000.0 - s.trimEnd else 0.0))
                s.j.optJSONObject("tempo")?.optJSONArray("s")?.let { if (it.optDouble(0) > 0) r("Tempo", "%.0f BPM".format(it.optDouble(0))) }
                Text(s.path, fontSize = 11.sp, color = dim)
            }
        })
}

@Composable
fun EditInfo(s: Song) {
    val keys = listOf("title" to "Title", "artist" to "Artist", "album" to "Album", "albumartist" to "Album artist", "track" to "Track", "disc" to "Disc", "year" to "Year", "genre" to "Genre")
    val vals = remember { mutableStateMapOf<String, String>().apply { for ((k, _) in keys) put(k, s.j.optString(k).let { if (it == "0") "" else it }) } }
    AlertDialog(onDismissRequest = { Dialogs.close() }, title = { Text("Edit Info") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((k, l) in keys) OutlinedTextField(vals[k] ?: "", { vals[k] = it }, label = { Text(l) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    keyboardOptions = if (k == "track" || k == "disc") KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val f = HashMap<String, Any>()
                for ((k, _) in keys) { val v = (vals[k] ?: "").trim(); f[k] = if (k == "track" || k == "disc") (v.toIntOrNull() ?: 0) else v }
                Dialogs.close(); Store.scope.launch { Store.edit(s.id, f) }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { Dialogs.close() }) { Text("Cancel") } })
}

@Composable
fun LookupSong(s: Song) {
    var q by remember { mutableStateOf("${s.title} ${primaryArtist(s.artist)}".trim()) }
    var hits by remember { mutableStateOf<List<Online.Hit>?>(null) }
    var cover by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun go() { hits = null; scope.launch { hits = withContext(Dispatchers.IO) { Online.songs(q) } } }
    LaunchedEffect(Unit) { go() }
    AlertDialog(onDismissRequest = { Dialogs.close() }, title = { Text("Look Up Song Info") },
        confirmButton = { TextButton(onClick = { Dialogs.close() }) { Text("Cancel") } },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, trailingIcon = { IconBtn(Ic.Search, size = 20.dp) { go() } }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cover, { cover = it }); Text("Use its cover too") }
                if (hits == null) Text("Searching iTunes and Deezer…", color = dim)
                else if (hits!!.isEmpty()) Text("Nothing found", color = dim)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(hits ?: emptyList()) { h ->
                        Row(Modifier.fillMaxWidth().clickable {
                            Dialogs.close()
                            Store.scope.launch {
                                val f = HashMap<String, Any>()
                                if (h.title.isNotBlank()) f["title"] = h.title; if (h.artist.isNotBlank()) f["artist"] = h.artist; if (h.album.isNotBlank()) f["album"] = h.album
                                if (h.year.isNotBlank()) f["year"] = h.year; if (h.genre.isNotBlank()) f["genre"] = h.genre; if (h.track > 0) f["track"] = h.track; if (h.disc > 0) f["disc"] = h.disc
                                Store.edit(s.id, f)
                                if (cover && h.cover.isNotBlank()) try { Store.setCover(listOf(s.id), Net.get(h.cover)) } catch (_: Exception) { }
                                Toasts.show("Info updated")
                            }
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(h.cover, null, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(h.artist, h.album, h.year, h.src).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        })
}

/** Look Up Album Info (all fields + cover) or Find Cover Online (cover only) */
@Composable
fun LookupAlbum(a: Album, coverOnly: Boolean) {
    var q by remember { mutableStateOf("${a.title} ${a.artist}".trim()) }
    var hits by remember { mutableStateOf<List<Online.Hit>?>(null) }
    val scope = rememberCoroutineScope()
    fun go() { hits = null; scope.launch { hits = withContext(Dispatchers.IO) { Online.albums(q) } } }
    LaunchedEffect(Unit) { go() }
    AlertDialog(onDismissRequest = { Dialogs.close() }, title = { Text(if (coverOnly) "Find Cover Online" else "Look Up Album Info") },
        confirmButton = { TextButton(onClick = { Dialogs.close() }) { Text("Cancel") } },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, trailingIcon = { IconBtn(Ic.Search, size = 20.dp) { go() } }, modifier = Modifier.fillMaxWidth())
                if (hits == null) Text("Searching iTunes and Deezer…", color = dim) else if (hits!!.isEmpty()) Text("Nothing found", color = dim)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(hits ?: emptyList()) { h ->
                        Row(Modifier.fillMaxWidth().clickable {
                            Dialogs.close()
                            Store.scope.launch {
                                try {
                                    if (coverOnly) Store.setCover(a.songs.map { it.id }, Net.get(h.cover)) else Online.applyAlbum(a, h)
                                    Toasts.show(if (coverOnly) "Cover updated" else "Album info updated")
                                } catch (e: Exception) { Toasts.show("Couldn't: ${e.message}") }
                            }
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(h.cover, null, Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(h.album, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOf(h.artist, h.year, h.genre, h.src).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = dim, maxLines = 1)
                            }
                        }
                    }
                }
            }
        })
}

@Composable
fun LyricsSearch(s: Song) {
    var q by remember { mutableStateOf("${s.title} ${primaryArtist(s.artist)}".trim()) }
    var hits by remember { mutableStateOf<List<org.json.JSONObject>?>(null) }
    val scope = rememberCoroutineScope()
    fun go() { hits = null; scope.launch { hits = withContext(Dispatchers.IO) { Online.searchLyrics(q) } } }
    LaunchedEffect(Unit) { go() }
    AlertDialog(onDismissRequest = { Dialogs.close() }, title = { Text("Choose Lyrics") },
        confirmButton = { TextButton(onClick = { Dialogs.close() }) { Text("Cancel") } },
        dismissButton = { TextButton(onClick = { Online.save(s.id, null); Lyr.bump(); Dialogs.close() }) { Text("No lyrics") } },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, trailingIcon = { IconBtn(Ic.Search, size = 20.dp) { go() } }, modifier = Modifier.fillMaxWidth())
                if (hits == null) Text("Searching LRCLIB…", color = dim) else if (hits!!.isEmpty()) Text("Nothing found", color = dim)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(hits ?: emptyList()) { h ->
                        Column(Modifier.fillMaxWidth().clickable { Online.save(s.id, h); Lyr.bump(); Dialogs.close(); Toasts.show("Lyrics saved") }.padding(vertical = 8.dp)) {
                            Text(h.optString("trackName"), maxLines = 1)
                            Text(listOf(h.optString("artistName"), h.optString("albumName"), fmt((h.optDouble("duration", 0.0) * 1000).toLong()), if (h.optString("syncedLyrics").let { it.isNotBlank() && it != "null" }) "synced" else "plain").filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = dim, maxLines = 1)
                        }
                    }
                }
            }
        })
}

/** lyrics refresh signal */
object Lyr { val v = mutableStateOf(0); fun bump() { v.value++ } }

// ------------------------------------------------------------------ album card / grid helpers
@Composable
fun AlbumCard(a: Album, modifier: Modifier = Modifier) {
    Column(modifier.clickable { Nav.open(Page.AlbumP(a.key)) }) {
        CoverId(a.cover?.cover ?: "", Modifier.fillMaxWidth())
        Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Text(listOf(a.artist, a.year).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = dim)
    }
}

@Composable
fun AlbumRow(title: String, albums: List<Album>) {
    if (albums.isEmpty()) return
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.key }) { a -> AlbumCard(a, Modifier.width(140.dp)) }
    }
}

/** the bar shown while songs are selected */
@Composable
fun SelectionBar() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by Store.songs.collectAsState()
    val by = songs.associateBy { it.id }
    val list = Sel.ids.mapNotNull { by[it] }
    var more by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Ic.Close) { Sel.ids.clear() }
        Text("${list.size} selected", Modifier.weight(1f), fontWeight = FontWeight.Medium)
        IconBtn(Ic.Play, accentC) { Ctl.play(ctx, list, 0, "Selection"); Sel.ids.clear() }
        IconBtn(Ic.Queue) { Ctl.addToQueue(ctx, list); Sel.ids.clear() }
        IconBtn(Ic.List) { val ids = list.map { it.id }; Dialogs.show { PlaylistPicker(ids) }; Sel.ids.clear() }
        Box {
            IconBtn(Ic.More) { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                Item(Ic.Play, "Play Next") { more = false; Ctl.playNext(ctx, list); Sel.ids.clear() }
                Item(Ic.HeartFill, "Add to Favorites") { more = false; Store.setFav(list.map { it.id }, true); Sel.ids.clear() }
                Item(Ic.Heart, "Remove from Favorites") { more = false; Store.setFav(list.map { it.id }, false); Sel.ids.clear() }
                Item(Ic.Select, "Select All") { more = false; Sel.ids.clear(); Sel.ids.addAll(songs.map { it.id }) }
                Item(Ic.Pack, "Share as Music Pack…") { more = false; val l = list; Sel.ids.clear(); Dialogs.show { Prompt("Name the Music Pack", "My Music", "Share") { n -> scope.launch { sharePack(ctx, n, l, null) } } } }
                Item(Ic.Trash, "Remove…") { more = false; val ids = list.map { it.id }; Dialogs.show { Confirm("Remove ${plural(ids.size, "song")}?", "They're deleted from this phone.", "Remove") { Store.remove(ids); Sel.ids.clear() } } }
            }
        }
    }
}

suspend fun sharePack(ctx: Context, title: String, songs: List<Song>, playlist: String?) {
    try { val f = Packs.build(title, songs, playlist); Packs.share(ctx, f) } catch (e: Exception) { Store.busy.value = ""; Toasts.show("Couldn't make the pack: ${e.message}") }
}
