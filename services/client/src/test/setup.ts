import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => {
  cleanup();
  // Absent in the node environment (test/static.test.ts).
  globalThis.localStorage?.clear();
});
