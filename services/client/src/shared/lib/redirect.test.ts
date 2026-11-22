import { describe, expect, it } from "vitest";
import { safeRedirect } from "@/shared/lib/redirect";

describe("safeRedirect", () => {
  it("keeps same-origin paths", () => {
    expect(safeRedirect("/issues?state=open")).toBe("/issues?state=open");
  });

  it.each(["https://evil.test", "//evil.test", "/\\evil.test", "issues", "", undefined, 42])(
    "falls back for %s",
    (target) => {
      expect(safeRedirect(target)).toBe("/dashboard");
    },
  );
});
