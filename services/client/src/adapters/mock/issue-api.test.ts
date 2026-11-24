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
    const issues = await mockIssueApi(signedInAs("resident-a1"), runtime, clock, true).list();
    expect(issues.length).toBeGreaterThan(0);
    expect(issues.every((issue) => issue.buildingId === BUILDINGS.a.buildingId)).toBe(true);
  });

  it("shows an administrator only the building they administer", async () => {
    const issues = await mockIssueApi(signedInAs("administrator-b"), runtime, clock, true).list();
    expect(issues.length).toBeGreaterThan(0);
    expect(issues.every((issue) => issue.buildingId === BUILDINGS.b.buildingId)).toBe(true);
  });

  it("shows a contractor nothing (no membership)", async () => {
    await expect(mockIssueApi(signedInAs("contractor"), runtime, clock, true).list()).resolves.toEqual([]);
  });

  it("answers 401 without a persona when auth is mocked", async () => {
    await expect(mockIssueApi(signedInAs(null), runtime, clock, true).list()).rejects.toMatchObject({ status: 401 });
  });
});

describe("mockIssueApi reports (same rules as the Issue service)", () => {
  const areaA = "0199f2a0-0000-7000-8000-00000000a001";
  const areaB = "0199f2a0-0000-7000-8000-00000000a101";
  const report = { buildingId: BUILDINGS.a.buildingId, areaId: areaA, text: "The garage door does not close." };

  function world() {
    const identity = createMockIdentity();
    const api = mockIssueApi(identity, runtime, clock, true);
    const as = (personaId: string) => {
      identity.set(personaId);
      return api;
    };
    return { as };
  }

  it("gives a resident a receipt, and replays it for the same key and body", async () => {
    const { as } = world();
    const receipt = await as("resident-a1").submitReport(report, "k1");
    expect(receipt).toMatchObject({ state: "received", version: 1 });
    await expect(as("resident-a1").submitReport(report, "k1")).resolves.toEqual(receipt);
  });

  it("refuses the same key with another body (422)", async () => {
    const { as } = world();
    await as("resident-a1").submitReport(report, "k1");
    await expect(
      as("resident-a1").submitReport({ ...report, text: "Something else broke." }, "k1"),
    ).rejects.toMatchObject({ status: 422, code: "IDEMPOTENCY_KEY_REUSED" });
  });

  it("scopes keys per user", async () => {
    const { as } = world();
    const first = await as("resident-a1").submitReport(report, "same");
    const second = await as("resident-a2").submitReport(report, "same");
    expect(second.reportId).not.toBe(first.reportId);
  });

  it("validates fields and the area, with the service's errors[]", async () => {
    const { as } = world();
    await expect(as("resident-a1").submitReport({ ...report, text: "short" }, "k")).rejects.toMatchObject({
      status: 400,
      code: "VALIDATION_FAILED",
      extensions: { errors: [{ field: "text", reason: "too_short" }] },
    });
    await expect(as("resident-a1").submitReport({ ...report, areaId: areaB }, "k")).rejects.toMatchObject({
      status: 400,
      code: "INVALID_AREA",
      extensions: { errors: [{ field: "areaId", reason: "not_in_building" }] },
    });
  });

  it("answers 404 for a building the caller does not belong to", async () => {
    const { as } = world();
    await expect(as("resident-b1").submitReport(report, "k")).rejects.toMatchObject({ status: 404 });
    // Membership is checked before the text, as in the service: a non-member never learns the text rules apply.
    await expect(as("resident-b1").submitReport({ ...report, text: "short" }, "k")).rejects.toMatchObject({
      status: 404,
    });
    await expect(as("resident-b1").listAreas(BUILDINGS.a.buildingId)).rejects.toMatchObject({ status: 404 });
    await expect(as("resident-a1").listAreas(BUILDINGS.a.buildingId)).resolves.toHaveLength(3);
  });

  it("lets the author and the building's administrator read a report, and nobody else", async () => {
    const { as } = world();
    const { reportId } = await as("resident-a1").submitReport(report, "k");
    await expect(as("resident-a1").getReport(reportId)).resolves.toMatchObject({ authoredByMe: true });
    await expect(as("administrator-a").getReport(reportId)).resolves.toMatchObject({
      authoredByMe: false,
      source: "resident",
      text: report.text,
    });
    await expect(as("resident-a2").getReport(reportId)).rejects.toMatchObject({ status: 404 });
    await expect(as("administrator-b").getReport(reportId)).rejects.toMatchObject({ status: 404 });
  });

  it("lists own reports and the administered buildings' reports, without text", async () => {
    const { as } = world();
    await as("resident-a1").submitReport(report, "k1");
    await as("administrator-a").submitReport({ ...report, text: "Entrance light is broken." }, "k2");

    const own = await as("resident-a1").listReports({});
    expect(own.items).toHaveLength(1);
    expect(own.items[0]).not.toHaveProperty("text");

    const admin = await as("administrator-a").listReports({});
    expect(admin.items.map((r) => r.source).sort()).toEqual(["administrator_on_behalf", "resident"]);
    await expect(as("resident-a2").listReports({})).resolves.toMatchObject({ items: [], hasMore: false });
  });
});

describe("mockIssueApi triage and history (same rules as the Issue service)", () => {
  const suggested = "0199f2a1-0000-7000-8000-000000002001"; // Building A, in_triage, AI suggestion, Resident A1's
  const pending = "0199f2a1-0000-7000-8000-000000002002"; // Building A, received, Resident A2's
  const scheduled = "0199f2a1-0000-7000-8000-000000002003"; // Building A, scheduled, human decision
  const roof = "0199f2a1-0000-7000-8000-000000002004"; // Building B
  const decision = { category: "electrical", urgency: "high", reason: "Exposed wiring by the door." } as const;

  function world() {
    const identity = createMockIdentity();
    const api = mockIssueApi(identity, runtime, clock, true);
    const as = (personaId: string) => {
      identity.set(personaId);
      return api;
    };
    return { as };
  }

  it("lists the administered buildings' issues, and residents the issues of their own reports", async () => {
    const { as } = world();
    const ids = async (persona: string) => (await as(persona).list()).map((i) => i.issueId).sort();
    expect(await ids("administrator-a")).toEqual([suggested, pending, scheduled].sort());
    expect(await ids("administrator-b")).toEqual([roof]);
    expect(await ids("resident-a1")).toEqual([suggested, scheduled].sort());
    expect(await ids("resident-a2")).toEqual([pending]);
  });

  it("never shows residents a suggestion, a reason or an internal entry", async () => {
    const { as } = world();
    const issue = await as("resident-a1").getIssue(suggested);
    expect(issue.suggestion).toBeNull();
    const decided = await as("resident-a1").getIssue(scheduled);
    expect(decided.triage).toMatchObject({ category: "plumbing", reason: null });

    const history = await as("resident-a1").getHistory(suggested);
    expect(history.items.map((e) => e.type)).toEqual(["report_received"]);
    const adminHistory = await as("administrator-a").getHistory(suggested);
    expect(adminHistory.items.map((e) => e.visibility)).toEqual(["public", "internal"]);
  });

  it("records a decision on the current version and adds it to the history", async () => {
    const { as } = world();
    const result = await as("administrator-a").triage(suggested, 2, decision, "k1");
    expect(result).toEqual({
      issueId: suggested,
      state: "triaged",
      version: 3,
      category: "electrical",
      urgency: "high",
      decisionSource: "human",
    });
    const issue = await as("administrator-a").getIssue(suggested);
    expect(issue).toMatchObject({ state: "triaged", version: 3, triage: { ...decision, decisionSource: "human" } });
    const history = await as("administrator-a").getHistory(suggested);
    expect(history.items.at(-1)).toMatchObject({ type: "triage_decided", actorRole: "administrator", ...decision });
    // The same action again replays the answer instead of deciding twice.
    await expect(as("administrator-a").triage(suggested, 2, decision, "k1")).resolves.toEqual(result);
  });

  it("refuses a decision made on a stale version, changing nothing (409 VERSION_CONFLICT)", async () => {
    const { as } = world();
    await expect(as("administrator-a").triage(pending, 0, decision, "k")).rejects.toMatchObject({
      status: 409,
      code: "VERSION_CONFLICT",
      extensions: { currentVersion: 1, currentState: "received" },
    });
    await expect(as("administrator-a").getIssue(pending)).resolves.toMatchObject({ version: 1, triage: null });
  });

  it("refuses triage once the issue has moved on (409 INVALID_TRANSITION)", async () => {
    const { as } = world();
    await expect(as("administrator-a").triage(scheduled, 6, decision, "k")).rejects.toMatchObject({
      status: 409,
      code: "INVALID_TRANSITION",
      extensions: { currentState: "scheduled" },
    });
  });

  it("lets only the building's administrators decide", async () => {
    const { as } = world();
    await expect(as("resident-a1").triage(suggested, 2, decision, "k")).rejects.toMatchObject({ status: 404 });
    await expect(as("administrator-b").triage(suggested, 2, decision, "k")).rejects.toMatchObject({ status: 404 });
    await expect(as("administrator-b").getIssue(suggested)).rejects.toMatchObject({ status: 404 });
  });

  it("validates the decision with the service's errors[]", async () => {
    const { as } = world();
    await expect(as("administrator-a").triage(pending, 1, { ...decision, reason: " " }, "k")).rejects.toMatchObject({
      status: 400,
      extensions: { errors: [{ field: "reason", reason: "required" }] },
    });
  });

  it("opens an issue for a new report, which the author can follow", async () => {
    const { as } = world();
    const receipt = await as("resident-a2").submitReport(
      { buildingId: BUILDINGS.a.buildingId, areaId: "0199f2a0-0000-7000-8000-00000000a002", text: "Broken lock." },
      "k",
    );
    await expect(as("resident-a2").getIssue(receipt.issueId)).resolves.toMatchObject({ state: "received" });
    await as("administrator-a").triage(receipt.issueId, 1, decision, "k2");
    await expect(as("resident-a2").getReport(receipt.reportId)).resolves.toMatchObject({
      issue: { state: "triaged", version: 2 },
    });
  });
});
