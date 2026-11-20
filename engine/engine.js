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
const thumbOf = (t) => { const a = Array.isArray(t) ? t : Array.isArray(t?.contents) ? t.contents : t ? [t] : []; return a.at(-1)?.url || ""; };
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
    } else if (type === "artist" && /^UC/.test(it.id || "")) {
      r = { kind: "artist", id: it.id, title: it.name || txt(it.title), artist: "", album: "", thumb, sub: ["Artist", it.subscribers].filter(Boolean).join(" · ") };
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
    const al = await timed(yt.music.getAlbum(id), 45000, "Reading the album");
    const hdr = al.header || {};
    const album = (txt(hdr.title) || "Album").replace(/^(Album|Single|EP)\s*-\s*/i, "");
    const artist = a.artist || (hdr.strapline_text_one ? txt(hdr.strapline_text_one) : "") || hdr.author?.name || (txt(hdr.subtitle).split(/\s*•\s*/)[1] || "");
    const cover = thumbOf(hdr.thumbnails ?? hdr.thumbnail);
    let n = 0; const out = [];
    for (const t of al.contents || []) {
      if (!/^[\w-]{11}$/.test(t.id || "")) continue;
      n++;
      out.push({ id: t.id, title: txt(t.title), artist: names(t.artists || t.authors) || artist, albumartist: artist, album, year: String(hdr.year || (txt(hdr.subtitle).match(/\b(19|20)\d\d\b/) || [""])[0]), thumb: cover || thumbOf(t.thumbnail), track: parseInt(txt(t.index), 10) || n });
    }
    return { title: album, tracks: out };
  }
  let pl = await timed(yt.getPlaylist(id), 30000, "Reading the playlist");
  const title = txt(pl.info?.title);
  // YouTube hands a playlist over 100 songs at a time: keep asking for the next page until there isn't one
  let items = [...(pl.items || [])];
  for (let page = 0; pl.has_continuation && page < 80; page++) {
    try { pl = await timed(pl.getContinuation(), 30000, "Reading more of the playlist"); } catch (e) { break; }
    const more = pl.items || [];
    if (!more.length) break;
    items = items.concat([...more]);
  }
  const seen = new Set();
  const out = items.filter((t) => /^[\w-]{11}$/.test(t.id || "") && !seen.has(t.id) && seen.add(t.id)).map((t) => ({
    id: t.id, title: txt(t.title), artist: t.author?.name || "", album: "", thumb: thumbOf(t.thumbnails ?? t.thumbnail),
  }));
  return { title, tracks: out };
}

/** An artist's page: name, photo, albums + singles/EPs, top songs */
async function artist(id) {
  const yt = await client();
  const page = await timed(yt.music.getArtist(id), 45000, "Reading the artist page");
  const hdr = page.header || {};
  const name = txt(hdr.title).replace(/\s*-\s*Topic$/i, "").trim();
  const sections = page.sections || [];
  const out = new Map();
  const add = (it, single) => {
    const bid = it?.id || it?.endpoint?.payload?.browseId || "";
    if (!/^MPREb/.test(bid) || out.has(bid)) return;
    out.set(bid, { id: bid, title: txt(it.title), year: String(it.year || (txt(it.subtitle).match(/\b(19|20)\d\d\b/) || [""])[0]), single, thumb: thumbOf(it.thumbnail) });
  };
  for (const sec of sections) {
    const title = txt(sec.header?.title ?? sec.title).toLowerCase();
    if (!/album|single|\bep\b/.test(title)) continue;
    const single = /single|\bep\b/.test(title) && !/album/.test(title);
    for (const it of sec.contents || []) add(it, single);
    const more = sec.header?.more_content?.endpoint || sec.endpoint;
    if (more?.call && mod.YTNodes?.MusicTwoRowItem) {
      try {
        const p = await timed(more.call(yt.actions || yt.session?.actions, { client: "YTMUSIC", parse: true }), 45000, "Reading the full list");
        for (const it of p?.contents_memo?.getType?.(mod.YTNodes.MusicTwoRowItem) || []) add(it, single);
      } catch (e) { log("full list unavailable: " + (e.message || e)); }
    }
  }
  const top = [];
  for (const sec of sections) {
    if (!/song/i.test(txt(sec.title ?? sec.header?.title))) continue;
    for (const it of sec.contents || []) if (/^[\w-]{11}$/.test(it?.id || "")) top.push({ id: it.id, title: txt(it.title), artist: name, album: it.album?.name || "", thumb: thumbOf(it.thumbnail) });
    if (top.length) break;
  }
  const byYear = (x, y) => String(x.year || "9999").localeCompare(String(y.year || "9999"));
  return { id, name, thumb: thumbOf(hdr.thumbnail ?? hdr.thumbnails), albums: [...out.values()].sort(byYear), top };
}

/** {q} or {id} -> similar songs (YouTube Music's own "radio" for that song) */
async function radio(a) {
  const yt = await client();
  let vid = a.id;
  if (!vid) { const r = await search({ q: a.q, type: "song" }); vid = r[0]?.id; }
  if (!vid) throw new Error("Couldn't find that song on YouTube Music");
  const up = await timed(yt.music.getUpNext(vid, true), 30000, "Getting similar songs");
  const out = [];
  for (const it of up.contents || []) {
    const v = it.video_id || it.id;
    if (!/^[\w-]{11}$/.test(v || "") || v === vid) continue;
    out.push({ id: v, title: txt(it.title), artist: names(it.artists || it.authors) || txt(it.author), album: it.album?.name || "", thumb: thumbOf(it.thumbnail) });
  }
  return { title: "Radio", tracks: out.slice(0, 25) };
}

const FN = { audio: (a) => audio(a), search, tracks, artist: (a) => artist(a), radio };
window.__eng = {
  run(id, fn, argJson) {
    (async () => {
      try {
        const arg = argJson ? JSON.parse(argJson) : null;
        const res = await FN[fn](fn === "audio" || fn === "artist" ? String(arg) : arg);
        Android.result(id, JSON.stringify(res));
      } catch (e) { Android.fail(id, String(e?.message || e)); }
    })();
  },
};
