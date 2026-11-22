import type { IssueSummary } from "@/contracts/issue";

/** The Issue service, as the client needs it. Grows one method per use case (ADR-009). */
export interface IssueApi {
  /**
   * Issues the caller may see, filtered by membership on the server. `signal` comes from TanStack Query, which
   * aborts the request when nobody needs the answer any more (the user left the page).
   */
  list(signal?: AbortSignal): Promise<IssueSummary[]>;
}
