import type { IssueApi } from "@/ports/issue-api";

const unused = (name: string) => () => Promise.reject(new Error(`${name} was not expected in this test`));

/** An IssueApi whose every method fails loudly unless the test replaces it. */
export function stubIssueApi(overrides: Partial<IssueApi> = {}): IssueApi {
  return {
    list: unused("list"),
    listAreas: unused("listAreas"),
    submitReport: unused("submitReport"),
    getReport: unused("getReport"),
    listReports: unused("listReports"),
    getIssue: unused("getIssue"),
    getHistory: unused("getHistory"),
    triage: unused("triage"),
    ...overrides,
  };
}
