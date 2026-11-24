import { describe, expect, it } from "vitest";
import { safeRedirect } from "@/shared/lib/redirect";

describe("safeRedirect", () => {
  it("keeps same-origin paths", () => {
    expect(safeRedirect("/issues?state=open")).toBe("/issues?state=open");
  });

  it.each([
    "https://evil.test",
    "//evil.test",
    "/\\evil.test",
    "/\t/evil.test",
    "/\n/evil.test",
    "/\r/evil.test",
    // What the router hands over after decoding `?redirect=/%09/evil.test`.
    decodeURIComponent("/%09/evil.test"),
    "/a\\b",
    "/€",
    "issues",
    "",
    undefined,
    42,
  ])("falls back for %s", (target) => {
    expect(safeRedirect(target)).toBe("/dashboard");
  });
});
