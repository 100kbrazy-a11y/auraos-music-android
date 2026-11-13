// cssMinify off: the CSS minifier drops the standard `backdrop-filter` and keeps only `-webkit-backdrop-filter`,
// which Chrome/Android ignores (no glass blur). Unminified CSS is only ~30 KB.
export default { base: "./", build: { target: "es2020", cssMinify: false, outDir: "dist", emptyOutDir: true, chunkSizeWarningLimit: 2000 } };
