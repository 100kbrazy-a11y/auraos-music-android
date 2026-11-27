package com.auraos.music.next

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * The same sample library the WebView app was screenshotted with (for side-by-side checks in CI only).
 * Runs only when the app is started with `--ez seedDemo true` and the library is empty.
 */
object Demo {
    fun seed() {
        if (Store.songs.value.isNotEmpty()) return
        val cols = listOf(0xe05a7a to 0x3a1c5c, 0x3fb6d6 to 0x123a55, 0xf2b84b to 0x7a2e12, 0x7be09a to 0x1d4a3a, 0xb07cf0 to 0x2a1a50, 0xf07c5a to 0x40203a, 0x5a7cf0 to 0x101c40, 0xe0e05a to 0x4a3a10)
        val artists = listOf("Nova Rae", "The Midnight Hours", "Kairo", "Lumen Drift", "Sable & Fox", "Orion Vale")
        val albums = listOf("Neon Tides", "Glass Hearts", "Afterglow", "Paper Moons", "Low Tide", "Satellites", "Velvet Sky", "Echo Park")
        val titles = listOf("Starlight", "Run Away", "Golden Hour", "Blue", "Falling Slow", "Wildfire", "Daydream", "Midnight Drive", "Ocean Eyes", "Paper Planes", "Static", "Halo")
        val genres = listOf("Pop", "Electronic", "R&B", "Indie")
        for (a in 0 until 8) {
            val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val (x, y) = cols[a]
            c.drawRect(0f, 0f, 600f, 600f, Paint().apply { shader = LinearGradient(0f, 0f, 600f, 600f, (0xFF shl 24) or x, (0xFF shl 24) or y, Shader.TileMode.CLAMP) })
            c.drawCircle(390f, 240f, 132f, Paint().apply { color = 0x40FFFFFF; isAntiAlias = true })
            val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 90, o)
            Store.coverFile("c$a").apply { parentFile?.mkdirs(); writeBytes(o.toByteArray()) }
        }
        val wav = wav()
        val out = ArrayList<Song>(); var n = 0; val now = System.currentTimeMillis()
        for (a in 0 until 8) for (t in 0 until (if (a < 3) 10 else 5)) {
            val title = titles[(n * 7) % 12] + if (n > 11) " ${n % 5 + 1}" else ""
            val rel = Store.organizedPath(artists[a % 6], artists[a % 6], albums[a], t + 1, 0, title, "wav")
            File(Store.app.filesDir, "Songs/$rel").apply { parentFile?.mkdirs(); writeBytes(wav) }
            out.add(Song(JSONObject().put("id", "s$n").put("title", title).put("artist", artists[a % 6]).put("albumartist", artists[a % 6]).put("album", albums[a])
                .put("year", "${2016 + a}").put("genre", genres[a % 4]).put("duration", 180 + (n * 37) % 120).put("file", rel).put("type", "audio/wav")
                .put("added", now - n * 86400000L).put("track", t + 1).put("store", "disk").put("cover", "c$a").put("silenceChecked", 1).put("tempoChecked", 1).put("compressed", 2).put("autofilled", 1)))
            n++
        }
        Store.songs.value = out
        val ids = out.map { it.id }
        val fav = JSONObject(); ids.take(9).forEachIndexed { i, id -> fav.put(id, now - i) }
        Store.meta.put("favorites", fav)
        Store.meta.put("playlists", JSONObject().put("Late Night Drive", JSONArray(ids.subList(3, 15))).put("Gym", JSONArray(ids.subList(20, 32))).put("Chill", JSONArray(ids.subList(40, 48))))
        Store.settings().put("crossfade", 6)
        Store.metaChanged(); Store.save(true)
        File(Store.root, "migrated.json").writeText("{\"none\":true}")
    }
    private fun wav(): ByteArray {
        val sr = 8000; val n = sr * 30; val b = ByteArray(44 + n * 2)
        fun s(o: Int, t: String) { for (i in t.indices) b[o + i] = t[i].code.toByte() }
        fun i32(o: Int, v: Int) { for (k in 0..3) b[o + k] = (v shr (8 * k)).toByte() }
        fun i16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        s(0, "RIFF"); i32(4, 36 + n * 2); s(8, "WAVE"); s(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 1); i32(24, sr); i32(28, sr * 2); i16(32, 2); i16(34, 16); s(36, "data"); i32(40, n * 2)
        for (i in 0 until n) i16(44 + i * 2, (Math.sin(i / 20.0) * 3000 * Math.sin(i / 4000.0)).toInt())
        return b
    }
}
