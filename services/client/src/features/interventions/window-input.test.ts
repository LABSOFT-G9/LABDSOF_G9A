import { describe, expect, it } from "vitest";
import { fromLocalInput, toLocalInput } from "@/features/interventions/window-input";

describe("datetime-local values", () => {
  it("round-trips an instant through the browser's time zone, to the minute", () => {
    const iso = "2026-12-03T09:30:00.000Z";
    expect(fromLocalInput(toLocalInput(iso))).toBe(iso);
  });

  it("gives no instant for an empty or invalid value", () => {
    expect(fromLocalInput("")).toBeNull();
    expect(fromLocalInput("not a date")).toBeNull();
    expect(toLocalInput("not a date")).toBe("");
  });
});
