import { describe, expect, it } from "vitest";
import { rememberReturnTo, takeReturnTo } from "@/shared/lib/return-to";

describe("return-to", () => {
  it("hands the remembered path back once", () => {
    rememberReturnTo("/issues?state=open");
    expect(takeReturnTo()).toBe("/issues?state=open");
    expect(takeReturnTo()).toBeNull();
  });

  it("is null when nothing was remembered", () => {
    expect(takeReturnTo()).toBeNull();
  });

  it("survives storage that throws", () => {
    const broken = {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      },
      removeItem: () => {
        throw new Error("blocked");
      },
    };
    expect(() => rememberReturnTo("/issues", broken)).not.toThrow();
    expect(takeReturnTo(broken)).toBeNull();
  });
});
