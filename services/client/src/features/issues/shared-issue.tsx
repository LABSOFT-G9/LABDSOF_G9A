import { useMutation, useQueryClient } from "@tanstack/react-query";
import { BellOff, BellRing, CircleCheck, Info, Megaphone, RefreshCw, TriangleAlert, Users } from "lucide-react";
import { type FormEvent, useId, useRef, useState } from "react";
import { useServices } from "@/app/context";
import {
  codePointLength,
  FieldViolationsSchema,
  type Issue,
  PublicationRequestSchema,
  SUMMARY_MAX,
} from "@/contracts/issue";
import { impactMutation, issueKeys, issueQuery, publishMutation, subscriptionMutation } from "@/features/issues/api";
import { ErrorState } from "@/shared/components/error-state";
import { SubmitButton } from "@/shared/components/submit-button";
import { t } from "@/shared/i18n";
import { isApiError } from "@/shared/lib/errors";
import { formatDateTime, timeAgo } from "@/shared/lib/format";
import { randomId } from "@/shared/lib/id";
import { Alert, AlertDescription, AlertTitle } from "@/shared/ui/alert";
import { Badge } from "@/shared/ui/badge";
import { Button } from "@/shared/ui/button";
import { Label } from "@/shared/ui/label";
import { Textarea } from "@/shared/ui/textarea";

const summaryMessage = (reason: string) => {
  const messages: Record<string, string> = t.issues.shared.errors.summary;
  return messages[reason] ?? t.issues.shared.errors.unknown;
};

/** A `400 VALIDATION_FAILED` about the summary, as a message for its field; `null` for any other failure. */
function serverSummaryError(error: unknown): string | null {
  if (!isApiError(error, 400)) return null;
  const violations = FieldViolationsSchema.safeParse(error.extensions["errors"]);
  const violation = violations.success ? violations.data.find((v) => v.field === "summary") : undefined;
  return violation ? summaryMessage(violation.reason) : null;
}

/** The list's mark for an issue shared with the building: icon and words. */
export function SharedBadge() {
  return (
    <Badge variant="secondary">
      <Users aria-hidden />
      {t.issues.shared.badge}
    </Badge>
  );
}

function ImpactCount({ issue }: { issue: Pick<Issue, "impact"> }) {
  if (!issue.impact) return null;
  return (
    <p className="flex items-center gap-2 text-sm">
      <Users className="size-4 shrink-0" aria-hidden />
      <span>
        {t.issues.shared.impactCount(issue.impact.count)}{" "}
        <span className="text-muted-foreground">{t.issues.shared.impactHint}</span>
      </span>
    </p>
  );
}

function PublishedAt({ at, now }: { at: string; now: Date }) {
  return (
    <p className="text-muted-foreground text-sm">
      <time dateTime={at} title={formatDateTime(at)}>
        {t.issues.shared.publishedAt(timeAgo(at, now))}
      </time>
    </p>
  );
}

/**
 * US-04-02, administrator: publish or change the reviewed summary the building's residents see instead of the report.
 * Like the triage form, it is sent with the version the administrator started from (`If-Match`): if the issue changed
 * since, the service refuses it (`409`), nothing is overwritten and the form offers to reload. The version is taken
 * when the administrator starts writing, not when the page opened, so a triage decision saved just before does not
 * turn the first publication into a conflict.
 */
export function PublishSummary({ issue }: { issue: Issue }) {
  const { issues, clock } = useServices();
  const queryClient = useQueryClient();
  const mutation = useMutation(publishMutation(issues, queryClient, issue.issueId));
  const id = useId();
  const [editing, setEditing] = useState(!issue.summary);
  const [text, setText] = useState(issue.summary?.text ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [basis, setBasis] = useState<number | null>(null);
  const idempotencyKey = useRef<string | null>(null);
  const fieldId = `${id}-summary`;

  function change(value: string) {
    if (mutation.isPending) return;
    setBasis((current) => current ?? issue.version);
    idempotencyKey.current = null;
    setSaved(false);
    if (!isApiError(mutation.error, 409)) mutation.reset();
    setText(value);
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const parsed = PublicationRequestSchema.safeParse({ summary: text });
    if (!parsed.success) {
      setError(summaryMessage(parsed.error.issues[0]!.message));
      document.getElementById(fieldId)?.focus();
      return;
    }
    setError(null);
    const version = basis ?? issue.version;
    setBasis(version);
    idempotencyKey.current ??= randomId();
    mutation.mutate(
      { version, publication: parsed.data, idempotencyKey: idempotencyKey.current },
      {
        onSuccess: (result) => {
          idempotencyKey.current = null;
          setBasis(null);
          setSaved(true);
          setEditing(false);
          setText(result.summary.text);
        },
        onError: (failure) => {
          const message = serverSummaryError(failure);
          if (message) {
            setError(message);
            document.getElementById(fieldId)?.focus();
          }
        },
      },
    );
  }

  async function reload() {
    idempotencyKey.current = null;
    mutation.reset();
    await queryClient.invalidateQueries({ queryKey: issueKeys.all });
    const latest = queryClient.getQueryData(issueQuery(issues, issue.issueId).queryKey);
    setBasis(latest?.version ?? null);
  }

  const failure = mutation.isError && !serverSummaryError(mutation.error) ? mutation.error : null;
  const conflict = isApiError(failure, 409) ? failure : null;
  const changedMeanwhile = basis !== null && issue.version > basis && !conflict && !mutation.isPending;
  const describedBy = [`${fieldId}-hint`, `${fieldId}-count`, error ? `${fieldId}-error` : null]
    .filter(Boolean)
    .join(" ");

  return (
    <section aria-labelledby={`${id}-title`} className="space-y-4">
      <h2 id={`${id}-title`} className="text-base font-semibold">
        {t.issues.shared.adminTitle}
      </h2>
      <p className="text-muted-foreground text-sm">{t.issues.shared.adminHint}</p>

      {saved ? (
        <p role="status" className="flex items-center gap-2 text-sm">
          <CircleCheck className="size-4" aria-hidden />
          {t.issues.shared.published}
        </p>
      ) : null}

      {issue.summary && !editing ? (
        <div className="space-y-3">
          <blockquote className="border-l-2 pl-3 text-sm break-words whitespace-pre-wrap">
            {issue.summary.text}
          </blockquote>
          <PublishedAt at={issue.summary.publishedAt} now={clock.now()} />
          <ImpactCount issue={issue} />
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              setSaved(false);
              setEditing(true);
            }}
          >
            {t.issues.shared.change}
          </Button>
        </div>
      ) : (
        <form onSubmit={onSubmit} noValidate className="space-y-4">
          {!issue.summary ? <p className="text-sm">{t.issues.shared.notShared}</p> : null}
          {error ? (
            <p role="alert" className="text-destructive text-sm font-medium">
              {t.issues.shared.errors.summary_title}
            </p>
          ) : null}
          <fieldset disabled={mutation.isPending} className="space-y-2">
            <Label htmlFor={fieldId}>{t.issues.shared.summaryLabel}</Label>
            <Textarea
              id={fieldId}
              value={text}
              onChange={(event) => change(event.target.value)}
              aria-invalid={error ? true : undefined}
              aria-describedby={describedBy}
            />
            <p id={`${fieldId}-hint`} className="text-muted-foreground text-sm">
              {t.issues.shared.summaryHint}
            </p>
            <p id={`${fieldId}-count`} className="text-muted-foreground text-sm">
              {t.issues.shared.summaryCount(codePointLength(text.trim()), SUMMARY_MAX)}
            </p>
            {error ? (
              <p id={`${fieldId}-error`} className="text-destructive text-sm">
                {error}
              </p>
            ) : null}
          </fieldset>

          {changedMeanwhile ? (
            <Alert>
              <Info aria-hidden />
              <AlertTitle>{t.issues.changedMeanwhileTitle}</AlertTitle>
              <AlertDescription>
                <p>{t.issues.changedMeanwhileBody}</p>
                <Button type="button" variant="outline" size="sm" className="mt-2" onClick={() => void reload()}>
                  <RefreshCw aria-hidden />
                  {t.issues.reload}
                </Button>
              </AlertDescription>
            </Alert>
          ) : null}

          {conflict ? (
            <Alert role="alert" variant="destructive">
              <TriangleAlert aria-hidden />
              <AlertTitle>{t.issues.conflictTitle}</AlertTitle>
              <AlertDescription>
                <p>{t.issues.conflictBody}</p>
                <Button type="button" variant="outline" size="sm" className="mt-2" onClick={() => void reload()}>
                  <RefreshCw aria-hidden />
                  {t.issues.reload}
                </Button>
              </AlertDescription>
            </Alert>
          ) : failure ? (
            <ErrorState error={failure} />
          ) : null}

          <div className="flex flex-wrap gap-2">
            <SubmitButton pending={mutation.isPending} pendingLabel={t.issues.shared.publishing}>
              <Megaphone aria-hidden />
              {issue.summary ? t.issues.shared.update : t.issues.shared.publish}
            </SubmitButton>
            {issue.summary ? (
              <Button
                type="button"
                variant="ghost"
                disabled={mutation.isPending}
                onClick={() => {
                  mutation.reset();
                  setError(null);
                  setBasis(null);
                  setText(issue.summary?.text ?? "");
                  setEditing(false);
                }}
              >
                {t.issues.shared.cancel}
              </Button>
            ) : null}
          </div>
        </form>
      )}
    </section>
  );
}

/**
 * US-04-02, residents of the building: the shared summary, how many residents it affects, and two separate, optional
 * choices: "this affects me too" and notifications. Withdrawing or unsubscribing never removes access to the issue's
 * status; the messages say so. Each choice is the caller's own; the service answers the new state, which is shown.
 */
export function SharedIssuePanel({ issue, canConfirm }: { issue: Issue; canConfirm: boolean }) {
  const { issues, clock } = useServices();
  const queryClient = useQueryClient();
  const impact = useMutation(impactMutation(issues, queryClient, issue.issueId));
  const subscription = useMutation(subscriptionMutation(issues, queryClient, issue.issueId));
  const id = useId();
  if (!issue.summary) return null;

  const mine = issue.impact?.mine ?? false;
  // While a change is on its way, show the choice being made; the service's answer replaces it.
  const subscribed = subscription.isPending ? subscription.variables : (issue.subscribed ?? false);

  return (
    <section aria-labelledby={`${id}-title`} className="space-y-4">
      <h2 id={`${id}-title`} className="text-base font-semibold">
        {t.issues.shared.title}
      </h2>
      <blockquote className="border-l-2 pl-3 text-sm break-words whitespace-pre-wrap">{issue.summary.text}</blockquote>
      <PublishedAt at={issue.summary.publishedAt} now={clock.now()} />
      <ImpactCount issue={issue} />

      {canConfirm ? (
        <div className="space-y-2">
          {mine ? (
            <div className="flex flex-wrap items-center gap-3">
              <p className="flex items-center gap-2 text-sm font-medium">
                <CircleCheck className="size-4 shrink-0" aria-hidden />
                {t.issues.shared.youConfirmed}
              </p>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={impact.isPending}
                onClick={() => impact.mutate(false)}
              >
                {t.issues.shared.withdraw}
              </Button>
            </div>
          ) : (
            <>
              <Button
                type="button"
                variant="outline"
                disabled={impact.isPending}
                aria-describedby={`${id}-affects-hint`}
                onClick={() => impact.mutate(true)}
              >
                <Users aria-hidden />
                {t.issues.shared.affectsMe}
              </Button>
              <p id={`${id}-affects-hint`} className="text-muted-foreground text-sm">
                {t.issues.shared.affectsMeHint}
              </p>
            </>
          )}
          <p role="status" className="text-sm">
            {impact.isSuccess ? (impact.data.mine ? t.issues.shared.confirmed : t.issues.shared.withdrawn) : null}
          </p>
          {impact.isError ? <ErrorState error={impact.error} /> : null}
        </div>
      ) : null}

      <div className="space-y-2">
        <div className="flex items-start gap-3">
          <input
            id={`${id}-subscribe`}
            type="checkbox"
            className="accent-primary mt-0.5 size-4 shrink-0"
            checked={subscribed}
            disabled={subscription.isPending}
            aria-describedby={`${id}-subscribe-hint`}
            onChange={(event) => subscription.mutate(event.target.checked)}
          />
          <div className="space-y-1">
            <Label htmlFor={`${id}-subscribe`}>{t.issues.shared.subscribe}</Label>
            <p id={`${id}-subscribe-hint`} className="text-muted-foreground text-sm">
              {t.issues.shared.subscribeHint}
            </p>
          </div>
        </div>
        <p role="status" className="flex items-center gap-2 text-sm">
          {subscription.isSuccess ? (
            <>
              {subscription.data.subscribed ? (
                <BellRing className="size-4 shrink-0" aria-hidden />
              ) : (
                <BellOff className="size-4 shrink-0" aria-hidden />
              )}
              {subscription.data.subscribed ? t.issues.shared.subscribed : t.issues.shared.unsubscribed}
            </>
          ) : null}
        </p>
        {subscription.isError ? <ErrorState error={subscription.error} /> : null}
      </div>
    </section>
  );
}
