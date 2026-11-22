import { describe, expect, it } from "vitest";
import { createMockIdentity } from "@/adapters/mock/auth-session";
import { BUILDINGS } from "@/adapters/mock/data";
import { mockIssueApi } from "@/adapters/mock/issue-api";
import { createMockRuntime } from "@/adapters/mock/runtime";

const runtime = createMockRuntime({ latencyMs: 0, allowFailures: false, readSearch: () => "" });
const clock = { now: () => new Date("2026-11-22T12:00:00Z") };

function signedInAs(personaId: string | null) {
  const identity = createMockIdentity();
  identity.set(personaId);
  return identity;
}

describe("mockIssueApi", () => {
  it("shows a resident only the issues of their building, like the real service", async () => {
    const issues = await mockIssueApi(signedInAs("resident"), runtime, clock, true).list();
    expect(issues.length).toBeGreaterThan(0);
    expect(issues.every((issue) => issue.buildingId === BUILDINGS.a.buildingId)).toBe(true);
  });

  it("shows an administrator of two buildings both", async () => {
    const issues = await mockIssueApi(signedInAs("administrator"), runtime, clock, true).list();
    expect(new Set(issues.map((issue) => issue.buildingId))).toEqual(
      new Set([BUILDINGS.a.buildingId, BUILDINGS.b.buildingId]),
    );
  });

  it("shows a contractor nothing (no membership)", async () => {
    await expect(mockIssueApi(signedInAs("contractor"), runtime, clock, true).list()).resolves.toEqual([]);
  });

  it("answers 401 without a persona when auth is mocked", async () => {
    await expect(mockIssueApi(signedInAs(null), runtime, clock, true).list()).rejects.toMatchObject({ status: 401 });
  });
});
