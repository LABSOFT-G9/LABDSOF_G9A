import js from "@eslint/js";
import boundaries from "eslint-plugin-boundaries";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import globals from "globals";
import tseslint from "typescript-eslint";

/*
 * Architecture rules (ADR-009), enforced here so a wrong import fails `npm run lint`. If a rule fails, fix the
 * import; do not add an exception.
 *
 *   app (composition root) → everything
 *   routes   → app context/loaders, features (their index.ts only), shared, contracts
 *   features → ports, contracts, shared, app context, other features (their index.ts only)
 *   adapters → ports, contracts, shared
 *   ports    → contracts, shared
 *   contracts → nothing local (zod only)
 *   shared   → shared only
 */
const ELEMENTS = [
  { type: "app", pattern: "src/app" },
  { type: "routes", pattern: "src/routes" },
  { type: "features", pattern: "src/features/*", capture: ["feature"] },
  { type: "adapters", pattern: "src/adapters" },
  { type: "ports", pattern: "src/ports" },
  { type: "contracts", pattern: "src/contracts" },
  { type: "shared", pattern: "src/shared" },
  { type: "test-support", pattern: "src/test" },
];
/** Files at the root of src/ (main.tsx, vite-env.d.ts) and the generated route tree belong to no layer. */
const FILES = [
  { pattern: "src/*.{ts,tsx}", category: "entry" },
  { pattern: "src/**/*.test.{ts,tsx}", category: "test" },
];

const to = (...types) => ({ to: { element: { type: types } } });
/** The app files the rest may use: the context hooks and the loader helpers. Everything else in app/ is wiring. */
const appPublic = { to: { element: { type: "app", fileInternalPath: ["context.tsx", "loaders.ts"] } } };
/** A feature is reached from outside only through its index.ts (its public API). */
const featureIndex = { to: { element: { type: "features", fileInternalPath: "index.ts" } } };

export default tseslint.config(
  { ignores: ["dist", "dev-dist", "node_modules", "public"] },
  {
    files: ["**/*.{ts,tsx}"],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: { ecmaVersion: 2023, globals: globals.browser },
    plugins: { "react-hooks": reactHooks, "react-refresh": reactRefresh },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "@typescript-eslint/consistent-type-imports": ["error", { fixStyle: "inline-type-imports" }],
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
    },
  },
  {
    // Inside src/, every local import is `@/…`: no `./x`, no `../x`. One spelling per module, grep-able, movable.
    files: ["src/**/*.{ts,tsx}"],
    ignores: ["src/routeTree.gen.ts"],
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            { regex: "^\\.{1,2}(/|$)", message: "Use the @/ alias (e.g. @/shared/ui/button), never a relative path." },
          ],
        },
      ],
    },
  },
  {
    // The network only through the HTTP adapters; the environment only through src/app/config.ts. Otherwise a feature
    // could bypass its port, and mock/HTTP switching would silently stop covering it.
    files: ["src/**/*.{ts,tsx}"],
    ignores: ["src/adapters/http/**", "src/app/config.ts", "src/**/*.test.{ts,tsx}", "src/test/**"],
    rules: {
      "no-restricted-globals": [
        "error",
        ...["fetch", "XMLHttpRequest", "WebSocket", "EventSource"].map((name) => ({
          name,
          message: "Reach the network through a port and its HTTP adapter (src/adapters/http).",
        })),
      ],
      "no-restricted-properties": [
        "error",
        ...["window", "globalThis", "self"].map((object) => ({
          object,
          property: "fetch",
          message: "Reach the network through a port and its HTTP adapter (src/adapters/http).",
        })),
      ],
      "no-restricted-syntax": [
        "error",
        {
          selector: "MemberExpression[object.type='MetaProperty'][property.name='env']",
          message:
            "Read configuration through useAppConfig() / AppConfig; src/app/config.ts is the only reader of import.meta.env.",
        },
      ],
    },
  },
  {
    files: ["src/**/*.{ts,tsx}"],
    ignores: ["src/routeTree.gen.ts"],
    plugins: { boundaries },
    settings: {
      "boundaries/elements": ELEMENTS,
      "boundaries/files": FILES,
      "boundaries/legacy-templates": false,
      "boundaries/include": ["src/**/*"],
      "import/resolver": { typescript: { project: "./tsconfig.app.json" } },
    },
    rules: {
      // Every file in src/ belongs to a layer.
      "boundaries/no-unknown-files": "error",
      "boundaries/dependencies": [
        "error",
        {
          default: "disallow",
          message: "This import breaks the ADR-009 layering (see the map at the top of eslint.config.js).",
          policies: [
            {
              from: { element: { type: "app" } },
              allow: [
                to("app", "routes", "features", "adapters", "ports", "contracts", "shared"),
                { to: { file: { categories: "entry" } } },
              ],
            },
            {
              from: { element: { type: "routes" } },
              allow: [appPublic, featureIndex, to("ports", "contracts", "shared")],
            },
            {
              from: { element: { type: "features" } },
              allow: [appPublic, featureIndex, to("ports", "contracts", "shared")],
            },
            { from: { element: { type: "adapters" } }, allow: to("ports", "contracts", "shared") },
            { from: { element: { type: "ports" } }, allow: to("contracts", "shared") },
            { from: { element: { type: "shared" } }, allow: to("shared") },
            // main.tsx imports the install module for its side effect (it must run before React mounts).
            {
              from: { file: { categories: "entry" } },
              allow: [
                to("app", "shared"),
                { to: { file: { categories: "entry" } } },
                { to: { element: { type: "features", fileInternalPath: ["index.ts", "install.ts"] } } },
              ],
            },
            {
              from: { element: { type: "test-support" } },
              allow: to("app", "adapters", "ports", "contracts", "shared", "features"),
            },
            // Tests may reach whatever they set up (fixtures, fakes, the test renderer). Last, so it wins.
            { from: { file: { categories: "test" } }, allow: { to: { element: { type: "*" } } } },
          ],
        },
      ],
    },
  },
  {
    files: ["src/features/**/*.tsx"],
    rules: { "react-refresh/only-export-components": ["warn", { allowConstantExport: true }] },
  },
  {
    files: ["*.config.{ts,js}", "test/**/*.ts"],
    languageOptions: { globals: globals.node },
  },
);
