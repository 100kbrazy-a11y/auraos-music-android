import { diag } from "./diag.js";
import { lib } from "./library.js";
import { MediaSession } from "@jofr/capacitor-media-session";

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
      a.addEventListener("timeupdate", live(() => { this.emit("time"); this.checkCrossfade(); }));
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
  get audio() { return this.els[this.cur]; }
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
    this.emit("state");
  }

  async load(dir = 1, autoplay = true) {
    const s = this.current;
    if (!s) return;
    const token = ++this._loadToken;
    this.loadedAt = performance.now();            // no crossfade may start while a song is being swapped in
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
    this.emit("willtrack", { dir: 1 });
    this.load(1);
  }
  prev() {
    const i = this.peek(-1);
    if (i < 0) return this.restart();
    this.index = i;
    this.emit("willtrack", { dir: -1 });
    this.load(-1);
  }
  jump(i) { if (i >= 0 && i < this.queue.length) { const d = i >= this.index ? 1 : -1; this.index = i; this.emit("willtrack", { dir: d }); this.load(d); } }
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
  checkCrossfade() {
    const secs = this.crossfade, a = this.audio;
    if (!secs || this.xf || a.paused || !isFinite(a.duration) || this.repeat === "one") return;
    // just after a song loads, the element can still report the previous song's time/length for a moment
    if (performance.now() - (this.loadedAt || 0) < 1500 || a.currentTime < 1) return;
    const left = a.duration - a.currentTime;
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
    if (this.bgActive) return;                 // the phone's own player has the lock screen right now
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
    const a = this.audio;
    if (!this.current || !isFinite(a.duration) || !a.duration) return;
    try { MediaSession.setPositionState({ duration: a.duration, position: Math.min(a.currentTime, a.duration), playbackRate: 1 }); } catch {}
  }
}

function blobToDataUrl(b) {
  return new Promise((r) => { const f = new FileReader(); f.onload = () => r(f.result); f.readAsDataURL(b); });
}

export const player = new Player();
