import type { Area, IssueSummary, Report, ReportPage, ReportReceipt, SubmitReport } from "@/contracts/issue";

export interface ReportListParams {
  /** Only this building's reports. Omit for all the caller may see. */
  readonly buildingId?: string;
  /** Zero-based. */
  readonly page?: number;
}

/** The Issue service, as the client needs it. Grows one method per use case (ADR-009). */
export interface IssueApi {
  /**
   * Issues the caller may see, filtered by membership on the server. `signal` comes from TanStack Query, which
   * aborts the request when nobody needs the answer any more (the user left the page).
   */
  list(signal?: AbortSignal): Promise<IssueSummary[]>;

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
}
