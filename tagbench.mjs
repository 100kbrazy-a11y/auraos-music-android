import { parseBlob } from "music-metadata";
import fs from "fs";
import { globSync } from "glob";
const files = globSync("/home/claude/promo/demo/**/*.mp3").slice(0, 20);
const blobs = files.map((f) => new Blob([fs.readFileSync(f)], { type: "audio/mpeg" }));
for (const [label, opt, head] of [["full scan (now)", { duration: true }, 0], ["headers only", { duration: false, skipPostHeaders: true }, 0], ["headers, first 1 MB", { duration: false, skipPostHeaders: true }, 1 << 20]]) {
  const t = performance.now(); let got = 0, dur = 0;
  for (const b of blobs) { const m = await parseBlob(head ? b.slice(0, head) : b, opt); if (m.common.title && m.common.artist) got++; if (m.format.duration) dur++; }
  console.log(label.padEnd(22), ((performance.now() - t) / blobs.length).toFixed(1), "ms/song · title+artist", got, "/", blobs.length, "· duration known", dur);
}
