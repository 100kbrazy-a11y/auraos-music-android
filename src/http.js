// Web requests. On the phone they go through Android's own networking (no browser CORS rules), in a browser through fetch.
import { Capacitor, CapacitorHttp } from "@capacitor/core";

const native = Capacitor.isNativePlatform();

export async function getJson(url, headers = {}) {
  if (native) {
    const r = await CapacitorHttp.get({ url, headers, responseType: "json", connectTimeout: 15000, readTimeout: 20000 });
    if (r.status === 404) return null;
    if (r.status >= 400) throw new Error("HTTP " + r.status);
    if (typeof r.data === "string") { try { return JSON.parse(r.data || "null"); } catch { return null; } }
    return r.data;
  }
  const r = await fetch(url, { headers });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error("HTTP " + r.status);
  return r.json();
}

function b64ToBlob(b64, type) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return new Blob([out], { type });
}

/** Download a file. onProgress(fraction) is called when the size is known. */
export async function getBlob(url, onProgress) {
  if (native) {
    const r = await CapacitorHttp.get({ url, responseType: "blob", connectTimeout: 20000, readTimeout: 120000 });
    if (r.status >= 400) throw new Error("HTTP " + r.status);
    const type = (r.headers?.["Content-Type"] || r.headers?.["content-type"] || "").split(";")[0];
    onProgress?.(1);
    return typeof r.data === "string" ? b64ToBlob(r.data, type) : new Blob([r.data], { type });
  }
  const r = await fetch(url);
  if (!r.ok) throw new Error("HTTP " + r.status);
  const total = +r.headers.get("content-length") || 0;
  if (!total || !r.body) return r.blob();
  const reader = r.body.getReader();
  const parts = [];
  let got = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(value);
    got += value.length;
    onProgress?.(got / total);
  }
  return new Blob(parts, { type: r.headers.get("content-type") || "" });
}

// small MD5 (needed for Subsonic / Navidrome logins)
export function md5(str) {
  const bytes = new TextEncoder().encode(str);
  const K = new Int32Array(64), S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
  for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) | 0;
  const len = bytes.length, nBlocks = ((len + 8) >>> 6) + 1;
  const w = new Int32Array(nBlocks * 16);
  for (let i = 0; i < len; i++) w[i >> 2] |= bytes[i] << ((i % 4) * 8);
  w[len >> 2] |= 0x80 << ((len % 4) * 8);
  w[nBlocks * 16 - 2] = len * 8;
  w[nBlocks * 16 - 1] = Math.floor(len / 536870912);
  let a0 = 0x67452301, b0 = 0xefcdab89 | 0, c0 = 0x98badcfe | 0, d0 = 0x10325476;
  for (let blk = 0; blk < nBlocks; blk++) {
    let a = a0, b = b0, c = c0, d = d0;
    for (let i = 0; i < 64; i++) {
      let f, g;
      if (i < 16) { f = (b & c) | (~b & d); g = i; }
      else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) % 16; }
      else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16; }
      else { f = c ^ (b | ~d); g = (7 * i) % 16; }
      const s = S[(i >> 4) * 4 + (i % 4)];
      const t = d; d = c; c = b;
      const x = (a + f + K[i] + w[blk * 16 + g]) | 0;
      b = (b + ((x << s) | (x >>> (32 - s)))) | 0;
      a = t;
    }
    a0 = (a0 + a) | 0; b0 = (b0 + b) | 0; c0 = (c0 + c) | 0; d0 = (d0 + d) | 0;
  }
  return [a0, b0, c0, d0].map((v) => [0, 8, 16, 24].map((s) => ((v >>> s) & 255).toString(16).padStart(2, "0")).join("")).join("");
}
