
// 1. Fixing the Media Bar Tap issue across ALL full-screen buttons.
// When you tap the mini player, the click can 'bleed' through to the full screen 
// buttons if they are instantly rendered in the exact same spot during the morph animation.

/* 
  In your src/main.js, locate the buildNp() function.
  Find where all the controls are built (around line 247).
  
  Replace these lines:
  
  np.fav = ib("heart", () => { const s = player.current; if (s) { const on = lib.toggleFav([s.id]); updateNpFav(); nc.haptic(on ? "success" : "tap"); snack(on ? "Added to Favorites" : "Removed from Favorites"); refresh(); } });
  // ...
  np.shuffle = ib("shuffle", () => player.toggleShuffle(), "small dim");
  np.repeat = ib("repeat", () => player.cycleRepeat(), "small dim");
  np.pp = h("button", { class: "pp", onclick: () => player.toggle() }, icon("play"));
  // ...
  np.lyrBtn = ib("lyrics", () => toggleLyrics(), "", "Lyrics");
  // ...
  
  With this updated block that checks !morph.active for EVERY button:
*/

np.fav = ib("heart", () => { 
    if (morph.active) return;
    const s = player.current; 
    if (s) { 
        const on = lib.toggleFav([s.id]); 
        updateNpFav(); 
        nc.haptic(on ? "success" : "tap"); 
        snack(on ? "Added to Favorites" : "Removed from Favorites"); 
        refresh(); 
    } 
});

np.shuffle = ib("shuffle", () => { if (!morph.active) player.toggleShuffle(); }, "small dim");
np.repeat = ib("repeat", () => { if (!morph.active) player.cycleRepeat(); }, "small dim");
np.pp = h("button", { class: "pp", onclick: () => { if (!morph.active) player.toggle(); } }, icon("play"));

const btnPrev = ib("prev", () => { if (!morph.active) player.prev(); });
const btnNext = ib("next", () => { if (!morph.active) player.next(); });

np.lyrBtn = ib("lyrics", () => { if (!morph.active) toggleLyrics(); }, "", "Lyrics");
const btnEq = ib("eq", () => { if (!morph.active) { closeNp(); push({ type: "settings" }); } }, "", "Equalizer");
const btnQueue = ib("queue", () => { if (!morph.active) showQueue(); }, "", "Queue");
const btnMore = ib("more", () => { if (!morph.active && player.current) songMenu(player.current); });

/*
  Then, a few lines lower down, update the wrapper construction to use the new variables.
  Find this block:
  
  const wrap = h("div", { class: "wrap" },
    h("div", { class: "top" }, ib("down", () => closeNp(), "", "Close"), np.ctx, ib("more", () => player.current && songMenu(player.current))),
    // ...
    h("div", { class: "ctrls" }, np.shuffle, ib("prev", () => player.prev()), np.pp, ib("next", () => player.next()), np.repeat),
    h("div", { class: "extras" }, np.lyrBtn, ib("eq", () => { closeNp(); push({ type: "settings" }); }, "", "Equalizer"), ib("queue", () => showQueue(), "", "Queue")));
    
  Replace the top, ctrls, and extras lines with:
*/

// h("div", { class: "top" }, ib("down", () => { if (!morph.active) closeNp(); }, "", "Close"), np.ctx, btnMore),
// ...
// h("div", { class: "ctrls" }, np.shuffle, btnPrev, np.pp, btnNext, np.repeat),
// h("div", { class: "extras" }, np.lyrBtn, btnEq, btnQueue));



// 2. Modifying the addArtist function to use inline progress (from previous request)
async function addArtist(yt, link) {
  if (linkBusy) return snack("Already adding from a link…");
  linkBusy = true;
  try {
    let a;
    try { 
        // Read artist info first without popup
        a = await yt.readArtist(link, () => {}); 
    } catch (e) {
      diag.log("YouTube: " + (e.message || e));
      snack("Couldn't read that artist: " + (e.message || e), "Details", showDiagnostics, 12000);
      return;
    }
    
    const nAlb = a.albums.length, nSin = a.singles.length;
    const options = [];
    if (nAlb + nSin) options.push({ value: "all", icon: "album", label: "Albums, singles & EPs", sub: [nAlb && plural(nAlb, "album"), nSin && plural(nSin, "single / EP")].filter(Boolean).join(" · ") });
    if (nAlb && nSin) options.push({ value: "albums", icon: "album", label: "Albums only", sub: plural(nAlb, "album") });
    if (a.top.length) options.push({ value: "top", icon: "playlist", label: "Top songs", sub: plural(a.top.length, "song") + " · quickest" });
    if (!options.length) { snack(`Found ${a.name}, but there is nothing to download on that page`); return; }
    
    const what = await chooseSheet(
      h("div", { class: "sh-head" }, art(null, { label: a.name, round: true }), h("div", { class: "meta" }, h("div", { class: "t" }, a.name), h("div", { class: "s" }, "YouTube Music artist · what should I download?"))),
      options, "Songs you already have are skipped. Only add music you have the right to.");
    if (!what) return;
    
    const ctl = { stop: false };
    
    // --- UI CHANGES: Update the artist page instead of popping up a box ---
    const isCurrentPage = stack.at(-1).type === "artist" && stack.at(-1).name === a.name;
    const ytButton = pageEl?.querySelector(".ahp.round");
    const dlText = pageEl?.querySelector(".ah-dl-text");
    const dlBar = pageEl?.querySelector(".ah-dl-bar");
    const dlFill = pageEl?.querySelector(".ah-dl-bar b");
    
    if (isCurrentPage && ytButton && dlText && dlBar && dlFill) {
        ytButton.disabled = true; // Gray out YouTube button
        dlText.textContent = `Downloading ${a.name}'s music...`;
        dlText.classList.add("active");
        dlBar.classList.add("active");
    } else {
        snack(`Downloading ${a.name} in the background...`); // Fallback if not on their page
    }

    try {
      const r = await yt.importArtist(a, what, lib, (f, t) => {
          // Update the inline bar instead of progressBox
          if (isCurrentPage && dlFill) {
              dlFill.style.transform = `scaleX(${f})`;
          }
      }, ctl);
      
      setTimeout(() => autoFill(r.songs || []), 4600);
      let msg = r.added || r.skipped ? `Added ${plural(r.added, "song")} by ${a.name}` : `Couldn't download anything by ${a.name}${r.firstError ? ": " + r.firstError : ""}`;
      if (r.skipped) msg += ` · ${r.skipped} already in your library`;
      if (r.failed && (r.added || r.skipped)) msg += ` · ${r.failed} couldn't be downloaded (${r.firstError})`;
      
      const here = stack.at(-1);
      snack(msg, "Open", () => { if (!(here?.type === "artist" && here.name === a.name)) push({ type: "artist", name: a.name }); }, 10000);
    } catch (e) {
      diag.log("YouTube: " + (e.message || e));
      snack("Couldn't add that artist: " + (e.message || e), "Details", showDiagnostics, 12000);
    } finally {
      // Restore UI state
      if (isCurrentPage && ytButton && dlText && dlBar) {
          ytButton.disabled = false;
          dlText.classList.remove("active");
          dlBar.classList.remove("active");
          if (dlFill) dlFill.style.transform = `scaleX(0)`;
      }
    }
  } finally {
    linkBusy = false;
    lib.rebuild();
    refresh();
  }
}

// 3. Scroll Position Tracking (from previous request)
let artistScrollPositions = {}; 
// ... logic to save to artistScrollPositions on page scroll, and restore on show/pageArtist
