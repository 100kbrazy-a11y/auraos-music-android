import { parseBlob } from "music-metadata";

export const AUDIO_EXT = ["mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aif", "aiff", "wma", "alac", "mp4", "weba", "webm"];

export function extOf(name) {
  const m = /\.([a-z0-9]+)$/i.exec(name || "");
  return m ? m[1].toLowerCase() : "";
}

export function isAudio(name, type) {
  return AUDIO_EXT.includes(extOf(name)) || (type || "").startsWith("audio/");
}

export function safeName(s, fallback = "Unknown") {
  s = String(s || "").replace(/[\x00-\x1f/\\:*?"<>|]+/g, " ").replace(/\s+/g, " ").trim().replace(/^\.+|\.+$/g, "").trim();
  return (s || fallback).slice(0, 120);
}

/** Same layout as the desktop app: Artist/Album/NN Title.ext */
export function organizedPath(t, ext) {
  const artist = t.albumartist || primaryArtist(t.artist) || "Unknown Artist";
  const album = t.album || "Unknown Album";
  const n = +t.track || 0, d = +t.disc || 0;
  const pre = n ? (d > 1 ? `${d}-${String(n).padStart(2, "0")} ` : `${String(n).padStart(2, "0")} `) : "";
  return `${safeName(artist, "Unknown Artist")}/${safeName(album, "Unknown Album")}/${pre}${safeName(t.title, "Untitled")}.${ext}`;
}

function fromFilename(name) {
  let stem = name.replace(/^.*[\\/]/, "").replace(/\.[^.]+$/, "");
  const out = {};
  let m = /^(\d{1,3})[\s._-]+(.*)$/.exec(stem);
  if (m) { out.track = +m[1]; stem = m[2]; }
  m = /^(.+?)\s+-\s+(.+)$/.exec(stem);
  if (m) { out.artist = m[1].trim(); stem = m[2]; }
  out.title = stem.replace(/_/g, " ").trim() || "Untitled";
  return out;
}

export const primaryArtist = (a) => String(a || "").split(/\s*,\s*/)[0].trim();

/** Ask the browser's own decoder for the length when the tags don't say. */
export function probeDuration(blob) {
  return new Promise((res) => {
    const a = new Audio();
    const url = URL.createObjectURL(blob);
    const done = (d) => { URL.revokeObjectURL(url); a.src = ""; res(isFinite(d) ? d : 0); };
    a.preload = "metadata";
    a.onloadedmetadata = () => done(a.duration);
    a.onerror = () => done(0);
    setTimeout(() => done(0), 4000);
    a.src = url;
  });
}

export function joinNames(list) {
  const seen = new Set(), out = [];
  for (const x of list || []) for (const p of String(x).split(/\s*[;/]\s*|\s*,\s*/)) {
    const k = p.trim();
    if (k && !seen.has(k.toLowerCase())) { seen.add(k.toLowerCase()); out.push(k); }
  }
  return out.join(", ");
}

/** Read song info + embedded cover from an audio Blob/File. */
export async function readTags(blob, name) {
  let m = null;
  try { m = await parseBlob(blob, { duration: true }); } catch {}
  const c = m?.common || {};
  const guess = fromFilename(name || blob.name || "");
  const t = {
    title: (c.title || "").trim() || guess.title,
    artist: joinNames(c.artists?.length ? c.artists : c.artist ? [c.artist] : []) || guess.artist || "",
    album: (c.album || "").trim(),
    albumartist: (c.albumartist || "").trim(),
    track: c.track?.no || guess.track || 0,
    disc: c.disk?.no || 0,
    year: c.year ? String(c.year) : (c.date || "").slice(0, 4),
    genre: joinNames(c.genre || []),
    composer: joinNames(c.composer || []),
    label: (c.label || [])[0] || "",
    copyright: c.copyright || "",
    isrc: (c.isrc || [])[0] || "",
    bpm: c.bpm || "",
    duration: m?.format?.duration || 0,
    codec: [m?.format?.codec || m?.format?.container, m?.format?.sampleRate ? `${m.format.sampleRate / 1000} kHz` : "",
      m?.format?.bitsPerSample ? `${m.format.bitsPerSample}-bit` : "",
      m?.format?.bitrate ? `${Math.round(m.format.bitrate / 1000)} kbps` : ""].filter(Boolean).join(" · "),
  };
  const pic = c.picture?.find((p) => /front/i.test(p.type || "")) || c.picture?.[0];
  return { tags: t, picture: pic ? new Blob([pic.data], { type: pic.format || "image/jpeg" }) : null };
}

/** Decode an image and make a square JPEG of `size` px. */
/** Decode a picture through an <img> (iPhone's createImageBitmap can draw it at full size, so only the top-left shows). */
export async function loadImage(blob) {
  const url = URL.createObjectURL(blob);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    return img;
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}

// iPhone's web view has a hard cap on memory for <canvas> and frees old canvases late: making a new one per cover
// runs out part-way through a big pack and the later covers silently fail. So a few canvases are reused.
const canvases = new Map();
export function sharedCanvas(size, key = "") {
  const k = size + key;
  let cv = canvases.get(k);
  if (!cv) { cv = document.createElement("canvas"); cv.width = cv.height = size; canvases.set(k, cv); }
  return cv;
}

export async function squareJpeg(blob, size) {
  try {
    const img = await loadImage(blob);
    const w = img.naturalWidth, h = img.naturalHeight, s = Math.min(w, h);
    if (!s) return null;
    const cv = sharedCanvas(size);
    const g = cv.getContext("2d");
    g.clearRect(0, 0, size, size);
    g.imageSmoothingQuality = "high";
    g.drawImage(img, (w - s) / 2, (h - s) / 2, s, s, 0, 0, size, size);    // the centre square, scaled to fit
    return await new Promise((r) => cv.toBlob(r, "image/jpeg", 0.9));
  } catch {
    return null;
  }
}

/** A pleasant accent colour from a cover. */
/** The cover's colour: its *dominant* hue (a colour that's actually in the picture, not an average of everything),
 *  and a soft grey for black-and-white covers. */
export const COLOR_VERSION = 3;
export async function coverColor(blob) {
  try {
    const N = 40;
    const img = await loadImage(blob);
    const cv = sharedCanvas(N, "read");
    const g = cv.getContext("2d", { willReadFrequently: true });
    g.clearRect(0, 0, N, N);
    g.drawImage(img, 0, 0, N, N);                          // the whole cover, shrunk (not just its corner)
    const d = g.getImageData(0, 0, N, N).data;
    const B = 36, bins = Array.from({ length: B }, () => ({ w: 0, r: 0, g: 0, b: 0 }));
    let colorful = 0, nr = 0, ng = 0, nb = 0, nn = 0;
    for (let i = 0; i < d.length; i += 4) {
      const r = d[i] / 255, gg = d[i + 1] / 255, bb = d[i + 2] / 255;
      const mx = Math.max(r, gg, bb), mn = Math.min(r, gg, bb), v = mx, sat = mx ? (mx - mn) / mx : 0;
      if (v < 0.14) continue;                                   // near-black says nothing about the colour
      if (sat < 0.2) { nr += r; ng += gg; nb += bb; nn++; continue; }
      let h = 0;
      const dd = mx - mn;
      h = mx === r ? (gg - bb) / dd + (gg < bb ? 6 : 0) : mx === gg ? (bb - r) / dd + 2 : (r - gg) / dd + 4;
      h /= 6;
      const w = Math.pow(sat, 1.4) * Math.pow(v, 0.8);
      const k = Math.min(B - 1, Math.floor(h * B));
      bins[k].w += w; bins[k].r += r * w; bins[k].g += gg * w; bins[k].b += bb * w;
      colorful++;
    }
    const total = (d.length / 4);
    if (colorful < total * 0.06) {                            // basically black / white / grey: keep it neutral
      const l = nn ? (nr + ng + nb) / (3 * nn) : 0.6;
      return `hsl(230 6% ${Math.round(Math.min(72, Math.max(58, l * 100)))}%)`;
    }
    let best = 0, bestW = -1;
    for (let k = 0; k < B; k++) {                             // strongest hue, counting its neighbours a little
      const w = bins[k].w + 0.5 * (bins[(k + B - 1) % B].w + bins[(k + 1) % B].w);
      if (w > bestW) { bestW = w; best = k; }
    }
    let r = 0, gg = 0, bb = 0, w = 0;
    for (const k of [(best + B - 1) % B, best, (best + 1) % B]) { r += bins[k].r; gg += bins[k].g; bb += bins[k].b; w += bins[k].w; }
    return hslFix([(r / w) * 255, (gg / w) * 255, (bb / w) * 255]);
  } catch {
    return null;
  }
}

function hslFix([r, g, b]) {
  r /= 255; g /= 255; b /= 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
  let h = 0, s = 0;
  const l = (mx + mn) / 2;
  if (mx !== mn) {
    const d = mx - mn;
    s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
    h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
    h /= 6;
  }
  const L = Math.min(0.72, Math.max(0.56, l)), S = Math.min(0.88, Math.max(0.4, s));
  return `hsl(${Math.round(h * 360)} ${Math.round(S * 100)}% ${Math.round(L * 100)}%)`;
}

/**
 * The cover's two main colours, for the player's moving "fog": the strongest hue, and a second one that's clearly
 * different (another hue, or for mostly black / white / skin-tone covers the cover's own grey). [[r,g,b],[r,g,b]] 0–255.
 */
export async function coverPalette(blob) {
  try {
    const N = 32;
    const img = await loadImage(blob);
    const cv = sharedCanvas(N, "pal");
    const g = cv.getContext("2d", { willReadFrequently: true });
    g.clearRect(0, 0, N, N);
    g.drawImage(img, 0, 0, N, N);
    const d = g.getImageData(0, 0, N, N).data;
    const B = 24, bins = Array.from({ length: B }, () => ({ w: 0, r: 0, g: 0, b: 0 }));
    let nr = 0, ng = 0, nb = 0, nn = 0;
    for (let i = 0; i < d.length; i += 4) {
      const r = d[i] / 255, gg = d[i + 1] / 255, bb = d[i + 2] / 255;
      const mx = Math.max(r, gg, bb), mn = Math.min(r, gg, bb), sat = mx ? (mx - mn) / mx : 0;
      if (mx < 0.1) continue;
      if (sat < 0.12) { nr += r; ng += gg; nb += bb; nn++; continue; }
      const dd = mx - mn;
      let h = mx === r ? (gg - bb) / dd + (gg < bb ? 6 : 0) : mx === gg ? (bb - r) / dd + 2 : (r - gg) / dd + 4;
      h /= 6;
      const w = Math.pow(sat, 1.2) * Math.pow(mx, 0.7);
      const k = Math.min(B - 1, Math.floor(h * B));
      bins[k].w += w; bins[k].r += r * w; bins[k].g += gg * w; bins[k].b += bb * w;
    }
    const near = (k) => bins[k].w + 0.5 * (bins[(k + B - 1) % B].w + bins[(k + 1) % B].w);
    const avg = (k) => {
      let r = 0, gg = 0, bb = 0, w = 0;
      for (const j of [(k + B - 1) % B, k, (k + 1) % B]) { r += bins[j].r; gg += bins[j].g; bb += bins[j].b; w += bins[j].w; }
      return w ? [(r / w) * 255, (gg / w) * 255, (bb / w) * 255] : null;
    };
    let a = -1, aw = 0;
    for (let k = 0; k < B; k++) if (near(k) > aw) { aw = near(k); a = k; }
    let b = -1, bw = 0;
    for (let k = 0; k < B; k++) {
      const dist = Math.min(Math.abs(k - a), B - Math.abs(k - a));
      if (dist >= 4 && near(k) > bw) { bw = near(k); b = k; }
    }
    const grey = nn ? [(nr / nn) * 255, (ng / nn) * 255, (nb / nn) * 255] : [120, 122, 130];
    const A = a >= 0 && aw > 0.4 ? avg(a) : grey;
    const Bc = b >= 0 && bw > aw * 0.18 ? avg(b) : (A === grey ? [grey[0] * 0.7 + 40, grey[1] * 0.7 + 30, grey[2] * 0.7 + 50] : grey);
    return [A, Bc].map((c) => c.map((x) => Math.round(Math.max(0, Math.min(255, x)))));
  } catch {
    return null;
  }
}
