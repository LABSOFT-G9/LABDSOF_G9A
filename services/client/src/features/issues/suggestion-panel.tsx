import { Clock, Info, ShieldQuestion, Siren, TriangleAlert } from "lucide-react";
import { useId } from "react";
import {
  type CriticalCheck,
  type Issue,
  KNOWN_FAILURE_CODES,
  type KnownFailureCode,
  TRIAGE_PENDING_AFTER_MS,
} from "@/contracts/issue";
import { signalText } from "@/features/issues/critical-signals";
import { ORIGIN_ICONS } from "@/features/issues/origin-icons";
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

  const OriginIcon = ORIGIN_ICONS[suggestion.origin];
  // What produced it: the model for `ai`, the rules version otherwise (US-14-03). Omitted when the service does not say.
  const source =
    suggestion.origin === "ai"
      ? suggestion.modelVersion && t.issues.suggestion.model(suggestion.modelVersion)
      : suggestion.rulesVersion && t.issues.suggestion.rules(suggestion.rulesVersion);

  return (
    <section aria-labelledby={`${id}-title`} className="space-y-3 rounded-lg border border-dashed p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id={`${id}-title`} className="text-sm font-semibold">
          {t.issues.suggestion.title}
        </h3>
        <Badge variant="outline" className="border-dashed">
          <OriginIcon aria-hidden />
          {t.issues.suggestion.unconfirmed(t.issues.origins[suggestion.origin])}
        </Badge>
      </div>

      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-1 text-sm">
        <dt className="text-muted-foreground">{t.issues.category}</dt>
        <dd>{t.issues.categories[suggestion.category]}</dd>
        <dt className="text-muted-foreground">{t.issues.urgencyLabel}</dt>
        <dd>{t.issues.urgency[suggestion.urgency]}</dd>
        {source ? (
          <>
            <dt className="text-muted-foreground">{t.issues.suggestion.producedBy}</dt>
            <dd>{source}</dd>
          </>
        ) : null}
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
 * US-03/US-14: Issue's own critical check stays in view whatever the suggestion says. Every matched rule is listed; a
 * lower suggested urgency is pointed out instead of hiding them. A check that has not finished says so in words, so it
 * never reads as "nothing found" (#96). A finished check with no signals shows nothing. Static content, so a note, not
 * a live alert.
 */
export function CriticalCheckBanner({
  check,
  issue,
  now,
}: {
  check: CriticalCheck;
  issue: Pick<Issue, "triage" | "suggestion">;
  now: Date;
}) {
  const pending = check.status === "pending";
  if (check.signals.length === 0) {
    if (!pending) return null;
    return (
      <Alert role="note">
        <ShieldQuestion aria-hidden />
        <AlertTitle>{t.issues.critical.pendingTitle}</AlertTitle>
        <AlertDescription>
          <p>{t.issues.critical.pendingBody}</p>
        </AlertDescription>
      </Alert>
    );
  }
  const suggested = !issue.triage ? issue.suggestion : null;
  return (
    <Alert role="note" variant="destructive" className="border-destructive">
      <Siren aria-hidden />
      <AlertTitle>{t.issues.critical.title}</AlertTitle>
      <AlertDescription>
        <ul className="text-foreground space-y-1">
          {check.signals.map((signal) => (
            <li key={`${signal.code}-${signal.raisedAt}`}>
              {signalText(signal.code)}{" "}
              <time className="text-xs opacity-80" dateTime={signal.raisedAt} title={formatDateTime(signal.raisedAt)}>
                {t.issues.critical.raisedAt(timeAgo(signal.raisedAt, now))}
              </time>
            </li>
          ))}
        </ul>
        {suggested && suggested.urgency !== "critical" ? (
          <p>{t.issues.critical.disagrees(t.issues.urgency[suggested.urgency])}</p>
        ) : null}
        {!issue.triage ? <p>{t.issues.critical.review}</p> : null}
        {pending ? <p>{t.issues.critical.pendingBody}</p> : null}
        {/* US-14-03: which rule set raised it, as the suggestion panel names its rules version. */}
        {check.rulesVersion ? <p className="text-xs">{t.issues.suggestion.rules(check.rulesVersion)}</p> : null}
      </AlertDescription>
    </Alert>
  );
}

/** The list's short form of the critical check: icon and words, never colour alone. Nothing when there is nothing. */
export function CriticalBadge({ check }: { check: CriticalCheck | null | undefined }) {
  if (!check) return null;
  if (check.signals.length > 0) {
    return (
      <Badge variant="destructive">
        <Siren aria-hidden />
        {t.issues.critical.badge}
      </Badge>
    );
  }
  if (check.status !== "pending") return null;
  return (
    <Badge variant="outline">
      <ShieldQuestion aria-hidden />
      {t.issues.critical.pendingBadge}
    </Badge>
  );
}
