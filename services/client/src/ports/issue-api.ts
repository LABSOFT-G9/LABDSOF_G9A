import type { IssueSummary } from "@/contracts/issue";

/** The Issue service, as the client needs it. Grows one method per use case (ADR-009). */
export interface IssueApi {
  /** Issues the caller may see, filtered by membership on the server. */
  list(): Promise<IssueSummary[]>;
}
