# Strata Music Next (native Kotlin + Compose): handoff

Branch `native-next`. Builds with `.github/workflows/native-next.yml` into a **separate test app**
(`com.auraos.music.next`, release tag `next`, pre-release). It never touches `main`, the real app, or its data.
Same signing key. Install it beside the real app from https://github.com/100kbrazy-a11y/auraos-music-android/releases/tag/next

## Layout
- `android/app/build.gradle`: flavors `legacy` (today's app) and `next` (this one). `android.yml` now builds `assembleLegacyRelease`.
- `android/app/src/next/`: everything native-only (manifest, Kotlin, generated `assets/engine/engine.js`).
  - `com/auraos/music/NativePlayer.kt`: drives the SAME `AuraPlaybackService` (crossfade, beat match, EQ, silence cut). Package `com.auraos.music` on purpose (package-private access).
  - `next/Store.kt`: library JSON (`next-library.json`) + songs in `filesDir/Songs/Artist/Album/NN Title.ext`; import songs, import Music Pack.
  - `next/YtEngine.kt`: the hidden browser. One WebView as `https://www.youtube.com`, created on demand, destroyed 8 s after the last job.
  - `next/Downloader.kt`: queue; the browser only finds the audio address, the file is downloaded natively.
  - `next/NextActivity.kt`: all screens.
- `engine/engine.js` + `vite.engine.config.js`: the browser-side script (YouTube.js + BotGuard token), bundled by `npx vite build -c vite.engine.config.js`.

## Done so far
Songs / Albums / Artists / Playlists + favourites, sort, search, song info, delete; mini + full player (seek, shuffle, repeat, favourite, queue view, sleep timer);
all icons drawn natively (`Icons.kt`, no icon library); Settings: crossfade, beat matching, keep screen on, 10-band EQ with presets, accent colour;
session restore, lock-screen artwork; add songs, Music Pack import; YouTube Music search + link paste + download queue (hidden browser).
**Untested on a device**: compiles and signs in CI; nothing has been run on a phone yet.

## Not done yet (parity checklist)
- Migration of existing data from the WebView app (IndexedDB export: songs, playlists, favourites, settings, history, covers, session).
- Folder import, cover.jpg inside packs, Music Pack export, Full Backup export/import.
- Visualizer, lyrics, queue editing (reorder / play next), tempo/pitch, silence-cut and compression settings, artist pages.
- Drag-out / share-in, YouTube sign-in, cover-colour theming, widgets.
- Take over the real package only when parity is signed off (real applicationId, versionCode above 10000). Rollback = ship the old app as a newer version.

## Also parked
`wip/speed-fixes` holds the unfinished media-bar / play-start speed edits for the WebView app.
