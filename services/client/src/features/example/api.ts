import { mutationOptions, type QueryClient, queryOptions } from "@tanstack/react-query";
import type { ExampleApi, Person } from "@/ports/example-api";

/*
 * REFERENCE ONLY — delete with the rest of src/features/example.
 * The pattern every feature follows: a key factory, one `queryOptions` per resource (used by loaders and
 * components alike) and `mutationOptions` that keep the cache right after a write.
 */

export const personKeys = {
  all: ["example", "people"] as const,
  lists: () => [...personKeys.all, "list"] as const,
  list: (search: string | undefined) => [...personKeys.lists(), { search: search ?? "" }] as const,
  detail: (id: number) => [...personKeys.all, "detail", id] as const,
};

export const peopleQuery = (example: ExampleApi, search: string | undefined) =>
  queryOptions({ queryKey: personKeys.list(search), queryFn: () => example.listPeople({ search }) });

export const personQuery = (example: ExampleApi, id: number) =>
  queryOptions({ queryKey: personKeys.detail(id), queryFn: () => example.getPerson(id) });

export const createPersonMutation = (example: ExampleApi, queryClient: QueryClient) =>
  mutationOptions({
    mutationFn: example.createPerson,
    onSuccess: (person: Person) => {
      // DummyJSON fakes writes (nothing is stored), so refetching would lose the new person: put it in the cached
      // lists instead. Against a real API, `invalidateQueries({ queryKey: personKeys.lists() })` is usually enough.
      queryClient.setQueriesData<Person[]>({ queryKey: personKeys.lists() }, (people) =>
        people ? [person, ...people] : people,
      );
      queryClient.setQueryData(personKeys.detail(person.id), person);
    },
  });
