import { createFileRoute } from "@tanstack/react-router";
import { z } from "zod";
import { routeData } from "@/app/loaders";
import { PeoplePage, peopleQuery } from "@/features/example";

const SearchSchema = z.object({ q: z.string().trim().max(100).optional().catch(undefined) });

/**
 * REFERENCE ONLY. Search params validated with zod and passed to the loader through `loaderDeps`; the loader awaits
 * the list, so the page paints complete (a failure shows the route's error component).
 */
export const Route = createFileRoute("/_app/example/")({
  validateSearch: SearchSchema,
  loaderDeps: ({ search }) => ({ q: search.q }),
  loader: ({ context, deps }) =>
    routeData(context.queryClient.ensureQueryData(peopleQuery(context.services.example, deps.q))),
  component: PeopleRoute,
});

function PeopleRoute() {
  const { q } = Route.useSearch();
  return <PeoplePage search={q} />;
}
