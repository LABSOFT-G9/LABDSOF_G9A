import { FieldViolationsSchema } from "@/contracts/issue";
import { t } from "@/shared/i18n";
import { isApiError } from "@/shared/lib/errors";

export const REPORT_FIELDS = ["buildingId", "areaId", "text"] as const;
export type ReportField = (typeof REPORT_FIELDS)[number];
export type ReportFieldErrors = Partial<Record<ReportField, string>>;

const isReportField = (field: string): field is ReportField => (REPORT_FIELDS as readonly string[]).includes(field);

/** A reason code (from the form's schema or the service's `errors[]`) as a sentence for that field. */
export function fieldMessage(field: ReportField, reason: string): string {
  const messages: Record<string, string> = t.reports.errors[field];
  return messages[reason] ?? t.reports.errors.unknown;
}

/** The first problem per field, as sentences. */
export function toFieldErrors(violations: readonly { field: string; reason: string }[]): ReportFieldErrors {
  const errors: ReportFieldErrors = {};
  for (const { field, reason } of violations) {
    if (isReportField(field)) errors[field] ??= fieldMessage(field, reason);
  }
  return errors;
}

/**
 * Field problems the service reported: `400 VALIDATION_FAILED` or `INVALID_AREA`, both with `errors[]`. `null` when
 * the failure is not about fields (it is shown as a whole instead).
 */
export function serverFieldErrors(error: unknown): ReportFieldErrors | null {
  if (!isApiError(error, 400)) return null;
  const violations = FieldViolationsSchema.safeParse(error.extensions["errors"]);
  if (!violations.success) return null;
  const errors = toFieldErrors(violations.data);
  return Object.keys(errors).length > 0 ? errors : null;
}
