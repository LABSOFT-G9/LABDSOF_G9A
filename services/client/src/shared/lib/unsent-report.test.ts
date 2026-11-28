import { afterEach, describe, expect, it, vi } from "vitest";
import {
  forgetStaleUnsentReport,
  forgetUnsentReport,
  keepUnsentReport,
  peekUnsentReport,
  UNSENT_REPORT_KEPT_MS,
} from "@/shared/lib/unsent-report";

const report = { buildingId: "b1", areaId: "a1", text: "The garage door does not close." };
const KEY = "bmc-unsent-report";

describe("unsent report", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("gives the report back to its author without removing it, until it is forgotten", () => {
    keepUnsentReport("u1", report);
    expect(peekUnsentReport("u1")).toEqual(report);
    // A render React throws away and repeats reads it again.
    expect(peekUnsentReport("u1")).toEqual(report);
    forgetUnsentReport();
    expect(peekUnsentReport("u1")).toBeNull();
  });

  it("never gives it to another person", () => {
    keepUnsentReport("u1", report);
    expect(peekUnsentReport("u2")).toBeNull();
  });

  it("removes another person's report once someone is signed in, and keeps the author's", () => {
    keepUnsentReport("u1", report);
    forgetStaleUnsentReport("u1");
    expect(sessionStorage.getItem(KEY)).not.toBeNull();
    forgetStaleUnsentReport("u2");
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  it("keeps the author's report while nobody is signed in, until it expires", () => {
    const keptAt = Date.parse("2026-11-26T10:00:00Z");
    keepUnsentReport("u1", report, sessionStorage, keptAt);
    forgetStaleUnsentReport(null, sessionStorage, keptAt + UNSENT_REPORT_KEPT_MS);
    expect(sessionStorage.getItem(KEY)).not.toBeNull();
    forgetStaleUnsentReport(null, sessionStorage, keptAt + UNSENT_REPORT_KEPT_MS + 1);
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  it("keeps it for 15 minutes, no longer, and not when the clock moved back", () => {
    const keptAt = Date.parse("2026-11-26T10:00:00Z");
    keepUnsentReport("u1", report, sessionStorage, keptAt);
    expect(peekUnsentReport("u1", sessionStorage, keptAt + UNSENT_REPORT_KEPT_MS)).toEqual(report);
    expect(peekUnsentReport("u1", sessionStorage, keptAt + UNSENT_REPORT_KEPT_MS + 1)).toBeNull();
    expect(peekUnsentReport("u1", sessionStorage, keptAt - 1)).toBeNull();

    forgetStaleUnsentReport("u1", sessionStorage, keptAt - 1);
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  it("removes it by itself after 15 minutes while the page stays open", () => {
    vi.useFakeTimers();
    vi.setSystemTime(Date.parse("2026-11-26T10:00:00Z"));
    keepUnsentReport("u1", report);
    vi.advanceTimersByTime(UNSENT_REPORT_KEPT_MS - 1_000);
    expect(sessionStorage.getItem(KEY)).not.toBeNull();
    vi.advanceTimersByTime(2_001);
    expect(sessionStorage.getItem(KEY)).toBeNull();
  });

  it("ignores what is not a kept report", () => {
    sessionStorage.setItem(KEY, "{not json");
    expect(peekUnsentReport("u1")).toBeNull();
    sessionStorage.setItem(KEY, JSON.stringify({ sub: "u1", keptAt: Date.now(), text: 3 }));
    expect(peekUnsentReport("u1")).toBeNull();
  });

  it("does nothing when the browser blocks storage", () => {
    const blocked = {
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
    expect(() => keepUnsentReport("u1", report, blocked)).not.toThrow();
    expect(peekUnsentReport("u1", blocked)).toBeNull();
    expect(() => forgetStaleUnsentReport("u1", blocked)).not.toThrow();
    expect(() => forgetUnsentReport(blocked)).not.toThrow();
  });
});
