// A small log of what the app is doing, kept on the phone (Settings › Diagnostics), for tracking down problems
// without a computer attached. Also: the current step, shown under import progress bars.
const KEY = "auraos-diag";
const lines = (() => { try { return JSON.parse(localStorage.getItem(KEY) || "[]"); } catch { return []; } })();
let saveT = 0;
export const diag = {
  step: "",
  log(...parts) {
    const msg = parts.map((p) => (p instanceof Error ? `${p.name}: ${p.message}` : typeof p === "object" ? safeJson(p) : String(p))).join(" ");
    const d = new Date();
    lines.push(`${d.toLocaleTimeString([], { hour12: false })}.${String(d.getMilliseconds()).padStart(3, "0")} ${msg}`);
    if (lines.length > 400) lines.splice(0, lines.length - 400);
    clearTimeout(saveT);
    saveT = setTimeout(() => { try { localStorage.setItem(KEY, JSON.stringify(lines)); } catch {} }, 400);
  },
  /** what's happening right now (also logged) */
  set(step) { this.step = step; this.log(step); },
  text() { return lines.join("\n"); },
  /** the last few lines, without the time */
  recent(n = 3) { return lines.slice(-n).map((l) => l.replace(/^\S+ /, "")); },
  clear() { lines.length = 0; try { localStorage.removeItem(KEY); } catch {} },
};
function safeJson(o) { try { return JSON.stringify(o).slice(0, 400); } catch { return String(o); } }

/** Rejects (with the step's name) if it takes longer than ms, instead of hanging for ever. */
export function timed(p, ms, what) {
  let t;
  return Promise.race([p, new Promise((_, rej) => { t = setTimeout(() => rej(new Error(`${what} took longer than ${Math.round(ms / 1000)} s`)), ms); })])
    .finally(() => clearTimeout(t));
}

// record errors and warnings
const wrap = (k) => { const f = console[k].bind(console); console[k] = (...a) => { diag.log(k.toUpperCase(), ...a); f(...a); }; };
wrap("warn"); wrap("error");
addEventListener("error", (e) => diag.log("ERROR", e.message, `${(e.filename || "").replace(/^.*\//, "")}:${e.lineno}`));
addEventListener("unhandledrejection", (e) => diag.log("UNHANDLED", e.reason instanceof Error ? e.reason : String(e.reason)));
diag.log(`app start · ${navigator.userAgent.replace(/^.*?\(/, "(").slice(0, 90)}`);
