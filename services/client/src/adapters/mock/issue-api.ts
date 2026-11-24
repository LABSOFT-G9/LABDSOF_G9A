import type { MockIdentity } from "@/adapters/mock/auth-session";
import { AREAS, BUILDINGS, type MockIssueRecord, seedIssues } from "@/adapters/mock/data";
import type { MockRuntime } from "@/adapters/mock/runtime";
import {
  type HistoryEntry,
  type Issue,
  type IssueState,
  type Report,
  type ReportReceipt,
  type ReportSummary,
  SubmitReportSchema,
  type TriageResult,
  TriageRequestSchema,
} from "@/contracts/issue";
import type { Session } from "@/ports/auth-session";
import type { Clock } from "@/ports/clock";
import type { IssueApi } from "@/ports/issue-api";
import { ApiError } from "@/shared/lib/errors";
import { randomId } from "@/shared/lib/id";

const PAGE_SIZE = 20;
/** Manual triage is allowed from these states (technical design §3.1). */
const TRIAGEABLE: readonly IssueState[] = ["received", "in_triage", "triaged"];

interface StoredReport extends Omit<Report, "issue"> {
  readonly authorId: string;
}

type IssueRecord = { -readonly [K in keyof MockIssueRecord]: MockIssueRecord[K] };

const notFound = () => new ApiError(404, "NOT_FOUND", "Not found.", "mock-correlation-id");

const validationError = (issues: readonly { path: readonly PropertyKey[]; message: string }[]) =>
  new ApiError(400, "VALIDATION_FAILED", "The request is not valid.", "mock-correlation-id", {
    errors: issues.map((issue) => ({ field: String(issue.path[0]), reason: issue.message })),
  });

/**
 * In-memory Issue service. Applies the same rules as the real one (services/issue README, technical design §3.1), so
 * each persona sees and may do what it would against the service: membership filtering, `404` for anything outside the
 * caller's scope, role projections (residents never see internal history or reasons), text validation, the area check,
 * idempotent replay, and `If-Match` version checks with `409` on a stale or impossible change.
 */
export function mockIssueApi(
  identity: MockIdentity,
  runtime: MockRuntime,
  clock: Clock,
  /** Auth is mocked too: no persona means no session, as a real service would answer 401. */
  authIsMocked: boolean,
): IssueApi {
  const issues: IssueRecord[] = seedIssues(clock.now()).map((issue) => ({ ...issue }));
  const reports: StoredReport[] = [];
  const replays = new Map<string, { body: string; answer: unknown }>();

  /** The signed-in persona; `null` when sign-in is real and this port is mocked (no persona to apply rules to). */
  function caller(): Session | null {
    const me = identity.current();
    if (!me && authIsMocked) throw new ApiError(401, "UNAUTHENTICATED", "Sign in to continue.");
    return me;
  }

  /** `null` caller = no persona to apply rules to: treated as an administrator everywhere. */
  const roleIn = (me: Session | null, buildingId: string) =>
    me ? me.memberships.find((m) => m.buildingId === buildingId)?.role : "administrator";
  const administers = (me: Session | null, buildingId: string) => roleIn(me, buildingId) === "administrator";

  const mayReadReport = (me: Session | null, report: StoredReport) =>
    !me || report.authorId === me.sub || administers(me, report.buildingId);
  const mayReadIssue = (me: Session | null, issue: IssueRecord) =>
    administers(me, issue.buildingId) || (!!me && issue.reporterIds.includes(me.sub));

  /** What a role may see of an issue: residents get no suggestion, no critical alert and no reason. */
  function project(me: Session | null, { reporterIds: _r, history: _h, ...issue }: IssueRecord): Issue {
    if (administers(me, issue.buildingId)) return issue;
    return {
      ...issue,
      suggestion: null,
      criticalCheck: null,
      triage: issue.triage && { ...issue.triage, reason: null },
    };
  }

  const findIssue = (me: Session | null, issueId: string) => {
    const issue = issues.find((i) => i.issueId === issueId);
    if (!issue || !mayReadIssue(me, issue)) throw notFound();
    return issue;
  };

  const issueStatus = (issueId: string) => {
    const issue = issues.find((i) => i.issueId === issueId)!;
    return { state: issue.state, version: issue.version };
  };

  /** Same key and body replay the first answer; the same key with another body is refused (24-hour replay). */
  function idempotent<T>(me: Session | null, operation: string, key: string, body: unknown, run: () => T): T {
    const scopedKey = `${me?.sub ?? "anonymous"}:${operation}:${key}`;
    const serialized = JSON.stringify(body);
    const previous = replays.get(scopedKey);
    if (previous) {
      if (previous.body !== serialized) {
        throw new ApiError(422, "IDEMPOTENCY_KEY_REUSED", "This key was already used for a different request.");
      }
      return previous.answer as T;
    }
    const answer = run();
    replays.set(scopedKey, { body: serialized, answer });
    return answer;
  }

  return {
    list: ({ buildingId, page = 0 }) =>
      runtime.call(() => {
        const me = caller();
        const visible = issues
          .filter((issue) => mayReadIssue(me, issue) && (!buildingId || issue.buildingId === buildingId))
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        const items = visible.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map((record): Issue => {
          const issue = project(me, record);
          // Lists never carry the reason or the suggestion's explanation, not even to administrators: both are written
          // from private text and read on the issue itself (#108).
          return {
            ...issue,
            triage: issue.triage && { ...issue.triage, reason: null },
            suggestion: issue.suggestion && { ...issue.suggestion, rationale: null },
          };
        });
        return { items, page, size: PAGE_SIZE, hasMore: visible.length > (page + 1) * PAGE_SIZE };
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
          const issues = (parsed.error?.issues ?? []).filter((issue) => fields.includes(String(issue.path[0])));
          if (issues.length > 0) throw validationError(issues);
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

        return idempotent(me, "report", idempotencyKey, report, (): ReportReceipt => {
          const submittedAt = clock.now().toISOString();
          const authorId = me?.sub ?? "anonymous";
          const building = Object.values(BUILDINGS).find((b) => b.buildingId === report.buildingId)!;
          const issue: IssueRecord = {
            issueId: randomId(),
            ...building,
            areaLabel: area.label,
            state: "received",
            version: 1,
            createdAt: submittedAt,
            triage: null,
            suggestion: null,
            reporterIds: [authorId],
            history: [
              {
                sequence: 1,
                type: "report_received",
                visibility: "public",
                actorRole: role === "administrator" ? "administrator" : "resident",
                occurredAt: submittedAt,
              },
            ],
          };
          issues.push(issue);
          const stored: StoredReport = {
            reportId: randomId(),
            issueId: issue.issueId,
            buildingId: report.buildingId,
            areaId: area.areaId,
            areaLabel: area.label,
            text: report.text,
            source: role === "administrator" ? "administrator_on_behalf" : "resident",
            authoredByMe: true,
            submittedAt,
            authorId,
          };
          reports.push(stored);
          return { reportId: stored.reportId, issueId: issue.issueId, state: "received", version: 1, submittedAt };
        });
      }),

    getReport: (reportId) =>
      runtime.call(() => {
        const me = caller();
        const report = reports.find((r) => r.reportId === reportId);
        if (!report || !mayReadReport(me, report)) throw notFound();
        const { authorId, ...visible } = report;
        return { ...visible, authoredByMe: !!me && authorId === me.sub, issue: issueStatus(report.issueId) };
      }),

    listReports: ({ buildingId, page = 0 }) =>
      runtime.call(() => {
        const me = caller();
        const visible = reports
          .filter((r) => mayReadReport(me, r) && (!buildingId || r.buildingId === buildingId))
          .sort((a, b) => b.submittedAt.localeCompare(a.submittedAt));
        const items = visible
          .slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
          .map(({ text: _text, authorId, ...r }): ReportSummary => ({
            ...r,
            authoredByMe: !!me && authorId === me.sub,
            issue: issueStatus(r.issueId),
          }));
        return { items, page, size: PAGE_SIZE, hasMore: visible.length > (page + 1) * PAGE_SIZE };
      }),

    getIssue: (issueId) =>
      runtime.call(() => {
        const me = caller();
        return project(me, findIssue(me, issueId));
      }),

    getHistory: (issueId) =>
      runtime.call(() => {
        const me = caller();
        const issue = findIssue(me, issueId);
        const items: HistoryEntry[] = administers(me, issue.buildingId)
          ? [...issue.history]
          : issue.history.filter((e) => e.visibility === "public").map((e) => ({ ...e, reason: null }));
        return { items };
      }),

    triage: (issueId, version, decision, idempotencyKey) =>
      runtime.call(() => {
        const me = caller();
        const issue = findIssue(me, issueId);
        // Residents who may follow the issue still may not decide on it. They can see it, so the answer is 403, not 404.
        if (!administers(me, issue.buildingId)) {
          throw new ApiError(403, "FORBIDDEN", "Only the building's administrators may triage.", "mock-correlation-id");
        }
        const parsed = TriageRequestSchema.safeParse(decision);
        if (!parsed.success) throw validationError(parsed.error.issues);

        return idempotent(me, `triage:${issueId}`, idempotencyKey, { version, ...parsed.data }, (): TriageResult => {
          const conflict = { currentVersion: issue.version, currentState: issue.state };
          if (version !== issue.version) {
            throw new ApiError(
              409,
              "VERSION_CONFLICT",
              "The issue changed since you loaded it.",
              "mock-correlation-id",
              conflict,
            );
          }
          if (!TRIAGEABLE.includes(issue.state)) {
            throw new ApiError(
              409,
              "INVALID_TRANSITION",
              "This issue can no longer be triaged.",
              "mock-correlation-id",
              conflict,
            );
          }
          const { category, urgency, reason } = parsed.data;
          const decidedAt = clock.now().toISOString();
          issue.triage = { category, urgency, reason, decidedAt, decisionSource: "human" };
          // Once decided, the issue carries no suggestion; it stays in the history only (#108).
          issue.suggestion = null;
          issue.state = "triaged";
          issue.version += 1;
          issue.history = [
            ...issue.history,
            {
              sequence: issue.history.length + 1,
              type: "triage_decided",
              visibility: "public",
              actorRole: "administrator",
              occurredAt: decidedAt,
              category,
              urgency,
              reason,
            },
          ];
          return { issueId, state: issue.state, version: issue.version, category, urgency, decisionSource: "human" };
        });
      }),
  };
}
