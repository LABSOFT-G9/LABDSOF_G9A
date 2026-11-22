import type { MockRuntime } from "@/adapters/mock/runtime";
import type { ExampleApi, Person } from "@/ports/example-api";
import { ApiError } from "@/shared/lib/errors";

/** REFERENCE ONLY — delete with src/features/example. Synthetic people; created ones stay until the page reloads. */
export function mockExampleApi(runtime: MockRuntime): ExampleApi {
  const person = (id: number, name: string, jobTitle: string | null, company: string | null): Person => ({
    id,
    name,
    email: `${name.toLowerCase().replace(/\s+/g, ".")}@people.test`,
    avatarUrl: null,
    jobTitle,
    company,
  });
  const people: Person[] = [
    person(1, "Alex Example", "Building administrator", "Demo Condominiums"),
    person(2, "Bea Sample", "Plumber", "Demo Plumbing"),
    person(3, "Caio Placeholder", "Electrician", "Demo Electrics"),
    person(4, "Dana Fixture", "Lift technician", "Demo Lifts"),
    person(5, "Eli Stub", "Resident", null),
    person(6, "Fran Mock", null, null),
  ];
  return {
    listPeople: ({ search }) =>
      runtime.call(() => {
        const q = search?.trim().toLowerCase();
        return q ? people.filter((p) => `${p.name} ${p.email}`.toLowerCase().includes(q)) : people;
      }),
    getPerson: (id) =>
      runtime.call(() => {
        const found = people.find((p) => p.id === id);
        if (!found) throw new ApiError(404, "NOT_FOUND", "There is no such person.");
        return found;
      }),
    createPerson: ({ firstName, lastName, email }) =>
      runtime.call(() => {
        const created: Person = {
          id: Math.max(0, ...people.map((p) => p.id)) + 1,
          name: `${firstName} ${lastName}`,
          email,
          avatarUrl: null,
          jobTitle: null,
          company: null,
        };
        people.unshift(created);
        return created;
      }),
  };
}
