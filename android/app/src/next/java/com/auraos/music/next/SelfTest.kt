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
                    val t0 = System.currentTimeMillis()
                    val p = PcmLoader.load(f)
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
                    Log.w("Strata", "DECODETEST file=${f.name} length=${"%.3f".format(java.util.Locale.US, p.n.toDouble() / PcmLoader.RATE)} onsets=${on.joinToString(",")} peaks=${pk.joinToString(",")} ms=${System.currentTimeMillis() - t0} info=[${p.info}]")
                } catch (e: Throwable) { Log.w("Strata", "DECODETEST file=${f.name} error=${e.javaClass.simpleName}: ${e.message}") }
            }
            Log.w("Strata", "DECODETEST done")
        }
    }
}
