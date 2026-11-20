@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.auraos.music.next

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Folder scanner. Android only lets an app read folders you hand it, so "Add a folder" opens the system folder picker once; the
 * permission is kept, and from then on the folder is scanned again whenever you open this page. Every song found is listed with
 * a tick, so you choose exactly what comes in, and whether it lands in a playlist (one for all of it, or one per folder).
 */
object Scan {
    data class Hit(val uri: Uri, val name: String, val folder: String, val size: Long, val coverUri: Uri?, val title: String, val artist: String, val have: Boolean)

    val folders = MutableStateFlow<List<String>>(emptyList())
    val hits = MutableStateFlow<List<Hit>>(emptyList())
    val picked = MutableStateFlow<Set<String>>(emptySet())
    val status = MutableStateFlow("")          // while it is looking
    val adding = MutableStateFlow("")          // while it is adding
    var scanned = false
    private var ctx: Context? = null

    fun load(c: Context) {
        ctx = c.applicationContext
        val a = Store.settings().optJSONArray("scanFolders")
        folders.value = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }
    private fun save() { Store.settings().put("scanFolders", JSONArray(folders.value)); Store.save() }

    fun nameOf(tree: String): String {
        val id = try { DocumentsContract.getTreeDocumentId(Uri.parse(tree)) } catch (_: Exception) { tree }
        val tail = id.substringAfter(':', id).trimEnd('/')
        return if (tail.isEmpty()) "Internal storage" else tail
    }

    fun addFolder(c: Context, u: Uri) {
        try { c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        val s = u.toString()
        if (s !in folders.value) { folders.value = folders.value + s; save() }
        scan()
    }
    fun removeFolder(c: Context, s: String) {
        try { c.contentResolver.releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        folders.value = folders.value - s; save()
        hits.value = emptyList(); picked.value = emptySet(); scanned = false
        if (folders.value.isNotEmpty()) scan()
    }

    private fun clean(n: String) = n.substringBeforeLast('.').replace('_', ' ').trim()

    /** looks through every saved folder, then reads each song's tags so the list shows real titles and artists */
    fun scan() {
        val c = ctx ?: return
        if (status.value.isNotEmpty()) return
        scanned = true
        Store.scope.launch {
            val found = ArrayList<Triple<Uri, Triple<String, String, Long>, Pair<String, Uri?>>>()   // uri · (name, folder, size) · (rootName, cover)
            for (t in folders.value) {
                val tree = Uri.parse(t); val root = nameOf(t).substringAfterLast('/')
                status.value = "Looking through $root…"
                fun walk(docId: String, rel: String, depth: Int) {
                    if (depth > 10) return
                    val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                    val files = ArrayList<List<String>>()
                    try {
                        c.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { q ->
                            while (q.moveToNext()) files.add(listOf(q.getString(0) ?: "", q.getString(1) ?: "", q.getString(2) ?: "", q.getLong(3).toString()))
                        }
                    } catch (_: Exception) { }
                    val coverDoc = files.firstOrNull { Regex("^(cover|folder|front)\\.(jpe?g|png)$", RegexOption.IGNORE_CASE).matches(it[1]) }
                    val cover = coverDoc?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it[0]) }
                    for (f in files) {
                        if (f[2] == DocumentsContract.Document.MIME_TYPE_DIR) walk(f[0], if (rel.isEmpty()) f[1] else "$rel/${f[1]}", depth + 1)
                        else if (f[1].substringAfterLast('.', "").lowercase() in Store.AUDIO)
                            found.add(Triple(DocumentsContract.buildDocumentUriUsingTree(tree, f[0]), Triple(f[1], rel.ifEmpty { root }, f[3].toLongOrNull() ?: 0L), root to cover))
                    }
                }
                try { walk(DocumentsContract.getTreeDocumentId(tree), "", 0) } catch (e: Exception) { Diag.log("scan: ${e.message}") }
            }
            val out = ArrayList<Hit>()
            val seen = HashSet<String>()
            found.forEachIndexed { i, (u, info, rc) ->
                if (!seen.add(u.toString())) return@forEachIndexed
                if (i % 12 == 0) { status.value = "Reading songs… ${i + 1} of ${found.size}"; if (i % 48 == 0) hits.value = out.toList() }
                var title = ""; var artist = ""
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(c, u)
                    title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim() ?: ""
                    artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim() ?: ""
                } catch (_: Exception) { } finally { try { r.release() } catch (_: Exception) { } }
                if (title.isEmpty()) {
                    val n = clean(info.first)
                    if (artist.isEmpty() && n.contains(" - ")) { artist = n.substringBefore(" - ").trim(); title = n.substringAfter(" - ").trim() } else title = n
                }
                out.add(Hit(u, info.first, info.second, info.third, rc.second, title, artist, Store.haveSong(title, artist)))
            }
            hits.value = out.sortedWith(compareBy({ it.folder.lowercase() }, { it.name.lowercase() }))
            picked.value = hits.value.filter { !it.have }.map { it.uri.toString() }.toSet()
            status.value = ""
            if (out.isEmpty() && folders.value.isNotEmpty()) Toasts.show("No songs found in those folders")
        }
    }

    fun toggle(h: Hit) { val k = h.uri.toString(); picked.value = if (k in picked.value) picked.value - k else picked.value + k }
    fun setGroup(list: List<Hit>, on: Boolean) { val k = list.map { it.uri.toString() }.toSet(); picked.value = if (on) picked.value + k else picked.value - k }

    /** mode: 0 no playlist · 1 one playlist for everything chosen · 2 one playlist per folder */
    fun add(mode: Int, name: String) {
        val sel = hits.value.filter { it.uri.toString() in picked.value }
        if (sel.isEmpty() || adding.value.isNotEmpty()) return
        Store.scope.launch {
            val ids = ArrayList<String>(); val byFolder = LinkedHashMap<String, MutableList<String>>()
            var added = 0; var dup = 0
            val covers = HashMap<Uri, ByteArray?>()
            sel.forEachIndexed { i, h ->
                adding.value = "Adding ${i + 1} of ${sel.size}…"; Store.busy.value = adding.value
                val cov = h.coverUri?.let { cu -> covers.getOrPut(cu) { try { ctx?.contentResolver?.openInputStream(cu)?.use { it.readBytes() } } catch (_: Exception) { null } } }
                val r = Store.importUri(h.uri, cov)
                if (r != null) { ids.add(r.first); byFolder.getOrPut(h.folder) { ArrayList() }.add(r.first); if (r.second) added++ else dup++ }
            }
            var made = ""
            when (mode) {
                1 -> if (ids.isNotEmpty()) made = Store.newPlaylist(name.ifBlank { "New songs" }, ids.distinct())
                2 -> byFolder.forEach { (f, l) -> if (l.isNotEmpty()) Store.newPlaylist(f.substringAfterLast('/'), l.distinct()) }
            }
            val done = sel.map { it.uri.toString() }.toSet()
            hits.value = hits.value.map { if (it.uri.toString() in done) it.copy(have = true) else it }
            picked.value = emptySet(); adding.value = ""; Store.busy.value = ""
            Toasts.show("Added ${plural(added, "song")}" + (if (dup > 0) " · $dup were already here" else "") + (if (made.isNotEmpty()) " · playlist “$made”" else if (mode == 2) " · ${plural(byFolder.size, "playlist")}" else ""))
        }
    }
}

@Composable
private fun Tick(on: Boolean, size: androidx.compose.ui.unit.Dp = 26.dp) {
    val a = accent
    Box(Modifier.size(size).clip(CircleShape).background(if (on) a else Color.Transparent).then(if (on) Modifier else Modifier.border(2.dp, Color.White.copy(alpha = 0.28f), CircleShape)), contentAlignment = Alignment.Center) {
        if (on) Icon(Ic.Check, size = size * 0.62f, tint = C.onAccent, strokeWidth = 3f)
    }
}

@Composable
fun ScannerPage() {
    val ctx = LocalContext.current
    val folders by Scan.folders.collectAsState(); val hits by Scan.hits.collectAsState(); val picked by Scan.picked.collectAsState()
    val status by Scan.status.collectAsState(); val adding by Scan.adding.collectAsState()
    val ls = rememberLazyListState(); val a = accent
    var mode by remember { mutableStateOf(0) }; var name by remember { mutableStateOf("") }
    var newOnly by remember { mutableStateOf(true) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) Scan.addFolder(ctx, u) }
    LaunchedEffect(Unit) { Scan.load(ctx); if (Scan.folders.value.isNotEmpty() && Scan.status.value.isEmpty() && !Scan.scanned) Scan.scan() }
    val shown = remember(hits, newOnly) { if (newOnly) hits.filter { !it.have } else hits }
    val groups = remember(shown) { shown.groupBy { it.folder } }
    val newCount = hits.count { !it.have }
    Box(Modifier.fillMaxSize().background(C.bg)) {
        LazyColumn(state = ls, contentPadding = PaddingValues(top = topPad(), bottom = if (hits.isNotEmpty()) 300.dp else LocalBottom.current)) {
            item {
                T("Pick the folders your music lives in. They are scanned for you, and you choose which songs come in. Strata asks Android for read access to each folder once and remembers it.", 14.sp, color = C.sub, lines = 5, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                Group("Folders") {
                    folders.forEach { f ->
                        Item(Ic.Folder, Scan.nameOf(f), "Scanned each time you open this page", trailing = { IB(Ic.Close, C.sub, 20.dp, 40.dp) { Scan.removeFolder(ctx, f) } })
                    }
                    Item(Ic.Plus, "Add a folder", if (folders.isEmpty()) "Android shows its folder picker — choose Music, Downloads or any folder" else "", onClick = { pick.launch(null) })
                    if (folders.isNotEmpty()) Item(Ic.History, "Scan again", status.ifEmpty { "Look for new songs in all folders" }, onClick = { Scan.scan() })
                }
                if (status.isNotEmpty()) T(status, 13.sp, color = a, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))
                if (hits.isNotEmpty()) Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("New only", newOnly, newCount) { newOnly = true }
                    Chip("Everything", !newOnly, hits.size) { newOnly = false }
                }
            }
            groups.forEach { (folder, list) ->
                item(key = "h$folder") {
                    val all = list.all { it.uri.toString() in picked }
                    Row(Modifier.fillMaxWidth().clickable { Hap.tick(); Scan.setGroup(list, !all) }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Ic.Folder, tint = a)
                        Column(Modifier.weight(1f)) { T(folder.substringAfterLast('/'), 15.sp, W750, lines = 1); T(plural(list.size, "song") + if (folder.contains('/')) " · $folder" else "", 12.sp, color = C.sub) }
                        Tick(all)
                    }
                }
                items(list, key = { it.uri.toString() }) { h ->
                    val on = h.uri.toString() in picked
                    Row(Modifier.fillMaxWidth().clickable { Hap.tick(); Scan.toggle(h) }.padding(start = 40.dp, end = 24.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f)) {
                            T(h.title.ifEmpty { h.name }, 15.sp, W600, if (h.have) C.sub else C.text)
                            T(listOf(h.artist, if (h.have) "Already in your library" else fmtSize(h.size)).filter { it.isNotEmpty() }.joinToString(" · "), 12.5.sp, color = C.sub)
                        }
                        Tick(on, 24.dp)
                    }
                }
            }
            if (hits.isEmpty() && folders.isNotEmpty() && status.isEmpty()) item { T("No songs found yet.", 14.sp, color = C.sub, modifier = Modifier.padding(24.dp)) }
        }
        Head("Scan folders", ls.scrolled, back = true)
        if (hits.isNotEmpty()) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(C.surface2).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("${picked.size} of ${hits.size} chosen", 15.sp, W700, modifier = Modifier.weight(1f))
                TextBtn("All") { Scan.setGroup(shown, true) }
                TextBtn("None") { Scan.setGroup(hits, false) }
            }
            T("Put them in a playlist?", 13.sp, color = C.sub, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("No playlist", mode == 0) { mode = 0 }
                Chip("One playlist", mode == 1) { mode = 1 }
                Chip("One per folder", mode == 2) { mode = 2 }
            }
            if (mode == 1) Field(name, { name = it }, "Playlist name", modifier = Modifier.padding(top = 10.dp))
            Btn(if (adding.isNotEmpty()) adding else "Add ${plural(picked.size, "song")}", Ic.Plus, enabled = picked.isNotEmpty() && adding.isEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Scan.add(mode, name) }
        }
    }
}
