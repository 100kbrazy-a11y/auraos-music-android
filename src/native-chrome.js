// The iPhone app's real Liquid Glass bubble, tabs and menus (drawn natively: ios-native/NativeChrome.swift).
// This side tells it what to show and hears back what was tapped or dragged. Anywhere else (Android, browsers)
// it stays off and the web versions are used.
import { Capacitor, registerPlugin } from "@capacitor/core";
import { diag, timed } from "./diag.js";

const NC = registerPlugin("NativeChrome");

// Android: the same haptics through the phone's vibration motor (iPhone uses its Taptic Engine natively)
const onAndroid = Capacitor.getPlatform() === "android";
let hapt = null, lastTick = 0;
async function androidHaptic(kind) {
  if (!onAndroid) return;
  try {
    if (kind === "tick") { const now = performance.now(); if (now - lastTick < 45) return; lastTick = now; }
    hapt ||= await import("@capacitor/haptics");
    const { Haptics, ImpactStyle, NotificationType } = hapt;
    if (kind === "success") return await Haptics.notification({ type: NotificationType.Success });
    if (kind === "error") return await Haptics.notification({ type: NotificationType.Error });
    const style = kind === "thud" ? ImpactStyle.Heavy : kind === "press" || kind === "play" ? ImpactStyle.Medium : ImpactStyle.Light;
    await Haptics.impact({ style });
  } catch {}
}
const SYMBOLS = { albums: "square.stack.fill", artists: "music.mic", songs: "music.note", playlists: "music.note.list", browse: "safari" };

export const nc = {
  on: false,
  last: {},
  async start(tabs, handlers) {
    if (Capacitor.getPlatform() !== "ios" || !Capacitor.isPluginAvailable("NativeChrome")) return false;
    try { const r = await NC.start({ tabs: tabs.map((t) => ({ id: t.id, label: t.label, symbol: SYMBOLS[t.id] || "circle" })) }); diag.log(`iOS ${r?.ios} · native glass ${r?.glass ? "on" : "off (needs iOS 26)"}`); }
    catch (e) { console.warn("native chrome unavailable", e); return false; }
    NC.addListener("event", (e) => handlers[e.type]?.(e));
    this.on = true;
    document.documentElement.classList.add("native-chrome");
    return true;
  },
  /** Only sends what changed. */
  update(o) {
    if (!this.on) return;
    const diff = {};
    for (const [k, v] of Object.entries(o)) if (this.last[k] !== v) { diff[k] = v; this.last[k] = v; }
    if (Object.keys(diff).length) NC.update(diff).catch(() => {});
  },
  /** The page's top bar, read from its web header: the native glass bar draws it, taps come back as headAction / back. */
  header(page, scrolled) {
    if (!this.on || !page) return;
    const head = page.querySelector(".head");
    if (!head) return;
    const actions = [...head.querySelectorAll(":scope > button:not(.back)")].map((b) => ({
      symbol: SF[b.querySelector("svg[data-n]")?.dataset.n] || "circle",
      label: b.querySelector("span")?.textContent || b.getAttribute("aria-label") || "",
      text: b.classList.contains("hbtn"),
      prominent: b.classList.contains("hbtn") && !b.classList.contains("tonal"),
      on: b.classList.contains("on"),
    }));
    // a page with its own Play / Shuffle (album, playlist, artist, Songs…): the top bar's appear only once those are under it
    const own = page.querySelector(".bplay");
    let textVisible = true;
    if (own) {
      const barBottom = (parseFloat(getComputedStyle(head).paddingTop) || 10) - 10 + 60;     // the glass bar: notch + 60
      textVisible = own.getBoundingClientRect().bottom < barBottom + 4;
    }
    const m = { title: head.querySelector("h1")?.textContent || "", back: !!head.querySelector(".back"), scrolled: !!scrolled, actions, textVisible };
    const key = JSON.stringify(m);
    if (key === this.lastHead) return;
    this.lastHead = key;
    NC.header(m).catch(() => {});
  },
  chromeFade(a) { if (this.on && a !== this.lastFade) { this.lastFade = a; NC.chromeFade({ a }).catch(() => {}); } },
  async bgStart(o) { try { return !!(await NC.bgStart(o))?.ok; } catch { return false; } },
  async bgStop() { try { return await NC.bgStop(); } catch { return null; } },
  haptic(kind = "tap") { if (this.on) NC.haptic({ kind }).catch(() => {}); else androidHaptic(kind); },
  npButton(o) { if (this.on) NC.npButton(o).catch(() => {}); },
  async morphBegin(reuse, rgb) {
    if (!this.on) return false;
    try { return !!(await NC.morphBegin({ reuse: !!reuse, r: rgb?.[0] || 0, g: rgb?.[1] || 0, b: rgb?.[2] || 0 }))?.ok; } catch { return false; }
  },
  morphFrame(o) { if (this.on) NC.morphFrame(o).catch(() => {}); },
  morphEnd(keep) { if (this.on) return NC.morphEnd({ keep: !!keep }).catch(() => {}); },
  accentRgb() { return cssRgb(getComputedStyle(document.documentElement).getPropertyValue("--accent").trim() || "#f7a"); },
  progressShow(title) { if (this.on) NC.progressShow({ title }).catch(() => {}); },
  progressUpdate(o) { if (this.on) NC.progressUpdate(o).catch(() => {}); },
  progressHide() { if (this.on) NC.progressHide().catch(() => {}); },
  hide() { if (!this.on) return; this.last.visible = false; NC.update({ visible: false }).catch(() => {}); },
  show(bounce) { if (!this.on) return; this.last.visible = true; NC.update({ visible: true, bounce: !!bounce }).catch(() => {}); },
  progress(pos, dur, playing) {
    if (this.on) NC.progress({ pos: pos || 0, dur: dur || 0, playing: !!playing }).catch(() => {});
  },
  tint(css) {
    if (!this.on || !css) return;
    const rgb = cssRgb(css);
    if (rgb) this.update({ r: rgb[0], g: rgb[1], b: rgb[2] });
  },
  /** Song artwork as a small JPEG for the bubble. */
  async art(key, url) {
    if (!this.on || this.last.artKey === key) return;
    let b64 = "";
    if (url) {
      try {
        const img = new Image();
        img.src = url;
        await img.decode();
        const { sharedCanvas } = await import("./tags.js");
        const c = sharedCanvas(132, "art");
        const s = Math.min(img.naturalWidth, img.naturalHeight);
        c.getContext("2d").clearRect(0, 0, 132, 132);
        c.getContext("2d").drawImage(img, (img.naturalWidth - s) / 2, (img.naturalHeight - s) / 2, s, s, 0, 0, 132, 132);
        b64 = c.toDataURL("image/jpeg", 0.86).split(",")[1] || "";
      } catch {}
    }
    this.last.artKey = key;
    NC.update({ artKey: key, art: b64 }).catch(() => {});
  },
  /** iPhone: pick music packs in the Files browser; read from disk in pieces. */
  async pickPacks() {
    const { DiskFile } = await import("./files.js");
    const r = await NC.pickAudio({ kind: "pack" });
    diag.log(`picked ${(r?.files || []).length} pack(s)`);
    return (r?.files || []).map((f) => Object.assign(new DiskFile(Capacitor.convertFileSrc(f.path), f.size || 0, f.name, 0, f.size || 0, f.path), { picked: f.rel }));
  },
  async settings(sections, open) {
    if (this.on) await NC.settings({ sections, open: !!open }).catch((e) => console.warn("settings", e));
  },
  /** iPhone: pick music in the Files browser. Resolves to File objects (copies the picker made for us). */
  async pickAudio() {
    const r = await NC.pickAudio({ kind: "audio" });
    const out = [];
    diag.log(`picked ${(r?.files || []).length} file(s)`);
    for (const f of r?.files || []) {
      let b = null;
      try {
        // ask for a byte range: for music files the app's file server only gives a proper reply to range requests
        const res = await timed(fetch(Capacitor.convertFileSrc(f.path), { headers: { Range: "bytes=0-" } }), 60000, `Opening ${f.name}`);
        if (res.ok || res.status === 0) b = await res.blob();
        diag.log(`read ${f.name}: status ${res.status}, ${b ? Math.round(b.size / 1024) + " KB" : "nothing"}`);
      } catch (e) { console.warn("couldn't fetch", f.name, e); }
      if (!b || !b.size) {
        try {                                      // second way in: the Filesystem plugin
          const { Filesystem } = await import("@capacitor/filesystem");
          const { data } = await timed(Filesystem.readFile({ path: f.path }), 90000, `Reading ${f.name}`);
          b = typeof data === "string" ? await (await fetch("data:application/octet-stream;base64," + data)).blob() : data;
          diag.log(`read ${f.name} another way: ${Math.round(b.size / 1024)} KB`);
        } catch (e) { console.warn("couldn't read", f.name, e); }
      }
      if (b && b.size) {
        const file = new File([b], f.name, { type: b.type && !/octet-stream/.test(b.type) ? b.type : "" });
        file.source = { path: f.path, offset: 0, length: -1 };         // the copy the picker made: saved natively from there
        file.picked = f.rel;
        out.push(file);
      }
    }
    return out;
  },
  // ---- system menus and dialogs (glass on iOS 26)
  async actionSheet(title, message, items) {
    const r = await NC.actionSheet({ title, message, items });
    return r?.id ?? null;
  },
  async confirm(title, message, ok, destructive) {
    return !!(await NC.confirm({ title, message, ok, destructive }))?.ok;
  },
  async prompt(title, message, value, ok) {
    return (await NC.prompt({ title, message, value, ok }))?.value ?? null;
  },
};

// the app's icon names → SF Symbols
const SF = { play: "play.fill", pause: "pause.fill", next: "forward.fill", prev: "backward.fill", shuffle: "shuffle", repeat: "repeat", repeat1: "repeat.1",
  heart: "heart", heartf: "heart.fill", lyrics: "quote.bubble", queue: "list.bullet", more: "ellipsis", search: "magnifyingglass",
  settings: "gearshape", back: "chevron.backward", down: "chevron.down", add: "plus", album: "square.stack", artist: "music.mic", note: "music.note",
  playlist: "music.note.list", share: "square.and.arrow.up", trash: "trash", check: "checkmark", close: "xmark", eq: "slider.horizontal.3",
  pack: "shippingbox", playnext: "text.insert", addq: "text.append", image: "photo", info: "info.circle", spark: "sparkles", edit: "pencil",
  globe: "globe", history: "clock.arrow.circlepath", browse: "safari", chevron: "chevron.right", logout: "rectangle.portrait.and.arrow.right",
  filter: "line.3.horizontal.decrease", folder: "folder", select: "checkmark.circle" };
export const sfSymbol = (name) => SF[name] || "circle";
const SYM = { trash: "trash", delete: "trash", remove: "minus.circle", play: "play.fill", next: "text.insert", queue: "text.append",
  playlist: "music.note.list", add: "plus", heart: "heart", heartOn: "heart.fill", edit: "pencil", album: "square.stack", artist: "music.mic",
  share: "square.and.arrow.up", info: "info.circle", search: "magnifyingglass", shuffle: "shuffle", download: "arrow.down.circle", radio: "dot.radiowaves.left.and.right" };
export const symbolFor = (icon) => SF[icon] || SYM[icon] || null;

let probe;
function cssRgb(css) {
  probe ||= Object.assign(document.createElement("canvas"), { width: 1, height: 1 }).getContext("2d", { willReadFrequently: true });
  probe.clearRect(0, 0, 1, 1);
  probe.fillStyle = "#000";
  probe.fillStyle = css;
  probe.fillRect(0, 0, 1, 1);
  const d = probe.getImageData(0, 0, 1, 1).data;
  return [d[0], d[1], d[2]];
}
