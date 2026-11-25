import { createFileRoute } from "@tanstack/react-router";
import { reportListQuery, ReportsPage } from "@/features/reports";

/** Prefetch, do not await: the page shows its own loading, error and empty states. */
export const Route = createFileRoute("/_app/reports/")({
  loader: ({ context }) => {
    void context.queryClient.prefetchInfiniteQuery(reportListQuery(context.services.issues));
  },
  component: ReportsPage,
});
