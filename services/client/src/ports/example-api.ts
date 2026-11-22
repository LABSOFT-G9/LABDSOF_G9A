import type { CreatePerson } from "@/contracts/example";

/**
 * REFERENCE ONLY — delete with src/features/example.
 * The model the UI works with. Not the wire shape: adapters map the DTO into this (names joined, empty strings to
 * null, avatar URLs made same-origin), so the UI never depends on a particular API's quirks.
 */
export interface Person {
  readonly id: number;
  readonly name: string;
  readonly email: string;
  /** Same-origin URL (the CSP allows `img-src 'self'` only), or null to show initials. */
  readonly avatarUrl: string | null;
  readonly jobTitle: string | null;
  readonly company: string | null;
}

/** A port with a filtered list, a detail and a mutation: the three shapes every feature needs. */
export interface ExampleApi {
  listPeople(filter: { search?: string | undefined }): Promise<Person[]>;
  getPerson(id: number): Promise<Person>;
  createPerson(input: CreatePerson): Promise<Person>;
}
