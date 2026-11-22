import { useQuery } from "@tanstack/react-query";
import { Wrench } from "lucide-react";
import { useServices } from "@/app/context";
import { issueListQuery } from "@/features/issues/api";
import { IssueStateBadge } from "@/features/issues/issue-state";
import { ErrorState } from "@/shared/components/error-state";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { formatDateTime, timeAgo } from "@/shared/lib/format";
import { Card, CardContent } from "@/shared/ui/card";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/shared/ui/empty";
import { Skeleton } from "@/shared/ui/skeleton";

export function IssuesPage() {
  const { issues, clock } = useServices();
  const query = useQuery(issueListQuery(issues));

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
      ) : query.data.length === 0 ? (
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
        <ul className="space-y-3">
          {query.data.map((issue) => (
            <li key={issue.id}>
              <Card className="py-4">
                <CardContent className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0 space-y-1">
                    <p className="truncate font-medium">{issue.title}</p>
                    <p className="text-muted-foreground text-sm">
                      {issue.category ?? t.issues.uncategorised}
                      {issue.urgency ? ` · ${t.issues.urgency[issue.urgency]}` : ""} ·{" "}
                      <time dateTime={issue.createdAt} title={formatDateTime(issue.createdAt)}>
                        {timeAgo(issue.createdAt, clock.now())}
                      </time>
                    </p>
                  </div>
                  <IssueStateBadge state={issue.state} />
                </CardContent>
              </Card>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
