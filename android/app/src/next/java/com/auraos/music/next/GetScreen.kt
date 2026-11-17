@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.auraos.music.next

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The Get tab's state lives here, so it survives switching tabs (and a YouTube link shared into the app lands here) */
object GetState {
    val q = mutableStateOf("")
    val type = mutableStateOf("song")
    val results = mutableStateOf(listOf<JSONObject>())
    val msg = mutableStateOf("")
    val artist = mutableStateOf<JSONObject?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun fail(e: Exception) { msg.value = e.message ?: "Something went wrong" }

    fun run() {
        val t = q.value.trim(); if (t.isEmpty()) return
        msg.value = if (t.startsWith("http")) "Reading the link…" else "Searching…"; artist.value = null
        scope.launch {
            try {
                if (t.startsWith("http")) {
                    val ch = Regex("/channel/(UC[\\w-]{20,})").find(t)?.groupValues?.get(1)
                    if (ch != null) { openArtist(ch); return@launch }
                    val (title, tr) = Engine.tracks(JSONObject().put("link", t))
                    Downloader.enqueue(tr); msg.value = "Added ${plural(tr.size, "song")} from “$title” to the downloads"; results.value = emptyList()
                } else { results.value = Engine.search(t, type.value); msg.value = if (results.value.isEmpty()) "Nothing found" else "" }
            } catch (e: Exception) { fail(e) }
        }
    }
    /** a shared link (YouTube / YouTube Music › Share › Strata Music) */
    fun link(text: String) { val u = Regex("https?://\\S+").find(text)?.value ?: return; q.value = u; run() }
    fun openArtist(id: String) { msg.value = "Reading the artist…"; scope.launch { try { artist.value = Engine.artist(id); msg.value = "" } catch (e: Exception) { fail(e) } } }
    /** from an artist page in your library: their YouTube Music page (searched by name when no channel is known) */
    fun findArtist(name: String, channel: String) {
        if (channel.startsWith("UC")) { openArtist(channel); return }
        q.value = name; type.value = "artist"; msg.value = "Searching…"; artist.value = null
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
        scope.launch { try { val (_, tr) = Engine.tracks(JSONObject().put("kind", "album").put("id", id).put("artist", who)); Downloader.enqueue(tr); msg.value = "Added “$title” (${plural(tr.size, "song")})" } catch (e: Exception) { fail(e) } }
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
fun GetScreen() {
    var q by GetState.q
    var type by GetState.type
    val results by GetState.results
    val msg by GetState.msg
    val ar by GetState.artist
    val dl by Downloader.items.collectAsState()
    if (ar != null) androidx.activity.compose.BackHandler { GetState.artist.value = null }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Header("Get Music", "Search YouTube Music or paste a link")
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search, or paste a YouTube link") }, singleLine = true,
                leadingIcon = { Icon(Ic.Search, size = 20.dp, tint = dim) },
                trailingIcon = { Row { if (q.isNotEmpty()) IconBtn(Ic.Close, dim, 18.dp) { q = "" }; IconBtn(Ic.Check, accentC, 20.dp) { GetState.run() } } },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { GetState.run() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search))
            Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("song" to "Songs", "artist" to "Artists", "album" to "Albums", "playlist" to "Playlists").forEach { (k, l) -> FilterChip(selected = type == k, onClick = { type = k; if (q.isNotBlank() && !q.startsWith("http")) GetState.run() }, label = { Text(l) }) }
            }
            if (msg.isNotEmpty()) Text(msg, color = dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        val a = ar
        if (a != null) {
            val name = a.optString("name"); val albums = GetState.list(a.optJSONArray("albums")); val top = GetState.list(a.optJSONArray("top"))
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Ic.Back) { GetState.artist.value = null }
                        AsyncImage(a.optString("thumb"), null, Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${albums.count { !it.optBoolean("single") }} albums · ${albums.count { it.optBoolean("single") }} singles & EPs · ${top.size} top songs", fontSize = 12.sp, color = dim)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = top.isNotEmpty(), onClick = { GetState.saveArtist(a); Downloader.enqueue(top.map { Track(it.getString("id"), it.optString("title"), name, it.optString("album"), it.optString("thumb")) }); GetState.msg.value = "Added ${top.size} top songs" }) { Text("Top songs") }
                        OutlinedButton(enabled = albums.isNotEmpty(), onClick = { GetState.addAll(a, false) }) { Text("All albums") }
                        Button(enabled = albums.isNotEmpty(), onClick = { GetState.addAll(a, true) }) { Icon(Ic.Download, size = 18.dp, tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(6.dp)); Text("Everything") }
                    }
                }
            }
            items(albums, key = { "al-" + it.optString("id") }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(r.optString("thumb"), null, Modifier.size(54.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(r.optString("title"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(if (r.optBoolean("single")) "Single / EP" else "Album", r.optString("year")).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = dim)
                    }
                    IconBtn(Ic.Download, accentC) { GetState.saveArtist(a); GetState.addAlbum(r.getString("id"), name, r.optString("title")) }
                }
            }
        } else items(results, key = { it.optString("kind") + it.optString("id") }) { r ->
            val kind = r.optString("kind")
            Row(Modifier.fillMaxWidth().clickable(enabled = kind == "artist") { GetState.openArtist(r.getString("id")) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(r.optString("thumb"), null, Modifier.size(48.dp).clip(if (kind == "artist") CircleShape else RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(r.optString("title"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.optString("sub"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = dim)
                }
                if (kind == "artist") Icon(Ic.Person, tint = dim, modifier = Modifier.padding(end = 12.dp))
                else {
                    if (kind == "song") IconBtn(Ic.Radio, dim, 20.dp) {
                        Store.scope.launch { try { val tr = Engine.radio(JSONObject().put("id", r.getString("id"))); Downloader.enqueue(listOf(Track(r.getString("id"), r.optString("title"), r.optString("artist"), r.optString("album"), r.optString("thumb"))) + tr, play = true); Toasts.show("Mix: ${tr.size + 1} songs on the way") } catch (e: Exception) { Toasts.show(e.message ?: "Radio failed") } }
                    }
                    IconBtn(Ic.Download, accentC) {
                        if (kind == "song") Downloader.enqueue(listOf(Track(r.getString("id"), r.optString("title"), r.optString("artist"), r.optString("album"), r.optString("thumb"))))
                        else Store.scope.launch { try { val (_, tr) = Engine.tracks(JSONObject().put("kind", kind).put("id", r.getString("id")).put("artist", r.optString("artist"))); Downloader.enqueue(tr) } catch (e: Exception) { Toasts.show(e.message ?: "Failed") } }
                    }
                }
            }
        }
        if (dl.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads · ${dl.count { it.done }} of ${dl.size}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (dl.any { !it.done && !it.failed }) TextButton(onClick = { Downloader.stop() }) { Text("Stop") }
                if (dl.any { it.failed }) TextButton(onClick = { Downloader.retryFailed() }) { Text("Retry") }
                TextButton(onClick = { Downloader.clearFinished() }) { Text("Clear") }
            }
        }
        items(dl, key = { "dl-" + it.track.id }) { d ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(d.track.title.ifBlank { d.track.id }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(d.track.artist, d.status).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = if (d.failed) MaterialTheme.colorScheme.error else dim, maxLines = 2)
                if (!d.done && !d.failed && d.frac > 0f) LinearProgressIndicator(progress = { d.frac }, Modifier.fillMaxWidth())
            }
        }
        item { Text("Only add music you have the right to.", fontSize = 11.sp, color = dim, modifier = Modifier.padding(vertical = 16.dp)) }
    }
}
