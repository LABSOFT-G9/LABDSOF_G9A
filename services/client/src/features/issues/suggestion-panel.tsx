import { Clock, Info, Siren, Sparkles, TriangleAlert } from "lucide-react";
import { useId } from "react";
import {
  type CriticalAlert,
  type Issue,
  KNOWN_FAILURE_CODES,
  type KnownFailureCode,
  TRIAGE_PENDING_AFTER_MS,
} from "@/contracts/issue";
import { t } from "@/shared/i18n";
import { formatDateTime, timeAgo } from "@/shared/lib/format";
import { Alert, AlertDescription, AlertTitle } from "@/shared/ui/alert";
import { Badge } from "@/shared/ui/badge";

const isKnownFailure = (code: string): code is KnownFailureCode =>
  (KNOWN_FAILURE_CODES as readonly string[]).includes(code);

const failureText = (code: string) => t.issues.suggestion.failure[isKnownFailure(code) ? code : "other"];

/**
 * US-03-02: what the suggester proposes, for the administrator about to decide. Always marked unconfirmed, with its
 * origin, its explanation and, on `fallback`, why the AI could not be used. Without a suggestion it says where
 * processing stands: still waiting, or (after 2 minutes) "triage pending — classify manually". Shown only before a
 * decision: a suggestion that arrives later never competes with it.
 */
export function SuggestionPanel({
  issue,
  now,
}: {
  issue: Pick<Issue, "state" | "createdAt" | "triage" | "suggestion">;
  now: Date;
}) {
  const id = useId();
  const { suggestion } = issue;
  if (issue.triage) return null;

  if (!suggestion) {
    if (issue.state !== "received") return null;
    const waited = now.getTime() - new Date(issue.createdAt).getTime();
    if (waited < TRIAGE_PENDING_AFTER_MS) {
      return (
        <p className="text-muted-foreground flex items-center gap-2 text-sm">
          <Clock className="size-4 shrink-0" aria-hidden />
          {t.issues.suggestion.waiting}
        </p>
      );
    }
    return (
      <Alert role="note">
        <Clock aria-hidden />
        <AlertTitle>{t.issues.suggestion.pendingTitle}</AlertTitle>
        <AlertDescription>
          <p>{t.issues.suggestion.pendingBody}</p>
        </AlertDescription>
      </Alert>
    );
  }

  return (
    <section aria-labelledby={`${id}-title`} className="space-y-3 rounded-lg border border-dashed p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id={`${id}-title`} className="text-sm font-semibold">
          {t.issues.suggestion.title}
        </h3>
        <Badge variant="outline" className="border-dashed">
          <Sparkles aria-hidden />
          {t.issues.suggestion.unconfirmed(t.issues.origins[suggestion.origin])}
        </Badge>
      </div>

      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-sm">
        <dt className="text-muted-foreground">{t.issues.category}</dt>
        <dd>{t.issues.categories[suggestion.category]}</dd>
        <dt className="text-muted-foreground">{t.issues.urgencyLabel}</dt>
        <dd>{t.issues.urgency[suggestion.urgency]}</dd>
      </dl>

      {suggestion.rationale ? (
        <div className="space-y-1 text-sm">
          <p className="text-muted-foreground">{t.issues.suggestion.explanation}</p>
          {/* Suggester output is data: React renders it as text, never as markup or a link. */}
          <blockquote className="border-l-2 pl-3 break-words whitespace-pre-wrap">{suggestion.rationale}</blockquote>
          <p className="text-muted-foreground text-xs">{t.issues.suggestion.explanationHint}</p>
        </div>
      ) : null}

      {suggestion.needsReview ? (
        <p className="flex items-center gap-2 text-sm">
          <Info className="size-4 shrink-0" aria-hidden />
          {t.issues.suggestion.needsReview}
        </p>
      ) : null}

      {suggestion.origin === "fallback" ? (
        <Alert role="note">
          <TriangleAlert aria-hidden />
          <AlertTitle>{t.issues.suggestion.failureTitle}</AlertTitle>
          <AlertDescription>
            <p>
              {failureText(suggestion.failureCode ?? "")} {t.issues.suggestion.fallbackNote}
            </p>
          </AlertDescription>
        </Alert>
      ) : null}

      <p className="text-muted-foreground text-sm">
        {suggestion.suggestedAt ? (
          <>
            <time dateTime={suggestion.suggestedAt} title={formatDateTime(suggestion.suggestedAt)}>
              {t.issues.suggestion.suggestedAt(timeAgo(suggestion.suggestedAt, now))}
            </time>
            {" · "}
          </>
        ) : null}
        {t.issues.suggestion.notADecision}
      </p>
    </section>
  );
}

/**
 * US-03/US-14: a critical alert stays in view whatever the suggestion says. It is Issue's own check, not the AI's, so
 * a lower suggested urgency is pointed out instead of hiding the alert. Static content, so a note, not a live alert.
 */
export function CriticalAlertBanner({
  alert,
  issue,
  now,
}: {
  alert: CriticalAlert;
  issue: Pick<Issue, "triage" | "suggestion">;
  now: Date;
}) {
  const suggested = !issue.triage ? issue.suggestion : null;
  return (
    <Alert role="note" variant="destructive" className="border-destructive">
      <Siren aria-hidden />
      <AlertTitle>{t.issues.critical.title}</AlertTitle>
      <AlertDescription>
        <p className="text-foreground">{alert.reason}</p>
        {suggested && suggested.urgency !== "critical" ? (
          <p>{t.issues.critical.disagrees(t.issues.urgency[suggested.urgency])}</p>
        ) : null}
        {!issue.triage ? <p>{t.issues.critical.review}</p> : null}
        <p className="text-xs">
          <time dateTime={alert.raisedAt} title={formatDateTime(alert.raisedAt)}>
            {t.issues.critical.raisedAt(timeAgo(alert.raisedAt, now))}
          </time>
        </p>
      </AlertDescription>
    </Alert>
  );
}

/** The list's short form of a critical alert: icon and words, never colour alone. */
export function CriticalBadge() {
  return (
    <Badge variant="destructive">
      <Siren aria-hidden />
      {t.issues.critical.badge}
    </Badge>
  );
}
