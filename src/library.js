import { idb } from "./store.js";
import { COLOR_VERSION } from "./tags.js";
import { readTags, squareJpeg, coverColor, organizedPath, extOf, isAudio, safeName, primaryArtist, probeDuration } from "./tags.js";
import { buildZip, readZip } from "./zip.js";
import { putSongFile, songBlob, delSongFile, songURL, libraryFileURL, dropPicked } from "./files.js";
import { diag, timed } from "./diag.js";

export const PACK_FORMAT = "auraos-musicpack";
const uid = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
const norm = (s) => String(s || "").toLowerCase().replace(/[^a-z0-9]+/g, "");

function hash(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return (h >>> 0).toString(36);
}

export const albumKey = (t) => `${t.albumartist || primaryArtist(t.artist) || "Unknown Artist"}\u0001${t.album || "Unknown Album"}`;

class Library extends EventTarget {
  songs = new Map();
  meta = { playlists: {}, favorites: {}, settings: { eq: "Flat", motion: true, offsets: {} }, lyrics: {} };
  albums = [];
  albumMap = new Map();
  covers = new Map();      // coverId -> {url, small, color}

  async load() {
    for (const s of await idb.all("songs")) this.songs.set(s.id, s);
    const m = await idb.get("kv", "meta");
    if (m) this.meta = { ...this.meta, ...m, settings: { ...this.meta.settings, ...(m.settings || {}) } };
    await this.loadCovers();
    this.rebuild();
  }

  async loadCovers() {
    const ids = new Set([...this.songs.values()].map((s) => s.cover).filter(Boolean));
    for (const a of Object.values(this.meta.artistInfo || {})) if (a?.image) ids.add(a.image);     // artist photos (from YouTube Music)
    for (const id of ids) if (!this.covers.has(id)) await this.loadCover(id);
  }

  async loadCover(id) {
    const c = await idb.get("covers", id);
    if (!c) return null;
    const v = { url: URL.createObjectURL(c.big), small: URL.createObjectURL(c.small), color: c.color, big: c.big, smallBlob: c.small, cv: c.cv || 1 };
    this.covers.set(id, v);
    return v;
  }

  /** Covers whose colour was worked out the old way: work it out again from the small cover (in the background, once). */
  async refreshColors(onEach) {
    for (const [id, v] of [...this.covers]) {
      if (v.cv >= COLOR_VERSION) continue;
      const color = await coverColor(v.smallBlob || v.big);
      if (!color) continue;
      v.color = color; v.cv = COLOR_VERSION;
      const c = await idb.get("covers", id);
      if (c) await idb.put("covers", { ...c, color, cv: COLOR_VERSION }, id);
      onEach?.(id);
      await new Promise((r) => setTimeout(r, 0));
    }
  }



  saveMeta() {
    clearTimeout(this._mt);
    this._mt = setTimeout(() => idb.put("kv", this.meta, "meta"), 250);
  }

  rebuild() {
    const map = new Map();
    for (const s of this.songs.values()) {
      const k = albumKey(s);
      if (!map.has(k)) map.set(k, { key: k, title: s.album || "Unknown Album", artist: s.albumartist || primaryArtist(s.artist) || "Unknown Artist", tracks: [], cover: null, year: "", added: 0 });
      const a = map.get(k);
      a.tracks.push(s);
      a.cover ||= s.cover;
      a.year ||= s.year;
      a.added = Math.max(a.added, s.added || 0);
    }
    for (const a of map.values()) a.tracks.sort((x, y) => (x.disc || 0) - (y.disc || 0) || (x.track || 0) - (y.track || 0) || x.title.localeCompare(y.title));
    this.albumMap = map;
    this.albums = [...map.values()].sort((a, b) => a.title.localeCompare(b.title));
    this.dispatchEvent(new Event("change"));
  }

  get sortedSongs() {
    return [...this.songs.values()].sort((a, b) => a.title.localeCompare(b.title) || (a.artist || "").localeCompare(b.artist || ""));
  }

  artists() {
    const m = new Map();
    for (const s of this.songs.values()) {
      for (const name of (s.albumartist ? [s.albumartist] : []).concat((s.artist || "Unknown Artist").split(/\s*,\s*/))) {
        if (!name) continue;
        const k = name.toLowerCase();
        if (!m.has(k)) m.set(k, { name, songs: new Set(), cover: null });
        const e = m.get(k);
        e.songs.add(s);
        e.cover ||= s.cover;
      }
    }
    for (const [k, e] of m) { const img = this.meta.artistInfo?.[k]?.image; if (img && this.covers.has(img)) e.cover = img; }
    return [...m.values()].sort((a, b) => a.name.localeCompare(b.name));
  }

  /** What is known about an artist besides their songs: { image, channel, about, synced } (set by Add from Link) */
  artistInfo(name) { return this.meta.artistInfo?.[String(name || "").toLowerCase()] || null; }
  async setArtistInfo(name, fields, picBlob) {
    const k = String(name || "").toLowerCase();
    const info = ((this.meta.artistInfo ||= {})[k] ||= {});
    if (picBlob) {
      try {
        const id = "artist-" + hash(k);
        const big = await squareJpeg(picBlob, 1000);
        const small = big && await squareJpeg(big, 160);
        if (big && small) {
          const color = await coverColor(small);
          await idb.put("covers", { big, small, color, cv: COLOR_VERSION }, id);
          await this.loadCover(id);
          info.image = id;
        }
      } catch (e) { diag.log(`artist photo: ${e?.message || e}`); }
    }
    for (const [f, v] of Object.entries(fields || {})) if (v !== undefined && v !== null && v !== "") info[f] = v;
    this.saveMeta();
    return info;
  }

  cover(id) { return id ? this.covers.get(id) : null; }
  albumOf(s) { return this.albumMap.get(albumKey(s)); }
  colorOf(s) { return this.cover(s?.cover)?.color || null; }

  // ------------------------------------------------------------ adding music
  async storeCover(t, picBlob) {
    const id = hash(albumKey(t));
    if (this.covers.has(id)) return id;
    if (!picBlob) return null;
    const big = await squareJpeg(picBlob, 800);
    if (!big) return null;
    const small = await squareJpeg(big, 160);
    const color = await coverColor(small || big);
    await idb.put("covers", { big, small, color, cv: COLOR_VERSION }, id);
    await this.loadCover(id);
    return id;
  }

  existing(t) {
    for (const s of this.songs.values()) {
      if (norm(s.title) === norm(t.title) && norm(s.artist) === norm(t.artist) && norm(s.album) === norm(t.album)
          && Math.abs((s.duration || 0) - (t.duration || 0)) < 1.5) return s;
    }
    return null;
  }

  /** The same song (title, artist, length) already here, on whatever release. */
  existingAnywhere(t) {
    const title = norm(t.title), artist = norm(String(t.artist || "").split(/\s*,\s*/)[0]);
    for (const s of this.songs.values()) {
      if (norm(s.title) === title && norm(String(s.artist || "").split(/\s*,\s*/)[0]) === artist
          && Math.abs((s.duration || 0) - (t.duration || 0)) < 2.5) return s;
    }
    return null;
  }

  uniqueFile(path) {
    const used = new Set([...this.songs.values()].map((s) => s.file.toLowerCase()));
    if (!used.has(path.toLowerCase())) return path;
    const m = /^(.*?)(\.[^.]+)$/.exec(path);
    for (let i = 2; ; i++) {
      const p = `${m[1]} (${i})${m[2]}`;
      if (!used.has(p.toLowerCase())) return p;
    }
  }

  /** Add one audio blob with info (from the file's tags, optionally overridden). Returns {song, skipped}. */
  async addAudio(blob, name, override = {}, folderCover = null, source = null) {
    diag.step = `Reading tags · ${name}`;
    const { tags, picture } = await timed(readTags(blob, name), 60000, `Reading the tags of ${name}`);
    for (const [k, v] of Object.entries(override)) if (v !== undefined && v !== null && v !== "") tags[k] = v;
    if (!tags.duration) { diag.step = `Measuring · ${name}`; tags.duration = await probeDuration(blob); }
    const dup = this.existing(tags);
    if (dup) return { song: dup, skipped: true };
    const ext = extOf(name) || "mp3";
    const file = this.uniqueFile(organizedPath(tags, ext));
    const type = blob.type || mimeFor(ext);
    const song = { id: uid(), file, ...tags, size: blob.size, added: Date.now(), type };
    diag.step = `Cover · ${name}`;
    song.cover = await timed(this.storeCover(tags, picture || folderCover), 30000, `Saving the cover of ${name}`);
    song.store = await putSongFile(blob.type ? blob : new Blob([blob], { type }), file, source || blob.source);
    await idb.put("songs", song);
    this.songs.set(song.id, song);
    return { song, skipped: false };
  }

  /** Add a file already downloaded into Library/<rel> (YouTube links), with known info and cover. Returns {song, skipped}. */
  async addLocalFile(rel, size, ext, tags, picture) {
    const dup = this.existing(tags);
    if (dup) { await dropPicked(rel); return { song: dup, skipped: true }; }
    const file = this.uniqueFile(organizedPath(tags, ext));
    const type = mimeFor(ext);
    const song = { id: uid(), file, ...tags, size, added: Date.now(), type };
    diag.step = `Cover · ${tags.title}`;
    song.cover = await timed(this.storeCover(tags, picture), 30000, `Saving the cover of ${tags.title}`);
    try { song.store = await putSongFile({ size, type }, file, { path: await libraryFileURL(rel), offset: 0, length: -1 }); }
    finally { await dropPicked(rel); }
    await idb.put("songs", song);
    this.songs.set(song.id, song);
    return { song, skipped: false };
  }

  async importFiles(files, onProgress) {
    const list = [...files];
    const audio = list.filter((f) => isAudio(f.name, f.type));
    const images = list.filter((f) => /\.(jpe?g|png|webp)$/i.test(f.name));
    const folderCover = images.find((f) => /cover|folder|front/i.test(f.name)) || images[0] || null;
    let added = 0, skipped = 0, failed = 0, firstError = "";
    const songs = [];
    for (let i = 0; i < audio.length; i++) {
      onProgress?.(i / audio.length, audio[i].name);
      try {
        const r = await this.addAudio(audio[i], audio[i].name, {}, folderCover, audio[i].source);
        r.skipped ? skipped++ : (added++, songs.push(r.song));
      } catch (e) {
        console.warn("import failed", audio[i].name, e);
        diag.log(`import: failed ${audio[i].name}: ${e?.message || e}`);
        firstError ||= String(e?.message || e);
        failed++;
      }
    }
    this.rebuild();
    return { added, skipped, failed, firstError, songs, ignored: list.length - audio.length - images.length };
  }

  // ------------------------------------------------------------ music packs (same format as the desktop app)
  async importPack(file, onProgress) {
    diag.set(`pack: ${file.name} (${Math.round((file.size || 0) / 1048576)} MB)`);
    const entries = await timed(readZip(file), 120000, "Reading the pack's contents");
    diag.log(`pack: ${entries.length} entries`);
    const man = entries.find((e) => e.name === "musicpack.json");
    let manifest = {};
    if (man) { try { manifest = JSON.parse(await (await man.blob()).text()); } catch {} }
    const info = new Map((manifest.songs || []).map((s) => [s.file, s]));
    const covers = new Map();
    for (const e of entries) if (/(^|\/)(cover|folder|front)\.(jpe?g|png)$/i.test(e.name)) covers.set(e.name.replace(/[^/]*$/, ""), e);
    const audio = entries.filter((e) => isAudio(e.name) && !e.name.includes("__MACOSX"));
    let added = 0, skipped = 0, failed = 0, firstError = "";
    const order = [];
    for (let i = 0; i < audio.length; i++) {
      const e = audio[i];
      onProgress?.(i / audio.length, e.name.replace(/^.*\//, ""));
      try {                                // one unreadable song doesn't stop the rest of the pack
        const rel = e.name.replace(/^Music\//, "");
        const fields = { ...(info.get(rel) || {}) };
        delete fields.file; delete fields.duration;
        const dir = e.name.replace(/[^/]*$/, "");
        const cov = covers.get(dir) ? await covers.get(dir).blob() : null;
        const raw = await e.blob();
        const blob = new Blob([raw], { type: mimeFor(extOf(e.name)) });
        const r = await this.addAudio(blob, e.name.replace(/^.*\//, ""), fields, cov, raw.source);
        r.skipped ? skipped++ : added++;
        order.push(r.song.id);
      } catch (err) {
        console.warn("pack: couldn't add", e.name, err);
        diag.log(`pack: failed ${e.name}: ${err?.message || err}`);
        firstError ||= String(err?.message || err);
        failed++;
      }
    }
    this.rebuild();
    let playlist = null;
    if (manifest.playlist && order.length) {
      const same = this.meta.playlists[manifest.playlist];
      if (!(same && same.join() === order.join())) {
        playlist = this.uniquePlaylist(manifest.playlist, same ? undefined : manifest.playlist);
        this.meta.playlists[playlist] = order;
        this.saveMeta();
      }
    }
    const title = manifest.title || (file.name || "Music pack").replace(/-musicpack.*$/i, "").replace(/\.zip$/i, "");
    return { added, skipped, failed, firstError, playlist, title };
  }

  async buildPack(title, songs, playlist, onProgress) {
    const entries = [], names = new Set(), coverDone = new Set();
    const manifest = { format: PACK_FORMAT, version: 1, title, created: new Date().toISOString().slice(0, 19),
      made_with: "AuraOS Music for Android", playlist: playlist || null, songs: [] };
    for (const s of songs) {
      const blob = await songBlob(s);
      if (!blob) continue;
      let rel = organizedPath(s, extOf(s.file) || "mp3");
      const base = rel.replace(/\.[^.]+$/, ""), ext = extOf(rel);
      for (let n = 2; names.has(rel.toLowerCase()); n++) rel = `${base} (${n}).${ext}`;
      names.add(rel.toLowerCase());
      entries.push({ name: "Music/" + rel, blob });
      const folder = rel.replace(/[^/]*$/, "");
      const cov = this.cover(s.cover);
      if (cov && !coverDone.has(folder)) { coverDone.add(folder); entries.push({ name: "Music/" + folder + "cover.jpg", blob: cov.big }); }
      const f = {};
      for (const k of ["title", "artist", "album", "albumartist", "track", "disc", "year", "genre"]) if (s[k]) f[k] = s[k];
      manifest.songs.push({ ...f, file: rel, duration: Math.round((s.duration || 0) * 100) / 100 });
    }
    if (!manifest.songs.length) throw new Error("There's nothing to share.");
    entries.push({ name: "musicpack.json", blob: new Blob([JSON.stringify(manifest, null, 1)], { type: "application/json" }) });
    entries.push({ name: "README.txt", blob: new Blob([`${title} — a music pack made with AuraOS Music.\n\nTo add it: open AuraOS Music → Settings → Add Music Pack.\nOr just unzip it: the songs are in the Music folder, sorted by artist and album.\n`]) });
    const zip = await buildZip(entries, onProgress);
    return { blob: zip, name: `${safeName(title, "Music")}-musicpack.zip`, count: manifest.songs.length };
  }

  uniquePlaylist(base, prefer) {
    if (prefer && !(prefer in this.meta.playlists)) return prefer;
    let name = base;
    for (let i = 2; name in this.meta.playlists; i++) name = `${base} ${i}`;
    return name;
  }

  // ------------------------------------------------------------ edits
  async removeSongs(ids) {
    for (const id of ids) {
      const s = this.songs.get(id);
      if (!s) continue;
      await delSongFile(s);
      await idb.del("songs", id);
      this.songs.delete(id);
      delete this.meta.favorites[id];
      for (const k of Object.keys(this.meta.playlists)) this.meta.playlists[k] = this.meta.playlists[k].filter((x) => x !== id);
    }
    this.saveMeta();
    this.rebuild();
  }

  /** Songs without a cover: use their album's cover if it has one now, else the picture inside the song's own file
   *  (covers that failed to save during a big import). Returns how many were fixed. */
  async repairCovers(onFix) {
    let fixed = 0;
    for (const s of [...this.songs.values()]) {
      if (s.cover && this.covers.has(s.cover)) continue;
      if (s.coverChecked === COLOR_VERSION) continue;          // looked already: the file has no picture
      try {
        let id = hash(albumKey(s));
        if (!this.covers.has(id)) {
          const blob = await songBlob(s);
          if (!blob) continue;
          const { picture } = await timed(readTags(blob, s.file), 60000, `Reading the cover of ${s.title}`);
          id = picture ? await this.storeCover(s, picture) : null;
        }
        if (id && this.covers.has(id)) { await this.updateSong(s.id, { cover: id }); fixed++; onFix?.(s); }
        else await this.updateSong(s.id, { coverChecked: COLOR_VERSION });
      } catch (e) { diag.log(`cover repair: ${s.title}: ${e?.message || e}`); }
    }
    if (fixed) { diag.log(`cover repair: fixed ${fixed}`); this.rebuild(); }
    return fixed;
  }

  async updateSong(id, fields) {
    const s = this.songs.get(id);
    Object.assign(s, fields);
    await idb.put("songs", s);
  }

  isFav(id) { return id in this.meta.favorites; }
  toggleFav(ids, on) {
    on ??= !ids.every((id) => this.isFav(id));
    const now = Date.now();
    ids.forEach((id, i) => {
      if (on) { if (!this.isFav(id)) this.meta.favorites[id] = now - i; }
      else delete this.meta.favorites[id];
    });
    this.saveMeta();
    this.dispatchEvent(new Event("favs"));
    return on;
  }
  favorites() {
    return Object.entries(this.meta.favorites).sort((a, b) => b[1] - a[1]).map(([id]) => this.songs.get(id)).filter(Boolean);
  }

  playlistSongs(name) { return (this.meta.playlists[name] || []).map((id) => this.songs.get(id)).filter(Boolean); }
  addToPlaylist(name, ids) {
    const pl = (this.meta.playlists[name] ||= []);
    let n = 0;
    for (const id of ids) if (!pl.includes(id)) { pl.push(id); n++; }
    this.saveMeta();
    return n;
  }

  async fileBlob(s) { return songBlob(s); }
  /** { url, revoke } for playing a song, or null if its file is missing */
  async fileURL(s) { return songURL(s); }
}

export function mimeFor(ext) {
  return { mp3: "audio/mpeg", flac: "audio/flac", m4a: "audio/mp4", mp4: "audio/mp4", aac: "audio/aac", ogg: "audio/ogg",
    opus: "audio/ogg", wav: "audio/wav", webm: "audio/webm", weba: "audio/webm" }[ext] || "";
}

export const lib = new Library();
