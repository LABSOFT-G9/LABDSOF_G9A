import { createFileRoute } from "@tanstack/react-router";
import { routeData } from "@/app/loaders";
import { todoListQuery, TodosPage } from "@/features/example";

/** REFERENCE ONLY. Awaits the list, so the page paints complete (a failure shows the route's error component). */
export const Route = createFileRoute("/_app/example/")({
  loader: ({ context }) => routeData(context.queryClient.ensureQueryData(todoListQuery(context.services.example))),
  component: TodosPage,
});
