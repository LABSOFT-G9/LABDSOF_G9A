import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { CircleCheck, CloudOff, RotateCw } from "lucide-react";
import { type FormEvent, useEffect, useId, useRef, useState } from "react";
import { useServices } from "@/app/context";
import {
  codePointLength,
  REPORT_TEXT_MAX,
  type ReportReceipt,
  type SubmitReport,
  SubmitReportSchema,
} from "@/contracts/issue";
import { useSession } from "@/features/auth";
import { IssueStateBadge } from "@/features/issues";
import { areasQuery, submitReportMutation } from "@/features/reports/api";
import {
  fieldMessage,
  REPORT_FIELDS,
  type ReportField,
  type ReportFieldErrors,
  serverFieldErrors,
} from "@/features/reports/field-errors";
import { ErrorState } from "@/shared/components/error-state";
import { SubmitButton } from "@/shared/components/submit-button";
import { t } from "@/shared/i18n";
import { isApiError, isUnavailable, NetworkError } from "@/shared/lib/errors";
import { formatDateTime } from "@/shared/lib/format";
import { randomId } from "@/shared/lib/id";
import { cn } from "@/shared/lib/utils";
import { Alert, AlertDescription, AlertTitle } from "@/shared/ui/alert";
import { Button } from "@/shared/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/shared/ui/card";
import { Label } from "@/shared/ui/label";
import { Textarea } from "@/shared/ui/textarea";

/** The look of `Input`, for a native `<select>`: native keeps keyboard and screen-reader behaviour for free. */
const SELECT_CLASS =
  "h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs outline-none md:text-sm dark:bg-input/30 focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20";

/**
 * US-01: report a fault (building, area, text). Validated by the same schema the service applies, with each error tied
 * to its field. A receipt appears only after the service answered 201; a failure to save says so and keeps the text,
 * and "Try again" resends the same action with the same `Idempotency-Key`, so a retry never creates a second report.
 */
export function ReportForm() {
  const session = useSession();
  const { issues } = useServices();
  const queryClient = useQueryClient();
  const mutation = useMutation(submitReportMutation(issues, queryClient));
  const id = useId();

  const buildings = session.memberships;
  const [values, setValues] = useState<SubmitReport>(() => ({
    buildingId: buildings.length === 1 ? buildings[0]!.buildingId : "",
    areaId: "",
    text: "",
  }));
  const [errors, setErrors] = useState<ReportFieldErrors>({});
  const [receipt, setReceipt] = useState<ReportReceipt | null>(null);
  /** The current action's key: kept across retries, dropped as soon as the report changes (a new action). */
  const idempotencyKey = useRef<string | null>(null);
  const receiptHeading = useRef<HTMLHeadingElement>(null);

  const areas = useQuery({ ...areasQuery(issues, values.buildingId), enabled: values.buildingId !== "" });

  useEffect(() => {
    if (receipt) receiptHeading.current?.focus();
  }, [receipt]);

  const fieldId = (field: ReportField) => `${id}-${field}`;
  const focusFirst = (next: ReportFieldErrors) => {
    const first = REPORT_FIELDS.find((field) => next[field]);
    if (first) document.getElementById(fieldId(first))?.focus();
  };

  function change(patch: Partial<SubmitReport>) {
    // The fields are disabled while a request is in flight; its answer must not be thrown away (nor its key).
    if (mutation.isPending) return;
    idempotencyKey.current = null;
    mutation.reset();
    setValues((current) => ({ ...current, ...patch }));
  }

  function send(report: SubmitReport) {
    idempotencyKey.current ??= randomId();
    mutation.mutate(
      { report, idempotencyKey: idempotencyKey.current },
      {
        onSuccess: (answer) => {
          idempotencyKey.current = null;
          setReceipt(answer);
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

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const parsed = SubmitReportSchema.safeParse(values);
    if (!parsed.success) {
      const next: ReportFieldErrors = {};
      for (const issue of parsed.error.issues) {
        const field = issue.path[0] as ReportField;
        next[field] ??= fieldMessage(field, issue.message);
      }
      setErrors(next);
      focusFirst(next);
      return;
    }
    setErrors({});
    send(parsed.data);
  }

  function startOver() {
    setReceipt(null);
    setErrors({});
    mutation.reset();
    setValues((current) => ({ buildingId: current.buildingId, areaId: "", text: "" }));
  }

  if (receipt) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-lg">
            <CircleCheck className="size-5" aria-hidden />
            <h2 ref={receiptHeading} tabIndex={-1} className="outline-none">
              {t.reports.received}
            </h2>
          </CardTitle>
          <CardDescription>{t.reports.receivedBody}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <dl className="grid gap-3 text-sm sm:grid-cols-[auto_1fr] sm:gap-x-6">
            <dt className="text-muted-foreground">{t.reports.reportId}</dt>
            <dd className="font-mono break-all">{receipt.reportId}</dd>
            <dt className="text-muted-foreground">{t.reports.status}</dt>
            <dd>
              <IssueStateBadge state={receipt.state} />
            </dd>
            <dt className="text-muted-foreground">{t.reports.submittedAt}</dt>
            <dd>
              <time dateTime={receipt.submittedAt}>{formatDateTime(receipt.submittedAt)}</time>
            </dd>
          </dl>
          <div className="flex flex-wrap gap-3">
            <Button asChild>
              <Link to="/reports/$reportId" params={{ reportId: receipt.reportId }}>
                {t.reports.viewReport}
              </Link>
            </Button>
            <Button variant="outline" onClick={startOver}>
              {t.reports.reportAnother}
            </Button>
          </div>
        </CardContent>
      </Card>
    );
  }

  const describedBy = (field: ReportField, ...extra: string[]) =>
    [...extra, errors[field] ? `${fieldId(field)}-error` : null].filter(Boolean).join(" ") || undefined;
  const fieldError = (field: ReportField) =>
    errors[field] ? (
      <p id={`${fieldId(field)}-error`} className="text-destructive text-sm">
        {errors[field]}
      </p>
    ) : null;

  const failure = mutation.isError && !serverFieldErrors(mutation.error) ? mutation.error : null;
  // 503 DEPENDENCY_UNAVAILABLE, any 5xx, 429 or no answer at all: the report may be retried as it is.
  const notSaved = failure !== null && isUnavailable(failure);
  // No answer at all: the request may have been stored before the connection dropped, so never claim it was not.
  const noAnswer = failure instanceof NetworkError;
  const textLength = codePointLength(values.text.trim());

  return (
    <form onSubmit={onSubmit} noValidate className="max-w-xl space-y-5">
      {Object.keys(errors).length > 0 ? (
        <p role="alert" className="text-destructive text-sm font-medium">
          {t.reports.errors.summary}
        </p>
      ) : null}

      {/* Locked while the report is being sent, so the answer always belongs to what is on screen. */}
      <fieldset disabled={mutation.isPending} className="space-y-5">
        <div className="space-y-2">
          <Label htmlFor={fieldId("buildingId")}>{t.reports.building}</Label>
          <select
            id={fieldId("buildingId")}
            className={SELECT_CLASS}
            value={values.buildingId}
            onChange={(event) => change({ buildingId: event.target.value, areaId: "" })}
            aria-invalid={errors.buildingId ? true : undefined}
            aria-describedby={describedBy("buildingId")}
          >
            <option value="">{t.reports.chooseBuilding}</option>
            {buildings.map((m) => (
              <option key={m.buildingId} value={m.buildingId}>
                {m.buildingName}
              </option>
            ))}
          </select>
          {fieldError("buildingId")}
        </div>

        <div className="space-y-2">
          <Label htmlFor={fieldId("areaId")}>{t.reports.area}</Label>
          <select
            id={fieldId("areaId")}
            className={SELECT_CLASS}
            value={values.areaId}
            onChange={(event) => change({ areaId: event.target.value })}
            disabled={values.buildingId === "" || !areas.isSuccess}
            aria-invalid={errors.areaId ? true : undefined}
            aria-describedby={describedBy("areaId", `${fieldId("areaId")}-hint`)}
          >
            <option value="">{t.reports.chooseArea}</option>
            {areas.data?.map((area) => (
              <option key={area.areaId} value={area.areaId}>
                {area.label} ({t.reports.areaKinds[area.kind]})
              </option>
            ))}
          </select>
          <p id={`${fieldId("areaId")}-hint`} className="text-muted-foreground text-sm">
            {values.buildingId === "" ? t.reports.chooseBuildingFirst : areas.isPending ? t.reports.loadingAreas : null}
          </p>
          {areas.isError ? <ErrorState error={areas.error} onRetry={() => void areas.refetch()} /> : null}
          {fieldError("areaId")}
        </div>

        <div className="space-y-2">
          <Label htmlFor={fieldId("text")}>{t.reports.text}</Label>
          <Textarea
            id={fieldId("text")}
            className="min-h-32"
            value={values.text}
            onChange={(event) => change({ text: event.target.value })}
            aria-invalid={errors.text ? true : undefined}
            aria-describedby={describedBy("text", `${fieldId("text")}-hint`, `${fieldId("text")}-count`)}
          />
          <p id={`${fieldId("text")}-hint`} className="text-muted-foreground text-sm">
            {t.reports.textHint}
          </p>
          <p
            id={`${fieldId("text")}-count`}
            className={cn("text-sm", textLength > REPORT_TEXT_MAX ? "text-destructive" : "text-muted-foreground")}
          >
            {t.reports.textCount(textLength, REPORT_TEXT_MAX)}
          </p>
          {fieldError("text")}
        </div>
      </fieldset>

      {notSaved ? (
        <Alert role="alert" variant="destructive">
          <CloudOff aria-hidden />
          <AlertTitle>{noAnswer ? t.reports.notConfirmed : t.reports.notSaved}</AlertTitle>
          <AlertDescription>
            <p>{noAnswer ? t.reports.notConfirmedBody : t.reports.notSavedBody}</p>
            {isApiError(failure) && failure.correlationId ? (
              <p className="text-xs opacity-80">{t.common.reference(failure.correlationId)}</p>
            ) : null}
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="mt-2"
              disabled={mutation.isPending}
              onClick={() => send(mutation.variables!.report)}
            >
              <RotateCw aria-hidden />
              {t.common.retry}
            </Button>
          </AlertDescription>
        </Alert>
      ) : failure ? (
        <ErrorState error={failure} />
      ) : null}

      <SubmitButton pending={mutation.isPending} pendingLabel={t.reports.submitting}>
        {t.reports.submit}
      </SubmitButton>
    </form>
  );
}
