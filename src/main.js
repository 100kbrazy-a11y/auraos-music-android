
// 1. Maintain scroll position on Artist pages
let artistScrollPositions = {}; // Store scroll positions by artist name

// Inside your pageShell event listener, add this line right before closing it:
// page.addEventListener("scroll", () => { ...
//   if (stack.at(-1).type === "artist") {
//       artistScrollPositions[stack.at(-1).name] = page.scrollTop;
//   }
// }, { passive: true });

// Also, when pushing a new page, if it's an artist page, restore the scroll:
// (You'll inject this inside the `show` or `pageArtist` function)
// if (artistScrollPositions[name]) {
//    setTimeout(() => { p.scrollTop = artistScrollPositions[name]; }, 10);
// }

// When popping off an artist page (leaving it), clear it:
// (Inject inside `pop` function)
// if (stack.at(-1).type === "artist") { delete artistScrollPositions[stack.at(-1).name]; }


// 2. Modifying the addArtist function to use inline progress instead of progressBox popups
async function addArtist(yt, link) {
  if (linkBusy) return snack("Already adding from a link…");
  linkBusy = true;
  try {
    let a;
    try { 
        // Read artist info first without popup (or using a minimal loading state)
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

// 3. Injecting the UI elements into pageArtist()
// Locate where 'acts' is defined in pageArtist(name) and modify it like this:
/*
  const acts = h("div", { class: "ah-acts" },
    h("button", { class: "ahp solid", onclick: shuffle }, icon("shuffle"), "Shuffle"),
    h("button", { class: "ahp", onclick: radio }, icon("spark"), "Radio"),
    h("div", { class: "ah-dl-text" }, `Downloading ${name}...`), // NEW TEXT
    native && h("button", { class: "ahp round", onclick: () => artistFromYouTube(name), "aria-label": info.channel ? "Get more from YouTube Music" : "Link to YouTube Music" }, icon("globe")));
  
  const dlBar = h("div", { class: "ah-dl-bar" }, h("b")); // NEW BAR
  p.append(hero, acts, dlBar);
*/
