import type { HttpClient } from "@/adapters/http/client";
import { TodoListSchema, TodoSchema } from "@/contracts/example";
import type { ExampleApi } from "@/ports/example-api";

/**
 * REFERENCE ONLY — delete with src/features/example.
 * A complete HTTP adapter: paths under our /api prefix, every response parsed with its contract. In `npm run dev`
 * the Vite proxy forwards /api/example to JSONPlaceholder; there is no such route on the deployed edge.
 */
export function httpExampleApi(http: HttpClient): ExampleApi {
  return {
    listTodos: async () => (await http.get("/api/example/todos", TodoListSchema)).slice(0, 20),
    getTodo: (id) => http.get(`/api/example/todos/${id}`, TodoSchema),
    createTodo: (input) =>
      http.request("POST", "/api/example/todos", {
        schema: TodoSchema,
        body: { ...input, userId: 1, completed: false },
      }),
  };
}
