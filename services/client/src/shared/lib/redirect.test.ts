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
    "/%09/evil.test".replace("%09", "\t"),
    "/a\\b",
    "issues",
    "",
    undefined,
    42,
  ])("falls back for %s", (target) => {
    expect(safeRedirect(target)).toBe("/dashboard");
  });
});
