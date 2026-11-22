import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ChevronRight, Circle, CircleCheck, ListTodo } from "lucide-react";
import { useServices } from "@/app/context";
import { todoListQuery } from "@/features/example/api";
import { CreateTodoForm } from "@/features/example/create-todo-form";
import { ErrorState } from "@/shared/components/error-state";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/ui/card";
import { Empty, EmptyHeader, EmptyMedia, EmptyTitle } from "@/shared/ui/empty";
import { Skeleton } from "@/shared/ui/skeleton";

/** REFERENCE ONLY. The loader already filled the cache, so `useQuery` renders at once and keeps it fresh. */
export function TodosPage() {
  const { example } = useServices();
  const query = useQuery(todoListQuery(example));

  return (
    <>
      <PageHeader title={t.example.title} subtitle={t.example.subtitle} />
      <div className="grid gap-6 lg:grid-cols-[1fr_20rem]">
        <section aria-labelledby="todos-title" className="space-y-3">
          <h2 id="todos-title" className="text-lg font-semibold">
            {t.example.listTitle}
          </h2>
          {query.isPending ? (
            <div className="space-y-2" aria-busy="true" aria-label={t.common.loading}>
              {[0, 1, 2, 3].map((i) => (
                <Skeleton key={i} className="h-12 w-full" />
              ))}
            </div>
          ) : query.isError ? (
            <ErrorState error={query.error} onRetry={() => void query.refetch()} />
          ) : query.data.length === 0 ? (
            <Empty className="border">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <ListTodo aria-hidden />
                </EmptyMedia>
                <EmptyTitle>{t.example.empty}</EmptyTitle>
              </EmptyHeader>
            </Empty>
          ) : (
            <ul className="divide-y rounded-lg border">
              {query.data.map((todo) => (
                <li key={todo.id}>
                  <Link
                    to="/example/$todoId"
                    params={{ todoId: String(todo.id) }}
                    className="hover:bg-accent focus-visible:ring-ring/50 flex min-h-12 items-center gap-3 px-4 py-3 text-sm outline-none focus-visible:ring-[3px] focus-visible:ring-inset"
                  >
                    {todo.completed ? (
                      <CircleCheck className="size-4 shrink-0" aria-label={t.example.done} />
                    ) : (
                      <Circle className="text-muted-foreground size-4 shrink-0" aria-label={t.example.open} />
                    )}
                    <span className="min-w-0 flex-1 truncate">{todo.title}</span>
                    <ChevronRight className="text-muted-foreground size-4" aria-hidden />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
        <Card className="h-fit">
          <CardHeader>
            <CardTitle className="text-base">
              <h2>{t.example.createTitle}</h2>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <CreateTodoForm />
          </CardContent>
        </Card>
      </div>
    </>
  );
}
