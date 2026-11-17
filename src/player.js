import { diag } from "./diag.js";
import { lib } from "./library.js";
import { MediaSession } from "@jofr/capacitor-media-session";
import { Capacitor, registerPlugin } from "@capacitor/core";
import { songRelPath } from "./files.js";

// Android: the native player (Media3 / ExoPlayer in Android's media service). See AuraPlaybackService.java.
const AuraPlayer = globalThis.__auraMock || (Capacitor.isNativePlatform() && Capacitor.getPlatform() === "android" ? registerPlugin("AuraPlayer") : null);   // (__auraMock: tests)
export const nativePlayerAvailable = !!AuraPlayer;
export { AuraPlayer };

/**
 * Looks like an <audio> element to the rest of the app (currentTime, duration, paused, play(), pause()…), but the
 * sound comes from the native player. Position is worked out locally between the native player's updates.
 */
class NativeAudio extends EventTarget {
  constructor(P) {
    super();
    this.P = P;
    this._pos = 0; this._at = performance.now(); this._dur = NaN; this._play = false;
    this.dataset = {}; this.volume = 1; this.src = ""; this.error = null;
    this.bins = new Uint8Array(512); this._binAt = 0; this._binBusy = false; this._vizUsed = 0; this._vizOn = false;
    const self = this;
    this.analyser = {                                  // the visualizer reads the native player's spectrum
      frequencyBinCount: 1024,
      getByteFrequencyData(arr) { self.spectrum(arr); },
    };
  }
  get paused() { return !this._play; }
  get currentTime() {
    const t = this._play ? this._pos + ((performance.now() - this._at) / 1000) * (this._speed || 1) : this._pos;
    return Math.max(0, isFinite(this._dur) ? Math.min(t, this._dur) : t);
  }
  set currentTime(t) {
    this._pos = Math.max(0, t); this._at = performance.now();
    AuraPlayer.seek({ ms: Math.round(this._pos * 1000) }).catch(() => {});
    this.dispatchEvent(new Event("seeked"));
  }
  get duration() { return this._dur; }
  get readyState() { return isFinite(this._dur) && this._dur > 0 ? 4 : 0; }
  play() { this._set(true); return AuraPlayer.play(); }
  pause() { this._pos = this.currentTime; this._at = performance.now(); this._set(false); AuraPlayer.pause().catch(() => {}); }
  load() {}
  removeAttribute() { this.src = ""; }
  _set(on) {
    if (this._play === on) return;
    this._play = on;
    this.P.emit("state");
    this.P.tickTime();
  }
  /** an update from the native player */
  apply(d) {
    if (!d) return;
    this._pos = (d.pos || 0) / 1000; this._at = performance.now();
    this._speed = d.speed > 0 ? d.speed : 1;
    const dur = d.dur > 0 ? d.dur / 1000 : NaN;
    if (dur !== this._dur && !(isNaN(dur) && isNaN(this._dur))) {
      this._dur = isNaN(dur) ? this._dur : dur;
      this.dispatchEvent(new Event("loadedmetadata"));
      this.dispatchEvent(new Event("durationchange"));
      const s = this.P.current;
      if (s && !s.duration && dur > 0 && s.id === d.id) lib.updateSong(s.id, { duration: dur });
    }
    this._set(!!d.playing);
    this.P.emit("time");
  }
  spectrum(arr) {
    const now = performance.now();
    this._vizUsed = now;
    if (!this._vizOn) {
      this._vizOn = true;
      AuraPlayer.setViz({ on: true }).catch(() => {});
      const off = setInterval(() => {                  // nobody has looked for a second: stop working it out
        if (performance.now() - this._vizUsed < 1000) return;
        clearInterval(off); this._vizOn = false; this.bins.fill(0);
        AuraPlayer.setViz({ on: false }).catch(() => {});
      }, 1000);
    }
    if (!this._binBusy && now - this._binAt > 30) {
      this._binBusy = true; this._binAt = now;
      AuraPlayer.levels().then((r) => {
        const b = atob(r.data || "");
        for (let i = 0; i < b.length && i < 512; i++) this.bins[i] = b.charCodeAt(i);
      }).catch(() => {}).finally(() => { this._binBusy = false; });
    }
    arr.set(this.bins.subarray(0, Math.min(512, arr.length)));
    if (arr.length > 512) arr.fill(0, 512);
  }
}

export const EQ_BANDS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
export const EQ_PRESETS = {
  Flat: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  "Bass Boost": [6, 5, 4, 2, 0, 0, 0, 0, 0, 0],
  "Bass Reducer": [-6, -5, -4, -2, 0, 0, 0, 0, 0, 0],
  "Treble Boost": [0, 0, 0, 0, 0, 1, 2, 4, 5, 6],
  "Treble Reducer": [0, 0, 0, 0, 0, -1, -2, -4, -5, -6],
  Vocal: [-2, -2, -1, 1, 3, 4, 3, 1, 0, -1],
  Rock: [5, 4, 2, -1, -2, -1, 1, 3, 4, 5],
  Pop: [-1, 1, 3, 4, 3, 0, -1, -1, 1, 2],
  "Hip-Hop": [6, 5, 2, 3, -1, -1, 1, -1, 2, 3],
  Electronic: [5, 4, 1, 0, -2, 2, 1, 2, 4, 5],
  Jazz: [3, 2, 1, 2, -1, -1, 0, 1, 2, 3],
  Classical: [4, 3, 2, 1, -1, -1, 0, 2, 3, 4],
  Acoustic: [4, 3, 2, 1, 1, 1, 2, 3, 3, 2],
  "Late Night": [-2, -1, 0, 1, 2, 2, 1, 0, -2, -3],
  Loudness: [6, 4, 0, 0, -2, 0, -1, -4, 4, 2],
};

class Player extends EventTarget {
  els = [new Audio(), new Audio()];   // two players, so songs can crossfade
  cur = 0;
  xf = null;                          // the crossfade in progress
  queue = [];        // song ids in play order
  base = [];         // original order (for un-shuffling)
  index = -1;
  shuffle = false;
  repeat = "off";    // off | all | one
  ctx = null;
  url = null;
  _loadToken = 0;

  constructor() {
    super();
    for (const a of this.els) {
      a.preload = "auto";
      const live = (fn) => (e) => { if (a === this.audio) fn(e); };      // only the active player talks to the UI
      a.addEventListener("ended", live(() => this.onEnded()));
      for (const ev of ["play", "pause", "playing"]) a.addEventListener(ev, live(() => { this.emit("state"); this.updateSession(); }));
      a.addEventListener("timeupdate", live(() => {
        this.emit("time"); this.checkCrossfade();
        const s = this.current;                       // silence cut from the end: the song ends at its last sound
        if (s?.trimEnd > 0 && lib.meta.settings.trimSilence !== false && !this.xf && a.currentTime >= s.trimEnd && !a.paused) this.onEnded();
      }));
      a.addEventListener("loadedmetadata", live(() => this.emit("time")));
      a.addEventListener("durationchange", live(() => {          // learn the length if the file's tags didn't have it
        const s = this.current;
        if (s && !s.duration && isFinite(a.duration) && a.duration > 0) lib.updateSong(s.id, { duration: a.duration });
      }));
      a.addEventListener("error", live(() => this.onMediaError(a)));
    }
    setInterval(() => this.updatePosition(), 5000);
    setInterval(() => this.checkCrossfade(), 200);
    this.setupSession();
  }
  get audio() { return this.nat ? this.fa : this.els[this.cur]; }
  /** how much silence was cut from the start of the song playing: the native player counts from there (lyrics add it back) */
  trimShift() { const s = this.current; return this.nat && lib.meta.settings.trimSilence !== false && s?.trimStart > 0 ? s.trimStart : 0; }

  // ------------------------------------------------ the native Android player
  nat = null;      // the AuraPlayer plugin once it's in charge
  fa = null;       // its <audio>-like stand-in
  _qDirty = true;
  /** Hand playback to Android's own player. Returns its state (it may already be playing: the app was reopened). */
  async useNative() {
    if (this.nat) return this._natStart;
    if (!AuraPlayer) return null;
    let st;
    try { st = await AuraPlayer.start(); } catch (e) { diag.log("native player didn't start: " + (e?.message || e)); return null; }
    for (const el of this.els) { try { el.pause(); } catch {} }
    this.fa = new NativeAudio(this);
    this.analyser = this.fa.analyser;
    this.nat = AuraPlayer;
    this._natStart = st;
    AuraPlayer.addListener("state", (d) => this.fa.apply(d));
    AuraPlayer.addListener("track", (d) => this.natTrack(d));
    AuraPlayer.addListener("ended", (d) => { this.fa.apply(d); this.onEnded(); });
    AuraPlayer.addListener("error", (d) => this.natError(d));
    AuraPlayer.setRepeat({ mode: this.repeat }).catch(() => {});
    this.sendXf();
    this.applyEq(lib.meta.settings.eq);
    this.addEventListener("queue", () => this.markQueue());
    diag.log(`native player: on${st?.count ? ` (already has ${st.count} songs, ${st.playing ? "playing" : "paused"})` : ""}`);
    return st;
  }
  natItems() {
    const cut = lib.meta.settings.trimSilence !== false;
    return this.queue.map((id) => {
      const s = lib.songs.get(id) || {};
      const o = { id, path: songRelPath(s), title: s.title || "", artist: s.artist || "", album: s.album || "" };
      if (cut && s.trimStart > 0) o.start = Math.round(s.trimStart * 1000);           // silence cut from the start / end
      if (cut && s.trimEnd > 0) o.end = Math.round(s.trimEnd * 1000);
      // its beat (only a clear, steady one): where it starts (coming in) and where it ends (going out)
      const ok = (g) => g && g[0] > 0 && g[2] >= 3.5;
      if (ok(s.tempo?.s)) { o.bs = s.tempo.s[0]; o.bb = Math.round(s.tempo.s[1] * 1000); }
      if (ok(s.tempo?.e)) { o.be = s.tempo.e[0]; o.eb = Math.round(s.tempo.e[1] * 1000); }
      return o;
    });
  }
  /** the queue changed (shuffle, play next, mix…): tell the native player without interrupting the song */
  markQueue() {
    this._qDirty = true;
    clearTimeout(this._qT);
    this._qT = setTimeout(() => {
      if (!this._qDirty || this.index < 0 || !this.fa.src) return;
      this._qDirty = false;
      this._artSent = new Set();
      AuraPlayer.setQueue({ items: this.natItems(), index: this.index, keep: true, play: !this.fa.paused, position: Math.round(this.fa.currentTime * 1000) })
        .then(() => this.natArtSoon()).catch((e) => diag.log("native queue: " + (e?.message || e)));
    }, 0);
  }
  async natLoad(autoplay, posSec = 0) {
    const s = this.current;
    const fa = this.fa;
    fa._pos = posSec; fa._at = performance.now(); fa._dur = s?.duration || NaN;
    fa.src = "native";
    fa._set(autoplay);
    try {
      if (this._qDirty) {
        this._qDirty = false;
        this._artSent = new Set();
        await AuraPlayer.setQueue({ items: this.natItems(), index: this.index, play: autoplay, position: Math.round(posSec * 1000) });
      } else await AuraPlayer.skipTo({ index: this.index, play: autoplay, position: Math.round(posSec * 1000) });
    } catch (e) { diag.log("native load: " + (e?.message || e)); this.emit("error", { song: s, why: String(e?.message || e) }); }
    this.natArtSoon();
  }
  /** the native player moved on by itself (end of song, crossfade, lock screen / headphone buttons) */
  natTrack(d) {
    if (d.index < 0) return;
    let i = d.index;
    if (this.queue[i] !== d.id) { const j = this.queue.indexOf(d.id); if (j < 0) return; i = j; }
    this.fa.apply(d);
    if (i === this.index) return;
    const dir = i > this.index || (this.index === this.queue.length - 1 && i === 0) ? 1 : -1;
    this.index = i;
    this.loadedAt = performance.now();
    this.emit("willtrack", { dir });
    this.emit("track", { dir });
    this.emit("state");
    this.natArtSoon();
  }
  natError(d) {
    const s = this.current;
    diag.log(`play failed (native): ${s?.title || "?"} · ${d?.message || ""}`);
    this.fa.apply(d);
    this.emit("error", { song: s, why: d?.message || "" });
  }
  /** the cover for the lock screen / notification: the song playing and the next one */
  /** the lock-screen covers, a moment after a song change (never during the skip animation) */
  natArtSoon() { clearTimeout(this._artT); this._artT = setTimeout(() => (window.requestIdleCallback ? requestIdleCallback(() => this.natArt(), { timeout: 1500 }) : this.natArt()), 900); }
  /** a 512 px JPEG of a cover, made off the main thread (decoded and encoded by the browser's own threads), cached */
  async artData(cov, id) {
    this._artCache ||= new Map();
    if (this._artCache.has(id)) return this._artCache.get(id);
    let b64 = "";
    const src = cov.big || cov.smallBlob;
    try {
      if (window.createImageBitmap && window.OffscreenCanvas) {
        const bmp = await createImageBitmap(src, { resizeWidth: 512, resizeHeight: 512, resizeQuality: "high" });
        const oc = new OffscreenCanvas(512, 512);
        oc.getContext("2d").drawImage(bmp, 0, 0);
        bmp.close?.();
        b64 = String(await blobToDataUrl(await oc.convertToBlob({ type: "image/jpeg", quality: 0.86 }))).split(",")[1] || "";
      }
    } catch {}
    if (!b64) b64 = String(await blobToDataUrl(cov.smallBlob || cov.big)).split(",")[1] || "";
    if (this._artCache.size > 12) this._artCache.delete(this._artCache.keys().next().value);
    this._artCache.set(id, b64);
    return b64;
  }
  async natArt() {
    this._artSent ||= new Set();
    for (const i of [this.index, this.index + 1]) {
      const s = lib.songs.get(this.queue[i]);
      if (!s || this._artSent.has(s.id)) continue;
      this._artSent.add(s.id);
      const cov = lib.cover(s.cover);
      if (!cov) continue;
      try { await AuraPlayer.setArtwork({ id: s.id, data: await this.artData(cov, s.cover) }); } catch {}
    }
  }
  /** "time" events while the native player plays (the progress bar and lyrics follow these) */
  tickTime() {
    clearInterval(this._timeT);
    if (!this.nat || this.fa.paused) return;
    this._timeT = setInterval(() => { if (!document.hidden) this.emit("time"); }, 500);
  }
  /** A song wouldn't play: log why, check its file is really there, and try once more by loading it into memory. */
  async onMediaError(a) {
    const s = this.current;
    const err = a.error;
    const why = err ? `code ${err.code}${err.message ? " · " + err.message : ""}` : "unknown";
    diag.log(`play failed: ${s?.title || "?"} (${s?.file || ""}) · ${why} · ${String(a.src).slice(0, 160)}`);
    if (!s || !a.src || a.dataset.retried === s.id) { this.emit("error", { song: s, why }); return; }
    a.dataset.retried = s.id;
    try {
      const probe = await fetch(a.src, { headers: { Range: "bytes=0-1" } });
      if (!probe.ok) { diag.log(`play failed: the file isn't there (${probe.status})`); this.emit("error", { song: s, missing: true }); return; }
      const r = await fetch(a.src, { headers: { Range: "bytes=0-" } });
      const blob = await r.blob();
      diag.log(`play retry from memory: ${Math.round(blob.size / 1024)} KB, type ${blob.type || s.type}`);
      if (this.current !== s) return;
      const url = URL.createObjectURL(blob.type ? blob : new Blob([blob], { type: s.type || "audio/mpeg" }));
      a.dataset.url = this.url = url;
      a.dataset.revoke = "1";
      a.src = url;
      await a.play();
    } catch (e) {
      diag.log(`play retry failed: ${e?.message || e}`);
      this.emit("error", { song: s, why });
    }
  }
  get crossfade() { return Math.max(0, Math.min(12, +lib.meta.settings.crossfade || 0)); }

  emit(n, detail) { this.dispatchEvent(new CustomEvent(n, { detail })); }
  get current() { return lib.songs.get(this.queue[this.index]) || null; }
  get playing() { return !this.audio.paused; }

  // ------------------------------------------------ audio graph (EQ + visualizer)
  ensureGraph() {
    if (this.nat) return;
    if (this.ctx) { if (this.ctx.state === "suspended") this.ctx.resume(); return; }
    try {
      // iPhone: treat this as music playback (keeps playing when locked and with the silent switch on)
      try { if (navigator.audioSession) navigator.audioSession.type = "playback"; } catch {}
      const ctx = (this.ctx = new (window.AudioContext || window.webkitAudioContext)());
      // a phone call, Siri or another app can pause the audio engine; start it again when the song should be playing
      const wake = () => { if (ctx.state !== "running" && !this.audio.paused) ctx.resume().catch(() => {}); };
      ctx.onstatechange = wake;
      document.addEventListener("visibilitychange", () => { if (!document.hidden) wake(); });
      for (const el of this.els) el.addEventListener("play", wake);
      const mix = ctx.createGain();                 // both players meet here, each with its own fader
      this.gains = this.els.map((el) => {
        const g = ctx.createGain();
        ctx.createMediaElementSource(el).connect(g);
        g.connect(mix);
        return g;
      });
      this.gains[1 - this.cur].gain.value = 0;
      const src = mix;
      this.filters = EQ_BANDS.map((f, i) => {
        const b = ctx.createBiquadFilter();
        b.type = i === 0 ? "lowshelf" : i === EQ_BANDS.length - 1 ? "highshelf" : "peaking";
        b.frequency.value = f;
        b.Q.value = 1.1;
        return b;
      });
      this.pre = ctx.createGain();
      this.limiter = ctx.createDynamicsCompressor();
      this.limiter.threshold.value = -1.5;
      this.limiter.knee.value = 3;
      this.limiter.ratio.value = 12;
      this.limiter.attack.value = 0.003;
      this.limiter.release.value = 0.12;
      this.fade = ctx.createGain();
      this.analyser = ctx.createAnalyser();
      this.analyser.fftSize = 2048;
      this.analyser.smoothingTimeConstant = 0.72;
      let node = src;
      for (const f of this.filters) { node.connect(f); node = f; }
      node.connect(this.pre);
      this.pre.connect(this.limiter);
      this.limiter.connect(this.fade);
      this.fade.connect(this.analyser);
      this.analyser.connect(ctx.destination);
      this.applyEq(lib.meta.settings.eq);
    } catch (e) {
      console.warn("audio graph unavailable", e);
    }
  }

  applyEq(name) {
    const g = EQ_PRESETS[name] || EQ_PRESETS.Flat;
    lib.meta.settings.eq = name;
    lib.saveMeta();
    if (this.nat) {                    // the native player has the same EQ (and keeps the same loudness)
      const w = [0.9, 1, 1, 0.9, 0.8, 0.7, 0.55, 0.4, 0.3, 0.2];
      const avg = g.reduce((a, d, i) => a + d * w[i], 0) / w.reduce((a, b) => a + b, 0);
      AuraPlayer.setEq({ gains: g, pre: Math.pow(10, (-avg * 0.8) / 20) }).catch(() => {});
      return;
    }
    if (!this.filters) return;
    g.forEach((db, i) => this.filters[i].gain.setTargetAtTime(db, this.ctx.currentTime, 0.05));
    // keep the overall loudness level with Flat (music energy is mostly in the low-mids)
    const w = [0.9, 1, 1, 0.9, 0.8, 0.7, 0.55, 0.4, 0.3, 0.2];
    const avg = g.reduce((a, d, i) => a + d * w[i], 0) / w.reduce((a, b) => a + b, 0);
    this.pre.gain.setTargetAtTime(Math.pow(10, (-avg * 0.8) / 20), this.ctx.currentTime, 0.05);
  }

  // ------------------------------------------------ queue
  playList(ids, start = 0, opts = {}) {
    ids = ids.filter((id) => lib.songs.has(id));
    if (!ids.length) return;
    this.base = [...ids];
    if (opts.shuffle !== undefined) this.shuffle = opts.shuffle;
    if (this.shuffle) {
      // Shuffle button: everything random. Tapping a song with shuffle on: that song first, the rest random.
      const keepFirst = !opts.shuffle;
      const rest = ids.filter((_, i) => !(keepFirst && i === start));
      for (let i = rest.length - 1; i > 0; i--) { const j = (Math.random() * (i + 1)) | 0; [rest[i], rest[j]] = [rest[j], rest[i]]; }
      this.queue = keepFirst ? [ids[start], ...rest] : rest;
      this.index = 0;
    } else {
      this.queue = [...ids];
      this.index = start;
    }
    this.emit("queue");
    this.load(1);
  }

  playNext(ids) {
    if (this.index < 0) return this.playList(ids);
    this.queue.splice(this.index + 1, 0, ...ids);
    this.emit("queue");
  }
  addToQueue(ids) {
    if (this.index < 0) return this.playList(ids);
    this.queue.push(...ids);
    this.emit("queue");
  }

  toggleShuffle() {
    this.shuffle = !this.shuffle;
    const cur = this.queue[this.index];
    if (this.shuffle) {
      const rest = this.queue.filter((_, i) => i !== this.index);
      for (let i = rest.length - 1; i > 0; i--) { const j = (Math.random() * (i + 1)) | 0; [rest[i], rest[j]] = [rest[j], rest[i]]; }
      this.queue = cur ? [cur, ...rest] : rest;
      this.index = cur ? 0 : -1;
    } else if (this.base.length) {
      this.queue = [...this.base];
      this.index = Math.max(0, this.queue.indexOf(cur));
    }
    this.emit("queue");
    this.emit("state");
  }
  cycleRepeat() {
    this.repeat = { off: "all", all: "one", one: "off" }[this.repeat];
    if (this.nat) AuraPlayer.setRepeat({ mode: this.repeat }).catch(() => {});
    this.emit("state");
  }

  async load(dir = 1, autoplay = true) {
    const s = this.current;
    if (!s) return;
    const token = ++this._loadToken;
    this.loadedAt = performance.now();            // no crossfade may start while a song is being swapped in
    if (this.nat) {
      // the native player is told first (its command leaves right now); the screen's own updates come after
      const go = this.natLoad(autoplay);
      this.emit("track", { dir });
      await go;
      return;
    }
    this.ensureGraph();
    this.cancelCrossfade();
    const src = await lib.fileURL(s).catch(() => null);
    if (token !== this._loadToken) { if (src?.revoke) URL.revokeObjectURL(src.url); return; }
    if (!src) { this.emit("error"); return; }
    // short fade so the old song never "chops" into the new one
    if (this.fade && this.playing) {
      const t = this.ctx.currentTime;
      this.fade.gain.cancelScheduledValues(t);
      this.fade.gain.setValueAtTime(this.fade.gain.value, t);
      this.fade.gain.linearRampToValueAtTime(0.0001, t + 0.06);
      await new Promise((r) => setTimeout(r, 70));
      if (token !== this._loadToken) return;
    }
    const el = this.audio;
    const old = el.dataset.revoke ? el.dataset.url : null;
    el.dataset.url = this.url = src.url;
    el.dataset.revoke = src.revoke ? "1" : "";
    this.els[1 - this.cur].pause();
    if (this.gains) { const t = this.ctx.currentTime; this.gains[this.cur].gain.setValueAtTime(1, t); this.gains[1 - this.cur].gain.setValueAtTime(0, t); }
    el.src = el.dataset.url;
    if (lib.meta.settings.trimSilence !== false && s.trimStart > 0) el.addEventListener("loadedmetadata", () => { if (this.current === s && el.currentTime < s.trimStart) el.currentTime = s.trimStart; }, { once: true });
    this.loadedAt = performance.now();
    if (old) setTimeout(() => URL.revokeObjectURL(old), 2000);
    this.emit("track", { dir });
    this.updateSession(true);
    if (autoplay) {
      try { await this.audio.play(); } catch (e) { console.warn(e); }
    }
    if (this.fade) {
      const t = this.ctx.currentTime;
      this.fade.gain.cancelScheduledValues(t);
      this.fade.gain.setValueAtTime(0.0001, t);
      this.fade.gain.linearRampToValueAtTime(1, t + 0.05);
    }
  }

  toggle() {
    if (this.xf && !this.audio.paused) { this.cancelCrossfade(); }
    if (!this.current) {
      const ids = lib.sortedSongs.map((s) => s.id);
      return this.playList(ids, 0);
    }
    this.ensureGraph();
    if (this.audio.paused) {
      if (!this.audio.src) return this.load(0);
      this.audio.play();
    } else this.audio.pause();
  }
  play() { if (this.audio.paused) this.toggle(); }
  pause() { this.cancelCrossfade(); this.audio.pause(); }

  /** Where skipping would go: a queue index, or -1 when it would just restart this song. */
  peek(dir) {
    if (!this.queue.length) return -1;
    let i;
    if (dir > 0) i = this.index + 1 < this.queue.length ? this.index + 1 : this.repeat === "all" ? 0 : -1;
    else i = this.audio.currentTime > 3 || this.index <= 0 ? -1 : this.index - 1;
    return i >= 0 && this.queue[i] !== this.queue[this.index] ? i : -1;
  }
  // nothing up next: play this song again from the top (no slide — it's the same song)
  restart() {
    this.seek(0);
    if (this.audio.paused) this.audio.play().catch(() => {});
    this.emit("restart");
  }
  next() {
    if (!this.queue.length) return;
    const i = this.peek(1);
    if (i < 0) return this.restart();
    this.index = i;
    this.load(1);                       // (native: the skip is sent before anything on screen starts moving)
    this.emit("willtrack", { dir: 1 });
  }
  prev() {
    const i = this.peek(-1);
    if (i < 0) return this.restart();
    this.index = i;
    this.load(-1);
    this.emit("willtrack", { dir: -1 });
  }
  jump(i) { if (i >= 0 && i < this.queue.length) { const d = i >= this.index ? 1 : -1; this.index = i; this.load(d); this.emit("willtrack", { dir: d }); } }
  seek(t) { if (isFinite(t)) { this.cancelCrossfade(); this.audio.currentTime = Math.max(0, t); this.emit("time"); this.updatePosition(); } }

  onEnded() {
    if (this.xf && this.finishCrossfade()) return;            // the outgoing song just finished: hand over now
    if (this.repeat === "one") { this.audio.currentTime = 0; this.audio.play(); return; }
    this.next();             // restarts this song when nothing is up next
  }

  removeIds(ids) {
    const cur = this.queue[this.index];
    this.queue = this.queue.filter((id) => !ids.includes(id) || id === cur);
    this.base = this.base.filter((id) => !ids.includes(id));
    this.index = this.queue.indexOf(cur);
    if (ids.includes(cur)) {
      // the playing song was removed: move on to the song after it (or the one before, or stop)
      this.audio.pause();
      this.queue.splice(this.index, 1);
      if (!this.queue.length) { this.index = -1; this.audio.removeAttribute("src"); this.audio.load(); this.emit("state"); }
      else { this.index = Math.min(this.index, this.queue.length - 1); this.emit("willtrack", { dir: 1 }); this.load(1); }
    }
    this.emit("queue");
  }

  // ------------------------------------------------ crossfade
  sendXf() {
    this._xfSent = this.crossfade; this._bmSent = lib.meta.settings.beatMatch !== false;
    AuraPlayer.setCrossfade({ secs: this._xfSent, match: this._bmSent }).catch(() => {});
  }
  checkCrossfade() {
    if (this.nat) {                    // the native player crossfades by itself; just keep its setting current
      if (this.crossfade !== this._xfSent || (lib.meta.settings.beatMatch !== false) !== this._bmSent) this.sendXf();
      return;
    }
    const secs = this.crossfade, a = this.audio;
    if (!secs || this.xf || a.paused || !isFinite(a.duration) || this.repeat === "one") return;
    // just after a song loads, the element can still report the previous song's time/length for a moment
    if (performance.now() - (this.loadedAt || 0) < 1500 || a.currentTime < 1) return;
    const s0 = this.current, end = s0?.trimEnd > 0 && lib.meta.settings.trimSilence !== false ? Math.min(s0.trimEnd, a.duration) : a.duration;
    const left = end - a.currentTime;
    if (left > secs || left < 0.25 || a.duration < secs * 2 + 1) return;
    const i = this.peek(1);
    if (i < 0) return;                 // nothing up next: the song repeats instead
    this.startCrossfade(i, left);
  }

  async startCrossfade(i, secs) {
    const s = lib.songs.get(this.queue[i]);
    const src = s && (await lib.fileURL(s).catch(() => null));
    if (!src || this.xf) { if (src?.revoke) URL.revokeObjectURL(src.url); return; }
    const from = this.cur, to = 1 - from;
    const next = this.els[to];
    const token = ++this._loadToken;
    this.xf = { from, to, token };
    const old = next.dataset.revoke ? next.dataset.url : null;
    next.dataset.url = src.url;
    next.dataset.revoke = src.revoke ? "1" : "";
    next.src = next.dataset.url;
    if (old) setTimeout(() => URL.revokeObjectURL(old), 2000);
    // equal-power curves so the loudness stays even through the blend
    const N = 64, up = new Float32Array(N), down = new Float32Array(N);
    for (let k = 0; k < N; k++) { const x = k / (N - 1); up[k] = Math.sin((x * Math.PI) / 2); down[k] = Math.cos((x * Math.PI) / 2); }
    if (this.gains) {
      const t = this.ctx.currentTime;
      for (const [g, curve] of [[this.gains[from], down], [this.gains[to], up]]) {
        g.gain.cancelScheduledValues(t);
        g.gain.setValueCurveAtTime(curve, t + 0.02, secs);
      }
    } else {                                          // no audio graph: fade with element volume
      const t0 = performance.now();
      this.xf.timer = setInterval(() => {
        const x = Math.min(1, (performance.now() - t0) / (secs * 1000));
        this.els[from].volume = Math.cos((x * Math.PI) / 2); next.volume = Math.sin((x * Math.PI) / 2);
        if (x >= 1) clearInterval(this.xf?.timer);
      }, 50);
    }
    try { await next.play(); } catch (e) { console.warn(e); }
    if (token !== this._loadToken || !this.xf) { if (next !== this.audio) next.pause(); return; }   // skipped while it was starting
    // The outgoing song stays "the" song (title, progress bar, lock screen, lyrics) until it has actually
    // finished; the incoming one is only heard, fading in underneath. The switch happens at the end.
    this.xf.index = i;
    this.xf.done = setTimeout(() => this.finishCrossfade(token), secs * 1000 + 60);
  }

  finishCrossfade(token) {
    const xf = this.xf;
    if (!xf || (token !== undefined && xf.token !== token) || xf.index === undefined) return false;
    clearTimeout(xf.done); clearInterval(xf.timer);
    this.xf = null;
    this.els[xf.from].pause();
    if (this.gains) {
      const t = this.ctx.currentTime;
      this.gains[xf.from].gain.cancelScheduledValues(0); this.gains[xf.from].gain.setValueAtTime(0, t);
      this.gains[xf.to].gain.cancelScheduledValues(0); this.gains[xf.to].gain.setValueAtTime(1, t);
    }
    this.els[xf.to].volume = 1;
    this.cur = xf.to;
    this.index = xf.index;
    this.url = this.audio.dataset.url;
    this.emit("willtrack", { dir: 1 });
    this.emit("track", { dir: 1 });
    this.emit("state");
    this.updateSession(true);
    return true;
  }

  cancelCrossfade() {
    if (this.nat) return;
    const xf = this.xf;
    if (!xf) return;
    clearTimeout(xf.done); clearInterval(xf.timer);
    this.xf = null;
    const other = this.els[1 - this.cur];
    other.pause();
    if (this.gains) {
      const t = this.ctx.currentTime;
      // cancel from 0, not "now": a fade curve that has already started is otherwise left running
      this.gains[this.cur].gain.cancelScheduledValues(0);
      this.gains[this.cur].gain.setValueAtTime(1, t);
      this.gains[1 - this.cur].gain.cancelScheduledValues(0);
      this.gains[1 - this.cur].gain.setValueAtTime(0, t);
    }
    this.audio.volume = 1; other.volume = 1;
  }

  // ------------------------------------------------ lock screen / notification controls
  setupSession() {
    const h = {
      play: () => this.play(), pause: () => this.pause(), nexttrack: () => this.next(), previoustrack: () => this.prev(),
      seekto: (d) => this.seek(d.seekTime), stop: () => this.pause(),
      seekforward: () => this.seek(this.audio.currentTime + 10), seekbackward: () => this.seek(this.audio.currentTime - 10),
    };
    for (const [action, fn] of Object.entries(h)) {
      try { MediaSession.setActionHandler({ action }, fn); } catch {}
    }
  }

  async updateSession(meta = false) {
    if (this.bgActive || this.nat) return;     // (the native player has the lock screen itself)                 // the phone's own player has the lock screen right now
    const s = this.current;
    if (!s) return;
    try {
      if (meta) {
        const cov = lib.cover(s.cover);
        const artwork = cov ? [{ src: await blobToDataUrl(cov.smallBlob || cov.big), sizes: "160x160", type: "image/jpeg" }] : [];
        await MediaSession.setMetadata({ title: s.title, artist: s.artist || "", album: s.album || "", artwork });
      }
      await MediaSession.setPlaybackState({ playbackState: this.audio.paused ? "paused" : "playing" });
      this.updatePosition();
    } catch {}
  }
  updatePosition() {
    if (this.nat) return;
    const a = this.audio;
    if (!this.current || !isFinite(a.duration) || !a.duration) return;
    try { MediaSession.setPositionState({ duration: a.duration, position: Math.min(a.currentTime, a.duration), playbackRate: 1 }); } catch {}
  }
}

function blobToDataUrl(b) {
  return new Promise((r) => { const f = new FileReader(); f.onload = () => r(f.result); f.readAsDataURL(b); });
}

export const player = new Player();
