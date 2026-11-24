import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

/** A plain in-memory Storage, like a browser tab's. */
function memoryStorage(): Storage {
  const items = new Map<string, string>();
  return {
    get length() {
      return items.size;
    },
    clear: () => items.clear(),
    getItem: (key) => items.get(key) ?? null,
    key: (index) => [...items.keys()][index] ?? null,
    removeItem: (key) => void items.delete(key),
    setItem: (key, value) => void items.set(key, String(value)),
  };
}

/*
 * Node 25+ defines its own global `localStorage` and `sessionStorage`. Without `--localstorage-file` they do not work
 * (Node 26 warns and stores nothing; earlier versions with the flag throw), and they hide happy-dom's, so the mock
 * persona would never be remembered. Give the test DOM working storage of its own, whatever the Node version.
 */
if (typeof document !== "undefined") {
  for (const name of ["localStorage", "sessionStorage"] as const) {
    Object.defineProperty(globalThis, name, { value: memoryStorage(), configurable: true, writable: true });
  }
}

afterEach(() => {
  cleanup();
  // Only the DOM tests have storage; in the node environment (test/static.test.ts) Node's own may throw when touched.
  if (typeof document !== "undefined") {
    localStorage.clear();
    sessionStorage.clear();
  }
});
