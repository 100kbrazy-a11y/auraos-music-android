// Minimal streaming-friendly ZIP reader/writer for music packs.
// Audio is "stored" (already compressed), so entries are sliced straight out of the Blob — nothing big is loaded into memory.

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

export async function crc32Blob(blob, onChunk) {
  let c = 0xffffffff;
  const CH = 4 << 20;
  for (let off = 0; off < blob.size; off += CH) {
    const buf = new Uint8Array(await blob.slice(off, off + CH).arrayBuffer());
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 255] ^ (c >>> 8);
    onChunk && onChunk(Math.min(blob.size, off + CH));
  }
  return (c ^ 0xffffffff) >>> 0;
}

const enc = new TextEncoder();

function dosTime(d = new Date()) {
  const time = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
  const date = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  return { time, date };
}

/** entries: [{name, blob}] → Blob of a valid zip (all entries stored). */
export async function buildZip(entries, onProgress) {
  const parts = [];
  const central = [];
  let offset = 0;
  const { time, date } = dosTime();
  const total = entries.reduce((a, e) => a + e.blob.size, 0) || 1;
  let done = 0;
  for (const e of entries) {
    const name = enc.encode(e.name);
    const crc = await crc32Blob(e.blob, (n) => onProgress && onProgress((done + n) / total));
    done += e.blob.size;
    const size = e.blob.size;
    if (offset + size > 0xfffffff0) throw new Error("That's more than 4 GB — pick fewer songs for one pack.");
    const lh = new DataView(new ArrayBuffer(30));
    lh.setUint32(0, 0x04034b50, true);
    lh.setUint16(4, 20, true);
    lh.setUint16(6, 0x0800, true);          // UTF-8 names
    lh.setUint16(8, 0, true);               // stored
    lh.setUint16(10, time, true);
    lh.setUint16(12, date, true);
    lh.setUint32(14, crc, true);
    lh.setUint32(18, size, true);
    lh.setUint32(22, size, true);
    lh.setUint16(26, name.length, true);
    lh.setUint16(28, 0, true);
    parts.push(lh.buffer, name, e.blob);
    const ch = new DataView(new ArrayBuffer(46));
    ch.setUint32(0, 0x02014b50, true);
    ch.setUint16(4, 0x0314, true);
    ch.setUint16(6, 20, true);
    ch.setUint16(8, 0x0800, true);
    ch.setUint16(10, 0, true);
    ch.setUint16(12, time, true);
    ch.setUint16(14, date, true);
    ch.setUint32(16, crc, true);
    ch.setUint32(20, size, true);
    ch.setUint32(24, size, true);
    ch.setUint16(28, name.length, true);
    ch.setUint32(38, (0o100644 << 16) >>> 0, true);
    ch.setUint32(42, offset, true);
    central.push(ch.buffer, name);
    offset += 30 + name.length + size;
  }
  const cdSize = central.reduce((a, b) => a + b.byteLength, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, entries.length, true);
  end.setUint16(10, entries.length, true);
  end.setUint32(12, cdSize, true);
  end.setUint32(16, offset, true);
  return new Blob([...parts, ...central, end.buffer], { type: "application/zip" });
}

/** Read the table of contents of a zip Blob. Returns [{name, size, blob(): Promise<Blob>}] */
export async function readZip(file) {
  const tailLen = Math.min(file.size, 65557 + 20);
  const tail = new DataView(await file.slice(file.size - tailLen).arrayBuffer());
  let eocd = -1;
  for (let i = tail.byteLength - 22; i >= 0; i--) {
    if (tail.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error("That isn't a zip file (or it's damaged).");
  let count = tail.getUint16(eocd + 10, true);
  let cdSize = tail.getUint32(eocd + 12, true);
  let cdOff = tail.getUint32(eocd + 16, true);
  if (eocd >= 20 && tail.getUint32(eocd - 20, true) === 0x07064b50) {       // zip64
    const z64 = Number(tail.getBigUint64(eocd - 20 + 8, true));
    const zd = new DataView(await file.slice(z64, z64 + 56).arrayBuffer());
    count = Number(zd.getBigUint64(32, true));
    cdSize = Number(zd.getBigUint64(40, true));
    cdOff = Number(zd.getBigUint64(48, true));
  }
  const cd = new DataView(await file.slice(cdOff, cdOff + cdSize).arrayBuffer());
  const dec = new TextDecoder();
  const out = [];
  let p = 0;
  for (let n = 0; n < count && p + 46 <= cd.byteLength; n++) {
    if (cd.getUint32(p, true) !== 0x02014b50) break;
    const method = cd.getUint16(p + 10, true);
    let csize = cd.getUint32(p + 20, true);
    let usize = cd.getUint32(p + 24, true);
    const nl = cd.getUint16(p + 28, true), xl = cd.getUint16(p + 30, true), cl = cd.getUint16(p + 32, true);
    let lo = cd.getUint32(p + 42, true);
    const name = dec.decode(new Uint8Array(cd.buffer, p + 46, nl));
    let x = p + 46 + nl;
    const xend = x + xl;
    while (x + 4 <= xend) {
      const id = cd.getUint16(x, true), len = cd.getUint16(x + 2, true);
      if (id === 1) {
        let q = x + 4;
        if (usize === 0xffffffff) { usize = Number(cd.getBigUint64(q, true)); q += 8; }
        if (csize === 0xffffffff) { csize = Number(cd.getBigUint64(q, true)); q += 8; }
        if (lo === 0xffffffff) { lo = Number(cd.getBigUint64(q, true)); }
      }
      x += 4 + len;
    }
    p = xend + cl;
    if (name.endsWith("/")) continue;
    out.push({
      name, size: usize, method,
      async blob() {
        const lh = new DataView(await file.slice(lo, lo + 30).arrayBuffer());
        const start = lo + 30 + lh.getUint16(26, true) + lh.getUint16(28, true);
        const raw = file.slice(start, start + csize);
        if (method === 0) return raw.realize ? raw.realize() : raw;        // (read from disk: fetch just this entry)
        if (method === 8) return new Response(raw.stream().pipeThrough(new DecompressionStream("deflate-raw"))).blob();
        throw new Error("Unsupported compression in " + name);
      },
    });
  }
  return out;
}
