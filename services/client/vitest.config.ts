import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    tsconfigPaths: true,
    // The PWA plugin's virtual module exists only in Vite builds; tests get an inert stand-in.
    alias: { "virtual:pwa-register/react": fileURLToPath(new URL("./src/test/pwa-register-stub.ts", import.meta.url)) },
  },
  test: {
    environment: "happy-dom",
    include: ["src/**/*.test.{ts,tsx}", "test/**/*.test.ts"],
    setupFiles: ["./src/test/setup.ts"],
    restoreMocks: true,
  },
});
