package com.auraos.music.next

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * The speech recogniser that word timing listens with (NVIDIA NeMo's small English Conformer-CTC, 8-bit): downloaded the first time it is needed
 * (about 76 MB, 46 MB once unpacked), then used on the phone with ONNX Runtime. Everything here can fail without harm: the timing then
 * carries on with the voice finder alone.
 */
object SpeechModel {
    private const val URL_TAR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-ctc-en-conformer-small.tar.bz2"
    private const val DIR_IN_TAR = "sherpa-onnx-nemo-ctc-en-conformer-small/"
    private const val SHA_MODEL = "66cfde4ce0b6da9e1a4352e58f0c968f87da23070cf229c81ae0d520cddad2a2"
    private const val SHA_TOKENS = "1adce7866285e6a4aac6208b120d5b8e4302b7cae5f2ae541c343097773fd988"

    /** seconds of song listened to per step: 20 s of the song in the middle of a 24 s window (2 s of context each side) */
    const val STEP_S = 20
    private const val CONTEXT_S = 2

    private fun dir() = File(Store.app.filesDir, "speech").also { it.mkdirs() }
    private fun modelFile() = File(dir(), "model.int8.onnx")
    private fun tokensFile() = File(dir(), "tokens.txt")
    private fun okFile() = File(dir(), "verified-1")

    /** true when the model is on the phone and checked */
    val ready: Boolean get() = okFile().exists() && modelFile().exists() && tokensFile().exists()

    sealed class St { object Idle : St(); class Downloading(val fraction: Float) : St(); class Failed(val why: String) : St() }
    private val _state = MutableStateFlow<St>(St.Idle)
    val state: StateFlow<St> = _state
    private val dl = Mutex()

    /** Downloads and unpacks the model if it isn't here yet. Returns true when it is ready. Only one download runs at a time. */
    suspend fun ensure(): Boolean {
        if (ready) return true
        if (dl.isLocked) return false                 // already on its way
        return dl.withLock {
            if (ready) return@withLock true
            withContext(Dispatchers.IO) {
                val tmpM = File(dir(), "model.part"); val tmpT = File(dir(), "tokens.part")
                try {
                    _state.value = St.Downloading(0f)
                    val c = URL(URL_TAR).openConnection() as HttpURLConnection
                    c.connectTimeout = 20000; c.readTimeout = 30000
                    if (c.responseCode != 200) throw RuntimeException("the download said ${c.responseCode}")
                    val total = c.contentLengthLong.coerceAtLeast(1L)
                    var got = 0L
                    val counting = object : java.io.FilterInputStream(c.inputStream) {
                        override fun read(): Int { val r = super.read(); if (r >= 0) got++; return r }
                        override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) { got += r; _state.value = St.Downloading((got.toFloat() / total).coerceIn(0f, 0.99f)) }; return r }
                    }
                    var haveM = false; var haveT = false
                    TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 1 shl 16))).use { tar ->
                        while (true) {
                            val e = tar.nextTarEntry ?: break
                            val name = e.name.removePrefix("./")
                            val out = when (name) { DIR_IN_TAR + "model.int8.onnx" -> tmpM; DIR_IN_TAR + "tokens.txt" -> tmpT; else -> null } ?: continue
                            out.outputStream().use { o -> tar.copyTo(o, 1 shl 16) }
                            if (out === tmpM) haveM = true else haveT = true
                            if (haveM && haveT) break
                        }
                    }
                    if (!haveM || !haveT) throw RuntimeException("the download didn't hold the model")
                    if (sha256(tmpM) != SHA_MODEL || sha256(tmpT) != SHA_TOKENS) throw RuntimeException("the downloaded model didn't check out")
                    modelFile().delete(); tokensFile().delete()
                    if (!tmpM.renameTo(modelFile()) || !tmpT.renameTo(tokensFile())) throw RuntimeException("couldn't keep the model")
                    okFile().writeText("1")
                    _state.value = St.Idle
                    Diag.log("speech model downloaded (${modelFile().length() / 1_000_000} MB)")
                    true
                } catch (e: Throwable) {
                    tmpM.delete(); tmpT.delete()
                    _state.value = St.Failed(e.message ?: e.javaClass.simpleName)
                    Diag.log("speech model download failed: ${e.javaClass.simpleName} ${e.message}")
                    false
                }
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** removes the model (Settings) */
    fun delete() { release(); dir().deleteRecursively(); _state.value = St.Idle }

    // ---- listening ----

    private class Engine(val env: OrtEnvironment, val session: OrtSession, val vocab: Vocab)
    @Volatile private var engine: Engine? = null

    private fun open(): Engine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val env = OrtEnvironment.getEnvironment()
            val so = OrtSession.SessionOptions()
            so.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            val session = env.createSession(modelFile().absolutePath, so)
            val vocab = Vocab(tokensFile().readLines())
            return Engine(env, session, vocab).also { engine = it }
        }
    }

    val vocab: Vocab get() = open().vocab

    /** frees the model's memory (done when a song's timing is over) */
    fun release() { synchronized(this) { try { engine?.session?.close() } catch (_: Throwable) { }; engine = null } }

    /** empty answer sheet for a song of this many seconds */
    fun sheet(seconds: Double): LogProbs { val e = open(); return LogProbs(((seconds + 1) / LogProbs.STEP).toInt() + 1, e.vocab.size, e.vocab.blank) }

    /** step number [c] of a song: 20 seconds listened to (with 2 s of context either side), written into [lp] */
    fun listen(pcm: Pcm, c: Int, lp: LogProbs) {
        val e = open()
        val RATE = WordTiming.RATE
        val ws = max(0, c * STEP_S - CONTEXT_S)                         // seconds
        val we = min(pcm.n.toDouble() / RATE, (c + 1.0) * STEP_S + CONTEXT_S)
        if (we - ws < 1.0) return
        val x = Resample16.mono(pcm, (ws.toLong() * RATE).toInt(), (we * RATE).toInt())
        val (feat, frames) = Fbank.compute(x)
        if (frames < 20) return
        // the model wants [1, 80, frames]
        val ft = FloatArray(Fbank.BINS * frames)
        for (f in 0 until frames) for (b in 0 until Fbank.BINS) ft[b * frames + f] = feat[f * Fbank.BINS + b]
        OnnxTensor.createTensor(e.env, FloatBuffer.wrap(ft), longArrayOf(1, Fbank.BINS.toLong(), frames.toLong())).use { a ->
            OnnxTensor.createTensor(e.env, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1)).use { l ->
                e.session.run(mapOf("audio_signal" to a, "length" to l)).use { res ->
                    @Suppress("UNCHECKED_CAST") val out = (res[0].value as Array<Array<FloatArray>>)[0]
                    // keep this step's own 20 s (the context either side only helped the model hear)
                    val firstKeep = if (c == 0) 0 else CONTEXT_S * 25
                    val g0 = ws * 25                                    // song frame (40 ms) of the window's first frame
                    val lastKeep = if (we >= pcm.n.toDouble() / RATE - 0.5) out.size else (CONTEXT_S + STEP_S) * 25
                    for (k in firstKeep until min(out.size, lastKeep)) {
                        val g = g0 + k
                        if (g >= lp.frames) break
                        System.arraycopy(out[k], 0, lp.data, g * lp.vocab, min(lp.vocab, out[k].size))
                        lp.heard[g] = true
                    }
                }
            }
        }
    }

    /** how many steps a song of this length has */
    fun steps(seconds: Double): Int = max(1, Math.ceil(seconds / STEP_S).toInt())
}
