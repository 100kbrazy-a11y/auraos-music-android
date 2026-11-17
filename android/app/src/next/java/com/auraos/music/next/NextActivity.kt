@file:OptIn(ExperimentalMaterial3Api::class)

package com.auraos.music.next

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auraos.music.NativePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Strata Music Next: the all-native app (Kotlin + Compose). The WebView app is untouched; this is a separate test app. */
class NextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Store.init(this)
        NativePlayer.start(this)
        if (savedInstanceState == null) Ctl.restore(this)
        setContent { Theme { App() } }
    }
    override fun onStop() { super.onStop(); Ctl.save() }
}

@Composable
fun Theme(content: @Composable () -> Unit) {
    val accent by Prefs.accent.collectAsState()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(accent), onPrimary = Color(0xFF14101E), background = Color(0xFF0B0B0E),
            surface = Color(0xFF14141A), onSurface = Color(0xFFEDEDF2), onBackground = Color(0xFFEDEDF2),
            surfaceVariant = Color(0xFF1E1E26), onSurfaceVariant = Color(0xFFA8A8B8), secondaryContainer = Color(0xFF2A2638),
        ),
    ) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
}

private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
private val dim @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
fun IconBtn(ic: Ic, tint: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 24.dp, onClick: () -> Unit) {
    Box(Modifier.size(size + 20.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) { Icon(ic, size = size, tint = tint) }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val songs by Store.songs.collectAsState()
    val st by NativePlayer.state.collectAsState()
    val busy by Store.busy.collectAsState()
    val awake by Prefs.keepAwake.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var full by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    val cur = songs.firstOrNull { it.id == st.id }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(awake) {
        val w = (ctx as? Activity)?.window ?: return@LaunchedEffect
        if (awake) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(cur?.id) {            // the lock-screen / notification picture
        val c = cur ?: return@LaunchedEffect
        val bytes = if (c.cover.isEmpty()) null else withContext(Dispatchers.IO) { try { File(Store.app.filesDir, c.cover).readBytes() } catch (_: Exception) { null } }
        NativePlayer.artwork(c.id, bytes)
    }
    LaunchedEffect(Unit) { while (true) { delay(5000); Ctl.save() } }
    val tabs = listOf("Songs" to Ic.Note, "Albums" to Ic.Disc, "Artists" to Ic.Person, "Playlists" to Ic.List, "Get" to Ic.Download, "Settings" to Ic.Gear)
    Scaffold(
        bottomBar = {
            Column {
                if (busy.isNotEmpty()) Text(busy, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), fontSize = 12.sp)
                if (cur != null) MiniPlayer(cur, st, onOpen = { full = true })
                NavigationBar {
                    tabs.forEachIndexed { i, (t, ic) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, size = 22.dp) }, label = { Text(t, fontSize = 10.sp, maxLines = 1) })
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> SongsScreen(songs) { l, i -> Ctl.play(ctx, l, i) }
                1 -> GroupScreen("Albums", songs, { it.album }) { l, i -> Ctl.play(ctx, l, i) }
                2 -> GroupScreen("Artists", songs, { it.artist }) { l, i -> Ctl.play(ctx, l, i) }
                3 -> PlaylistsScreen(songs) { l, i -> Ctl.play(ctx, l, i) }
                4 -> GetScreen()
                else -> SettingsScreen(songs.size)
            }
        }
    }
    if (full && cur != null) {
        BackHandler { if (queueOpen) queueOpen = false else full = false }
        FullPlayer(cur, st, onClose = { full = false }, onQueue = { queueOpen = true })
        if (queueOpen) QueueScreen(st) { queueOpen = false }
    }
}

@Composable
fun Header(title: String, sub: String = "", actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = dim)
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
fun Cover(s: Song?, size: Dp, modifier: Modifier = Modifier) {
    val m = modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
    if (s != null && s.cover.isNotEmpty()) AsyncImage(File(Store.app.filesDir, s.cover), null, m, contentScale = ContentScale.Crop)
    else Box(m, contentAlignment = Alignment.Center) { Icon(Ic.Note, size = size / 2, tint = dim) }
}

@Composable
fun SongRow(s: Song, playing: Boolean, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val favs by Store.favs.collectAsState()
    val pls by Store.playlists.collectAsState()
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(s, 46.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Text(listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = dim)
        }
        if (s.id in favs) Icon(Ic.HeartFill, size = 16.dp, tint = MaterialTheme.colorScheme.primary)
        if (s.durMs > 0) Text(fmt(s.durMs), fontSize = 12.sp, color = dim, modifier = Modifier.padding(start = 8.dp))
        Box {
            IconBtn(Ic.More, dim, 20.dp) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(leadingIcon = { Icon(if (s.id in favs) Ic.HeartFill else Ic.Heart, size = 20.dp) }, text = { Text(if (s.id in favs) "Remove from favourites" else "Favourite") }, onClick = { Store.toggleFav(s.id); menu = false })
                DropdownMenuItem(leadingIcon = { Icon(Ic.List, size = 20.dp) }, text = { Text("Add to playlist…") }, onClick = { menu = false; pick = true })
                DropdownMenuItem(leadingIcon = { Icon(Ic.Info, size = 20.dp) }, text = { Text("Song info") }, onClick = { menu = false; info = true })
                DropdownMenuItem(leadingIcon = { Icon(Ic.Trash, size = 20.dp) }, text = { Text("Delete from this phone") }, onClick = { Store.remove(s); menu = false })
            }
        }
    }
    if (pick) AlertDialog(onDismissRequest = { pick = false }, confirmButton = { TextButton(onClick = { pick = false }) { Text("Close") } },
        title = { Text("Add to playlist") },
        text = { Column { if (pls.isEmpty()) Text("Make a playlist in the Playlists tab first."); pls.forEach { p -> TextButton(onClick = { Store.addToPlaylist(p.id, s.id); pick = false }) { Text(p.name) } } } })
    if (info) {
        val f = File(Store.app.filesDir, s.path)
        AlertDialog(onDismissRequest = { info = false }, confirmButton = { TextButton(onClick = { info = false }) { Text("Close") } }, title = { Text(s.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Artist: ${s.artist}"); Text("Album: ${s.album}"); if (s.track > 0) Text("Track: ${s.track}")
                    Text("Length: ${fmt(s.durMs)}"); Text("Type: ${s.path.substringAfterLast('.').uppercase()} · ${"%.1f".format(f.length() / 1048576.0)} MB")
                    Text("Added: ${DateFormat.getDateInstance().format(Date(s.added))}"); Text(s.path, fontSize = 11.sp, color = dim)
                }
            })
    }
}

@Composable
fun SongsScreen(songs: List<Song>, onPlay: (List<Song>, Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    var onlyFav by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val sort by Prefs.sort.collectAsState()
    val favs by Store.favs.collectAsState()
    val st by NativePlayer.state.collectAsState()
    val list = remember(songs, q, sort, onlyFav, favs) {
        val f = songs.filter { (!onlyFav || it.id in favs) && (q.isBlank() || it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)) }
        when (sort) {
            1 -> f.sortedBy { it.title.lowercase() }
            2 -> f.sortedWith(compareBy({ it.album.lowercase() }, { it.track }, { it.title.lowercase() }))
            3 -> f.sortedByDescending { it.added }
            4 -> f.sortedByDescending { it.durMs }
            else -> f.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.track }, { it.title.lowercase() }))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Songs", "${list.size} songs · ${fmt(list.sumOf { it.durMs })} total") {
            Box {
                IconBtn(Ic.Sort) { sortMenu = true }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    Prefs.SORTS.forEachIndexed { i, n -> DropdownMenuItem(leadingIcon = { if (i == sort) Icon(Ic.Check, size = 18.dp) else Spacer(Modifier.size(18.dp)) }, text = { Text(n) }, onClick = { Prefs.setSort(i); sortMenu = false }) }
                }
            }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), placeholder = { Text("Search your music") }, singleLine = true,
            leadingIcon = { Icon(Ic.Search, size = 20.dp, tint = dim) }, trailingIcon = { if (q.isNotEmpty()) IconBtn(Ic.Close, dim, 18.dp) { q = "" } })
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = onlyFav, onClick = { onlyFav = !onlyFav }, label = { Text("Favourites") })
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (list.isNotEmpty()) onPlay(list, 0) }, enabled = list.isNotEmpty()) { Icon(Ic.Play, size = 18.dp, tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(6.dp)); Text("Play") }
            OutlinedButton(onClick = { if (list.isNotEmpty()) { Prefs.setShuffle(true); onPlay(list, (list.indices).random()) } }, enabled = list.isNotEmpty()) { Icon(Ic.Shuffle, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
        }
        if (songs.isEmpty()) Empty(Ic.Note, "No music yet. Add songs in Settings, or find some in Get.")
        else if (list.isEmpty()) Empty(Ic.Search, "Nothing matches")
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { s -> SongRow(s, s.id == st.id) { onPlay(list, list.indexOf(s)) } }
        }
    }
}

@Composable
fun GroupScreen(title: String, songs: List<Song>, key: (Song) -> String, onPlay: (List<Song>, Int) -> Unit) {
    var open by remember { mutableStateOf(setOf<String>()) }
    val st by NativePlayer.state.collectAsState()
    val groups = remember(songs) { songs.groupBy { key(it).ifBlank { "Unknown" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    Column(Modifier.fillMaxSize()) {
        Header(title, "${groups.size} ${title.lowercase()} · ${songs.size} songs")
        if (songs.isEmpty()) Empty(if (title == "Albums") Ic.Disc else Ic.Person, "Nothing here yet")
        LazyColumn(Modifier.fillMaxSize()) {
            groups.forEach { (name, list) ->
                val sorted = list.sortedWith(compareBy({ it.album.lowercase() }, { it.track }, { it.title.lowercase() }))
                item(key = "g-$name") {
                    Row(Modifier.fillMaxWidth().clickable { open = if (name in open) open - name else open + name }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(sorted.first(), 52.dp)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${list.size} songs · ${fmt(list.sumOf { it.durMs })}", fontSize = 12.sp, color = dim)
                        }
                        IconBtn(Ic.Play, MaterialTheme.colorScheme.primary) { onPlay(sorted, 0) }
                    }
                }
                if (name in open) items(sorted, key = { "s-" + it.id }) { s -> SongRow(s, s.id == st.id) { onPlay(sorted, sorted.indexOf(s)) } }
            }
        }
    }
}

@Composable
fun PlaylistsScreen(songs: List<Song>, onPlay: (List<Song>, Int) -> Unit) {
    val pls by Store.playlists.collectAsState()
    val favs by Store.favs.collectAsState()
    val st by NativePlayer.state.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    var making by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val byId = remember(songs) { songs.associateBy { it.id } }
    val o = open
    if (o != null) {
        BackHandler { open = null }
        val list: List<Song> = if (o == "fav") songs.filter { it.id in favs } else (pls.firstOrNull { it.id == o }?.ids ?: emptyList()).mapNotNull { byId[it] }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconBtn(Ic.Back) { open = null }
                Text(if (o == "fav") "Favourites" else pls.firstOrNull { it.id == o }?.name ?: "", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (list.isNotEmpty()) IconBtn(Ic.Play, MaterialTheme.colorScheme.primary) { onPlay(list, 0) }
                if (o != "fav") IconBtn(Ic.Trash) { Store.deletePlaylist(o); open = null }
            }
            if (list.isEmpty()) Empty(Ic.List, "Empty. Use ⋯ on a song to add it here.")
            LazyColumn { items(list, key = { it.id }) { s -> SongRow(s, s.id == st.id) { onPlay(list, list.indexOf(s)) } } }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Header("Playlists", "${pls.size} playlists") { IconBtn(Ic.Plus) { making = true } }
        LazyColumn(Modifier.weight(1f)) {
            item {
                Row(Modifier.fillMaxWidth().clickable { open = "fav" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.HeartFill, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(16.dp)); Text("Favourites · ${songs.count { it.id in favs }}")
                }
            }
            items(pls, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { open = p.id }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.List); Spacer(Modifier.width(16.dp)); Text("${p.name} · ${p.ids.size}")
                }
            }
        }
    }
    if (making) AlertDialog(onDismissRequest = { making = false },
        confirmButton = { TextButton(onClick = { Store.newPlaylist(name); name = ""; making = false }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { making = false }) { Text("Cancel") } },
        title = { Text("New playlist") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) })
}

@Composable
fun GetScreen() {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("song") }
    var results by remember { mutableStateOf(listOf<JSONObject>()) }
    var msg by remember { mutableStateOf("") }
    val dl by Downloader.items.collectAsState()
    fun run() {
        msg = "Searching…"
        scope.launch {
            try {
                val t = q.trim()
                if (t.startsWith("http")) {
                    val (title, tr) = Engine.tracks(JSONObject().put("link", t))
                    Downloader.enqueue(tr); msg = "Added ${tr.size} from “$title” to the downloads"; results = emptyList()
                } else {
                    results = Engine.search(t, type); msg = if (results.isEmpty()) "Nothing found" else ""
                }
            } catch (e: Exception) { msg = e.message ?: "Something went wrong" }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Header("Get music", "Search YouTube Music or paste a link")
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search, or paste a link") }, singleLine = true,
            leadingIcon = { Icon(Ic.Search, size = 20.dp, tint = dim) }, trailingIcon = { IconBtn(Ic.Check, MaterialTheme.colorScheme.primary, 20.dp) { if (q.isNotBlank()) run() } })
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("song" to "Songs", "album" to "Albums", "playlist" to "Playlists").forEach { (k, l) -> FilterChip(selected = type == k, onClick = { type = k }, label = { Text(l) }) }
        }
        if (msg.isNotEmpty()) Text(msg, color = dim, fontSize = 13.sp)
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.optString("kind") + it.optString("id") }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(r.optString("thumb"), null, Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(r.optString("title"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.optString("sub"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = dim)
                    }
                    IconBtn(Ic.Download, MaterialTheme.colorScheme.primary) {
                        scope.launch {
                            try {
                                val kind = r.optString("kind")
                                if (kind == "song") Downloader.enqueue(listOf(Track(r.getString("id"), r.optString("title"), r.optString("artist"), r.optString("album"), r.optString("thumb"))))
                                else { val (_, tr) = Engine.tracks(JSONObject().put("kind", kind).put("id", r.getString("id"))); Downloader.enqueue(tr) }
                            } catch (e: Exception) { msg = e.message ?: "Something went wrong" }
                        }
                    }
                }
            }
            if (dl.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloads · ${dl.count { it.done }}/${dl.size}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { Downloader.clearFinished() }) { Text("Clear finished") }
                }
            }
            items(dl, key = { "dl-" + it.track.id }) { d ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(d.track.title.ifBlank { d.track.id }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.status, fontSize = 12.sp, color = if (d.failed) MaterialTheme.colorScheme.error else dim)
                    if (!d.done && !d.failed && d.frac > 0f) LinearProgressIndicator(progress = { d.frac }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp)); content()
    }
}

@Composable
fun SwitchRow(label: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label); if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = dim) }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(count: Int) {
    val pickSongs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { Store.importSongs(it) }
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) Store.importPack(u) }
    val fade by Prefs.crossfade.collectAsState()
    val match by Prefs.beatMatch.collectAsState()
    val awake by Prefs.keepAwake.collectAsState()
    val eqOn by Prefs.eqOn.collectAsState()
    val eq by Prefs.eq.collectAsState()
    val accent by Prefs.accent.collectAsState()
    val songs by Store.songs.collectAsState()
    var mb by remember { mutableStateOf(0.0) }
    LaunchedEffect(songs.size) { mb = withContext(Dispatchers.IO) { File(Store.app.filesDir, "Songs").walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1048576.0 } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Settings")
        Section("Library") {
            Text("$count songs · ${"%.0f".format(mb)} MB on this phone", color = dim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickSongs.launch(arrayOf("audio/*")) }) { Icon(Ic.Plus, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("Add songs") }
                OutlinedButton(onClick = { pickPack.launch(arrayOf("application/zip", "application/octet-stream")) }) { Icon(Ic.Folder, size = 18.dp); Spacer(Modifier.width(6.dp)); Text("Music Pack") }
            }
        }
        Section("Playback") {
            Text("Crossfade: ${if (fade < 0.5f) "off" else "%.0f s".format(fade)}")
            Slider(value = fade, onValueChange = { Prefs.setFade(it) }, valueRange = 0f..12f)
            SwitchRow("Beat matching", "Line the beats up while crossfading", match) { Prefs.setMatch(it) }
            SwitchRow("Keep the screen on", "While this app is open", awake) { Prefs.setAwake(it) }
        }
        Section("Equalizer") {
            SwitchRow("Equalizer", "10 bands", eqOn) { Prefs.setEqOn(it) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Prefs.PRESETS.forEach { (n, g) -> FilterChip(selected = eq == g && eqOn, onClick = { Prefs.setEq(g); Prefs.setEqOn(true) }, label = { Text(n) }) }
            }
            eq.forEachIndexed { i, g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Prefs.EQ_LABELS[i], Modifier.width(36.dp), fontSize = 12.sp, color = dim)
                    Slider(value = g, onValueChange = { v -> Prefs.setEq(eq.toMutableList().also { it[i] = Math.round(v * 2f) / 2f }); if (!eqOn) Prefs.setEqOn(true) }, valueRange = -12f..12f, modifier = Modifier.weight(1f))
                    Text("%+.1f".format(g), Modifier.width(44.dp), fontSize = 12.sp, color = dim)
                }
            }
        }
        Section("Look") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Prefs.ACCENTS.forEach { c ->
                    Box(Modifier.size(38.dp).clip(CircleShape).background(Color(c)).clickable { Prefs.setAccent(c) }, contentAlignment = Alignment.Center) {
                        if (c == accent) Icon(Ic.Check, size = 20.dp, tint = Color(0xFF14101E))
                    }
                }
            }
        }
        Section("Downloads") {
            TextButton(onClick = { Downloader.clearFinished() }) { Text("Clear finished downloads") }
            Text("The hidden YouTube browser only runs while you search or download, then shuts itself down.", fontSize = 12.sp, color = dim)
        }
        Section("About") { Text("Strata Music Next is a separate native test app. It does not touch your real Strata Music or its data.", fontSize = 12.sp, color = dim) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun MiniPlayer(s: Song, st: NativePlayer.St, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).clickable { onOpen() }) {
        if (st.dur > 0) LinearProgressIndicator(progress = { (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(2.dp))
        Row(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(s, 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = dim)
            }
            IconBtn(Ic.Prev, size = 22.dp) { NativePlayer.prev() }
            IconBtn(if (st.playing) Ic.Pause else Ic.Play, MaterialTheme.colorScheme.primary, 28.dp) { NativePlayer.toggle() }
            IconBtn(Ic.Next, size = 22.dp) { NativePlayer.next() }
        }
    }
}

@Composable
fun FullPlayer(s: Song, st: NativePlayer.St, onClose: () -> Unit, onQueue: () -> Unit) {
    val ctx = LocalContext.current
    var drag by remember { mutableStateOf<Float?>(null) }
    var sleepPick by remember { mutableStateOf(false) }
    val rep by Prefs.repeat.collectAsState()
    val shuf by Prefs.shuffle.collectAsState()
    val favs by Store.favs.collectAsState()
    val sleepAt by NativePlayer.sleepAt.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepAt) { while (sleepAt > 0) { nowMs = System.currentTimeMillis(); delay(15000) } }
    val primary = MaterialTheme.colorScheme.primary
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Down, onClick = onClose)
                Text("Now playing", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = dim)
                IconBtn(Ic.Queue, onClick = onQueue)
            }
            Spacer(Modifier.height(8.dp))
            Cover(s, 320.dp, Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBtn(if (s.id in favs) Ic.HeartFill else Ic.Heart, if (s.id in favs) primary else MaterialTheme.colorScheme.onSurface) { Store.toggleFav(s.id) }
            }
            Spacer(Modifier.height(8.dp))
            val frac = if (st.dur > 0) (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) else 0f
            Slider(value = drag ?: frac, onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { NativePlayer.seek((it * st.dur).toLong()) }; drag = null })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(fmt(((drag ?: frac) * st.dur).toLong()), fontSize = 12.sp, color = dim); Text(fmt(st.dur), fontSize = 12.sp, color = dim) }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Shuffle, if (shuf) primary else dim) { Ctl.toggleShuffle(ctx) }
                IconBtn(Ic.Prev, size = 30.dp) { NativePlayer.prev() }
                Box(Modifier.size(68.dp).clip(CircleShape).background(primary).clickable { NativePlayer.toggle() }, contentAlignment = Alignment.Center) {
                    Icon(if (st.playing) Ic.Pause else Ic.Play, size = 34.dp, tint = MaterialTheme.colorScheme.onPrimary)
                }
                IconBtn(Ic.Next, size = 30.dp) { NativePlayer.next() }
                IconBtn(if (rep == 2) Ic.RepeatOne else Ic.Repeat, if (rep > 0) primary else dim) { Ctl.cycleRepeat() }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Timer, if (sleepAt > 0) primary else dim) { sleepPick = true }
                if (sleepAt > 0) Text("${((sleepAt - nowMs) / 60000 + 1).coerceAtLeast(1)} min", fontSize = 12.sp, color = primary)
            }
        }
    }
    if (sleepPick) AlertDialog(onDismissRequest = { sleepPick = false }, confirmButton = { TextButton(onClick = { sleepPick = false }) { Text("Close") } },
        title = { Text("Sleep timer") },
        text = {
            Column {
                listOf(0 to "Off", 5 to "5 minutes", 10 to "10 minutes", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour").forEach { (m, l) ->
                    TextButton(onClick = { NativePlayer.sleep(m); sleepPick = false }) { Text(l) }
                }
            }
        })
}

@Composable
fun QueueScreen(st: NativePlayer.St, onClose: () -> Unit) {
    val q by Ctl.queue.collectAsState()
    val ls = rememberLazyListState(initialFirstVisibleItemIndex = (st.index - 2).coerceAtLeast(0))
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                IconBtn(Ic.Back, onClick = onClose)
                Text("Up next · ${q.size} songs", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            LazyColumn(state = ls) {
                itemsIndexed(q, key = { i, s -> "$i-${s.id}" }) { i, s ->
                    Row(Modifier.fillMaxWidth().clickable { NativePlayer.jump(i) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { if (i == st.index) Icon(Ic.Play, size = 16.dp, tint = MaterialTheme.colorScheme.primary) else Text("${i + 1}", fontSize = 12.sp, color = dim) }
                        Cover(s, 40.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (i == st.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = dim)
                        }
                        Text(fmt(s.durMs), fontSize = 12.sp, color = dim)
                    }
                }
            }
        }
    }
}
