import { useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Wrench } from "lucide-react";
import { useServices } from "@/app/context";
import { issueListQuery } from "@/features/issues/api";
import { IssueStateBadge } from "@/features/issues/issue-state";
import { CriticalBadge } from "@/features/issues/suggestion-panel";
import { TriageBadge } from "@/features/issues/triage-status";
import { ErrorState } from "@/shared/components/error-state";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { formatDateTime, timeAgo } from "@/shared/lib/format";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/shared/ui/empty";
import { Skeleton } from "@/shared/ui/skeleton";

/**
 * US-02: administrators see the issues of the buildings they manage; residents see the issues of their own reports.
 * The service filters; this page only shows what it gets. Each row says where triage stands: decided, suggested or
 * pending.
 */
export function IssuesPage() {
  const { issues, clock } = useServices();
  const query = useInfiniteQuery(issueListQuery(issues));
  const items = query.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <>
      <PageHeader title={t.issues.title} subtitle={t.issues.subtitle} />
      {query.isPending ? (
        <div className="space-y-3" role="status">
          <span className="sr-only">{t.common.loading}</span>
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-20 w-full" />
          ))}
        </div>
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : items.length === 0 ? (
        <Empty className="border">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <Wrench aria-hidden />
            </EmptyMedia>
            <EmptyTitle>{t.issues.empty}</EmptyTitle>
            <EmptyDescription>{t.issues.emptyHint}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <>
          <ul className="space-y-3">
            {items.map((issue) => (
              <li key={issue.issueId}>
                <Card className="py-4">
                  <CardContent className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                    <div className="min-w-0 space-y-1">
                      <Link
                        to="/issues/$issueId"
                        params={{ issueId: issue.issueId }}
                        className="font-medium underline-offset-4 hover:underline focus-visible:underline"
                      >
                        {issue.areaLabel}
                      </Link>
                      <p className="text-muted-foreground text-sm">
                        {issue.buildingName} ·{" "}
                        <time dateTime={issue.createdAt} title={formatDateTime(issue.createdAt)}>
                          {timeAgo(issue.createdAt, clock.now())}
                        </time>
                      </p>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      {issue.criticalAlert ? <CriticalBadge /> : null}
                      <TriageBadge issue={issue} />
                      <IssueStateBadge state={issue.state} />
                    </div>
                  </CardContent>
                </Card>
              </li>
            ))}
          </ul>
          {query.hasNextPage ? (
            <Button
              variant="outline"
              className="mt-4"
              disabled={query.isFetchingNextPage}
              onClick={() => void query.fetchNextPage()}
            >
              {query.isFetchingNextPage ? t.issues.loadingMore : t.issues.showMore}
            </Button>
          ) : null}
        </>
      )}
    </>
  );
}
