@file:OptIn(ExperimentalFoundationApi::class)

package com.auraos.music.next

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** YouTube Music search / links / artists. Its state lives here so it survives leaving the page. */
object Yt {
    val mode = mutableStateOf(false)
    val q = mutableStateOf("")
    val type = mutableStateOf("song")
    val results = mutableStateOf(listOf<JSONObject>())
    val msg = mutableStateOf("")
    val artist = mutableStateOf<JSONObject?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun fail(e: Exception) { msg.value = e.message ?: "Something went wrong" }

    fun run() {
        val t = q.value.trim(); if (t.isEmpty()) return
        msg.value = if (t.startsWith("http")) "Reading the link…" else "Searching YouTube Music…"; artist.value = null
        scope.launch {
            try {
                if (t.startsWith("http")) {
                    val ch = Regex("/channel/(UC[\\w-]{20,})").find(t)?.groupValues?.get(1)
                    if (ch != null) { openArtist(ch); return@launch }
                    val (title, tr) = Engine.tracks(JSONObject().put("link", t))
                    Downloader.enqueue(tr); msg.value = "Adding ${plural(tr.size, "song")} from “$title”"; results.value = emptyList()
                } else { results.value = Engine.search(t, type.value); msg.value = if (results.value.isEmpty()) "Nothing found" else "" }
            } catch (e: Exception) { fail(e) }
        }
    }
    /** a shared link (YouTube › Share › Strata Music) */
    fun link(text: String) { val u = Regex("https?://\\S+").find(text)?.value ?: return; mode.value = true; q.value = u; run() }
    fun openArtist(id: String) { msg.value = "Reading the artist…"; scope.launch { try { artist.value = Engine.artist(id); msg.value = "" } catch (e: Exception) { fail(e) } } }
    fun findArtist(name: String, channel: String) {
        mode.value = true
        if (channel.startsWith("UC")) { openArtist(channel); return }
        q.value = name; type.value = "artist"; msg.value = "Searching YouTube Music…"; artist.value = null
        scope.launch {
            try {
                val r = Engine.search(name, "artist")
                val hit = r.firstOrNull { it.optString("title").equals(name, true) } ?: r.firstOrNull()
                if (hit != null) openArtist(hit.getString("id")) else { results.value = r; msg.value = "Nothing found" }
            } catch (e: Exception) { fail(e) }
        }
    }
    fun saveArtist(ar: JSONObject) {
        Store.scope.launch {
            val pic = try { ar.optString("thumb").takeIf { it.isNotBlank() }?.let { Net.get(it.replace(Regex("=w\\d+-h\\d+"), "=w1000-h1000")) } } catch (_: Exception) { null }
            Store.setArtistInfo(ar.optString("name"), mapOf("channel" to ar.optString("id"), "synced" to System.currentTimeMillis().toString()), pic)
        }
    }
    fun addAlbum(id: String, who: String, title: String) {
        scope.launch { try { val (_, tr) = Engine.tracks(JSONObject().put("kind", "album").put("id", id).put("artist", who)); Downloader.enqueue(tr); Toasts.show("Adding “$title” (${plural(tr.size, "song")})") } catch (e: Exception) { fail(e) } }
    }
    fun addAll(ar: JSONObject, singles: Boolean) {
        val name = ar.optString("name"); saveArtist(ar)
        val albums = list(ar.optJSONArray("albums")).filter { singles || !it.optBoolean("single") }
        scope.launch {
            albums.forEachIndexed { i, r ->
                msg.value = "Reading ${r.optString("title")} (${i + 1} of ${albums.size})…"
                try { val (_, tr) = Engine.tracks(JSONObject().put("kind", "album").put("id", r.getString("id")).put("artist", name)); Downloader.enqueue(tr) } catch (_: Exception) { }
            }
            msg.value = "Everything by $name is on its way"
        }
    }
    fun list(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
}

@Composable
private fun YtRow(thumb: String, title: String, sub: String, round: Boolean = false, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(start = 18.dp, end = 8.dp).height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(thumb, null, Modifier.size(48.dp).clip(if (round) CircleShape else RoundedCornerShape(9.dp)).background(C.surface2), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f)) { T(title, 15.sp, W650); T(sub, 13.sp, color = C.sub) }
        trailing()
    }
}

fun ytResults(scope: LazyListScope) = with(scope) {
    item {
        val type by Yt.type
        Row(Modifier.padding(start = 18.dp, bottom = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("song" to "Songs", "album" to "Albums", "artist" to "Artists", "playlist" to "Playlists").forEach { (k, l) -> Chip(l, type == k) { Yt.type.value = k; if (Yt.q.value.isNotBlank() && !Yt.q.value.startsWith("http")) Yt.run() } }
        }
        val msg by Yt.msg
        if (msg.isNotEmpty()) T(msg, 13.sp, color = C.sub, lines = 2, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp))
    }
    item { Downloads() }
    item {
        val ar = Yt.artist.value
        if (ar != null) {
            val name = ar.optString("name"); val albums = Yt.list(ar.optJSONArray("albums")); val top = Yt.list(ar.optJSONArray("top"))
            Column {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsyncImage(ar.optString("thumb"), null, Modifier.size(84.dp).clip(CircleShape).background(C.surface2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f)) { T(name, 22.sp, W800, lines = 2); T("${albums.count { !it.optBoolean("single") }} albums · ${albums.count { it.optBoolean("single") }} singles & EPs", 13.sp, color = C.sub) }
                    IB(Ic.Close, C.sub) { Yt.artist.value = null }
                }
                Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Everything", Ic.Download, modifier = Modifier.weight(1f), enabled = albums.isNotEmpty()) { Yt.addAll(ar, true) }
                    Btn("Albums", tonal = true, modifier = Modifier.weight(1f), enabled = albums.isNotEmpty()) { Yt.addAll(ar, false) }
                    Btn("Top", tonal = true, modifier = Modifier.weight(1f), enabled = top.isNotEmpty()) { Yt.saveArtist(ar); Downloader.enqueue(top.map { Track(it.getString("id"), it.optString("title"), name, it.optString("album"), it.optString("thumb")) }) }
                }
                for (r in albums) YtRow(r.optString("thumb"), r.optString("title"), listOf(if (r.optBoolean("single")) "Single / EP" else "Album", r.optString("year")).filter { it.isNotBlank() }.joinToString(" · ")) {
                    IB(Ic.Download, accent) { Yt.saveArtist(ar); Yt.addAlbum(r.getString("id"), name, r.optString("title")) }
                }
            }
        }
    }
    item {
        if (Yt.artist.value == null) for (r in Yt.results.value) {
            val kind = r.optString("kind")
            YtRow(r.optString("thumb"), r.optString("title"), r.optString("sub"), kind == "artist", if (kind == "artist") ({ Yt.openArtist(r.getString("id")) }) else null) {
                if (kind == "artist") Icon(Ic.Back, Modifier.rotate(180f), tint = C.sub)
                else {
                    if (kind == "song") IB(Ic.Spark, C.sub, 20.dp) {
                        Store.scope.launch { try { val tr = Engine.radio(JSONObject().put("id", r.getString("id"))); Downloader.enqueue(listOf(Track(r.getString("id"), r.optString("title"), r.optString("artist"), r.optString("album"), r.optString("thumb"))) + tr, play = true); Toasts.show("Mix: ${tr.size + 1} songs on the way") } catch (e: Exception) { Toasts.show(e.message ?: "Radio failed") } }
                    }
                    IB(Ic.Download, accent) {
                        if (kind == "song") Downloader.enqueue(listOf(Track(r.getString("id"), r.optString("title"), r.optString("artist"), r.optString("album"), r.optString("thumb"))))
                        else Store.scope.launch { try { val (_, tr) = Engine.tracks(JSONObject().put("kind", kind).put("id", r.getString("id")).put("artist", r.optString("artist"))); Downloader.enqueue(tr) } catch (e: Exception) { Toasts.show(e.message ?: "Failed") } }
                    }
                }
            }
        }
        T("Only add music you have the right to.", 12.sp, color = C.faint, modifier = Modifier.padding(18.dp))
    }
}

@Composable
fun Downloads() {
    val dl by Downloader.items.collectAsState()
    if (dl.isEmpty()) return
    val a = accent
    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp)).background(C.surface).padding(vertical = 6.dp)) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            T("DOWNLOADS · ${dl.count { it.done }} OF ${dl.size}", 13.sp, W750, a, spacing = 0.3.sp, modifier = Modifier.weight(1f))
            if (dl.any { !it.done && !it.failed }) TextBtn("Stop") { Downloader.stop() }
            if (dl.any { it.failed }) TextBtn("Retry") { Downloader.retryFailed() }
            TextBtn("Clear") { Downloader.clearFinished() }
        }
        for (d in dl.takeLast(30)) Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
            T(d.track.title.ifBlank { d.track.id }, 15.sp, W650)
            T(listOf(d.track.artist, d.status).filter { it.isNotBlank() }.joinToString(" · "), 13.sp, color = if (d.failed) C.danger else C.sub, lines = 2)
            if (!d.done && !d.failed && d.frac > 0f) Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.surface3)) { Box(Modifier.fillMaxHeight().fillMaxWidth(d.frac).background(a)) }
        }
    }
}
@Composable fun TextBtn(t: String, onClick: () -> Unit) = Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 8.dp)) { T(t, 14.sp, W700, accent) }

/** Add Music: songs, a folder, a music pack, a link / YouTube Music */
@Composable
fun AddMusicSheet() {
    val pickSongs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) Store.importSongs(it) { a, s -> Toasts.show("Added ${plural(a, "song")}" + if (s > 0) " · $s were already here" else "") } }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) Store.importFolder(u) }
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) Store.importPack(u) }
    SheetScroll {
        Row(Modifier.padding(start = 24.dp, end = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Icon(Ic.Plus); Column { T("Add Music", 15.sp, W750); T("Choose how to add music", 13.sp, color = C.sub) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line)); Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().clickable { pickSongs.launch(arrayOf("audio/*")) }.padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) { Icon(Ic.Note, tint = C.sub); Column { T("Add Songs", 15.5.sp, W550); T("Pick music files from your phone", 13.sp, color = C.sub) } }
        Row(Modifier.fillMaxWidth().clickable { pickFolder.launch(null) }.padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) { Icon(Ic.Folder, tint = C.sub); Column { T("Add a Folder", 15.5.sp, W550); T("Everything in a folder and its sub-folders", 13.sp, color = C.sub) } }
        Row(Modifier.fillMaxWidth().clickable { pickPack.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }.padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) { Icon(Ic.Pack, tint = C.sub); Column { T("Add Music Pack", 15.5.sp, W550); T("Add a …-musicpack.zip someone shared with you", 13.sp, color = C.sub) } }
        Act(Ic.Globe, "Add from Link", "Paste a YouTube or YouTube Music song, album, playlist or artist link") {
            Dialogs.show {
                var t by remember { mutableStateOf("") }
                DTitle("Add from Link"); DText("Paste a YouTube or YouTube Music link. Only add music you have the right to.")
                Field(t, { t = it }, "https://music.youtube.com/…")
                DButtons("Cancel" to { Dialogs.close() }, "Add" to { Dialogs.close(); Nav.open(Page.Search); Yt.mode.value = true; Yt.q.value = t.trim(); Yt.run() })
            }
        }
        Act(Ic.Search, "Search YouTube Music", "Find songs, albums, playlists and artists") { Nav.open(Page.Search); Yt.mode.value = true }
        Act(Ic.Spark, "Bring Over from Strata Music", if (Migrate.done()) "Done · run again to pick up anything new" else "Your music, playlists and settings from the older app") { Store.scope.launch { Toasts.show(Migrate.run(force = true)) } }
    }
}
