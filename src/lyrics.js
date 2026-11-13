import { lib } from "./library.js";
import { player } from "./player.js";
import { getJson } from "./http.js";

export function parseLrc(text) {
  let offset = 0;
  const om = /\[offset:\s*([+-]?\d+)\]/i.exec(text || "");
  if (om) offset = +om[1] / 1000;
  const lines = [];
  for (const raw of (text || "").split(/\r?\n/)) {
    const tags = [...raw.matchAll(/\[(\d+):(\d+(?:[.:]\d+)?)\]/g)];
    if (!tags.length) continue;
    let body = raw.replace(/\[\d+:\d+(?:[.:]\d+)?\]/g, "").trim();
    let words = null;
    if (/<\d+:\d+(?:\.\d+)?>/.test(body)) {
      words = [];
      const parts = body.split(/<(\d+):(\d+(?:\.\d+)?)>/);
      for (let i = 1; i < parts.length - 2; i += 3) {
        const w = parts[i + 2].trim();
        if (w) words.push([+parts[i] * 60 + +parts[i + 1] - offset, w]);
      }
      body = words.map((w) => w[1]).join(" ");
    }
    for (const [, mm, ss] of tags) lines.push({ t: Math.max(0, +mm * 60 + +ss.replace(":", ".") - offset), text: body, words: words && [...words] });
  }
  lines.sort((a, b) => a.t - b.t);
  lines.forEach((ln, i) => {
    if (ln.words || !ln.text) return;
    const next = i + 1 < lines.length ? lines[i + 1].t : ln.t + 6;
    const ws = ln.text.split(/\s+/);
    const span = Math.max(0.6, Math.min(next - ln.t, 1 + 0.42 * ws.length * 1.6)) * 0.92;
    const total = ws.reduce((a, w) => a + w.length + 1, 0);
    let acc = 0;
    ln.words = ws.map((w) => { const t = ln.t + (span * acc) / total; acc += w.length + 1; return [t, w]; });
  });
  return lines;
}

const HDR = { "Lrclib-Client": "AuraOS Music Android" };
const lrc = async (path, params) => getJson("https://lrclib.net/api/" + path + "?" + new URLSearchParams(params), HDR);
/** "Song (feat. X) - 2011 Remaster [Explicit]" → "Song" */
export const cleanTitle = (t) => String(t || "").replace(/\s*[([](feat|ft|with|prod|remaster|explicit|clean|bonus|live|radio|single|mono|stereo|deluxe)[^)\]]*[)\]]/gi, "")
  .replace(/\s+-\s+(\d{4}\s+)?(remaster(ed)?|live|radio edit|single version|mono|stereo|.*version).*$/i, "").trim();

function bestOf(hits, s) {
  const dur = s.duration || 0;
  const ok = hits.filter((h) => (h.syncedLyrics || h.plainLyrics || h.instrumental) && (!dur || !h.duration || Math.abs(h.duration - dur) < 8));
  ok.sort((a, b) => (!b.syncedLyrics - !a.syncedLyrics) || Math.abs((a.duration || dur) - dur) - Math.abs((b.duration || dur) - dur));
  return ok[0] || null;
}

/** {synced, plain, instrumental, source} or {error} */
export async function fetchLyrics(s, force) {
  const cached = lib.meta.lyrics[s.id];
  if (cached && !force && !(cached.none && Date.now() - (cached.at || 0) > 3 * 864e5)) return cached;
  const artist = (s.artist || "").split(",")[0].trim();
  const title = s.title || "", clean = cleanTitle(title);
  let got = null;
  try {
    const tries = [];
    const base = { track_name: title, artist_name: artist };
    if (s.album && s.duration) tries.push(() => lrc("get", { ...base, album_name: s.album, duration: Math.round(s.duration) }));
    if (s.duration) tries.push(() => lrc("get", { ...base, duration: Math.round(s.duration) }));
    if (clean !== title && s.duration) tries.push(() => lrc("get", { track_name: clean, artist_name: artist, duration: Math.round(s.duration) }));
    tries.push(async () => bestOf((await lrc("search", base)) || [], s));
    if (clean !== title) tries.push(async () => bestOf((await lrc("search", { track_name: clean, artist_name: artist })) || [], s));
    tries.push(async () => bestOf((await lrc("search", { q: `${clean} ${artist}`.trim() })) || [], s));
    for (const t of tries) {
      const r = await t();
      if (r && (r.syncedLyrics || r.plainLyrics || r.instrumental)) { got = r; break; }
    }
  } catch (e) {
    console.warn("lyrics", e);
    return { error: true };
  }
  const res = got ? { synced: got.syncedLyrics || "", plain: got.plainLyrics || "", instrumental: !!got.instrumental, source: "LRCLIB" } : { none: true, at: Date.now() };
  lib.meta.lyrics[s.id] = res;
  lib.saveMeta();
  return res;
}

export async function searchLyrics(q, s) {
  const hits = (await lrc("search", { q })) || [];
  const dur = s?.duration || 0;
  return hits.sort((a, b) => (!b.syncedLyrics - !a.syncedLyrics) || (dur ? Math.abs((a.duration || 0) - dur) - Math.abs((b.duration || 0) - dur) : 0));
}

/** Four lines at a time, the third is being sung; a glow in the album colour floats word to word. */
export class LyricsView {
  constructor(canvas) {
    this.cv = canvas;
    this.lines = [];
    this.plain = [];
    this.message = "";
    this.scroll = 0;
    this.vel = 0;
    this.glow = null;
    this.color = "#ff7aa0";
    this.layoutCache = new Map();
    this.running = false;
    this.browseUntil = 0;
    this.bf = 0;                  // 0 = following the song, 1 = you're browsing
    canvas.addEventListener("click", (e) => { if (this.justDragged) { this.justDragged = false; return; } this.onClick(e); });
    let sy = 0, so = 0, ss = 0, drag = false, down = false;
    this.fling = 0;                 // lines per second, after a hard swipe
    const track = [];               // recent finger positions, for the release speed
    canvas.addEventListener("pointerdown", (e) => {
      down = true; drag = false; sy = e.clientY; so = this.plainOff || 0; ss = this.scroll;
      this.fling = 0; track.length = 0;
      canvas.setPointerCapture(e.pointerId);
    });
    canvas.addEventListener("pointermove", (e) => {
      if (!down) return;
      const dy = e.clientY - sy;
      if (!drag && Math.abs(dy) > 6) drag = true;
      if (!drag) return;
      track.push([performance.now(), e.clientY]);
      while (track.length > 2 && performance.now() - track[0][0] > 90) track.shift();
      if (!this.lines.length) { this.plainOff = Math.max(0, so - dy); return; }
      this.scroll = Math.max(0, Math.min(this.lines.length - 1, ss - dy / this.avgH()));
      this.vel = 0;
      this.browseUntil = Infinity;
    });
    const up = () => {
      if (!down) return;
      down = false;
      if (!drag) return;
      this.justDragged = true; setTimeout(() => (this.justDragged = false), 60);
      // how fast the finger was moving when it let go → keep coursing that way, slowing down
      if (track.length >= 2 && performance.now() - track[track.length - 1][0] < 70) {     // a finger that stopped before letting go doesn't fling
        const [t0, y0] = track[0], [t1, y1] = track[track.length - 1];
        const pxPerS = ((y1 - y0) / Math.max(8, t1 - t0)) * 1000;
        if (Math.abs(pxPerS) > 250) this.fling = this.lines.length ? -pxPerS / this.avgH() : -pxPerS;
      }
      this.release();
    };
    canvas.addEventListener("pointerup", up);
    canvas.addEventListener("pointercancel", up);
    canvas.addEventListener("wheel", (e) => {
      e.preventDefault();
      if (!this.lines.length) { this.plainOff = Math.max(0, (this.plainOff || 0) + e.deltaY); return; }
      this.scroll = Math.max(0, Math.min(this.lines.length - 1, this.scroll + e.deltaY / this.avgH()));
      this.vel = 0;
      this.release();
    }, { passive: false });
  }

  /** how long after you stop scrolling before the lyrics glide back to the song (Settings → Lyrics) */
  returnDelay() { const v = +lib.meta.settings.lyricReturn; return (isFinite(v) && v > 0 ? v : 1.5) * 1000; }
  release() { this.browseUntil = performance.now() + this.returnDelay(); }
  avgH() {
    const n = Math.min(this.lines.length, 12);
    let t = 0;
    for (let i = 0; i < n; i++) t += this.layoutCache.get(i + ":" + this.lastWidth)?.h || (this.fs || 26) * 1.25 + 14;
    return n ? t / n : 50;
  }

  set(res, color) {
    this.browseUntil = 0; this.bf = 0;
    this.color = color || "#ff7aa0";
    this.lines = []; this.plain = []; this.message = ""; this.glow = null; this.plainOff = 0;
    this.layoutCache.clear();
    if (!res) this.message = "Finding lyrics…";
    else if (res.error) this.message = "Couldn't look up lyrics — check your internet connection.";
    else if (res.synced) { this.lines = parseLrc(res.synced); if (!this.lines.length) this.plain = (res.plain || res.synced).split("\n"); }
    else if (res.plain) this.plain = res.plain.split("\n");
    else if (res.instrumental) this.message = "♪  Instrumental  ♪";
    else this.message = "No lyrics found for this song.";
    this.scroll = Math.max(0, this.currentIndex());
    this.vel = 0;
  }

  now() {
    const s = player.current;
    const off = (s && lib.meta.settings.offsets[s.id]) || 0;
    const lat = player.ctx?.outputLatency || player.ctx?.baseLatency || 0;
    return player.audio.currentTime - off - lat;
  }

  currentIndex() {
    const t = this.now() + 0.15;
    let idx = -1;
    for (let i = 0; i < this.lines.length; i++) { if (this.lines[i].t <= t) idx = i; else break; }
    return idx;
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.last = performance.now();
    const loop = (ts) => {
      if (!this.running) return;
      this.frame(ts);
      requestAnimationFrame(loop);
    };
    requestAnimationFrame(loop);
  }
  stop() { this.running = false; }

  font() { return `800 ${this.fs}px system-ui, Roboto, sans-serif`; }

  rows(i, width, g) {
    const key = i + ":" + width;
    if (this.layoutCache.has(key)) return this.layoutCache.get(key);
    const ln = this.lines[i];
    g.font = this.font();
    const space = g.measureText(" ").width;
    let words = ln.words?.length ? ln.words : (ln.text || "♪").split(/\s+/).map((w) => [ln.t, w]);
    if (!ln.text) words = [[ln.t, "♪"]];
    const placed = [];
    let x = 0, row = 0;
    for (const [t, w] of words) {
      const ww = g.measureText(w).width;
      if (x > 0 && x + ww > width) { row++; x = 0; }
      placed.push({ t, w, x, row, ww });
      x += ww + space;
    }
    const lineH = Math.round(this.fs * 1.25);
    const res = { placed, h: (row + 1) * lineH + 14, lineH };
    this.layoutCache.set(key, res);
    return res;
  }

  geometry(width, H, g) {
    const n = this.lines.length;
    const heights = this.lines.map((_, i) => this.rows(i, width, g).h);
    const tops = [0];
    for (let i = 0; i < n - 1; i++) tops.push(tops[i] + heights[i]);
    const s = Math.max(0, Math.min(this.scroll, n - 1));
    const anchorAt = (k) => {
      const slot = k >= 2 ? heights[k - 2] + heights[k - 1] : heights[0] * 2;
      const block = slot + heights[k] + (k + 1 < n ? heights[k + 1] : heights[k]);
      return (H - block) / 2 + slot - tops[k];
    };
    const lo = Math.floor(s), hi = Math.min(n - 1, lo + 1), frac = s - lo;
    const shift = anchorAt(lo) + (anchorAt(hi) - anchorAt(lo)) * frac;   // continuous: never jumps mid-move
    return { heights, tops, s, shift };
  }

  frame(ts) {
    const cv = this.cv;
    const dpr = window.devicePixelRatio || 1;
    const W = cv.clientWidth, H = cv.clientHeight;
    if (!W || !H) return;
    if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) {
      cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
      this.layoutCache.clear();
    }
    const fs = Math.round(Math.max(18, Math.min(30, W / 13, H / 7.5)));
    if (fs !== this.fs) { this.fs = fs; this.layoutCache.clear(); }
    const dt = Math.min(0.05, (ts - this.last) / 1000);
    this.last = ts;
    const target = Math.max(0, this.currentIndex());
    if (this.fling) {                       // momentum after a flick: glide on, slowing like iOS
      if (this.lines.length) {
        this.scroll += this.fling * dt;
        if (this.scroll < 0 || this.scroll > this.lines.length - 1) { this.scroll = Math.max(0, Math.min(this.lines.length - 1, this.scroll)); this.fling = 0; }
      } else this.plainOff = Math.max(0, (this.plainOff || 0) + this.fling * dt);
      this.fling *= Math.exp(-dt * 3.6);
      if (Math.abs(this.fling) < (this.lines.length ? 0.35 : 20)) this.fling = 0;
      this.browseUntil = performance.now() + this.returnDelay();      // the snap-back clock starts once it stops
    }
    const browsing = performance.now() < this.browseUntil;
    this.bf += ((browsing ? 1 : 0) - this.bf) * Math.min(1, dt * 7);
    if (!browsing) {
      // far away (a seek, or the end of a browse): glide back quickly, never jump
      const w = Math.abs(target - this.scroll) > 3 ? 7 : 11, x = this.scroll - target;   // critically damped spring
      this.vel = (this.vel - w * w * x * dt) / (1 + 2 * w * dt);
      this.scroll += this.vel * dt;
      if (Math.abs(target - this.scroll) < 0.0005 && Math.abs(this.vel) < 0.01) { this.scroll = target; this.vel = 0; }
    }

    const g = cv.getContext("2d");
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, W, H);
    const pad = 22;
    const width = W - pad * 2;
    this.lastWidth = width;
    g.textBaseline = "middle";
    if (this.message && !this.lines.length && !this.plain.length) {
      g.font = `700 20px system-ui, Roboto, sans-serif`;
      g.fillStyle = "rgba(255,255,255,.65)";
      g.textAlign = "center";
      g.fillText(this.message, W / 2, H / 2);
      g.textAlign = "left";
      return;
    }
    if (!this.lines.length) return this.paintPlain(g, pad, width, H);
    const { tops, s, shift } = this.geometry(width, H, g);
    const cur = this.currentIndex();
    const now = this.now();
    const lo = Math.floor(s);
    g.font = this.font();
    const bf = this.bf;
    for (let i = Math.max(0, lo - 12); i < Math.min(this.lines.length, lo + 14); i++) {
      const d = i - s;
      const y = shift + tops[i];
      const { placed, lineH, h: lh } = this.rows(i, width, g);
      if (y > H || y + lh < 0) continue;
      // following the song: 4 lines; browsing: every line on screen, fading at the edges
      const winSong = Math.max(0, Math.min(1, (d + 2.7) / 0.7)) * Math.max(0, Math.min(1, (1.7 - d) / 0.7));
      const winBrowse = Math.max(0, Math.min(1, (y + lh * 0.5) / 50, (H - y - lh * 0.5) / 50));
      const win = winSong + (winBrowse - winSong) * bf;
      if (win <= 0.003) continue;
      const focus = Math.max(0, 1 - Math.abs(d));
      const dim = i < s ? 0.3 : 0.42;
      const aSong = dim + (1 - dim) * focus, aBrowse = i === cur ? 1 : 0.55;
      const alpha = (aSong + (aBrowse - aSong) * bf) * win;
      if (i === cur && placed.length) this.paintGlow(g, placed, pad, y, lineH, W, H, win);
      g.fillStyle = `rgba(255,255,255,${alpha.toFixed(3)})`;
      for (const p of placed) g.fillText(p.w, pad + p.x, y + p.row * lineH + lineH / 2);
    }
  }

  /** A soft glow in the album colour behind the whole current line (no word chasing). */
  paintGlow(g, placed, pad, y, lineH, W, H, vis = 1) {
    const rows = Math.max(...placed.map((p) => p.row)) + 1;
    const lw = Math.max(...placed.map((p) => p.x + p.ww));
    const t = [pad + lw / 2, y + (rows * lineH) / 2, lw, rows * lineH];
    if (!this.glow) this.glow = [...t];
    const gl = this.glow;
    for (let k = 0; k < 4; k++) gl[k] += (t[k] - gl[k]) * 0.18;
    const breathe = 0.85 + 0.15 * Math.sin((performance.now() / 1000) * 1.6);
    const rx = Math.max(10, Math.min(gl[2] / 2 + 34, gl[0] - 2, W - gl[0] - 2));
    const ry = Math.max(10, Math.min(gl[3] / 2 + 18, gl[1] - 2, H - gl[1] - 2));
    g.save();
    g.globalAlpha = Math.max(0, Math.min(1, vis)) * breathe;
    g.translate(gl[0], gl[1]);
    g.scale(1, ry / rx);
    const grad = g.createRadialGradient(0, 0, 0, 0, 0, rx);
    grad.addColorStop(0, withAlpha(this.color, 0.48));
    grad.addColorStop(0.55, withAlpha(this.color, 0.2));
    grad.addColorStop(1, withAlpha(this.color, 0));
    g.fillStyle = grad;
    g.beginPath();
    g.arc(0, 0, rx, 0, Math.PI * 2);
    g.fill();
    g.restore();
  }

  paintPlain(g, pad, width, H) {
    g.font = `700 19px system-ui, Roboto, sans-serif`;
    let y = 24 - (this.plainOff || 0);
    g.fillStyle = "rgba(255,255,255,.45)";
    g.font = `500 13px system-ui, Roboto, sans-serif`;
    g.fillText("These lyrics aren't time-synced", pad, y);
    y += 34;
    g.font = `700 19px system-ui, Roboto, sans-serif`;
    g.fillStyle = "rgba(255,255,255,.88)";
    for (const ln of this.plain) {
      const words = ln.split(/\s+/);
      let row = "";
      for (const w of words) {
        const t = row ? row + " " + w : w;
        if (g.measureText(t).width > width && row) { g.fillText(row, pad, y); y += 26; row = w; } else row = t;
      }
      g.fillText(row, pad, y);
      y += 32;
    }
    this.plainH = y + (this.plainOff || 0);
    this.plainOff = Math.min(this.plainOff || 0, Math.max(0, this.plainH - H + 30));
  }

  onClick(e) {
    if (!this.lines.length) return;
    const r = this.cv.getBoundingClientRect();
    const g = this.cv.getContext("2d");
    const { heights, tops, shift } = this.geometry(r.width - 44, r.height, g);
    const y = e.clientY - r.top;
    for (let i = 0; i < this.lines.length; i++) {
      const top = shift + tops[i];
      if (y >= top && y < top + heights[i]) { player.seek(this.lines[i].t + ((lib.meta.settings.offsets[player.current?.id]) || 0) + 0.01); return; }
    }
  }
}

function withAlpha(color, a) {
  if (color.startsWith("hsl(")) return color.replace(/\)$/, ` / ${a})`);
  return color;
}
