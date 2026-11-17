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

## Data format (important)
The native app keeps the WebView app's own records unchanged: `filesDir/strata/songs.json` = the IndexedDB `songs` store,
`strata/meta.json` = `kv/meta` (playlists by name, favorites, settings, lyrics, artistInfo, favArtists…), `strata/session.json` = `kv/session`.
Audio stays at `filesDir/Songs/<file>`, covers at `filesDir/covers/<coverId>.jpg` (same ids). Unknown fields are kept.
`Migrate.kt` copies IndexedDB over via a hidden WebView on `https://localhost` (runs once on first start; Settings › Bring Over to rerun).
It only finds data once this app IS `com.auraos.music`; in the Next test app the database is empty. The old database is never deleted.

## Done (parity round 1)
Library: Songs / Albums (grid) / Artists (grid, favourite artists) / Playlists + Favorites; sort (title, artist, album, added, longest, most played), genre filters,
search with recent searches, multi-select (long-press) with play / queue / playlist / favourite / share / remove; swipe between tabs.
Pages: album (discs, copyright, menu: play next, queue, playlist, favourite all, go to artist, look up info, find cover online, cover from phone, share pack, remove),
artist (photo, shuffle, radio, songs, albums, singles & EPs, featured on, about, more on YouTube Music), playlist (rename, reorder, remove, share, delete).
Song menu: play next, add to queue, favourite, playlist, radio, go to album/artist, edit info, look up info (iTunes + Deezer), lyrics, info, share file, remove.
Player: living background in cover colours reacting to the bass, visualizer, cover accent, double-tap like, swipe cover to skip, swipe down to close,
synced lyrics (tap to seek, choose other lyrics), sleep timer (incl. end of song), keep screen on (sun/moon), queue (reorder, remove, clear), play counts.
Audio: crossfade, beat matching, silence cut + beat finding (background, cached per song), 15 EQ presets + custom, compression to Opus (only while charging).
Adding: songs, folders (cover.jpg used), Music Packs (manifest + playlist), YouTube Music (songs, albums, playlists, artists, links, radio/mixes), share-in from YouTube.
Other: Music Pack export, find missing covers, autofill genre/year, battery saver pauses motion, remove all music, diagnostics log.
**Compiles and signs in CI. Not yet run on a phone.**

## Still different from the WebView app
- YouTube sign-in (TV code) for Add from Link: not built.
- Drag songs out to other apps: replaced by Share (song file / Music Pack).
- WebView-only polish (media-bar tuck, scroll timers, bubble morph animation) is replaced by native equivalents, not copied 1:1.
- Lyric timing offsets per song are kept in the data but not editable yet.
- Taking over the real package: set the `next` flavor's applicationId to `com.auraos.music` and versionCode above 10000, only after phone testing.

## Also parked
`wip/speed-fixes` holds the unfinished media-bar / play-start speed edits for the WebView app.
