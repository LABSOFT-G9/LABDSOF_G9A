import type { AuthSession, DevPersonas } from "@/ports/auth-session";
import type { Clock } from "@/ports/clock";
import type { IssueApi } from "@/ports/issue-api";

/**
 * Everything the UI may depend on, as ports. The composition root (src/app/container.ts) decides which adapter
 * implements each one; features never import an adapter.
 */
export interface Services {
  readonly auth: AuthSession;
  readonly issues: IssueApi;
  readonly clock: Clock;
  /** Mock wiring only. */
  readonly devPersonas?: DevPersonas;
}
