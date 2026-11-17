package com.auraos.music.next

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Moves everything from the WebView app into the native app: songs, playlists, favourites, lyrics, artist info,
 * settings, the session (queue + position), covers, and any song still kept inside the database.
 *
 * The WebView app's data is in IndexedDB "auraos-music" under the origin https://localhost (Capacitor). A hidden
 * WebView opened on that same origin, in the same app, can read it. Nothing is deleted from the old database, so the
 * old app still works if this one is rolled back. This only finds anything once the native app IS the real app
 * (same package); in the separate "Next" test app the database is simply empty.
 */
object Migrate {
    val state = MutableStateFlow("")
    private val marker get() = File(Store.root, "migrated.json")
    fun done(): Boolean = marker.exists()

    private const val JS = """
(async () => {
  const M = window.Mig;
  const req = (r) => new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
  try {
    const db = await new Promise((res, rej) => {
      const r = indexedDB.open("auraos-music");
      r.onupgradeneeded = () => { r.transaction.abort(); };
      r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error || new Error("none"));
    }).catch(() => null);
    if (!db || !db.objectStoreNames.contains("songs")) { M.done(JSON.stringify({ none: true })); return; }
    const st = (n) => db.transaction(n).objectStore(n);
    const songs = await req(st("songs").getAll());
    M.json("songs", JSON.stringify(songs));
    M.json("meta", JSON.stringify((await req(st("kv").get("meta"))) || {}));
    M.json("session", JSON.stringify((await req(st("kv").get("session"))) || {}));
    const send = async (kind, key, blob) => {
      if (!blob || !blob.size) return;
      M.begin(kind, String(key));
      const CH = 786432;
      for (let i = 0; i < blob.size; i += CH) {
        const b = new Uint8Array(await blob.slice(i, i + CH).arrayBuffer());
        let s = ""; for (let j = 0; j < b.length; j += 32768) s += String.fromCharCode.apply(null, b.subarray(j, j + 32768));
        M.chunk(btoa(s));
      }
      M.end();
    };
    const ck = await req(st("covers").getAllKeys());
    for (let i = 0; i < ck.length; i++) { M.progress("Covers " + (i + 1) + " of " + ck.length); const c = await req(st("covers").get(ck[i])); await send("cover", ck[i], c && (c.big || c.small)); }
    const fk = await req(st("files").getAllKeys());
    for (let i = 0; i < fk.length; i++) { M.progress("Songs kept in the database " + (i + 1) + " of " + fk.length); await send("file", fk[i], await req(st("files").get(fk[i]))); }
    M.done(JSON.stringify({ songs: songs.length, covers: ck.length, files: fk.length }));
  } catch (e) { M.fail(String(e && e.message || e)); }
})();
"""

    private class Bridge(val got: HashMap<String, String>, val result: CompletableDeferred<String>) {
        var out: FileOutputStream? = null; var outFile: File? = null; var dest: File? = null
        @JavascriptInterface fun json(name: String, text: String) { got[name] = text }
        @JavascriptInterface fun progress(t: String) { state.value = t }
        @JavascriptInterface fun begin(kind: String, key: String) {
            dest = if (kind == "cover") Store.coverFile(key) else File(Store.app.filesDir, "Songs/$key")
            val tmp = File(Store.app.cacheDir, "mig-${System.nanoTime()}"); outFile = tmp; out = FileOutputStream(tmp)
        }
        @JavascriptInterface fun chunk(b64: String) { out?.write(Base64.decode(b64, Base64.DEFAULT)) }
        @JavascriptInterface fun end() {
            out?.close(); out = null
            val d = dest ?: return; val t = outFile ?: return
            if (d.exists() && d.length() > 0) { t.delete(); return }       // (already moved over: keep it)
            d.parentFile?.mkdirs(); if (!t.renameTo(d)) { t.copyTo(d, true); t.delete() }
        }
        @JavascriptInterface fun done(j: String) { result.complete(j) }
        @JavascriptInterface fun fail(m: String) { result.completeExceptionally(RuntimeException(m)) }
    }

    /** Runs once (automatically on first start); [force] runs it again (Settings), merging without duplicates. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(force: Boolean = false): String {
        if (done() && !force) return ""
        state.value = "Looking for your music from Strata Music…"
        val got = HashMap<String, String>()
        val result = CompletableDeferred<String>()
        var web: WebView? = null
        val summary = try {
            withContext(Dispatchers.Main) {
                val w = WebView(Store.app)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.addJavascriptInterface(Bridge(got, result), "Mig")
                w.webViewClient = object : WebViewClient() {
                    var ran = false
                    override fun onPageFinished(view: WebView, url: String?) { if (!ran) { ran = true; view.evaluateJavascript(JS, null) } }
                }
                web = w
                w.loadDataWithBaseURL("https://localhost/", "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
            }
            JSONObject(withTimeoutOrNull(30 * 60 * 1000L) { result.await() } ?: throw RuntimeException("took too long"))
        } catch (e: Exception) {
            Diag.log("migration failed: ${e.message}"); state.value = ""
            withContext(Dispatchers.Main) { web?.destroy() }
            return "Couldn't read the old app's music: ${e.message}"
        }
        withContext(Dispatchers.Main) { web?.destroy() }
        if (summary.optBoolean("none")) { state.value = ""; if (!force) marker.writeText(JSONObject().put("none", true).toString()); return "There was nothing to bring over." }
        return withContext(Dispatchers.IO) { merge(got, summary) }
    }

    private fun merge(got: Map<String, String>, summary: JSONObject): String {
        state.value = "Putting your library together…"
        val arr = JSONArray(got["songs"] ?: "[]")
        val have = Store.songs.value.map { it.id }.toHashSet()
        val add = ArrayList<Song>(); var missing = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") in have) continue
            val f = File(Store.app.filesDir, "Songs/" + o.optString("file"))
            if (!f.exists()) { missing++; o.put("missingFile", true) }
            o.put("store", "disk")
            add.add(Song(o))
        }
        Store.songs.value = Store.songs.value + add
        // meta: the old one wins for anything the native app doesn't have yet; nothing already here is lost
        val old = JSONObject(got["meta"] ?: "{}")
        val m = Store.meta
        for (k in old.keys()) {
            val v = old.get(k)
            val cur = m.opt(k)
            if (cur is JSONObject && v is JSONObject) { for (kk in v.keys()) if (!cur.has(kk)) cur.put(kk, v.get(kk)) }
            else if (cur is JSONArray && v is JSONArray) { val s = (0 until cur.length()).map { cur.get(it).toString() }.toHashSet(); for (x in 0 until v.length()) if (s.add(v.get(x).toString())) cur.put(v.get(x)) }
            else if (cur == null) m.put(k, v)
        }
        val ss = JSONObject(got["session"] ?: "{}")
        if (ss.has("queue")) m.put("oldSession", ss)
        Store.metaChanged(); Store.save(true)
        Prefs.reload()
        if (ss.has("queue")) Ctl.adoptOldSession(ss)
        val res = JSONObject(summary.toString()).put("added", add.size).put("missingFiles", missing).put("at", System.currentTimeMillis())
        marker.writeText(res.toString())
        Diag.log("migration: $res")
        state.value = ""
        Tasks.poke()
        return "Brought over ${add.size} songs, your playlists, favourites, lyrics and settings" + (if (missing > 0) " · $missing songs had no file" else "")
    }
}
