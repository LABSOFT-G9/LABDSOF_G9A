import type { MockIdentity } from "@/adapters/mock/auth-session";
import { seedIssues } from "@/adapters/mock/data";
import type { MockRuntime } from "@/adapters/mock/runtime";
import type { Clock } from "@/ports/clock";
import type { IssueApi } from "@/ports/issue-api";
import { ApiError } from "@/shared/lib/errors";

/** In-memory Issue service. Filters by membership like the real one, so each persona sees what it would see. */
export function mockIssueApi(
  identity: MockIdentity,
  runtime: MockRuntime,
  clock: Clock,
  /** Auth is mocked too: no persona means no session, as a real service would answer 401. */
  authIsMocked: boolean,
): IssueApi {
  const issues = seedIssues(clock.now());
  return {
    list: () =>
      runtime.call(() => {
        const me = identity.current();
        if (!me) {
          if (authIsMocked) throw new ApiError(401, "UNAUTHENTICATED", "Sign in to continue.");
          // Auth is wired to HTTP while this port stays mocked (a per-port override): no persona to filter by.
          return issues;
        }
        const visible = new Set(me.memberships.map((m) => m.buildingId));
        return issues.filter((issue) => visible.has(issue.buildingId));
      }),
  };
}
