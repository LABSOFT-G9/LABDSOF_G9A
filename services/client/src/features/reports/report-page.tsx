import { useSuspenseQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowLeft } from "lucide-react";
import { useServices } from "@/app/context";
import { IssueStateBadge } from "@/features/issues";
import { reportQuery } from "@/features/reports/api";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { formatDateTime } from "@/shared/lib/format";
import { Button } from "@/shared/ui/button";
import { Card, CardContent } from "@/shared/ui/card";

/**
 * One report: the receipt again, plus the description. The route loader awaited the query (an API 404 became the
 * not-found page), so the data is there on the first render. The text is rendered as text, never as HTML.
 */
export function ReportPage({ reportId }: { reportId: string }) {
  const { issues } = useServices();
  const { data: report } = useSuspenseQuery(reportQuery(issues, reportId));

  return (
    <>
      <Button asChild variant="ghost" size="sm" className="mb-4 -ml-2">
        <Link to="/reports">
          <ArrowLeft aria-hidden />
          {t.reports.backToReports}
        </Link>
      </Button>
      <PageHeader title={`${t.reports.detailTitle} · ${report.areaLabel}`} />
      <Card>
        <CardContent className="space-y-5">
          <dl className="grid gap-3 text-sm sm:grid-cols-[auto_1fr] sm:gap-x-6">
            <dt className="text-muted-foreground">{t.reports.reportId}</dt>
            <dd className="font-mono break-all">{report.reportId}</dd>
            <dt className="text-muted-foreground">{t.reports.status}</dt>
            <dd>
              <IssueStateBadge state={report.issue.state} />
            </dd>
            <dt className="text-muted-foreground">{t.reports.submittedAt}</dt>
            <dd>
              <time dateTime={report.submittedAt}>{formatDateTime(report.submittedAt)}</time>
            </dd>
            <dt className="text-muted-foreground">{t.reports.area}</dt>
            <dd>{report.areaLabel}</dd>
            <dt className="text-muted-foreground">{t.reports.source}</dt>
            <dd>{report.authoredByMe ? t.reports.mine : t.reports.sources[report.source]}</dd>
          </dl>
          <section aria-labelledby="report-description" className="space-y-2">
            <h2 id="report-description" className="text-sm font-medium">
              {t.reports.description}
            </h2>
            <p className="text-sm break-words whitespace-pre-wrap">{report.text}</p>
          </section>
          <Button asChild variant="outline">
            <Link to="/issues/$issueId" params={{ issueId: report.issueId }}>
              {t.issues.followIssue}
            </Link>
          </Button>
        </CardContent>
      </Card>
    </>
  );
}
