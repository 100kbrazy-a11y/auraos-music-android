// Bundles src/potoken-page.js into one plain script (src/gen/potoken.iife.js) that the app hands to its hidden
// YouTube page. Run before the main build (npm run build does both).
import { defineConfig } from "vite";
export default defineConfig({
  publicDir: false,
  build: {
    outDir: "src/gen", emptyOutDir: false, minify: true, copyPublicDir: false,
    lib: { entry: "src/potoken-page.js", formats: ["iife"], name: "AuraPo", fileName: () => "potoken.iife.js" },
  },
});
