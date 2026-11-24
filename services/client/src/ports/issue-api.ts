import type {
  Area,
  History,
  Issue,
  IssuePage,
  Report,
  ReportPage,
  ReportReceipt,
  SubmitReport,
  TriageRequest,
  TriageResult,
} from "@/contracts/issue";

/** One page of a list: `GET /reports` and `GET /issues` take the same parameters. */
export interface PageParams {
  /** Only this building's items. Omit for all the caller may see. */
  readonly buildingId?: string;
  /** Zero-based. */
  readonly page?: number;
}
export type ReportListParams = PageParams;
export type IssueListParams = PageParams;

/** The Issue service, as the client needs it. Grows one method per use case (ADR-009). */
export interface IssueApi {
  /**
   * Issues the caller may see, newest first, one page at a time: those of the buildings the caller administers, plus
   * those of the caller's own reports. The server filters. `signal` comes from TanStack Query, which aborts the request
   * when nobody needs the answer any more (the user left the page).
   */
  list(params: IssueListParams, signal?: AbortSignal): Promise<IssuePage>;

  /** Areas of a building the caller belongs to, for the report form (US-01). */
  listAreas(buildingId: string, signal?: AbortSignal): Promise<Area[]>;

  /**
   * Report a fault (US-01). `idempotencyKey` identifies one user action: send the same key when retrying it, so the
   * service replays the original receipt instead of creating a second report.
   */
  submitReport(report: SubmitReport, idempotencyKey: string): Promise<ReportReceipt>;

  /** One report, for its author or an administrator of the building; `404` for anyone else. */
  getReport(reportId: string, signal?: AbortSignal): Promise<Report>;

  /** Own reports plus every report of the buildings the caller administers, newest first. */
  listReports(params: ReportListParams, signal?: AbortSignal): Promise<ReportPage>;

  /** One issue, projected for the caller's role (US-02); `404` outside the caller's scope. */
  getIssue(issueId: string, signal?: AbortSignal): Promise<Issue>;

  /** The issue's history: every entry for its administrators, `public` entries only for residents (US-02). */
  getHistory(issueId: string, signal?: AbortSignal): Promise<History>;

  /**
   * Record the administrator's triage decision (US-02). `version` is the issue version the decision was made on, sent
   * as `If-Match`: if someone changed the issue since, the service answers `409 VERSION_CONFLICT` and nothing changes.
   */
  triage(issueId: string, version: number, decision: TriageRequest, idempotencyKey: string): Promise<TriageResult>;
}
