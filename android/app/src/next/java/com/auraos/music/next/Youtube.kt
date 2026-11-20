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

/** what a YouTube Music result can do: play straight away (it downloads, and starts the moment it lands), queue, or just add */
object YtAct {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    fun trackOf(r: JSONObject) = Track(r.getString("id"), r.optString("title"), r.optString("artist"), r.optString("album"), r.optString("thumb"))
    fun have(id: String): Song? = Store.songs.value.firstOrNull { it.j.optString("yt") == id }
    private fun said(how: String, what: String) = Toasts.show(when (how) { "now" -> "Getting $what — it plays the moment it's ready"; "next" -> "$what plays next once it's ready"; "queue" -> "$what joins the queue once it's ready"; else -> "Adding $what" })

    /** how: now · next · queue · add */
    fun act(ctx: android.content.Context, r: JSONObject, how: String) {
        when (r.optString("kind")) {
            "song" -> song(ctx, r, how)
            "artist" -> scope.launch {
                try {
                    val ar = Engine.artist(r.getString("id")); val name = ar.optString("name"); val top = Yt.list(ar.optJSONArray("top"))
                    if (how == "add") { Yt.addAll(ar, true); return@launch }
                    if (top.isEmpty()) { Toasts.show("No songs found for $name"); return@launch }
                    Yt.saveArtist(ar); said(how, "$name's top songs")
                    Downloader.enqueue(top.map { Track(it.getString("id"), it.optString("title"), name, it.optString("album"), it.optString("thumb")) }, how = how.takeIf { it != "next" } ?: "queue")
                } catch (e: Exception) { Toasts.show(e.message ?: "Couldn't read the artist") }
            }
            else -> scope.launch {
                try {
                    val (_, tr) = Engine.tracks(JSONObject().put("kind", r.optString("kind")).put("id", r.getString("id")).put("artist", r.optString("artist")))
                    if (tr.isEmpty()) { Toasts.show("Nothing to add"); return@launch }
                    if (how == "add") { Downloader.enqueue(tr); Toasts.show("Adding “${r.optString("title")}” (${plural(tr.size, "song")})") }
                    else { said(how, "“${r.optString("title")}”"); Downloader.enqueue(tr, how = how.takeIf { it != "next" } ?: "queue") }
                } catch (e: Exception) { Toasts.show(e.message ?: "Couldn't read that") }
            }
        }
    }
    private fun song(ctx: android.content.Context, r: JSONObject, how: String) {
        val mine = have(r.getString("id"))
        if (mine != null) {
            when (how) { "now" -> Ctl.play(ctx, listOf(mine), 0, "YouTube Music"); "next" -> Ctl.playNext(ctx, listOf(mine)); "queue" -> Ctl.addToQueue(ctx, listOf(mine)); else -> Toasts.show("Already in your library") }
            return
        }
        said(how, "“${r.optString("title")}”")
        Downloader.enqueue(listOf(trackOf(r)), how = if (how == "add") "" else how)
    }
    fun radio(r: JSONObject) {
        Store.scope.launch {
            try {
                val tr = Engine.radio(JSONObject().put("id", r.getString("id")))
                Downloader.enqueue(listOf(trackOf(r)) + tr, how = "now"); Toasts.show("Mix: ${tr.size + 1} songs on the way")
            } catch (e: Exception) { Toasts.show(e.message ?: "Radio failed") }
        }
    }
}

/** the ⋯ menu for a result: the same options as a song / album / artist of your own, for things you haven't added yet */
@Composable
private fun YtMenu(r: JSONObject) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val kind = r.optString("kind"); val round = kind == "artist"
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AsyncImage(r.optString("thumb"), null, Modifier.size(52.dp).clip(if (round) CircleShape else RoundedCornerShape(10.dp)).background(C.surface2), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f)) { T(r.optString("title"), 15.sp, W750); T(r.optString("sub"), 13.sp, color = C.sub) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.line)); Spacer(Modifier.height(6.dp))
    SheetScroll {
        val mine = kind == "song" && YtAct.have(r.optString("id")) != null
        Act(Ic.Play, if (kind == "artist") "Play Top Songs" else "Play") { YtAct.act(ctx, r, "now") }
        if (kind == "song") Act(Ic.PlayNext, "Play Next") { YtAct.act(ctx, r, "next") }
        Act(Ic.AddQ, "Add to Queue") { YtAct.act(ctx, r, "queue") }
        Act(Ic.Download, when (kind) { "artist" -> "Download Everything"; "song" -> if (mine) "In Your Library" else "Add to Library"; else -> "Add to Library" }, if (mine) "Already downloaded" else "") { YtAct.act(ctx, r, "add") }
        if (kind == "song") Act(Ic.Spark, "Start Radio", "A mix of similar songs") { YtAct.radio(r) }
        if (kind == "artist") Act(Ic.Person, "Open Artist") { Yt.openArtist(r.getString("id")) }
        else if (r.optString("artist").isNotBlank()) Act(Ic.Person, "Go to Artist") { Yt.findArtist(primaryArtist(r.optString("artist")), "") }
    }
}

/** the round play button and the ⋯ that end every result row */
@Composable
private fun RowScope.YtEnd(r: JSONObject) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val a = accent
    Box(Modifier.size(38.dp).clip(CircleShape).background(mix(a, 0.22f, C.surface2)).clickable { Hap.tap(); YtAct.act(ctx, r, "now") }, contentAlignment = Alignment.Center) { Icon(Ic.Play, size = 18.dp, tint = a) }
    IB(Ic.More, C.sub, 20.dp) { Dialogs.sheet { YtMenu(r) } }
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
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val name = ar.optString("name"); val albums = Yt.list(ar.optJSONArray("albums")); val top = Yt.list(ar.optJSONArray("top"))
            val pic = ar.optString("thumb").ifBlank { top.firstOrNull()?.optString("thumb") ?: albums.firstOrNull()?.optString("thumb") ?: "" }
            Column {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsyncImage(pic.replace(Regex("=w\\d+-h\\d+"), "=w600-h600"), null, Modifier.size(84.dp).clip(CircleShape).background(C.surface2), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f)) { T(name, 22.sp, W800, lines = 2); T("${albums.count { !it.optBoolean("single") }} albums · ${albums.count { it.optBoolean("single") }} singles & EPs", 13.sp, color = C.sub) }
                    IB(Ic.Close, C.sub) { Yt.artist.value = null }
                }
                Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("All", Ic.Download, modifier = Modifier.weight(1f), enabled = albums.isNotEmpty()) { Yt.addAll(ar, true) }
                    Btn("Albums", tonal = true, modifier = Modifier.weight(1f), enabled = albums.isNotEmpty()) { Yt.addAll(ar, false) }
                    Btn("Top", tonal = true, modifier = Modifier.weight(1f), enabled = top.isNotEmpty()) { Yt.saveArtist(ar); Downloader.enqueue(top.map { Track(it.getString("id"), it.optString("title"), name, it.optString("album"), it.optString("thumb")) }) }
                }
                if (top.isNotEmpty()) {
                    T("Top songs", 18.sp, W800, modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 2.dp))
                    for (t in top.take(10)) {
                        val sr = JSONObject(t.toString()).put("kind", "song").put("sub", listOf(name, t.optString("album")).filter { it.isNotBlank() }.joinToString(" · ")).put("artist", name)
                        YtRow(t.optString("thumb"), t.optString("title"), sr.optString("sub"), onClick = { YtAct.act(ctx, sr, "now") }) { YtEnd(sr) }
                    }
                }
                T("Albums & singles", 18.sp, W800, modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 2.dp))
                for (r in albums) {
                    val ar2 = JSONObject(r.toString()).put("kind", "album").put("artist", name).put("sub", listOf(if (r.optBoolean("single")) "Single / EP" else "Album", r.optString("year")).filter { it.isNotBlank() }.joinToString(" · "))
                    YtRow(r.optString("thumb"), r.optString("title"), ar2.optString("sub"), onClick = { YtAct.act(ctx, ar2, "now") }) { YtEnd(ar2) }
                }
            }
        }
    }
    item {
        if (Yt.artist.value == null) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            for (r in Yt.results.value) {
                val kind = r.optString("kind")
                YtRow(r.optString("thumb"), r.optString("title"), r.optString("sub"), kind == "artist", if (kind == "artist") ({ Yt.openArtist(r.getString("id")) }) else ({ YtAct.act(ctx, r, "now") })) { YtEnd(r) }
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
