import { defineConfig, minimal2023Preset } from "@vite-pwa/assets-generator/config";

// `npm run icons` regenerates the PNG icons and favicon in public/ from the placeholder mark. Commit the result.
export default defineConfig({
  headLinkOptions: { preset: "2023" },
  preset: {
    ...minimal2023Preset,
    maskable: { ...minimal2023Preset.maskable, resizeOptions: { background: "#0a0a0a" } },
    apple: { ...minimal2023Preset.apple, resizeOptions: { background: "#0a0a0a" } },
  },
  images: ["public/favicon.svg"],
});
