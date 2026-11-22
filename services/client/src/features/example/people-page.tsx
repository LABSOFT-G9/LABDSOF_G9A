import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { ChevronRight, Search, Users } from "lucide-react";
import { type FormEvent, useId } from "react";
import { useServices } from "@/app/context";
import { peopleQuery } from "@/features/example/api";
import { CreatePersonForm } from "@/features/example/create-person-form";
import { PersonAvatar } from "@/features/example/person-avatar";
import { ErrorState } from "@/shared/components/error-state";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { Button } from "@/shared/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/shared/ui/card";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/shared/ui/empty";
import { Input } from "@/shared/ui/input";
import { Skeleton } from "@/shared/ui/skeleton";

/**
 * REFERENCE ONLY. The search lives in the URL (`?q=`), so it survives reloads and can be shared; the loader already
 * filled the cache for it, so `useQuery` renders at once and keeps it fresh.
 */
export function PeoplePage({ search }: { search: string | undefined }) {
  const { example } = useServices();
  const query = useQuery(peopleQuery(example, search));
  const navigate = useNavigate();
  const searchId = useId();

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const q = String(new FormData(event.currentTarget).get("q") ?? "").trim();
    void navigate({ to: "/example", search: { q: q || undefined }, replace: true });
  }

  return (
    <>
      <PageHeader title={t.example.title} subtitle={t.example.subtitle} />
      <div className="grid gap-6 lg:grid-cols-[1fr_20rem]">
        <section aria-labelledby="people-title" className="min-w-0 space-y-4">
          <h2 id="people-title" className="sr-only">
            {t.example.listTitle}
          </h2>
          <form role="search" onSubmit={onSearch} className="flex gap-2">
            <label htmlFor={searchId} className="sr-only">
              {t.example.searchLabel}
            </label>
            <Input
              id={searchId}
              key={search ?? ""}
              name="q"
              type="search"
              defaultValue={search ?? ""}
              placeholder={t.example.searchPlaceholder}
            />
            <Button type="submit" variant="outline">
              <Search aria-hidden />
              <span className="sr-only sm:not-sr-only">{t.example.search}</span>
            </Button>
          </form>

          {query.isPending ? (
            <div className="space-y-2" aria-busy="true" aria-label={t.common.loading}>
              {[0, 1, 2, 3, 4].map((i) => (
                <Skeleton key={i} className="h-16 w-full" />
              ))}
            </div>
          ) : query.isError ? (
            <ErrorState error={query.error} onRetry={() => void query.refetch()} />
          ) : query.data.length === 0 ? (
            <Empty className="border">
              <EmptyHeader>
                <EmptyMedia variant="icon">
                  <Users aria-hidden />
                </EmptyMedia>
                <EmptyTitle>{t.example.empty}</EmptyTitle>
                {search ? <EmptyDescription>{t.example.emptySearch(search)}</EmptyDescription> : null}
              </EmptyHeader>
            </Empty>
          ) : (
            <ul className="divide-y rounded-lg border">
              {query.data.map((person) => (
                <li key={person.id}>
                  <Link
                    to="/example/$personId"
                    params={{ personId: String(person.id) }}
                    className="hover:bg-accent focus-visible:ring-ring/50 flex min-h-16 items-center gap-3 px-4 py-3 outline-none focus-visible:ring-[3px] focus-visible:ring-inset"
                  >
                    <PersonAvatar person={person} />
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium">{person.name}</span>
                      <span className="text-muted-foreground block truncate text-xs">
                        {[person.jobTitle, person.company].filter(Boolean).join(" · ") || person.email}
                      </span>
                    </span>
                    <ChevronRight className="text-muted-foreground size-4 shrink-0" aria-hidden />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
        <Card className="h-fit">
          <CardHeader>
            <CardTitle className="text-base">
              <h2>{t.example.createTitle}</h2>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <CreatePersonForm />
          </CardContent>
        </Card>
      </div>
    </>
  );
}
