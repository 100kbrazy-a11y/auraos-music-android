import "./style.css";
import { Capacitor } from "@capacitor/core";
import { lib, albumKey, mimeFor } from "./library.js";
import { getJson as getJsonHttp, getBlob } from "./http.js";
import { player, EQ_PRESETS, EQ_BANDS } from "./player.js";
import { LyricsView, fetchLyrics, searchLyrics, cleanTitle } from "./lyrics.js";
import { persistStorage, usage } from "./store.js";
import { squareJpeg, coverColor, splitArtists, primaryArtist } from "./tags.js";
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
  filter: ["M4 6h16M7 12h10M10 18h4"], tag: ["M20.6 13.4l-7.2 7.2a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8zM7.5 7.5h.01"],
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

// Active artist download state for inline Artist page & Artists tab UI
const artistDl = { active: false, name: "", fraction: 0, text: "" };
// Scroll memory for artist pages (kept when tapping into albums/songs, cleared when leaving the artist page)
const artistScrollPositions = new Map();

function updateArtistDlUI() {
  if (!pageEl) return;
  const cur = stack.at(-1);
  if (cur?.type === "artist" && artistDl.active && cur.name.toLowerCase() === artistDl.name.toLowerCase()) {
    const wrap = pageEl.querySelector(".ah-dl-wrap");
    const fill = pageEl.querySelector(".ah-dl-bar b");
    const txt = pageEl.querySelector(".ah-dl-status");
    const ytBtn = pageEl.querySelector(".ahp.round");
    if (wrap) wrap.hidden = false;
    if (fill) fill.style.transform = `scaleX(${Math.max(0.02, Math.min(1, artistDl.fraction || 0))})`;
    if (txt) txt.textContent = artistDl.text || diag.step || "";
    if (ytBtn) ytBtn.disabled = true;
  } else if (cur?.type === "artist") {
    const wrap = pageEl.querySelector(".ah-dl-wrap");
    const ytBtn = pageEl.querySelector(".ahp.round");
    if (wrap) wrap.hidden = true;
    if (ytBtn) ytBtn.disabled = false;
  }
}

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

// press: tap / long-press (and right-click on desktop)
function press(el, tap, long) {
  let timer = 0, sx = 0, sy = 0, fired = false;
  el.addEventListener("pointerdown", (e) => {
    if (e.button > 0) return;
    sx = e.clientX; sy = e.clientY; fired = false;
    el.classList.add("pressed");
    if (long) timer = setTimeout(() => { fired = true; el.classList.remove("pressed"); navigator.vibrate?.(18); nc.haptic("press"); long(e); }, 460);
  });
  const cancel = () => { clearTimeout(timer); el.classList.remove("pressed"); };
  el.addEventListener("pointermove", (e) => { if (Math.hypot(e.clientX - sx, e.clientY - sy) > 10) cancel(); });
  el.addEventListener("pointerup", cancel);
  el.addEventListener("pointercancel", cancel);
  el.addEventListener("pointerleave", () => clearTimeout(timer));
  el.addEventListener("click", (e) => { if (fired) { e.preventDefault(); e.stopPropagation(); return; } tap?.(e); });
  if (long) el.addEventListener("contextmenu", (e) => { e.preventDefault(); clearTimeout(timer); if (!fired) long(e); fired = false; });
}

// ============================================================ snackbar, dialogs, sheets
let snackT;
function snack(msg, action, fn, ms = 4200) {
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
  const sh = $("#sheet"), scrim = $("#scrim");
  sh.innerHTML = "";
  sh.className = dialog ? "dialog" : "";
  if (!dialog) sh.append(h("div", { class: "grab" }));
  sh.append(content);
  sh.hidden = scrim.hidden = false;
  nc.update({ covered: true });
  npPlayButton();
  sheetClose = onClose || null;
  scrim.onclick = () => closeSheet();
}
function closeSheet(silent) {
  const sh = $("#sheet");
  if (sh.hidden) return false;
  sh.hidden = $("#scrim").hidden = true;
  queueMicrotask(() => { if ($("#sheet").hidden) { nc.update({ covered: !!sel }); npPlayButton(); } });
  sh.innerHTML = "";
  const f = sheetClose; sheetClose = null;
  if (!silent) f?.();
  return true;
}
function actionSheet(head, actions) {
  if (nc.on) {
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
// Non-blocking dynamic progress notification bar at the top (replaces modal progress popups)
function progressBox(title, onStop) {
  let host = $("#topprog");
  if (!host) {
    host = h("div", { id: "topprog", hidden: true });
    $("#app").append(host);
  }
  const bar = h("b"), txt = h("span", {}, "Starting…");
  const stopBtn = onStop && h("button", { class: "tp-stop", onclick: (e) => { e.currentTarget.disabled = true; e.currentTarget.textContent = "Stopping…"; onStop(); } }, "Stop");
  const card = h("div", { class: "tp-card" },
    h("div", { class: "tp-row" }, h("b", {}, title), txt, stopBtn),
    h("div", { class: "tp-bar" }, bar)
  );
  host.replaceChildren(card);
  host.hidden = false;
  diag.step = ""; diag.log("progress: " + title);
  const tick = setInterval(() => {
    if (diag.step && !txt.dataset.manual) txt.textContent = diag.step;
  }, 250);
  return {
    set(f, t) {
      bar.style.transform = `scaleX(${Math.max(0.02, Math.min(1, f || 0))})`;
      if (t) { txt.dataset.manual = "1"; txt.textContent = t; }
    },
    close() {
      clearInterval(tick);
      diag.step = "";
      host.hidden = true;
      host.innerHTML = "";
    },
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
let sel = null;
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
  for (const b of $("#tabs").querySelectorAll("button[data-tab]")) b.classList.toggle("on", b.dataset.tab === stack[0].type);
  sideLists();
  moveInd(stack[0].type);
  nc.update({ tab: stack[0].type });
}

let quietScrollUntil = 0;
const miniTuck = (() => {
  let tucked = false, inSettings = false, timer = 0;
  const landscape = () => matchMedia("(orientation: landscape) and (min-width: 640px)").matches;
  const delay = () => { const v = +lib.meta.settings.lyricReturn; return (isFinite(v) && v > 0 ? v : 1.5) * 1000; };
  const pinned = () => !!lib.meta.settings.tuckPinned;
  const TUCKED_BTN = 51, COVER = 68, TUCKED_H = 86;
  function place() {
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
    const finalTop = mr.bottom - TUCKED_H;
    const dy = Math.min(bottom + 8, innerHeight - 6 - TUCKED_H) - finalTop;
    m.style.transform = `translate3d(${dx}px, ${dy}px, 0)`;
  }
  function tuck() {
    document.body.classList.toggle("mini-pinned", pinned());
    if (tucked || !landscape() || $("#mini").hidden) return;
    tucked = true;
    document.body.classList.add("mini-tucked");
    place();
    report();
  }
  function untuck() {
    if (!tucked) return;
    tucked = false;
    document.body.classList.remove("mini-tucked");
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
      if (!inSettings && !pinned()) timer = setTimeout(later, delay());
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

const ind = { x: 0, v: 0, w: 0, target: 0, raf: 0, drag: false, last: 0, ready: false };
const pillMode = () => false;
const vertical = () => matchMedia("(orientation: landscape) and (min-width: 640px)").matches;
function tabGeom() {
  const nav = $("#tabs"), nr = nav.getBoundingClientRect(), v = vertical();
  return [...nav.querySelectorAll(":scope > button[data-tab]")].map((b) => { const r = b.getBoundingClientRect();
    return { id: b.dataset.tab, x: v ? r.top - nr.top : r.left - nr.left, w: v ? r.height : r.width, el: b }; });
}
function renderInd() {
  const el = $("#tabs .ind");
  if (!el) return;
  const v = vertical();
  const stretch = Math.min(0.28, Math.abs(ind.v) / 2600);
  const lift = ind.drag ? 1.08 : 1;
  const key = (v ? "h" : "w") + ind.w;
  if (el._w !== key) { el._w = key; el.style.width = ind.w + "px"; el.style.height = v ? ind.w + "px" : ""; }
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
    const k = 560, c = 22;
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
    let x = toBar(pos);
    if (x < min) x = min - (min - x) * 0.3;
    if (x > max) x = max + (x - max) * 0.3;
    const now = performance.now();
    ind.v = ((pos - lastX) / Math.max(1, now - lastT)) * 1000;
    lastX = pos; lastT = now;
    ind.x = x;
    const centre = x + ind.w / 2;
    for (const t of g) t.el.classList.toggle("hover", centre >= t.x && centre < t.x + t.w);
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
    ind.settle = setTimeout(() => { const e2 = $("#tabs .ind"); if (e2) e2._w = null; moveInd(stack[0].type, true); }, 450);
  });
  new ResizeObserver(() => { if (ind.drag) return; const e2 = $("#tabs .ind"); if (e2) e2._w = null; moveInd(stack[0].type, !ind.raf || true); }).observe(nav);
  nav.addEventListener("transitionend", (e) => { if (e.propertyName === "height" && e.target.matches("button[data-tab]")) { const el = $("#tabs .ind"); if (el) el._w = null; moveInd(stack[0].type); } });
}
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
  const cur = stack[stack.length - 1];
  const el = render(cur);
  $("#pages").append(el);
  pageEl = el;
  // Restore scroll position if returning to an artist page
  if (cur.type === "artist") {
    const saved = artistScrollPositions.get(cur.name.toLowerCase()) || 0;
    if (saved > 0) {
      quietScrollUntil = performance.now() + 180;
      el.scrollTop = saved;
      if (saved > 8) el.classList.add("scrolled");
    }
  }
  markTab();
  nc.header(el, el.classList.contains("scrolled"));
  if (old) {
    if (motion() && dir) {
      const [ax, ay] = { left: [1, 0], right: [-1, 0], up: [0, 1], down: [0, -1] }[dir];
      const o = { duration: 380, easing: "cubic-bezier(.2,.8,.2,1)" };
      const startT = old.style.transform || "none";
      el.animate([{ transform: `translate(${ax * 40}%, ${ay * 14}%)`, opacity: 0 }, { transform: "none", opacity: 1 }], o);
      old.style.pointerEvents = "none";
      old.animate([{ transform: startT, opacity: 1 }, { transform: `translate(${-ax * 30}%, ${-ay * 8}%)`, opacity: 0 }], o).onfinish = () => old.remove();
    } else old.remove();
  }
}

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
    active = !sel && e.touches.length === 1 && !e.target.closest(".hscroll, .chips, input, .eqviz, #mini");
    if (!active) return;
    sx = e.touches[0].clientX; sy = e.touches[0].clientY; dx = 0; decided = horiz = false;
  }, { passive: true });
  pages.addEventListener("touchmove", (e) => {
    if (!active) return;
    const t = e.touches[0];
    dx = t.clientX - sx;
    const dy = t.clientY - sy;
    if (!decided && (Math.abs(dx) > 14 || Math.abs(dy) > 14)) { decided = true; horiz = Math.abs(dx) > Math.abs(dy) * 1.4; }
    if (!horiz || !pageEl) return;
    const ok = !!target(dx);
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
  artistScrollPositions.clear();
  if (stack.length === 1 && stack[0].type === id) { pageEl?.scrollTo({ top: 0, behavior: "smooth" }); return; }
  const from = TABS.findIndex((t) => t.id === stack[0].type), to = TABS.findIndex((t) => t.id === id);
  stack = [{ type: id }];
  show(to >= from ? "left" : "right");
}
function push(page) {
  if (page.type === "settings" && nc.on) return openNativeSettings();
  if (sel) endSelect();
  const cur = stack.at(-1);
  if (cur?.type === "artist" && pageEl) {
    artistScrollPositions.set(cur.name.toLowerCase(), pageEl.scrollTop);
  }
  stack.push(page);
  show("left");
}
function pop() {
  if (stack.length > 1) {
    const leaving = stack.pop();
    if (leaving.type === "artist") {
      artistScrollPositions.delete(leaving.name.toLowerCase());
    }
    show("right");
    return true;
  }
  return false;
}
function refresh() {
  if (!pageEl) return;
  sideLists();
  const top = pageEl.scrollTop;
  const el = render(stack[stack.length - 1]);
  pageEl.replaceWith(el);
  pageEl = el;
  quietScrollUntil = performance.now() + 150;
  el.scrollTop = top;
  if (top > 8) el.classList.add("scrolled");
}

function pageShell(title, { back = stack.length > 1, actions = [] } = {}) {
  const page = h("div", { class: "page" });
  const head = h("div", { class: "head" }, back && h("button", { class: "ib back", onclick: pop }, icon("back")), h("h1", {}, title), ...actions.filter(Boolean));
  page.append(head);
  if (nc.on) {
    let t = 0;
    new MutationObserver(() => { clearTimeout(t); t = setTimeout(() => { if (page === pageEl) nc.header(page, page.classList.contains("scrolled")); }, 30); })
      .observe(head, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ["class"] });
  }
  let lastTop = 0;
  page.addEventListener("scroll", () => {
    const was = page.classList.contains("scrolled");
    page.classList.toggle("scrolled", page.scrollTop > 8);
    const cur = stack.at(-1);
    if (cur?.type === "artist" && page === pageEl) {
      artistScrollPositions.set(cur.name.toLowerCase(), page.scrollTop);
    }
    if (nc.on && page === pageEl && (was !== page.scrollTop > 8 || page.querySelector(".bplay"))) nc.header(page, page.scrollTop > 8);
    if (Math.abs(page.scrollTop - lastTop) > 4 && performance.now() > quietScrollUntil) miniTuck.scrolled();
    lastTop = page.scrollTop;
  }, { passive: true });
  return page;
}
// ---------------------------------------------------------------- filters (Albums, Artists, Songs, Favorites)
const filt = { genres: new Set(), artists: new Set(), decades: new Set(), length: new Set(), fav: false, recent: false };
const filtCount = () => filt.genres.size + filt.artists.size + filt.decades.size + filt.length.size + (filt.fav ? 1 : 0) + (filt.recent ? 1 : 0);
const artistNames = (s) => [...new Set([...splitArtists(s.artist), ...splitArtists(s.albumartist)])];
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
        h("button", { class: "chip" + (isOn(k) ? " on" : ""), onclick: ()