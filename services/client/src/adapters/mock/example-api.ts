import type { MockRuntime } from "@/adapters/mock/runtime";
import type { Todo } from "@/contracts/example";
import type { ExampleApi } from "@/ports/example-api";
import { ApiError } from "@/shared/lib/errors";

/** REFERENCE ONLY — delete with src/features/example. In-memory todos; created ones stay until the page reloads. */
export function mockExampleApi(runtime: MockRuntime): ExampleApi {
  const todos: Todo[] = [
    { id: 1, userId: 1, title: "Read ADR-009 (service internal architecture)", completed: true },
    { id: 2, userId: 1, title: "Add a port and its mock adapter", completed: false },
    { id: 3, userId: 1, title: "Switch the port to its HTTP adapter", completed: false },
  ];
  return {
    listTodos: () => runtime.call(() => todos),
    getTodo: (id) =>
      runtime.call(() => {
        const todo = todos.find((t) => t.id === id);
        if (!todo) throw new ApiError(404, "NOT_FOUND", "There is no such todo.");
        return todo;
      }),
    createTodo: (input) =>
      runtime.call(() => {
        const todo: Todo = { id: Math.max(0, ...todos.map((t) => t.id)) + 1, userId: 1, completed: false, ...input };
        todos.unshift(todo);
        return todo;
      }),
  };
}
