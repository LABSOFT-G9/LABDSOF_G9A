import { describe, expect, it } from "vitest";
import type { CriticalCheck, Issue } from "@/contracts/issue";
import { isProcessing, issueQuery, PROCESSING_POLL_MS } from "@/features/issues/api";
import { stubIssueApi } from "@/test/fakes";

const base: Issue = {
  issueId: "i1",
  buildingId: "b1",
  buildingName: "Synthetic Building A",
  areaLabel: "Entrance hall",
  state: "received",
  version: 1,
  createdAt: "2026-11-24T10:00:00Z",
  triage: null,
  suggestion: null,
};
const suggestion = { category: "lift", urgency: "low", origin: "ai" } as const;
const decision = {
  category: "lift",
  urgency: "low",
  reason: null,
  decidedAt: "2026-11-24T10:05:00Z",
  decisionSource: "human",
} as const;

describe("issue polling (#144 review)", () => {
  it.each<[string, Issue, boolean]>([
    ["received, no suggestion yet", base, true],
    ["a suggestion arrived", { ...base, state: "in_triage", suggestion }, false],
    ["decided", { ...base, state: "triaged", triage: decision }, false],
    ["critical check pending", { ...base, state: "in_triage", suggestion, criticalCheck: pending() }, true],
    ["critical check pending but decided", { ...base, triage: decision, criticalCheck: pending() }, false],
  ])("%s: polls = %s", (_, issue, polls) => {
    expect(isProcessing(issue)).toBe(polls);
  });

  it("asks again every 10 seconds only while processing", () => {
    const options = issueQuery(stubIssueApi(), "i1");
    const interval = options.refetchInterval as (query: { state: { data: Issue | undefined } }) => number | false;
    expect(interval({ state: { data: base } })).toBe(PROCESSING_POLL_MS);
    expect(PROCESSING_POLL_MS).toBe(10_000);
    expect(interval({ state: { data: { ...base, state: "in_triage", suggestion } } })).toBe(false);
    expect(interval({ state: { data: undefined } })).toBe(false);
  });
});

function pending(): CriticalCheck {
  return { status: "pending", rulesVersion: null, signals: [] };
}
