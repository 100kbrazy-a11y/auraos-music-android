package com.auraos.music.next

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Settings › Diagnostics: the last few hundred things the app did (also in logcat as "Strata"). */
object Diag {
    val lines = MutableStateFlow<List<String>>(emptyList())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    fun log(m: String) {
        Log.d("Strata", m)
        val l = "${fmt.format(Date())}  $m"
        synchronized(this) { lines.value = (lines.value + l).takeLast(400) }
    }
}

/** Short messages at the bottom of the screen */
object Toasts {
    val flow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun show(m: String) { flow.tryEmit(m) }
}

object Net {
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 20000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "StrataMusic/2.0 (Android; https://github.com/100kbrazy-a11y/auraos-music-android)")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        val code = c.responseCode
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return c.inputStream.use { it.readBytes() }
    }
    fun json(url: String, headers: Map<String, String> = emptyMap(), timeout: Int = 20000): Any { val t = get(url, headers, timeout).toString(Charsets.UTF_8).trim(); return if (t.startsWith("[")) JSONArray(t) else JSONObject(t) }
    fun q(s: String): String = URLEncoder.encode(s, "UTF-8")
}

object Images {
    private val colors = ConcurrentHashMap<String, IntArray>()
    fun forget() { colors.clear() }

    /** a square JPEG, centre-cropped, at most [size] px (null if it isn't a picture) */
    fun squareJpeg(bytes: ByteArray, size: Int): ByteArray? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (b == null) null else {
            val side = minOf(b.width, b.height); val out = minOf(side, size)
            val dst = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
            Canvas(dst).drawBitmap(b, Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2), Rect(0, 0, out, out), Paint(Paint.FILTER_BITMAP_FLAG))
            val bo = ByteArrayOutputStream(); dst.compress(Bitmap.CompressFormat.JPEG, 90, bo); b.recycle(); dst.recycle(); bo.toByteArray()
        }
    } catch (_: Throwable) { null }

    /** the cover's two main colours (ARGB), worked out from a tiny copy; cached */
    fun palette(f: File): IntArray {
        colors[f.path]?.let { return it }
        val res = try {
            val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 16 })
            if (b == null) intArrayOf(0xFF3A2F5C.toInt(), 0xFF1F3A4A.toInt()) else {
                val s = Bitmap.createScaledBitmap(b, 24, 24, true)
                val buckets = HashMap<Int, IntArray>()       // key -> [count, r, g, b]
                for (y in 0 until 24) for (x in 0 until 24) {
                    val c = s.getPixel(x, y); val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
                    val mx = maxOf(r, g, bl); val mn = minOf(r, g, bl)
                    if (mx < 30 || (mx > 235 && mn > 220)) continue
                    val k = (r / 48) * 100 + (g / 48) * 10 + bl / 48
                    val e = buckets.getOrPut(k) { IntArray(4) }
                    val w = 1 + (mx - mn) / 24                    // colourful pixels count more
                    e[0] += w; e[1] += r * w; e[2] += g * w; e[3] += bl * w
                }
                b.recycle(); s.recycle()
                val top = buckets.values.sortedByDescending { it[0] }
                fun col(e: IntArray) = (0xFF shl 24) or ((e[1] / e[0]) shl 16) or ((e[2] / e[0]) shl 8) or (e[3] / e[0])
                val a = top.getOrNull(0)?.let(::col) ?: 0xFF3A2F5C.toInt()
                val c2 = top.drop(1).firstOrNull { e -> dist(col(e), a) > 60 }?.let(::col) ?: shade(a)
                intArrayOf(a, c2)
            }
        } catch (_: Throwable) { intArrayOf(0xFF3A2F5C.toInt(), 0xFF1F3A4A.toInt()) }
        colors[f.path] = res
        return res
    }
    private fun dist(a: Int, b: Int): Int = Math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) + Math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) + Math.abs((a and 255) - (b and 255))
    private fun shade(c: Int): Int = (0xFF shl 24) or ((((c shr 16) and 255) * 6 / 10) shl 16) or ((((c shr 8) and 255) * 6 / 10) shl 8) or ((c and 255) * 6 / 10)
}
