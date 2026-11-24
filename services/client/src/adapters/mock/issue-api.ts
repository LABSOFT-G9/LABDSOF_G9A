import type { MockIdentity } from "@/adapters/mock/auth-session";
import { AREAS, seedIssues } from "@/adapters/mock/data";
import type { MockRuntime } from "@/adapters/mock/runtime";
import { type Report, type ReportReceipt, type ReportSummary, SubmitReportSchema } from "@/contracts/issue";
import type { Session } from "@/ports/auth-session";
import type { Clock } from "@/ports/clock";
import type { IssueApi } from "@/ports/issue-api";
import { ApiError } from "@/shared/lib/errors";
import { randomId } from "@/shared/lib/id";

const PAGE_SIZE = 20;

interface StoredReport extends Report {
  readonly authorId: string;
}

const notFound = () => new ApiError(404, "NOT_FOUND", "Not found.", "mock-correlation-id");

/**
 * In-memory Issue service. Applies the same rules as the real one (services/issue README), so each persona sees and
 * may do what it would against the service: membership filtering, `404` for anything outside the caller's scope,
 * text validation, the area check and the 24-hour idempotency replay.
 */
export function mockIssueApi(
  identity: MockIdentity,
  runtime: MockRuntime,
  clock: Clock,
  /** Auth is mocked too: no persona means no session, as a real service would answer 401. */
  authIsMocked: boolean,
): IssueApi {
  const issues = seedIssues(clock.now());
  const reports: StoredReport[] = [];
  const receipts = new Map<string, { body: string; receipt: ReportReceipt }>();

  /** The signed-in persona; `null` when sign-in is real and this port is mocked (no persona to apply rules to). */
  function caller(): Session | null {
    const me = identity.current();
    if (!me && authIsMocked) throw new ApiError(401, "UNAUTHENTICATED", "Sign in to continue.");
    return me;
  }

  const roleIn = (me: Session | null, buildingId: string) =>
    me ? me.memberships.find((m) => m.buildingId === buildingId)?.role : "administrator";

  const mayRead = (me: Session | null, report: StoredReport) =>
    !me || report.authorId === me.sub || roleIn(me, report.buildingId) === "administrator";

  const summary = (me: Session | null, { text: _text, authorId, ...report }: StoredReport): ReportSummary => ({
    ...report,
    authoredByMe: !!me && authorId === me.sub,
  });

  return {
    list: () =>
      runtime.call(() => {
        const me = caller();
        // Auth is wired to HTTP while this port stays mocked (a per-port override): no persona to filter by.
        if (!me) return issues;
        const visible = new Set(me.memberships.map((m) => m.buildingId));
        return issues.filter((issue) => visible.has(issue.buildingId));
      }),

    listAreas: (buildingId) =>
      runtime.call(() => {
        const me = caller();
        const areas = AREAS[buildingId];
        if (!areas || !roleIn(me, buildingId)) throw notFound();
        return [...areas];
      }),

    submitReport: (input, idempotencyKey) =>
      runtime.call(() => {
        const me = caller();
        // In the service's order: identifiers first, then membership (404, whatever the text), then the text.
        const parsed = SubmitReportSchema.safeParse(input);
        const invalid = (fields: readonly string[]) => {
          const errors = (parsed.error?.issues ?? [])
            .filter((issue) => fields.includes(String(issue.path[0])))
            .map((issue) => ({ field: String(issue.path[0]), reason: issue.message }));
          if (errors.length > 0) {
            throw new ApiError(400, "VALIDATION_FAILED", "The report is not valid.", "mock-correlation-id", { errors });
          }
        };
        invalid(["buildingId", "areaId"]);
        const role = roleIn(me, input.buildingId);
        if (!role) throw notFound();
        invalid(["text"]);
        const report = parsed.data!;
        const area = AREAS[report.buildingId]?.find((a) => a.areaId === report.areaId);
        if (!area) {
          throw new ApiError(400, "INVALID_AREA", "The area does not exist in the selected building.", undefined, {
            errors: [{ field: "areaId", reason: "not_in_building" }],
          });
        }

        // Scoped per user, like the service: the same key and body replay the receipt; another body is refused.
        const scopedKey = `${me?.sub ?? "anonymous"}:${idempotencyKey}`;
        const body = JSON.stringify(report);
        const previous = receipts.get(scopedKey);
        if (previous) {
          if (previous.body !== body) {
            throw new ApiError(422, "IDEMPOTENCY_KEY_REUSED", "This key was used for another report.");
          }
          return previous.receipt;
        }

        const stored: StoredReport = {
          reportId: randomId(),
          issueId: randomId(),
          buildingId: report.buildingId,
          areaId: area.areaId,
          areaLabel: area.label,
          text: report.text,
          source: role === "administrator" ? "administrator_on_behalf" : "resident",
          authoredByMe: true,
          submittedAt: clock.now().toISOString(),
          issue: { state: "received", version: 1 },
          authorId: me?.sub ?? "anonymous",
        };
        reports.push(stored);
        const receipt: ReportReceipt = {
          reportId: stored.reportId,
          issueId: stored.issueId,
          state: "received",
          version: 1,
          submittedAt: stored.submittedAt,
        };
        receipts.set(scopedKey, { body, receipt });
        return receipt;
      }),

    getReport: (reportId) =>
      runtime.call(() => {
        const me = caller();
        const report = reports.find((r) => r.reportId === reportId);
        if (!report || !mayRead(me, report)) throw notFound();
        const { authorId, ...visible } = report;
        return { ...visible, authoredByMe: !!me && authorId === me.sub };
      }),

    listReports: ({ buildingId, page = 0 }) =>
      runtime.call(() => {
        const me = caller();
        const visible = reports
          .filter((r) => mayRead(me, r) && (!buildingId || r.buildingId === buildingId))
          .sort((a, b) => b.submittedAt.localeCompare(a.submittedAt));
        const items = visible.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map((r) => summary(me, r));
        return { items, page, size: PAGE_SIZE, hasMore: visible.length > (page + 1) * PAGE_SIZE };
      }),
  };
}
