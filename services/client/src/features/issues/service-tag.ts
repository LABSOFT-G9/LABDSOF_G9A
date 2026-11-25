import type { Issue } from "@/contracts/issue";

/**
 * Which fill the service tag's stub gets: the decided urgency, else the suggested one, else "pending". A critical
 * signal raised by the safety check also marks the tag critical. Decoration only: the badges say it in words.
 */
export function tagUrgency(issue: Pick<Issue, "triage" | "suggestion" | "criticalCheck">) {
  if (issue.criticalCheck && issue.criticalCheck.signals.length > 0) return "critical";
  return issue.triage?.urgency ?? issue.suggestion?.urgency ?? "pending";
}
