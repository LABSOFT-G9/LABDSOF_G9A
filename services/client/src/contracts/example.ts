import { z } from "zod";

/*
 * REFERENCE ONLY — delete with src/features/example once real features exist.
 * Contracts of JSONPlaceholder's /todos (https://jsonplaceholder.typicode.com/guide/), reached through the dev
 * proxy at /api/example. Shows the pattern: one schema per payload, types inferred from it, never written twice.
 */

export const TodoSchema = z.object({
  id: z.number().int(),
  userId: z.number().int(),
  title: z.string(),
  completed: z.boolean(),
});
export type Todo = z.infer<typeof TodoSchema>;

export const TodoListSchema = z.array(TodoSchema);

export const CreateTodoSchema = z.object({
  title: z.string().trim().min(3, "Use at least 3 characters.").max(120, "Use at most 120 characters."),
});
export type CreateTodo = z.infer<typeof CreateTodoSchema>;
