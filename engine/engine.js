// The whole "browser" part of the native app. It runs inside a hidden WebView that exists ONLY while the app searches or
// downloads (the app creates it, and destroys it a few seconds after the last job). Everything else in the app is native.
// The page is loaded as https://www.youtube.com so YouTube's proof-of-origin check (BotGuard) runs on its own origin.
// Native -> JS: __eng.run(id, fn, argJson) ;  JS -> native: Android.result / Android.fail / Android.fetch / Android.log
import * as mod from "youtubei.js/web";
import "../src/potoken-page.js";

const { Innertube, Platform, UniversalCache } = mod;
const CLIENTS = ["YTMUSIC", "MWEB", "WEB_EMBEDDED", "IOS", "ANDROID_VR", "VISIONOS", "TV_SIMPLY"];
const WEBBY = new Set(["YTMUSIC", "MWEB", "WEB_EMBEDDED", "WEB"]);
const log = (m) => { try { Android.log(String(m)); } catch {} };

// ---- networking goes through the app (no browser cross-site blocking): Android.fetch -> window.__fx / __fxe
const waiting = new Map();
let fseq = 1;
window.__fx = (id, status, hdrJson, b64) => {
  const w = waiting.get(id); if (!w) return; waiting.delete(id);
  const bin = atob(b64); const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  const h = new Headers();
  try { for (const [k, v] of Object.entries(JSON.parse(hdrJson))) { try { h.set(k, String(v)); } catch {} } } catch {}
  const s = status >= 200 && status <= 599 ? status : 500;
  w.resolve(new Response([204, 205, 304].includes(s) ? null : bytes, { status: s, headers: h }));
};
window.__fxe = (id, msg) => { const w = waiting.get(id); if (w) { waiting.delete(id); w.reject(new Error(msg)); } };

async function nfetch(input, init = {}) {
  const req = input instanceof Request ? input : new Request(input, init);
  const method = (init.method || req.method || "GET").toUpperCase();
  const headers = {};
  new Headers(init.headers || req.headers).forEach((v, k) => { headers[k] = v; });
  let body = "";
  if (method !== "GET" && method !== "HEAD") {
    body = typeof init.body === "string" ? init.body : init.body != null ? await new Response(init.body).text() : await req.clone().text();
  }
  const id = fseq++;
  return new Promise((resolve, reject) => {
    waiting.set(id, { resolve, reject });
    Android.fetch(id, req.url, method, JSON.stringify(headers), body);
  });
}

const timed = (p, ms, what) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error(`${what}: timed out`)), ms))]);

let ytp = null;
function client() {
  if (!ytp) {
    ytp = (async () => {
      Platform.load({ ...Platform.shim, eval: (data) => new Function(data.output)() });
      return await timed(Innertube.create({ fetch: nfetch, cache: new UniversalCache(false), retrieve_player: true }), 45000, "Connecting to YouTube");
    })().catch((e) => { ytp = null; throw e; });
  }
  return ytp;
}

const txt = (x) => (x == null ? "" : typeof x === "string" ? x : x.text ?? x.toString?.() ?? "");
const thumbOf = (t) => { const a = Array.isArray(t) ? t : t ? [t] : []; return a.at(-1)?.url || ""; };
const names = (xs) => (xs || []).map((a) => a?.name).filter(Boolean).join(", ");

async function token(id) {
  try { return await timed(window.__auraMint(id), 60000, "Getting YouTube's OK"); }
  catch (e) { log("no token: " + (e.message || e)); return ""; }
}

let goodClient = "";
/** Finds the audio address for one video. Returns what native needs to download it. */
async function audio(id) {
  const yt = await client();
  const pot = await token(id);
  let order = [...CLIENTS];
  if (goodClient) order = [goodClient, ...order.filter((c) => c !== goodClient)];
  const reasons = [];
  for (const name of order) {
    if (!pot && WEBBY.has(name)) continue;
    try {
      const info = await timed(yt.getBasicInfo(id, pot ? { client: name, po_token: pot } : { client: name }), 30000, `Asking YouTube (${name})`);
      const ps = info.playability_status;
      if (ps && ps.status && ps.status !== "OK") { reasons.push(`${name}: ${ps.status}`); continue; }
      let fmt;
      try { fmt = info.chooseFormat({ type: "audio", quality: "best", format: "webm" }); } catch {}
      if (!fmt) { try { fmt = info.chooseFormat({ type: "audio", quality: "best", format: "mp4" }); } catch { reasons.push(`${name}: no audio`); continue; } }
      let url = await timed(fmt.decipher(yt.session.player), 30000, "Unscrambling the audio address");
      if (pot) { const u = new URL(url); u.searchParams.set("pot", pot); url = u.toString(); }
      const opus = /webm|opus/i.test(fmt.mime_type || "");
      const b = info.basic_info || {};
      goodClient = name;
      return {
        url, size: +fmt.content_length || 0, ext: opus ? "webm" : "m4a", client: name,
        ua: mod.Constants?.CLIENTS?.[name]?.USER_AGENT || navigator.userAgent,
        origin: WEBBY.has(name) ? (name === "YTMUSIC" ? "https://music.youtube.com" : "https://www.youtube.com") : "",
        kbps: Math.round((+fmt.bitrate || +fmt.average_bitrate || 0) / 1000),
        title: b.title || "", artist: b.author || "", duration: b.duration || 0, thumb: thumbOf(b.thumbnail),
      };
    } catch (e) { reasons.push(`${name}: ${String(e.message || e).replace(/https:\/\/\S+/, "(address)")}`); log(reasons.at(-1)); }
  }
  throw new Error(`YouTube refused every way in (${reasons[0] || "no answer"})`);
}

/** arg = {q, type} -> [{kind,id,title,artist,album,thumb,sub,duration}] */
async function search({ q, type }) {
  const yt = await client();
  const res = await timed(yt.music.search(q, { type }), 30000, "Searching YouTube Music");
  const items = [];
  for (const shelf of res.contents || []) for (const it of shelf?.contents || []) if (it?.type === "MusicResponsiveListItem" || it?.flex_columns) items.push(it);
  const out = [], seen = new Set();
  for (const it of items) {
    const thumb = thumbOf(it.thumbnail?.contents ?? it.thumbnails ?? it.thumbnail);
    let r = null;
    if (type === "song" && /^[\w-]{11}$/.test(it.id || "")) {
      const artist = names(it.artists || it.authors) || it.author?.name || "";
      r = { kind: "song", id: it.id, title: txt(it.title), artist, album: it.album?.name || "", duration: it.duration?.seconds || 0, thumb,
        sub: [artist, it.album?.name, it.duration?.text].filter(Boolean).join(" · ") };
    } else if (type === "album" && /^MPREb/.test(it.id || "")) {
      const artist = names(it.artists || it.authors) || it.author?.name || "";
      r = { kind: "album", id: it.id, title: txt(it.title), artist, album: txt(it.title), thumb, sub: ["Album", artist, it.year].filter(Boolean).join(" · ") };
    } else if (type === "playlist" && it.id) {
      const id = String(it.id).replace(/^VL/, "");
      r = { kind: "playlist", id, title: txt(it.title), artist: "", album: "", thumb, sub: ["Playlist", it.author?.name || names(it.authors)].filter(Boolean).join(" · ") };
    }
    if (r && r.title && !seen.has(r.kind + r.id)) { seen.add(r.kind + r.id); out.push(r); }
  }
  return out;
}

/** arg = {kind:"song"|"album"|"playlist", id} or {link} -> {title, tracks:[{id,title,artist,album,thumb}]} */
async function tracks(a) {
  const yt = await client();
  let { kind, id } = a;
  if (a.link) {
    const t = String(a.link);
    const list = t.match(/[?&]list=([\w-]+)/)?.[1];
    const vid = t.match(/[?&]v=([\w-]{11})/)?.[1] || t.match(/youtu\.be\/([\w-]{11})/)?.[1];
    if (list && !/^(RD|LM)/.test(list)) { kind = "playlist"; id = list; } else if (vid) { kind = "song"; id = vid; } else throw new Error("That doesn't look like a YouTube link");
  }
  if (kind === "song") {
    const info = await timed(yt.getBasicInfo(id), 30000, "Reading the song");
    const b = info.basic_info || {};
    return { title: b.title || "", tracks: [{ id, title: b.title || "", artist: b.author || "", album: "", thumb: thumbOf(b.thumbnail) }] };
  }
  if (kind === "album") {
    const al = await timed(yt.music.getAlbum(id), 30000, "Reading the album");
    const album = txt(al.header?.title);
    const cover = thumbOf(al.header?.thumbnails ?? al.header?.thumbnail);
    const out = (al.contents || []).filter((t) => /^[\w-]{11}$/.test(t.id || "")).map((t) => ({
      id: t.id, title: txt(t.title), artist: names(t.artists || t.authors) || txt(al.header?.author?.name), album, thumb: cover,
    }));
    return { title: album, tracks: out };
  }
  const pl = await timed(yt.getPlaylist(id), 30000, "Reading the playlist");
  const out = (pl.items || []).filter((t) => /^[\w-]{11}$/.test(t.id || "")).map((t) => ({
    id: t.id, title: txt(t.title), artist: t.author?.name || "", album: "", thumb: thumbOf(t.thumbnails ?? t.thumbnail),
  }));
  return { title: txt(pl.info?.title), tracks: out };
}

const FN = { audio: (a) => audio(a), search, tracks };
window.__eng = {
  run(id, fn, argJson) {
    (async () => {
      try {
        const arg = argJson ? JSON.parse(argJson) : null;
        const res = await FN[fn](fn === "audio" ? String(arg) : arg);
        Android.result(id, JSON.stringify(res));
      } catch (e) { Android.fail(id, String(e?.message || e)); }
    })();
  },
};
