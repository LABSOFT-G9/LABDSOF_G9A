import type { CreateTodo, Todo } from "@/contracts/example";

/** REFERENCE ONLY — delete with src/features/example. A port with a query, a detail and a mutation. */
export interface ExampleApi {
  listTodos(): Promise<Todo[]>;
  getTodo(id: number): Promise<Todo>;
  createTodo(input: CreateTodo): Promise<Todo>;
}
