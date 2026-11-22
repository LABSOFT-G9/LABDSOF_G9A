import { createFileRoute, notFound } from "@tanstack/react-router";
import { routeData } from "@/app/loaders";
import { TodoDetailPage, todoQuery } from "@/features/example";

/** REFERENCE ONLY. A path parameter, validated; an API 404 becomes the not-found page via `routeData`. */
export const Route = createFileRoute("/_app/example/$todoId")({
  loader: async ({ context, params }) => {
    const id = Number(params.todoId);
    if (!Number.isInteger(id) || id <= 0) throw notFound();
    await routeData(context.queryClient.ensureQueryData(todoQuery(context.services.example, id)));
    return { id };
  },
  component: TodoDetailRoute,
});

function TodoDetailRoute() {
  const { id } = Route.useLoaderData();
  return <TodoDetailPage todoId={id} />;
}
