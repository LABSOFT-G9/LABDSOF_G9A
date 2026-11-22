import { queryOptions } from "@tanstack/react-query";
import type { IssueApi } from "@/ports/issue-api";

/** Key factory: every issue query key starts with "issues", so one invalidation reaches them all. */
export const issueKeys = {
  all: ["issues"] as const,
  list: () => [...issueKeys.all, "list"] as const,
};

/** One `queryOptions` per resource, shared by the route loader and the component. */
export const issueListQuery = (issues: IssueApi) =>
  queryOptions({
    queryKey: issueKeys.list(),
    queryFn: ({ signal }) => issues.list(signal),
  });
