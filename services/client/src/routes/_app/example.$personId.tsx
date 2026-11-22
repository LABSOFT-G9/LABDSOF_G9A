import { createFileRoute, notFound } from "@tanstack/react-router";
import { routeData } from "@/app/loaders";
import { PersonPage, personQuery } from "@/features/example";

/** REFERENCE ONLY. A path parameter, validated; an API 404 becomes the not-found page via `routeData`. */
export const Route = createFileRoute("/_app/example/$personId")({
  loader: async ({ context, params }) => {
    const id = Number(params.personId);
    if (!Number.isInteger(id) || id <= 0) throw notFound();
    await routeData(context.queryClient.ensureQueryData(personQuery(context.services.example, id)));
    return { id };
  },
  component: PersonRoute,
});

function PersonRoute() {
  const { id } = Route.useLoaderData();
  return <PersonPage personId={id} />;
}
