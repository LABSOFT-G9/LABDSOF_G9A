import { createFileRoute } from "@tanstack/react-router";
import { routeData } from "@/app/loaders";
import { historyQuery, IssuePage, issueQuery } from "@/features/issues";

/**
 * Awaits the issue (an API 404, for an unknown issue or one outside the caller's scope, becomes the not-found page)
 * and prefetches its history, which the page shows in place.
 */
export const Route = createFileRoute("/_app/issues/$issueId")({
  loader: async ({ context, params }) => {
    void context.queryClient.prefetchQuery(historyQuery(context.services.issues, params.issueId));
    await routeData(context.queryClient.ensureQueryData(issueQuery(context.services.issues, params.issueId)));
  },
  component: IssueRoute,
});

function IssueRoute() {
  const { issueId } = Route.useParams();
  return <IssuePage issueId={issueId} />;
}
