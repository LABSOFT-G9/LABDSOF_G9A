import type { HttpClient } from "@/adapters/http/client";
import { PeoplePageDtoSchema, type PersonDto, PersonDtoSchema } from "@/contracts/example";
import type { ExampleApi, Person } from "@/ports/example-api";

const PREFIX = "/api/example";
const PAGE_SIZE = 24;
const FIELDS = "firstName,lastName,email,image,company";
const UPSTREAM_ORIGIN = "https://dummyjson.com";

/**
 * DTO → model. Avatars point at dummyjson.com; rewriting them under our own /api/example prefix keeps every image
 * same-origin, as the CSP requires (`img-src 'self'`).
 */
export function toPerson(dto: PersonDto): Person {
  const image = dto.image.startsWith(UPSTREAM_ORIGIN) ? `${PREFIX}${dto.image.slice(UPSTREAM_ORIGIN.length)}` : null;
  return {
    id: dto.id,
    name: `${dto.firstName} ${dto.lastName}`.trim(),
    email: dto.email,
    avatarUrl: image,
    jobTitle: dto.company?.title || null,
    company: dto.company?.name || null,
  };
}

/**
 * REFERENCE ONLY — delete with src/features/example.
 * A complete HTTP adapter: paths under our /api prefix, every response parsed with its contract and mapped to the
 * model. In `npm run dev` the Vite proxy forwards /api/example to DummyJSON; the deployed edge has no such route.
 */
export function httpExampleApi(http: HttpClient): ExampleApi {
  return {
    async listPeople({ search }, signal) {
      const query = new URLSearchParams({ limit: String(PAGE_SIZE), select: FIELDS });
      const q = search?.trim();
      if (q) query.set("q", q);
      const path = q ? `${PREFIX}/users/search?${query}` : `${PREFIX}/users?${query}`;
      return (await http.get(path, PeoplePageDtoSchema, signal)).users.map(toPerson);
    },
    getPerson: async (id, signal) => toPerson(await http.get(`${PREFIX}/users/${id}`, PersonDtoSchema, signal)),
    // DummyJSON fakes writes: it answers as if it stored the person, but nothing is kept.
    createPerson: async (input) =>
      toPerson(await http.request("POST", `${PREFIX}/users/add`, { schema: PersonDtoSchema, body: input })),
  };
}
