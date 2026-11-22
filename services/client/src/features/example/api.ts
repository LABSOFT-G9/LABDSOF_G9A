import { mutationOptions, type QueryClient, queryOptions } from "@tanstack/react-query";
import type { Todo } from "@/contracts/example";
import type { ExampleApi } from "@/ports/example-api";

/*
 * REFERENCE ONLY — delete with the rest of src/features/example.
 * The pattern every feature follows: a key factory, one `queryOptions` per resource (used by loaders and
 * components alike) and `mutationOptions` that keep the cache right after a write.
 */

export const todoKeys = {
  all: ["example", "todos"] as const,
  list: () => [...todoKeys.all, "list"] as const,
  detail: (id: number) => [...todoKeys.all, "detail", id] as const,
};

export const todoListQuery = (example: ExampleApi) =>
  queryOptions({ queryKey: todoKeys.list(), queryFn: () => example.listTodos() });

export const todoQuery = (example: ExampleApi, id: number) =>
  queryOptions({ queryKey: todoKeys.detail(id), queryFn: () => example.getTodo(id) });

export const createTodoMutation = (example: ExampleApi, queryClient: QueryClient) =>
  mutationOptions({
    mutationFn: example.createTodo,
    onSuccess: (todo: Todo) => {
      // JSONPlaceholder fakes writes (nothing is stored), so refetching would lose the new todo: put it in the cache
      // instead. Against a real API, `invalidateQueries({ queryKey: todoKeys.all })` is usually all you need.
      queryClient.setQueryData<Todo[]>(todoKeys.list(), (todos = []) => [todo, ...todos]);
      queryClient.setQueryData(todoKeys.detail(todo.id), todo);
    },
  });
