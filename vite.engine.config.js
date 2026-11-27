// Bundles engine/engine.js (YouTube.js + the proof-of-origin check) into ONE plain script that the native app loads into
// its hidden, on-demand WebView only while music is being searched/downloaded. Output is generated, not committed.
import { defineConfig } from "vite";
export default defineConfig({
  publicDir: false,
  build: {
    target: "es2020", outDir: "android/app/src/next/assets/engine", emptyOutDir: true, minify: true, copyPublicDir: false,
    lib: { entry: "engine/engine.js", formats: ["iife"], name: "AuraEng", fileName: () => "engine.js" },
  },
});
