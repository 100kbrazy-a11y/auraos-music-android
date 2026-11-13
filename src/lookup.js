// Online song / album info: iTunes + Deezer (same sources as the desktop app's lookup).
const norm = (s) => String(s || "").toLowerCase().normalize("NFKD").replace(/[̀-ͯ]/g, "")
  .replace(/\(.*?\)|\[.*?\]/g, " ").replace(/feat\.?.*$|ft\..*$/g, " ").replace(/[^a-z0-9]+/g, "");
export const same = (a, b) => { const x = norm(a), y = norm(b); return !!x && !!y && (x === y || x.includes(y) || y.includes(x)); };

function sim(a, b) {
  const x = norm(a), y = norm(b);
  if (!x || !y) return 0;
  if (x === y) return 1;
  if (x.includes(y) || y.includes(x)) return 0.8;
  const grams = (s) => { const g = new Set(); for (let i = 0; i < s.length - 1; i++) g.add(s.slice(i, i + 2)); return g; };
  const A = grams(x), B = grams(y);
  let n = 0; for (const g of A) if (B.has(g)) n++;
  return (2 * n) / (A.size + B.size || 1);
}

import { getJson } from "./http.js";
async function json(url) {
  return (await getJson(url)) || {};
}
const big = (u) => (u || "").replace(/\/\d+x\d+bb\./, "/1000x1000bb.");
const small = (u) => (u || "").replace(/\/\d+x\d+bb\./, "/200x200bb.");
const year = (d) => (d || "").slice(0, 4);

async function itunes(term, entity) {
  const q = new URLSearchParams({ term, media: "music", entity, limit: "25" });
  const res = (await json("https://itunes.apple.com/search?" + q)).results || [];
  return res.map((r) => entity === "song" ? {
    source: "iTunes", kind: "song", id: "it" + r.trackId, itAlbum: r.collectionId,
    title: r.trackName, artist: r.artistName, album: r.collectionName, albumartist: r.collectionArtistName || "",
    year: year(r.releaseDate), genre: r.primaryGenreName || "", track: r.trackNumber || 0, trackCount: r.trackCount || 0,
    disc: r.discNumber || 0, duration: (r.trackTimeMillis || 0) / 1000, thumb: small(r.artworkUrl100), cover: big(r.artworkUrl100),
  } : {
    source: "iTunes", kind: "album", id: "ia" + r.collectionId, itAlbum: r.collectionId,
    album: r.collectionName, artist: r.artistName, albumartist: r.artistName, year: year(r.releaseDate), genre: r.primaryGenreName || "",
    trackCount: r.trackCount || 0, copyright: r.copyright || "", thumb: small(r.artworkUrl100), cover: big(r.artworkUrl100),
  });
}

async function deezer(term, entity) {
  const res = (await json(`https://api.deezer.com/search${entity === "album" ? "/album" : ""}?` + new URLSearchParams({ q: term, limit: "25" }))).data || [];
  return res.map((r) => entity === "song" ? {
    source: "Deezer", kind: "song", id: "dz" + r.id, dzTrack: r.id, dzAlbum: r.album?.id,
    title: r.title, artist: r.artist?.name || "", album: r.album?.title || "", duration: r.duration || 0,
    thumb: r.album?.cover_medium || "", cover: r.album?.cover_xl || r.album?.cover_big || "",
  } : {
    source: "Deezer", kind: "album", id: "dza" + r.id, dzAlbum: r.id,
    album: r.title, artist: r.artist?.name || "", albumartist: r.artist?.name || "", trackCount: r.nb_tracks || 0,
    thumb: r.cover_medium || "", cover: r.cover_xl || r.cover_big || "",
  });
}

/** Search both services. `like` is the song/album we're looking up (for ranking). */
export async function search(term, kind, like = {}) {
  const entity = kind === "album" ? "album" : "song";
  const all = (await Promise.allSettled([itunes(term, entity), deezer(term, entity)])).flatMap((p) => (p.status === "fulfilled" ? p.value : []));
  if (!all.length) {
    const errs = await Promise.allSettled([json("https://itunes.apple.com/search?term=a&limit=1")]);
    if (errs[0].status === "rejected") throw new Error("offline");
  }
  for (const r of all) {
    let s = 0;
    if (kind === "album") {
      s += 3 * sim(r.album, like.album || term) + 2 * sim(r.artist, like.artist);
      if (like.tracks && r.trackCount) s += r.trackCount === like.tracks ? 1 : -0.3 * Math.min(3, Math.abs(r.trackCount - like.tracks)) / 3;
    } else {
      s += 3 * sim(r.title, like.title || term) + 2 * sim(r.artist, like.artist) + sim(r.album, like.album);
      if (like.duration && r.duration) s += Math.max(-1, 1 - Math.abs(r.duration - like.duration) / 4);
    }
    if (r.source === "iTunes") s += 0.15;           // iTunes results carry year + genre
    r.score = s;
  }
  // drop exact duplicates (same title/artist/album from both services), keeping the better one
  const seen = new Map();
  for (const r of all.sort((a, b) => b.score - a.score)) {
    const k = norm(r.title) + "|" + norm(r.artist) + "|" + norm(r.album);
    const keep = seen.get(k);
    if (!keep) { seen.set(k, r); continue; }
    // the same song on both services: one row, details from both
    for (const f of ["dzTrack", "dzAlbum", "itAlbum", "year", "genre", "track", "disc", "trackCount", "albumartist", "copyright", "cover", "thumb"]) keep[f] ||= r[f];
    if (!keep.source.includes(r.source)) keep.source += " + " + r.source;
  }
  return [...seen.values()];
}

/** Fill in the extra details a result can have (year, genre, label, ISRC, BPM, track list). */
export async function details(r) {
  const d = { ...r };
  try {
    if (r.dzTrack) {
      const t = await json(`https://api.deezer.com/track/${r.dzTrack}`);
      Object.assign(d, { isrc: t.isrc || "", bpm: t.bpm ? Math.round(t.bpm) : "", track: t.track_position || d.track || 0, disc: t.disk_number || d.disc || 0, year: year(t.release_date) || d.year });
    }
    if (r.dzAlbum) {
      const a = await json(`https://api.deezer.com/album/${r.dzAlbum}`);
      Object.assign(d, { year: d.year || year(a.release_date), genre: d.genre || (a.genres?.data || []).map((g) => g.name).join(", "), label: a.label || "",
        albumartist: d.albumartist || a.artist?.name || "", trackCount: a.nb_tracks || d.trackCount,
        tracks: (a.tracks?.data || []).map((t, i) => ({ title: t.title, track: i + 1, disc: 1 })) });
    }
    if (r.itAlbum) {
      const res = (await json(`https://itunes.apple.com/lookup?id=${r.itAlbum}&entity=song`)).results || [];
      const coll = res.find((x) => x.wrapperType === "collection");
      if (coll) d.copyright ||= coll.copyright || "";
      const tr = res.filter((x) => x.wrapperType === "track");
      if (tr.length) d.tracks = tr.map((t) => ({ title: t.trackName, track: t.trackNumber, disc: t.discNumber || 1 }));
    }
  } catch (e) { console.warn("details", e); }
  return d;
}

/** For automatic filling: the single best match if we're confident, else null. */
export async function bestSongMatch(s) {
  const artist = (s.artist || "").split(",")[0];
  const res = await search(`${artist} ${s.title}`.trim(), "song", s);
  const r = res[0];
  if (!r || !same(r.title, s.title)) return null;
  if (s.artist && !same(r.artist, artist)) return null;
  if (s.duration && r.duration && Math.abs(r.duration - s.duration) > 6) return null;
  return r;
}
export async function bestAlbumMatch(a) {
  const res = await search(`${a.artist} ${a.title}`.trim(), "album", { album: a.title, artist: a.artist, tracks: a.tracks.length });
  const r = res.find((x) => same(x.album, a.title) && (!a.artist || a.artist === "Unknown Artist" || same(x.artist, a.artist)));
  return r || null;
}
