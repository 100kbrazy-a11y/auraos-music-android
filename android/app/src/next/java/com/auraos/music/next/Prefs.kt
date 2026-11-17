package com.auraos.music.next

import android.content.Context
import android.content.SharedPreferences
import com.auraos.music.NativePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.pow

/** Every setting, saved the moment it changes. */
object Prefs {
    private lateinit var sp: SharedPreferences
    val accent = MutableStateFlow(0xFFB794F6.toInt())
    val crossfade = MutableStateFlow(0f)
    val beatMatch = MutableStateFlow(true)
    val keepAwake = MutableStateFlow(false)
    val eqOn = MutableStateFlow(false)
    val eq = MutableStateFlow(List(10) { 0f })
    val sort = MutableStateFlow(0)
    val shuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(0)
    val EQ_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val SORTS = listOf("Artist", "Title", "Album", "Recently added", "Longest first")
    val ACCENTS = listOf(0xFFB794F6, 0xFF7DD3FC, 0xFF86EFAC, 0xFFFDA4AF, 0xFFFCD34D, 0xFFFDBA74).map { it.toInt() }
    val PRESETS = listOf(
        "Flat" to List(10) { 0f }, "Bass" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f), "Treble" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
        "Vocal" to listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f), "Rock" to listOf(5f, 4f, 2f, -1f, -2f, -1f, 2f, 4f, 5f, 5f),
        "Electronic" to listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 4f, 5f), "Loudness" to listOf(6f, 4f, 0f, 0f, -1f, 0f, -1f, 0f, 4f, 2f),
    )

    fun init(c: Context) {
        sp = c.getSharedPreferences("strata-next", Context.MODE_PRIVATE)
        accent.value = sp.getInt("accent", accent.value)
        crossfade.value = sp.getFloat("fade", 0f)
        beatMatch.value = sp.getBoolean("match", true)
        keepAwake.value = sp.getBoolean("awake", false)
        eqOn.value = sp.getBoolean("eqOn", false)
        eq.value = (sp.getString("eq", "") ?: "").split(",").mapNotNull { it.toFloatOrNull() }.let { if (it.size == 10) it else List(10) { 0f } }
        sort.value = sp.getInt("sort", 0)
        shuffle.value = sp.getBoolean("shuffle", false)
        repeat.value = sp.getInt("repeat", 0)
        applyAudio()
    }

    fun setAccent(v: Int) { accent.value = v; sp.edit().putInt("accent", v).apply() }
    fun setFade(v: Float) { crossfade.value = v; sp.edit().putFloat("fade", v).apply(); applyAudio() }
    fun setMatch(v: Boolean) { beatMatch.value = v; sp.edit().putBoolean("match", v).apply(); applyAudio() }
    fun setAwake(v: Boolean) { keepAwake.value = v; sp.edit().putBoolean("awake", v).apply() }
    fun setEqOn(v: Boolean) { eqOn.value = v; sp.edit().putBoolean("eqOn", v).apply(); applyAudio() }
    fun setEq(v: List<Float>) { eq.value = v; sp.edit().putString("eq", v.joinToString(",")).apply(); applyAudio() }
    fun setSort(v: Int) { sort.value = v; sp.edit().putInt("sort", v).apply() }
    fun setShuffle(v: Boolean) { shuffle.value = v; sp.edit().putBoolean("shuffle", v).apply() }
    fun setRepeat(v: Int) { repeat.value = v; sp.edit().putInt("repeat", v).apply() }

    /** Pushes crossfade and EQ into the audio engine. The pre-gain keeps boosted bands from clipping. */
    fun applyAudio() {
        NativePlayer.crossfade(if (crossfade.value < 0.5f) 0f else crossfade.value, beatMatch.value)
        val g = if (eqOn.value) eq.value else List(10) { 0f }
        val top = max(0f, g.maxOrNull() ?: 0f)
        NativePlayer.eq(g.toFloatArray(), 10f.pow(-top / 20f))
    }

    fun saveSession(ids: List<String>, index: Int, pos: Long) {
        if (ids.isEmpty()) return
        sp.edit().putString("q", ids.joinToString(",")).putInt("qi", index).putLong("qp", pos).apply()
    }
    fun session(): Triple<List<String>, Int, Long> =
        Triple((sp.getString("q", "") ?: "").split(",").filter { it.isNotBlank() }, sp.getInt("qi", 0), sp.getLong("qp", 0))
}

/** What is playing and in which order (the queue), shuffle, restoring where you left off. */
object Ctl {
    val queue = MutableStateFlow<List<Song>>(emptyList())
    private var orig: List<Song> = emptyList()
    private val scope = CoroutineScope(Dispatchers.Main)

    fun play(ctx: Context, list: List<Song>, index: Int) {
        if (list.isEmpty()) return
        orig = list
        var l = list; var i = index.coerceIn(0, list.size - 1)
        if (Prefs.shuffle.value) { val f = l[i]; l = listOf(f) + (l - f).shuffled(); i = 0 }
        load(ctx, l, i, 0, true)
    }

    private fun load(ctx: Context, l: List<Song>, i: Int, pos: Long, play: Boolean) {
        queue.value = l
        scope.launch {
            NativePlayer.load(ctx, l.map { NativePlayer.Item(it.id, it.path, it.title, it.artist, it.album) }, i, pos, play)
            NativePlayer.repeat(Prefs.repeat.value)
        }
    }

    fun toggleShuffle(ctx: Context) {
        val on = !Prefs.shuffle.value
        Prefs.setShuffle(on)
        val st = NativePlayer.state.value
        val q = queue.value
        val cur = q.getOrNull(st.index) ?: return
        if (on) load(ctx, listOf(cur) + (q - cur).shuffled(), 0, st.pos, st.playing)
        else { val l = orig.ifEmpty { q }; load(ctx, l, l.indexOf(cur).coerceAtLeast(0), st.pos, st.playing) }
    }

    fun cycleRepeat() { val m = (Prefs.repeat.value + 1) % 3; Prefs.setRepeat(m); NativePlayer.repeat(m) }

    fun save() { val st = NativePlayer.state.value; Prefs.saveSession(queue.value.map { it.id }, st.index, st.pos) }

    /** Opens the app on the song you were last playing (paused) */
    fun restore(ctx: Context) {
        val (ids, idx, pos) = Prefs.session()
        val by = Store.songs.value.associateBy { it.id }
        val l = ids.mapNotNull { by[it] }
        if (l.isEmpty()) return
        orig = l
        load(ctx, l, idx.coerceIn(0, l.size - 1), pos, false)
    }
}
