import { infiniteQueryOptions, mutationOptions, type QueryClient, queryOptions } from "@tanstack/react-query";
import type { Issue, PublicationRequest, TriageRequest } from "@/contracts/issue";
import type { IssueApi } from "@/ports/issue-api";

/** Key factory: every issue query key starts with "issues", so one invalidation reaches them all. */
export const issueKeys = {
  all: ["issues"] as const,
  list: () => [...issueKeys.all, "list"] as const,
  detail: (issueId: string) => [...issueKeys.all, "detail", issueId] as const,
  history: (issueId: string) => [...issueKeys.all, "history", issueId] as const,
};

/** One `queryOptions` per resource, shared by the route loader and the component. */
export const issueListQuery = (issues: IssueApi) =>
  infiniteQueryOptions({
    queryKey: issueKeys.list(),
    queryFn: ({ pageParam, signal }) => issues.list({ page: pageParam }, signal),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined),
  });

/** How often an issue still waiting on background processing is asked again. */
export const PROCESSING_POLL_MS = 10_000;

/**
 * Still waiting on background work the administrator is watching for: a suggestion (US-03) or the critical check
 * (US-14). Asking again also re-renders, which moves "waiting" to "triage pending" after 2 minutes.
 */
export const isProcessing = (issue: Issue | undefined) =>
  !!issue &&
  !issue.triage &&
  ((issue.state === "received" && !issue.suggestion) || issue.criticalCheck?.status === "pending");

export const issueQuery = (issues: IssueApi, issueId: string) =>
  queryOptions({
    queryKey: issueKeys.detail(issueId),
    queryFn: ({ signal }) => issues.getIssue(issueId, signal),
    refetchInterval: (query) => (isProcessing(query.state.data) ? PROCESSING_POLL_MS : false),
  });

export const historyQuery = (issues: IssueApi, issueId: string) =>
  queryOptions({
    queryKey: issueKeys.history(issueId),
    queryFn: ({ signal }) => issues.getHistory(issueId, signal),
  });

export interface TriageVariables {
  readonly version: number;
  readonly decision: TriageRequest;
  readonly idempotencyKey: string;
}

/**
 * The decision changes the issue, its history and the lists (and the reports that show its state). Everything about
 * issues and reports is refetched; on a conflict nothing is, so the form keeps the user's choices until they reload.
 */
export const triageMutation = (issues: IssueApi, queryClient: QueryClient, issueId: string) =>
  mutationOptions({
    mutationFn: ({ version, decision, idempotencyKey }: TriageVariables) =>
      issues.triage(issueId, version, decision, idempotencyKey),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: issueKeys.all }),
        queryClient.invalidateQueries({ queryKey: ["reports"] }),
      ]);
    },
  });

export interface PublishVariables {
  readonly version: number;
  readonly publication: PublicationRequest;
  readonly idempotencyKey: string;
}

/** Publishing changes the issue (its version and history) and what residents see: refetch everything about issues. */
export const publishMutation = (issues: IssueApi, queryClient: QueryClient, issueId: string) =>
  mutationOptions({
    mutationFn: ({ version, publication, idempotencyKey }: PublishVariables) =>
      issues.publish(issueId, version, publication, idempotencyKey),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: issueKeys.all });
    },
  });

/** Patch the cached issue with the caller's own answer, then refetch the rest in the background. */
function patchIssue(queryClient: QueryClient, issueId: string, patch: Partial<Issue>) {
  queryClient.setQueryData<Issue>(issueKeys.detail(issueId), (issue) => issue && { ...issue, ...patch });
  void queryClient.invalidateQueries({ queryKey: issueKeys.all, refetchType: "inactive" });
}

/** The caller's impact contribution (US-04). Only the count and the caller's own choice come back. */
export const impactMutation = (issues: IssueApi, queryClient: QueryClient, issueId: string) =>
  mutationOptions({
    mutationFn: (affected: boolean) => issues.setImpact(issueId, affected),
    onSuccess: (impact) => patchIssue(queryClient, issueId, { impact }),
  });

/** The caller's notification subscription (US-04), independent of the impact. */
export const subscriptionMutation = (issues: IssueApi, queryClient: QueryClient, issueId: string) =>
  mutationOptions({
    mutationFn: (subscribed: boolean) => issues.setSubscription(issueId, subscribed),
    onSuccess: ({ subscribed }) => patchIssue(queryClient, issueId, { subscribed }),
  });
