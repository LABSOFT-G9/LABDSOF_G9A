import { createFileRoute } from "@tanstack/react-router";
import { routeData } from "@/app/loaders";
import { ReportPage, reportQuery } from "@/features/reports";

/** Awaited: the page paints complete. An API 404 (unknown, or not the caller's) becomes the not-found page. */
export const Route = createFileRoute("/_app/reports/$reportId")({
  loader: async ({ context, params }) => {
    await routeData(context.queryClient.ensureQueryData(reportQuery(context.services.issues, params.reportId)));
  },
  component: ReportRoute,
});

function ReportRoute() {
  const { reportId } = Route.useParams();
  return <ReportPage reportId={reportId} />;
}
