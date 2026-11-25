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
    const { items: issues } = await mockIssueApi(signedInAs("resident-a1"), runtime, clock, true).list({});
    expect(issues.length).toBeGreaterThan(0);
    expect(issues.every((issue) => issue.buildingId === BUILDINGS.a.buildingId)).toBe(true);
  });

  it("shows an administrator only the building they administer", async () => {
    const { items: issues } = await mockIssueApi(signedInAs("administrator-b"), runtime, clock, true).list({});
    expect(issues.length).toBeGreaterThan(0);
    expect(issues.every((issue) => issue.buildingId === BUILDINGS.b.buildingId)).toBe(true);
  });

  it("shows a contractor nothing (no membership)", async () => {
    await expect(mockIssueApi(signedInAs("contractor"), runtime, clock, true).list({})).resolves.toMatchObject({
      items: [],
      hasMore: false,
    });
  });

  it("answers 401 without a persona when auth is mocked", async () => {
    await expect(mockIssueApi(signedInAs(null), runtime, clock, true).list({})).rejects.toMatchObject({ status: 401 });
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
  const pending = "0199f2a1-0000-7000-8000-000000002002"; // Building A, received, Resident A2's, shared (version 2)
  const scheduled = "0199f2a1-0000-7000-8000-000000002003"; // Building A, scheduled, human decision
  const roof = "0199f2a1-0000-7000-8000-000000002004"; // Building B
  const critical = "0199f2a1-0000-7000-8000-000000002005"; // Building B, fallback suggestion, critical alert
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

  it("lists the administered buildings' issues, and residents their own and their building's shared ones", async () => {
    const { as } = world();
    const ids = async (persona: string) => (await as(persona).list({})).items.map((i) => i.issueId).sort();
    expect(await ids("administrator-a")).toEqual([suggested, pending, scheduled].sort());
    expect(await ids("administrator-b")).toEqual([roof, critical].sort());
    // The garage issue is shared with Building A, so Resident A1 sees it too; Building B's residents do not.
    expect(await ids("resident-a1")).toEqual([suggested, pending, scheduled].sort());
    expect(await ids("resident-a2")).toEqual([pending]);
  });

  it("pages the list, and never puts the triage reason in it", async () => {
    const { as } = world();
    const first = await as("administrator-a").list({ page: 0 });
    expect(first).toMatchObject({ page: 0, size: 20, hasMore: false });
    expect(first.items.find((i) => i.issueId === scheduled)?.triage).toMatchObject({
      category: "plumbing",
      reason: null,
    });
    // The administrator still reads the reason on the issue itself.
    expect((await as("administrator-a").getIssue(scheduled)).triage?.reason).toEqual(expect.any(String));
    expect((await as("administrator-a").list({ page: 1 })).items).toEqual([]);
    expect((await as("administrator-a").list({ buildingId: BUILDINGS.b.buildingId })).items).toEqual([]);
  });

  it("keeps the suggestion's explanation out of the list, and drops the suggestion once decided (#108)", async () => {
    const { as } = world();
    const listed = (await as("administrator-a").list({})).items.find((i) => i.issueId === suggested);
    expect(listed?.suggestion).toMatchObject({ category: "electrical", rationale: null });
    expect((await as("administrator-a").getIssue(suggested)).suggestion?.rationale).toEqual(expect.any(String));

    await as("administrator-a").triage(suggested, 2, decision, "k");
    expect((await as("administrator-a").getIssue(suggested)).suggestion).toBeNull();
    const history = await as("administrator-a").getHistory(suggested);
    expect(history.items.map((e) => e.type)).toContain("triage_suggested");
  });

  it("never shows residents a suggestion, a reason or an internal entry", async () => {
    const { as } = world();
    const issue = await as("resident-a1").getIssue(suggested);
    expect(issue.suggestion).toBeNull();
    const decided = await as("resident-a1").getIssue(scheduled);
    expect(decided.triage).toMatchObject({ category: "plumbing", reason: null });
    const alerted = await as("resident-b1").getIssue(critical);
    expect(alerted).toMatchObject({ suggestion: null, criticalCheck: null });
    expect((await as("administrator-b").getIssue(critical)).criticalCheck).toMatchObject({
      status: "completed",
      signals: [{ code: "smoke_or_fire" }, { code: "water_near_electrics" }],
    });

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
      extensions: { currentVersion: 2, currentState: "received" },
    });
    await expect(as("administrator-a").getIssue(pending)).resolves.toMatchObject({ version: 2, triage: null });
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
    // The resident can see the issue, so the refusal is 403; another building's administrator cannot, so 404.
    await expect(as("resident-a1").triage(suggested, 2, decision, "k")).rejects.toMatchObject({
      status: 403,
      code: "FORBIDDEN",
    });
    await expect(as("administrator-b").triage(suggested, 2, decision, "k")).rejects.toMatchObject({ status: 404 });
    await expect(as("administrator-b").getIssue(suggested)).rejects.toMatchObject({ status: 404 });
  });

  it("validates the decision with the service's errors[]", async () => {
    const { as } = world();
    await expect(as("administrator-a").triage(pending, 2, { ...decision, reason: " " }, "k")).rejects.toMatchObject({
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

describe("mockIssueApi shared issue (US-04 rules, ASSUMPTION until #89)", () => {
  const shared = "0199f2a1-0000-7000-8000-000000002002"; // Building A, shared, Resident A2's report, A2 confirmed
  const unshared = "0199f2a1-0000-7000-8000-000000002001"; // Building A, not shared, Resident A1's report

  function world() {
    const identity = createMockIdentity();
    const api = mockIssueApi(identity, runtime, clock, true);
    const as = (personaId: string) => {
      identity.set(personaId);
      return api;
    };
    return { as };
  }

  it("counts one contribution per resident, however often they confirm or withdraw", async () => {
    const { as } = world();
    await expect(as("resident-a1").setImpact(shared, true)).resolves.toEqual({ count: 2, mine: true });
    await expect(as("resident-a1").setImpact(shared, true)).resolves.toEqual({ count: 2, mine: true });
    await expect(as("resident-a1").setImpact(shared, false)).resolves.toEqual({ count: 1, mine: false });
    await expect(as("resident-a1").setImpact(shared, false)).resolves.toEqual({ count: 1, mine: false });
    // Resident A2's own contribution is untouched by A1's withdrawal.
    await expect(as("resident-a2").getIssue(shared)).resolves.toMatchObject({ impact: { count: 1, mine: true } });
  });

  it("never changes the triage or the state when the count changes", async () => {
    const { as } = world();
    const before = await as("administrator-a").getIssue(shared);
    await as("resident-a1").setImpact(shared, true);
    const after = await as("administrator-a").getIssue(shared);
    expect(after).toMatchObject({ state: before.state, version: before.version, triage: before.triage });
  });

  it("refuses impact from administrators, other buildings and unshared issues", async () => {
    const { as } = world();
    await expect(as("administrator-a").setImpact(shared, true)).rejects.toMatchObject({ status: 403 });
    await expect(as("resident-b1").setImpact(shared, true)).rejects.toMatchObject({ status: 404 });
    await expect(as("resident-a1").setImpact(unshared, true)).rejects.toMatchObject({ status: 409 });
  });

  it("lets only the building's administrators publish, on the current version", async () => {
    const { as } = world();
    const publication = { summary: "The entrance light switch is unsafe." };
    await expect(as("resident-a1").publish(unshared, 2, publication, "k")).rejects.toMatchObject({ status: 403 });
    await expect(as("administrator-b").publish(unshared, 2, publication, "k")).rejects.toMatchObject({ status: 404 });
    await expect(as("administrator-a").publish(unshared, 1, publication, "k")).rejects.toMatchObject({
      status: 409,
      code: "VERSION_CONFLICT",
    });
    await expect(as("administrator-a").publish(unshared, 2, { summary: "short" }, "k")).rejects.toMatchObject({
      status: 400,
      extensions: { errors: [{ field: "summary", reason: "too_short" }] },
    });
    await expect(as("administrator-a").publish(unshared, 2, publication, "k")).resolves.toMatchObject({
      version: 3,
      summary: { text: publication.summary },
    });
    // Now shared: Resident A2, who did not report it, can read it, with the count and no suggestion.
    await expect(as("resident-a2").getIssue(unshared)).resolves.toMatchObject({
      summary: { text: publication.summary },
      impact: { count: 0, mine: false },
      suggestion: null,
    });
  });

  it("keeps the subscription separate from the impact", async () => {
    const { as } = world();
    await expect(as("resident-a1").setSubscription(shared, true)).resolves.toEqual({ subscribed: true });
    await expect(as("resident-a1").getIssue(shared)).resolves.toMatchObject({
      subscribed: true,
      impact: { count: 1, mine: false },
    });
    await expect(as("resident-a1").setSubscription(shared, false)).resolves.toEqual({ subscribed: false });
    await expect(as("resident-b1").setSubscription(shared, true)).rejects.toMatchObject({ status: 404 });
  });
});
