import type { HttpClient } from "@/adapters/http/client";
import { IssueListSchema } from "@/contracts/issue";
import type { IssueApi } from "@/ports/issue-api";

/** The Issue service behind the edge prefix /api/issue (technical design §4.1). */
export function httpIssueApi(http: HttpClient): IssueApi {
  return {
    list: () => http.get("/api/issue/issues", IssueListSchema),
  };
}
