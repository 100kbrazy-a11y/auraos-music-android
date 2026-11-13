// IndexedDB: song info, the audio files themselves (Chromium keeps big Blobs on disk), covers, settings.
let dbp;
function db() {
  return (dbp ||= new Promise((res, rej) => {
    const r = indexedDB.open("auraos-music", 1);
    r.onupgradeneeded = () => {
      const d = r.result;
      d.createObjectStore("songs", { keyPath: "id" });
      d.createObjectStore("files");
      d.createObjectStore("covers");
      d.createObjectStore("kv");
    };
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  }));
}

function req(r) {
  return new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
}

async function store(name, mode = "readonly") {
  return (await db()).transaction(name, mode).objectStore(name);
}

export const idb = {
  async get(s, k) { return req((await store(s)).get(k)); },
  async put(s, v, k) { return req(k === undefined ? (await store(s, "readwrite")).put(v) : (await store(s, "readwrite")).put(v, k)); },
  async del(s, k) { return req((await store(s, "readwrite")).delete(k)); },
  async all(s) { return req((await store(s)).getAll()); },
  async keys(s) { return req((await store(s)).getAllKeys()); },
  async clear(s) { return req((await store(s, "readwrite")).clear()); },
};

export async function persistStorage() {
  try { if (navigator.storage?.persist) await navigator.storage.persist(); } catch {}
}

export async function usage() {
  try { return await navigator.storage.estimate(); } catch { return {}; }
}
