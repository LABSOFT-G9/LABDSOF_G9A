import type { HttpClient } from "@/adapters/http/client";
import {
  AreaListSchema,
  HistorySchema,
  IssueListSchema,
  IssueSchema,
  ReportPageSchema,
  ReportReceiptSchema,
  ReportSchema,
  TriageResultSchema,
} from "@/contracts/issue";
import type { IssueApi } from "@/ports/issue-api";

const PREFIX = "/api/issue";
const segment = encodeURIComponent;

/** The Issue service behind the edge prefix /api/issue (technical design §4.1). */
export function httpIssueApi(http: HttpClient): IssueApi {
  return {
    list: (signal) => http.get(`${PREFIX}/issues`, IssueListSchema, signal),
    listAreas: (buildingId, signal) =>
      http.get(`${PREFIX}/buildings/${segment(buildingId)}/areas`, AreaListSchema, signal),
    submitReport: (report, idempotencyKey) =>
      http.request("POST", `${PREFIX}/reports`, { schema: ReportReceiptSchema, body: report, idempotencyKey }),
    getReport: (reportId, signal) => http.get(`${PREFIX}/reports/${segment(reportId)}`, ReportSchema, signal),
    listReports: ({ buildingId, page = 0 }, signal) => {
      const query = new URLSearchParams({ page: String(page) });
      if (buildingId) query.set("buildingId", buildingId);
      return http.get(`${PREFIX}/reports?${query.toString()}`, ReportPageSchema, signal);
    },
    getIssue: (issueId, signal) => http.get(`${PREFIX}/issues/${segment(issueId)}`, IssueSchema, signal),
    getHistory: (issueId, signal) => http.get(`${PREFIX}/issues/${segment(issueId)}/history`, HistorySchema, signal),
    triage: (issueId, version, decision, idempotencyKey) =>
      http.request("POST", `${PREFIX}/issues/${segment(issueId)}/triage`, {
        schema: TriageResultSchema,
        body: decision,
        ifMatch: version,
        idempotencyKey,
      }),
  };
}
