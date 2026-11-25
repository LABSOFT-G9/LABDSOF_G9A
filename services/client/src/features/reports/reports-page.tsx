import { useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { FileText, Plus } from "lucide-react";
import { useServices } from "@/app/context";
import { IssueStateBadge } from "@/features/issues";
import { reportListQuery } from "@/features/reports/api";
import { ErrorState } from "@/shared/components/error-state";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { formatDateTime, timeAgo } from "@/shared/lib/format";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/shared/ui/empty";
import { Skeleton } from "@/shared/ui/skeleton";

function NewReportButton() {
  return (
    <Button asChild>
      <Link to="/reports/new">
        <Plus aria-hidden />
        {t.reports.newReport}
      </Link>
    </Button>
  );
}

/** US-01: "view it again later". Own reports, plus every report of the buildings the caller administers. */
export function ReportsPage() {
  const { issues, clock } = useServices();
  const query = useInfiniteQuery(reportListQuery(issues));
  const reports = query.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <>
      <PageHeader title={t.reports.title} subtitle={t.reports.subtitle} />
      <div className="mb-6">
        <NewReportButton />
      </div>
      {query.isPending ? (
        <div className="space-y-3" role="status">
          <span className="sr-only">{t.common.loading}</span>
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-20 w-full" />
          ))}
        </div>
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : reports.length === 0 ? (
        <Empty className="border">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FileText aria-hidden />
            </EmptyMedia>
            <EmptyTitle>{t.reports.empty}</EmptyTitle>
            <EmptyDescription>{t.reports.emptyHint}</EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            <NewReportButton />
          </EmptyContent>
        </Empty>
      ) : (
        <>
          <ul className="space-y-3">
            {reports.map((report) => (
              <li key={report.reportId}>
                <Card className="py-4">
                  <CardContent className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                    <div className="min-w-0 space-y-1">
                      <Link
                        to="/reports/$reportId"
                        params={{ reportId: report.reportId }}
                        className="font-medium underline-offset-4 hover:underline focus-visible:underline"
                      >
                        {report.areaLabel}
                      </Link>
                      <p className="text-muted-foreground text-sm">
                        {report.authoredByMe ? t.reports.mine : t.reports.sources[report.source]} ·{" "}
                        <time dateTime={report.submittedAt} title={formatDateTime(report.submittedAt)}>
                          {timeAgo(report.submittedAt, clock.now())}
                        </time>
                      </p>
                    </div>
                    <IssueStateBadge state={report.issue.state} />
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
              {query.isFetchingNextPage ? t.reports.loadingMore : t.reports.showMore}
            </Button>
          ) : null}
        </>
      )}
    </>
  );
}
