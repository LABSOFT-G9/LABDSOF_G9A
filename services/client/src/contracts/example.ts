import { z } from "zod";

/*
 * REFERENCE ONLY — delete with src/features/example once real features exist.
 * Contracts of DummyJSON's /users (https://dummyjson.com/docs/users): synthetic people, reached through the dev
 * proxy at /api/example. Shows the pattern: one schema per payload, types inferred from it, never written twice.
 * These are WIRE shapes; the adapter maps them to the model the UI uses (`Person` in src/ports/example-api.ts).
 */

export const PersonDtoSchema = z.object({
  id: z.number().int(),
  firstName: z.string(),
  lastName: z.string(),
  email: z.string(),
  /** Absolute URL of a PNG, or "" for people created through the API. */
  image: z.string(),
  company: z.object({ name: z.string(), title: z.string(), department: z.string() }).partial().optional(),
});
export type PersonDto = z.infer<typeof PersonDtoSchema>;

export const PeoplePageDtoSchema = z.object({
  users: z.array(PersonDtoSchema),
  total: z.number().int(),
  skip: z.number().int(),
  limit: z.number().int(),
});

/** The create form and the POST body: validated on the client with the same schema that types the request. */
export const CreatePersonSchema = z.object({
  firstName: z.string().trim().min(1, "Enter a first name.").max(60, "Use at most 60 characters."),
  lastName: z.string().trim().min(1, "Enter a last name.").max(60, "Use at most 60 characters."),
  email: z.email("Enter a valid email address."),
});
export type CreatePerson = z.infer<typeof CreatePersonSchema>;
