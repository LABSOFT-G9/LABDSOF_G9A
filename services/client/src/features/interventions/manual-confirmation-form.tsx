import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CircleCheck, RefreshCw, TriangleAlert } from "lucide-react";
import { type FormEvent, useId, useRef, useState } from "react";
import { useServices } from "@/app/context";
import {
  type Booking,
  type Intervention,
  MANUAL_REASON_MAX,
  type ManualConfirmation,
  type ManualSource,
  ManualSourceSchema,
} from "@/contracts/dispatch";
import { codePointLength, FieldViolationsSchema } from "@/contracts/issue";
import { interventionKeys, manualConfirmationMutation } from "@/features/interventions/api";
import { fromLocalInput, toLocalInput } from "@/features/interventions/window-input";
import { ErrorState } from "@/shared/components/error-state";
import { SubmitButton } from "@/shared/components/submit-button";
import { t } from "@/shared/i18n";
import { isApiError } from "@/shared/lib/errors";
import { randomId } from "@/shared/lib/id";
import { Alert, AlertDescription, AlertTitle } from "@/shared/ui/alert";
import { Button } from "@/shared/ui/button";
import { Input } from "@/shared/ui/input";
import { Label } from "@/shared/ui/label";
import { NativeSelect } from "@/shared/ui/native-select";
import { Textarea } from "@/shared/ui/textarea";

const FIELDS = ["start", "end", "source", "reason"] as const;
type Field = (typeof FIELDS)[number];
type FieldErrors = Partial<Record<Field, string>>;

interface Draft {
  readonly start: string;
  readonly end: string;
  readonly source: ManualSource | "";
  readonly reason: string;
}

/** Dispatch's field names (`window.start`, …) as the form's. */
const SERVER_FIELDS: Record<string, Field> = {
  "window.start": "start",
  "window.end": "end",
  source: "source",
  reason: "reason",
};

const message = (field: Field, reason: string) => t.booking.errors[field][reason] ?? t.booking.errors.unknown;

function serverFieldErrors(error: unknown): FieldErrors | null {
  if (!isApiError(error, 400)) return null;
  const violations = FieldViolationsSchema.safeParse(error.extensions["errors"]);
  if (!violations.success) return null;
  const errors: FieldErrors = {};
  for (const { field, reason } of violations.data) {
    const mine = SERVER_FIELDS[field];
    if (mine) errors[mine] ??= message(mine, reason);
  }
  return Object.keys(errors).length > 0 ? errors : null;
}

/** The same checks as Dispatch's `BookingCommands.validateManual`, so most mistakes are explained before sending. */
function validate(draft: Draft, now: Date): { errors: FieldErrors; confirmation?: ManualConfirmation } {
  const errors: FieldErrors = {};
  const start = fromLocalInput(draft.start);
  const end = fromLocalInput(draft.end);
  if (!draft.start) errors.start = message("start", "required");
  else if (!start) errors.start = message("start", "invalid_format");
  else if (Date.parse(start) <= now.getTime()) errors.start = message("start", "not_in_future");
  if (!draft.end) errors.end = message("end", "required");
  else if (!end) errors.end = message("end", "invalid_format");
  else if (start && Date.parse(end) <= Date.parse(start)) errors.end = message("end", "not_after_start");
  if (!draft.source) errors.source = message("source", "required");
  const reason = draft.reason.trim();
  if (!reason) errors.reason = message("reason", "required");
  else if (codePointLength(reason) > MANUAL_REASON_MAX) errors.reason = message("reason", "too_long");
  if (Object.keys(errors).length > 0 || !start || !end || !draft.source) return { errors };
  return { errors, confirmation: { window: { start, end }, source: draft.source, reason } };
}

/**
 * US-08: the administrator confirms the booking by hand, with the window, how it was agreed and why. It stops the
 * automatic attempts; Dispatch still looks up any request the calendar may have made, and reports it as a discrepancy
 * instead of booking twice. Sent with the version the form was opened on (`If-Match`): if the intervention changed
 * meanwhile (the calendar confirmed it, say), nothing is overwritten, and the form says so and offers to reload.
 */
export function ManualConfirmationForm({ intervention, booking }: { intervention: Intervention; booking: Booking }) {
  const { dispatch, clock } = useServices();
  const queryClient = useQueryClient();
  const mutation = useMutation(
    manualConfirmationMutation(dispatch, queryClient, intervention.issueId, intervention.interventionId),
  );
  const id = useId();
  // The agreed window, unless it has already started: then the administrator must give the one agreed by hand.
  const [draft, setDraft] = useState<Draft>(() => {
    const future = Date.parse(booking.requestedWindow.start) > clock.now().getTime();
    return {
      start: future ? toLocalInput(booking.requestedWindow.start) : "",
      end: future ? toLocalInput(booking.requestedWindow.end) : "",
      source: "",
      reason: "",
    };
  });
  const [errors, setErrors] = useState<FieldErrors>({});
  const [saved, setSaved] = useState(false);
  /** The intervention version the administrator's choices are based on; moves only on an explicit reload. */
  const [basis, setBasis] = useState(intervention.version);
  const idempotencyKey = useRef<string | null>(null);

  const fieldId = (field: Field) => `${id}-${field}`;
  const focusFirst = (next: FieldErrors) => {
    const first = FIELDS.find((field) => next[field]);
    if (first) document.getElementById(fieldId(first))?.focus();
  };

  function change(patch: Partial<Draft>) {
    if (mutation.isPending) return;
    idempotencyKey.current = null;
    setSaved(false);
    if (!isApiError(mutation.error, 409)) mutation.reset();
    setDraft((current) => ({ ...current, ...patch }));
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const { errors: next, confirmation } = validate(draft, clock.now());
    if (!confirmation) {
      setErrors(next);
      focusFirst(next);
      return;
    }
    setErrors({});
    idempotencyKey.current ??= randomId();
    mutation.mutate(
      { version: basis, confirmation, idempotencyKey: idempotencyKey.current },
      {
        onSuccess: () => {
          idempotencyKey.current = null;
          setSaved(true);
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
    await queryClient.invalidateQueries({ queryKey: interventionKeys.forIssue(intervention.issueId) });
    const latest = queryClient
      .getQueryData<{ items: Intervention[] }>(interventionKeys.forIssue(intervention.issueId))
      ?.items.find((i) => i.interventionId === intervention.interventionId);
    if (latest) setBasis(latest.version);
  }

  const failure = mutation.isError && !serverFieldErrors(mutation.error) ? mutation.error : null;
  const conflict = isApiError(failure, 409) ? failure : null;
  const currentState = conflict ? String(conflict.extensions["currentState"] ?? "") : "";
  const describedBy = (field: Field, ...extra: string[]) =>
    [...extra, errors[field] ? `${fieldId(field)}-error` : null].filter(Boolean).join(" ") || undefined;
  const fieldError = (field: Field) =>
    errors[field] ? (
      <p id={`${fieldId(field)}-error`} className="text-destructive text-sm">
        {errors[field]}
      </p>
    ) : null;

  if (saved) {
    return (
      <p role="status" className="flex items-center gap-2 text-sm">
        <CircleCheck className="size-4" aria-hidden />
        {t.booking.confirmedByHand}
      </p>
    );
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-5" aria-labelledby={`${id}-title`}>
      <div className="space-y-1">
        <h3 id={`${id}-title`} className="text-sm font-semibold">
          {t.booking.manualTitle}
        </h3>
        <p className="text-muted-foreground text-sm">{t.booking.manualIntro}</p>
      </div>

      {Object.keys(errors).length > 0 ? (
        <p role="alert" className="text-destructive text-sm font-medium">
          {t.booking.errors.summary}
        </p>
      ) : null}

      <fieldset disabled={mutation.isPending} className="space-y-5">
        <div className="grid gap-5 sm:grid-cols-2">
          {(["start", "end"] as const).map((field) => (
            <div key={field} className="space-y-2">
              <Label htmlFor={fieldId(field)}>{t.booking[field]}</Label>
              <Input
                id={fieldId(field)}
                type="datetime-local"
                value={draft[field]}
                onChange={(event) => change({ [field]: event.target.value })}
                aria-invalid={errors[field] ? true : undefined}
                aria-describedby={describedBy(field)}
              />
              {fieldError(field)}
            </div>
          ))}
        </div>

        <div className="space-y-2">
          <Label htmlFor={fieldId("source")}>{t.booking.sourceLabel}</Label>
          <NativeSelect
            id={fieldId("source")}
            value={draft.source}
            onChange={(event) => change({ source: event.target.value as Draft["source"] })}
            aria-invalid={errors.source ? true : undefined}
            aria-describedby={describedBy("source")}
          >
            <option value="">{t.booking.chooseSource}</option>
            {ManualSourceSchema.options.map((source) => (
              <option key={source} value={source}>
                {t.booking.manualSourceOptions[source]}
              </option>
            ))}
          </NativeSelect>
          {fieldError("source")}
        </div>

        <div className="space-y-2">
          <Label htmlFor={fieldId("reason")}>{t.booking.reasonLabel}</Label>
          <Textarea
            id={fieldId("reason")}
            value={draft.reason}
            onChange={(event) => change({ reason: event.target.value })}
            aria-invalid={errors.reason ? true : undefined}
            aria-describedby={describedBy("reason", `${fieldId("reason")}-hint`, `${fieldId("reason")}-count`)}
          />
          <p id={`${fieldId("reason")}-hint`} className="text-muted-foreground text-sm">
            {t.booking.reasonHint}
          </p>
          <p id={`${fieldId("reason")}-count`} className="text-muted-foreground text-sm">
            {t.booking.reasonCount(codePointLength(draft.reason.trim()), MANUAL_REASON_MAX)}
          </p>
          {fieldError("reason")}
        </div>
      </fieldset>

      {conflict ? (
        <Alert variant="destructive">
          <TriangleAlert aria-hidden />
          <AlertTitle>
            {conflict.code === "INVALID_TRANSITION" ? t.booking.notPendingTitle : t.booking.conflictTitle}
          </AlertTitle>
          <AlertDescription>
            <p>
              {conflict.code === "INVALID_TRANSITION"
                ? t.booking.notPending(
                    t.booking.interventionStates[currentState as keyof typeof t.booking.interventionStates] ??
                      currentState,
                  )
                : t.booking.conflictBody}
            </p>
            {conflict.correlationId ? (
              <p className="text-xs opacity-80">{t.common.reference(conflict.correlationId)}</p>
            ) : null}
            <Button type="button" variant="outline" size="sm" className="mt-2" onClick={() => void reload()}>
              <RefreshCw aria-hidden />
              {t.booking.reload}
            </Button>
          </AlertDescription>
        </Alert>
      ) : failure ? (
        <ErrorState error={failure} />
      ) : null}

      <SubmitButton pending={mutation.isPending} pendingLabel={t.booking.confirming}>
        {t.booking.confirm}
      </SubmitButton>
    </form>
  );
}
