import { useSuspenseQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowLeft, Circle, CircleCheck } from "lucide-react";
import { useServices } from "@/app/context";
import { todoQuery } from "@/features/example/api";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { Badge } from "@/shared/ui/badge";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";

/**
 * REFERENCE ONLY. The route loader awaited this query (an API 404 became the not-found page), so the suspense
 * query has its data on the first render.
 */
export function TodoDetailPage({ todoId }: { todoId: number }) {
  const { example } = useServices();
  const { data: todo } = useSuspenseQuery(todoQuery(example, todoId));

  return (
    <>
      <Button asChild variant="ghost" size="sm" className="mb-4 -ml-2">
        <Link to="/example">
          <ArrowLeft aria-hidden />
          {t.common.back}
        </Link>
      </Button>
      <PageHeader title={t.example.detailTitle(todo.id)} />
      <Card>
        <CardContent className="space-y-3">
          <p className="text-lg">{todo.title}</p>
          <div className="flex flex-wrap gap-2">
            <Badge variant={todo.completed ? "default" : "outline"}>
              {todo.completed ? <CircleCheck aria-hidden /> : <Circle aria-hidden />}
              {todo.completed ? t.example.done : t.example.open}
            </Badge>
            <Badge variant="secondary">{t.example.owner(todo.userId)}</Badge>
          </div>
        </CardContent>
      </Card>
    </>
  );
}
