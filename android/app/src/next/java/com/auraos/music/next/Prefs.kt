package com.auraos.music.next

import android.content.Context
import com.auraos.music.NativePlayer
import com.getcapacitor.JSObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/** Every setting. They live in the library's meta.settings, under the same names the WebView app uses. */
object Prefs {
    val EQ_BANDS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val EQ_PRESETS: LinkedHashMap<String, List<Float>> = linkedMapOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), "Bass Boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Bass Reducer" to listOf(-6f, -5f, -4f, -2f, 0f, 0f, 0f, 0f, 0f, 0f), "Treble Boost" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
        "Treble Reducer" to listOf(0f, 0f, 0f, 0f, 0f, -1f, -2f, -4f, -5f, -6f), "Vocal" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f),
        "Rock" to listOf(5f, 4f, 2f, -1f, -2f, -1f, 1f, 3f, 4f, 5f), "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f),
        "Hip-Hop" to listOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, -1f, 2f, 3f), "Electronic" to listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 4f, 5f),
        "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f), "Classical" to listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
        "Acoustic" to listOf(4f, 3f, 2f, 1f, 1f, 1f, 2f, 3f, 3f, 2f), "Late Night" to listOf(-2f, -1f, 0f, 1f, 2f, 2f, 1f, 0f, -2f, -3f),
        "Loudness" to listOf(6f, 4f, 0f, 0f, -2f, 0f, -1f, -4f, 4f, 2f),
    )
    val SORTS = listOf("Title", "Artist", "Album", "Recently Added", "Longest First", "Most Played")
    val ACCENTS = listOf(0xFFB794F6, 0xFF7DD3FC, 0xFF86EFAC, 0xFFFDA4AF, 0xFFFCD34D, 0xFFFDBA74, 0xFFF0F0F5).map { it.toInt() }

    val accent = MutableStateFlow(ACCENTS[0])
    val coverAccent = MutableStateFlow(true)
    val crossfade = MutableStateFlow(0f)
    val beatMatch = MutableStateFlow(true)
    val trimSilence = MutableStateFlow(true)
    val keepAwake = MutableStateFlow(false)
    val eqName = MutableStateFlow("Flat")
    val eqCustom = MutableStateFlow(List(10) { 0f })
    val motion = MutableStateFlow(true)
    val liquid = MutableStateFlow(true)
    // the glass (Settings › Look & Feel › Glass): thickness of the rim (dp), how much it bends, colour split, frost, opacity, tilt shine
    val glassThick = MutableStateFlow(24f); val glassBend = MutableStateFlow(0.6f); val glassSplit = MutableStateFlow(0.16f)
    val glassFrost = MutableStateFlow(0.25f); val glassOpacity = MutableStateFlow(0.5f); val glassShine = MutableStateFlow(true)
    val tuck = MutableStateFlow(false)
    val scrollTimer = MutableStateFlow(1.5f)
    val autoSaver = MutableStateFlow(true)
    /** ms: how much later (+) or earlier (-) the visualizer and background effects move than the music */
    val vizShift = MutableStateFlow(0f)
    val compress = MutableStateFlow(true)
    val autofill = MutableStateFlow(true)
    val npLyrics = MutableStateFlow(false)
    val discordRpc = MutableStateFlow(false)
    val discordAppName = MutableStateFlow("Strata Music")
    val discordFormat = MutableStateFlow(0)
    val discordShowTimeline = MutableStateFlow(true)
    val discordHidePaused = MutableStateFlow(true)
    val discordShowCover = MutableStateFlow(true)
    /** how the presence looks: [0] song text style, [1] artist text style, [2] song marker, [3] artist marker, [4] which text the member list shows (0 name, 1 artist, 2 song), [5] cover tooltip */
    val discordLook = MutableStateFlow(listOf(0, 0, 0, 0, 2, 0))
    val sort = MutableStateFlow(0)
    val shuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(0)
    val saving = MutableStateFlow(false)   // battery saver is in force right now

    private val s get() = Store.settings()
    fun init(@Suppress("UNUSED_PARAMETER") c: Context) = reload()
    fun reload() {
        accent.value = s.optInt("accent", ACCENTS[0]); coverAccent.value = s.optBoolean("coverAccent", true)
        crossfade.value = s.optDouble("crossfade", 0.0).toFloat().coerceIn(0f, 12f)
        beatMatch.value = s.optBoolean("beatMatch", true); trimSilence.value = s.optBoolean("trimSilence", true)
        keepAwake.value = s.optBoolean("keepAwake", false); eqName.value = s.optString("eq", "Flat")
        eqCustom.value = s.optJSONArray("eqCustom")?.let { a -> (0 until 10).map { a.optDouble(it, 0.0).toFloat() } } ?: List(10) { 0f }
        motion.value = s.optBoolean("motion", true); liquid.value = s.optBoolean("liquidGlass", true);
        glassThick.value = s.optDouble("glassThick", 24.0).toFloat(); glassBend.value = s.optDouble("glassBend", 0.6).toFloat(); glassSplit.value = s.optDouble("glassSplit", 0.16).toFloat()
        glassFrost.value = s.optDouble("glassFrost", 0.25).toFloat(); glassOpacity.value = s.optDouble("glassOpacity", 0.5).toFloat(); glassShine.value = s.optBoolean("glassShine", true); tuck.value = s.optBoolean("tuckPinned", false); scrollTimer.value = s.optDouble("scrollTimer", 1.5).toFloat(); autoSaver.value = s.optBoolean("autoSaver", true); vizShift.value = s.optDouble("vizShift", 0.0).toFloat(); NativePlayer.vizShift(vizShift.value)
        compress.value = s.optBoolean("compress", true); autofill.value = s.optBoolean("autofill", true)
        npLyrics.value = s.optBoolean("npLyrics", false)
        discordRpc.value = s.optBoolean("discordRpc", false)
        discordAppName.value = s.optString("discordAppName", "Strata Music").ifBlank { "Strata Music" }
        discordFormat.value = s.optInt("discordFormat", 0).coerceIn(0, 2)
        discordShowTimeline.value = s.optBoolean("discordShowTimeline", true)
        discordHidePaused.value = s.optBoolean("discordHidePaused", true); discordShowCover.value = s.optBoolean("discordShowCover", true)
        discordLook.value = List(6) { i -> s.optInt("discordLook$i", if (i == 4) 2 else 0).coerceIn(0, if (i == 4) 2 else 20) }
        sort.value = s.optInt("nativeSort", 0)
        applyAudio()
        Cfg.load()
    }
    private fun put(k: String, v: Any) { s.put(k, v); Store.save() }

    fun setAccent(v: Int) { accent.value = v; put("accent", v) }
    fun setCoverAccent(v: Boolean) { coverAccent.value = v; put("coverAccent", v) }
    fun setFade(v: Float) { crossfade.value = v; put("crossfade", Math.round(v * 2) / 2.0); applyAudio() }
    fun setMatch(v: Boolean) { beatMatch.value = v; put("beatMatch", v); applyAudio(); if (v) Tasks.poke() }
    fun setTrim(v: Boolean) { trimSilence.value = v; put("trimSilence", v); Ctl.refreshQueue(); if (v) Tasks.poke() }
    fun setAwake(v: Boolean) { keepAwake.value = v; put("keepAwake", v) }
    fun setEq(name: String) { eqName.value = name; put("eq", name); applyAudio() }
    fun setEqBand(i: Int, v: Float) { val l = eqGains().toMutableList(); l[i] = v; eqCustom.value = l; s.put("eqCustom", JSONArray(l.map { it.toDouble() })); setEq("Custom") }
    fun setTuck(v: Boolean) { tuck.value = v; put("tuckPinned", v) }
    fun setScrollTimer(v: Float) { scrollTimer.value = v; put("scrollTimer", v.toDouble()) }
    fun setGlass(thick: Float = glassThick.value, bend: Float = glassBend.value, split: Float = glassSplit.value, frost: Float = glassFrost.value, opacity: Float = glassOpacity.value, shine: Boolean = glassShine.value) {
        glassThick.value = thick; glassBend.value = bend; glassSplit.value = split; glassFrost.value = frost; glassOpacity.value = opacity; glassShine.value = shine
        s.put("glassThick", thick.toDouble()); s.put("glassBend", bend.toDouble()); s.put("glassSplit", split.toDouble()); s.put("glassFrost", frost.toDouble()); s.put("glassOpacity", opacity.toDouble()); s.put("glassShine", shine); Store.save()
    }
    fun setLiquid(v: Boolean) { liquid.value = v; put("liquidGlass", v) }
    fun setMotion(v: Boolean) { motion.value = v; put("motion", v) }
    fun setVizShift(v: Float) { vizShift.value = v; NativePlayer.vizShift(v); put("vizShift", v.toDouble()) }
    fun setAutoSaver(v: Boolean) { autoSaver.value = v; put("autoSaver", v) }
    fun setCompress(v: Boolean) { compress.value = v; put("compress", v); if (v) Tasks.poke() }
    fun setAutofill(v: Boolean) { autofill.value = v; put("autofill", v); if (v) Tasks.poke() }
    fun setNpLyrics(v: Boolean) { npLyrics.value = v; put("npLyrics", v) }
    fun setDiscordRpc(v: Boolean) { discordRpc.value = v; put("discordRpc", v); DiscordRpc.onToggle(v) }
    fun setDiscordAppName(v: String) { val n = v.trim().ifBlank { "Strata Music" }; discordAppName.value = n; put("discordAppName", n); DiscordRpc.onSettingsChanged() }
    fun setDiscordFormat(v: Int) { discordFormat.value = v.coerceIn(0, 2); put("discordFormat", discordFormat.value); DiscordRpc.onSettingsChanged() }
    fun setDiscordShowTimeline(v: Boolean) { discordShowTimeline.value = v; put("discordShowTimeline", v); DiscordRpc.onSettingsChanged() }
    fun setDiscordLook(i: Int, v: Int) { val l = discordLook.value.toMutableList(); l[i] = v; discordLook.value = l; put("discordLook$i", v) }
    fun setDiscordShowCover(v: Boolean) { discordShowCover.value = v; put("discordShowCover", v) }
    fun setDiscordHidePaused(v: Boolean) { discordHidePaused.value = v; put("discordHidePaused", v); DiscordRpc.onSettingsChanged() }
    fun setSort(v: Int) { sort.value = v; put("nativeSort", v) }

    fun eqGains(): List<Float> = if (eqName.value == "Custom") eqCustom.value else EQ_PRESETS[eqName.value] ?: EQ_PRESETS.getValue("Flat")

    /** crossfade + EQ into the audio engine; the pre-gain keeps the loudness the same as the WebView app did */
    fun applyAudio() {
        NativePlayer.crossfade(if (crossfade.value < 0.5f) 0f else crossfade.value, beatMatch.value)
        val g = eqGains()
        val w = listOf(0.9f, 1f, 1f, 0.9f, 0.8f, 0.7f, 0.55f, 0.4f, 0.3f, 0.2f)
        val avg = g.indices.sumOf { (g[it] * w[it]).toDouble() } / w.sum()
        NativePlayer.eq(g.toFloatArray(), 10.0.pow(-avg * 0.8 / 20).toFloat())
    }
}

/** What is playing and in which order: the queue, shuffle, repeat, play next / add to queue / reorder, the session. */
object Ctl {
    val queue = MutableStateFlow<List<Song>>(emptyList())
    val context = MutableStateFlow("")
    private var orig: List<Song> = emptyList()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val file get() = File(Store.root, "session.json")

    private fun itemOf(s: Song): JSObject {
        val o = JSObject()
        o.put("id", s.id); o.put("path", s.path); o.put("title", s.title); o.put("artist", s.artist); o.put("album", s.album)
        if (Prefs.trimSilence.value && s.trimStart > 0) o.put("start", Math.round(s.trimStart * 1000))
        if (Prefs.trimSilence.value && s.trimEnd > 0) o.put("end", Math.round(s.trimEnd * 1000))
        val t = s.j.optJSONObject("tempo")
        fun ok(a: JSONArray?) = a != null && a.optDouble(0) > 0 && a.optDouble(2) >= 3.5
        t?.optJSONArray("s")?.takeIf { ok(it) }?.let { o.put("bs", it.optDouble(0)); o.put("bb", Math.round(it.optDouble(1) * 1000)) }
        t?.optJSONArray("e")?.takeIf { ok(it) }?.let { o.put("be", it.optDouble(0)); o.put("eb", Math.round(it.optDouble(1) * 1000)) }
        return o
    }

    private fun load(ctx: Context, l: List<Song>, i: Int, pos: Long, play: Boolean, keep: Boolean = false) {
        queue.value = l
        scope.launch {
            NativePlayer.loadItems(ctx, l.map { itemOf(it) }, i, pos, play, keep)
            NativePlayer.repeat(Prefs.repeat.value)
        }
        save()
    }

    fun play(ctx: Context, list: List<Song>, index: Int, label: String = "", shuffle: Boolean? = null) {
        if (list.isEmpty()) return
        if (shuffle != null) Prefs.shuffle.value = shuffle
        orig = list; context.value = label
        var l = list; var i = index.coerceIn(0, list.size - 1)
        if (Prefs.shuffle.value) { val f = l[i]; l = listOf(f) + (l - f).shuffled(); i = 0 }
        load(ctx, l, i, 0, true)
    }
    fun shuffleAll(ctx: Context, list: List<Song>, label: String = "") { if (list.isNotEmpty()) play(ctx, list, list.indices.random(), label, true) }

    private fun cur(): Int = NativePlayer.state.value.index
    /** the queue changed around the song that's playing: it keeps playing */
    private fun requeue(ctx: Context, l: List<Song>, i: Int) {
        val st = NativePlayer.state.value
        load(ctx, l, i, st.pos, st.playing, keep = true)
    }
    fun playNext(ctx: Context, songs: List<Song>) {
        val q = queue.value
        if (q.isEmpty()) { play(ctx, songs, 0); return }
        val i = cur().coerceAtLeast(0)
        val rest = q.filterIndexed { k, s -> k <= i || s !in songs }
        val at = rest.indexOf(q[i]) + 1
        requeue(ctx, rest.take(at) + songs + rest.drop(at), at - 1)
        Toasts.show(if (songs.size == 1) "Playing next" else "${songs.size} songs playing next")
    }
    fun addToQueue(ctx: Context, songs: List<Song>) {
        val q = queue.value
        if (q.isEmpty()) { play(ctx, songs, 0); return }
        requeue(ctx, q + songs, cur().coerceAtLeast(0)); Toasts.show("Added to queue")
    }
    fun removeAt(ctx: Context, k: Int) {
        val q = queue.value; val i = cur()
        if (k == i || k !in q.indices) return
        requeue(ctx, q.filterIndexed { x, _ -> x != k }, if (k < i) i - 1 else i)
    }
    fun move(ctx: Context, from: Int, to: Int) {
        val q = queue.value.toMutableList(); if (from !in q.indices || to !in q.indices || from == to) return
        val playing = q[cur().coerceIn(0, q.size - 1)]
        val s = q.removeAt(from); q.add(to, s)
        requeue(ctx, q, q.indexOf(playing))
    }
    fun clearUpNext(ctx: Context) { val q = queue.value; val i = cur(); if (i >= 0) requeue(ctx, q.take(i + 1), i) }
    fun append(ctx: Context, s: Song) { val q = queue.value; if (q.isEmpty()) play(ctx, listOf(s), 0) else if (s !in q) requeue(ctx, q + s, cur().coerceAtLeast(0)) }
    /** after a setting that changes the items (silence cut on / off) */
    fun refreshQueue() { val q = queue.value; if (q.isNotEmpty()) requeue(Store.app, q.map { Store.song(it.id) ?: it }, cur().coerceAtLeast(0)) }

    fun toggleShuffle(ctx: Context) {
        val on = !Prefs.shuffle.value; Prefs.shuffle.value = on
        val q = queue.value; val c = q.getOrNull(cur()) ?: run { save(); return }
        if (on) requeue(ctx, listOf(c) + (q - c).shuffled(), 0)
        else { val l = orig.ifEmpty { q }.let { if (c in it) it else q }; requeue(ctx, l, l.indexOf(c).coerceAtLeast(0)) }
    }
    fun cycleRepeat() { val m = (Prefs.repeat.value + 1) % 3; Prefs.repeat.value = m; NativePlayer.repeat(m); save() }

    /** same shape as the WebView app's session: { queue, index, t (s), shuffle, repeat: off|all|one, ctx } */
    fun save() {
        val st = NativePlayer.state.value
        val j = JSONObject().put("queue", JSONArray(queue.value.take(2000).map { it.id })).put("index", st.index).put("t", st.pos / 1000.0)
            .put("shuffle", Prefs.shuffle.value).put("repeat", listOf("off", "all", "one")[Prefs.repeat.value]).put("ctx", context.value)
        Store.scope.launch { try { file.writeText(j.toString()) } catch (_: Exception) { } }
    }

    private fun apply(ctx: Context, j: JSONObject) {
        val by = Store.songs.value.associateBy { it.id }
        val a = j.optJSONArray("queue") ?: return
        val l = (0 until a.length()).mapNotNull { by[a.optString(it)] }
        if (l.isEmpty()) return
        Prefs.shuffle.value = j.optBoolean("shuffle"); Prefs.repeat.value = when (j.optString("repeat")) { "all" -> 1; "one" -> 2; else -> 0 }
        context.value = j.optString("ctx"); orig = l
        load(ctx, l, j.optInt("index").coerceIn(0, l.size - 1), (j.optDouble("t", 0.0) * 1000).toLong(), false)
    }
    /** Opens the app on the song you were last playing (paused) */
    fun restore(ctx: Context) { try { if (file.exists()) apply(ctx, JSONObject(file.readText())) } catch (_: Exception) { } }
    fun adoptOldSession(j: JSONObject) { scope.launch { if (queue.value.isEmpty()) apply(Store.app, j) } }
}
