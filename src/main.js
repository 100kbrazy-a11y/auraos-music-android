import "./style.css";
import { Capacitor, registerPlugin } from "@capacitor/core";
import { lib, albumKey, mimeFor } from "./library.js";
import { getJson as getJsonHttp, getBlob } from "./http.js";
import { player, EQ_PRESETS, EQ_BANDS } from "./player.js";
import { LyricsView, fetchLyrics, searchLyrics, cleanTitle } from "./lyrics.js";
import { persistStorage, usage } from "./store.js";
import { squareJpeg, coverColor, coverPalette } from "./tags.js";
import { idb } from "./store.js";
import { diag } from "./diag.js";
import { DiskFile, dropPicked, initLibraryURL, songFileURLNow } from "./files.js";
import { nc, symbolFor } from "./native-chrome.js";
import { search as searchOnline, details as resultDetails, bestSongMatch, bestAlbumMatch, same } from "./lookup.js";

const native = Capacitor.isNativePlatform();
const ios = Capacitor.getPlatform() === "ios" || /iP(hone|ad|od)/.test(navigator.userAgent);
if (ios) document.documentElement.classList.add("ios");

// ============================================================ helpers
const $ = (s, r = document) => r.querySelector(s);
const ICONS = {
  play: ["M8 5.5v13l11-6.5z", 1], pause: ["M6.5 5h4v14h-4zM13.5 5h4v14h-4z", 1], next: ["M5 6l9 6-9 6zM15.5 6H18.5v12h-3z", 1],
  prev: ["M19 6l-9 6 9 6zM5.5 6h3v12h-3z", 1],
  shuffle: ["M16 4h4v4M4 20 20 4M20 16v4h-4M14.5 14.5 20 20M4 4l5 5"], repeat: ["M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3"],
  repeat1: ["M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3M11.5 10.5l1-0.5v4.5"],
  heart: ["M12 20.5s-7.5-4.6-9.8-9.1C.7 8.2 2.6 4 6.6 4c2.3 0 3.9 1.4 5.4 3.3C13.5 5.4 15.1 4 17.4 4c4 0 5.9 4.2 4.4 7.4-2.3 4.5-9.8 9.1-9.8 9.1z"],
  heartf: ["M12 20.5s-7.5-4.6-9.8-9.1C.7 8.2 2.6 4 6.6 4c2.3 0 3.9 1.4 5.4 3.3C13.5 5.4 15.1 4 17.4 4c4 0 5.9 4.2 4.4 7.4-2.3 4.5-9.8 9.1-9.8 9.1z", 1],
  lyrics: ["M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H10l-5 4v-4H5A1.5 1.5 0 0 1 3.5 16V6A1.5 1.5 0 0 1 5 4.5zM7.5 8.5h9M7.5 11h9M7.5 13.5h6"],
  queue: ["M4 6h12M4 11h12M4 16h7M16 13.5v7l5.5-3.5z"], more: ["M12 5h.01M12 12h.01M12 19h.01"],
  search: ["M10.5 17.5a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20.5 20.5l-5-5"], settings: ["M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4"],
  back: ["M15 5l-7 7 7 7"], down: ["M6 9.5l6 6 6-6"], add: ["M12 5v14M5 12h14"],
  album: ["M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 14.2a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4z"],
  artist: ["M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4.5 21c0-4 3.4-6.5 7.5-6.5s7.5 2.5 7.5 6.5"],
  note: ["M9 18V6l11-2v12M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"],
  playlist: ["M4 6h12M4 11h12M4 16h7M19.5 8v8.5M19.5 16.5a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z"],
  share: ["M12 15V4M7.5 8.5 12 4l4.5 4.5M5 14v4a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-4"],
  trash: ["M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"], check: ["M5 12.5l4.5 4.5L19 7.5"], close: ["M6 6l12 12M18 6 6 18"],
  eq: ["M5 20v-6M5 10V4M12 20v-9M12 7V4M19 20v-4M19 12V4M2.5 14h5M9.5 7h5M16.5 16h5"],
  pack: ["M21 8l-9-5-9 5 9 5 9-5zM3 8v8l9 5 9-5V8M12 13v8"], playnext: ["M4 6h11M4 11h11M4 16h6M15 13.5l6 3.5-6 3.5z"],
  addq: ["M4 6h12M4 11h12M4 16h8M18 13v7M14.5 16.5h7"], image: ["M4 5h16v14H4zM4 16l5-5 4 4 2-2 5 5M15 9h.01"],
  info: ["M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5M12 8h.01"], spark: ["M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z"],
  edit: ["M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4"], globe: ["M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c2.5 2.5 3.5 5.5 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.5-3.5-9s1-6.5 3.5-9z"],
  history: ["M12 21a9 9 0 1 0-8.5-6M3 9.5V15h5.5M12 7.5V12l3 2"], browse: ["M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM15.5 8.5l-2 5-5 2 2-5z"],
  chevron: ["M7 10l5 5 5-5"], logout: ["M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 16l-4-4 4-4M6 12h10"],
  filter: ["M4 6h16M7 12h10M10 18h4"],
  folder: ["M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"], select: ["M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 17l2 2 4-4"],
};
function icon(name, cls = "") {
  const [d, fill] = ICONS[name] || ICONS.note;
  const w = document.createElement("span");
  w.innerHTML = `<svg data-n="${name}" class="i ${fill ? "fill" : ""} ${cls}" viewBox="0 0 24 24"${name === "more" ? ' style="stroke-width:3.2"' : ""}><path d="${d}"/></svg>`;
  return w.firstChild;
}
function h(tag, a = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(a || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "html") el.innerHTML = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const c of kids.flat()) if (c !== null && c !== undefined && c !== false) el.append(c instanceof Node ? c : document.createTextNode(c));
  return el;
}
const ib = (name, on, cls = "", title = "") => h("button", { class: "ib " + cls, onclick: on, "aria-label": title || name }, icon(name));
const plural = (n, w) => `${n} ${w}${n === 1 ? "" : "s"}`;
const fmtTime = (s) => { s = Math.max(0, Math.floor(s || 0)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
const fmtSize = (n) => { for (const u of ["B", "KB", "MB", "GB"]) { if (n < 1024 || u === "GB") return `${n < 10 && u !== "B" ? n.toFixed(1) : Math.round(n)} ${u}`; n /= 1024; } };
const nameColor = (s) => { let x = 0; for (const c of String(s)) x = (x * 31 + c.charCodeAt(0)) % 360; return `hsl(${x} 45% 45%)`; };
const motion = () => lib.meta.settings.motion !== false;

function art(coverId, { small = true, label = "", round = false, cls = "art" } = {}) {
  const c = lib.cover(coverId);
  const el = h("div", { class: cls + (round ? " round" : "") });
  if (c) el.append(h("img", { src: small ? c.small : c.url, loading: "lazy", decoding: "async", alt: "" }));
  else { const p = h("div", { class: "ph", style: { "--c": nameColor(label) } }, icon("note")); el.append(p); }
  return el;
}

function mosaic(songs, label) {
  const ids = [...new Set(songs.map((s) => s.cover).filter(Boolean))].slice(0, 4);
  if (ids.length < 4) return art(ids[0], { small: false, label, cls: "bigart" });
  const el = h("div", { class: "bigart" }, h("div", { class: "mosaic" }, ids.map((id) => art(id, { small: false }))));
  return el;
}

/** a tick whenever a drag crosses the point where letting go would do something (and back) */
function edge() {
  let on = false;
  const f = (now) => { if (now !== on) { on = now; nc.haptic(now ? "tick" : "soft"); } };
  f.reset = () => { on = false; };
  return f;
}

// ---- double tap to like (anywhere a song is): a heart bursts where you tapped
function likeSong(s, x, y) {
  if (!s) return;
  const on = !lib.isFav(s.id);
  if (on) lib.toggleFav([s.id], true);
  nc.haptic(on ? "success" : "tap");
  heartBurst(x, y, on);
  updateNpFav?.();
  if (on) snack(`Liked “${s.title}”`);
  pageDirty = true;
}
function heartBurst(x, y, on = true) {
  if (x == null) { x = innerWidth / 2; y = innerHeight / 2; }
  const el = h("div", { class: "heartburst" + (on ? "" : " already") }, icon("heartf"));
  el.style.left = `${x}px`; el.style.top = `${y}px`;
  document.body.append(el);
  setTimeout(() => el.remove(), 900);
}
/** tap / double tap on one element: a single tap waits a moment only if `waitForDouble` (else it runs at once) */
// (remembered per song, not per element: the first tap can redraw the row it landed on)
let lastTap = { key: "", t: 0, x: 0, y: 0 };
function doubleTap(el, onDouble, key) {
  el.addEventListener("pointerup", (e) => {
    const now = performance.now();
    if (lastTap.key === key && now - lastTap.t < 320 && Math.hypot(e.clientX - lastTap.x, e.clientY - lastTap.y) < 30) {
      lastTap = { key: "", t: 0, x: 0, y: 0 }; dblAt.set(key, now); onDouble(e.clientX, e.clientY); return;
    }
    lastTap = { key, t: now, x: e.clientX, y: e.clientY };
  });
}
const dblAt = new Map();
const justDoubled = (key) => performance.now() - (dblAt.get(key) || 0) < 120;

// press: tap / long-press (and right-click on desktop)
function press(el, tap, long, drag) {
  let timer = 0, sx = 0, sy = 0, fired = false, dragged = false, touching = false;
  const fire = (e) => { if (fired) return; fired = true; clearTimeout(timer); el.classList.remove("pressed"); nc.haptic("press"); long?.(e); };
  el.addEventListener("pointerdown", (e) => {
    if (e.button > 0) return;
    sx = e.clientX; sy = e.clientY; fired = false; dragged = false; touching = e.pointerType !== "mouse";
    el.classList.add("pressed");
    if (long) timer = setTimeout(() => fire(e), 460);
  });
  const cancel = () => { clearTimeout(timer); el.classList.remove("pressed"); touching = false; };
  el.addEventListener("pointermove", (e) => {
    const d = Math.hypot(e.clientX - sx, e.clientY - sy);
    // held, then the finger moves: pick it up and drag it (to another app, or onto one of the targets that appear)
    if (fired && drag && !dragged && d > 12 && canDragOut()) { dragged = true; closeSheet(true); startDragOut(drag(), e); return; }
    if (!fired && d > 10) cancel();
  });
  // once it's been held, moving the finger must not scroll the list (that would cancel the touch before the drag starts)
  if (drag) el.addEventListener("touchmove", (e) => { if (fired) e.preventDefault(); }, { passive: false });
  el.addEventListener("pointerup", cancel);
  el.addEventListener("pointercancel", cancel);
  el.addEventListener("pointerleave", () => { if (!touching) clearTimeout(timer); });
  el.addEventListener("click", (e) => { if (fired) { e.preventDefault(); e.stopPropagation(); return; } tap?.(e); });
  if (long) el.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    if (touching) { fire(e); return; }          // the phone's own long-press: the same hold (keeps it "held" for dragging)
    clearTimeout(timer); long(e); fired = false;  // a right-click
  });
}

// ============================================================ snackbar, dialogs, sheets
let snackT;
function snack(msg, action, fn, ms = 4200) {
  const m = String(msg);
  if (/^(couldn'?t|can'?t|failed|error|no |nothing)|isn'?t|wouldn'?t|refused|missing/i.test(m)) nc.haptic("error");
  else if (/^(added|saved|sent|made|created|imported|copied|signed in|found|renamed|updated|restored|downloaded)/i.test(m)) nc.haptic("success");
  const box = $("#snack");
  box.innerHTML = "";
  const s = h("div", { class: "s" }, h("span", {}, msg), action && h("button", { onclick: () => { fn(); s.remove(); } }, action));
  box.append(s);
  clearTimeout(snackT);
  snackT = setTimeout(() => { s.classList.add("out"); setTimeout(() => s.remove(), 300); }, ms);
}

let sheetClose = null;
function openSheet(content, { dialog = false, onClose } = {}) {
  closeSheet(true);
  nc.haptic(dialog ? "soft" : "tap");
  const sh = $("#sheet"), scrim = $("#scrim");
  sh.innerHTML = "";
  sh.className = dialog ? "dialog" : "";
  if (!dialog) sh.append(h("div", { class: "grab" }));
  sh.append(content);
  sh.hidden = scrim.hidden = false;
  nc.update({ covered: true });            // the native bubble / top bar step aside while a sheet is open
  npPlayButton();
  sheetClose = onClose || null;
  scrim.onclick = () => closeSheet();
}
function closeSheet(silent) {
  const sh = $("#sheet");
  if (sh.hidden) return false;
  sh.hidden = $("#scrim").hidden = true;
  queueMicrotask(() => { if ($("#sheet").hidden) { nc.update({ covered: !!sel }); npPlayButton(); } });   // (not when another sheet opens straight away)
  sh.innerHTML = "";
  const f = sheetClose; sheetClose = null;
  if (!silent) f?.();
  return true;
}
function actionSheet(head, actions) {
  if (nc.on) {                     // iPhone: the system's own (glass) menu
    const list = actions.filter(Boolean);
    const txt = (el, sel) => (el && el.querySelector ? el.querySelector(sel)?.textContent : null) || null;
    const title = txt(head, ".t") || head?.textContent || null, message = txt(head, ".s");
    nc.actionSheet(title, message, list.map((a, i) => ({ id: String(i), label: String(a.label?.textContent ?? a.label ?? ""), destructive: !!a.danger, symbol: symbolFor(a.icon) || undefined })))
      .then((id) => { if (id != null) list[+id]?.run(); });
    return;
  }
  const box = h("div");
  if (head) box.append(head);
  for (const a of actions) {
    if (!a) continue;
    box.append(h("button", { class: "act" + (a.danger ? " danger" : ""), onclick: () => { closeSheet(true); a.run(); } }, icon(a.icon), a.label));
  }
  openSheet(box);
}
function songHead(s) {
  return h("div", { class: "sh-head" }, art(s.cover, { label: s.album }), h("div", { class: "meta" }, h("div", { class: "t" }, s.title), h("div", { class: "s" }, [s.artist, s.album].filter(Boolean).join(" · "))));
}
function prompt(title, message, value = "", suffix = "", ok = "OK") {
  if (nc.on) return nc.prompt(title, [message, suffix].filter(Boolean).join("\n") || null, value, ok);
  return new Promise((res) => {
    const inp = h("input", { class: "field", value, enterkeyhint: "done" });
    const done = (v) => { closeSheet(true); res(v); };
    inp.addEventListener("keydown", (e) => { if (e.key === "Enter") done(inp.value.trim() || null); });
    openSheet(h("div", {}, h("h3", {}, title), message && h("p", {}, message), inp, suffix && h("div", { class: "suffix" }, suffix),
      h("div", { class: "btns" }, h("button", { class: "btn text", onclick: () => done(null) }, "Cancel"),
        h("button", { class: "btn", onclick: () => done(inp.value.trim() || null) }, ok))), { dialog: true, onClose: () => res(null) });
    setTimeout(() => { inp.focus(); inp.select(); }, 60);
  });
}
function confirmBox(title, message, ok = "Remove") {
  if (nc.on) return nc.confirm(title, message, ok, /remove|delete|clear|erase/i.test(ok));
  return new Promise((res) => {
    const done = (v) => { closeSheet(true); res(v); };
    openSheet(h("div", {}, h("h3", {}, title), h("p", {}, message), h("div", { class: "btns" },
      h("button", { class: "btn text", onclick: () => done(false) }, "Cancel"), h("button", { class: "btn", onclick: () => done(true) }, ok))),
    { dialog: true, onClose: () => res(false) });
  });
}
function progressBox(title) {
  if (nc.on) {                     // iPhone: a Liquid Glass card that shows what it's doing, step by step
    diag.step = ""; diag.log("progress: " + title);
    nc.progressShow(title);
    let f = 0, t = "", sent = "";
    const push = () => { const o = { fraction: f, text: t, step: diag.step, log: diag.recent(3) }; const k = JSON.stringify(o); if (k !== sent) { sent = k; nc.progressUpdate(o); } };
    const tick = setInterval(push, 250);
    return { set(nf, nt, sub) { f = nf; if (nt) t = nt; if (sub?.text) diag.step = sub.text; push(); }, close() { clearInterval(tick); diag.step = ""; nc.progressHide(); } };
  }
  // two bars: the whole job, and the thing it's doing right now (one song's download, a step…)
  const bar = h("b"), txt = h("p", {}, "Starting…"), pct = h("span", { class: "pct" }, "0%");
  const bar2 = h("b"), txt2 = h("div", { class: "suffix" }), sub = h("div", { class: "subtask" }, h("div", { class: "progress thin" }, bar2), txt2);
  openSheet(h("div", { class: "progbox" }, h("h3", {}, title, pct), h("div", { class: "progress" }, bar), txt, sub), { dialog: true });
  $("#scrim").onclick = null;
  diag.step = ""; diag.log("progress: " + title);
  let subSet = 0;
  // without its own sub-task, the second line shows what the app is doing right now
  const tick = setInterval(() => { if (performance.now() - subSet > 1500 && txt2.textContent !== diag.step) { txt2.textContent = diag.step; sub.classList.toggle("nobar", true); } }, 250);
  return {
    set(f, t, s2) {
      const p = Math.max(0, Math.min(1, f));
      bar.style.width = (p * 100).toFixed(1) + "%"; pct.textContent = Math.floor(p * 100) + "%";
      if (t) txt.textContent = t;
      if (s2) {
        subSet = performance.now();
        sub.classList.toggle("nobar", s2.f == null);
        if (s2.f != null) bar2.style.width = (Math.max(0, Math.min(1, s2.f)) * 100).toFixed(1) + "%";
        if (s2.text) txt2.textContent = s2.text;
      }
    },
    close() { clearInterval(tick); diag.step = ""; closeSheet(true); },
  };
}
function showDiagnostics() {
  const pre = h("pre", { class: "diag" }, diag.text() || "Nothing logged yet.");
  openSheet(h("div", {}, h("h3", {}, "Diagnostics"), h("p", {}, "What the app did recently. Copy it and send it along when something goes wrong."), pre,
    h("div", { class: "btns" },
      h("button", { class: "btn text", onclick: () => { diag.clear(); pre.textContent = "Cleared."; } }, "Clear"),
      h("button", { class: "btn text", onclick: async () => { try { const { Share } = await import("@capacitor/share"); await Share.share({ title: "AuraOS diagnostics", text: "AuraOS diagnostics (newest first)\n" + diag.text().split("\n").reverse().join("\n") }); } catch {} } }, "Share"),
      h("button", { class: "btn", onclick: async () => { try { await navigator.clipboard.writeText("AuraOS diagnostics (newest first)\n" + diag.text().split("\n").reverse().join("\n")); snack("Copied"); } catch { snack("Couldn't copy — select the text instead"); } } }, "Copy"))),
  { dialog: true });
  setTimeout(() => { pre.scrollTop = pre.scrollHeight; }, 30);
}

// ============================================================ navigation
const TABS = [
  { id: "albums", label: "Albums", icon: "album" }, { id: "artists", label: "Artists", icon: "artist" },
  { id: "songs", label: "Songs", icon: "note" }, { id: "playlists", label: "Playlists", icon: "playlist" },
];
let stack = [{ type: "albums" }];
let pageEl = null;
let sel = null;            // {kind: 'songs'|'albums', ids: Set}
const favGenres = new Set();

function buildTabs() {
  const nav = $("#tabs");
  nav.innerHTML = "";
  nav.append(h("div", { class: "brand" }, h("img", { src: "./icon-192.png", alt: "" }), "AuraOS Music"));
  nav.append(h("div", { class: "ind" }));
  if (!$("#dock .tviz")) $("#dock").prepend(h("canvas", { class: "tviz" }));
  for (const t of TABS) {
    nav.append(h("button", { "data-tab": t.id, onclick: () => goTab(t.id) }, h("span", { class: "pill" }, icon(t.icon)), h("span", { class: "lbl" }, t.label)));
  }
  setupTabDrag();
}
function markTab() {
  miniTuck.page(stack.at(-1).type === "settings");
  document.body.classList.toggle("in-settings", stack.at(-1).type === "settings");   // portrait: the bottom bar steps away      // sideways: tucked in Settings; elsewhere the Scroll Timer restarts
  for (const b of $("#tabs").querySelectorAll("button[data-tab]")) b.classList.toggle("on", b.dataset.tab === stack[0].type);
  sideLists();
  moveInd(stack[0].type);
  nc.update({ tab: stack[0].type });
}

// ---- sideways: the media bar wraps down to its album cover and tucks in under the tab pill (Settings, and while you scroll)
let quietScrollUntil = 0;
const miniTuck = (() => {
  let tucked = false, inSettings = false, timer = 0;
  const landscape = () => matchMedia("(orientation: landscape) and (min-width: 640px)").matches;
  const delay = () => { const v = +lib.meta.settings.lyricReturn; return (isFinite(v) && v > 0 ? v : 1.5) * 1000; };   // the Scroll Timers setting
  const pinned = () => !!lib.meta.settings.tuckPinned;
  const TUCKED_BTN = 51, COVER = 68, TUCKED_H = 86;     // tucked bubble: the cover on top, a strip of 5 bars under it
  function place() {
    // where the cover ends up: centred under the tab pill *as it will be* once it has shrunk, so the shrink
    // and the slide can run at the same time
    const m = $("#mini"), t = $("#tabs");
    if (!m || m.hidden) return;
    const prev = m.style.transform;
    m.style.transition = "none"; m.style.transform = "none";
    const mr = m.getBoundingClientRect(), tr = t.getBoundingClientRect();
    m.style.transform = prev; void m.offsetWidth; m.style.transition = "";
    const cs = getComputedStyle(t), n = t.querySelectorAll(":scope > button[data-tab]").length;
    const h = n * TUCKED_BTN + (n - 1) * (parseFloat(cs.rowGap) || 0) + parseFloat(cs.paddingTop) + parseFloat(cs.paddingBottom);
    const bottom = innerHeight / 2 - 40 + h / 2;
    const dx = tr.left + (tr.width - COVER) / 2 - mr.left;
    const finalTop = mr.bottom - TUCKED_H;                 // it grows taller (upwards) as it tucks
    const dy = Math.min(bottom + 8, innerHeight - 6 - TUCKED_H) - finalTop;
    m.style.transform = `translate3d(${dx}px, ${dy}px, 0)`;
  }
  function tuck() {
    document.body.classList.toggle("mini-pinned", pinned());
    if (tucked || !landscape() || $("#mini").hidden) return;
    tucked = true;
    document.body.classList.add("mini-tucked");      // wrap down to the cover + tabs shrink…
    place();                                         // …while it slides under them, all at once
    report();
  }
  function untuck() {
    if (!tucked) return;
    tucked = false;
    document.body.classList.remove("mini-tucked");   // unwrap and slide back together
    $("#mini").style.transform = "";
    report();
  }
  const later = () => { if (!pinned() && !inSettings) untuck(); };
  const report = () => nc.update({ tucked, pinned: pinned() });
  function sync() {
    if (!landscape()) { if (tucked) { tucked = false; document.body.classList.remove("mini-tucked"); $("#mini").style.transform = ""; report(); } return; }
    if (pinned() || inSettings) { if (tucked) setTimeout(place, 60); else tuck(); }
    else if (tucked) setTimeout(place, 60);
  }
  addEventListener("resize", sync);
  return {
    // every page change: Settings keeps it tucked; anywhere else a tucked bar waits the full Scroll Timer again
    page(isSettings) {
      inSettings = isSettings;
      clearTimeout(timer);
      if (isSettings || pinned()) return tuck();
      if (tucked) timer = setTimeout(later, delay());
    },
    scrolled() {
      if (!landscape()) return;
      tuck();
      clearTimeout(timer);
      if (!inSettings && !pinned()) timer = setTimeout(later, delay());    // stopped scrolling: back after the Scroll Timer
    },
    pin(on) {
      lib.meta.settings.tuckPinned = on; lib.saveMeta();
      document.body.classList.toggle("mini-pinned", on);
      clearTimeout(timer);
      report();
      if (on) tuck(); else if (!inSettings) untuck();
    },
    sync,
    get tucked() { return tucked; },
  };
})();

// ---- iOS-style tab bar: a floating pill whose highlight you can drag; it snaps, stretches and wobbles into place
const ind = { x: 0, v: 0, w: 0, target: 0, raf: 0, drag: false, last: 0, ready: false };
const pillMode = () => true;
const vertical = () => matchMedia("(orientation: landscape) and (min-width: 640px)").matches;   // sideways: a vertical pill on the right
function tabGeom() {
  const nav = $("#tabs"), nr = nav.getBoundingClientRect(), v = vertical();
  return [...nav.querySelectorAll(":scope > button[data-tab]")].map((b) => { const r = b.getBoundingClientRect();
    return { id: b.dataset.tab, x: v ? r.top - nr.top : r.left - nr.left, w: v ? r.height : r.width, el: b }; });
}
function renderInd() {
  const el = $("#tabs .ind");
  if (!el) return;
  const v = vertical();
  const stretch = Math.min(0.28, Math.abs(ind.v) / 2600);        // stretches with speed, like liquid
  const lift = ind.drag ? 1.08 : 1;
  const key = (v ? "h" : "w") + ind.w;
  if (el._w !== key) { el._w = key; el.style.width = ind.w + "px"; el.style.height = v ? ind.w + "px" : ""; }   // sideways: a circle
  const along = (1 + stretch) * lift, across = (1 - stretch * 0.5) * lift;
  el.style.transform = v ? `translate3d(0, ${ind.x}px, 0) scale(${across}, ${along})` : `translate3d(${ind.x}px, 0, 0) scale(${along}, ${across})`;
  el.classList.toggle("drag", ind.drag);
}
function moveInd(id, instant) {
  if (!pillMode()) return;
  const g = tabGeom().find((t) => t.id === id);
  if (!g || !g.w) return;
  ind.target = g.x + 4; ind.w = g.w - 8;
  if (instant || !ind.ready || !motion()) { ind.x = ind.target; ind.v = 0; ind.ready = true; renderInd(); return; }
  springInd();
}
function springInd() {
  cancelAnimationFrame(ind.raf);
  ind.last = performance.now();
  const step = (ts) => {
    const dt = Math.min(0.032, (ts - ind.last) / 1000); ind.last = ts;
    if (ind.drag) return;
    const k = 560, c = 22;                              // under-damped: a little overshoot and wobble
    ind.v += (-k * (ind.x - ind.target) - c * ind.v) * dt;
    ind.x += ind.v * dt;
    if (Math.abs(ind.x - ind.target) < 0.25 && Math.abs(ind.v) < 4) { ind.x = ind.target; ind.v = 0; renderInd(); return; }
    renderInd();
    ind.raf = requestAnimationFrame(step);
  };
  ind.raf = requestAnimationFrame(step);
}
function setupTabDrag() {
  const nav = $("#tabs");
  let sx = 0, startX = 0, down = false, lastX = 0, lastT = 0, moved = false;
  const toBar = (pos) => pos - (vertical() ? nav.getBoundingClientRect().top : nav.getBoundingClientRect().left) - ind.w / 2;
  nav.addEventListener("pointerdown", (e) => {
    if (!pillMode() || !e.target.closest("button[data-tab]")) return;
    const p0 = vertical() ? e.clientY : e.clientX;
    down = true; moved = false; sx = p0; startX = ind.x; lastX = p0; lastT = performance.now();
    // the bubble goes to where your finger is, not from where it was
    const g = tabGeom();
    ind.target = Math.max(g[0].x + 4, Math.min(g[g.length - 1].x + 4, toBar(p0)));
    if (motion()) springInd();
  });
  nav.addEventListener("pointermove", (e) => {
    if (!down) return;
    const pos = vertical() ? e.clientY : e.clientX;
    const dx = pos - sx;
    if (!moved && Math.abs(dx) < 6) return;
    if (!moved) { moved = true; ind.drag = true; cancelAnimationFrame(ind.raf); nav.setPointerCapture(e.pointerId); }
    const g = tabGeom(), min = g[0].x + 4, max = g[g.length - 1].x + 4;
    let x = toBar(pos);                                   // centred under your finger
    if (x < min) x = min - (min - x) * 0.3;               // rubber-band past the ends
    if (x > max) x = max + (x - max) * 0.3;
    const now = performance.now();
    ind.v = ((pos - lastX) / Math.max(1, now - lastT)) * 1000;
    lastX = pos; lastT = now;
    ind.x = x;
    const centre = x + ind.w / 2;
    let over = null;
    for (const t of g) { const on = centre >= t.x && centre < t.x + t.w; t.el.classList.toggle("hover", on); if (on) over = t.id; }
    if (over && over !== nav.dataset.over) { if (nav.dataset.over) nc.haptic("tick"); nav.dataset.over = over; }
    renderInd();
  });
  const up = () => {
    if (!down) return;
    down = false;
    if (!moved) return;
    ind.drag = false;
    const g = tabGeom(), centre = ind.x + ind.w / 2;
    g.forEach((t) => t.el.classList.remove("hover"));
    const near = g.reduce((a, b) => (Math.abs(b.x + b.w / 2 - centre) < Math.abs(a.x + a.w / 2 - centre) ? b : a));
    nav.dataset.dragged = "1";
    delete nav.dataset.over;
    nc.haptic("soft");
    setTimeout(() => delete nav.dataset.dragged, 50);
    if (near.id !== stack[0].type) goTab(near.id); else moveInd(near.id);
    springInd();
  };
  nav.addEventListener("pointerup", up);
  nav.addEventListener("pointercancel", up);
  nav.addEventListener("click", (e) => { if (nav.dataset.dragged) { e.stopPropagation(); e.preventDefault(); } }, true);
  addEventListener("resize", () => {
    const el = $("#tabs .ind"); if (el) el._w = null;
    moveInd(stack[0].type, true);
    clearTimeout(ind.settle);
    ind.settle = setTimeout(() => { const e2 = $("#tabs .ind"); if (e2) e2._w = null; moveInd(stack[0].type, true); }, 450);   // once rotation layout settles
  });
  // whenever the tab bar's size actually changes (rotation, tucking, the bubble resizing), re-seat the highlight
  new ResizeObserver(() => { if (ind.drag) return; const e2 = $("#tabs .ind"); if (e2) e2._w = null; moveInd(stack[0].type, !ind.raf || true); }).observe(nav);
  // the tab buttons shrink/grow when the media bar tucks in: keep the highlight on its tab
  nav.addEventListener("transitionend", (e) => { if (e.propertyName === "height" && e.target.matches("button[data-tab]")) { const el = $("#tabs .ind"); if (el) el._w = null; moveInd(stack[0].type); } });
}
// sideways: playlists listed under the sidebar, like the desktop app
function sideLists() {
  const nav = $("#tabs");
  nav.querySelector(".side")?.remove();
  const cur = stack.at(-1);
  const box = h("div", { class: "side" }, h("div", { class: "side-t" }, "Playlists"));
  const item = (label, ic, on, go, cls = "") => {
    const b = h("button", { class: "side-i " + cls + (on ? " on" : ""), onclick: go }, icon(ic), h("span", {}, label));
    return b;
  };
  box.append(item("Favorite Songs", "heartf", cur.type === "favorites", () => (stack.length = 1, push({ type: "favorites" })), "fav"));
  for (const name of Object.keys(lib.meta.playlists)) box.append(item(name, "playlist", cur.type === "playlist" && cur.name === name, () => (stack.length = 1, push({ type: "playlist", name }))));
  box.append(item("New Playlist", "add", false, () => newPlaylist(), "new"));
  nav.append(box);
}

function show(dir) {
  const old = pageEl;
  const el = render(stack[stack.length - 1]);
  $("#pages").append(el);
  pageEl = el;
  markTab();
  nc.header(el, el.classList.contains("scrolled"));
  if (old) {
    if (motion() && dir) {
      const [ax, ay] = { left: [1, 0], right: [-1, 0], up: [0, 1], down: [0, -1] }[dir];
      const o = { duration: 380, easing: "cubic-bezier(.2,.8,.2,1)" };
      const startT = old.style.transform || "none";       // continue from where a swipe left it
      el.animate([{ transform: `translate(${ax * 40}%, ${ay * 14}%)`, opacity: 0 }, { transform: "none", opacity: 1 }], o);
      old.style.pointerEvents = "none";
      old.animate([{ transform: startT, opacity: 1 }, { transform: `translate(${-ax * 30}%, ${-ay * 8}%)`, opacity: 0 }], o).onfinish = () => old.remove();
    } else old.remove();
  }
}

// swipe left / right anywhere on a page to change tabs (or swipe right to go back from an album, artist…)
function setupSwipe() {
  const pages = $("#pages");
  let sx = 0, sy = 0, dx = 0, active = false, decided = false, horiz = false;
  const target = (d) => {
    if (stack.length > 1) return d > 0 ? "back" : null;
    const i = TABS.findIndex((t) => t.id === stack[0].type);
    const j = i + (d < 0 ? 1 : -1);
    return j >= 0 && j < TABS.length ? TABS[j].id : null;
  };
  pages.addEventListener("touchstart", (e) => {
    active = !sel && e.touches.length === 1 && !e.target.closest(".hscroll, .chips, input, .eqviz, #mini, .capsule, [data-dragging]");
    if (!active) return;
    sx = e.touches[0].clientX; sy = e.touches[0].clientY; dx = 0; decided = horiz = false; swipeEdge.reset();
  }, { passive: true });
  const swipeEdge = edge();
  pages.addEventListener("touchmove", (e) => {
    if (!active) return;
    const t = e.touches[0];
    dx = t.clientX - sx;
    const dy = t.clientY - sy;
    if (!decided && (Math.abs(dx) > 14 || Math.abs(dy) > 14)) { decided = true; horiz = Math.abs(dx) > Math.abs(dy) * 1.4; }
    if (!horiz || !pageEl) return;
    const ok = !!target(dx);
    swipeEdge(ok && Math.abs(dx) > 70);
    pageEl.style.transition = "none";
    pageEl.style.overflowY = "hidden";
    pageEl.style.transform = `translateX(${dx * (ok ? 0.6 : 0.12)}px)`;
    pageEl.style.opacity = String(1 - Math.min(0.35, Math.abs(dx) / 1000));
  }, { passive: true });
  const end = () => {
    if (!active) return;
    active = false;
    if (!horiz || !pageEl) return;
    const el = pageEl, to = target(dx);
    el.style.overflowY = "";
    if (to && Math.abs(dx) > 70) {
      el.style.opacity = "";
      el.style.transition = "";
      nc.haptic("tick");
      if (to === "back") pop(); else goTab(to);
    } else {
      el.style.transition = "transform .3s cubic-bezier(.34,1.4,.64,1), opacity .3s";
      el.style.transform = ""; el.style.opacity = "";
      setTimeout(() => (el.style.transition = ""), 320);
    }
  };
  pages.addEventListener("touchend", end);
  pages.addEventListener("touchcancel", end);
}
function goTab(id) {
  if (sel) endSelect();
  if (stack.length === 1 && stack[0].type === id) { pageEl?.scrollTo({ top: 0, behavior: "smooth" }); return; }
  const from = TABS.findIndex((t) => t.id === stack[0].type), to = TABS.findIndex((t) => t.id === id);
  stack = [{ type: id }];
  show(to >= from ? "left" : "right");
}
function push(page) {
  if (page.type === "settings" && nc.on) return openNativeSettings();     // iPhone: a real iOS Settings screen
  if (sel) endSelect(); stack.push(page); show("left");
}
function pop() { if (stack.length > 1) { stack.pop(); show("right"); return true; } return false; }
function refresh() {
  if (!pageEl) return;
  sideLists();
  const top = pageEl.scrollTop;
  const el = render(stack[stack.length - 1]);
  pageEl.replaceWith(el);
  pageEl = el;
  quietScrollUntil = performance.now() + 150;     // restoring the scroll position isn't you scrolling
  el.scrollTop = top;
  el._wantTop = top;
  setTimeout(() => { if (el._wantTop === top) el._wantTop = 0; }, 3000);
  if (top > 8) el.classList.add("scrolled");
}

function pageShell(title, { back = stack.length > 1, actions = [] } = {}) {
  const page = h("div", { class: "page" });
  let acts = actions.filter(Boolean);
  if (!nc.on) {                    // (iPhone: the native glass top bar does this itself)
    const icons = acts.filter((a) => a.classList?.contains("ib") && !a.classList.contains("back"));
    if (icons.length) { const cap = capsule(icons); const at = acts.indexOf(icons[0]); acts = acts.filter((a) => !icons.includes(a)); acts.splice(at, 0, cap); }
  }
  const head = h("div", { class: "head" }, back && h("button", { class: "ib back", onclick: pop }, icon("back")), h("h1", {}, title), ...acts);
  page.append(head);
  if (nc.on) {                    // iPhone: the native glass top bar mirrors this header
    let t = 0;
    new MutationObserver(() => { clearTimeout(t); t = setTimeout(() => { if (page === pageEl) nc.header(page, page.classList.contains("scrolled")); }, 30); })
      .observe(head, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ["class"] });
  }
  let lastTop = 0;
  page.addEventListener("scroll", () => {
    const was = page.classList.contains("scrolled");
    page.classList.toggle("scrolled", page.scrollTop > 8);
    if (nc.on && page === pageEl && (was !== page.scrollTop > 8 || page.querySelector(".bplay"))) nc.header(page, page.scrollTop > 8);   // (sends only when something changed)
    if (Math.abs(page.scrollTop - lastTop) > 4 && performance.now() > quietScrollUntil) miniTuck.scrolled();
    lastTop = page.scrollTop;
  }, { passive: true });
  return page;
}
// ---------------------------------------------------------------- filters (Albums, Artists, Songs, Favorites)
const filt = { genres: new Set(), artists: new Set(), decades: new Set(), length: new Set(), fav: false, recent: false };
const filtCount = () => filt.genres.size + filt.artists.size + filt.decades.size + filt.length.size + (filt.fav ? 1 : 0) + (filt.recent ? 1 : 0);
const artistNames = (s) => [...(s.artist || "").split(/\s*,\s*/), s.albumartist || ""].map((x) => x.trim()).filter(Boolean);
const decadeOf = (s) => { const y = parseInt(s.year, 10); return y ? Math.floor(y / 10) * 10 : 0; };
const lengthOf = (s) => (!s.duration ? "" : s.duration < 180 ? "short" : s.duration <= 300 ? "mid" : "long");
const LENGTHS = { short: "Under 3 min", mid: "3 – 5 min", long: "Over 5 min" };
function passes(s) {
  if (filt.genres.size && !genresOf(s).some((g) => filt.genres.has(g.toLowerCase()))) return false;
  if (filt.artists.size && !artistNames(s).some((a) => filt.artists.has(a.toLowerCase()))) return false;
  if (filt.decades.size && !filt.decades.has(decadeOf(s))) return false;
  if (filt.length.size && !filt.length.has(lengthOf(s))) return false;
  if (filt.fav && !lib.isFav(s.id)) return false;
  if (filt.recent && !(s.added > Date.now() - 30 * 864e5)) return false;
  return true;
}
const filtered = (songs) => (filtCount() ? songs.filter(passes) : songs);

function filterButton() {
  const n = filtCount();
  const b = ib("filter", () => filterSheet(), n ? "on fbtn" : "fbtn", "Filter");
  if (n) b.append(h("span", { class: "fbadge" }, String(n)));
  return b;
}
/** the active filters as chips under the title, each with an ×, plus Clear */
function filterBar() {
  if (!filtCount()) return null;
  const bar = h("div", { class: "chips fbar" });
  const chip = (label, off) => bar.append(h("button", { class: "chip on", onclick: () => { off(); refresh(); } }, label, icon("close")));
  for (const g of filt.genres) chip(g.replace(/\b\w/g, (c) => c.toUpperCase()), () => filt.genres.delete(g));
  for (const a of filt.artists) chip(lib.artists().find((x) => x.name.toLowerCase() === a)?.name || a, () => filt.artists.delete(a));
  for (const d of filt.decades) chip(`${d}s`, () => filt.decades.delete(d));
  for (const l of filt.length) chip(LENGTHS[l], () => filt.length.delete(l));
  if (filt.fav) chip("Favorites", () => (filt.fav = false));
  if (filt.recent) chip("Added this month", () => (filt.recent = false));
  bar.append(h("button", { class: "chip", onclick: () => { clearFilters(); refresh(); } }, "Clear"));
  return bar;
}
function clearFilters() { filt.genres.clear(); filt.artists.clear(); filt.decades.clear(); filt.length.clear(); filt.fav = filt.recent = false; }

function filterSheet() {
  const all = [...lib.songs.values()];
  const box = h("div", { class: "fsheet" });
  const draw = () => {
    const count = all.filter(passes).length;
    const tally = (keyFn) => { const m = new Map(), names = new Map(); for (const s of all) for (const k of [].concat(keyFn(s))) { if (!k && k !== 0) continue; const key = typeof k === "string" ? k.toLowerCase() : k; m.set(key, (m.get(key) || 0) + 1); if (!names.has(key)) names.set(key, k); } return [m, names]; };
    const section = (title, entries, isOn, toggle, label) => {
      if (!entries.length) return null;
      return h("div", { class: "fsec" }, h("div", { class: "fsec-t" }, title), h("div", { class: "fwrap" }, entries.map(([k, n]) =>
        h("button", { class: "chip" + (isOn(k) ? " on" : ""), onclick: () => { toggle(k); draw(); } }, isOn(k) && icon("check"), label(k), h("span", { class: "n" }, String(n))))));
    };
    const [gm, gn] = tally(genresOf);
    const [am, an] = tally(artistNames);
    const [dm] = tally((s) => decadeOf(s) || []);
    const [lm] = tally((s) => lengthOf(s) || []);
    const tog = (set) => (k) => (set.has(k) ? set.delete(k) : set.add(k));
    const byCount = (m) => [...m.entries()].sort((a, b) => b[1] - a[1]);
    box.replaceChildren(
      h("div", { class: "lk-head" }, icon("filter"), h("h3", {}, "Filter"), h("span", { style: { flex: 1 } }),
        filtCount() ? h("button", { class: "btn text", onclick: () => { clearFilters(); draw(); } }, "Clear all") : null),
      section("Genre", byCount(gm), (k) => filt.genres.has(k), tog(filt.genres), (k) => gn.get(k)) ||
        h("div", { class: "fsec" }, h("div", { class: "fsec-t" }, "Genre"), h("button", { class: "chip", onclick: () => { closeSheet(true); findGenres(); } }, icon("search"), "Find genres for your songs")),
      section("Artist", byCount(am).slice(0, 40), (k) => filt.artists.has(k), tog(filt.artists), (k) => an.get(k)),
      section("Decade", [...dm.entries()].sort((a, b) => b[0] - a[0]), (k) => filt.decades.has(k), tog(filt.decades), (k) => `${k}s`),
      section("Length", ["short", "mid", "long"].filter((k) => lm.has(k)).map((k) => [k, lm.get(k)]), (k) => filt.length.has(k), tog(filt.length), (k) => LENGTHS[k]),
      h("div", { class: "fsec" }, h("div", { class: "fsec-t" }, "Other"), h("div", { class: "fwrap" },
        h("button", { class: "chip" + (filt.fav ? " on" : ""), onclick: () => { filt.fav = !filt.fav; draw(); } }, filt.fav ? icon("check") : icon("heart"), "Favorites only"),
        h("button", { class: "chip" + (filt.recent ? " on" : ""), onclick: () => { filt.recent = !filt.recent; draw(); } }, filt.recent && icon("check"), "Added this month"))),
      h("div", { class: "btns fsticky" }, h("button", { class: "btn", style: { flex: 1 }, onclick: () => closeSheet() }, `Show ${plural(count, "song")}`)));
  };
  draw();
  openSheet(box, { onClose: () => refresh() });
}

/** Look up genres online for songs that don't have one (one lookup per album, then single songs). */
async function findGenres(songs) {
  const todo = (songs || [...lib.songs.values()]).filter((s) => !genresOf(s).length);
  if (!todo.length) return snack("Every song already has a genre");
  const pb = progressBox("Finding genres");
  let found = 0, done = 0;
  const pause = () => new Promise((r) => setTimeout(r, 300));
  const albumKeys = [...new Set(todo.filter((s) => s.album).map((s) => albumKey(s)))];
  const total = albumKeys.length + todo.filter((s) => !s.album).length || 1;
  try {
    for (const k of albumKeys) {
      const a = lib.albumMap.get(k);
      pb.set(done++ / total, a?.title || "");
      if (!a) continue;
      let genre = "";
      try {
        const m = await bestAlbumMatch(a);
        if (m) genre = m.genre || (await resultDetails(m)).genre || "";
        if (!genre) { const sm = await bestSongMatch(a.tracks[0]); if (sm) genre = sm.genre || (await resultDetails(sm)).genre || ""; }
      } catch {}
      if (genre) for (const t of a.tracks) if (!genresOf(t).length) { await lib.updateSong(t.id, { genre }); found++; }
      await pause();
    }
    for (const s of todo.filter((x) => !x.album && !genresOf(x).length)) {
      pb.set(done++ / total, s.title);
      try {
        const m = await bestSongMatch(s);
        const genre = m ? m.genre || (await resultDetails(m)).genre : "";
        if (genre) { await lib.updateSong(s.id, { genre }); found++; }
      } catch {}
      await pause();
    }
  } finally {
    pb.close();
  }
  lib.rebuild();
  refresh();
  const left = todo.filter((s) => !genresOf(lib.songs.get(s.id) || s).length).length;
  snack(found ? `Found genres for ${plural(found, "song")}` + (left ? ` · ${left} not found` : "") : "Couldn't find genres online — check your internet connection");
}

// Play / Shuffle buttons. Upright they sit under the title; sideways they move into the top bar.
function playButtons(songs, ctx, mix) {
  const play = () => { if (mix) return playWithMix(songs, 0, ctx); player.playList(songs.map((s) => s.id), 0, { shuffle: false }); ctxLabel = ctx; };
  const shuf = () => { player.playList(songs.map((s) => s.id), 0, { shuffle: true }); ctxLabel = ctx; };
  return {
    head: [h("button", { class: "hbtn", onclick: play, "aria-label": "Play" }, icon("play"), h("span", {}, "Play")),
      h("button", { class: "hbtn tonal", onclick: shuf, "aria-label": "Shuffle" }, icon("shuffle"), h("span", {}, "Shuffle"))],
    body: h("div", { class: "acts bplay" }, h("button", { class: "btn", onclick: play }, icon("play"), "Play"), h("button", { class: "btn tonal", onclick: shuf }, icon("shuffle"), "Shuffle")),
  };
}

/** The header's icon buttons in one capsule. Tap a button as usual — or hold the capsule and a bubble appears under
 *  your finger: slide it across the buttons (a tick on each) and let go on the one you want. Going to a page with
 *  fewer or more buttons, the capsule shrinks or grows into its new size. */
let capW = 0;
function capsule(buttons) {
  const lens = h("i", { class: "lens" });
  const cap = h("div", { class: "capsule" }, lens, ...buttons);
  let timer = 0, held = false, over = null, sx = 0, sy = 0;
  const btnAt = (x) => buttons.find((b) => { const r = b.getBoundingClientRect(); return x >= r.left - 2 && x <= r.right + 2; }) ||
    (x < buttons[0].getBoundingClientRect().left ? buttons[0] : buttons.at(-1));
  const place = (b, grow) => {
    const cr = cap.getBoundingClientRect(), r = b.getBoundingClientRect();
    lens.style.left = `${r.left - cr.left}px`; lens.style.width = `${r.width}px`;
    lens.classList.toggle("big", !!grow);
    buttons.forEach((x) => x.classList.toggle("lensed", x === b));
  };
  const end = () => { clearTimeout(timer); held = false; over = null; cap.classList.remove("holding"); lens.classList.remove("on", "big"); buttons.forEach((x) => x.classList.remove("lensed")); };
  cap.addEventListener("pointerdown", (e) => {
    sx = e.clientX; sy = e.clientY; held = false;
    clearTimeout(timer);
    timer = setTimeout(() => {
      held = true; over = btnAt(sx);
      try { cap.setPointerCapture(e.pointerId); } catch {}
      cap.classList.add("holding"); lens.classList.add("on"); place(over, true);
      nc.haptic("press");
    }, 220);
  });
  cap.addEventListener("pointermove", (e) => {
    if (!held) { if (Math.hypot(e.clientX - sx, e.clientY - sy) > 10) clearTimeout(timer); return; }
    const b = btnAt(e.clientX);
    if (b !== over) { over = b; place(b, true); nc.haptic("tick"); }
  });
  cap.addEventListener("pointerup", (e) => {
    clearTimeout(timer);
    if (!held) return;
    const b = over; end();
    cap.dataset.swallow = "1"; setTimeout(() => delete cap.dataset.swallow, 60);
    nc.haptic("tap");
    b?.click();
  });
  cap.addEventListener("pointercancel", end);
  cap.addEventListener("click", (e) => { if (cap.dataset.swallow && e.isTrusted) { e.stopPropagation(); e.preventDefault(); } }, true);
  cap.addEventListener("contextmenu", (e) => e.preventDefault());
  // shrink / grow from the last capsule's size into this one's
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (!cap.isConnected) return;
    const w = cap.getBoundingClientRect().width;
    if (capW && Math.abs(capW - w) > 2 && motion())
      cap.animate([{ width: `${capW}px` }, { width: `${w}px` }], { duration: 460, easing: "cubic-bezier(.3,1.35,.5,1)" });
    capW = w;
  }));
  return cap;
}
const rootActions = (withFilter = true) => [ib("search", () => { push({ type: "browse" }); setTimeout(() => $(".browse-q")?.focus(), 420); }, "", "Search"),
  withFilter && filterButton(), ib("settings", () => push({ type: "settings" }), "", "Settings")].filter(Boolean);

function render(p) {
  switch (p.type) {
    case "albums": return pageAlbums();
    case "artists": return pageArtists();
    case "songs": return pageSongs();
    case "favorites": return pageFavorites();
    case "playlists": return pagePlaylists();
    case "browse": return pageBrowse();
    case "album": return pageAlbum(p.key);
    case "artist": return pageArtist(p.name);
    case "playlist": return pagePlaylist(p.name);
    case "settings": return pageSettings();
    case "search": return pageSearch(p);
  }
}

// ============================================================ selection
function startSelect(kind, id) {
  nc.haptic("press");
  sel = { kind, ids: new Set([id]) };
  nc.update({ covered: true });
  refresh();
  updateSelbar();
}
function toggleSel(id) {
  sel.ids.has(id) ? sel.ids.delete(id) : sel.ids.add(id);
  nc.haptic(sel.ids.has(id) ? "tick" : "soft");
  if (!sel.ids.size) return endSelect();
  refresh();
  updateSelbar();
}
function endSelect() {
  sel = null;
  nc.update({ covered: false });
  $("#selbar").hidden = true;
  refresh();
}
function selSongs() {
  if (!sel) return [];
  if (sel.kind === "albums") return [...sel.ids].flatMap((k) => lib.albumMap.get(k)?.tracks || []);
  return [...sel.ids].map((id) => lib.songs.get(id)).filter(Boolean);
}
function updateSelbar() {
  const bar = $("#selbar");
  if (!sel) { bar.hidden = true; return; }
  const n = sel.ids.size, what = sel.kind === "albums" ? plural(n, "album") : plural(n, "song");
  bar.innerHTML = "";
  bar.append(ib("close", endSelect), h("div", { class: "n" }, what + " selected"),
    ib("play", () => { player.playList(selSongs().map((s) => s.id), 0); endSelect(); }, "", "Play"),
    ib("heart", () => { const on = lib.toggleFav(selSongs().map((s) => s.id)); snack(on ? "Added to Favorites" : "Removed from Favorites"); endSelect(); }),
    ib("playlist", () => pickPlaylist(selSongs(), endSelect), "", "Add to playlist"),
    ib("share", () => { const s = selSongs(); endSelect(); sharePack(s, sel?.kind === "albums" ? "My Albums" : "My Mix"); }, "", "Share"),
    ib("trash", () => { const s = selSongs(); removeSongs(s, what).then(endSelect); }, "", "Remove"));
  bar.hidden = false;
}

// ============================================================ shared row/card builders
function songRow(s, i, list, opts = {}) {
  const cur = player.current?.id === s.id;
  const selected = sel?.kind === "songs" && sel.ids.has(s.id);
  const row = h("div", { class: "row" + (cur ? " now" : "") + (selected ? " sel" : "") + (cur && !player.playing ? " paused" : "") });
  const play = () => {
    if (opts.mix) return opts.ctx === "Search" ? playWithMix([s], 0, "Search") : playWithMix(list, i, opts.ctx || "");
    player.playList(list.map((x) => x.id), i); ctxLabel = opts.ctx || "";
  };
  if (opts.num) row.append(h("div", { class: "num" }, cur ? h("span", { class: "eqbars" }, h("b"), h("b"), h("b")) : String(s.track || i + 1)));
  else {
    const a = art(s.cover, { label: s.album });
    if (cur) { a.classList.add("playing"); a.append(h("span", { class: "eqbars" }, h("b"), h("b"), h("b"))); }
    else a.append(h("div", { class: "play" }, h("span", {}, icon("play"))));
    row.append(a);
  }
  row.append(h("div", { class: "meta" }, h("div", { class: "t" }, s.title),
    h("div", { class: "s" }, opts.num ? s.artist || "" : [s.artist, opts.noAlbum ? "" : s.album].filter(Boolean).join(" · "))));
  if (lib.isFav(s.id)) row.append(icon("heartf", "heart"));
  if (opts.dur) row.append(h("div", { class: "dur" }, fmtTime(s.duration)));
  if (!sel) {
    const more = ib("more", (e) => { e.stopPropagation(); songMenu(s, opts); });
    more.addEventListener("pointerdown", (e) => e.stopPropagation());
    row.append(more);
  }
  press(row, () => { if (justDoubled("s" + s.id)) return; sel ? sel.kind === "songs" && toggleSel(s.id) : play(); },
    () => (sel ? sel.kind === "songs" && toggleSel(s.id) : songMenu(s, opts)), () => ({ kind: "song", song: s }));
  if (!sel) doubleTap(row, (x, y) => likeSong(s, x, y), "s" + s.id);
  row.dataset.id = s.id;
  row._make = () => songRow(s, i, list, opts);        // (so a new song playing can redo just this row)
  return row;
}
function songList(songs, opts) {
  const box = h("div", { class: "list" });
  // long lists: the first screenfuls now, the rest a chunk per frame (no freeze on a big library)
  const first = songs.length > 160 ? 100 : songs.length;
  const frag = document.createDocumentFragment();
  for (let i = 0; i < first; i++) frag.append(songRow(songs[i], i, songs, opts));
  box.append(frag);
  let at = first;
  const more = () => {
    if (at >= songs.length) return;
    if (!box.isConnected && at > first) return;        // page went away
    const f = document.createDocumentFragment();
    const end = Math.min(songs.length, at + 220);
    for (; at < end; at++) f.append(songRow(songs[at], at, songs, opts));
    box.append(f);
    const pg = box.closest(".page");                     // keep the scroll position a refresh asked for
    if (pg?._wantTop && pg.scrollTop < pg._wantTop - 2) { quietScrollUntil = performance.now() + 150; pg.scrollTop = pg._wantTop; }
    requestAnimationFrame(more);
  };
  if (at < songs.length) requestAnimationFrame(more);
  return box;
}
/** an artist as a card the size of an album card (round picture) */
function artistCard(a) {
  const c = h("div", { class: "card artist-card" }, art(a.cover, { small: false, label: a.name, round: true }),
    h("div", { class: "t" }, a.name), h("div", { class: "s" }, plural(a.songs.size, "song")));
  press(c, () => push({ type: "artist", name: a.name }));
  return c;
}
/** a song as a card the size of an album card; tapping plays it (with a mix of similar songs after it) */
function songCard(s, list, i, ctx) {
  const c = h("div", { class: "card song-card" + (player.current?.id === s.id ? " now" : "") }, art(s.cover, { small: false, label: s.album }),
    h("div", { class: "t" }, s.title), h("div", { class: "s" }, s.artist || s.album || ""));
  press(c, () => { if (justDoubled("s" + s.id)) return; playWithMix(list, i, ctx); }, () => songMenu(s), () => ({ kind: "song", song: s }));
  doubleTap(c, (x, y) => likeSong(s, x, y), "s" + s.id);
  c.dataset.id = s.id;
  c._make = () => songCard(s, list, i, ctx);
  return c;
}
function albumCard(a) {
  const selected = sel?.kind === "albums" && sel.ids.has(a.key);
  const c = h("div", { class: "card" + (selected ? " sel" : "") }, art(a.cover, { small: false, label: a.title }),
    h("div", { class: "t" }, a.title), h("div", { class: "s" }, a.artist));
  if (selected) c.firstChild.append(h("span", { class: "check" }, icon("check")));
  press(c, () => (sel ? sel.kind === "albums" && toggleSel(a.key) : push({ type: "album", key: a.key })),
    () => (sel ? sel.kind === "albums" && toggleSel(a.key) : albumMenu(a)),
    () => ({ kind: "pack", title: a.title, sub: `${a.artist} · ${plural(a.tracks.length, "song")}`, cover: a.cover, songs: () => a.tracks }));
  return c;
}
function emptyLibrary() {
  return h("div", { class: "empty" }, h("div", { class: "big" }, icon("note")), h("h3", {}, "Add your music"),
    h("div", {}, "Pick songs from your phone, or add a music pack someone shared with you."),
    h("div", { class: "acts" }, h("button", { class: "btn", onclick: pickSongs }, icon("add"), "Add Songs"),
      h("button", { class: "btn tonal", onclick: pickPacks }, icon("pack"), "Add a Music Pack")));
}

// ============================================================ pages
function pageAlbums() {
  const p = pageShell("Albums", { back: false, actions: rootActions() });
  if (!lib.songs.size) { p.append(emptyLibrary()); return p; }
  const albums = filtCount() ? lib.albums.filter((a) => a.tracks.some(passes)) : lib.albums;
  p.append(...[filterBar()].filter(Boolean), albums.length ? h("div", { class: "grid" }, albums.map(albumCard)) : noMatches());
  return p;
}
const noMatches = () => h("div", { class: "empty" }, h("div", { class: "big" }, icon("filter")), h("h3", {}, "Nothing matches"), h("div", {}, "Try removing a filter."),
  h("div", { class: "acts" }, h("button", { class: "btn tonal", onclick: () => { clearFilters(); refresh(); } }, "Clear filters")));

function pageArtists() {
  const p = pageShell("Artists", { back: false, actions: rootActions() });
  let list = lib.artists();
  if (!list.length) { p.append(emptyLibrary()); return p; }
  if (filtCount()) list = list.map((a) => ({ ...a, n: [...a.songs].filter(passes).length })).filter((a) => a.n);
  { const fb = filterBar(); if (fb) p.append(fb); }
  if (!list.length) p.append(noMatches());
  for (const a of list) {
    const r = h("div", { class: "arow" }, art(a.cover, { label: a.name, round: true }),
      h("div", {}, h("div", { class: "t" }, a.name), h("div", { class: "s" }, plural(a.n ?? a.songs.size, "song"))));
    press(r, () => push({ type: "artist", name: a.name }));
    p.append(r);
  }
  return p;
}

function pageSongs() {
  if (!lib.songs.size) { const e = pageShell("Songs", { back: false, actions: rootActions() }); e.append(emptyLibrary()); return e; }
  const songs = filtered(lib.sortedSongs);
  const pb = playButtons(songs, "Songs");
  const p = pageShell("Songs", { back: false, actions: [...pb.head, ...rootActions()] });
  { const fb = filterBar(); if (fb) p.append(fb); }
  if (!songs.length) { p.append(noMatches()); return p; }
  p.append(h("div", { class: "plays" }, pb.body));
  p.append(songList(songs, { ctx: "Songs", dur: true }));
  return p;
}

function genresOf(s) { return (s.genre || "").split(",").map((g) => g.trim()).filter(Boolean); }
function pageFavorites() {
  const all0 = lib.favorites();       // newest first
  const all = filtered(all0);
  const shownPre = favGenres.size ? all.filter((s) => genresOf(s).some((g) => favGenres.has(g.toLowerCase()))) : all;
  const pb = playButtons(shownPre, "Favorites");
  const p = pageShell("Favorite Songs", { actions: all0.length ? [...pb.head, filterButton()] : [] });
  if (!all0.length) {
    p.append(h("div", { class: "empty" }, h("div", { class: "big" }, icon("heart")), h("h3", {}, "No favorites yet"),
      h("div", {}, "Tap the heart on a song you love — newest favorites show up first.")));
    return p;
  }
  const counts = new Map(), names = new Map();
  for (const s of all) for (const g of genresOf(s)) { const k = g.toLowerCase(); counts.set(k, (counts.get(k) || 0) + 1); if (!names.has(k)) names.set(k, g); }
  for (const k of [...favGenres]) if (!counts.has(k)) favGenres.delete(k);
  const keys = [...counts.keys()].sort((a, b) => counts.get(b) - counts.get(a) || names.get(a).localeCompare(names.get(b)));
  { const fb = filterBar(); if (fb) p.append(fb); }
  {
    const chips = h("div", { class: "chips" });
    chips.append(h("button", { class: "chip" + (favGenres.size ? "" : " on"), onclick: () => { favGenres.clear(); refresh(); } },
      !favGenres.size && icon("check"), favGenres.size ? "All genres" : "Recently added"));
    for (const k of keys) {
      const on = favGenres.has(k);
      chips.append(h("button", { class: "chip" + (on ? " on" : ""), onclick: () => { on ? favGenres.delete(k) : favGenres.add(k); refresh(); } },
        on && icon("check"), names.get(k), h("span", { class: "n" }, String(counts.get(k)))));
    }
    const missing = all.filter((s) => !genresOf(s).length);
    if (missing.length) chips.append(h("button", { class: "chip find" }, icon("search"), keys.length ? `Find genres (${missing.length})` : "Find genres"));
    chips.lastChild?.classList.contains("find") && (chips.lastChild.onclick = () => findGenres(missing));
    p.append(chips);
    const prev = pageEl?.querySelector(".chips")?.scrollLeft;
    if (prev) requestAnimationFrame(() => (chips.scrollLeft = prev));
  }
  const shown = shownPre;
  p.append(h("div", { class: "plays" }, h("div", { class: "info" },
      favGenres.size ? `${plural(shown.length, "song")} in ${[...favGenres].map((k) => names.get(k)).join(" + ")} · ${all0.length} favorites in all` : `${plural(all.length, "song")} · newest first`),
    pb.body));
  p.append(shown.length ? songList(shown, { ctx: "Favorites" }) : noMatches());
  return p;
}

function pagePlaylists() {
  const p = pageShell("Playlists", { back: false, actions: rootActions() });
  const newRow = h("div", { class: "arow" }, h("div", { class: "art", style: { borderRadius: "12px", display: "grid", placeItems: "center", background: "color-mix(in srgb, var(--accent) 20%, var(--surface2))", color: "var(--accent)" } }, icon("add")),
    h("div", {}, h("div", { class: "t" }, "New Playlist")));
  press(newRow, () => newPlaylist());
  p.append(newRow);
  const fav = h("div", { class: "arow" }, h("div", { class: "art", style: { borderRadius: "12px", display: "grid", placeItems: "center", background: "linear-gradient(145deg,#ff7aa0,#b0305a)", color: "#2a0612" } }, icon("heartf")),
    h("div", {}, h("div", { class: "t" }, "Favorite Songs"), h("div", { class: "s" }, plural(lib.favorites().length, "song"))));
  press(fav, () => push({ type: "favorites" }), null,
    () => ({ kind: "pack", title: "Favorite Songs", sub: plural(lib.favorites().length, "song"), songs: () => lib.favorites(), playlist: "Favorite Songs" }));
  p.append(fav);
  for (const name of Object.keys(lib.meta.playlists)) {
    const songs = lib.playlistSongs(name);
    const a = art(songs.find((s) => s.cover)?.cover, { label: name });
    a.style.borderRadius = "12px";
    const r = h("div", { class: "arow" }, a, h("div", {}, h("div", { class: "t" }, name), h("div", { class: "s" }, plural(songs.length, "song"))));
    press(r, () => push({ type: "playlist", name }), () => playlistMenu(name),
      () => ({ kind: "pack", title: name, sub: plural(songs.length, "song"), cover: songs.find((x) => x.cover)?.cover, songs: () => lib.playlistSongs(name), playlist: name }));
    p.append(r);
  }
  return p;
}

function detailHero(cover, title, by, info, songs, ctx, more, round, mix) {
  const hero = h("div", { class: "hero" }, cover);
  if (round) cover.classList.add("round");
  const pb = playButtons(songs, ctx, mix);
  hero.append(h("h2", {}, title), by && h("div", { class: "by" }, by), h("div", { class: "info" }, info), pb.body);
  hero._head = pb.head;            // the page puts these in its top bar (shown when sideways)
  return hero;
}
function withHeadPlay(p, hero) {
  const head = p.querySelector(".head"), title = head.querySelector("h1");
  for (const b of [...(hero._head || [])].reverse()) title.after(b);      // Play, then Shuffle
  // the title of the page shows in the top bar when sideways, so the hero can be compact
  if (!title.textContent) title.textContent = hero.querySelector("h2")?.textContent || "";
  title.classList.add("htitle");
  return p;
}
const totalTime = (songs) => { const m = Math.round(songs.reduce((a, s) => a + (s.duration || 0), 0) / 60); return m >= 60 ? `${Math.floor(m / 60)} hr ${m % 60} min` : `${m} min`; };

function pageAlbum(key) {
  const a = lib.albumMap.get(key);
  if (!a) { setTimeout(pop); return pageShell(""); }
  const p = pageShell("", { actions: [ib("more", () => albumMenu(a))] });
  const col = lib.cover(a.cover)?.color;
  if (col) p.style.setProperty("--accent", col);
  p.style.background = `linear-gradient(color-mix(in srgb, var(--accent) 22%, transparent), transparent 420px)`;
  p.append(detailHero(art(a.cover, { small: false, label: a.title, cls: "bigart" }), a.title,
    h("span", { onclick: () => push({ type: "artist", name: a.artist.split(",")[0].trim() }) }, a.artist),
    [a.year, plural(a.tracks.length, "song"), totalTime(a.tracks)].filter(Boolean).join(" · "), a.tracks, a.title, null, false, a.tracks.length < 5));
  p.append(songList(a.tracks, { num: true, ctx: a.title, dur: true, inAlbum: true, mix: a.tracks.length < 5 }));
  return withHeadPlay(p, p.querySelector(".hero"));
}

function pageArtist(name) {
  const k = name.toLowerCase();
  const songs = [...lib.songs.values()].filter((s) => (s.albumartist || "").toLowerCase() === k || (s.artist || "").split(/\s*,\s*/).some((x) => x.toLowerCase() === k))
    .sort((a, b) => (a.album || "").localeCompare(b.album || "") || (a.track || 0) - (b.track || 0));
  const p = pageShell("");
  const albums = [...new Set(songs.map((s) => albumKey(s)))].map((key) => lib.albumMap.get(key)).filter(Boolean);
  p.append(detailHero(art(songs.find((s) => s.cover)?.cover, { small: false, label: name, cls: "bigart" }), name, "",
    `${plural(albums.length, "album")} · ${plural(songs.length, "song")}`, songs, name, null, true));
  if (albums.length) { p.append(h("div", { class: "section-t" }, "Albums"), h("div", { class: "hscroll" }, albums.map(albumCard))); }
  p.append(h("div", { class: "section-t" }, "Songs"), songList(songs, { ctx: name }));
  return withHeadPlay(p, p.querySelector(".hero"));
}

function pagePlaylist(name) {
  if (!(name in lib.meta.playlists)) { setTimeout(pop); return pageShell(""); }
  const songs = lib.playlistSongs(name);
  const p = pageShell("", { actions: [ib("more", () => playlistMenu(name))] });
  p.append(detailHero(mosaic(songs, name), name, "", songs.length ? `${plural(songs.length, "song")} · ${totalTime(songs)}` : "Empty — long-press songs to add them here", songs, name));
  p.append(songList(songs, { ctx: name, playlist: name }));
  return withHeadPlay(p, p.querySelector(".hero"));
}

function pageSearch(state) {
  const p = pageShell("Search");
  const input = h("input", { placeholder: "Songs, albums, artists", value: state.q || "", enterkeyhint: "search" });
  const results = h("div");
  const run = () => {
    state.q = input.value;
    const q = input.value.trim().toLowerCase();
    results.innerHTML = "";
    if (!q) return;
    const m = (x) => (x || "").toLowerCase().includes(q);
    const songs = lib.sortedSongs.filter((s) => m(s.title) || m(s.artist) || m(s.album));
    const albums = lib.albums.filter((a) => m(a.title) || m(a.artist));
    const artists = lib.artists().filter((a) => m(a.name));
    if (artists.length) {
      results.append(h("div", { class: "section-t" }, "Artists"));
      for (const a of artists.slice(0, 6)) {
        const r = h("div", { class: "arow" }, art(a.cover, { label: a.name, round: true }), h("div", {}, h("div", { class: "t" }, a.name), h("div", { class: "s" }, plural(a.songs.size, "song"))));
        press(r, () => push({ type: "artist", name: a.name }));
        results.append(r);
      }
    }
    if (albums.length) results.append(h("div", { class: "section-t" }, "Albums"), h("div", { class: "hscroll" }, albums.slice(0, 20).map(albumCard)));
    if (songs.length) results.append(h("div", { class: "section-t" }, "Songs"), songList(songs.slice(0, 80), { ctx: "Search" }));
    if (!songs.length && !albums.length && !artists.length) results.append(h("div", { class: "empty" }, `Nothing matches “${input.value}”`));
  };
  input.addEventListener("input", run);
  p.append(h("div", { class: "search" }, icon("search"), input), results);
  run();
  setTimeout(() => input.focus(), 350);
  return p;
}

// ---- iPhone: Settings as a native screen. Same settings, described here; the screen sends back changes and taps.
function settingsModel(sizeText) {
  const s = lib.meta.settings;
  const missingGenres = [...lib.songs.values()].filter((x) => !genresOf(x).length).length;
  const eqVals = EQ_PRESETS[s.eq] || EQ_PRESETS.Flat;
  return [
    { title: "Your Music", rows: [
      { id: "add", type: "action", symbol: "plus", title: "Add Songs", subtitle: "Pick music files from your phone" },
      { id: "pack", type: "action", symbol: "shippingbox", title: "Add Music Pack", subtitle: "Add a …-musicpack.zip someone shared with you" },
      { id: "link", type: "action", symbol: "link", title: "Add from Link (Experimental)", subtitle: "Paste a YouTube or YouTube Music song, album or playlist link" },
      { id: "ytacct", type: "action", symbol: "person.crop.circle", title: "YouTube Account", subtitle: ytSignedInNow() ? "Signed in · used by Add from Link · tap to sign out" : "Sign in if YouTube asks you to (for Add from Link)" },
      { id: "covers", type: "action", symbol: "globe", title: "Find Missing Covers", subtitle: "Look up album art online" },
      { id: "genres", type: "action", symbol: "magnifyingglass", title: "Find Genres", subtitle: missingGenres ? `Look up genres online for ${plural(missingGenres, "song")} that don't have one` : "Every song has a genre" },
      { id: "autofill", type: "toggle", symbol: "wand.and.stars", title: "Fill In Song Info Automatically", subtitle: "When you add songs, look up missing albums, years, genres and covers", value: s.autofill !== false },
      { id: "library", type: "info", symbol: "folder", title: "Library", subtitle: sizeText || `${plural(lib.songs.size, "song")} · ${plural(lib.albums.length, "album")}` },
      { id: "diag", type: "action", symbol: "info.circle", title: "Diagnostics", subtitle: "What the app did recently, for troubleshooting" },
    ] },
    { title: "Playback", rows: [
      { id: "crossfade", type: "slider", symbol: "list.bullet", title: "Crossfade", subtitle: "Blend the end of each song into the next", min: 0, max: 12, value: +s.crossfade || 0, unit: "s", zero: "Off" },
      { id: "lyricReturn", type: "choice", symbol: "quote.bubble", title: "Scroll Timers", subtitle: "After you stop scrolling, lyrics glide back to the current line and the player comes back (sideways) after",
        value: +s.lyricReturn || 1.5, options: [1, 1.5, 2, 3, 5, 10].map((v) => ({ label: `${v}s`, value: v })) },
      { id: "tuckPinned", type: "toggle", symbol: "rectangle.bottomhalf.inset.filled", title: "Keep Media Bar Tucked", subtitle: "Sideways: keep the media bar tucked under the tabs as just the album cover", value: !!s.tuckPinned },
    ] },
    { title: "Equalizer", rows: [
      { id: "eqgraph", type: "eq", values: eqVals, labels: EQ_BANDS.map((f) => (f >= 1000 ? f / 1000 + "k" : String(f))) },
      { id: "eq", type: "menu", symbol: "slider.horizontal.3", title: "Preset", value: s.eq && EQ_PRESETS[s.eq] ? s.eq : "Flat", options: Object.keys(EQ_PRESETS) },
    ] },
    { title: "Look & Feel", rows: [
      { id: "motion", type: "toggle", symbol: "sparkles", title: "Motion Effects", subtitle: "Sliding pages, glow, living background", value: motion() },
    ] },
    { title: "Storage", footer: "AuraOS Music for iPhone · music packs work with the desktop and Android apps too", rows: [
      { id: "removeAll", type: "action", symbol: "trash", title: "Remove All Music", subtitle: "Deletes the songs stored in this app", destructive: true, stay: true },
    ] },
  ];
}
function openNativeSettings() {
  nc.settings(settingsModel(), true);
  usage().then((u) => { if (u.usage) nc.settings(settingsModel(`${plural(lib.songs.size, "song")} · ${plural(lib.albums.length, "album")} · ${fmtSize(u.usage)} used`)); });
}
function applySetting(id, v) {
  const s = lib.meta.settings;
  if (id === "autofill") s.autofill = !!v;
  else if (id === "tuckPinned") { miniTuck.pin(!!v); return; }
  else if (id === "motion") { s.motion = !!v; document.body.classList.toggle("nomotion", !motion()); }
  else if (id === "crossfade") s.crossfade = +v || 0;
  else if (id === "lyricReturn") s.lyricReturn = +v || 1.5;
  else if (id === "eq") { player.applyEq(String(v)); nc.settings(settingsModel()); }
  lib.saveMeta();
}
async function settingAction(id) {
  if (id === "add") pickSongs();
  else if (id === "pack") pickPacks();
  else if (id === "link") addFromLink();
  else if (id === "ytacct") youtubeAccount();
  else if (id === "covers") findCovers(true);
  else if (id === "genres") findGenres();
  else if (id === "diag") showDiagnostics();
  else if (id === "removeAll") {
    if (!lib.songs.size) return;
    if (await confirmBox("Remove all music?", `This deletes all ${plural(lib.songs.size, "song")} from this app. Music packs and files on your phone aren't touched.`, "Remove All")) {
      player.pause(); await lib.removeSongs([...lib.songs.keys()]); player.queue = []; player.index = -1; updateMini(); refresh(); snack("Removed all music");
      nc.settings(settingsModel());
    }
  }
}

function pageSettings() {
  const p = pageShell("Settings");
  const s = lib.meta.settings;
  const item = (ic, t, sub, on, right) =>
    h("button", { class: "item", onclick: on }, icon(ic), h("div", { class: "meta" }, h("div", { class: "t" }, t), sub && h("div", { class: "s" }, sub)), right);
  const group = (title, ...kids) => h("div", { class: "group" }, title && h("h4", {}, title), ...kids.filter(Boolean));
  const toggle = (on) => h("span", { class: "switch" + (on ? " on" : "") });
  /** a row that folds open to show more (keeps the page short) */
  const fold = (ic, t, sub, panel) => {
    const val = h("span", { class: "val" }, sub());
    const chev = h("span", { class: "chev" }, icon("down"));
    const box = h("div", { class: "fold" }, panel);
    const row = h("button", { class: "item", onclick: () => { const open = !box.classList.contains("open"); box.classList.toggle("open", open); chev.classList.toggle("open", open); } },
      icon(ic), h("div", { class: "meta" }, h("div", { class: "t" }, t)), val, chev);
    return [row, box, () => (val.textContent = sub())];
  };

  // ---- your library at a glance
  const stat = (n, label) => h("div", { class: "stat" }, h("b", {}, String(n)), h("span", {}, label));
  const used = h("div", { class: "used" }, "…");
  usage().then((u) => { used.textContent = u.usage ? `${fmtSize(u.usage)} on this phone` : ""; });
  const summary = h("div", { class: "group libcard" },
    h("div", { class: "stats" }, stat(lib.songs.size, "songs"), stat(lib.albums.length, "albums"), stat(new Set([...lib.songs.values()].map((x) => (x.albumartist || x.artist || "").toLowerCase()).filter(Boolean)).size, "artists"),
      stat(Object.keys(lib.meta.playlists).length, "playlists")), used);

  // ---- adding music
  const add = group("Add Music",
    item("add", "Add Songs", "Music files from your phone", pickSongs),
    item("pack", "Add Music Pack", "A …-musicpack.zip someone shared with you", pickPacks),
    item("globe", "Add from Link", "YouTube or YouTube Music song, album or playlist · experimental", addFromLink));

  // ---- library health: only what needs doing
  const noCover = lib.albums.filter((a) => !a.cover).length;
  const noGenre = [...lib.songs.values()].filter((x) => !genresOf(x).length).length;
  const afSw = toggle(s.autofill !== false);
  const health = group("Library Health",
    noCover ? item("globe", "Find Missing Covers", `${plural(noCover, "album")} without a cover`, () => findCovers(true), h("span", { class: "badge" }, String(noCover))) : null,
    noGenre ? item("search", "Find Genres", `${plural(noGenre, "song")} without a genre`, () => findGenres(), h("span", { class: "badge" }, String(noGenre))) : null,
    !noCover && !noGenre && lib.songs.size ? h("div", { class: "item" }, icon("check"), h("div", { class: "meta" }, h("div", { class: "t" }, "All set"), h("div", { class: "s" }, "Every album has a cover and every song a genre"))) : null,
    item("spark", "Fill In Info Automatically", "Look up missing albums, years, genres and covers when you add songs", () => {
      s.autofill = s.autofill === false; lib.saveMeta(); afSw.classList.toggle("on", s.autofill !== false);
    }, afSw));

  // ---- playback
  const xfVal = h("span", { class: "val" });
  const xf = h("input", { type: "range", min: "0", max: "12", step: "1", value: String(s.crossfade || 0), class: "slider" });
  const showXf = () => { const v = +xf.value; xfVal.textContent = v ? `${v} s` : "Off"; xf.style.setProperty("--f", (v / 12) * 100 + "%"); };
  xf.addEventListener("input", () => { s.crossfade = +xf.value; lib.saveMeta(); showXf(); });
  showXf();
  const eqViz = h("div", { class: "eqviz" });
  const drawEq = () => {
    eqViz.innerHTML = "";
    (EQ_PRESETS[s.eq] || EQ_PRESETS.Flat).forEach((db, i) => {
      const top = 50 - (db / 12) * 50;
      eqViz.append(h("div", { class: "band" }, h("div", { class: "bar" },
        h("b", { style: { top: `${Math.min(50, top)}%`, bottom: `${100 - Math.max(50, top)}%` } }), h("i", { style: { top: `${top}%` } })),
        h("small", {}, EQ_BANDS[i] >= 1000 ? EQ_BANDS[i] / 1000 + "k" : String(EQ_BANDS[i]))));
    });
  };
  const chips = h("div", { class: "chips", style: { padding: "6px 18px 16px" } });
  let eqLabel = () => {};
  const drawChips = () => {
    chips.innerHTML = "";
    for (const name of Object.keys(EQ_PRESETS)) chips.append(h("button", { class: "chip" + (s.eq === name ? " on" : ""), onclick: () => { player.applyEq(name); drawEq(); drawChips(); eqLabel(); } }, s.eq === name && icon("check"), name));
  };
  drawEq(); drawChips();
  const [eqRow, eqBox, eqUpd] = fold("eq", "Equalizer", () => s.eq || "Flat", h("div", {}, eqViz, chips));
  eqLabel = eqUpd;
  const play = group("Playback",
    h("div", { class: "item" }, icon("queue"), h("div", { class: "meta" }, h("div", { class: "t" }, "Crossfade"), h("div", { class: "s" }, "Blend the end of each song into the next")), xfVal),
    h("div", { class: "sl-wrap" }, xf),
    eqRow, eqBox);

  // ---- look & feel
  const mSw = toggle(motion()), pinSw = toggle(!!s.tuckPinned);
  const lrChips = h("div", { class: "chips", style: { padding: "2px 18px 14px" } });
  let lrLabel = () => {};
  const drawLr = () => {
    lrChips.innerHTML = "";
    const cur = +s.lyricReturn || 1.5;
    for (const v of [1, 1.5, 2, 3, 5, 10]) lrChips.append(h("button", { class: "chip" + (cur === v ? " on" : ""), onclick: () => { s.lyricReturn = v; lib.saveMeta(); drawLr(); lrLabel(); } }, cur === v && icon("check"), `${v} s`));
  };
  drawLr();
  const [lrRow, lrBox, lrUpd] = fold("lyrics", "Scroll Timers", () => `${+s.lyricReturn || 1.5} s`,
    h("div", {}, h("div", { class: "s fold-note" }, "After you stop scrolling, lyrics glide back to the current line, and sideways the player comes back, after:"), lrChips));
  lrLabel = lrUpd;
  const look = group("Look & Feel",
    item("spark", "Motion Effects", "Sliding pages, glow, living background", () => {
      s.motion = !motion(); lib.saveMeta(); mSw.classList.toggle("on", motion()); document.body.classList.toggle("nomotion", !motion());
    }, mSw),
    item("folder", "Keep Media Bar Tucked", "Sideways: just the album cover, tucked under the tabs", () => {
      miniTuck.pin(!s.tuckPinned); pinSw.classList.toggle("on", !!s.tuckPinned);
    }, pinSw),
    lrRow, lrBox);

  // ---- sharing & accounts
  const ovVal = h("span", { class: "val" }, "");
  const share = group("Sharing & Accounts",
    canDragOut() && item("pack", "Drag to Other Apps", "Hold a song, album or playlist, then drag it into Files, Messages… Albums and playlists become music packs.", async () => {
      const { granted } = await DragOut.canOverlay();
      if (granted) return snack("Naming packs when you drop them is on");
      lib.meta.settings.overlayAsked = false; askOverlayOnce();
    }, ovVal),
    item("artist", "YouTube Account", ytSignedInNow() ? "Signed in · tap to sign out" : "Sign in if YouTube asks you to (for Add from Link)", () => youtubeAccount()));
  if (canDragOut()) DragOut.canOverlay().then(({ granted }) => (ovVal.textContent = granted ? "Names on" : "Names off")).catch(() => {});

  // ---- help & storage
  const help = group("Help & Storage",
    item("info", "Diagnostics", "What the app did recently — share it when something goes wrong", showDiagnostics),
    item("trash", "Remove All Music", "Deletes the songs stored in this app", async () => {
      if (!lib.songs.size) return;
      if (await confirmBox("Remove all music?", `This deletes all ${plural(lib.songs.size, "song")} from this app. Music packs and files on your phone aren't touched.`, "Remove All")) {
        player.pause(); await lib.removeSongs([...lib.songs.keys()]); player.queue = []; player.index = -1; updateMini(); refresh(); snack("Removed all music");
      }
    }));
  p.append(...[summary, add, lib.songs.size ? health : null, play, look, share, help].filter(Boolean),
    h("div", { class: "empty", style: { padding: "10px 30px 40px", fontSize: "12px" } }, "AuraOS Music · music packs work on iPhone, Android and the desktop app"));
  return p;
}

// ============================================================ menus & actions
let ctxLabel = "";
function songMenu(s, opts = {}) {
  const fav = lib.isFav(s.id);
  const al = lib.albumOf(s);
  actionSheet(songHead(s), [
    { icon: "playnext", label: "Play Next", run: () => { player.playNext([s.id]); snack("Playing next"); } },
    { icon: "addq", label: "Add to Queue", run: () => { player.addToQueue([s.id]); snack("Added to queue"); } },
    { icon: "search", label: "Look Up Song Info…", run: () => lookupDialog({ song: s }) },
    !npOpen && { icon: "select", label: "Select Songs", run: () => startSelect("songs", s.id) },
    { icon: fav ? "heartf" : "heart", label: fav ? "Remove from Favorites" : "Add to Favorites", run: () => { lib.toggleFav([s.id]); refresh(); updateNpFav(); } },
    { icon: "playlist", label: "Add to Playlist…", run: () => pickPlaylist([s]) },
    !opts.inAlbum && al && { icon: "album", label: "Go to Album", run: () => { closeNp(); push({ type: "album", key: al.key }); } },
    s.artist && { icon: "artist", label: "Go to Artist", run: () => { closeNp(); push({ type: "artist", name: s.artist.split(",")[0].trim() }); } },
    { icon: "share", label: "Share as Music Pack…", run: () => sharePack([s], s.title) },
    opts.playlist && { icon: "close", label: "Remove from This Playlist", run: () => { const pl = lib.meta.playlists[opts.playlist]; pl.splice(pl.indexOf(s.id), 1); lib.saveMeta(); refresh(); } },
    { icon: "trash", label: "Remove Song…", danger: true, run: () => removeSongs([s], "this song") },
  ]);
}
function albumMenu(a) {
  const ids = a.tracks.map((s) => s.id);
  actionSheet(h("div", { class: "sh-head" }, art(a.cover, { label: a.title }), h("div", {}, h("div", { class: "t" }, a.title), h("div", { class: "s" }, a.artist))), [
    { icon: "playnext", label: "Play Next", run: () => { player.playNext(ids); snack("Playing next"); } },
    { icon: "addq", label: "Add to Queue", run: () => { player.addToQueue(ids); snack("Added to queue"); } },
    { icon: "search", label: "Look Up Album Info…", run: () => lookupDialog({ album: a }) },
    stack.at(-1).type !== "album" && { icon: "select", label: "Select Albums", run: () => startSelect("albums", a.key) },
    { icon: "heart", label: "Favorite All Songs", run: () => { lib.toggleFav(ids, true); snack("Added to Favorites"); refresh(); } },
    { icon: "playlist", label: "Add to Playlist…", run: () => pickPlaylist(a.tracks) },
    { icon: "share", label: "Share as Music Pack…", run: () => sharePack(a.tracks, a.title) },
    { icon: "image", label: "Choose Cover from Phone…", run: () => chooseCover(a) },
    { icon: "globe", label: "Find Cover Online", run: () => findCovers(true, [a]) },
    { icon: "trash", label: "Remove Album…", danger: true, run: () => removeSongs(a.tracks, `“${a.title}”`) },
  ]);
}
function playlistMenu(name) {
  const songs = lib.playlistSongs(name);
  actionSheet(h("div", { class: "sh-head" }, h("div", {}, h("div", { class: "t" }, name), h("div", { class: "s" }, plural(songs.length, "song")))), [
    songs.length && { icon: "play", label: "Play", run: () => player.playList(songs.map((s) => s.id), 0) },
    songs.length && { icon: "share", label: "Share as Music Pack…", run: () => sharePack(songs, name, name) },
    { icon: "edit", label: "Rename…", run: async () => {
      const n = await prompt("Rename Playlist", "", name, "", "Rename");
      if (!n || n === name) return;
      const nn = lib.uniquePlaylist(n, n);
      lib.meta.playlists = Object.fromEntries(Object.entries(lib.meta.playlists).map(([k, v]) => [k === name ? nn : k, v]));
      lib.saveMeta();
      if (stack.at(-1).type === "playlist") stack.at(-1).name = nn;
      refresh();
    } },
    { icon: "trash", label: "Delete Playlist", danger: true, run: async () => {
      if (await confirmBox(`Delete “${name}”?`, "The songs stay in your library.", "Delete")) {
        delete lib.meta.playlists[name]; lib.saveMeta();
        if (stack.at(-1).type === "playlist") pop(); else refresh();
      }
    } },
  ]);
}
async function newPlaylist(songs = []) {
  const name = await prompt("New Playlist", "", lib.uniquePlaylist("New Playlist"), "", "Create");
  if (!name) return null;
  const n = lib.uniquePlaylist(name, name);
  lib.meta.playlists[n] = songs.map((s) => s.id);
  lib.saveMeta();
  if (songs.length) snack(`Created “${n}” with ${plural(songs.length, "song")}`);
  refresh();
  return n;
}
function pickPlaylist(songs, after) {
  const names = Object.keys(lib.meta.playlists);
  actionSheet(h("div", { class: "sh-head" }, h("div", {}, h("div", { class: "t" }, "Add to Playlist"), h("div", { class: "s" }, plural(songs.length, "song")))), [
    { icon: "add", label: "New Playlist…", run: () => newPlaylist(songs).then(() => after?.()) },
    ...names.map((n) => ({ icon: "playlist", label: n, run: () => { const k = lib.addToPlaylist(n, songs.map((s) => s.id)); snack(k ? `Added ${plural(k, "song")} to “${n}”` : `Already in “${n}”`); after?.(); refresh(); } })),
  ]);
}
async function removeSongs(songs, what) {
  if (!songs.length) return;
  if (!(await confirmBox(`Remove ${what}?`, `${plural(songs.length, "song")} will be deleted from this app.`))) return;
  const ids = songs.map((s) => s.id);
  player.removeIds(ids);
  await lib.removeSongs(ids);
  snack(`Removed ${plural(ids.length, "song")}`);
  updateMini();
  refresh();
}

async function chooseCover(a) {
  const inp = $("#pickImage");
  inp.onchange = async () => {
    const f = inp.files[0];
    inp.value = "";
    if (!f) return;
    await setAlbumCover(a, f);
    snack("Cover updated");
  };
  inp.click();
}
async function setAlbumCover(a, blob) {
  const big = await squareJpeg(blob, 800);
  if (!big) return false;
  const small = await squareJpeg(big, 160);
  const color = await coverColor(small);
  const id = "c" + Date.now().toString(36);
  await idb.put("covers", { big, small, color, cv: 2 }, id);
  await lib.loadCover(id);
  for (const s of a.tracks) await lib.updateSong(s.id, { cover: id });
  lib.rebuild();
  refresh();
  if (player.current && a.tracks.includes(player.current)) npTrack(0);
  return true;
}

async function coverFromUrl(a, url) {
  try { return await setAlbumCover(a, await getBlob(url)); } catch { return false; }
}

const SONG_FIELDS = [["title", "Title"], ["artist", "Artist"], ["album", "Album"], ["albumartist", "Album artist"], ["track", "Track"], ["disc", "Disc"],
  ["year", "Year"], ["genre", "Genre"], ["label", "Label"], ["copyright", "Copyright"], ["isrc", "ISRC"], ["bpm", "BPM"]];
const ALBUM_FIELDS = [["album", "Album"], ["albumartist", "Album artist"], ["year", "Year"], ["genre", "Genre"], ["label", "Label"], ["copyright", "Copyright"]];

/** Search online for a song's or album's info, pick a result, choose which fields to use. */
function lookupDialog(target) {
  const isAlbum = !!target.album;
  const a = target.album, s0 = target.song;
  const like = isAlbum ? { album: a.title, artist: a.artist, tracks: a.tracks.length } : s0;
  const first = isAlbum ? `${a.title} ${a.artist !== "Unknown Artist" ? a.artist : ""}` : `${s0.title} ${(s0.artist || "").split(",")[0]}`;
  const box = h("div", { class: "lookup" });
  const input = h("input", { class: "field", value: first.trim(), enterkeyhint: "search", placeholder: isAlbum ? "Album and artist" : "Song and artist" });
  const status = h("div", { class: "lk-status" });
  const list = h("div", { class: "lk-list" });
  const go = async () => {
    const q = input.value.trim();
    if (!q) return;
    input.blur();
    status.textContent = "Searching iTunes and Deezer…";
    list.replaceChildren();
    try {
      const res = await searchOnline(q, isAlbum ? "album" : "song", like);
      status.textContent = res.length ? `${plural(res.length, "result")} — tap one to see its info` : `Nothing found for “${q}”. Try fewer words, or just the ${isAlbum ? "album" : "song"} name.`;
      res.slice(0, 30).forEach((r, i) => {
        const row = h("div", { class: "row lk-row" }, h("div", { class: "art" }, r.thumb ? h("img", { src: r.thumb, loading: "lazy", alt: "" }) : h("div", { class: "ph" }, icon("note"))),
          h("div", { class: "meta" }, h("div", { class: "t" }, isAlbum ? r.album : r.title),
            h("div", { class: "s" }, [r.artist, isAlbum ? (r.trackCount ? plural(r.trackCount, "song") : "") : r.album, r.year].filter(Boolean).join(" · "))),
          h("span", { class: "badge" + (i === 0 ? " best" : "") }, i === 0 ? "Best match" : r.source));
        press(row, () => showResult(r));
        list.append(row);
      });
    } catch (e) {
      status.textContent = "Couldn't reach iTunes or Deezer — check your internet connection.";
    }
  };
  input.addEventListener("keydown", (e) => e.key === "Enter" && go());
  const searchView = h("div", {}, h("div", { class: "lk-head" }, icon("search"), h("h3", {}, isAlbum ? "Look Up Album Info" : "Look Up Song Info")),
    h("div", { class: "lk-bar" }, input, h("button", { class: "btn", onclick: go }, "Search")), status, list);

  async function showResult(r0) {
    const view = h("div", {}, h("div", { class: "lk-head" }, h("button", { class: "ib", onclick: () => box.replaceChildren(searchView) }, icon("back")),
      h("h3", {}, "Use This Info?")), h("div", { class: "lk-status" }, "Getting the full details…"));
    box.replaceChildren(view);
    const r = await resultDetails(r0);
    if (!box.isConnected) return;
    const cur = isAlbum ? { album: a.title, albumartist: a.tracks[0]?.albumartist || a.artist, year: a.year, genre: a.tracks[0]?.genre, label: a.tracks[0]?.label, copyright: a.tracks[0]?.copyright } : s0;
    const fields = isAlbum ? ALBUM_FIELDS : SONG_FIELDS;
    const val = (k) => (k === "albumartist" ? r.albumartist || (isAlbum ? r.artist : "") : r[k]);
    const checks = {};
    const rows = [];
    const covCur = isAlbum ? a.cover : s0.cover;
    const coverChk = h("input", { type: "checkbox" });
    coverChk.checked = !!r.cover && !covCur;
    for (const [k, label] of fields) {
      const nv = val(k);
      if (nv === undefined || nv === null || nv === "" || nv === 0) continue;
      const cv = cur[k] ?? "";
      const differs = String(nv) !== String(cv);
      const c = h("input", { type: "checkbox" });
      c.checked = differs && (!cv || ["album", "albumartist", "year", "genre", "track", "disc", "label", "copyright", "isrc", "bpm"].includes(k) && !(k === "album" && !isAlbum && same(nv, cv) && cv));
      if (!differs) c.disabled = true;
      checks[k] = c;
      rows.push(h("label", { class: "kv" + (differs ? "" : " same") }, c, h("span", { class: "k" }, label),
        h("span", { class: "v" }, String(nv), differs && cv ? h("s", {}, String(cv)) : !differs ? h("em", {}, "same") : h("em", {}, "missing now"))));
    }
    let trackChk = null, matched = [];
    if (isAlbum && r.tracks?.length) {
      matched = a.tracks.map((t) => [t, r.tracks.find((x) => same(x.title, t.title))]).filter(([, m]) => m);
      if (matched.length) {
        trackChk = h("input", { type: "checkbox" });
        trackChk.checked = matched.some(([t, m]) => t.track !== m.track || (t.disc || 1) !== (m.disc || 1));
        rows.push(h("label", { class: "kv" }, trackChk, h("span", { class: "k" }, "Track numbers"), h("span", { class: "v" }, `${matched.length} of ${a.tracks.length} songs matched`)));
      }
    }
    const coverRow = r.cover ? h("label", { class: "lk-cover" }, h("img", { src: r.cover, alt: "" }),
      h("div", {}, coverChk, h("span", {}, covCur ? "Replace the cover" : "Use this cover"))) : null;
    const apply = async () => {
      const fieldsOn = Object.fromEntries(Object.entries(checks).filter(([, c]) => c.checked).map(([k]) => [k, val(k)]));
      const wantCover = coverChk.checked && r.cover;
      if (!Object.keys(fieldsOn).length && !wantCover && !trackChk?.checked) return closeSheet(true);
      closeSheet(true);
      if (isAlbum) {
        for (const t of a.tracks) await lib.updateSong(t.id, { ...fieldsOn });
        if (trackChk?.checked) for (const [t, m] of matched) await lib.updateSong(t.id, { track: m.track, disc: m.disc || 0 });
      } else await lib.updateSong(s0.id, fieldsOn);
      lib.rebuild();
      let ok = true;
      if (wantCover) {
        const al = isAlbum ? lib.albumMap.get(albumKey({ ...a.tracks[0] })) : lib.albumOf(lib.songs.get(s0.id));
        ok = al ? await coverFromUrl(al, r.cover) : false;
      }
      if (isAlbum && stack.at(-1).type === "album") stack.at(-1).key = albumKey(a.tracks[0]);
      refresh();
      if (player.current) npTrack(0);
      snack(`Updated ${isAlbum ? plural(a.tracks.length, "song") : "the song info"}` + (wantCover && !ok ? " (couldn't download the cover)" : ""));
    };
    view.replaceChildren(view.firstChild, coverRow,
      h("div", { class: "lk-title" }, h("b", {}, isAlbum ? r.album : r.title), h("span", {}, [r.artist, !isAlbum && r.album, r.source].filter(Boolean).join(" · "))),
      rows.length ? h("div", { class: "lk-fields" }, rows) : h("div", { class: "lk-status" }, "This result doesn't add anything new."),
      h("div", { class: "btns" }, h("button", { class: "btn text", onclick: () => box.replaceChildren(searchView) }, "Back"), h("button", { class: "btn", onclick: apply }, "Apply")));
  }
  box.append(searchView);
  openSheet(box);
  go();
}

/** After adding songs: quietly fill in missing album, year, genre and covers from iTunes/Deezer. */
async function autoFill(songs) {
  if (lib.meta.settings.autofill === false || !songs.length) return;
  let filled = 0, covers = 0, asked = 0;
  const pause = () => new Promise((r) => setTimeout(r, 350));
  try {
    // songs with no album name: find which album they're from
    for (const s of songs.filter((x) => !x.album || !x.year || !x.genre).slice(0, 40)) {
      if (s.album && lib.albumOf(s)?.tracks.length > 1) continue;      // whole albums are done below, in one go
      asked++;
      const m = await bestSongMatch(s);
      await pause();
      if (!m) continue;
      const d = await resultDetails(m);
      const f = {};
      for (const k of ["album", "albumartist", "year", "genre", "track", "disc"]) if (!s[k] && d[k]) f[k] = d[k];
      if (Object.keys(f).length) { await lib.updateSong(s.id, f); filled++; }
      lib.rebuild();
      const al = lib.albumOf(s);
      if (al && !al.cover && d.cover && (await coverFromUrl(al, d.cover))) covers++;
    }
    // albums: one lookup each, fills year / genre / cover for every song on it
    const keys = [...new Set(songs.map((s) => albumKey(s)))];
    for (const k of keys.slice(0, 30)) {
      const a = lib.albumMap.get(k);
      if (!a || a.title === "Unknown Album") continue;
      if (a.cover && a.tracks.every((t) => t.year && t.genre)) continue;
      asked++;
      const m = await bestAlbumMatch(a);
      await pause();
      if (!m) continue;
      const d = m.year && m.genre ? m : await resultDetails(m);
      for (const t of a.tracks) {
        const f = {};
        if (!t.year && d.year) f.year = d.year;
        if (!t.genre && d.genre) f.genre = d.genre;
        if (Object.keys(f).length) { await lib.updateSong(t.id, f); filled++; }
      }
      if (!a.cover && d.cover && (await coverFromUrl(a, d.cover))) covers++;
    }
  } catch (e) { console.warn("auto-fill", e); }
  lib.rebuild();
  refresh();
  if (filled || covers) snack(`Filled in info for ${plural(filled, "song")}` + (covers ? ` and found ${plural(covers, "cover")}` : ""));
}

const close = (a, b) => { const n = (s) => String(s || "").toLowerCase().replace(/\(.*?\)|\[.*?\]/g, "").replace(/[^a-z0-9]+/g, ""); return n(a) && (n(a) === n(b) || n(a).includes(n(b)) || n(b).includes(n(a))); };
async function findCovers(loud, list) {
  const albums = (list || lib.albums).filter((a) => list || !a.cover).filter((a) => a.title !== "Unknown Album");
  if (!albums.length) { if (loud) snack("Every album already has a cover"); return; }
  let found = 0;
  const pb = loud && albums.length > 1 ? progressBox("Finding covers") : null;
  for (let i = 0; i < albums.length; i++) {
    const a = albums[i];
    pb?.set(i / albums.length, a.title);
    try {
      const q = new URLSearchParams({ term: `${a.artist} ${a.title}`, media: "music", entity: "album", limit: "15" });
      const r = (await getJsonHttp("https://itunes.apple.com/search?" + q)) || {};
      const hit = (r.results || []).find((x) => close(x.collectionName, a.title) && close(x.artistName, a.artist)) || (r.results || []).find((x) => close(x.collectionName, a.title));
      if (!hit?.artworkUrl100) continue;
      const img = await getBlob(hit.artworkUrl100.replace(/\/\d+x\d+bb\./, "/800x800bb."));
      if (await setAlbumCover(a, img)) found++;
    } catch (e) { console.warn("cover lookup", e); }
  }
  pb?.close();
  if (loud) snack(found ? `Found ${plural(found, "cover")}` : "Couldn't find covers online — check your internet connection");
}

// ============================================================ music packs
async function sharePack(songs, defTitle, playlist) {
  songs = songs.filter(Boolean);
  if (!songs.length) return snack("There's nothing to share");
  const size = songs.reduce((a, s) => a + (s.size || 0), 0);
  const title = await prompt("Share as Music Pack", `${plural(songs.length, "song")} · ${fmtSize(size)}. Songs keep all their info and covers.`, defTitle, "Saved to Downloads as  <name>-musicpack.zip", "Make Pack");
  if (!title) return;
  const pb = progressBox("Making music pack");
  try {
    const pack = await lib.buildPack(title, songs, playlist, (f) => pb.set(f * 0.5, "Packing songs…"));
    const saved = await saveFile(pack.blob, pack.name, (f) => pb.set(0.5 + f * 0.5, "Saving to Downloads…"));
    pb.close();
    snack(`Saved ${saved.name} to ${saved.where}`, saved.uri ? "Share" : null, () => shareFile(saved), 8000);
  } catch (e) {
    pb.close();
    snack("Couldn't make the pack: " + (e.message || e));
  }
}

// ---- YouTube / YouTube Music › Share › Add to AuraOS Music: the link comes straight in, no copying and pasting
if (native && Capacitor.getPlatform() === "android") {
  const ShareIn = registerPlugin("ShareIn");
  const take = (t) => { if (!t) return; diag.log("shared to the app: " + String(t).slice(0, 120)); setTimeout(() => addFromLink(String(t)), 300); };
  ShareIn.addListener("shared", ({ text }) => take(text));
  setTimeout(() => ShareIn.take().then(({ text }) => take(text)).catch(() => {}), 900);   // (shared while the app was closed)
}

// ---- drag out to other apps (Android): hold a song, album or playlist, then move your finger. A song goes as its
// real audio file; an album or playlist becomes a music pack, made when you drop it (a small window over the other
// app asks its name and shows progress: the only time the app draws over other apps, and only with permission).
const DragOut = native && Capacitor.getPlatform() === "android" ? registerPlugin("DragOut") : null;
const canDragOut = () => !!DragOut;
const dragJobs = new Map();
let dragWired = false;
function wireDragOut() {
  if (dragWired || !DragOut) return;
  dragWired = true;
  DragOut.addListener("packRequest", ({ token, title }) => makeDroppedPack(token, title));
  DragOut.addListener("packCancel", ({ token }) => { dragJobs.delete(token); nc.haptic("soft"); });
  DragOut.addListener("dropped", ({ token }) => { const j = dragJobs.get(token); if (j && !j.inApp) { nc.haptic("success"); snack(`Sent “${j.label}”`); } });
  // following the drag over our own screen: the drop targets light up under it
  DragOut.addListener("dragMove", ({ x, y }) => dropBar.hover(x, y));
  DragOut.addListener("dragLeave", () => dropBar.hover(-1, -1));
  DragOut.addListener("dragDrop", ({ token, x, y }) => dropBar.drop(token, x, y));
  DragOut.addListener("dragEnd", ({ token, result, here }) => {
    dropBar.hide();
    const j = dragJobs.get(token);
    if (j?.kind === "pack" && result && !here) askOverlayOnce();      // dropped into another app: offer to name packs there next time
  });
}

/** the targets that slide down while you drag: Save to Downloads, then the apps you share with most */
const targetCache = new Map();
const dropBar = {
  el: null, hot: null,
  async show(job) {
    this.hide(true);
    const save = h("div", { class: "dt", "data-save": "1" }, h("div", { class: "ic" }, icon("folder")), h("span", {}, "Save to Downloads"));
    const row = h("div", { class: "dts" }, save);
    this.el = h("div", { id: "dropbar" }, h("div", { class: "hint" }, `Drop “${job.label}” on an app`), row,
      h("div", { class: "sub" }, "…or into another app open beside this one"));
    document.body.append(this.el);
    requestAnimationFrame(() => this.el?.classList.add("on"));
    const key = job.mime;
    let list = targetCache.get(key);
    if (!list || Date.now() - list.at > 60000) {
      try { list = { at: Date.now(), t: (await DragOut.targets({ mime: job.mime, max: 6 })).targets || [] }; targetCache.set(key, list); }
      catch { list = { t: [] }; }
    }
    if (!this.el) return;
    for (const t of list.t) {
      const d = h("div", { class: "dt" }, h("div", { class: "ic" }, t.icon ? h("img", { src: t.icon, alt: "" }) : icon("share")), h("span", {}, t.label));
      d._t = t;
      row.append(d);
    }
  },
  at(x, y) {
    if (!this.el) return null;
    for (const d of this.el.querySelectorAll(".dt")) {
      const r = d.getBoundingClientRect();
      if (x >= r.left - 6 && x <= r.right + 6 && y >= r.top - 10 && y <= r.bottom + 10) return d;
    }
    return null;
  },
  hover(x, y) {
    const d = this.at(x, y);
    if (d === this.hot) return;
    this.hot?.classList.remove("hot");
    this.hot = d;
    if (d) { d.classList.add("hot"); nc.haptic("tick"); }
  },
  drop(token, x, y) {
    const d = this.at(x, y);
    const job = dragJobs.get(token);
    this.hide();
    if (!job) return;
    if (!d) return snack("Drop it on one of the apps at the top (or into an app open beside this one)");
    nc.haptic("thud");
    job.inApp = true;
    runDropTarget(token, job, d.dataset.save ? null : d._t);
  },
  hide(now) {
    const el = this.el;
    this.el = null; this.hot = null;
    if (!el) return;
    if (now) return el.remove();
    el.classList.remove("on");
    setTimeout(() => el.remove(), 320);
  },
};

/** dropped on one of our targets: save it, or hand it to that app (a pack is named and made here first) */
async function runDropTarget(token, job, target) {
  try {
    if (job.kind === "song") {
      if (!target) {
        const blob = await lib.fileBlob(job.song);
        const r = await saveFile(blob, job.name);
        return snack(`Saved “${job.label}”${r?.where ? " to " + r.where : ""}`);
      }
      await job.ready;                                   // (the file is written as soon as the drag starts)
      await DragOut.send({ token, pkg: target.pkg, cls: target.cls });
      return;
    }
    const name = await prompt("Name your music pack", `${plural(job.songs.length, "song")} from “${job.label}”`, job.label, "Pack name", target ? `Send to ${target.label}` : "Save");
    if (!name) return;
    const pb = progressBox(`Making “${name}”`);
    try {
      const pack = await lib.buildPack(name, job.songs, job.playlist ? name : null, (f, t) => pb.set(f * 0.7, t || `Packing ${plural(job.songs.length, "song")}…`));
      const file = `${safeFile(name)}-musicpack.zip`;
      if (!target) {
        pb.set(0.75, "Saving…");
        const r = await saveFile(pack.blob, file, (f) => pb.set(0.75 + f * 0.25, "Saving…"));
        pb.close();
        return snack(`Saved the music pack “${name}”${r?.where ? " to " + r.where : ""}`);
      }
      const uri = await writeCacheFile(`dragout/${token}/pack.zip`, pack.blob, (f) => pb.set(0.7 + f * 0.3, `Handing it to ${target.label}…`));
      await DragOut.ready({ token, path: uri, name: file });
      pb.close();
      await DragOut.send({ token, pkg: target.pkg, cls: target.cls });
    } catch (e) { pb.close(); throw e; }
  } catch (e) {
    diag.log("drop: " + (e.message || e));
    snack("Couldn't share that: " + (e.message || e));
  }
}

async function writeCacheFile(path, blob, onProgress) {
  const { Filesystem, Directory } = await import("@capacitor/filesystem");
  const CH = 3 * 1024 * 1024;
  for (let off = 0; off < blob.size || off === 0; off += CH) {
    const data = await toBase64(blob.slice(off, off + CH));
    if (off === 0) await Filesystem.writeFile({ directory: Directory.Cache, path, data, recursive: true });
    else await Filesystem.appendFile({ directory: Directory.Cache, path, data });
    onProgress?.(Math.min(1, (off + CH) / Math.max(1, blob.size)));
    if (blob.size === 0) break;
  }
  return (await Filesystem.getUri({ directory: Directory.Cache, path })).uri;
}
async function smallArt(coverId) {
  const c = lib.cover(coverId);
  if (!c?.smallBlob) return null;
  try { return await toBase64(c.smallBlob); } catch { return null; }
}
async function startDragOut(info) {
  if (!DragOut || !info) return;
  wireDragOut();
  nc.haptic("thud");
  try {
    if (info.kind === "song") {
      const s = info.song;
      const ext = (s.file?.match(/\.([a-z0-9]+)$/i)?.[1] || "mp3").toLowerCase();
      const name = `${s.artist ? s.artist + " - " : ""}${s.title}.${ext}`.replace(/[\\/:*?"<>|]+/g, "_");
      const mime = s.type || mimeFor(ext) || "audio/mpeg";
      const job = { kind: "song", song: s, label: s.title, name, mime };
      dropBar.show(job);
      const { token, started } = await DragOut.start({ kind: "song", name, mime, label: s.title, sub: s.artist || s.album || "",
        size: s.size || 0, art: await smallArt(s.cover) });
      if (!started) { dropBar.hide(); return snack("Couldn't pick that up"); }
      dragJobs.set(token, job);
      job.ready = (async () => {                          // the real file, written while you drag
        try {
          const blob = await lib.fileBlob(s);
          if (!blob) throw new Error("the song's file is missing");
          const uri = await writeCacheFile(`dragout/${token}/${name}`, blob);
          await DragOut.ready({ token, path: uri });
        } catch (e) { DragOut.fail({ token, error: e.message || String(e) }); diag.log("drag out: " + (e.message || e)); throw e; }
      })();
      job.ready.catch(() => {});
    } else {
      const songs = info.songs().filter(Boolean);
      if (!songs.length) return snack("There's nothing in it to share");
      const job = { kind: "pack", label: info.title, songs, playlist: info.playlist || null, mime: "application/zip" };
      dropBar.show(job);
      const { token, started } = await DragOut.start({ kind: "pack", name: `${safeFile(info.title)}-musicpack.zip`, mime: "application/zip",
        title: info.title, label: info.title, sub: info.sub || plural(songs.length, "song"), art: await smallArt(info.cover || songs.find((x) => x.cover)?.cover) });
      if (!started) { dropBar.hide(); return snack("Couldn't pick that up"); }
      dragJobs.set(token, job);
    }
  } catch (e) {
    dropBar.hide();
    diag.log("drag out: " + (e.message || e));
    snack("Couldn't drag that out: " + (e.message || e));
  }
}
const safeFile = (t) => String(t || "Music").replace(/[\\/:*?"<>|]+/g, "_").trim().slice(0, 80) || "Music";
async function makeDroppedPack(token, title) {
  const j = dragJobs.get(token);
  if (!j) return DragOut.fail({ token, error: "that drag has expired" });
  try {
    const pack = await lib.buildPack(title || j.label, j.songs, j.playlist ? (title || j.playlist) : null,
      (f) => DragOut.progress({ token, f: f * 0.6, text: `Packing ${plural(j.songs.length, "song")}…` }));
    const uri = await writeCacheFile(`dragout/${token}/pack.zip`, pack.blob, (f) => DragOut.progress({ token, f: 0.6 + f * 0.4, text: "Handing it over…" }));
    await DragOut.ready({ token, path: uri });
    nc.haptic("success");
    snack(`Sent the music pack “${title || j.label}” (${plural(pack.count, "song")})`);
  } catch (e) {
    DragOut.fail({ token, error: e.message || String(e) });
    nc.haptic("error");
    diag.log("drag out pack: " + (e.message || e));
  }
}
/** the "display over other apps" permission, asked for once, with the reason, before the first album/playlist drag */
async function askOverlayOnce() {
  if (!DragOut || lib.meta.settings.overlayAsked) return;
  try {
    const { granted } = await DragOut.canOverlay();
    if (granted) return;
    lib.meta.settings.overlayAsked = true; lib.saveMeta();
    if (await confirmBox("Name packs as you drop them?", "When you drag an album or playlist into another app, AuraOS Music can show a small window over that app to ask the pack's name and show its progress. That window is the only thing it ever shows over other apps. Android calls this permission “Display over other apps”.", "Allow"))
      await DragOut.askOverlay();
  } catch {}
}

async function saveFile(blob, name, onProgress) {
  if (!native) {
    const a = h("a", { href: URL.createObjectURL(blob), download: name });
    document.body.append(a); a.click(); a.remove();
    window.__lastPack = { blob, name };
    return { name, where: "Downloads" };
  }
  const { Filesystem, Directory } = await import("@capacitor/filesystem");
  try { await Filesystem.requestPermissions(); } catch {}
  const places = ios
    ? [[Directory.Documents, "", "Files › On My iPhone › AuraOS Music"], [Directory.Cache, "", "this app"]]
    : [[Directory.ExternalStorage, "Download/", "Downloads"], [Directory.Documents, "", "Documents"], [Directory.Cache, "", "this app"]];
  let lastErr;
  for (const [dir, prefix, where] of places) {
    try {
      let file = name;
      for (let n = 2; ; n++) {
        try { await Filesystem.stat({ directory: dir, path: prefix + file }); file = name.replace(/(\.zip)$/i, ` (${n})$1`); } catch { break; }
      }
      const path = prefix + file;
      const CH = 3 * 1024 * 1024;
      for (let off = 0; off < blob.size || off === 0; off += CH) {
        const data = await toBase64(blob.slice(off, off + CH));
        if (off === 0) await Filesystem.writeFile({ directory: dir, path, data, recursive: true });
        else await Filesystem.appendFile({ directory: dir, path, data });
        onProgress?.(Math.min(1, (off + CH) / blob.size));
        if (blob.size === 0) break;
      }
      const { uri } = await Filesystem.getUri({ directory: dir, path });
      return { name: file, where, uri };
    } catch (e) { lastErr = e; console.warn("save failed in", where, e); }
  }
  throw lastErr || new Error("Couldn't save the file");
}
function toBase64(b) {
  return new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(String(r.result).split(",")[1] || ""); r.onerror = () => rej(r.error); r.readAsDataURL(b); });
}
async function shareFile(saved) {
  try { const { Share } = await import("@capacitor/share"); await Share.share({ title: saved.name, files: [saved.uri], dialogTitle: "Share music pack" }); }
  catch (e) { console.warn(e); }
}

async function importPacks(files) {
  for (const f of files) {
    if (!/\.zip$/i.test(f.name)) { snack(`${f.name} isn't a music pack (.zip)`); continue; }
    const pb = progressBox("Adding " + f.name.replace(/-musicpack\.zip$/i, ""));
    try {
      const r = await lib.importPack(f, (x, t) => pb.set(x, t));
      pb.close();
      if (!r.added && !r.skipped) { snack(r.failed ? `Couldn't read the ${plural(r.failed, "song")} in that pack: ${r.firstError}` : "There were no songs in that file", null, null, 12000); continue; }
      let msg = `Added “${r.title}”: ${plural(r.added, "song")}`;
      if (r.skipped) msg += ` (${r.skipped} you already had)`;
      if (r.failed) msg += ` · ${r.failed} couldn't be read (${r.firstError})`;
      if (r.playlist) msg += ` and the playlist “${r.playlist}”`;
      snack(msg, r.playlist ? "Open" : null, () => push({ type: "playlist", name: r.playlist }), 7000);
    } catch (e) {
      pb.close();
      snack("Couldn't add that pack: " + (e.message || e));
    }
    dropPicked(f.picked);                  // the copy the picker made isn't needed any more
  }
  refresh();
  lib.repairCovers().then((n) => { if (n) refresh(); findCovers(false); });
}
// ---- iPhone: when you leave the app or lock the phone, the music carries on in the phone's own player
// (the web view can't keep playing in the background). Everything it needs is ready ahead of time, because once the
// app is on its way out there may be no time to prepare anything.
function setupBackgroundPlayback() {
  const arts = {};                                   // cover id → small JPEG (base64), for the lock screen
  let bgActive = false, win = 0;
  const toB64 = (blob) => new Promise((res) => { const r = new FileReader(); r.onload = () => res(String(r.result).split(",")[1] || ""); r.onerror = () => res(""); r.readAsDataURL(blob); });
  const fillArts = async () => {                     // covers for the songs around the current one
    const ids = [];
    for (let i = Math.max(0, player.index - 5); i < Math.min(player.queue.length, player.index + 60); i++) {
      const c = lib.songs.get(player.queue[i])?.cover;
      if (c && !arts[c] && !ids.includes(c)) ids.push(c);
    }
    for (const id of ids.slice(0, 40)) { const cv = lib.cover(id); if (cv?.smallBlob) arts[id] = await toB64(cv.smallBlob); }
  };
  for (const ev of ["track", "state"]) player.addEventListener(ev, () => setTimeout(fillArts, 300));
  const payload = () => {
    win = Math.max(0, player.index - 50);
    const ids = player.queue.slice(win, player.index + 800);
    const items = ids.map((id) => { const s = lib.songs.get(id); return s ? { path: songFileURLNow(s), title: s.title || "", artist: s.artist || "", album: s.album || "", duration: s.duration || 0, art: s.cover || "" } : { path: "" }; });
    const used = {};
    for (const it of items) if (it.art && arts[it.art]) used[it.art] = arts[it.art];
    return { items, index: player.index - win, position: player.audio.currentTime || 0, repeat: player.repeat, arts: used };
  };
  const quietWeb = () => {                           // so the lock screen talks to the native player only
    try { navigator.mediaSession.metadata = null; for (const a of ["play", "pause", "nexttrack", "previoustrack", "seekto", "stop", "seekforward", "seekbackward"]) navigator.mediaSession.setActionHandler(a, null); } catch {}
  };
  const leave = () => {
    if (bgActive || !player.playing || !player.current) return;
    const o = payload();
    if (!o.items[o.index]?.path) return;             // (a song from before songs were stored as files)
    player.pause();
    player.bgActive = true;
    quietWeb();
    bgActive = true;
    nc.bgStart(o).then((ok) => { diag.log(`background: native player ${ok ? "took over" : "couldn't start"}`); if (!ok) { bgActive = false; player.bgActive = false; player.setupSession(); player.updateSession(true); player.play(); } });
  };
  const back = async () => {
    if (!bgActive) return;
    bgActive = false;
    const st = await nc.bgStop();
    player.bgActive = false;
    player.setupSession();
    if (!st?.active) return;
    const i = win + (st.index | 0);
    diag.log(`background: back in the app at song ${i + 1}, ${Math.round(st.position)} s, ${st.playing ? "playing" : "paused"}`);
    const resume = () => { player.seek(st.position || 0); if (st.playing) player.play(); player.updateSession(true); };
    if (i !== player.index && i >= 0 && i < player.queue.length) {
      player.index = i;
      player.emit("willtrack", { dir: 1 });
      await player.load(1, false);
      if (player.audio.readyState >= 1) resume(); else player.audio.addEventListener("loadedmetadata", resume, { once: true });
    } else resume();
  };
  document.addEventListener("visibilitychange", () => (document.hidden ? leave() : back()));
  addEventListener("pagehide", leave);
}

/** "Add Music Pack": on iPhone, Apple's Files browser, and the pack is read from disk piece by piece. */
async function pickPacks() {
  if (!nc.on) return $("#pickPack").click();
  try { const files = await nc.pickPacks(); if (files.length) importPacks(files); }
  catch (e) { console.warn(e); snack("Couldn't open the pack: " + (e.message || e)); }
}
/** "Add Songs": on iPhone, Apple's Files browser (music only); elsewhere the normal file picker. */
async function pickSongs() {
  if (!nc.on) return $("#pickAudio").click();
  try { const files = await nc.pickAudio(); if (files.length) importAudio(files); }
  catch (e) { console.warn(e); $("#pickAudio").click(); }
}
async function importAudio(files) {
  if (!files.length) return;
  const pb = progressBox("Adding songs");
  const r = await lib.importFiles(files, (f, t) => pb.set(f, t));
  pb.close();
  for (const f of files) dropPicked(f.picked);
  setTimeout(() => autoFill(r.songs || []), 4600);
  if (!r.added && !r.skipped && !r.failed && r.ignored) { snack("Those aren't music files (mp3, m4a, flac, wav, ogg…)"); return; }
  let msg = `Added ${plural(r.added, "song")}`;
  if (r.skipped) msg += ` · ${r.skipped} already in your library`;
  if (r.failed) msg += ` · ${r.failed} couldn't be read (${r.firstError})`;
  snack(msg);
  refresh();
}

// ---- YouTube sign-in for Add from Link (the "enter a code at google.com/device" kind, like a TV)
const ytSignedInNow = () => { try { return !!localStorage.getItem("auraos-yt-oauth"); } catch { return false; } };
let ytSigningIn = false;
async function youtubeAccount() {
  const yt = await import("./youtube.js");
  const again = () => { if (nc.on) nc.settings(settingsModel()); else if (stack.at(-1)?.type === "settings") refresh(); };
  if (yt.ytSignedIn()) {
    if (await confirmBox("Sign out of YouTube?", "Add from Link will stop using your YouTube account. You can sign in again any time.", "Sign Out")) {
      await yt.ytSignOut(); snack("Signed out of YouTube"); again();
    }
    return;
  }
  if (ytSigningIn) return snack("Already waiting for you to sign in…");
  ytSigningIn = true;
  const pb = progressBox("Signing in to YouTube");
  pb.set(0, "Getting a sign-in code…");
  try {
    await yt.ytSignIn(async (d) => {
      const url = d.verification_url || "https://www.google.com/device";
      try { await navigator.clipboard.writeText(d.user_code); } catch {}
      pb.set(0.5, `Code ${d.user_code} — enter it at ${url.replace(/^https?:\/\//, "")} (copied)`);
      diag.step = `Your code: ${d.user_code}`;
      const go = await confirmBox("Sign in to YouTube", `On the page that opens, sign in with your Google account and enter this code:\n\n${d.user_code}\n\n(It's copied — just paste it.) Then come back here; the app finishes by itself.`, "Open Sign-In Page");
      if (go) window.open(url, "_blank");
    });
    pb.close();
    snack("Signed in to YouTube — Add from Link will use your account");
    nc.haptic?.("success");
  } catch (e) {
    pb.close();
    diag.log("YouTube sign-in: " + (e.message || e));
    snack("Couldn't sign in to YouTube: " + (e.message || e), "Details", showDiagnostics, 10000);
  } finally {
    ytSigningIn = false;
    again();
  }
}

// ---- EXPERIMENTAL: add a song / album / playlist from a YouTube or YouTube Music link (see youtube.js)
let linkBusy = false;
async function addFromLink(shared) {
  if (linkBusy) return snack("Already adding from a link…");
  const text = typeof shared === "string" ? shared
    : await prompt("Add from Link", "Paste a YouTube or YouTube Music link to a song, album or playlist. Experimental: YouTube sometimes blocks this.", "", "", "Add");
  if (!text) return;
  const yt = await import("./youtube.js");
  if (!yt.parseLink(text)) { snack("That isn't a YouTube or YouTube Music link"); return; }
  linkBusy = true;
  const pb = progressBox("Adding from YouTube");
  try {
    const r = await yt.importLink(text, lib, (f, t, sub) => pb.set(f, t, sub));
    pb.close();
    setTimeout(() => autoFill(r.songs || []), 4600);
    if (!r.added && !r.skipped) { snack(`Couldn't add from that link${r.firstError ? ": " + r.firstError : ""}`, "Details", showDiagnostics, 12000); return; }
    let msg = `Added ${plural(r.added, "song")}`;
    if (r.skipped) msg += ` · ${r.skipped} already in your library`;
    if (r.failed) msg += ` · ${r.failed} couldn't be downloaded (${r.firstError})`;
    if (r.playlist) msg += ` and the playlist “${r.playlist}”`;
    snack(msg, r.playlist ? "Open" : null, () => push({ type: "playlist", name: r.playlist }), 8000);
  } catch (e) {
    pb.close();
    diag.log("YouTube: " + (e.message || e));
    snack("Couldn't add from that link: " + (e.message || e), "Details", showDiagnostics, 12000);
  } finally {
    linkBusy = false;
    refresh();
  }
}

// ============================================================ browse: search your library, with recent searches
const browse = { q: "" };
const recents = () => (lib.meta.recent ||= []);
function addRecent(q) {
  q = (q || "").trim();
  if (q.length < 2) return;
  const list = recents().filter((x) => x.toLowerCase() !== q.toLowerCase());
  list.unshift(q);
  lib.meta.recent = list.slice(0, 15);
  lib.saveMeta();
}

function pageBrowse() {
  const p = pageShell("Search", { actions: [ib("settings", () => push({ type: "settings" }), "", "Settings")] });
  const input = h("input", { class: "browse-q", placeholder: "Songs, albums, artists, genres", value: browse.q, enterkeyhint: "search" });
  const clearBtn = ib("close", () => { input.value = ""; run(); input.focus(); }, "clear-q", "Clear");
  const results = h("div");
  const run = () => {
    browse.q = input.value;
    clearBtn.style.visibility = input.value ? "visible" : "hidden";
    results.replaceChildren(...browseResults(input.value.trim(), (q) => { input.value = q; run(); addRecent(q); }));
  };
  let t = 0;
  input.addEventListener("input", () => { clearTimeout(t); t = setTimeout(run, 120); });
  input.addEventListener("keydown", (e) => { if (e.key === "Enter") { addRecent(input.value); input.blur(); } });
  results.addEventListener("click", () => addRecent(input.value), true);      // opening a result remembers the search
  p.append(h("div", { class: "search" }, icon("search"), input, clearBtn), results);
  run();
  return p;
}

function browseResults(q, useRecent) {
  // nothing typed: recent searches (if any), then everything in the library, laid out just like search results
  const out = [];
  if (!q) {
    if (!lib.songs.size) return [h("div", { class: "empty" }, h("div", { class: "big" }, icon("search")), h("h3", {}, "Search your music"),
      h("div", {}, "Find songs, albums, artists and genres. Play a song from here and you'll get a mix of similar ones."))];
    const list = recents();
    if (list.length) {
      out.push(h("div", { class: "section-t row-t" }, h("span", {}, "Recent Searches"),
        h("button", { class: "link", onclick: () => { lib.meta.recent = []; lib.saveMeta(); refresh(); } }, "Clear")));
      for (const r of list.slice(0, 5)) {
        const row = h("div", { class: "row recent" }, h("div", { class: "num" }, icon("history")), h("div", { class: "meta" }, h("div", { class: "t" }, r)),
          ib("close", (e) => { e.stopPropagation(); lib.meta.recent = recents().filter((x) => x !== r); lib.saveMeta(); refresh(); }, "", "Remove"));
        press(row, () => useRecent(r));
        out.push(row);
      }
    }
  }
  const low = (q || "").toLowerCase();
  const m = (x) => !low || (x || "").toLowerCase().includes(low);
  const songs = lib.sortedSongs.filter((s) => m(s.title) || m(s.artist) || m(s.album) || m(s.genre));
  const albums = lib.albums.filter((a) => m(a.title) || m(a.artist));
  const artists = lib.artists().filter((a) => m(a.name));
  const all = !q;
  const row = (title, n, cards) => out.push(h("div", { class: "section-t" }, all ? `${title} · ${n}` : title), h("div", { class: "hscroll" }, cards));
  if (artists.length) row("Artists", artists.length, artists.slice(0, all ? 400 : 40).map(artistCard));
  if (albums.length) row("Albums", albums.length, albums.slice(0, all ? 400 : 40).map(albumCard));
  if (songs.length) { const list = songs.slice(0, all ? 600 : 100); row("Songs", songs.length, list.map((x, i) => songCard(x, list, i, all ? "Library" : "Search"))); }
  if (!out.length) out.push(h("div", { class: "empty" }, `Nothing in your library matches “${q}”`));
  return out;
}

// ============================================================ mixes: a song you searched for, or a short album, keeps going with similar songs
function similarSongs(seeds, n = 25) {
  const seedIds = new Set(seeds.map((s) => s.id));
  const names = (s) => [...(s.artist || "").split(/\s*,\s*/), s.albumartist || ""].map((x) => x.trim().toLowerCase()).filter(Boolean);
  const artists = new Set(seeds.flatMap(names));
  const genres = new Set(seeds.flatMap((s) => genresOf(s).map((g) => g.toLowerCase())));
  const years = seeds.map((s) => parseInt(s.year, 10)).filter(Boolean);
  const year = years.length ? years.reduce((a, b) => a + b, 0) / years.length : 0;
  const albums = new Set(seeds.map((s) => albumKey(s)));
  const scored = [];
  for (const s of lib.songs.values()) {
    if (seedIds.has(s.id)) continue;
    let sc = 0;
    if (names(s).some((a) => artists.has(a))) sc += 3;
    const g = genresOf(s).map((x) => x.toLowerCase());
    const shared = g.filter((x) => genres.has(x)).length;
    sc += shared * 2;
    if (!shared && g.some((x) => [...genres].some((G) => x.includes(G) || G.includes(x)))) sc += 1;   // "electronic pop" ~ "pop"
    const y = parseInt(s.year, 10);
    if (year && y) sc += Math.max(0, 1.5 - Math.abs(y - year) / 4);
    if (albums.has(albumKey(s))) sc -= 1.5;            // keep going with other albums, not the same one
    if (lib.isFav(s.id)) sc += 0.5;
    scored.push([s, sc + Math.random() * 1.2]);          // a little randomness so every mix is different
  }
  scored.sort((a, b) => b[1] - a[1]);
  const out = [], perArtist = new Map();
  const tkey = (s) => (s.title || "").toLowerCase().replace(/\(.*?\)|\[.*?\]|[^a-z0-9]+/g, "") + "|" + (names(s)[0] || "");
  const seen = new Set(seeds.map(tkey));                 // the same song on a single and on its album counts once
  for (const [s, sc] of scored) {
    if (out.length >= n) break;
    if (sc < 1 && out.length >= 8) break;
    if (seen.has(tkey(s))) continue;
    seen.add(tkey(s));
    const a = names(s)[0] || "";
    if ((perArtist.get(a) || 0) >= 4) continue;          // variety: no more than 4 from one artist
    perArtist.set(a, (perArtist.get(a) || 0) + 1);
    out.push(s);
  }
  return out;
}

let mixToken = 0;
async function playWithMix(list, start, label) {
  const token = ++mixToken;
  player.playList(list.map((x) => x.id), start);      // keeps your shuffle setting
  ctxLabel = label;
  snack("Creating mix…", null, null, 2500);
  await new Promise((r) => setTimeout(r, 650));
  if (token !== mixToken) return;
  const extra = similarSongs(list).map((s) => s.id).filter((id) => !player.queue.includes(id));
  if (!extra.length) { snack("No similar songs in your library yet — add more music for mixes"); return; }
  player.queue.push(...extra);
  player.base.push(...extra);
  player.emit("queue");
  const seed = list.length === 1 ? list[0].title : list[0].album || label;
  ctxLabel = `${seed} Mix`;
  if (npOpen) np.ctx.replaceChildren(document.createTextNode("PLAYING FROM"), h("b", {}, ctxLabel));
  snack(`Mix ready · ${plural(extra.length, "similar song")} up next`, "Up Next", () => { openNp(); showQueue(); });
}

// ============================================================ mini player
function dockFlip(change) {
  const d = $("#dock");
  const before = d.getBoundingClientRect().top;
  change();
  const after = d.getBoundingClientRect().top;
  if (motion() && Math.abs(before - after) > 1 && !matchMedia("(orientation: landscape) and (min-width: 640px)").matches) {
    d.animate([{ transform: `translateY(${before - after}px)` }, { transform: "none" }], { duration: 420, easing: "cubic-bezier(.34,1.25,.64,1)" });
    $("#mini").animate([{ opacity: 0 }, { opacity: 1 }], { duration: 260, delay: 80, fill: "backwards" });
  }
}
function updateMini() {
  const m = $("#mini");
  const s = player.current;
  if (nc.on) {
    if (!s) nc.update({ song: false, playing: false });
    else { nc.update({ song: true, title: s.title, artist: s.artist || s.album || "", playing: player.playing }); nc.art((s.cover || "none") + ":" + (lib.cover(s.cover)?.cv || 1), lib.cover(s.cover)?.small); }
  }
  if (!s) { if (!m.hidden) dockFlip(() => (m.hidden = true)); return; }
  if (m.hidden) { dockFlip(() => (m.hidden = false)); setTimeout(() => miniTuck.sync(), 30); }
  if (m.dataset.id !== s.id) {
    m.dataset.id = s.id;
    m.innerHTML = "";
    m.append(h("canvas"), art(s.cover, { label: s.album }), h("div", { class: "meta" }, h("div", { class: "t" }, s.title), h("div", { class: "s" }, s.artist || s.album || "")),
      h("button", { class: "ib prev", onclick: (e) => { e.stopPropagation(); miniDir = -1; player.prev(); } }, icon("prev")),
      h("button", { class: "ib pp", onclick: (e) => { e.stopPropagation(); player.toggle(); } }, icon(player.playing ? "pause" : "play")),
      h("button", { class: "ib nx", onclick: (e) => { e.stopPropagation(); miniDir = 1; player.next(); } }, icon("next")),
      h("div", { class: "times" }, h("span", { class: "t0" }, "0:00"), h("span", {}, " / "), h("span", { class: "t1" }, fmtTime(s.duration))),
      h("div", { class: "bar" }, h("b")));
    const dir = miniDir; miniDir = 0;
    if (dir && motion()) for (const el of [m.querySelector(".art"), m.querySelector(".meta")])
      el.animate([{ transform: `translateX(${dir * 70}px)`, opacity: 0 }, { transform: "none", opacity: 1 }], { duration: 300, easing: "cubic-bezier(.22,1,.36,1)" });
  } else {
    m.querySelector(".pp").replaceChildren(icon(player.playing ? "pause" : "play"));
  }
}
let miniDir = 0;
function setupMiniGestures() {
  const m = $("#mini");
  let sx = 0, sy = 0, dx = 0, dy = 0, down = false, moved = false, coverMode = false, openDrag = false, upV = 0, upY = 0, upT = 0;
  const parts = () => [m.querySelector(".art"), m.querySelector(".meta")].filter(Boolean);
  const sideways = () => matchMedia("(orientation: landscape) and (min-width: 640px)").matches;
  m.addEventListener("pointerdown", (e) => {
    if (e.target.closest("button")) return;
    down = true; moved = false; openDrag = false; sx = e.clientX; sy = e.clientY; dx = dy = 0; upY = e.clientY; upT = performance.now(); upV = 0;
    coverMode = false;
    m.setPointerCapture(e.pointerId);
  });
  const miniEdge = edge();
  m.addEventListener("pointerdown", () => miniEdge.reset());
  m.addEventListener("pointermove", (e) => {
    if (!down) return;
    dx = e.clientX - sx; dy = e.clientY - sy;
    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true;
    if (openDrag || (!npOpen && !coverMode && moved && dy < -10 && Math.abs(dy) > Math.abs(dx) * 1.2 && player.current)) {
      if (!openDrag) { openDrag = true; for (const el of parts()) { el.style.transform = ""; el.style.opacity = ""; } prepareNp(); morph.rect = anchorRect(); morph.set(1); morph.grab(); morph.start = 1; }
      const now = performance.now(); upV = (e.clientY - upY) / Math.max(1, now - upT); upY = e.clientY; upT = now;
      morph.drag(dy);
      return;
    }
    if (coverMode) {
      // untucked: only a swipe right does anything; tucked: only a swipe left
      const ok = miniTuck.tucked ? dx < 0 : dx > 0;
      const a = m.querySelector(".art");
      a.style.transition = "none";
      a.style.transform = `translateX(${ok ? dx * 0.45 : dx * 0.06}px)`;
      return;
    }
    if (Math.abs(dx) > Math.abs(dy)) miniEdge(Math.abs(dx) > 60);
    if (Math.abs(dx) > Math.abs(dy)) for (const el of parts()) { el.style.transition = "none"; el.style.transform = `translateX(${dx * 0.7}px) scale(var(--artz, 1))`; el.style.opacity = String(1 - Math.min(0.7, Math.abs(dx) / 220)); }
  });
  const EASE_OUT = "cubic-bezier(.22,1,.36,1)";          // smooth glide, no overshoot
  const up = () => {
    if (!down) return;
    down = false;
    if (openDrag) {                       // let go: open fully, or (if barely pulled) sink back into the bubble
      openDrag = false;
      morph.v = (upV * 1000) / morph.dist();
      if (morph.p < 0.72 || upV < -0.5) morph.animate(0, SPRING_OPEN, () => morph.clear());
      else closeNp({ fromDrag: true });
      return;
    }
    if (coverMode) {
      const a = m.querySelector(".art");
      a.style.transition = "transform .3s cubic-bezier(.22,1,.36,1)"; a.style.transform = "";
      if (!moved) return openNp();
      const ok = miniTuck.tucked ? dx < 0 : dx > 0;
      if (ok && Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy)) { nc.haptic("soft"); miniTuck.pin(!miniTuck.tucked); }    // pinned tucked / back out
      return;
    }
    const els = parts();
    const swiped = moved && Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy);
    const commit = swiped && player.peek(dx < 0 ? 1 : -1) >= 0;
    if (swiped && !commit) {      // nothing to skip to: the song starts again, the bar just glides back
      for (const el of els) { el.style.transition = `transform .28s ${EASE_OUT}, opacity .28s`; el.style.transform = ""; el.style.opacity = ""; }
      dx < 0 ? player.next() : player.prev();
      return;
    }
    if (!commit) {
      for (const el of els) { el.style.transition = `transform .28s ${EASE_OUT}, opacity .28s`; el.style.transform = ""; el.style.opacity = ""; }
      if (!moved) openNp();
      return;
    }
    // keep going the way the finger went, then the next song slides in from the other side
    nc.haptic("tap");
    const dir = dx < 0 ? 1 : -1;
    const W = m.clientWidth;
    const id = m.dataset.id;
    let n = els.length;
    for (const el of els) {
      const from = el.style.transform || "none", op = el.style.opacity || "1";
      el.style.transition = "none";
      el.animate([{ transform: from, opacity: op }, { transform: `translateX(${-dir * W * 0.7}px)`, opacity: 0 }],
        { duration: 170, easing: "cubic-bezier(.4,0,1,1)", fill: "forwards" }).onfinish = () => {
        if (--n) return;
        miniDir = dir;
        dir > 0 ? player.next() : player.prev();
        // "back" near the start of a song just restarts it: slide the same song back in
        setTimeout(() => {
          if (m.dataset.id !== id) return;
          for (const e2 of parts()) {
            e2.getAnimations().forEach((an) => an.cancel());
            e2.style.transform = ""; e2.style.opacity = "";
            e2.animate([{ transform: `translateX(${dir * 70}px)`, opacity: 0 }, { transform: "none", opacity: 1 }], { duration: 300, easing: EASE_OUT });
          }
          miniDir = 0;
        }, 350);
      };
    }
  };
  m.addEventListener("pointerup", up);
  m.addEventListener("pointercancel", () => { down = false; for (const el of parts()) { el.style.transform = ""; el.style.opacity = ""; } });
}
const progCache = { mini: -1, np: -1, sec: -1, nps: -1 };
function updateProgress() {
  // called every frame while something is visible: only a GPU transform changes per frame, text only once a second
  const a = player.audio, f = a.duration ? Math.min(1, a.currentTime / a.duration) : 0;
  const fr = Math.round(f * 2000) / 2000;
  const b = $("#mini .bar b");
  if (b && fr !== progCache.mini) { progCache.mini = fr; b.style.transform = `scaleX(${fr})`; }
  const sec = Math.floor(a.currentTime);
  const t0 = $("#mini .t0");
  if (t0 && sec !== progCache.sec && innerWidth > innerHeight) { progCache.sec = sec; t0.textContent = fmtTime(a.currentTime); $("#mini .t1").textContent = fmtTime(a.duration || player.current?.duration); }
  if (npOpen && !seeking) {
    if (fr !== progCache.np) { progCache.np = fr; np.fill.style.transform = `scaleX(${fr})`; }
    if (sec !== progCache.nps) { progCache.nps = sec; np.t0.textContent = fmtTime(a.currentTime); np.t1.textContent = "-" + fmtTime((a.duration || 0) - a.currentTime); }
  }
}

// ============================================================ now playing
let npOpen = false, seeking = false, flipped = false;
const np = {};
let lyrView;
function buildNp() {
  const el = $("#np");
  np.bg = h("div", { class: "bg" });
  np.bg2l = h("div", { class: "bg", style: { opacity: 0 } });
  np.glow = h("div", { class: "glowpulse" });
  np.ctx = h("div", { class: "ctx" });
  np.cover = h("div", { class: "cover" }, h("div", { class: "face front" }), h("div", { class: "face back" }, h("div", { class: "blur" }), h("div", { class: "det" })));
  np.stage = h("div", { class: "stage" }, np.cover);
  np.title = h("div", { class: "t" });
  np.artist = h("div", { class: "s" });
  np.fav = ib("heart", () => { const s = player.current; if (s) { const on = lib.toggleFav([s.id]); updateNpFav(); nc.haptic(on ? "success" : "soft"); snack(on ? "Added to Favorites" : "Removed from Favorites"); refresh(); } });
  np.fill = h("div", { class: "fillb" });
  np.seek = h("div", { class: "seek" }, h("div", { class: "track" }, np.fill));
  np.t0 = h("span", {}, "0:00"); np.t1 = h("span", {}, "0:00");
  np.shuffle = ib("shuffle", () => player.toggleShuffle(), "small dim");
  np.repeat = ib("repeat", () => player.cycleRepeat(), "small dim");
  np.pp = h("button", { class: "pp", onclick: () => player.toggle() }, icon("play"));
  np.viz = h("canvas", { class: "vizbg" });
  np.lyrBtn = ib("lyrics", () => toggleLyrics(), "", "Lyrics");
  np.lyrCanvas = h("canvas");
  np.lyrHeadArt = h("div", { class: "art", onclick: () => toggleLyrics(false), role: "button", "aria-label": "Hide lyrics" });
  np.lyrHeadT = h("div", { class: "t" }); np.lyrHeadS = h("div", { class: "s" });
  np.lyrSrc = h("span", { class: "src" });
  np.lyrOff = h("span");
  const nudge = (d) => {
    const s = player.current; if (!s) return;
    const o = lib.meta.settings.offsets;
    o[s.id] = Math.round(((o[s.id] || 0) + d) * 10) / 10;
    if (!o[s.id]) delete o[s.id];
    lib.saveMeta(); showOffset();
  };
  const wrap = h("div", { class: "wrap" },
    h("div", { class: "top" }, ib("down", () => closeNp(), "", "Close"), np.ctx, ib("more", () => player.current && songMenu(player.current))),
    np.stage,
    h("div", { class: "lyr" }, h("div", { class: "lhead" }, np.lyrHeadArt, h("div", { class: "meta" }, np.lyrHeadT, np.lyrHeadS)), np.lyrCanvas,
      h("div", { class: "lfoot" }, np.lyrSrc, np.lyrOff, h("button", { onclick: () => nudge(-0.5) }, "Earlier"), h("button", { onclick: () => nudge(0.5) }, "Later"),
        h("button", { onclick: () => player.current && lyricsPicker(player.current) }, "Wrong lyrics?"))),
    h("div", { class: "titles" }, h("div", { class: "meta" }, np.title, np.artist), np.fav),
    np.seek, h("div", { class: "times" }, np.t0, np.t1),
    h("div", { class: "ctrls" }, np.shuffle, ib("prev", () => player.prev()), np.pp, ib("next", () => player.next()), np.repeat),
    h("div", { class: "extras" }, np.lyrBtn, ib("eq", () => { closeNp(); push({ type: "settings" }); }, "", "Equalizer"), ib("queue", () => showQueue(), "", "Queue")));
  np.wrapEl = wrap;
  el.append(np.bg, np.bg2l, fog.el(), h("div", { class: "bg2" }), np.glow, np.viz, wrap);
  lyrView = new LyricsView(np.lyrCanvas);
  np.artist.onclick = () => { const s = player.current; if (s?.artist) { closeNp(); push({ type: "artist", name: s.artist.split(",")[0].trim() }); } };

  // seek bar
  const seekTo = (e) => {
    const r = np.seek.getBoundingClientRect();
    const f = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    np.fill.style.transform = `scaleX(${f})`;
    progCache.np = -1; progCache.nps = -1;
    np.t0.textContent = fmtTime(f * (player.audio.duration || 0));
    return f;
  };
  np.seek.addEventListener("pointerdown", (e) => { seeking = true; np.seek.classList.add("active"); np.seek.setPointerCapture(e.pointerId); seekTo(e); e.stopPropagation(); });
  let seekStep = -1;
  np.seek.addEventListener("pointermove", (e) => { if (!seeking) return; const f = seekTo(e); const st = Math.floor(f * 10); if (st !== seekStep) { if (seekStep >= 0) nc.haptic("tick"); seekStep = st; } });
  np.seek.addEventListener("pointerup", (e) => { if (!seeking) return; const f = seekTo(e); seeking = false; np.seek.classList.remove("active"); seekStep = -1; player.seek(f * (player.audio.duration || 0)); nc.haptic("soft"); });

  // cover: tap to flip, swipe to skip
  let sx = 0, sy = 0, dx = 0, dy = 0, down = false, axis = "";
  const trail = [];
  let detTap = null;           // flipped: a touch in the details scrolls them (and a plain tap flips back) — never drags the player
  np.cover.addEventListener("pointerdown", (e) => {
    if (flipped && e.target.closest(".det")) { detTap = { x: e.clientX, y: e.clientY }; e.stopPropagation(); return; }
    down = true; sx = e.clientX; sy = e.clientY; dx = dy = 0; axis = ""; trail.length = 0;
    np.cover.setPointerCapture(e.pointerId); np.cover.style.transition = "none"; e.stopPropagation();
  });
  const coverEdge = edge();
  np.cover.addEventListener("pointerdown", () => coverEdge.reset());
  np.cover.addEventListener("pointermove", (e) => {
    if (!down) return;
    dx = e.clientX - sx; dy = e.clientY - sy;
    if (!axis && Math.hypot(dx, dy) > 8) axis = Math.abs(dy) > Math.abs(dx) && dy > 0 ? "y" : "x";
    if (axis === "y") {                       // swipe down: your finger drives the shrink into the bubble, stage by stage
      trail.push([performance.now(), e.clientY]);
      while (trail.length > 2 && performance.now() - trail[0][0] > 100) trail.shift();
      if (trail.length === 1) morph.grab();
      morph.drag(dy);
      return;
    }
    if (axis === "x" && !flipped) { np.cover.style.transform = `translateX(${dx}px) rotate(${dx / 40}deg)`; coverEdge(Math.abs(dx) > 80); }   // flipped: no sideways swipe
  });
  const glideBack = () => {        // smooth return to the middle, no bounce
    np.cover.style.transition = "transform .32s cubic-bezier(.22,1,.36,1)";
    np.cover.style.transform = "";
    setTimeout(() => (np.cover.style.transition = ""), 340);
  };
  // iPhone hands a touch over to scrolling (and cancels it) unless told otherwise: if that happens mid-drag, finish the drag
  np.cover.addEventListener("pointercancel", () => {
    detTap = null;              // (the details started scrolling)
    if (!down) return;
    down = false;
    np.cover.style.transform = "";
    if (axis === "y") morph.release(0); else glideBack();
  });
  // one tap flips the cover (after a moment, in case it's a double tap); two quick taps like the song
  let coverT = 0, coverLast = 0;
  const coverTap = (e) => {
    const now = performance.now();
    if (now - coverLast < 300) { clearTimeout(coverT); coverLast = 0; likeSong(player.current, e.clientX, e.clientY); return; }
    coverLast = now;
    clearTimeout(coverT);
    coverT = setTimeout(() => { nc.haptic("soft"); flip(); }, 260);
  };
  np.cover.addEventListener("pointerup", (e) => {
    if (detTap) {
      const moved = Math.hypot(e.clientX - detTap.x, e.clientY - detTap.y);
      detTap = null;
      if (moved < 8) coverTap(e);
      return;
    }
    if (!down) return;
    down = false;
    if (axis === "y") {
      const [t0, y0] = trail[0] || [0, 0], [t1, y1] = trail[trail.length - 1] || [1, 0];
      const v = (y1 - y0) / Math.max(8, t1 - t0);            // px per ms
      np.cover.style.transform = "";
      morph.release(v);
      return;
    }
    np.cover.style.transition = "";
    if (flipped && axis === "x") { np.cover.style.transform = ""; return; }   // details showing: sideways does nothing
    if (Math.abs(dx) < 8) {
      np.cover.style.transform = "";
      if ($("#np").classList.contains("lyrics")) return toggleLyrics(false);   // lyrics on: tapping the cover turns them off
      coverTap(e); return;
    }
    if (Math.abs(dx) <= 70) return glideBack();
    if (player.peek(dx < 0 ? 1 : -1) < 0) { glideBack(); dx < 0 ? player.next() : player.prev(); return; }  // same song again
    swipeOffset = dx;
    nc.haptic("tap");
    dx < 0 ? player.next() : player.prev();
  });

  // drag the sheet down to close
  let ty = 0, sdy = 0, dragging = false;
  let grabbed = false;
  wrap.addEventListener("pointerdown", (e) => {
    if (e.target.closest("button, .seek, .cover, canvas")) return;
    dragging = true; grabbed = false; ty = e.clientY; sdy = 0;
  });
  let lastY = 0, lastT = 0, vY = 0;
  window.addEventListener("pointermove", (e) => {
    if (!dragging) return;
    const now = performance.now();
    vY = (e.clientY - lastY) / Math.max(1, now - lastT); lastY = e.clientY; lastT = now;
    sdy = e.clientY - ty;
    if (!grabbed && Math.abs(sdy) < 6) return;
    if (!grabbed) { grabbed = true; morph.grab(); }
    morph.drag(sdy);
  });
  const endSheet = () => {
    if (!dragging) return;
    dragging = false;
    if (grabbed) morph.release(vY);
  };
  window.addEventListener("pointerup", endSheet);
  window.addEventListener("pointercancel", endSheet);
}
let swipeOffset = 0;

function openNp() {
  if (nc.on && !npOpen && !morph.nativePending) {        // iPhone: picture of the library first, then real glass
    morph.nativePending = true;
    nc.morphBegin(false, nc.accentRgb()).then((ok) => { morph.nativePending = false; openNpNow(ok); });
    return;
  }
  openNpNow(false);
}
let openingT = 0;
function openNpNow(native) {
  if (!player.current || npOpen) return;
  prepareNp();
  if (!motion()) { morph.clear(); return; }
  morph.rect = anchorRect();
  if (native) { morph.native = true; morph.active = true; document.documentElement.classList.add("native-morph"); }
  morph.set(1);                                  // start as the bubble…
  morph.v = 0;
  // the controls wait until it has finished opening (else the tap that opened it can land on the growing play button)
  const npEl = $("#np");
  npEl.classList.add("opening");
  clearTimeout(openingT);
  if (!nc.on) nc.haptic("soft");
  requestAnimationFrame(() => morph.animate(0, SPRING_OPEN, () => { morph.clear(); openingT = setTimeout(() => npEl.classList.remove("opening"), 120); }));   // …and grow out of it, overshooting a touch
}
function prepareNp() {
  unflipNow();                                   // always reopen on the artwork, not the details
  npOpen = true;
  progCache.np = progCache.nps = -1;
  npTrack(0);
  updateProgress();
  const el = $("#np");
  el.classList.toggle("fogged", motion());         // the storm-in-fog background (motion off: the still, blurred cover)
  if (!motion()) el.style.removeProperty("--accent");
  el.getAnimations().forEach((a) => a.cancel());
  el.style.transform = "";
  el.setAttribute("aria-hidden", "false");
  el.classList.add("open");
  startViz();
  if (el.classList.contains("lyrics")) lyrView.start();
}

// ---- the morph between the bubble and the full player. p = 0 is full screen, p = 1 is the bubble.
// Everything is a function of p, so a finger can hold it at any in-between stage; letting go hands it to a spring.
const SPRING_OPEN = { k: 210, c: 21 }, SPRING_CLOSE = { k: 240, c: 24 }, SPRING_BACK = { k: 260, c: 22 };
const morph = {
  p: 0, v: 0, rect: null, raf: 0, active: false, nat: null,
  dist() { return Math.max(220, (this.rect?.top || innerHeight * 0.8) * 0.95); },  // how far a finger travels for the whole morph (≈ the top edge follows it)
  /** where the player's pieces sit when it's full screen (measured once per morph, with nothing moved) */
  measure() {
    const el = $("#np"), pieces = this.pieces();
    for (const x of [np.stage, ...pieces.flip.map((f) => f.el), ...pieces.fade]) { x.style.transform = ""; x.style.opacity = ""; }
    const R = (x) => x.getBoundingClientRect();
    const titles = np.wrapEl.querySelector(".titles");
    const ctrls = np.wrapEl.querySelector(".ctrls");
    this.nat = { cover: R(np.cover), stage: R(np.stage), titles: R(titles), pp: R(np.pp), prev: R(ctrls.children[1]), next: R(ctrls.children[3]), seek: R(np.seek) };
    el.classList.add("morphing");
  },
  pieces() {
    const w = np.wrapEl, ctrls = w.querySelector(".ctrls");
    return {
      flip: [{ el: w.querySelector(".titles"), from: "text", nat: "titles" }, { el: np.pp, from: "play", nat: "pp" },
        { el: ctrls.children[1], from: "prev", nat: "prev" }, { el: ctrls.children[3], from: "next", nat: "next" },
        { el: np.seek, from: "bar", nat: "seek", stretch: true }],
      fade: [w.querySelector(".top"), w.querySelector(".lyr"), w.querySelector(".times"), w.querySelector(".extras"),
        ctrls.children[0], ctrls.children[4], np.viz].filter(Boolean),
    };
  },
  /** iPhone: switch to real Liquid Glass (a picture of the library + native glass behind the see-through web view) */
  async goNative(reuse) {
    if (!nc.on || this.native || this.nativePending) return;
    this.nativePending = true;
    const ok = await nc.morphBegin(reuse, nc.accentRgb());
    this.nativePending = false;
    if (!ok || !this.active) { if (ok) nc.morphEnd(npOpen); return; }
    this.native = true;
    document.documentElement.classList.add("native-morph");
    this.set(this.p);
  },
  set(p) {
    this.p = p; this.active = true;
    const el = $("#np"), r = this.rect || (this.rect = anchorRect());
    if (!this.nat) this.measure();
    const W = innerWidth, H = innerHeight, q = Math.max(-0.12, Math.min(1.12, p));
    const lerp = (a, b, t) => a + (b - a) * t;
    const ss = (a, b, x) => { const t = Math.max(0, Math.min(1, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
    // 1) the glass media bar stretches until it's almost full screen…  2) …then becomes the real full screen
    const NEAR = 0.16, side = matchMedia("(orientation: landscape) and (min-width: 640px)").matches;
    const full = { l: 0, t: 0, r: W, b: H };
    const bubble = { l: r.left, t: r.top, r: r.right, b: r.bottom }, bubbleRad = side ? 22 : 26;
    // one continuous stretch: it reaches "almost full screen" early, and only becomes the real player at the very end
    const e = q >= 0 ? 1 - Math.pow(1 - Math.min(q, 1), 1.15) + Math.max(0, q - 1) : q;      // past 1 / below 0: the bounces
    const rc = { l: lerp(full.l, bubble.l, e), t: lerp(full.t, bubble.t, e), r: lerp(full.r, bubble.r, e), b: lerp(full.b, bubble.b, e) };
    const rad = q <= 0 ? 0 : Math.min(40, 400 * q) * (1 - Math.min(1, q)) + bubbleRad * Math.min(1, q);
    el.style.clipPath = this.native ? "none" : `inset(${rc.t.toFixed(1)}px ${(W - rc.r).toFixed(1)}px ${(H - rc.b).toFixed(1)}px ${rc.l.toFixed(1)}px round ${rad.toFixed(1)}px)`;
    // the glass itself (behind the player's controls)
    if (!this.g?.isConnected) this.g = el.insertBefore(h("div", { class: "lgm" }), np.wrapEl);
    const gs = this.g.style;
    const glassO = 1 - ss(0, NEAR, NEAR - q);            // full glass while stretching; fades as the real background arrives
    gs.display = glassO > 0.002 && !this.native ? "block" : "none";
    if (this.native) nc.morphFrame({ x: rc.l, y: rc.t, w: rc.r - rc.l, h: rc.b - rc.t, rad, o: glassO });
    gs.left = rc.l.toFixed(1) + "px"; gs.top = rc.t.toFixed(1) + "px";
    gs.width = Math.max(0, rc.r - rc.l).toFixed(1) + "px"; gs.height = Math.max(0, rc.b - rc.t).toFixed(1) + "px";
    gs.borderRadius = rad.toFixed(1) + "px"; gs.opacity = glassO.toFixed(3);
    el.style.setProperty("--bgo", (1 - ss(0, NEAR + 0.08, q)).toFixed(3));    // the player's own background: only at the very end
    // 3) the pieces travel from where they are in the bar to where they'll be
    const u = ss(0.06, 1, q);
    const parts = anchorParts(r), N = this.nat;
    const flipTo = (x, from, nat, origin) => {
      if (!from || !nat || !nat.width) return;
      const sx = lerp(1, from.w / nat.width, u), sy = lerp(1, from.h / nat.height, u);
      const s1 = origin === "uniform" ? Math.min(sx, sy) : null;
      const dx = (from.x - nat.left) * u, dy = (from.y - nat.top) * u;
      x.style.transformOrigin = "0 0";
      x.style.transform = `translate3d(${dx.toFixed(1)}px, ${dy.toFixed(1)}px, 0) scale(${(s1 ?? sx).toFixed(4)}, ${(s1 ?? sy).toFixed(4)})`;
    };
    // the cover: move its stage so the cover lands exactly on the bar's album art
    if (N.cover.width) {
      const s1 = lerp(1, parts.art.w / N.cover.width, u);
      const dx = (parts.art.x - N.cover.left) * u, dy = (parts.art.y - N.cover.top) * u;
      np.stage.style.transformOrigin = `${(N.cover.left - N.stage.left).toFixed(1)}px ${(N.cover.top - N.stage.top).toFixed(1)}px`;
      np.stage.style.transform = `translate3d(${dx.toFixed(1)}px, ${dy.toFixed(1)}px, 0) scale(${s1.toFixed(4)})`;
    }
    const pieces = this.pieces();
    for (const f of pieces.flip) {
      const from = parts[f.from];
      if (!from || !from.w) {                         // nothing like it in the bar (e.g. no back button upright): it fades
        f.el.style.transform = `translate3d(0, ${(28 * ss(0, 1, q)).toFixed(1)}px, 0)`;
        f.el.style.opacity = (1 - ss(0.12, 0.62, q)).toFixed(3);
        continue;
      }
      flipTo(f.el, from, N[f.nat], f.stretch ? "stretch" : "uniform");
      f.el.style.opacity = !parts.showText ? (1 - ss(0.3, 0.8, q)).toFixed(3) : (1 - ss(0.85, 1, q) * 0.6).toFixed(3);
    }
    // the bubble / tabs / top bar fade in and out with it (crossfading with the pieces that travel), never popping
    nc.chromeFade(+ss(0.78, 1, q).toFixed(3));
    const cf = 1 - ss(0.12, 0.62, q);                    // everything else fades in and glides up into place
    for (const x of pieces.fade) { x.style.opacity = cf.toFixed(3); x.style.transform = `translate3d(0, ${(28 * ss(0, 1, q)).toFixed(1)}px, 0)`; }
  },
  clear() {
    cancelAnimationFrame(this.raf);
    this.active = false; this.p = 0; this.v = 0; this.rect = null; this.nat = null;
    if (this.native) { this.native = false; document.documentElement.classList.remove("native-morph"); nc.morphEnd(npOpen); }
    if (npOpen) nc.hide();                           // fully open: the bubble stays out of the way
    setTimeout(npPlayButton, 0);
    if (this.g) this.g.style.display = "none";
    const el = $("#np");
    el.style.clipPath = ""; el.style.transform = ""; el.style.removeProperty("--bgo");
    el.classList.remove("morphing");
    np.wrapEl.style.transform = ""; np.wrapEl.style.opacity = "";
    const pieces = this.pieces();
    for (const x of [np.stage, ...pieces.flip.map((f) => f.el), ...pieces.fade]) { x.style.transform = ""; x.style.opacity = ""; x.style.transformOrigin = ""; }
  },
  /** arrive: finish the moment it reaches `to` and hand its speed to done(v) (for momentum into the bubble) */
  animate(to, sp, done, arrive = false) {
    cancelAnimationFrame(this.raf);
    let last = performance.now();
    const side = Math.sign(this.p - to);
    const step = (ts) => {
      let t = Math.min(0.064, (ts - last) / 1000); last = ts;
      let p = this.p;
      while (t > 0) {                  // small fixed steps: the same bounce at any frame rate
        const dt = Math.min(t, 1 / 240); t -= dt;
        this.v += (-sp.k * (p - to) - sp.c * this.v) * dt;       // a spring: overshoots a little, then settles
        p += this.v * dt;
      }
      if (arrive && side && Math.sign(p - to) !== side) { const v = this.v; this.set(to); this.v = 0; return done?.(Math.abs(v)); }
      this.set(p);
      if (Math.abs(this.p - to) < 0.003 && Math.abs(this.v) < 0.05) { this.set(to); this.v = 0; return done?.(Math.abs(this.v)); }
      this.raf = requestAnimationFrame(step);
    };
    this.raf = requestAnimationFrame(step);
  },
  grab() { this.dragEdge.reset(); cancelAnimationFrame(this.raf); this.rect = anchorRect(); this.start = this.p || 0; this.v = 0; npPlayButton(); if (npOpen) this.goNative(true); },
  dragEdge: edge(),
  drag(dy) {                         // dy > 0: towards the bubble
    let p = (this.start || 0) + dy / this.dist();
    if (!nc.on) this.dragEdge(p > 0.32);
    if (p < 0) p = p * 0.25;         // pulling the other way just stretches a little
    this.set(Math.min(1.05, p));
  },
  release(vPxPerMs) {               // finish whichever way it was going
    this.v = (vPxPerMs * 1000) / this.dist();
    if (this.p > 0.32 || vPxPerMs > 0.5) closeNp({ fromDrag: true });
    else { if (!nc.on) nc.haptic("tick"); this.animate(0, SPRING_BACK, () => this.clear()); }
  },
};

// the shape the player grows from / shrinks into: the bubble upright, the media bubble sideways
function anchorRect() {
  const a = nc.on && nc.anchorBox;                // iPhone: the native bubble says exactly where it is
  if (a && a.w > 0) return { left: a.x, top: a.y, right: a.x + a.w, bottom: a.y + a.h, width: a.w, height: a.h };
  const side = matchMedia("(orientation: landscape) and (min-width: 640px)").matches;
  const el = side ? ($("#mini").hidden ? $("#tabs") : $("#mini")) : $("#dock");
  const r = el.getBoundingClientRect();
  if (side && miniTuck.tucked && !$("#mini").hidden) return { top: r.top, bottom: r.bottom, left: r.left, right: r.left + 68 };
  return r;
}
/** where the bar's album art, titles and play button are (for the pieces to travel from/to) */
function anchorParts(r) {
  const a = nc.on && nc.anchorBox;
  if (a && a.aw > 0) return { art: { x: a.ax, y: a.ay, w: a.aw, h: a.ah }, text: { x: a.tx, y: a.ty, w: a.tw, h: a.th }, play: { x: a.px, y: a.py, w: a.pw, h: a.ph },
    prev: a.vw && !a.tucked ? { x: a.vx, y: a.vy, w: a.vw, h: a.vh } : null, next: a.nw && !a.tucked ? { x: a.nx, y: a.ny, w: a.nw, h: a.nh } : null,
    bar: a.bw ? { x: a.bx, y: a.by, w: a.bw, h: a.bh } : null, showText: !a.tucked };
  const side = matchMedia("(orientation: landscape) and (min-width: 640px)").matches, tucked = side && miniTuck.tucked;
  const art = tucked ? { x: r.left + 10, y: r.top + 9, w: 48, h: 48 } : side ? { x: r.left + 9, y: r.top + 9, w: 48, h: 48 } : { x: r.left + 10, y: r.top + 9, w: 44, h: 44 };
  const w = r.right - r.left;
  return { art, text: { x: art.x + art.w + 12, y: r.top + 12, w: Math.max(40, w - art.w - 170), h: 40 },
    play: side ? { x: r.right - 104, y: r.top + 10, w: 46, h: 46 } : { x: r.right - 100, y: r.top + 10, w: 42, h: 42 }, showText: !tucked,
    prev: side && !tucked ? { x: r.right - 152, y: r.top + 11, w: 44, h: 44 } : null,
    next: tucked ? null : side ? { x: r.right - 52, y: r.top + 11, w: 44, h: 44 } : { x: r.right - 50, y: r.top + 9, w: 44, h: 44 },
    bar: tucked ? { x: r.left + 10, y: r.top + 78, w: 48, h: 2 } : { x: r.left + 14, y: (side ? r.bottom - 3 : r.top + 60), w: w - 28, h: 2 } };
}
const clipFor = (r, rad) => `inset(${Math.round(r.top)}px ${Math.round(innerWidth - r.right)}px ${Math.round(innerHeight - r.bottom)}px ${Math.round(r.left)}px round ${rad}px)`;
/** the player lands in the bubble with whatever speed it had: the bubble gives a little under it (squashes and dips,
 *  more for a fast flick, barely for a gentle close) and springs back — one continuous motion, no separate bounce */
let impulseRaf = 0;
function bubbleImpulse(v) {
  if (!motion()) return;
  const side = matchMedia("(orientation: landscape) and (min-width: 640px)").matches;
  const el = side ? $("#mini") : $("#dock");
  if (!el || el.hidden) return;
  cancelAnimationFrame(impulseRaf);
  let x = 0, xv = Math.min(9, Math.max(2.2, v * 1.4)), last = performance.now();
  el.style.transformOrigin = "50% 100%";
  const step = (ts) => {
    let t = Math.min(0.05, (ts - last) / 1000); last = ts;
    while (t > 0) { const dt = Math.min(t, 1 / 240); t -= dt; xv += (-420 * x - 13 * xv) * dt; x += xv * dt; }
    el.style.translate = `0 ${(x * 18).toFixed(2)}px`;
    el.style.scale = `${(1 + x * 0.05).toFixed(4)} ${(1 - x * 0.09).toFixed(4)}`;
    if (Math.abs(x) < 0.002 && Math.abs(xv) < 0.02) { el.style.translate = el.style.scale = el.style.transformOrigin = ""; return; }
    impulseRaf = requestAnimationFrame(step);
  };
  impulseRaf = requestAnimationFrame(step);
}
function bounceBubble() {
  if (!motion()) return;
  const side = matchMedia("(orientation: landscape) and (min-width: 640px)").matches;
  const el = side ? $("#mini") : $("#dock");
  if (!el || el.hidden) return;
  const base = getComputedStyle(el).transform;
  const t = base && base !== "none" ? base + " " : "";
  el.animate([{ transform: t + "scale(.93)" }, { transform: t + "scale(1.035)", offset: 0.55 }, { transform: t + "scale(1)" }],
    { duration: 420, easing: "cubic-bezier(.3,1,.5,1)" });
}
let pageDirty = false;
/** the playing song changed: redo only the row/card that stopped and the one that started (not the whole page) */
function markNow() {
  if (!pageEl) return;
  const id = player.current?.id;
  for (const el of pageEl.querySelectorAll(".row[data-id], .song-card[data-id]")) {
    const now = el.dataset.id === id;
    if (now !== el.classList.contains("now")) { const n = el._make?.(); if (n) el.replaceWith(n); }
  }
}
function closeNp(opts = {}) {
  if (!npOpen) return false;
  clearTimeout(openingT); $("#np").classList.remove("opening");
  npOpen = false;
  if (pageDirty) { pageDirty = false; setTimeout(refresh, 470); }
  const el = $("#np");
  el.setAttribute("aria-hidden", "true");
  lyrView.stop();
  if (!motion()) { el.classList.remove("open"); morph.clear(); unflipNow(); nc.show(false); return true; }
  // shrink into the bubble from wherever it is (a finger may be partway), overshoot a hair, then the bubble bounces
  if (!morph.active) { morph.rect = anchorRect(); morph.set(0); morph.v = 0; }
  else morph.rect = morph.rect || anchorRect();
  npPlayButton();
  morph.goNative(true);
  const done = (v = 0) => {
    if (npOpen) return;
    el.classList.remove("open");
    morph.clear();
    unflipNow();
    if (nc.on) { nc.show(true); nc.haptic("thud"); } else { nc.haptic(v > 6 ? "thud" : "soft"); bubbleImpulse(v); }
  };
  morph.animate(1, SPRING_CLOSE, done, !nc.on);           // (no stop-and-bounce: it lands with its speed)
  return true;
}
/** back to the front of the cover at once (no turning animation) — the player always reopens on the artwork */
function unflipNow() {
  if (!flipped) return;
  np.cover.style.transition = "none";
  flip(false);
  np.cover.getBoundingClientRect();
  requestAnimationFrame(() => (np.cover.style.transition = ""));
}
function flip(force) {
  flipped = force ?? !flipped;
  if (flipped) fillDetails();
  np.cover.classList.toggle("flipped", flipped);
  np.cover.style.transform = "";
}
function fillDetails() {
  const s = player.current; if (!s) return;
  const al = lib.albumOf(s);
  const det = np.cover.querySelector(".det");
  const rows = [["Album", s.album], ["Album artist", s.albumartist && s.albumartist !== s.artist ? s.albumartist : ""],
    ["Track", s.track ? `${s.track}${al ? " of " + al.tracks.length : ""}` : ""], ["Disc", s.disc > 0 ? String(s.disc) : ""], ["Released", s.year],
    ["Genre", s.genre], ["Composer", s.composer], ["Label", s.label], ["Copyright", s.copyright], ["ISRC", s.isrc], ["BPM", s.bpm ? String(s.bpm) : ""],
    ["Length", fmtTime(s.duration)], ["Format", s.codec], ["File size", s.size ? fmtSize(s.size) : ""], ["Added", s.added ? new Date(s.added).toLocaleDateString() : ""]];
  det.replaceChildren(h("h3", {}, s.title), h("div", { class: "by" }, s.artist || ""), ...rows.filter((r) => r[1]).map(([k, v]) => h("div", { class: "kv" }, h("span", {}, k), h("span", {}, v))));
}
function updateNpFav() {
  const s = player.current;
  if (!np.fav || !s) return;
  const on = lib.isFav(s.id);
  np.fav.replaceChildren(icon(on ? "heartf" : "heart"));
  np.fav.classList.toggle("on", on);
}
function showOffset() {
  const o = lib.meta.settings.offsets[player.current?.id] || 0;
  np.lyrOff.textContent = o ? `${o > 0 ? "+" : ""}${o.toFixed(1)}s` : "";
}

// covers of the next / previous songs are decoded ahead of time so a swipe never waits on an image
const coverCache = new Map();     // cover id -> decoded <img>
function decodedCover(id) {
  const c = lib.cover(id);
  if (!c) return null;
  let im = coverCache.get(id);
  if (!im) {
    im = new Image();
    im.decoding = "async";
    im.src = c.url;
    im.decode?.().catch(() => {});
    coverCache.set(id, im);
  }
  return im;
}
function preloadNeighbors() {
  const want = new Set();
  for (const k of [-2, -1, 0, 1, 2]) { const s = lib.songs.get(player.queue[player.index + k]); if (s?.cover) want.add(s.cover); }
  for (const id of want) decodedCover(id);
  for (const id of [...coverCache.keys()]) if (!want.has(id)) coverCache.delete(id);
}
function coverEl(s) {
  const im = s.cover && decodedCover(s.cover);
  if (!im) return h("div", { class: "ph", style: { "--c": nameColor(s.album) } }, icon("note"));
  const el = im.isConnected ? im.cloneNode() : im;      // the decoded image itself when we can
  el.alt = "";
  el.draggable = false;
  return el;
}

// a new song: slide the cover out one side and the new one in from the other
function npTrack(dir) {
  const s = player.current;
  if (!s || !np.cover) return;
  const already = dir && np.shownId === s.id;        // the swipe already showed this song; the audio just caught up
  np.shownId = s.id;
  const cov = lib.cover(s.cover);
  const color = cov?.color || "hsl(345 90% 72%)";
  if (document.documentElement.style.getPropertyValue("--accent") !== color) glowFade = performance.now();
  document.documentElement.style.setProperty("--accent", color);
  nc.tint(color);
  // the blurred backdrops use the small cover (looks the same blurred, far cheaper) and crossfade into each other
  setBackdrop(cov ? cov.small : null, color);
  fog.setCover(s.cover);
  np.cover.querySelector(".back .blur").style.backgroundImage = cov ? `url(${cov.small})` : "none";
  np.ctx.replaceChildren(document.createTextNode(ctxLabel ? "PLAYING FROM" : "NOW PLAYING"), h("b", {}, ctxLabel || s.album || ""));
  np.title.textContent = s.title;
  np.artist.textContent = s.artist || "Unknown Artist";
  np.lyrHeadArt.replaceChildren(...art(s.cover, { label: s.album, small: false }).childNodes);
  np.lyrHeadT.textContent = s.title;
  np.lyrHeadS.textContent = s.artist || "";
  updateNpFav();
  const front = np.cover.querySelector(".front");
  if (already) { if (flipped) fillDetails(); preloadNeighbors(); return; }
  const fresh = h("div", { class: "slide" }, coverEl(s));
  const old = front.firstChild;
  if (npOpen && dir && old && motion() && !$("#np").classList.contains("lyrics")) {
    // the outgoing cover flies off as its own layer (so nothing gets clipped), the new one slides in
    // the old cover leaves from exactly where your finger left it (its resting spot + the drag), no jump
    const ghost = h("div", { class: "slide", style: { inset: "auto", left: np.cover.offsetLeft + "px", top: np.cover.offsetTop + "px", width: np.cover.offsetWidth + "px", height: np.cover.offsetHeight + "px", boxShadow: "0 30px 70px rgba(0,0,0,.6)" } }, old.cloneNode(true).firstChild || "");
    np.stage.append(ghost);
    const start = swipeOffset || 0;
    if (start) dir = start < 0 ? 1 : -1;      // both covers travel the way you swiped
    swipeOffset = 0;
    const o = { duration: 520, easing: "cubic-bezier(.2,.8,.2,1)" };
    // The old cover keeps the tilt your drag gave it and swings on out; no blur (blurring the 3D-flippable cover
    // made the browser flatten its front + back faces for a frame, which looked like the cover splitting in half).
    const tilt = start / 40;
    ghost.animate([{ transform: `translateX(${start}px) rotate(${tilt}deg)`, opacity: 1 },
      { transform: `translateX(${-dir * 115}%) rotate(${tilt - dir * 6}deg) scale(.88)`, opacity: 0 }], o).onfinish = () => ghost.remove();
    front.replaceChildren(fresh);
    const was = flipped;
    if (flipped) flip(false);
    // clear the drag's leftover position/tilt *before* animating, so nothing snaps back when the slide ends
    np.cover.style.transition = "none";
    np.cover.style.transform = "";
    np.cover.animate([{ transform: `translateX(${dir * 115}%) rotate(${dir * 6}deg) scale(.88)${was ? " rotateY(180deg)" : ""}`, opacity: 0 },
      { transform: "none", opacity: 1 }], { ...o, duration: was ? 700 : 520 })
      .onfinish = () => { np.cover.style.transition = ""; };
  } else {
    front.replaceChildren(fresh);
    np.cover.style.transform = "";
    swipeOffset = 0;
    if (flipped && dir) flip(false);
  }
  if (flipped) fillDetails();
  if ($("#np").classList.contains("lyrics")) loadLyrics();
  preloadNeighbors();
}

let bgFront = 0, bgShown = null;
function setBackdrop(url, color) {
  const key = (url || "") + "|" + color;
  if (key === bgShown) return;
  bgShown = key;
  const layers = [np.bg, np.bg2l];
  const next = layers[1 - bgFront], prev = layers[bgFront];
  const show = () => {
    if (bgShown !== key) return;
    next.style.backgroundImage = url ? `url(${url})` : "none";
    next.style.backgroundColor = color;
    next.style.opacity = "1";
    prev.style.opacity = "0";
    bgFront = 1 - bgFront;
  };
  if (!url) return show();
  const im = new Image();
  im.src = url;
  (im.decode ? im.decode() : Promise.resolve()).catch(() => {}).then(show);      // only fade once it's ready: no blank flash
}

/** iPhone: the full player's play / pause is a real clear-glass button laid over the web one, while the player is open and still */
function npPlayButton() {
  if (!nc.on || !np.pp) return;
  const show = npOpen && !morph.active && $("#sheet").hidden;
  const r = show ? np.pp.getBoundingClientRect() : null;
  if (show && r.width > 0) nc.npButton({ show: true, x: r.left, y: r.top, w: r.width, playing: player.playing });
  else nc.npButton({ show: false });
  document.documentElement.classList.toggle("np-native-pp", !!(show && r?.width > 0));
}
addEventListener("resize", () => setTimeout(npPlayButton, 350));
function npState() {
  if (!np.pp) return;
  npPlayButton();
  np.pp.replaceChildren(icon(player.playing ? "pause" : "play"));
  np.shuffle.classList.toggle("on", player.shuffle);
  np.shuffle.classList.toggle("dim", !player.shuffle);
  np.repeat.replaceChildren(icon(player.repeat === "one" ? "repeat1" : "repeat"));
  np.repeat.classList.toggle("on", player.repeat !== "off");
  np.repeat.classList.toggle("dim", player.repeat === "off");
}

function toggleLyrics(force) {
  setTimeout(npPlayButton, 0); setTimeout(npPlayButton, 520);     // the play button moves in lyrics mode
  const el = $("#np");
  const on = el.classList.toggle("lyrics", force);
  np.lyrBtn.classList.toggle("on", on);
  if (on) { loadLyrics(); lyrView.start(); } else lyrView.stop();
}
async function loadLyrics() {
  const s = player.current; if (!s) return;
  lyrView.set(null, lib.colorOf(s));
  np.lyrSrc.textContent = "Lyrics";
  showOffset();
  const res = await fetchLyrics(s);
  if (player.current?.id !== s.id) return;
  lyrView.set(res, lib.colorOf(s));
  np.lyrSrc.textContent = res.source ? `Lyrics · ${res.source}` : "Lyrics";
}

function lyricsPicker(s) {
  const input = h("input", { class: "field", value: `${cleanTitle(s.title)} ${(s.artist || "").split(",")[0]}`.trim(), enterkeyhint: "search" });
  const status = h("div", { class: "lk-status" });
  const list = h("div", { class: "lk-list" });
  const go = async () => {
    status.textContent = "Searching LRCLIB…";
    list.replaceChildren();
    try {
      const res = await searchLyrics(input.value.trim(), s);
      status.textContent = res.length ? "Pick the right one — synced lyrics scroll along with the song" : "No lyrics found. Try just the song name.";
      for (const r of res.slice(0, 40)) {
        const row = h("div", { class: "row lk-row" }, h("div", { class: "meta" }, h("div", { class: "t" }, r.trackName),
          h("div", { class: "s" }, [r.artistName, r.albumName, r.duration ? fmtTime(r.duration) : ""].filter(Boolean).join(" · "))),
          h("span", { class: "badge" + (r.syncedLyrics ? " best" : "") }, r.syncedLyrics ? "Synced" : r.instrumental ? "Instrumental" : "Plain"));
        press(row, () => {
          const res2 = { synced: r.syncedLyrics || "", plain: r.plainLyrics || "", instrumental: !!r.instrumental, source: "LRCLIB", picked: true };
          lib.meta.lyrics[s.id] = res2; lib.saveMeta();
          closeSheet(true);
          if (player.current?.id === s.id) { lyrView.set(res2, lib.colorOf(s)); np.lyrSrc.textContent = "Lyrics · LRCLIB"; }
          snack("Lyrics saved for this song");
        });
        list.append(row);
      }
    } catch { status.textContent = "Couldn't reach LRCLIB — check your internet connection."; }
  };
  input.addEventListener("keydown", (e) => e.key === "Enter" && go());
  openSheet(h("div", { class: "lookup" }, h("div", { class: "lk-head" }, icon("lyrics"), h("h3", {}, "Find Lyrics")),
    h("div", { class: "lk-bar" }, input, h("button", { class: "btn", onclick: go }, "Search")), status, list));
  go();
}

function showQueue() {
  const box = h("div", { class: "queue" }, h("div", { class: "sh-head" }, h("div", {}, h("div", { class: "t" }, "Up Next"),
    h("div", { class: "s" }, plural(Math.max(0, player.queue.length - player.index - 1), "song") + (player.shuffle ? " · shuffled" : "")))));
  const list = h("div", { class: "list" });
  player.queue.slice(player.index, player.index + 200).forEach((id, k) => {
    const s = lib.songs.get(id); if (!s) return;
    const r = h("div", { class: "row" + (k === 0 ? " now" : "") }, art(s.cover, { label: s.album }), h("div", { class: "meta" }, h("div", { class: "t" }, s.title), h("div", { class: "s" }, s.artist || "")));
    press(r, () => { closeSheet(true); player.jump(player.index + k); });
    list.append(r);
  });
  box.append(list);
  openSheet(box);
}

// ---- the player's "storm in fog": two clouds in the cover's two main colours drift behind everything, swell with the
// bass and flash on hard hits like lightning in fog; the player's accent colour slides between the two with the beat.
// Drawn tiny (40×72) and stretched by the screen, so it's soft as fog and nearly free to draw.
const toHsl = ([r, g, b]) => {
  r /= 255; g /= 255; b /= 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
  let h = 0, s = 0;
  if (mx !== mn) { const d = mx - mn; s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn); h = (mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4) / 6; }
  return [h, s, l];
};
const fromHsl = ([h, s, l]) => {
  const f = (n) => { const k = (n + h * 12) % 12, a = s * Math.min(l, 1 - l); return 255 * (l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))); };
  return [f(0), f(8), f(4)];
};
/** a version of a colour that reads well as text / controls */
const accentSafe = (c) => { const [h, s, l] = toHsl(c); return fromHsl([h, Math.min(0.85, Math.max(s < 0.12 ? s : 0.38, s)), Math.min(0.74, Math.max(0.6, l))]); };
const FOG_DEFAULT = [[150, 110, 140], [70, 70, 95]];
const fog = {
  cv: null, g: null, pal: FOG_DEFAULT, cur: null, acc: [accentSafe(FOG_DEFAULT[0]), accentSafe(FOG_DEFAULT[1])], accH: [toHsl(accentSafe(FOG_DEFAULT[0])), toHsl(accentSafe(FOG_DEFAULT[1]))],
  t0: performance.now(), flash: 0, fx: 0.5, fy: 0.3, avg: 0, accStr: "", accAt: 0, cache: new Map(), want: null,
  el() {
    if (!this.cv) { this.cv = h("canvas", { class: "fog", width: 40, height: 72 }); this.g = this.cv.getContext("2d", { alpha: false }); }
    return this.cv;
  },
  async setCover(id) {
    this.want = id;
    let p = id ? this.cache.get(id) : FOG_DEFAULT;
    if (!p) {
      const c = lib.cover(id);
      p = (c?.smallBlob && (await coverPalette(c.smallBlob))) || FOG_DEFAULT;
      if (this.cache.size > 200) this.cache.clear();
      this.cache.set(id, p);
    }
    if (this.want !== id) return;
    this.pal = p;
    this.acc = [accentSafe(p[0]), accentSafe(p[1])];
    this.accH = this.acc.map(toHsl);
  },
  draw(bass, bassS, playing) {
    const g = this.g, W = 40, H = 72;
    if (!g) return;
    const now = performance.now(), t = (now - this.t0) / 1000;
    if (!this.cur) this.cur = this.pal.map((c) => [...c]);
    for (let k = 0; k < 2; k++) for (let i = 0; i < 3; i++) this.cur[k][i] += (this.pal[k][i] - this.cur[k][i]) * 0.05;   // new song: colours drift over
    // a hit: the bass jumps well above its recent level
    const spike = bass - this.avg;
    this.avg += (bass - this.avg) * 0.06;
    if (playing && spike > 0.13 && bass > 0.42 && this.flash < 0.25) { this.flash = Math.min(1, 0.55 + spike * 2.2); this.fx = 0.15 + Math.random() * 0.7; this.fy = 0.08 + Math.random() * 0.55; }
    this.flash *= 0.9;
    const e = playing ? bassS : 0;
    const [A, B] = this.cur;
    const rgb = (c, a) => `rgba(${c[0] | 0},${c[1] | 0},${c[2] | 0},${a.toFixed(3)})`;
    g.globalCompositeOperation = "source-over";
    g.fillStyle = rgb([A[0] * 0.1 + B[0] * 0.06, A[1] * 0.1 + B[1] * 0.06, A[2] * 0.1 + B[2] * 0.06], 1);
    g.fillRect(0, 0, W, H);
    g.globalCompositeOperation = "lighter";
    const blob = (x, y, r, c, a) => {
      const gr = g.createRadialGradient(x, y, 0, x, y, r);
      gr.addColorStop(0, rgb(c, a)); gr.addColorStop(0.55, rgb(c, a * 0.45)); gr.addColorStop(1, rgb(c, 0));
      g.fillStyle = gr; g.fillRect(0, 0, W, H);
    };
    blob(W * (0.5 + 0.34 * Math.sin(t * 0.13)), H * (0.28 + 0.12 * Math.sin(t * 0.17 + 1)), W * (0.95 + 0.3 * e), A, 0.34 + 0.46 * e);
    blob(W * (0.5 + 0.36 * Math.cos(t * 0.11 + 2)), H * (0.64 + 0.14 * Math.cos(t * 0.09)), W * (1.0 + 0.25 * e), B, 0.3 + 0.42 * e);
    for (let i = 0; i < 3; i++) {                              // wisps of fog drifting through
      const c = i % 2 ? A : B;
      blob(W * (0.5 + 0.5 * Math.sin(t * (0.21 + i * 0.07) + i * 2.1)), H * (0.5 + 0.42 * Math.sin(t * (0.15 + i * 0.05) + i)), W * 0.45, c, 0.07 + 0.08 * e);
    }
    if (this.flash > 0.02) blob(W * this.fx, H * this.fy, W * (0.55 + 0.4 * this.flash), [Math.min(255, A[0] * 0.6 + 110), Math.min(255, A[1] * 0.6 + 110), Math.min(255, A[2] * 0.6 + 110)], this.flash * 0.5);
    // the player's accent rides between the two colours with the music (at most ~20 times a second)
    if (now - this.accAt > 50) {
      this.accAt = now;
      const m = Math.max(0, Math.min(1, 0.15 + e * 1.3 + this.flash * 0.35));
      // round the colour wheel (the short way), not straight across it: orange → blue goes through vivid colours, never grey
      const [a, b] = this.accH;
      let dh = b[0] - a[0]; if (dh > 0.5) dh -= 1; if (dh < -0.5) dh += 1;
      const c = fromHsl([(a[0] + dh * m + 1) % 1, a[1] + (b[1] - a[1]) * m, a[2] + (b[2] - a[2]) * m]);
      const s = `rgb(${Math.round(c[0])}, ${Math.round(c[1])}, ${Math.round(c[2])})`;
      if (s !== this.accStr) { this.accStr = s; $("#np").style.setProperty("--accent", s); }
    }
  },
};

// visualizer + bass glow
let vizOn = false, vizCol = "", vizColAt = 0, glowFade = 0;
function vizColor() {
  if (npOpen && fog.accStr && document.querySelector("#np.fogged")) return fog.accStr;
  const t = performance.now();
  if (t - vizColAt > 120) { vizColAt = t; vizCol = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim() || "#ff7aa0"; }
  return vizCol;
}
function startViz() {
  if (vizOn) return;
  vizOn = true;
  const bins = new Uint8Array(1024);
  let smooth = new Float32Array(40), bassS = 0;
  let cvAt = 0, cvPick = null;
  const loop = () => {
    // upright the little visualizer plays along the bottom of the bubble behind the tabs; sideways, in the player bar
    // (which little canvas is showing: looked up a few times a second, not every frame — that forced a layout each time)
    if (performance.now() - cvAt > 250) { cvAt = performance.now(); cvPick = [$("#dock .tviz"), $("#mini canvas")].find((c) => c && c.offsetParent !== null && c.clientWidth > 0 && !$("#mini").hidden) || null; }
    const miniCv = cvPick;
    if (!npOpen && !miniCv) {
      // nothing visible to draw into right now (e.g. mid-rotation): check back shortly instead of stopping for good
      vizOn = false;
      if (player.current) setTimeout(startViz, 400);
      return;
    }
    requestAnimationFrame(loop);
    updateProgress();
    const an = player.analyser;
    const playing = player.playing;
    if (an && playing) an.getByteFrequencyData(bins); else bins.fill(0);
    const N = smooth.length;
    for (let i = 0; i < N; i++) {
      const lo = Math.floor(2 * Math.pow(400 / 2, i / N)), hi = Math.max(lo + 1, Math.floor(2 * Math.pow(400 / 2, (i + 1) / N)));
      let m = 0; for (let k = lo; k < hi; k++) m = Math.max(m, bins[k]);
      const v = Math.pow(m / 255, 1.6);
      smooth[i] += (v - smooth[i]) * (v > smooth[i] ? 0.5 : 0.12);
    }
    const bass = (bins[2] + bins[3] + bins[4] + bins[5]) / 1020;
    bassS += (bass - bassS) * 0.25;
    // tucked: five little bars over the album cover
    const drawFive = (cv) => {
      const dpr = devicePixelRatio || 1, W = cv.clientWidth, H = cv.clientHeight;
      if (!W) return;
      if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); }
      const g = cv.getContext("2d");
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, W, H);
      // in the strip *under* the cover, so they never cover the artwork
      const ax = 10, aw = 48, ab = H - 6;
      const n = 5, gap = 3, bw = (aw - 4 - gap * (n - 1)) / n, maxH = 11;
      g.fillStyle = "rgba(255,255,255,.92)";
      g.shadowColor = "rgba(0,0,0,.45)"; g.shadowBlur = 4;
      for (let i = 0; i < n; i++) {
        const lo = Math.floor((i * N) / n), hi = Math.floor(((i + 1) * N) / n);
        let v = 0; for (let k = lo; k < hi; k++) v = Math.max(v, smooth[k]);
        const bh = Math.max(2, v * maxH);
        const x = ax + 2 + i * (bw + gap);
        g.beginPath();
        g.roundRect?.(x, ab - bh, bw, bh, 1.5) ?? g.rect(x, ab - bh, bw, bh);
        g.fill();
      }
    };
    const draw = (cv, alphaTop, full) => {
      const dpr = devicePixelRatio || 1;
      if (!cv._sz || performance.now() - cv._szAt > 500) { cv._sz = [cv.clientWidth, cv.clientHeight]; cv._szAt = performance.now(); }
      const [W, H] = cv._sz;
      if (!W) return;
      if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); }
      const g = cv.getContext("2d");
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, W, H);
      const col = vizColor();
      const bw = W / N;
      for (let i = 0; i < N; i++) {
        const bh = Math.max(full ? 3 : 1.5, smooth[i] * H * (full ? 0.95 : 1));
        const grad = g.createLinearGradient(0, H - bh, 0, H);
        grad.addColorStop(0, col.startsWith("hsl(") ? col.replace(/\)$/, ` / ${alphaTop})`) : col);
        grad.addColorStop(1, "rgba(255,255,255,0)");
        g.fillStyle = grad;
        g.beginPath();
        g.roundRect?.(i * bw + bw * 0.18, H - bh, bw * 0.64, bh, 2) ?? g.rect(i * bw + bw * 0.18, H - bh, bw * 0.64, bh);
        g.fill();
      }
    };
    if (npOpen) {
      if (motion()) fog.draw(bass, bassS, playing);
      draw(np.viz, 0.75, true);
      if (motion()) {
        const fade = Math.min(1, (performance.now() - glowFade) / 700);      // new colour: the glow eases back in
        np.glow.style.opacity = ((0.2 + bassS * 0.55) * fade * fade).toFixed(3);
        np.glow.style.transform = `scale(${(1 + bassS * 0.25).toFixed(3)})`;
      }
    } else if (miniCv) {
      if (miniTuck.tucked && miniCv.closest("#mini")) drawFive(miniCv);
      else draw(miniCv, 0.5);
    }
  };
  requestAnimationFrame(loop);
}

// ============================================================ wiring
// the moment you skip: move the cover and titles right away, the audio follows a few ms later
player.addEventListener("willtrack", (e) => {
  if (!miniDir) miniDir = e.detail?.dir || 0;
  if (npOpen) npTrack(e.detail?.dir || 0);
  updateMini();
});
player.addEventListener("track", (e) => {
  if (!miniDir && $("#mini").dataset.id !== player.current?.id) miniDir = e.detail?.dir || 0;
  updateMini();
  npTrack(e.detail?.dir ?? 0);
  if (!npOpen) { const c = lib.colorOf(player.current) || "hsl(345 90% 72%)"; document.documentElement.style.setProperty("--accent", c); nc.tint(c); }
  markNow();                                      // just the rows that changed, not the whole page
  startViz();
  saveSession();
});
player.addEventListener("state", () => { updateMini(); npState(); pageEl?.querySelectorAll(".row.now").forEach((r) => r.classList.toggle("paused", !player.playing)); });
player.addEventListener("time", () => { if (!vizOn) updateProgress(); });
player.addEventListener("error", (e) => {
  const s = e.detail?.song;
  if (e.detail?.missing && s) {        // left behind by an import that didn't finish: offer to clear it out
    snack(`“${s.title}” isn't on this phone any more (its import didn't finish). Add it again to play it.`, "Remove", async () => {
      await lib.removeSongs([s.id]); refresh();
    }, 9000);
    return;
  }
  snack("Couldn't play this song", "Details", showDiagnostics, 7000);
});
player.addEventListener("restart", () => updateProgress());
lib.addEventListener("favs", () => updateNpFav());

function saveSession() {
  lib.meta.session = { queue: player.queue.slice(0, 2000), index: player.index, t: player.audio.currentTime || 0, shuffle: player.shuffle, repeat: player.repeat, ctx: ctxLabel };
  lib.saveMeta();
}
setInterval(() => player.playing && saveSession(), 10000);
document.addEventListener("visibilitychange", () => document.hidden && saveSession());

async function restoreSession() {
  const ss = lib.meta.session;
  if (!ss?.queue?.length) return;
  player.queue = ss.queue.filter((id) => lib.songs.has(id));
  player.index = Math.max(0, Math.min(player.queue.length - 1, player.queue.indexOf(ss.queue[ss.index])));
  player.shuffle = !!ss.shuffle; player.repeat = ss.repeat || "off"; ctxLabel = ss.ctx || "";
  player.base = [...player.queue];
  if (!player.current) return;
  const src = await lib.fileURL(player.current).catch(() => null);
  if (!src) return;
  player.url = player.audio.dataset.url = src.url;
  player.audio.dataset.revoke = src.revoke ? "1" : "";
  player.audio.src = player.url;
  player.audio.addEventListener("loadedmetadata", () => { player.audio.currentTime = Math.min(ss.t || 0, player.audio.duration - 1 || 0); }, { once: true });
  updateMini();
  npTrack(0);
  npState();
  player.updateSession(true);
}

function back() {
  if (closeSheet()) return true;
  if (npOpen) { if ($("#np").classList.contains("lyrics")) toggleLyrics(false); else closeNp(); return true; }
  if (sel) { endSelect(); return true; }
  if (pop()) return true;
  if (stack[0].type !== "albums") { goTab("albums"); return true; }
  return false;
}

async function boot() {
  document.body.classList.toggle("nomotion", false);
  await lib.load();
  document.body.classList.toggle("nomotion", !motion());
  persistStorage();
  buildTabs();
  buildNp();
  show(null);
  setupSwipe();
  setupMiniGestures();
  const dock = $("#dock");
  const sizeDock = () => document.documentElement.style.setProperty("--dock-h", Math.round(dock.getBoundingClientRect().height) + "px");
  new ResizeObserver(sizeDock).observe(dock);
  sizeDock();
  // iPhone's picker greys out music files when it's limited to "audio/*": let it show everything, the app picks out the songs
  if (ios) $("#pickAudio").removeAttribute("accept");
  $("#pickAudio").onchange = (e) => { const f = [...e.target.files]; e.target.value = ""; importAudio(f); };
  $("#pickPack").onchange = (e) => { const f = [...e.target.files]; e.target.value = ""; importPacks(f); };
  lib.addEventListener("change", () => {});
  await nc.start(TABS, {
    tab: (e) => goTab(e.id),
    back: () => back(),
    anchor: (e) => { nc.anchorBox = e; },
    setting: (e) => applySetting(e.id, e.value),
    settingTap: (e) => settingAction(e.id),
    headAction: (e) => { const b = pageEl?.querySelector(".head")?.querySelectorAll(":scope > button:not(.back)")[e.id]; b?.click(); },
    toggle: () => player.toggle(),
    next: () => player.next(),
    prev: () => player.prev(),
    open: () => { if (player.current && !npOpen) openNp(); },
    // drag up on the native bubble: the player grows out of it under the finger
    dragStart: async () => {
      if (!player.current || npOpen || morph.nativePending) return;
      morph.nativePending = true;
      const ok = await nc.morphBegin(false, nc.accentRgb());
      morph.nativePending = false;
      prepareNp(); morph.rect = anchorRect();
      if (ok) { morph.native = true; morph.active = true; document.documentElement.classList.add("native-morph"); }
      morph.set(1); morph.grab(); morph.start = 1;
    },
    drag: (e) => { if (npOpen && morph.active) morph.drag(e.dy); },
    dragEnd: (e) => {
      if (!npOpen || !morph.active) return;
      const v = e.v || 0;
      morph.v = (v * 1000) / morph.dist();
      if (morph.p < 0.72 || v < -0.5) morph.animate(0, SPRING_OPEN, () => morph.clear());
      else closeNp({ fromDrag: true });
    },
  });
  if (nc.on) {
    await initLibraryURL();
    setupBackgroundPlayback();
    let at = 0;
    const prog = (force) => { const now = performance.now(); if (!force && now - at < 900) return; at = now; const a = player.audio; nc.progress(a.currentTime, a.duration || player.current?.duration, player.playing); };
    player.addEventListener("time", () => prog());
    for (const ev of ["state", "track"]) player.addEventListener(ev, () => prog(true));
    for (const el of player.els) el.addEventListener("seeked", () => prog(true));
    // sliders tick at each step, checkboxes and pickers when they change
    let lastRange = null;
    document.addEventListener("input", (e) => { const t = e.target; if (t.type === "range" && t.value !== lastRange) { lastRange = t.value; nc.haptic("tick"); } }, true);
    document.addEventListener("change", (e) => { const t = e.target; if ((t.type === "checkbox" && !t.closest(".switch, label")) || t.tagName === "SELECT") nc.haptic("tick"); }, true);
    // a light tap for every button, row and card you touch (programmatic clicks don't count)
    document.addEventListener("click", (e) => {
      if (!e.isTrusted) return;
      const t = e.target.closest("button, .row, .arow, .card, .chip, .item, [role=button], .switch, label");
      if (!t || t.closest("[data-haptic=own]")) return;      // (those make their own, e.g. favorite = success)
      nc.haptic(t.matches(".pp") ? "press" : t.matches(".danger, .btn.danger") ? "thud"
        : t.matches(".chip, .switch, label, .seg button") ? "tick" : t.matches(".back") ? "soft" : "tap");
    }, true);
    nc.tint(getComputedStyle(document.documentElement).getPropertyValue("--accent").trim());
    nc.update({ tab: stack[0].type });
    nc.header(pageEl, pageEl?.classList.contains("scrolled"));
    miniTuck.sync();
  }
  await restoreSession();
  // songs added before the colour picking improved: recompute their colours quietly, then refresh what's on screen
  setTimeout(() => lib.refreshColors((id) => { if (player.current?.cover === id) npTrack(0); })
    .then(() => lib.repairCovers())                           // covers that didn't save during an import
    .then((n) => { if (n) { refresh(); if (player.current) npTrack(0); } })
    .then(() => { if (pageEl) refresh(); }), 1500);
  if (ios) {
    // the keyboard covers the bottom of the screen on iPhone; lift sheets above it
    const vv = window.visualViewport;
    const kb = () => document.documentElement.style.setProperty("--kb", Math.max(0, Math.round(innerHeight - vv.height - vv.offsetTop)) + "px");
    vv?.addEventListener("resize", kb); vv?.addEventListener("scroll", kb);
    if (native) { try { const { StatusBar, Style } = await import("@capacitor/status-bar"); await StatusBar.setStyle({ style: Style.Dark }); } catch {} }
  }
  if (native) {
    try {
      const { App } = await import("@capacitor/app");
      App.addListener("backButton", () => { if (!back()) App.minimizeApp(); });
      App.addListener("pause", saveSession);
    } catch {}

  }
  window.addEventListener("keydown", (e) => {
    if (e.target.tagName === "INPUT") { if (e.key === "Escape") back(); return; }
    if (e.key === "Escape") back();
    if (e.key === " ") { e.preventDefault(); player.toggle(); }
  });
  // drop files (desktop browsers / tests)
  window.addEventListener("dragover", (e) => e.preventDefault());
  window.addEventListener("drop", (e) => {
    e.preventDefault();
    const files = [...e.dataTransfer.files];
    const packs = files.filter((f) => /\.zip$/i.test(f.name));
    if (packs.length) importPacks(packs);
    const audio = files.filter((f) => !/\.zip$/i.test(f.name));
    if (audio.length) importAudio(audio);
  });
  window.__app = { nc, progressBox, DiskFile, miniTuck, morph, filt, findGenres, filterSheet, get lyr() { return lyrView; }, np, browse, similarSongs, playWithMix, lyricsPicker, lookupDialog, autoFill, lib, player, push, goTab, openNp, closeNp, toggleLyrics, sharePack, importPacks, importAudio, startSelect, refresh, back, stack: () => stack };
}
boot();
