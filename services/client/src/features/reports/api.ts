import { infiniteQueryOptions, mutationOptions, type QueryClient, queryOptions } from "@tanstack/react-query";
import type { SubmitReport } from "@/contracts/issue";
import { issueKeys } from "@/features/issues";
import type { IssueApi } from "@/ports/issue-api";

/** Key factory: every report query key starts with "reports", so one invalidation reaches them all. */
export const reportKeys = {
  all: ["reports"] as const,
  lists: () => [...reportKeys.all, "list"] as const,
  list: (buildingId: string | undefined) => [...reportKeys.lists(), { buildingId: buildingId ?? null }] as const,
  detail: (reportId: string) => [...reportKeys.all, "detail", reportId] as const,
  areas: (buildingId: string) => [...reportKeys.all, "areas", buildingId] as const,
};

/** Areas change rarely: keep them for five minutes. */
export const areasQuery = (issues: IssueApi, buildingId: string) =>
  queryOptions({
    queryKey: reportKeys.areas(buildingId),
    queryFn: ({ signal }) => issues.listAreas(buildingId, signal),
    staleTime: 5 * 60_000,
  });

export const reportQuery = (issues: IssueApi, reportId: string) =>
  queryOptions({
    queryKey: reportKeys.detail(reportId),
    queryFn: ({ signal }) => issues.getReport(reportId, signal),
  });

export const reportListQuery = (issues: IssueApi, buildingId?: string) =>
  infiniteQueryOptions({
    queryKey: reportKeys.list(buildingId),
    queryFn: ({ pageParam, signal }) =>
      issues.listReports({ page: pageParam, ...(buildingId ? { buildingId } : {}) }, signal),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined),
  });

export interface SubmitReportVariables {
  readonly report: SubmitReport;
  /** One per user action; the same key on a retry of that action. */
  readonly idempotencyKey: string;
}

/** A new report also opens an issue, so both lists are refetched. */
export const submitReportMutation = (issues: IssueApi, queryClient: QueryClient) =>
  mutationOptions({
    mutationFn: ({ report, idempotencyKey }: SubmitReportVariables) => issues.submitReport(report, idempotencyKey),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: reportKeys.lists() }),
        queryClient.invalidateQueries({ queryKey: issueKeys.all }),
      ]);
    },
  });
