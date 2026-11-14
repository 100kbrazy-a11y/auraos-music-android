// EXPERIMENTAL — "Add from link": a YouTube / YouTube Music song, album or playlist link, resolved inside the app
// with YouTube.js (youtubei.js). The audio (m4a) is downloaded natively and added to the library like any other song;
// a playlist / album link also makes a playlist in the same order.
//
// YouTube actively blocks downloaders, so this tries several of YouTube's own app identities (the ones that usually
// don't need a "proof of origin" token first) and logs every step to Settings › Diagnostics.
// Only add music you have the right to.
import { Capacitor, CapacitorHttp, registerPlugin } from "@capacitor/core";
import poScript from "./gen/potoken.iife.js?raw";
import { diag, timed } from "./diag.js";
import { getBlob } from "./http.js";
import { onDisk } from "./files.js";

const native = Capacitor.isNativePlatform();
// YouTube's app identities to try, in order (the first ones normally need no proof-of-origin token)
// With a proof-of-origin token (made below) YouTube Music's own web player identity works best; the others are fallbacks.
const CLIENTS = ["YTMUSIC", "MWEB", "WEB_EMBEDDED", "IOS", "ANDROID_VR", "VISIONOS", "TV_SIMPLY"];
const WEBBY = new Set(["YTMUSIC", "MWEB", "WEB_EMBEDDED", "WEB"]);

/** fetch for YouTube.js, through the app's native networking (no browser cross-site blocking) */
async function nativeFetch(input, init = {}) {
  const req = input instanceof Request ? input : new Request(input, init);
  if (!native) return fetch(req);
  const method = (init.method || req.method || "GET").toUpperCase();
  const headers = {};
  new Headers(init.headers || req.headers).forEach((v, k) => { headers[k] = v; });
  let data;
  if (method !== "GET" && method !== "HEAD") {
    const text = typeof init.body === "string" ? init.body : init.body != null ? await new Response(init.body).text() : await req.clone().text();
    try { data = JSON.parse(text); } catch { data = text; }
  }
  const r = await CapacitorHttp.request({ url: req.url, method, headers, data, responseType: "text", connectTimeout: 20000, readTimeout: 40000 });
  let body = r.data;
  if (body != null && typeof body !== "string") body = JSON.stringify(body);
  const status = r.status >= 200 && r.status <= 599 ? r.status : 500;
  const h = new Headers();
  for (const [k, v] of Object.entries(r.headers || {})) { try { h.set(k, String(v)); } catch {} }
  return new Response([204, 205, 304].includes(status) ? null : body ?? "", { status, headers: h });
}

let ytp = null;
async function client() {
  if (!ytp) {
    ytp = (async () => {
      diag.set("YouTube: loading…");
      const mod = await import("youtubei.js/web");
      const { Innertube, Platform, UniversalCache } = mod;
      // YouTube.js has no JavaScript interpreter of its own for YouTube's scrambled player code: the web view is one
      Platform.load({ ...Platform.shim, eval: (data) => new Function(data.output)() });
      const yt = await timed(Innertube.create({ fetch: nativeFetch, cache: new UniversalCache(false), retrieve_player: true }), 45000, "Connecting to YouTube");
      diag.log("YouTube: connected");
      yt.session.on("auth", ({ credentials }) => saveCreds(credentials));
      yt.session.on("update-credentials", ({ credentials }) => saveCreds(credentials));
      const saved = savedCreds();
      if (saved) {
        try { await timed(yt.session.signIn(saved), 30000, "Signing in to YouTube"); yt.session.logged_in = false; diag.log("YouTube: signed in"); }
        catch (e) { diag.log(`YouTube: saved sign-in didn't work (${e.message || e})`); if (/invalid|revoked|denied|expired/i.test(String(e.message))) saveCreds(null); }
      }
      return { mod, yt };
    })();
  }
  try { return await ytp; } catch (e) { ytp = null; throw e; }
}

// ---- signing in (only if YouTube asks): the "enter a code at google.com/device" sign-in that TVs use.
// The app keeps the sign-in (not your password) on the phone; Sign Out in Settings removes it.
const AUTH_KEY = "auraos-yt-oauth";
function savedCreds() { try { return JSON.parse(localStorage.getItem(AUTH_KEY) || "null"); } catch { return null; } }
function saveCreds(c) { try { c ? localStorage.setItem(AUTH_KEY, JSON.stringify(c)) : localStorage.removeItem(AUTH_KEY); } catch {} }
export function ytSignedIn() { return !!savedCreds(); }
/** onCode({ user_code, verification_url }) is called with the code to enter; resolves once you've signed in. */
export async function ytSignIn(onCode) {
  const { yt } = await client();
  if (yt.session.oauth?.oauth2_tokens && ytSignedIn()) return;
  const pending = (d) => { diag.log("YouTube: sign-in code ready"); onCode?.(d); };
  yt.session.on("auth-pending", pending);
  try { await yt.session.signIn(); }
  finally { yt.session.off("auth-pending", pending); }
  saveCreds(yt.session.oauth?.oauth2_tokens);
  yt.session.logged_in = false;                          // (switched on only for the TV identity, see fetchAudio)
  diag.log("YouTube: signed in");
}
export async function ytSignOut() {
  try { const { yt } = await client(); if (yt.session.oauth?.oauth2_tokens) { yt.session.logged_in = true; await yt.session.signOut(); } } catch (e) { diag.log(`YouTube: sign-out: ${e.message || e}`); }
  saveCreds(null);
  ytp = null;                                            // start fresh, signed out
}

// ---- proof of origin: YouTube now only hands out audio to players that pass its "BotGuard" check. The app's web view
// is a real browser engine, so it runs YouTube's own check script and gets a token, then makes a token for each song.
let minterP = null;
async function poMinter() {
  if (!minterP) {
    minterP = (async () => {
      const { yt } = await client();
      const { BotGuardClient } = await import("bgutils-js/botguard");
      const { WebPoMinter } = await import("bgutils-js/webpo");
      const { buildURL, getHeaders } = await import("bgutils-js/utils");
      const { getChallenge } = await import("bgutils-js/botguard");
      diag.set("YouTube: running YouTube's player check…");
      // the check (a small program + the script that runs it): from YouTube itself, or else from Google's anti-abuse service
      let program = "", globalName = "", js = "", src = "";
      try {        // Google's anti-abuse service: the check YouTube's web player uses for these tokens
        const c = await timed(getChallenge({ requestKey: "O43z0dpjhgX20SCx4KAo", fetchFunction: nativeFetch }), 30000, "Getting Google's check");
        program = c.program; globalName = c.globalName;
        js = c.interpreterJavascript?.privateDoNotAccessOrElseSafeScriptWrappedValue || "";
        src = c.interpreterUrl?.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue || "";
        diag.log(`YouTube: check from Google (${globalName}, script ${js ? "inline" : src ? "url" : "missing"})`);
        if (!program || !globalName) throw new Error("incomplete");
      } catch (e) {
        diag.log(`YouTube: Google's check unavailable (${e.message || e}); asking YouTube`);
        const ch = await timed(yt.getAttestationChallenge("ENGAGEMENT_TYPE_UNBOUND"), 30000, "Getting YouTube's check");
        const bg = ch?.bg_challenge;
        if (!bg) throw new Error("no check in YouTube's answer");
        program = bg.program; globalName = bg.global_name; js = "";
        src = bg.interpreter_url?.private_do_not_access_or_else_trusted_resource_url_wrapped_value || "";
        diag.log(`YouTube: check from YouTube (${globalName}, script ${src ? "url" : "missing"})`);
      }
      if (!js && src) {
        if (src.startsWith("//")) src = "https:" + src;
        const r = await nativeFetch(src);
        js = await r.text();
        diag.log(`YouTube: check script ${Math.round(js.length / 1024)} KB (HTTP ${r.status})`);
      }
      if (!js || !program || !globalName) throw new Error("couldn't load YouTube's check");
      new Function(js)();
      if (!globalThis[globalName]) throw new Error("YouTube's check script didn't start");
      const bgc = await timed(BotGuardClient.create({ program, globalName, globalObject: globalThis }), 30000, "Starting YouTube's check");
      const signal = [];
      const answer = await timed(bgc.snapshot({ webPoSignalOutput: signal }), 30000, "Running YouTube's check");
      diag.log(`YouTube: check answered (${answer?.length || 0} chars)`);
      const r = await nativeFetch(buildURL("GenerateIT", true), { method: "POST", headers: getHeaders(), body: JSON.stringify(["O43z0dpjhgX20SCx4KAo", answer]) });
      const txt = await r.text();
      let arr = null;
      try { arr = JSON.parse(txt); } catch {}
      if (!Array.isArray(arr)) diag.log(`YouTube: unexpected check result (HTTP ${r.status}): ${txt.slice(0, 160)}`);
      const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] = Array.isArray(arr) ? arr : [];
      if (!integrityToken) { diag.log(`YouTube: check result (HTTP ${r.status}): ${txt.slice(0, 300)}`); throw new Error(`YouTube didn't accept the check (${r.status})`); }
      const m = await WebPoMinter.create({ integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken }, signal);
      diag.log("YouTube: player check passed");
      setTimeout(() => { minterP = null; }, Math.max(300, (estimatedTtlSecs || 3600) - 300) * 1000);   // renew before it runs out
      return m;
    })();
  }
  try { return await minterP; } catch (e) { minterP = null; throw e; }
}
let tokenWhy = "";
const AuraPo = registerPlugin("AuraPo");      // native: a hidden page on www.youtube.com that runs YouTube's check
async function tokenFor(id) {
  try {
    const r = await timed(AuraPo.mint({ id, script: poScript }), 60000, "Getting YouTube's OK");
    if (r?.token) { tokenWhy = ""; diag.log("YouTube: token from the hidden YouTube page"); return r.token; }
    throw new Error("no token");
  } catch (e) { tokenWhy = e.message || String(e); diag.log(`YouTube: hidden YouTube page: ${tokenWhy}`); }
  try {                                         // (older way, inside the app's own page)
    const t = await timed((await poMinter()).mintAsWebsafeString(id), 20000, "Making the song's token");
    tokenWhy = ""; return t;
  } catch (e) { tokenWhy += ` · in-app: ${e.message || e}`; diag.log(`YouTube: no proof-of-origin token (${tokenWhy}); trying without`); return ""; }
}

/** What a pasted link points at: { video } and/or { list } */
export function parseLink(text) {
  const m = String(text || "").match(/https?:\/\/[^\s]+/);
  let u;
  try { u = new URL(m ? m[0] : String(text).trim()); } catch { return null; }
  const host = u.hostname.replace(/^www\.|^m\./, "");
  if (!/(^|\.)youtube\.com$|^youtu\.be$|^music\.youtube\.com$/.test(host)) return null;
  let video = u.searchParams.get("v") || "";
  if (host === "youtu.be") video = u.pathname.slice(1).split("/")[0];
  const shorts = u.pathname.match(/^\/(shorts|embed|live)\/([\w-]{6,})/);
  if (shorts) video = shorts[2];
  let list = u.searchParams.get("list") || "";
  // an automatic "mix" started from a song (RD + the song's id…) is endless: just that song. But YouTube Music's own
  // playlists (RDCLAK5uy_…, e.g. Today's Hits) and a mix link with no song in it are real playlists.
  if (/^RD/.test(list) && !/^RDCLAK5uy_/.test(list) && video) list = "";
  return video || list ? { video, list, music: host === "music.youtube.com" } : null;
}

// ---- tidy names (the same rules as aura-musicpack on the computer)
const JUNK = /\s*[([{]\s*(official|lyrics?|lyric video|audio|video|music video|hd|hq|4k|visuali[sz]er|full album|remaster(ed)?|explicit|clean|mv|m\/v)[^)\]}]*[)\]}]/gi;
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
function cleanTitle(title, artist) {
  let t = String(title || "").replace(JUNK, "").trim();
  t = t.replace(/\s+(official\s+(music\s+)?(video|audio)|lyrics?)\s*$/i, "").trim();
  if (artist) { const m = t.match(new RegExp("^\\s*" + esc(artist) + "\\s*[-–—:]\\s*(.+)$", "i")); if (m) t = m[1].trim(); }
  return t || title || "Untitled";
}
function tidy({ title, artist, album }) {
  artist = String(artist || "").replace(/\s*-\s*Topic$/i, "").replace(/VEVO$/i, "").trim();
  let t = String(title || "");
  if (!artist) { const m = t.match(/^(.+?)\s+[-–—]\s+(.+)$/); if (m && m[1].length < 60) { artist = m[1].trim(); t = m[2]; } }
  return { title: cleanTitle(t, artist), artist: artist || "Unknown Artist", album: String(album || "").trim() };
}
const bigThumb = (url, id) => {
  if (url && /googleusercontent|ggpht/.test(url)) return url.replace(/=w\d+-h\d+.*$/, "=w800-h800-l90-rj");
  return id ? `https://i.ytimg.com/vi/${id}/maxresdefault.jpg` : url;
};
const lastThumb = (t) => { const arr = t?.contents || t || []; return Array.isArray(arr) && arr.length ? arr[arr.length - 1]?.url || arr[0]?.url : ""; };

/** The songs a link points at, with their names (no downloading yet). */
async function resolve(link) {
  const { yt } = await client();
  if (link.list) {
    diag.set("YouTube: reading the playlist…");
    const tracks = [];
    let title = "";
    try {                                          // YouTube Music's view first (it knows albums and artists)
      let pl = await timed(yt.music.getPlaylist(link.list), 45000, "Reading the playlist");
      title = pl.header?.title?.toString?.() || pl.header?.title?.text || "";
      for (let page = 0; page < 40; page++) {
        for (const it of pl.items || []) {
          if (!it?.id) continue;
          tracks.push({ id: it.id, title: it.title, artist: (it.artists || it.authors || []).map((a) => a.name).filter(Boolean).join(", "),
            album: it.album?.name || "", duration: it.duration?.seconds || 0, thumb: lastThumb(it.thumbnail) });
        }
        if (!pl.has_continuation) break;
        pl = await timed(pl.getContinuation(), 45000, "Reading more of the playlist");
      }
    } catch (e) { diag.log(`YouTube Music playlist view failed (${e.message}); trying YouTube's`); }
    if (!tracks.length) {
      let pl = await timed(yt.getPlaylist(link.list), 45000, "Reading the playlist");
      title = pl.info?.title || title;
      for (let page = 0; page < 40; page++) {
        for (const v of pl.videos || pl.items || []) {
          if (!v?.id) continue;
          tracks.push({ id: v.id, title: v.title?.toString?.() || v.title?.text || "", artist: v.author?.name || "", album: "",
            duration: v.duration?.seconds || 0, thumb: lastThumb(v.thumbnails) });
        }
        if (!pl.has_continuation) break;
        pl = await timed(pl.getContinuation(), 45000, "Reading more of the playlist");
      }
    }
    title = String(title || "YouTube Playlist").replace(/^(Album|Single|EP)\s*-\s*/i, "");
    const isAlbum = /^OLAK5uy_/.test(link.list);
    tracks.forEach((t, i) => { if (isAlbum) { t.album ||= title; t.track = i + 1; } });
    diag.log(`YouTube: “${title}” has ${tracks.length} songs`);
    return { title, playlist: true, tracks };
  }
  return { title: "", playlist: false, tracks: [{ id: link.video }] };
}

/** Download one song's audio: tries YouTube's app identities in turn until one of them gives a real m4a. */
// asking YouTube for an address happens one song at a time (the sign-in switch is shared); downloads run in parallel
let infoQueue = Promise.resolve();
const oneAtATime = (fn) => { const r = infoQueue.then(fn, fn); infoQueue = r.catch(() => {}); return r; };
let goodClient = null;                              // the identity that worked last: tried first next time

async function fetchAudio(id, onFrac, onStage) {
  const { mod, yt } = await client();
  const reasons = [];
  onStage?.("Getting YouTube's OK…");
  const pot = await tokenFor(id);
  const signedIn = !!yt.session.oauth?.oauth2_tokens && ytSignedIn();
  // signed in: YouTube's TV identity carries the sign-in, so it goes first
  let order = signedIn ? ["TV", "TV_SIMPLY", ...CLIENTS.filter((c) => c !== "TV_SIMPLY")] : [...CLIENTS];
  if (goodClient && order.includes(goodClient)) order = [goodClient, ...order.filter((c) => c !== goodClient)];
  for (const name of order) {
    if (!pot && WEBBY.has(name)) continue;                // these need the token
    try {
      onStage?.(`Asking YouTube for the audio (${name.toLowerCase().replace(/_/g, " ")})…`);
      const got = await oneAtATime(async () => {
        yt.session.logged_in = signedIn && name.startsWith("TV");   // (the TV sign-in only works with the TV identities)
        try {
          const info = await timed(yt.getBasicInfo(id, pot ? { client: name, po_token: pot } : { client: name }), 30000, `Asking YouTube (${name})`);
          const ps = info.playability_status;
          if (ps && ps.status && ps.status !== "OK") return { why: `${name}: ${ps.status}${ps.reason ? " – " + ps.reason : ""}` };
          let fmt;
          try { fmt = info.chooseFormat({ type: "audio", quality: "best", format: "mp4" }); } catch { return { why: `${name}: no m4a audio` }; }
          let url = await timed(fmt.decipher(yt.session.player), 30000, "Unscrambling the audio address");
          if (pot) { const u = new URL(url); u.searchParams.set("pot", pot); url = u.toString(); }
          return { info, fmt, url };
        } finally { yt.session.logged_in = false; }
      });
      if (got.why) { reasons.push(got.why); diag.log("YouTube: " + got.why); continue; }
      const ua = mod.Constants?.CLIENTS?.[name]?.USER_AGENT || navigator.userAgent;
      const size = +got.fmt.content_length || 0;
      diag.log(`YouTube: ${name} gave an address for ${id} (${Math.round(size / 1024)} KB)`);
      onStage?.("Downloading…", 0);
      const file = await download(got.url, size, ua, id, onFrac, WEBBY.has(name) ? (name === "YTMUSIC" ? "https://music.youtube.com" : "https://www.youtube.com") : "");
      goodClient = name;
      const b = got.info.basic_info || {};
      return { ...file, client: name, meta: { title: b.title || "", artist: b.author || "", duration: b.duration || 0, thumb: lastThumb(b.thumbnail) } };
    } catch (e) { reasons.push(`${name}: ${String(e.message || e).replace(/https:\/\/\S+/, "(address)")}`); diag.log("YouTube: " + reasons.at(-1)); }
  }
  const first = String(reasons[0] || "").replace(/(Error downloading file):.*/, "$1");    // (not the whole address)
  const bot = reasons.some((r) => /not a bot|sign in|LOGIN_REQUIRED/i.test(r));
  throw new Error(`YouTube refused every way in (${first || "no answer"})` + (pot ? "" : ` · the player check failed: ${tokenWhy}`)
    + (bot && !signedIn ? " · signing in to YouTube (Settings › YouTube Account) may help" : ""));
}

/** Download natively, straight to a file. iPhone: into Library/Incoming (the library moves it into place).
 *  Android: into the cache, then handed over as a Blob (Android keeps songs inside the app's database). */
async function download(url, size, ua, id, onFrac, origin = "") {
  const { Filesystem, Directory } = await import("@capacitor/filesystem");
  const dir = onDisk ? Directory.Library : Directory.Cache;
  const rel = `${onDisk ? "Incoming/" : ""}yt-${id}-${Date.now().toString(36)}.m4a`;
  let sub = null;
  const full = size ? `${url}&range=0-${size - 1}` : url;
  // (every download in flight hears every progress event: only listen to this one's)
  try { sub = await Filesystem.addListener("progress", (p) => { if (p.contentLength > 0 && (!p.url || p.url === full)) onFrac?.(p.bytes / p.contentLength, p.bytes, p.contentLength); }); } catch {}
  let keep = false;
  try {
    const r = await timed(Filesystem.downloadFile({ url: full, path: rel, directory: dir, headers: origin ? { "User-Agent": ua, Origin: origin, Referer: origin + "/" } : { "User-Agent": ua }, recursive: true, progress: true }), 300000, "Downloading");
    let got = 0, blob = null;
    if (onDisk) {
      try { got = (await Filesystem.stat({ path: rel, directory: dir })).size || 0; } catch {}
    } else {
      const { uri } = await Filesystem.getUri({ path: rel, directory: dir });
      blob = await (await fetch(Capacitor.convertFileSrc(uri))).blob();
      got = blob.size;
    }
    diag.log(`YouTube: download finished · ${Math.round(got / 1024)} KB${r?.status ? " · HTTP " + r.status : ""}`);
    if (got < 20000 || (size && got < size * 0.9)) throw new Error(`download refused (${got} bytes)`);
    keep = onDisk;
    return onDisk ? { rel, size: got } : { blob: blob.type ? blob : new Blob([blob], { type: "audio/mp4" }), size: got };
  } finally {
    try { await sub?.remove(); } catch {}
    if (!keep) { try { await Filesystem.deleteFile({ path: rel, directory: dir }); } catch {} }
  }
}

/**
 * Add everything a link points at. lib: the library; onProgress(fraction, text).
 * Returns { added, skipped, failed, firstError, title, playlist, songs }.
 */
export async function importLink(text, lib, onProgress) {
  if (!native) throw new Error("Adding from links works in the phone app");
  const link = parseLink(text);
  if (!link) throw new Error("That isn't a YouTube or YouTube Music link");
  diag.log(`YouTube: link → ${JSON.stringify(link)}`);
  onProgress?.(0, "Connecting to YouTube…");
  const { title, playlist, tracks } = await resolve(link);
  let added = 0, skipped = 0, failed = 0, firstError = "", stop = false;
  const songs = [], slots = new Array(tracks.length);
  let plName = null;
  const keep = (i, id) => {                      // into the playlist right away, in the playlist's own order
    slots[i] = id;
    if (!playlist) return;
    if (!plName) { plName = lib.uniquePlaylist(title, title); diag.log(`YouTube: playlist “${plName}”`); }
    lib.meta.playlists[plName] = slots.filter(Boolean);
    lib.saveMeta();
  };
  let addQueue = Promise.resolve();              // saving into the library: one at a time (no clashing file names)
  const addOne = (fn) => { const r = addQueue.then(fn, fn); addQueue = r.catch(() => {}); return r; };
  let done = 0;
  const frac = new Array(tracks.length).fill(0);
  const live = new Map();                         // songs in progress: index → { title, text, f }
  const mb = (n) => (n / 1048576).toFixed(1);
  let lastReport = 0;
  const report = (force) => {
    const now = performance.now();
    if (!force && now - lastReport < 120) return;
    lastReport = now;
    const overall = Math.min(0.995, (done + frac.reduce((x, y) => x + y, 0)) / Math.max(1, tracks.length));
    const cur = live.size ? live.get(Math.min(...live.keys())) : null;     // the earliest song still going
    const head = `${Math.min(tracks.length, done + 1)} of ${tracks.length}` + (live.size > 1 ? ` · ${live.size} at once` : "");
    onProgress?.(overall, cur ? `${head} · ${cur.title}` : head, cur ? { f: cur.f, text: cur.text } : { text: "Finishing up…" });
  };
  const stage = (i, text, f = null) => { const c = live.get(i); if (c) { c.text = text; c.f = f; } report(); };
  const one = async (i) => {
    const t = tracks[i];
    try {
      diag.step = `Downloading · ${t.title || t.id}`;
      if (t.title) {
        const pre = tidy({ title: t.title, artist: t.artist, album: t.album });
        const had = lib.existing({ ...pre, duration: t.duration || 0 });
        if (had) { skipped++; keep(i, had.id); return; }
      }
      live.set(i, { title: t.title || "song", text: "Starting…", f: null });
      report(true);
      // the cover comes down at the same time as the song
      const coverP = (async () => {
        let c = null;
        try { c = await getBlob(bigThumb(t.thumb, t.id)); } catch {}
        if (!c || c.size < 2000) { try { c = await getBlob(`https://i.ytimg.com/vi/${t.id}/hqdefault.jpg`); } catch {} }
        return c;
      })();
      const a = await fetchAudio(t.id, (f, got, total) => {
        frac[i] = 0.1 + 0.8 * f;
        stage(i, total ? `Downloading · ${mb(got)} of ${mb(total)} MB` : "Downloading…", f);
      }, (text, f = null) => { frac[i] = Math.max(frac[i], 0.05); stage(i, text, f); });
      stage(i, "Saving to your library…", 1);
      const tags = tidy({ title: t.title || a.meta.title, artist: t.artist || a.meta.artist, album: t.album });
      const cover = await coverP;
      const info = { ...tags, track: t.track || 0, duration: t.duration || a.meta.duration || 0,
        codec: "AAC · from YouTube", source: `https://music.youtube.com/watch?v=${t.id}` };
      const r = await addOne(() => a.blob
        ? lib.addAudio(a.blob, `${tags.title}.m4a`, info, cover)
        : lib.addLocalFile(a.rel, a.size, "m4a", { albumartist: "", disc: 0, year: "", genre: "", composer: "", label: "", copyright: "", isrc: "", bpm: "", ...info }, cover));
      r.skipped ? skipped++ : (added++, songs.push(r.song));
      keep(i, r.song.id);
    } catch (e) {
      failed++;
      firstError ||= e.message || String(e);
      diag.log(`YouTube: couldn't add ${t.title || t.id}: ${e.message || e}`);
      if (failed >= 3 && !added && !skipped && !stop) { stop = true; diag.log("YouTube: the first 3 all failed — stopping"); }
    } finally { frac[i] = 0; live.delete(i); done++; report(true); }
  };
  let next = 0;
  const worker = async () => { while (!stop && next < tracks.length) await one(next++); };
  // the first song alone (it finds the identity that works), then three at a time
  if (tracks.length) { await one(next++); }
  await Promise.all(Array.from({ length: Math.min(3, Math.max(0, tracks.length - 1)) }, worker));
  if (stop) failed += tracks.length - done;
  onProgress?.(1, "Done");
  lib.rebuild();
  return { added, skipped, failed, firstError, title, playlist: plName, songs };
}
