package com.auraos.music.next

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Decoder self-test, started by CI with an intent (never by a person): reads each test file in the app's own external folder the way the word timing
 * does, finds the loud bursts in it and writes where they start. CI knows where each burst really is, so a late or early decoder shows up as numbers.
 */
object SelfTest {
    fun run(ctx: Context, arg: String) {
        val dir = ctx.getExternalFilesDir("decodetest") ?: return
        if (arg == "init") { Log.w("Strata", "DECODETEST dir=${dir.absolutePath}"); return }
        thread(name = "decodetest") {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
            Log.w("Strata", "DECODETEST files=${files.size}")
            for (f in files) {
                try {
                    if (f.name.startsWith("p")) {
                        // long test songs: read in one go and in 3 pieces, the bursts in both must be in the same places
                        val a0 = System.currentTimeMillis(); val one = PcmLoader.loadInParts(f, 1)
                        val a1 = System.currentTimeMillis(); val three = PcmLoader.loadInParts(f, 3)
                        val a2 = System.currentTimeMillis()
                        val (o1, _) = bursts(one); val (o3, _) = bursts(three)
                        Log.w("Strata", "DECODEPAR file=${f.name} one_ms=${a1 - a0} three_ms=${a2 - a1} one_len=${"%.3f".format(java.util.Locale.US, one.n.toDouble() / PcmLoader.RATE)} three_len=${"%.3f".format(java.util.Locale.US, three.n.toDouble() / PcmLoader.RATE)} one_on=${o1.joinToString(",")} three_on=${o3.joinToString(",")} info=[${three.info}]")
                        continue
                    }
                    val t0 = System.currentTimeMillis()
                    val p = PcmLoader.load(f)
                    val (on, pk) = bursts(p)
                    Log.w("Strata", "DECODETEST file=${f.name} length=${"%.3f".format(java.util.Locale.US, p.n.toDouble() / PcmLoader.RATE)} onsets=${on.joinToString(",")} peaks=${pk.joinToString(",")} ms=${System.currentTimeMillis() - t0} info=[${p.info}]")
                } catch (e: Throwable) { Log.w("Strata", "DECODETEST file=${f.name} error=${e.javaClass.simpleName}: ${e.message}") }
            }
            Log.w("Strata", "DECODETEST done")
        }
    }

    /** where the loud bursts in a read song start (seconds) and how loud each is */
    private fun bursts(p: Pcm): Pair<List<String>, List<String>> {
        val thr = (0.12 * 32768).toInt(); val quiet = (PcmLoader.RATE * 0.5).toInt()
        val on = ArrayList<String>(); val pk = ArrayList<String>()
        var last = -quiet * 2; var cur = 0; var i = 0
        while (i < p.n) {
            val a = Math.abs(p.left[i].toInt())
            if (a >= thr) {
                if (i - last > quiet) { if (on.isNotEmpty()) pk.add(cur.toString()); on.add("%.3f".format(java.util.Locale.US, i.toDouble() / PcmLoader.RATE)); cur = 0 }
                last = i
            }
            if (on.isNotEmpty() && i - last <= quiet) cur = Math.max(cur, a)
            i++
        }
        if (on.isNotEmpty()) pk.add(cur.toString())
        return Pair(on, pk)
    }

    /** Speech model test, started by CI: downloads the model for real, listens to a clip of read speech whose words are known, and writes where each word was found. */
    fun asr(ctx: Context, arg: String) {
        val dir = ctx.getExternalFilesDir("asrtest") ?: return
        if (arg == "init") { Log.w("Strata", "ASRTEST dir=${dir.absolutePath}"); return }
        thread(name = "asrtest") {
            try {
                val f = File(dir, "speech.m4a")
                val t0 = System.currentTimeMillis()
                val ok = kotlinx.coroutines.runBlocking { SpeechModel.ensure() }
                Log.w("Strata", "ASRTEST model ready=$ok download_ms=${System.currentTimeMillis() - t0}")
                if (!ok) { Log.w("Strata", "ASRTEST error=model not available: ${SpeechModel.state.value.let { (it as? SpeechModel.St.Failed)?.why }}"); return@thread }
                val pcm = PcmLoader.load(f)
                val secs = pcm.n.toDouble() / PcmLoader.RATE
                val t1 = System.currentTimeMillis()
                val sheet = SpeechModel.sheet(secs); val vocab = SpeechModel.vocab
                for (c in 0 until SpeechModel.steps(secs)) SpeechModel.listen(pcm, c, sheet)
                val t2 = System.currentTimeMillis()
                val words = listOf("after early nightfall the yellow lamps would light up here and there the squalid quarter of the brothels".split(" "))
                val r = CtcAlign.align(sheet, vocab, words, doubleArrayOf(0.0), booleanArrayOf(false))[0]
                if (r == null) { Log.w("Strata", "ASRTEST error=no line found"); return@thread }
                Log.w("Strata", "ASRTEST length=${"%.2f".format(java.util.Locale.US, secs)} conf=${"%.2f".format(java.util.Locale.US, r.conf)} listen_ms=${t2 - t1} starts=${r.starts.joinToString(",") { "%.2f".format(java.util.Locale.US, it) }}")
            } catch (e: Throwable) { Log.w("Strata", "ASRTEST error=${e.javaClass.simpleName}: ${e.message}") }
            finally { Log.w("Strata", "ASRTEST done") }
        }
    }
}
