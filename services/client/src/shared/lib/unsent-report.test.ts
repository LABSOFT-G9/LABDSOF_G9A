import { describe, expect, it } from "vitest";
import {
  forgetUnsentReportOfOthers,
  keepUnsentReport,
  takeUnsentReport,
  UNSENT_REPORT_KEPT_MS,
} from "@/shared/lib/unsent-report";

const report = { buildingId: "b1", areaId: "a1", text: "The garage door does not close." };

describe("unsent report", () => {
  it("gives the report back to its author once", () => {
    keepUnsentReport("u1", report);
    expect(takeUnsentReport("u1")).toEqual(report);
    expect(takeUnsentReport("u1")).toBeNull();
  });

  it("never gives it to another person, and removes it", () => {
    keepUnsentReport("u1", report);
    expect(takeUnsentReport("u2")).toBeNull();
    expect(sessionStorage.getItem("bmc-unsent-report")).toBeNull();
  });

  it("removes another person's report as soon as someone is signed in, and keeps the author's", () => {
    keepUnsentReport("u1", report);
    forgetUnsentReportOfOthers("u1");
    expect(sessionStorage.getItem("bmc-unsent-report")).not.toBeNull();
    forgetUnsentReportOfOthers("u2");
    expect(sessionStorage.getItem("bmc-unsent-report")).toBeNull();
  });

  it("keeps it for 15 minutes, no longer", () => {
    const keptAt = Date.parse("2026-11-26T10:00:00Z");
    keepUnsentReport("u1", report, sessionStorage, keptAt);
    expect(takeUnsentReport("u1", sessionStorage, keptAt + UNSENT_REPORT_KEPT_MS)).toEqual(report);

    keepUnsentReport("u1", report, sessionStorage, keptAt);
    expect(takeUnsentReport("u1", sessionStorage, keptAt + UNSENT_REPORT_KEPT_MS + 1)).toBeNull();

    keepUnsentReport("u1", report, sessionStorage, keptAt);
    forgetUnsentReportOfOthers("u1", sessionStorage, keptAt + UNSENT_REPORT_KEPT_MS + 1);
    expect(sessionStorage.getItem("bmc-unsent-report")).toBeNull();
  });

  it("ignores what is not a kept report", () => {
    sessionStorage.setItem("bmc-unsent-report", "{not json");
    expect(takeUnsentReport("u1")).toBeNull();
    sessionStorage.setItem("bmc-unsent-report", JSON.stringify({ sub: "u1", keptAt: Date.now(), text: 3 }));
    expect(takeUnsentReport("u1")).toBeNull();
    expect(sessionStorage.getItem("bmc-unsent-report")).toBeNull();
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
    expect(takeUnsentReport("u1", blocked)).toBeNull();
    expect(() => forgetUnsentReportOfOthers("u1", blocked)).not.toThrow();
  });
});
