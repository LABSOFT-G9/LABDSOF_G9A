import { createFileRoute } from "@tanstack/react-router";
import { issueListQuery, IssuesPage } from "@/features/issues";

/**
 * Prefetch, do not await: the page renders at once and shows loading, error (with retry) and empty states in place,
 * where the user meets them (ADR-011, degraded states in context).
 */
export const Route = createFileRoute("/_app/issues/")({
  loader: ({ context }) => {
    void context.queryClient.prefetchQuery(issueListQuery(context.services.issues));
  },
  component: IssuesPage,
});
