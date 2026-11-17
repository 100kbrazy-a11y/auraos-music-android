@file:OptIn(ExperimentalMaterial3Api::class)

package com.auraos.music.next

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auraos.music.NativePlayer
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Strata Music Next: the all-native app (Kotlin + Compose). The WebView app is untouched; this is a separate test app. */
class NextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        NativePlayer.start(this)
        setContent { Theme { App() } }
    }
}

@Composable
fun Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB794F6), onPrimary = Color(0xFF1A1030), background = Color(0xFF0B0B0E),
            surface = Color(0xFF14141A), onSurface = Color(0xFFEDEDF2), onBackground = Color(0xFFEDEDF2),
            surfaceVariant = Color(0xFF1E1E26), onSurfaceVariant = Color(0xFFA8A8B8),
        ),
    ) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
}

private var queue: List<Song> = emptyList()

private fun play(ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, list: List<Song>, index: Int, shuffle: Boolean = false) {
    var l = list; var i = index
    if (shuffle && l.isNotEmpty()) { val first = l[i]; l = listOf(first) + (l - first).shuffled(); i = 0 }
    queue = l
    scope.launch { NativePlayer.load(ctx, l.map { NativePlayer.Item(it.id, it.path, it.title, it.artist, it.album) }, i) }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by Store.songs.collectAsState()
    val st by NativePlayer.state.collectAsState()
    val busy by Store.busy.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var full by remember { mutableStateOf(false) }
    val cur = songs.firstOrNull { it.id == st.id }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val tabs = listOf("Songs", "Albums", "Artists", "Playlists", "Get", "Settings")
    Scaffold(
        bottomBar = {
            Column {
                if (busy.isNotEmpty()) Text(busy, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp), fontSize = 12.sp)
                if (cur != null) MiniPlayer(cur, st, onOpen = { full = true })
                NavigationBar {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("♪", "◉", "☺", "☰", "⬇", "⚙")[i]) }, label = { Text(t, fontSize = 10.sp, maxLines = 1) })
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> SongsScreen(songs) { l, i -> play(ctx, scope, l, i) }
                1 -> GroupScreen(songs, { it.album }) { l, i -> play(ctx, scope, l, i) }
                2 -> GroupScreen(songs, { it.artist }) { l, i -> play(ctx, scope, l, i) }
                3 -> PlaylistsScreen(songs) { l, i -> play(ctx, scope, l, i) }
                4 -> GetScreen()
                else -> SettingsScreen(songs.size)
            }
        }
    }
    if (full && cur != null) {
        BackHandler { full = false }
        FullPlayer(cur, st, onClose = { full = false }, onShuffle = { play(ctx, scope, queue.ifEmpty { songs }, queue.indexOfFirst { it.id == cur.id }.coerceAtLeast(0), shuffle = true) })
    }
}

@Composable
fun Cover(s: Song?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val m = modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
    if (s != null && s.cover.isNotEmpty()) AsyncImage(File(Store.app.filesDir, s.cover), null, m, contentScale = ContentScale.Crop)
    else Box(m, contentAlignment = Alignment.Center) { Text("♪", color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
fun SongRow(s: Song, playing: Boolean, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    val favs by Store.favs.collectAsState()
    val pls by Store.playlists.collectAsState()
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(s, 46.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Text(listOf(s.artist, s.album).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋯", fontSize = 20.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (s.id in favs) "Remove from favourites" else "Favourite") }, onClick = { Store.toggleFav(s.id); menu = false })
                DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { menu = false; pick = true })
                DropdownMenuItem(text = { Text("Delete from this phone") }, onClick = { Store.remove(s); menu = false })
            }
        }
    }
    if (pick) AlertDialog(onDismissRequest = { pick = false }, confirmButton = { TextButton(onClick = { pick = false }) { Text("Close") } },
        title = { Text("Add to playlist") },
        text = { Column { if (pls.isEmpty()) Text("Make a playlist in the Playlists tab first."); pls.forEach { p -> TextButton(onClick = { Store.addToPlaylist(p.id, s.id); pick = false }) { Text(p.name) } } } })
}

@Composable
fun SongsScreen(songs: List<Song>, onPlay: (List<Song>, Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    val st by NativePlayer.state.collectAsState()
    val list = remember(songs, q) {
        val f = songs.filter { q.isBlank() || it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
        f.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.track }, { it.title.lowercase() }))
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), placeholder = { Text("Search your music") }, singleLine = true)
        if (songs.isEmpty()) Text("No music yet. Add songs in Settings, or find some in Get.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { s -> SongRow(s, s.id == st.id) { onPlay(list, list.indexOf(s)) } }
        }
    }
}

@Composable
fun GroupScreen(songs: List<Song>, key: (Song) -> String, onPlay: (List<Song>, Int) -> Unit) {
    var open by remember { mutableStateOf(setOf<String>()) }
    val st by NativePlayer.state.collectAsState()
    val groups = remember(songs) { songs.groupBy { key(it).ifBlank { "Unknown" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { (name, list) ->
            val sorted = list.sortedWith(compareBy({ it.album.lowercase() }, { it.track }, { it.title.lowercase() }))
            item(key = "g-$name") {
                Row(Modifier.fillMaxWidth().clickable { open = if (name in open) open - name else open + name }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(sorted.first(), 52.dp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${list.size} songs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onPlay(sorted, 0) }) { Text("▶ Play") }
                }
            }
            if (name in open) items(sorted, key = { "s-" + it.id }) { s -> SongRow(s, s.id == st.id) { onPlay(sorted, sorted.indexOf(s)) } }
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
    if (open != null) {
        BackHandler { open = null }
        val list: List<Song> = if (open == "fav") songs.filter { it.id in favs } else (pls.firstOrNull { it.id == open }?.ids ?: emptyList()).mapNotNull { byId[it] }
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open = null }) { Text("← Back") }
                if (list.isNotEmpty()) TextButton(onClick = { onPlay(list, 0) }) { Text("▶ Play") }
                if (open != "fav") TextButton(onClick = { Store.deletePlaylist(open!!); open = null }) { Text("Delete playlist") }
            }
            LazyColumn { items(list, key = { it.id }) { s -> SongRow(s, s.id == st.id) { onPlay(list, list.indexOf(s)) } } }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item { Text("♥ Favourites · ${songs.count { it.id in favs }}", Modifier.fillMaxWidth().clickable { open = "fav" }.padding(16.dp)) }
            items(pls, key = { it.id }) { p -> Text("${p.name} · ${p.ids.size}", Modifier.fillMaxWidth().clickable { open = p.id }.padding(16.dp)) }
        }
        Button(onClick = { making = true }, Modifier.padding(12.dp)) { Text("New playlist") }
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
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search YouTube Music, or paste a link") }, singleLine = true)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("song" to "Songs", "album" to "Albums", "playlist" to "Playlists").forEach { (k, l) -> FilterChip(selected = type == k, onClick = { type = k }, label = { Text(l) }) }
            Button(onClick = { run() }, enabled = q.isNotBlank()) { Text("Go") }
        }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.optString("kind") + it.optString("id") }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(r.optString("thumb"), null, Modifier.size(46.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(r.optString("title"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.optString("sub"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                val kind = r.optString("kind")
                                if (kind == "song") Downloader.enqueue(listOf(Track(r.getString("id"), r.optString("title"), r.optString("artist"), r.optString("album"), r.optString("thumb"))))
                                else { val (_, tr) = Engine.tracks(JSONObject().put("kind", kind).put("id", r.getString("id"))); Downloader.enqueue(tr) }
                            } catch (e: Exception) { msg = e.message ?: "Something went wrong" }
                        }
                    }) { Text("⬇") }
                }
            }
            if (dl.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloads", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { Downloader.clearFinished() }) { Text("Clear finished") }
                }
            }
            items(dl, key = { "dl-" + it.track.id }) { d ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(d.track.title.ifBlank { d.track.id }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.status, fontSize = 12.sp, color = if (d.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!d.done && !d.failed && d.frac > 0f) LinearProgressIndicator(progress = { d.frac }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(count: Int) {
    val pickSongs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { Store.importSongs(it) }
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) Store.importPack(u) }
    var fade by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$count songs in this app", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { pickSongs.launch(arrayOf("audio/*")) }) { Text("Add songs from this phone") }
        Button(onClick = { pickPack.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Import a Music Pack (.zip)") }
        Text("Crossfade: ${if (fade < 0.5f) "off" else "%.0f s".format(fade)}")
        Slider(value = fade, onValueChange = { fade = it; NativePlayer.crossfade(if (it < 0.5f) 0f else it) }, valueRange = 0f..12f)
        Text("Strata Music Next is a separate test app: it does not touch your real Strata Music or its data.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MiniPlayer(s: Song, st: NativePlayer.St, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).clickable { onOpen() }) {
        if (st.dur > 0) LinearProgressIndicator(progress = { (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(2.dp))
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(s, 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { NativePlayer.prev() }) { Text("⏮") }
            TextButton(onClick = { NativePlayer.toggle() }) { Text(if (st.playing) "⏸" else "▶", fontSize = 20.sp) }
            TextButton(onClick = { NativePlayer.next() }) { Text("⏭") }
        }
    }
}

private fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun FullPlayer(s: Song, st: NativePlayer.St, onClose: () -> Unit, onShuffle: () -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var rep by remember { mutableStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = onClose, Modifier.align(Alignment.Start)) { Text("⌄ Close") }
            Cover(s, 300.dp, Modifier.aspectRatio(1f))
            Spacer(Modifier.height(24.dp))
            Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(s.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            val frac = if (st.dur > 0) (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) else 0f
            Slider(value = drag ?: frac, onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { NativePlayer.seek((it * st.dur).toLong()) }; drag = null })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(fmt(st.pos), fontSize = 12.sp); Text(fmt(st.dur), fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onShuffle) { Text("🔀") }
                TextButton(onClick = { NativePlayer.prev() }) { Text("⏮", fontSize = 26.sp) }
                Button(onClick = { NativePlayer.toggle() }) { Text(if (st.playing) "⏸" else "▶", fontSize = 26.sp) }
                TextButton(onClick = { NativePlayer.next() }) { Text("⏭", fontSize = 26.sp) }
                TextButton(onClick = { rep = (rep + 1) % 3; NativePlayer.repeat(rep) }) { Text(listOf("🔁 off", "🔁 all", "🔂 one")[rep], fontSize = 12.sp) }
            }
        }
    }
}
