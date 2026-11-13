// Where the song files themselves live.
//  - iPhone app: real files in the app's Library folder, played straight from disk (the web view on iPhone
//    can't reliably keep big audio files in its database or play them from there).
//  - everywhere else: in IndexedDB, as before.
import { Capacitor, registerPlugin } from "@capacitor/core";
import { idb } from "./store.js";
import { diag, timed } from "./diag.js";

export const onDisk = Capacitor.isNativePlatform() && Capacitor.getPlatform() === "ios";
const NC = registerPlugin("NativeChrome");
let libURL = null;             // file:// URL of the app's Library folder
async function libraryURL() { return (libURL ||= (await NC.libraryPath()).url.replace(/\/?$/, "/")); }
const encPath = (p) => p.split("/").map(encodeURIComponent).join("/");
/** call once at start-up so songFileURLNow() can answer instantly */
export async function initLibraryURL() { if (onDisk) { try { await libraryURL(); } catch {} } }
/** file:// URL of a song stored on the phone ("" if it isn't, or not known yet) — no waiting */
export function songFileURLNow(s) { return onDisk && s?.store === "disk" && libURL ? libURL + encPath(ROOT + s.file) : ""; }
const ROOT = "Songs/";
/** file:// URL of a path inside the app's Library folder */
export async function libraryFileURL(rel) { return (await libraryURL()) + encPath(rel); }
let F = null, DIR = null;
// Note: a Capacitor plugin object must never be the *result* of a promise (an async function's return value):
// the promise asks it for .then, the plugin answers "Filesystem.then() is not implemented" and the promise never
// settles. So this loads it and returns nothing; use F afterwards.
async function loadFs() {
  if (!F) { const m = await import("@capacitor/filesystem"); F = m.Filesystem; DIR = m.Directory.Library; }
}
function toBase64(b) {
  return new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result).split(",")[1] || ""); r.onerror = () => rej(r.error); r.readAsDataURL(b); });
}

/** Store a song's audio. Returns where it went ("disk" or "db").
 *  source: { path, offset, length } of the same bytes already on the phone (a picked file, or a song inside a pack) —
 *  then the app copies it natively and the audio never has to pass through the web view. */
export async function putSongFile(blob, file, source) {
  if (onDisk && source?.path) {
    const name = file.replace(/^.*\//, "");
    diag.step = `Saving ${name}`;
    await timed(NC.copyIn({ from: source.path, offset: source.offset || 0, length: source.length ?? -1, to: ROOT + file }), 120000, `Saving ${name}`);
    diag.step = `Saved ${name}`;
    return "disk";
  }
  if (!onDisk) {
    await idb.put("files", blob instanceof File ? new Blob([blob], { type: blob.type }) : blob, file);
    return "db";
  }
  await timed(loadFs(), 20000, "Loading the file module");
  const fsx = F, path = ROOT + file;
  const name = file.replace(/^.*\//, "");
  const CH = 1536 * 1024;                         // written in pieces so a big file never sits in memory twice
  const n = Math.max(1, Math.ceil(blob.size / CH));
  for (let i = 0, off = 0; i < n; i++, off += CH) {
    diag.step = `Saving ${name} · ${i + 1}/${n}`;
    const data = await timed(toBase64(blob.slice(off, off + CH)), 45000, `Reading ${name} (part ${i + 1})`);
    if (i === 0) await timed(fsx.writeFile({ directory: DIR, path, data, recursive: true }), 45000, `Saving ${name} (part 1)`);
    else await timed(fsx.appendFile({ directory: DIR, path, data }), 45000, `Saving ${name} (part ${i + 1})`);
  }
  diag.step = `Saved ${name}`;
  return "disk";
}

/** A URL the <audio> element can play. revoke: whether the caller should URL.revokeObjectURL it later. */
export async function songURL(s) {
  if (s.store === "disk") return { url: Capacitor.convertFileSrc((await libraryURL()) + encPath(ROOT + s.file)), revoke: false };
  const blob = await idb.get("files", s.file);
  return blob ? { url: URL.createObjectURL(blob), revoke: true } : null;
}

/** The audio as a Blob (for making music packs). */
export async function songBlob(s) {
  if (s.store === "disk") {
    const u = await songURL(s);
    const r = await fetch(u.url, { headers: { Range: "bytes=0-" } });   // (the app's file server needs a Range)
    if (!r.ok) return null;
    const b = await r.blob();
    return b.type ? b : new Blob([b], { type: s.type || "" });
  }
  return idb.get("files", s.file);
}

/** Remove a file the picker brought in (path relative to Library), once it has been imported. */
export async function dropPicked(rel) { if (onDisk && rel) { try { await NC.removeFile({ to: rel }); } catch {} } }

export async function delSongFile(s) {
  if (s.store === "disk") { try { await NC.removeFile({ to: ROOT + s.file }); } catch {} }
  else await idb.del("files", s.file);
}

/** Does this song's audio exist? */
export async function hasSongFile(s) {
  if (s.store === "disk") { try { await loadFs(); await F.stat({ directory: DIR, path: ROOT + s.file }); return true; } catch { return false; } }
  return !!(await idb.get("files", s.file));
}

/** A file on the phone read in pieces through the app's file server (byte ranges), so a big music pack never has
 *  to be loaded into memory. Has just what the zip reader needs: size, name, slice(), arrayBuffer(), stream(). */
export class DiskFile {
  constructor(url, size, name, start = 0, end = size, nativePath = null) { this.url = url; this.total = size; this.name = name; this.start = start; this.end = end; this.nativePath = nativePath; }
  get size() { return Math.max(0, this.end - this.start); }
  slice(a = 0, b = this.size) {
    const n = this.size;
    const s = a < 0 ? Math.max(0, n + a) : Math.min(n, a), e = b < 0 ? Math.max(0, n + b) : Math.min(n, b);
    return new DiskFile(this.url, this.total, this.name, this.start + s, this.start + Math.max(s, e), this.nativePath);
  }
  async arrayBuffer() {
    if (!this.size) return new ArrayBuffer(0);
    const get = () => timed(fetch(this.url, { headers: { Range: `bytes=${this.start}-${this.end - 1}` } }), 120000, `Reading ${this.name}`);
    let r;
    try { r = await get(); } catch (e) { diag.log(`read failed (${e.message}), trying again`); await new Promise((ok) => setTimeout(ok, 700)); r = await get(); }
    if (!r.ok && r.status !== 0) throw new Error(`Couldn't read ${this.name} (${r.status})`);
    const buf = await r.arrayBuffer();
    if (buf.byteLength !== this.size) throw new Error(`Couldn't read ${this.name} (got ${buf.byteLength} of ${this.size} bytes)`);
    return buf;
  }
  /** a real Blob of these bytes */
  async realize(type = "") {
    const b = new Blob([await this.arrayBuffer()], { type });
    if (this.nativePath) b.source = { path: this.nativePath, offset: this.start, length: this.size };   // where these bytes are on the phone
    return b;
  }
  async text() { return new TextDecoder().decode(await this.arrayBuffer()); }
  stream() { const self = this; return new ReadableStream({ async start(c) { c.enqueue(new Uint8Array(await self.arrayBuffer())); c.close(); } }); }
}
