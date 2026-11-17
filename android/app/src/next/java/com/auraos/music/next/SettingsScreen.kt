@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.auraos.music.next

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title.uppercase(), color = accentC, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp)); content()
    }
}

@Composable
fun Row2(ic: Ic, label: String, sub: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onClick() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, size = 22.dp, tint = dim); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(label); if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = dim) }
    }
}

@Composable
fun SwitchRow(ic: Ic, label: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, size = 22.dp, tint = dim); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(label); if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = dim) }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickSongs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) Store.importSongs(it) { a, s -> Toasts.show("Added $a songs" + if (s > 0) " · $s were already here" else "") } }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) Store.importFolder(u) }
    val pickPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) Store.importPack(u) }
    val songs by Store.songs.collectAsState()
    val fade by Prefs.crossfade.collectAsState(); val match by Prefs.beatMatch.collectAsState(); val trim by Prefs.trimSilence.collectAsState()
    val awake by Prefs.keepAwake.collectAsState(); val eqName by Prefs.eqName.collectAsState(); val eqC by Prefs.eqCustom.collectAsState()
    val motion by Prefs.motion.collectAsState(); val saver by Prefs.autoSaver.collectAsState(); val saving by Prefs.saving.collectAsState()
    val comp by Prefs.compress.collectAsState(); val fill by Prefs.autofill.collectAsState()
    val accent by Prefs.accent.collectAsState(); val coverAccent by Prefs.coverAccent.collectAsState()
    val task by Tasks.status.collectAsState(); val mig by Migrate.state.collectAsState()
    var bytes by remember { mutableStateOf(0L) }
    var showEq by remember { mutableStateOf(false) }
    LaunchedEffect(songs.size) { bytes = withContext(Dispatchers.IO) { Store.storageBytes() } }
    val albums = remember(songs) { Store.albums(songs) }
    val noCover = albums.count { it.cover == null }; val noGenre = songs.count { it.genre.isBlank() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Header("Settings")
            Section("Your library") {
                Text("${plural(songs.size, "song")} · ${plural(albums.size, "album")} · ${"%.1f".format(bytes / 1073741824.0)} GB", fontSize = 15.sp)
                val saved = Store.meta.optLong("compressSaved")
                Text(listOf(if (noCover > 0) plural(noCover, "missing cover") else "", if (noGenre > 0) "$noGenre without a genre" else "", if (saved > 0) "%.0f MB saved by compressing".format(saved / 1048576.0) else "").filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Covers, genres, compression · all set" }, fontSize = 12.sp, color = dim)
                if (task.isNotEmpty()) Text(task, fontSize = 12.sp, color = accentC)
            }
            Section("Add music") {
                Row2(Ic.Note, "Songs", "Pick audio files from this phone") { pickSongs.launch(arrayOf("audio/*")) }
                Row2(Ic.Folder, "A Folder", "Everything in a folder and its sub-folders") { pickFolder.launch(null) }
                Row2(Ic.Pack, "Music Pack", "A .zip shared from Strata Music") { pickPack.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
                Row2(Ic.Link, "From YouTube Music", "Search, or paste a song / album / playlist / artist link") { Nav.tab.value = 4 }
                Row2(Ic.Wand, "Bring Over from Strata Music", if (Migrate.done()) "Done · run again to pick up anything new" else "Songs, playlists, favorites, lyrics and settings from the older app") {
                    scope.launch { Toasts.show(Migrate.run(force = true)) }
                }
                if (mig.isNotEmpty()) Text(mig, fontSize = 12.sp, color = accentC)
            }
            Section("Library options") {
                Row2(Ic.Image, "Find Missing Covers", if (noCover > 0) plural(noCover, "album") + " without a cover" else "Every album has a cover") { Tasks.findCovers() }
                SwitchRow(Ic.Wand, "Fill In Info Automatically", "Genre and year from iTunes for songs missing them", fill) { Prefs.setAutofill(it) }
                SwitchRow(Ic.Pack, "Compress Music", if (comp) "Everything becomes Opus (near-lossless, a fraction of the size) · runs only while charging" else "Off · songs are kept as they are", comp) { Prefs.setCompress(it) }
                SwitchRow(Ic.Cut, "Cut Silence", if (trim) "Songs start at their first sound and end at their last" else "Off · songs play from their very first second", trim) { Prefs.setTrim(it) }
                Row2(Ic.Trash, "Remove All Music", "Deletes every song from this phone") { Dialogs.show { Confirm("Remove all music?", "Every song, cover and playlist is deleted from this phone. This can't be undone.", "Remove All") { Store.removeAll() } } }
            }
            Section("Playback") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Shuffle, size = 22.dp, tint = dim); Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Crossfade · ${if (fade < 0.5f) "Off" else "%.1f s".format(fade)}"); Text("Blend the end of each song into the next", fontSize = 12.sp, color = dim)
                        Slider(value = fade, onValueChange = { Prefs.setFade((it * 2).toInt() / 2f) }, valueRange = 0f..12f)
                    }
                }
                SwitchRow(Ic.Spark, "Match Beats", "Line the beats up while crossfading (when both songs have a steady beat)", match) { Prefs.setMatch(it) }
                Row2(Ic.Eq, "Equalizer", eqName) { showEq = !showEq }
                SwitchRow(Ic.Sun, "Keep the Screen On", "While the full player is open", awake) { Prefs.setAwake(it) }
            }
        }
        if (showEq) item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (Prefs.EQ_PRESETS.keys + "Custom").forEach { n -> FilterChip(selected = eqName == n, onClick = { Prefs.setEq(n) }, label = { Text(n) }) }
                }
                val g = if (eqName == "Custom") eqC else Prefs.EQ_PRESETS[eqName] ?: List(10) { 0f }
                g.forEachIndexed { i, v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Prefs.EQ_BANDS[i], Modifier.width(36.dp), fontSize = 12.sp, color = dim)
                        Slider(value = v, onValueChange = { x -> if (eqName != "Custom") { Prefs.eqCustom.value = g; Store.settings().put("eqCustom", org.json.JSONArray(g.map { it.toDouble() })) }; Prefs.setEqBand(i, Math.round(x * 2f) / 2f) }, valueRange = -12f..12f, modifier = Modifier.weight(1f))
                        Text("%+.1f".format(v), Modifier.width(44.dp), fontSize = 12.sp, color = dim)
                    }
                }
            }
        }
        item {
            Section("Look & feel") {
                SwitchRow(Ic.Spark, "Motion Effects", "Living background, visualizer and glow in the player", motion) { Prefs.setMotion(it) }
                SwitchRow(Ic.Moon, "Save Battery Automatically", if (saving) "Saving now · motion is paused" else "Pause the moving background in Battery Saver or below 20%", saver) { Prefs.setAutoSaver(it) }
                SwitchRow(Ic.Image, "Colors from the Cover", "The player takes its colour from the album art", coverAccent) { Prefs.setCoverAccent(it) }
                Text("Accent colour", fontSize = 13.sp, color = dim, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Prefs.ACCENTS.forEach { c ->
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(c)).clickable { Prefs.setAccent(c) }, contentAlignment = Alignment.Center) { if (c == accent) Icon(Ic.Check, size = 20.dp, tint = Color(0xFF14101E)) }
                    }
                }
            }
            Section("Sharing") {
                Text("Share any song (as its audio file), album or playlist (as a Music Pack) from its ⋯ menu.", fontSize = 13.sp, color = dim)
                Text("YouTube / YouTube Music › Share › Strata Music adds the link straight away.", fontSize = 13.sp, color = dim)
            }
            Section("Diagnostics") { Row2(Ic.Info, "Activity Log", "What the app has been doing (for fixing problems)") { Dialogs.show { DiagDialog(ctx) } } }
            Section("About") { Text("Strata Music · native (Kotlin + Jetpack Compose). The hidden YouTube browser only runs while you search or download.", fontSize = 12.sp, color = dim) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun DiagDialog(ctx: Context) {
    val lines by Diag.lines.collectAsState()
    AlertDialog(onDismissRequest = { Dialogs.close() }, title = { Text("Activity Log") },
        confirmButton = { TextButton(onClick = { Dialogs.close() }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Strata log", lines.joinToString("\n"))); Toasts.show("Copied") }) { Text("Copy") } },
        text = { LazyColumn(Modifier.heightIn(max = 460.dp)) { if (lines.isEmpty()) item { Text("Nothing yet", color = dim) }; items(lines.reversed()) { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) } } })
}
