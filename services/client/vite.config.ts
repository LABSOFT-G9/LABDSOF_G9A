import tailwindcss from "@tailwindcss/vite";
import { tanstackRouter } from "@tanstack/router-plugin/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv, type ProxyOptions } from "vite";
import { VitePWA } from "vite-plugin-pwa";

/** Paths answered by the edge (Traefik + its OIDC plugin, ADR-004/005), never by the SPA or its service worker. */
const EDGE_PATHS = ["/api", "/oidc", "/logout", "/auth"];

export default defineConfig(({ mode }) => {
  // `API_PROXY_TARGET` has no VITE_ prefix on purpose: it configures the dev server and never reaches the bundle.
  const env = loadEnv(mode, import.meta.dirname, "");
  const target = env.API_PROXY_TARGET || "http://localhost:80";

  // Same origin for the browser: the edge's paths are proxied with the path kept, so its session cookie is
  // first-party and there is no CORS (ADR-005).
  const edge: ProxyOptions = { target, changeOrigin: false };
  const proxy: Record<string, ProxyOptions> = {
    // Reference feature only (src/features/example): a public JSON API behind our own /api prefix, so the HTTP
    // adapter is written exactly like a real one. Must come before "/api" (the first matching prefix wins).
    "/api/example": {
      target: "https://jsonplaceholder.typicode.com",
      changeOrigin: true,
      rewrite: (path) => path.replace(/^\/api\/example/, ""),
    },
    ...Object.fromEntries(EDGE_PATHS.map((path) => [path, edge])),
  };

  return {
    resolve: { tsconfigPaths: true },
    plugins: [
      // File routes: writes src/routeTree.gen.ts (committed). Each route component is its own chunk.
      tanstackRouter({
        target: "react",
        routesDirectory: "./src/routes",
        generatedRouteTree: "./src/routeTree.gen.ts",
        autoCodeSplitting: true,
      }),
      react(),
      tailwindcss(),
      // Installable PWA, online only (ADR-011): the service worker precaches the app shell and static assets and
      // never touches API responses. Updates wait for the user ("prompt"), so a half-filled form is never lost.
      VitePWA({
        registerType: "prompt",
        injectRegister: false,
        includeAssets: ["favicon.ico", "favicon.svg", "apple-touch-icon-180x180.png", "theme-init.js"],
        manifest: {
          name: "BMC — Building Maintenance Coordinator",
          short_name: "BMC",
          description: "Report, triage and coordinate condominium faults.",
          start_url: "/",
          scope: "/",
          display: "standalone",
          background_color: "#ffffff",
          theme_color: "#0a0a0a",
          icons: [
            { src: "pwa-64x64.png", sizes: "64x64", type: "image/png" },
            { src: "pwa-192x192.png", sizes: "192x192", type: "image/png" },
            { src: "pwa-512x512.png", sizes: "512x512", type: "image/png" },
            { src: "maskable-icon-512x512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
          ],
        },
        workbox: {
          globPatterns: ["**/*.{js,css,html,svg,png,ico,woff2}"],
          navigateFallback: "/index.html",
          // Navigations to the edge (sign-in, sign-out) must reach the network, not the cached shell.
          navigateFallbackDenylist: EDGE_PATHS.map((path) => new RegExp(`^${path}(/|$)`)),
          // No runtimeCaching: API responses are never cached (private data, no stale states).
          runtimeCaching: [],
          cleanupOutdatedCaches: true,
        },
      }),
    ],
    server: { port: 5173, proxy },
    preview: { port: 4173, proxy },
  };
});
