import { useSuspenseQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowLeft, Briefcase, Building, Mail } from "lucide-react";
import { useServices } from "@/app/context";
import { personQuery } from "@/features/example/api";
import { PersonAvatar } from "@/features/example/person-avatar";
import { t } from "@/shared/i18n";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";

/**
 * REFERENCE ONLY. The route loader awaited this query (an API 404 became the not-found page), so the suspense
 * query has its data on the first render.
 */
export function PersonPage({ personId }: { personId: number }) {
  const { example } = useServices();
  const { data: person } = useSuspenseQuery(personQuery(example, personId));

  return (
    <>
      <Button asChild variant="ghost" size="sm" className="mb-4 -ml-2">
        <Link to="/example">
          <ArrowLeft aria-hidden />
          {t.common.back}
        </Link>
      </Button>
      <Card>
        <CardContent className="flex flex-col items-start gap-5 sm:flex-row sm:items-center">
          <PersonAvatar person={person} className="size-20" />
          <div className="min-w-0 space-y-3">
            <h1 className="text-2xl font-semibold">{person.name}</h1>
            <dl className="text-muted-foreground grid gap-2 text-sm">
              <div className="flex items-center gap-2">
                <dt>
                  <Mail className="size-4" aria-hidden />
                  <span className="sr-only">{t.example.email}</span>
                </dt>
                <dd className="truncate">{person.email}</dd>
              </div>
              {person.jobTitle ? (
                <div className="flex items-center gap-2">
                  <dt>
                    <Briefcase className="size-4" aria-hidden />
                    <span className="sr-only">{t.example.jobTitle}</span>
                  </dt>
                  <dd>{person.jobTitle}</dd>
                </div>
              ) : null}
              {person.company ? (
                <div className="flex items-center gap-2">
                  <dt>
                    <Building className="size-4" aria-hidden />
                    <span className="sr-only">{t.example.company}</span>
                  </dt>
                  <dd>{person.company}</dd>
                </div>
              ) : null}
            </dl>
          </div>
        </CardContent>
      </Card>
    </>
  );
}
