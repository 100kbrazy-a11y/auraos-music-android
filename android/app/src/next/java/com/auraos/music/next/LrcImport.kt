package com.auraos.music.next

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Your own lyrics. Pick one .lrc (or .txt) file, or many at once: files named like the song ("Artist - Title.lrc") are matched
 * to your library automatically, and a single file that can't be matched lets you choose the song yourself.
 */
object LrcImport {
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun nameOf(u: Uri): String = try {
        Store.app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null } ?: u.lastPathSegment?.substringAfterLast('/') ?: "lyrics"

    private fun match(file: String, songs: List<Song>): Song? {
        val base = norm(file.substringBeforeLast('.'))
        return songs.filter { val t = norm(it.title); t.isNotEmpty() && (t.length >= 3 || base == t) && base.contains(t) }
            .maxByOrNull { norm(it.title).length + (if (norm(primaryArtist(it.artist)).let { a -> a.isNotEmpty() && base.contains(a) }) 1000 else 0) }
    }

    fun handle(uris: List<Uri>, forSong: Song? = null) {
        Store.scope.launch {
            val songs = Store.songs.value
            var ok = 0; val left = ArrayList<Pair<String, String>>()
            for (u in uris) {
                val text = try { Store.app.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) }?.removePrefix("﻿") } catch (_: Exception) { null }
                if (text.isNullOrBlank()) continue
                val name = nameOf(u); val m = if (forSong != null && uris.size == 1) forSong else match(name, songs)
                if (m != null) { Online.saveOwn(m.id, text); ok++ } else left.add(name to text)
            }
            withContext(Dispatchers.Main) {
                if (ok > 0) Lyr.bump()
                when {
                    left.isEmpty() -> { Dialogs.close(); Toasts.show(if (ok == 0) "Couldn't read that file" else if (forSong != null) "Lyrics added for ${forSong.title}" else "Lyrics added for ${plural(ok, "song")}") }
                    uris.size == 1 -> Dialogs.sheet { SongPickSheet(left[0].first.substringBeforeLast('.')) { s -> Online.saveOwn(s.id, left[0].second); Lyr.bump(); Dialogs.close(); Toasts.show("Lyrics added for ${s.title}") } }
                    else -> Toasts.show("Added $ok · ${left.size} files didn't match a song (name them “Artist - Title.lrc”)")
                }
            }
        }
    }
}

/** opens the file picker for lyrics; call the returned function from a button */
@Composable
fun rememberLrcPicker(forSong: Song? = null): () -> Unit {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) LrcImport.handle(it, forSong) }
    return { pick.launch(arrayOf("*/*")) }
}

/** choose which song a lyrics file belongs to */
@Composable
fun SongPickSheet(initial: String, onPick: (Song) -> Unit) {
    val songs by Store.songs.collectAsState()
    var q by remember { mutableStateOf(initial) }
    val words = q.lowercase().split(" ", "-", "_").filter { it.length > 1 }
    val st by com.auraos.music.NativePlayer.state.collectAsState(); val queue by Ctl.queue.collectAsState()
    val playing = queue.getOrNull(st.index)
    val shown = remember(q, songs) { songs.filter { s -> val h = "${s.title} ${s.artist}".lowercase(); words.all { it in h } }.sortedBy { it.title.lowercase() }.take(40) }
    Column(Modifier.padding(horizontal = 20.dp)) {
        T("Which song is this for?", 22.sp, W700, modifier = Modifier.padding(bottom = 10.dp))
        SearchBox(q, { q = it }, "Song or artist")
        Spacer(Modifier.height(8.dp))
    }
    LazyColumn(Modifier.heightIn(max = 460.dp)) {
        if (playing != null) item { Act(Ic.Play, "Playing now: ${playing.title}", playing.artist) { onPick(playing) } }
        items(shown, key = { it.id }) { s ->
            Column(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 24.dp, vertical = 10.dp)) { T(s.title, 15.sp, W650); T(s.artist, 13.sp, color = C.sub) }
        }
    }
}
