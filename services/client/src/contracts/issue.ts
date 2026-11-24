import { z } from "zod";

/*
 * Issue service contracts, hand-written until the service publishes its OpenAPI document (ADR-002). Paths are
 * relative to the edge prefix /api/issue (technical design §4.1, §4.2). Identifiers are opaque UUIDv7 strings;
 * times are RFC 3339 in UTC.
 */

export const BuildingRoleSchema = z.enum(["resident", "administrator"]);
export type BuildingRole = z.infer<typeof BuildingRoleSchema>;

export const PlatformRoleSchema = z.enum(["platform_staff"]);
export type PlatformRole = z.infer<typeof PlatformRoleSchema>;

/**
 * `GET /me` exactly as the Issue service sends it (`ProfileResponse` in services/issue, US-01-01): the caller's own
 * profile and active memberships. Roles are per building and resolved server-side; no role travels in a token
 * (ADR-005). The auth adapter maps this wire shape to the client's `Session` model (`@/ports/auth-session`).
 */
export const ProfileSchema = z.object({
  userId: z.string(),
  displayName: z.string(),
  memberships: z.array(
    z.object({
      membershipId: z.string(),
      buildingId: z.string(),
      buildingName: z.string(),
      role: BuildingRoleSchema,
    }),
  ),
});
export type Profile = z.infer<typeof ProfileSchema>;

/** Issue states from technical design §3.1 (`IssueState` in services/issue). */
export const IssueStateSchema = z.enum([
  "received",
  "in_triage",
  "triaged",
  "in_coordination",
  "scheduled",
  "in_progress",
  "awaiting_validation",
  "resolved",
  "cancelled",
]);
export type IssueState = z.infer<typeof IssueStateSchema>;

/** Urgency levels from contract examples §1.2 (`POST /issues/{id}/triage`). */
export const IssueUrgencySchema = z.enum(["low", "normal", "high", "critical"]);
export type IssueUrgency = z.infer<typeof IssueUrgencySchema>;

/** One row of `GET /issues`. ASSUMPTION: field names to align with the Issue service's list projection. */
export const IssueSummarySchema = z.object({
  id: z.string(),
  buildingId: z.string(),
  title: z.string(),
  state: IssueStateSchema,
  category: z.string().nullable(),
  urgency: IssueUrgencySchema.nullable(),
  createdAt: z.iso.datetime({ offset: true }),
  version: z.number().int(),
});
export type IssueSummary = z.infer<typeof IssueSummarySchema>;

export const IssueListSchema = z.array(IssueSummarySchema);

/*
 * Reports (US-01-01, implemented: services/issue README "API"). Paths under /api/issue.
 */

export const AreaKindSchema = z.enum(["common_area", "unit"]);
export type AreaKind = z.infer<typeof AreaKindSchema>;

/** One row of `GET /buildings/{id}/areas`: where in the building a fault can be. */
export const AreaSchema = z.object({ areaId: z.string(), label: z.string(), kind: AreaKindSchema });
export type Area = z.infer<typeof AreaSchema>;
export const AreaListSchema = z.array(AreaSchema);

export const REPORT_TEXT_MIN = 10;
export const REPORT_TEXT_MAX = 4_000;

/** Counted as Unicode code points, like the service (`ReportText`), so an emoji counts as one character. */
export const codePointLength = (text: string) => [...text].length;

/**
 * Control characters other than tab and line breaks, and lone UTF-16 surrogates: the service rejects both. Spreading a
 * string yields whole code points, so an emoji arrives as one pair-free character and only an unpaired half is caught.
 */
const isForbiddenCodePoint = (codePoint: number) =>
  (codePoint <= 0x1f && codePoint !== 0x09 && codePoint !== 0x0a && codePoint !== 0x0d) ||
  (codePoint >= 0x7f && codePoint <= 0x9f) ||
  (codePoint >= 0xd800 && codePoint <= 0xdfff);
const hasForbiddenCharacter = (text: string) => [...text].some((char) => isForbiddenCodePoint(char.codePointAt(0)!));

/**
 * Validation failure reasons, shared by this schema and the service's `400 VALIDATION_FAILED` `errors[].reason`, so
 * the form explains both the same way. Messages are reason codes, never user-facing text.
 */
export const ReportFieldReasonSchema = z.enum([
  "required",
  "too_short",
  "too_long",
  "invalid_characters",
  "not_in_building",
]);
export type ReportFieldReason = z.infer<typeof ReportFieldReasonSchema>;

/** Body of `POST /reports`. `text` is trimmed before it is checked and sent, as the service does. */
export const SubmitReportSchema = z.object({
  buildingId: z.string().min(1, "required"),
  areaId: z.string().min(1, "required"),
  text: z
    .string()
    .trim()
    .superRefine((text, ctx) => {
      const length = codePointLength(text);
      const reason: ReportFieldReason | null =
        length === 0
          ? "required"
          : length < REPORT_TEXT_MIN
            ? "too_short"
            : length > REPORT_TEXT_MAX
              ? "too_long"
              : hasForbiddenCharacter(text)
                ? "invalid_characters"
                : null;
      if (reason) ctx.addIssue({ code: "custom", message: reason });
    }),
});
export type SubmitReport = z.infer<typeof SubmitReportSchema>;

/** `201` of `POST /reports`: the receipt. Only a 201 ever produces one (never a 503). */
export const ReportReceiptSchema = z.object({
  reportId: z.string(),
  issueId: z.string(),
  state: IssueStateSchema,
  version: z.number().int(),
  submittedAt: z.iso.datetime({ offset: true }),
});
export type ReportReceipt = z.infer<typeof ReportReceiptSchema>;

export const ReportSourceSchema = z.enum(["resident", "administrator_on_behalf"]);
export type ReportSource = z.infer<typeof ReportSourceSchema>;

const ReportFields = {
  reportId: z.string(),
  issueId: z.string(),
  buildingId: z.string(),
  areaId: z.string(),
  areaLabel: z.string(),
  source: ReportSourceSchema,
  authoredByMe: z.boolean(),
  submittedAt: z.iso.datetime({ offset: true }),
  issue: z.object({ state: IssueStateSchema, version: z.number().int() }),
};

/** `GET /reports/{id}`: the author or an administrator of the building. Never names the author. */
export const ReportSchema = z.object({ ...ReportFields, text: z.string() });
export type Report = z.infer<typeof ReportSchema>;

/** One row of `GET /reports`: no text. */
export const ReportSummarySchema = z.object(ReportFields);
export type ReportSummary = z.infer<typeof ReportSummarySchema>;

/** `GET /reports?buildingId=&page=&size=`: own reports plus every report of buildings the caller administers. */
export const ReportPageSchema = z.object({
  items: z.array(ReportSummarySchema),
  page: z.number().int(),
  size: z.number().int(),
  hasMore: z.boolean(),
});
export type ReportPage = z.infer<typeof ReportPageSchema>;

/** One entry of a `400 VALIDATION_FAILED` problem's `errors`. Unknown reasons are kept, so the UI can still say so. */
export const FieldViolationSchema = z.object({ field: z.string(), reason: z.string() });
export const FieldViolationsSchema = z.array(FieldViolationSchema);
