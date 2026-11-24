package com.auraos.music.next

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

// Precise word timing, made at home and light on purpose: no model to download (the same engine as the Mac app).
// 1. the song is read as 22.05 kHz stereo; cpp/voice keeps the part that is sung (centred, steady, not a drum hit) and finds where the voice is and where its syllables begin
// 2. each lyric line is lined up with that: its words are matched to the syllable starts in the stretch where it is sung
// The lyrics' own line times are the authority: a line never starts before its stamp or runs past the next line's, so words can't be mixed up between lines.
// The words' times are written as an enhanced .lrc (a time before each word, and a time with no word after it where the singing stops).

object PcmLoader {
    const val RATE = WordTiming.RATE
    const val MAX_SECONDS = 8 * 60

    /** [onProgress] 0..1 over the reading of the file */
    fun load(file: File, onProgress: (Float) -> Unit = {}): Pcm {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("no audio in this file")
            ex.selectTrack(track)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durUs > MAX_SECONDS * 1_000_000L) throw IllegalStateException("too long to analyse")
            var cap = ((durUs / 1_000_000.0) * RATE).toInt() + RATE * 2
            if (cap < RATE * 20) cap = RATE * 60
            var left = ShortArray(cap); var right = ShortArray(cap); var n = 0
            var inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var isFloat = false
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(fmt, null, null, 0)
            dec.start()
            // box-filter resampling to 22.05 kHz: every input sample adds to the output sample it falls in, weighted by how much of it falls there
            var binWidth = inRate / RATE.toDouble()
            var filled = 0.0; var accL = 0.0; var accR = 0.0
            val info = MediaCodec.BufferInfo()
            var eosIn = false; var eosOut = false; var reads = 0
            val deadline = System.currentTimeMillis() + 180_000L
            while (!eosOut) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("decoding took too long")
                if (!eosIn) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) { dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosIn = true }
                        else {
                            val tUs = ex.sampleTime
                            dec.queueInputBuffer(inIdx, 0, sz, tUs, 0); ex.advance()
                            if (durUs > 0 && (++reads and 63) == 0) onProgress((tUs.toFloat() / durUs).coerceIn(0f, 1f))
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = dec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    binWidth = inRate / RATE.toDouble()
                } else if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                    val ob = dec.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val sh = if (isFloat) null else ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val fl = if (isFloat) ob.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer() else null
                        val total = if (sh != null) sh.remaining() else fl!!.remaining()
                        var i = 0
                        while (i + ch <= total) {
                            val l = if (sh != null) sh.get(i).toDouble() else fl!!.get(i) * 32768.0
                            val r = if (ch > 1) (if (sh != null) sh.get(i + 1).toDouble() else fl!!.get(i + 1) * 32768.0) else l
                            i += ch
                            var w = 1.0
                            while (w > 1e-9) {
                                val take = min(w, binWidth - filled)
                                accL += l * take; accR += r * take; filled += take; w -= take
                                if (filled >= binWidth - 1e-9) {
                                    if (n >= left.size) { left = left.copyOf(left.size + RATE * 30); right = right.copyOf(right.size + RATE * 30) }
                                    left[n] = (accL / binWidth).roundToInt().coerceIn(-32768, 32767).toShort()
                                    right[n] = (accR / binWidth).roundToInt().coerceIn(-32768, 32767).toShort()
                                    n++; accL = 0.0; accR = 0.0; filled = 0.0
                                }
                            }
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                }
            }
            return Pcm(left, right, n)
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) { }
            try { ex.release() } catch (_: Exception) { }
        }
    }
}

object ForcedAligner {
    /** bump to have every song's word timing made again */
    const val GEN = 3
    private const val MAX_MS = PcmLoader.MAX_SECONDS * 1000L
    private val judged = HashMap<String, Int>()     // song id -> hash of the lyrics we looked at and decided there was nothing to do for

    // What the lyrics page shows: which song is being timed right now, how far along it is, and which songs' last try didn't work.
    private val _busy = MutableStateFlow<String?>(null)
    private val _progress = MutableStateFlow(0f)
    private val _waiting = MutableStateFlow<Set<String>>(emptySet())
    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    val busyId: StateFlow<String?> = _busy
    val progress: StateFlow<Float> = _progress
    /** songs lined up to be timed (the one being timed is in [busyId]) */
    val waiting: StateFlow<Set<String>> = _waiting
    val failed: StateFlow<Set<String>> = _failed
    private val gate = Mutex()                       // one song at a time: the engine holds the whole song in memory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun recOf(id: String): JSONObject? = Store.meta.optJSONObject("lyrics")?.optJSONObject(id)

    /** the lyrics as the source gave them: the original line times, no word tags */
    private fun sourceOf(rec: JSONObject): String? {
        val kept = rec.optString("syncedSrc")
        if (kept.isNotBlank()) return kept
        val synced = rec.optString("synced")
        if (synced.isBlank() || synced == "null") return null
        if (!synced.contains("<")) return synced
        // made by the first version of this: its line stamps are still the originals, so the plain lines can be rebuilt. Anything else with word times came that way, leave it.
        if (rec.optBoolean("aligned")) return Online.parseLrc(synced).joinToString("\n") { "[${WordTiming.stamp(it.ms / 1000.0)}]${it.text}" }
        return null
    }

    /** true when this song's words have been timed by this version */
    fun isTimed(s: Song): Boolean { val r = recOf(s.id) ?: return false; return r.optBoolean("aligned") && r.optInt("alignGen", 0) >= GEN }

    private fun check(s: Song, saverCounts: Boolean): Boolean {
        val rec = recOf(s.id) ?: return false
        val h = rec.optString("synced").hashCode()
        if (judged[s.id] == h) return false
        fun no(): Boolean { judged[s.id] = h; return false }
        if (rec.optInt("alignGen", 0) >= GEN || rec.optBoolean("instrumental") || rec.optBoolean("none")) return no()
        if (s.durMs < 8_000L || s.durMs > MAX_MS) return no()
        if (!Voice.load()) return no()
        if (saverCounts && Prefs.saving.value) return false     // battery saver: not now (try again later)
        if (!File(Store.app.filesDir, s.path).exists()) return no()
        val src = sourceOf(rec) ?: return no()
        return if (Online.parseLrc(src).size >= 2) true else no()
    }

    /** Is there a song with synced lyrics that hasn't had its words timed by this version yet? (for the background jobs: not in battery saver) */
    fun needsAlign(s: Song): Boolean = check(s, true)

    /** The song whose lyrics are on screen: its words are timed right away (ahead of the background queue), and the page shows it happening. */
    fun startNow(s: Song) {
        if (_busy.value == s.id || s.id in _waiting.value) return
        if (!check(s, false)) return
        scope.launch { alignSong(s, urgent = true) }
    }

    /** Throws away the words' times and makes them again from the lyrics' own line times. */
    fun resync(s: Song) {
        if (_busy.value == s.id || s.id in _waiting.value) return
        val r = recOf(s.id) ?: return
        val src = sourceOf(r) ?: return
        r.put("syncedSrc", src); r.put("synced", src); r.remove("aligned"); r.remove("alignGen")
        judged.remove(s.id)
        _failed.value = _failed.value - s.id
        Store.save()
        scope.launch { alignSong(s, urgent = true) }
    }

    /** Times the words of one song on this phone and saves them (as enhanced LRC) in place of the lyrics' own line-only version. Returns true when the words were timed. */
    suspend fun alignSong(s: Song, urgent: Boolean = false): Boolean {
        _waiting.value = _waiting.value + s.id
        return gate.withLock {
            _waiting.value = _waiting.value - s.id
            _busy.value = s.id; _progress.value = 0.02f
            try { withContext(Dispatchers.Default) { run(s, urgent) } }
            finally { _busy.value = null; _progress.value = 0f }
        }
    }

    private suspend fun run(s: Song, urgent: Boolean): Boolean {
        val rec = recOf(s.id) ?: return false
        fun mark() { rec.put("alignGen", GEN); Store.save() }       // a failed try isn't repeated either
        fun fail(why: String): Boolean { Diag.log("word timing ${s.title}: $why"); mark(); _failed.value = _failed.value + s.id; return false }
        try {
            android.os.Process.setThreadPriority(if (urgent) android.os.Process.THREAD_PRIORITY_DEFAULT else android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val src = sourceOf(rec)
            val lines = if (src != null) Online.parseLrc(src) else emptyList()
            if (src == null || lines.size < 2) return fail("no timed lines to work from")
            if (!Voice.load()) return fail("the timing engine isn't available on this phone")
            val offsets = Store.settings().optJSONObject("offsets")
            val offset = offsets?.optDouble(s.id, 0.0)?.takeIf { !it.isNaN() } ?: 0.0
            val pcm = PcmLoader.load(File(Store.app.filesDir, s.path)) { _progress.value = 0.02f + 0.33f * it }
            if (pcm.n < PcmLoader.RATE * 8) return fail("the song is too short")
            val out = WordTiming.align(pcm, lines, offset) { _progress.value = it }
                ?: return fail("no line could be lined up with the singing")
            rec.put("syncedSrc", src); rec.put("synced", out.trimEnd()); rec.put("aligned", true); rec.put("alignGen", GEN)
            offsets?.remove(s.id)            // the times are in the song's own time now: any nudge made for the old ones is already in them
            Store.save()
            _failed.value = _failed.value - s.id
            withContext(Dispatchers.Main) { Lyr.bump() }
            return true
        } catch (e: Throwable) {
            return fail(e.message ?: e.javaClass.simpleName)
        }
    }
}
