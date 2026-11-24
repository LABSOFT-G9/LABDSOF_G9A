import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CircleCheck, Info, RefreshCw, Sparkles, TriangleAlert } from "lucide-react";
import { type FormEvent, useId, useRef, useState } from "react";
import { useServices } from "@/app/context";
import {
  codePointLength,
  type Issue,
  IssueCategorySchema,
  IssueUrgencySchema,
  TRIAGE_REASON_MAX,
  type TriageDraft,
  TriageRequestSchema,
  FieldViolationsSchema,
} from "@/contracts/issue";
import { issueKeys, issueQuery, triageMutation } from "@/features/issues/api";
import { ErrorState } from "@/shared/components/error-state";
import { SubmitButton } from "@/shared/components/submit-button";
import { t } from "@/shared/i18n";
import { isApiError } from "@/shared/lib/errors";
import { randomId } from "@/shared/lib/id";
import { Alert, AlertDescription, AlertTitle } from "@/shared/ui/alert";
import { Button } from "@/shared/ui/button";
import { Label } from "@/shared/ui/label";
import { NativeSelect } from "@/shared/ui/native-select";
import { Textarea } from "@/shared/ui/textarea";

const FIELDS = ["category", "urgency", "reason"] as const;
type Field = (typeof FIELDS)[number];
type FieldErrors = Partial<Record<Field, string>>;

function message(field: Field, reason: string): string {
  const messages: Record<string, string> = t.issues.errors[field];
  return messages[reason] ?? t.issues.errors.unknown;
}

function serverFieldErrors(error: unknown): FieldErrors | null {
  if (!isApiError(error, 400)) return null;
  const violations = FieldViolationsSchema.safeParse(error.extensions["errors"]);
  if (!violations.success) return null;
  const errors: FieldErrors = {};
  for (const { field, reason } of violations.data) {
    if ((FIELDS as readonly string[]).includes(field)) errors[field as Field] ??= message(field as Field, reason);
  }
  return Object.keys(errors).length > 0 ? errors : null;
}

const fromIssue = (issue: Issue): TriageDraft => ({
  category: issue.triage?.category ?? "",
  urgency: issue.triage?.urgency ?? "",
  reason: "",
});

/**
 * US-02: the administrator's triage decision. The decision is sent with the version the form was opened on
 * (`If-Match`), not whatever a background refetch brought in since: if someone else changed the issue first, the
 * service refuses it (`409`) and nothing is overwritten. The form then says so, keeps the administrator's choices, and
 * offers to reload the issue. A suggestion can fill the form, but only saving makes it a decision.
 */
export function TriageForm({ issue }: { issue: Issue }) {
  const { issues } = useServices();
  const queryClient = useQueryClient();
  const mutation = useMutation(triageMutation(issues, queryClient, issue.issueId));
  const id = useId();
  const [draft, setDraft] = useState<TriageDraft>(() => fromIssue(issue));
  const [errors, setErrors] = useState<FieldErrors>({});
  const [saved, setSaved] = useState(false);
  /**
   * The version the administrator's choices are based on. Moves only after a save (to the version it produced) or an
   * explicit reload, never on a background refetch, so a colleague's decision is never replaced unseen. It also keeps a
   * retry identical to the first attempt (same key, same `If-Match`), which the service replays instead of refusing.
   */
  const [basis, setBasis] = useState(issue.version);
  const idempotencyKey = useRef<string | null>(null);

  const fieldId = (field: Field) => `${id}-${field}`;
  const focusFirst = (next: FieldErrors) => {
    const first = FIELDS.find((field) => next[field]);
    if (first) document.getElementById(fieldId(first))?.focus();
  };

  function change(patch: Partial<TriageDraft>) {
    // The fields are disabled while the decision is being saved; its answer must not be thrown away.
    if (mutation.isPending) return;
    idempotencyKey.current = null;
    setSaved(false);
    if (!isApiError(mutation.error, 409)) mutation.reset();
    setDraft((current) => ({ ...current, ...patch }));
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const parsed = TriageRequestSchema.safeParse(draft);
    if (!parsed.success) {
      const next: FieldErrors = {};
      for (const problem of parsed.error.issues) {
        const field = problem.path[0] as Field;
        next[field] ??= message(field, problem.message);
      }
      setErrors(next);
      focusFirst(next);
      return;
    }
    setErrors({});
    idempotencyKey.current ??= randomId();
    mutation.mutate(
      { version: basis, decision: parsed.data, idempotencyKey: idempotencyKey.current },
      {
        onSuccess: (result) => {
          idempotencyKey.current = null;
          setBasis(result.version);
          setSaved(true);
          setDraft((current) => ({ ...current, reason: "" }));
        },
        onError: (error) => {
          const fields = serverFieldErrors(error);
          if (fields) {
            setErrors(fields);
            focusFirst(fields);
          }
        },
      },
    );
  }

  async function reload() {
    idempotencyKey.current = null;
    mutation.reset();
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: issueKeys.detail(issue.issueId) }),
      queryClient.invalidateQueries({ queryKey: issueKeys.history(issue.issueId) }),
      queryClient.invalidateQueries({ queryKey: issueKeys.list() }),
    ]);
    // The administrator has now seen the latest version: decide on it from here.
    const latest = queryClient.getQueryData(issueQuery(issues, issue.issueId).queryKey);
    if (latest) setBasis(latest.version);
  }

  const failure = mutation.isError && !serverFieldErrors(mutation.error) ? mutation.error : null;
  const conflict = isApiError(failure, 409) ? failure : null;
  // A background refetch brought a newer version than the one the form is based on.
  const changedMeanwhile = issue.version > basis && !conflict && !mutation.isPending;
  const describedBy = (field: Field, ...extra: string[]) =>
    [...extra, errors[field] ? `${fieldId(field)}-error` : null].filter(Boolean).join(" ") || undefined;
  const fieldError = (field: Field) =>
    errors[field] ? (
      <p id={`${fieldId(field)}-error`} className="text-destructive text-sm">
        {errors[field]}
      </p>
    ) : null;
  const reasonLength = codePointLength(draft.reason.trim());

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-5" aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className="text-base font-semibold">
        {issue.triage ? t.issues.triageEditTitle : t.issues.triageTitle}
      </h2>

      {Object.keys(errors).length > 0 ? (
        <p role="alert" className="text-destructive text-sm font-medium">
          {t.issues.errors.summary}
        </p>
      ) : null}

      {/* Locked while the decision is being saved, so the answer always belongs to what is on screen. */}
      <fieldset disabled={mutation.isPending} className="space-y-5">
        {issue.suggestion && !issue.triage ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => change({ category: issue.suggestion!.category, urgency: issue.suggestion!.urgency })}
          >
            <Sparkles aria-hidden />
            {t.issues.useSuggestion}
          </Button>
        ) : null}

        <div className="grid gap-5 sm:grid-cols-2">
          <div className="space-y-2">
            <Label htmlFor={fieldId("category")}>{t.issues.category}</Label>
            <NativeSelect
              id={fieldId("category")}
              value={draft.category}
              onChange={(event) => change({ category: event.target.value as TriageDraft["category"] })}
              aria-invalid={errors.category ? true : undefined}
              aria-describedby={describedBy("category")}
            >
              <option value="">{t.issues.chooseCategory}</option>
              {IssueCategorySchema.options.map((category) => (
                <option key={category} value={category}>
                  {t.issues.categories[category]}
                </option>
              ))}
            </NativeSelect>
            {fieldError("category")}
          </div>
          <div className="space-y-2">
            <Label htmlFor={fieldId("urgency")}>{t.issues.urgencyLabel}</Label>
            <NativeSelect
              id={fieldId("urgency")}
              value={draft.urgency}
              onChange={(event) => change({ urgency: event.target.value as TriageDraft["urgency"] })}
              aria-invalid={errors.urgency ? true : undefined}
              aria-describedby={describedBy("urgency")}
            >
              <option value="">{t.issues.chooseUrgency}</option>
              {IssueUrgencySchema.options.map((urgency) => (
                <option key={urgency} value={urgency}>
                  {t.issues.urgency[urgency]}
                </option>
              ))}
            </NativeSelect>
            {fieldError("urgency")}
          </div>
        </div>

        <div className="space-y-2">
          <Label htmlFor={fieldId("reason")}>{t.issues.reasonLabel}</Label>
          <Textarea
            id={fieldId("reason")}
            value={draft.reason}
            onChange={(event) => change({ reason: event.target.value })}
            aria-invalid={errors.reason ? true : undefined}
            aria-describedby={describedBy("reason", `${fieldId("reason")}-hint`, `${fieldId("reason")}-count`)}
          />
          <p id={`${fieldId("reason")}-hint`} className="text-muted-foreground text-sm">
            {t.issues.reasonHint}
          </p>
          <p id={`${fieldId("reason")}-count`} className="text-muted-foreground text-sm">
            {t.issues.reasonCount(reasonLength, TRIAGE_REASON_MAX)}
          </p>
          {fieldError("reason")}
        </div>
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
          <AlertTitle>
            {conflict.code === "INVALID_TRANSITION" ? t.issues.invalidTransitionTitle : t.issues.conflictTitle}
          </AlertTitle>
          <AlertDescription>
            <p>
              {conflict.code === "INVALID_TRANSITION"
                ? t.issues.notTriageable(
                    t.issues.states[String(conflict.extensions["currentState"]) as keyof typeof t.issues.states] ??
                      String(conflict.extensions["currentState"]),
                  )
                : t.issues.conflictBody}
            </p>
            {conflict.correlationId ? (
              <p className="text-xs opacity-80">{t.common.reference(conflict.correlationId)}</p>
            ) : null}
            <Button type="button" variant="outline" size="sm" className="mt-2" onClick={() => void reload()}>
              <RefreshCw aria-hidden />
              {t.issues.reload}
            </Button>
          </AlertDescription>
        </Alert>
      ) : failure ? (
        <ErrorState error={failure} />
      ) : null}

      {saved ? (
        <p role="status" className="flex items-center gap-2 text-sm">
          <CircleCheck className="size-4" aria-hidden />
          {t.issues.saved}
        </p>
      ) : null}

      <SubmitButton pending={mutation.isPending} pendingLabel={t.issues.saving}>
        {t.issues.save}
      </SubmitButton>
    </form>
  );
}
