import { useQuery, useSuspenseQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowLeft } from "lucide-react";
import { useServices } from "@/app/context";
import type { IssueState } from "@/contracts/issue";
import { hasBuildingRole, useSession } from "@/features/auth";
import { historyQuery, issueQuery } from "@/features/issues/api";
import { IssueHistory } from "@/features/issues/issue-history";
import { IssueStateBadge } from "@/features/issues/issue-state";
import { PublishSummary, SharedIssuePanel } from "@/features/issues/shared-issue";
import { CriticalCheckBanner, SuggestionPanel } from "@/features/issues/suggestion-panel";
import { TriageForm } from "@/features/issues/triage-form";
import { TriageBadge } from "@/features/issues/triage-status";
import { ErrorState } from "@/shared/components/error-state";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { formatDateTime, timeAgo } from "@/shared/lib/format";
import { Button } from "@/shared/ui/button";
import { Card, CardContent, CardHeader } from "@/shared/ui/card";
import { Skeleton } from "@/shared/ui/skeleton";

/** Manual triage is allowed from these states (technical design §3.1). */
const TRIAGEABLE: readonly IssueState[] = ["received", "in_triage", "triaged"];

/**
 * US-02: one issue. Everyone who may see it gets its status, where triage stands and its history (residents: public
 * entries only, as the service decides). The building's administrators also get the triage form while triage may
 * still change. The loader awaited the issue (a 404 became the not-found page); the history loads in place.
 */
export function IssuePage({ issueId }: { issueId: string }) {
  const { issues, clock } = useServices();
  const session = useSession();
  const { data: issue } = useSuspenseQuery(issueQuery(issues, issueId));
  const history = useQuery(historyQuery(issues, issueId));
  const isAdministrator = hasBuildingRole(session, issue.buildingId, "administrator");
  const now = clock.now();

  return (
    <>
      <Button asChild variant="ghost" size="sm" className="mb-4 -ml-2">
        <Link to="/issues">
          <ArrowLeft aria-hidden />
          {t.issues.backToIssues}
        </Link>
      </Button>
      <PageHeader title={issue.areaLabel} subtitle={issue.buildingName} />

      <div className="grid gap-6 lg:grid-cols-[1fr_minmax(0,22rem)]">
        <div className="space-y-6">
          {issue.criticalCheck ? <CriticalCheckBanner check={issue.criticalCheck} issue={issue} now={now} /> : null}
          <Card>
            <CardContent>
              <dl className="grid gap-3 text-sm sm:grid-cols-[auto_1fr] sm:gap-x-6">
                <dt className="text-muted-foreground">{t.issues.status}</dt>
                <dd>
                  <IssueStateBadge state={issue.state} />
                </dd>
                <dt className="text-muted-foreground">{t.issues.decision}</dt>
                <dd className="space-y-1">
                  <TriageBadge issue={issue} />
                  <p className="text-muted-foreground">
                    {issue.triage
                      ? `${t.issues.decided} · ${t.issues.decidedAt(timeAgo(issue.triage.decidedAt, now))}`
                      : issue.suggestion
                        ? t.issues.suggestedHint
                        : t.issues.pendingHint}
                  </p>
                  {issue.triage?.reason ? (
                    <p className="break-words whitespace-pre-wrap">
                      <span className="text-muted-foreground">{t.issues.reason}: </span>
                      {issue.triage.reason}
                    </p>
                  ) : null}
                </dd>
                <dt className="text-muted-foreground">{t.issues.reportedAt}</dt>
                <dd>
                  <time dateTime={issue.createdAt}>{formatDateTime(issue.createdAt)}</time>
                </dd>
              </dl>
            </CardContent>
          </Card>

          {isAdministrator && TRIAGEABLE.includes(issue.state) ? (
            <Card>
              <CardContent>
                <TriageForm issue={issue} intro={<SuggestionPanel issue={issue} now={now} />} />
              </CardContent>
            </Card>
          ) : null}

          {isAdministrator ? (
            <Card>
              <CardContent>
                <PublishSummary issue={issue} />
              </CardContent>
            </Card>
          ) : issue.summary ? (
            <Card>
              <CardContent>
                <SharedIssuePanel issue={issue} canConfirm={hasBuildingRole(session, issue.buildingId, "resident")} />
              </CardContent>
            </Card>
          ) : null}
        </div>

        <Card>
          <CardHeader>
            <h2 className="text-base font-semibold">{t.issues.history}</h2>
          </CardHeader>
          <CardContent>
            {history.isPending ? (
              <div role="status" className="space-y-2">
                <span className="sr-only">{t.common.loading}</span>
                <Skeleton className="h-12 w-full" />
                <Skeleton className="h-12 w-full" />
              </div>
            ) : history.isError ? (
              <ErrorState error={history.error} onRetry={() => void history.refetch()} />
            ) : (
              <IssueHistory entries={history.data.items} />
            )}
          </CardContent>
        </Card>
      </div>
    </>
  );
}
