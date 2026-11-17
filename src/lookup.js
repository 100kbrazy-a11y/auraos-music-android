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

// ============================================================ genres: several sources, and suggestions when unsure
/** "Song (feat. X) [Official Audio] - Remastered 2011" → "Song" */
export const cleanTitle = (t) => String(t || "")
  .replace(/\s*[([{][^)\]}]*\b(feat|ft|with|prod|remaster|official|audio|video|lyrics?|visuali[sz]er|explicit|clean|version|edit|mono|stereo|live|bonus)\b[^)\]}]*[)\]}]/gi, "")
  .replace(/\s+[-–—]\s+.*\b(remaster(ed)?|version|edit|mix|live|mono|stereo|single)\b.*$/i, "")
  .replace(/\s+(feat|ft)\.?\s.*$/i, "").trim();
const mainArtist = (a) => String(a || "").split(/\s*(?:,|&|\bx\b|\bfeat\.?|\bft\.?|\bwith\b|\/)\s*/i)[0].trim();

// words that make a MusicBrainz tag a genre (its tags also include things like "seen live" or "american")
const GENREISH = /\b(rock|pop|rap|hip[ -]?hop|trap|drill|r&b|rnb|soul|funk|jazz|blues|metal|core|punk|emo|indie|alternative|grunge|shoegaze|electronic|electro|house|techno|trance|dubstep|drum and bass|dnb|edm|dance|disco|garage|ambient|lo-?fi|synth|wave|country|folk|bluegrass|americana|gospel|classical|orchestral|opera|soundtrack|reggae|reggaeton|dancehall|latin|salsa|bachata|cumbia|afro|amapiano|k-?pop|j-?pop|city pop|ska|grime|phonk|hyperpop|experimental|psychedelic|singer-songwriter|acoustic|chill|downtempo|trip[ -]?hop|breakbeat|jungle|bass|hardstyle|gabber|industrial|noise|post-|neo-|gangsta|boom bap|cloud rap|rage|plugg|g-funk|crunk|bounce|bossa|samba|flamenco|fado|celtic|world|new age|swing|bebop|big band|motown|doo-wop|rockabilly|surf|stoner|doom|black metal|death metal|thrash|nu metal|metalcore|deathcore|post-rock|math rock|art pop|dream pop|bedroom pop|chamber|baroque|minimal|idm|glitch|footwork|juke|afrobeats?)\b/i;
const NICE = { "hip hop": "Hip-Hop", "hip-hop": "Hip-Hop", "rnb": "R&B", "r&b": "R&B", "rhythm and blues": "R&B", "edm": "EDM", "lo-fi": "Lo-Fi", "lofi": "Lo-Fi",
  "k-pop": "K-Pop", "kpop": "K-Pop", "j-pop": "J-Pop", "dnb": "Drum and Bass", "uk garage": "UK Garage", "idm": "IDM", "hip-hop/rap": "Hip-Hop/Rap" };
export function niceGenre(g) {
  const k = String(g || "").trim().toLowerCase();
  if (!k) return "";
  return NICE[k] || k.replace(/\b[a-z]/g, (c) => c.toUpperCase()).replace(/\bAnd\b/g, "and").replace(/\bR&b\b/g, "R&B");
}
export const isGenre = (g) => GENREISH.test(String(g || ""));

// MusicBrainz asks for at most one request a second and a name for the app
let mbAt = 0;
async function mb(path, params) {
  const wait = mbAt + 1100 - Date.now();
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  mbAt = Date.now();
  return (await getJson(`https://musicbrainz.org/ws/2/${path}?` + new URLSearchParams({ ...params, fmt: "json" }),
    { "User-Agent": "StrataMusic/1.0 ( https://github.com/100kbrazy-a11y/auraos-music-android )", Accept: "application/json" })) || {};
}
const mbTags = (x) => (x?.genres?.length ? x.genres : x?.tags || []).filter((t) => (t.count ?? 1) > 0 && isGenre(t.name))
  .sort((a, b) => (b.count || 0) - (a.count || 0)).map((t) => niceGenre(t.name));

/** An artist's genres: iTunes' artist listing, then MusicBrainz (strongest first). */
export async function artistGenres(name) {
  name = mainArtist(name);
  if (!name || /^unknown/i.test(name)) return [];
  const out = [];
  try {
    const r = (await json("https://itunes.apple.com/search?" + new URLSearchParams({ term: name, media: "music", entity: "musicArtist", limit: "10" }))).results || [];
    const m = r.find((x) => same(x.artistName, name) && norm(x.artistName).length >= norm(name).length - 1);
    if (m?.primaryGenreName && isGenre(m.primaryGenreName)) out.push(niceGenre(m.primaryGenreName));
  } catch {}
  try {
    const r = await mb("artist/", { query: `artist:"${name.replace(/"/g, "")}"`, limit: "5" });
    const a = (r.artists || []).find((x) => same(x.name, name) && (x.score || 0) >= 90);
    if (a) {
      let tags = mbTags(a);
      if (!tags.length && a.id) { const full = await mb(`artist/${a.id}`, { inc: "genres+tags" }); tags = mbTags(full); }
      out.push(...tags.slice(0, 3));
    }
  } catch {}
  return [...new Set(out)];
}

/**
 * Genre for a song: tries the song as it's tagged, then with a cleaned-up title and just the main artist.
 * Returns { genre (when sure), maybe: [genres from near matches] }.
 */
export async function songGenre(s) {
  const maybe = [];
  const tries = [];
  const t0 = s.title || "", a0 = s.artist || "", t1 = cleanTitle(t0), a1 = mainArtist(a0);
  tries.push([t0, a0]);
  if (t1 && (t1 !== t0 || a1 !== a0)) tries.push([t1, a1]);
  for (const [title, artist] of tries) {
    let res = [];
    try { res = await search(`${artist} ${title}`.trim(), "song", { ...s, title, artist }); } catch { continue; }
    for (const r of res.slice(0, 6)) {
      const g = r.genre || "";
      const titleOk = same(r.title, title) || same(cleanTitle(r.title), title);
      const artistOk = !artist || same(r.artist, artist) || same(mainArtist(r.artist), artist);
      const durOk = !s.duration || !r.duration || Math.abs(r.duration - s.duration) <= 8;
      if (titleOk && artistOk && durOk) {
        if (g) return { genre: g, maybe };
        try { const d = await details(r); if (d.genre) return { genre: d.genre, maybe }; } catch {}
      } else if (g && (artistOk || titleOk)) maybe.push(niceGenre(g));      // close, but not sure: a suggestion
    }
  }
  return { genre: "", maybe: [...new Set(maybe)] };
}

/** Genres people pick from when nothing was found */
export const COMMON_GENRES = ["Hip-Hop/Rap", "Trap", "Drill", "R&B", "Pop", "Rock", "Alternative", "Indie", "Metal", "Punk", "Emo", "Electronic", "House",
  "Techno", "Dance", "Drum and Bass", "Dubstep", "Lo-Fi", "Ambient", "Jazz", "Soul", "Funk", "Blues", "Country", "Folk", "Singer-Songwriter",
  "Reggae", "Reggaeton", "Latin", "Afrobeats", "K-Pop", "J-Pop", "Classical", "Soundtrack", "Gospel", "Phonk", "Hyperpop", "Experimental"];
