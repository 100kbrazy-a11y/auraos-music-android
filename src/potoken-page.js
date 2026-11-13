// Runs INSIDE a hidden page that the phone treats as www.youtube.com (made natively by the app). Google only hands
// proof-of-origin tokens to YouTube's own pages, so the check has to run there, not in the app's own page.
// Exposes window.__auraMint(videoId) → a token for that song.
import { getChallenge, BotGuardClient } from "bgutils-js/botguard";
import { WebPoMinter } from "bgutils-js/webpo";
import { buildURL, getHeaders } from "bgutils-js/utils";

const KEY = "O43z0dpjhgX20SCx4KAo";
let minterP = null;

async function makeMinter() {
  const ch = await getChallenge({ requestKey: KEY, fetchFunction: (u, i) => fetch(u, i), useYouTubeAPI: true });
  let js = ch.interpreterJavascript?.privateDoNotAccessOrElseSafeScriptWrappedValue || "";
  if (!js && ch.interpreterUrl) {
    let u = ch.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue || "";
    if (u.startsWith("//")) u = "https:" + u;
    js = await (await fetch(u)).text();
  }
  if (!js || !ch.program || !ch.globalName) throw new Error("YouTube's check didn't arrive");
  new Function(js)();
  const bg = await BotGuardClient.create({ program: ch.program, globalName: ch.globalName, globalObject: window });
  const signal = [];
  const answer = await bg.snapshot({ webPoSignalOutput: signal });
  const r = await fetch(buildURL("GenerateIT", true), { method: "POST", headers: getHeaders(), body: JSON.stringify([KEY, answer]) });
  const txt = await r.text();
  let arr = null;
  try { arr = JSON.parse(txt); } catch {}
  if (!Array.isArray(arr) || !arr[0]) throw new Error(`Google refused the token (HTTP ${r.status}): ${txt.slice(0, 160)}`);
  const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] = arr;
  const m = await WebPoMinter.create({ integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken }, signal);
  setTimeout(() => { minterP = null; }, Math.max(300, (estimatedTtlSecs || 3600) - 300) * 1000);
  return m;
}

window.__auraMint = async (id) => {
  if (!minterP) minterP = makeMinter().catch((e) => { minterP = null; throw e; });
  return (await minterP).mintAsWebsafeString(id);
};
