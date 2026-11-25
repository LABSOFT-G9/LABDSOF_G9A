import { z } from "zod";

/*
 * Issue service contracts, hand-written until the service publishes its OpenAPI document (ADR-002). Paths are
 * relative to the edge prefix /api/issue (technical design §4.1, §4.2). Identifiers are opaque UUIDv7 strings;
 * times are RFC 3339 in UTC.
 */

/** Counted as Unicode code points, like the service (`ReportText`), so an emoji counts as one character. */
export const codePointLength = (text: string) => [...text].length;

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
  /** `null` when neither the profile nor the identity provider account has a name. */
  displayName: z.string().nullable(),
  /** From the token (EN-02-04, #139); Issue does not store it. Optional until that change is on `main`. */
  email: z.string().nullable().optional(),
  /** From the token's realm roles (EN-02-04, #139). Display only: Dispatch checks the role on every staff call. */
  platformRoles: z.array(z.string()).default([]),
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

/**
 * Control characters other than tab and line breaks, and lone UTF-16 surrogates: the service rejects both. Spreading a
 * string yields whole code points, so an emoji arrives as one pair-free character and only an unpaired half is caught.
 */
const isForbiddenCodePoint = (codePoint: number) =>
  (codePoint <= 0x1f && codePoint !== 0x09 && codePoint !== 0x0a && codePoint !== 0x0d) ||
  (codePoint >= 0x7f && codePoint <= 0x9f) ||
  (codePoint >= 0xd800 && codePoint <= 0xdfff);
const hasForbiddenCharacter = (text: string) => [...text].some((char) => isForbiddenCodePoint(char.codePointAt(0)!));

/*
 * Issues, triage and history (US-02). The operations are in technical design §4.2 and contract examples §1.2; the
 * shapes match the Issue service's pull request #140 (US-02-01), as answered on #87. Check them again if #140 changes
 * before it merges. Only what #140 does not define yet is marked ASSUMPTION.
 */

/** Categories from the US-02 story and contract examples §1.2. */
export const IssueCategorySchema = z.enum(["plumbing", "electrical", "lift", "cleaning", "structural", "other"]);
export type IssueCategory = z.infer<typeof IssueCategorySchema>;

/**
 * The administrator's decision. Only a human decision is a decision; a suggestion is never shown as one (US-02).
 * `reason` is internal: `null` for residents, and in `GET /issues` for everyone.
 */
export const TriageDecisionSchema = z.object({
  category: IssueCategorySchema,
  urgency: IssueUrgencySchema,
  reason: z.string().nullable(),
  decidedAt: z.iso.datetime({ offset: true }),
  decisionSource: z.literal("human"),
});
export type TriageDecision = z.infer<typeof TriageDecisionSchema>;

/** Where a suggestion came from (`TriageSuggested.origin`, technical design §4.4). */
export const SuggestionOriginSchema = z.enum(["ai", "rules", "fallback"]);
export type SuggestionOrigin = z.infer<typeof SuggestionOriginSchema>;

/**
 * Why the AI result was not used, on a `fallback` suggestion (`TriageSuggested.failureCode`, technical design §4.4).
 * ASSUMPTION: the values are not agreed yet (US-03-03 #108, US-14-01 #95). Any other code is still shown, as a
 * generic "AI could not be used", so a new code never breaks the page.
 */
export const KNOWN_FAILURE_CODES = ["timeout", "provider_error", "invalid_output", "quota_exceeded"] as const;
export type KnownFailureCode = (typeof KNOWN_FAILURE_CODES)[number];

/**
 * An unconfirmed suggestion, administrators only, and `null` once a decision exists. `category`, `urgency` and `origin`
 * are in the Issue service (#140); the rest is agreed on #108 for US-03-03, named after `TriageSuggested` (technical
 * design §4.4), and optional until that ships:
 * - `rationale`: the short explanation (1-240 characters). Model output: shown as plain text, never as markup. `null`
 *   in `GET /issues`, because it is written from the private report text; read it on the issue itself.
 * - `needsReview`: the suggester itself asks for a careful look.
 * - `failureCode`: why the AI result was not used, on `fallback` only.
 * - `suggestedAt`: when the suggestion was recorded.
 * - `rulesVersion` (`rules`, `fallback`) and `modelVersion` (`ai`): what produced it, so the administrator can tell
 *   a rules result from a model's (US-14-03 #97). Null when that part did not run (technical design §4.4).
 * There is deliberately no confidence value: a model's confidence must not be shown as a reliable probability (US-03).
 */
export const TriageSuggestionSchema = z.object({
  category: IssueCategorySchema,
  urgency: IssueUrgencySchema,
  origin: SuggestionOriginSchema,
  rationale: z.string().nullish(),
  needsReview: z.boolean().optional(),
  failureCode: z.string().nullish(),
  suggestedAt: z.iso.datetime({ offset: true }).nullish(),
  rulesVersion: z.string().nullish(),
  modelVersion: z.string().nullish(),
});
export type TriageSuggestion = z.infer<typeof TriageSuggestionSchema>;

/**
 * Rule codes of the critical check that the client words. ASSUMPTION: the codes come with the `critical-alerts-v1`
 * rule set, which is not agreed yet (#96). Any other code is still shown, in general words.
 */
export const KNOWN_SIGNAL_CODES = ["smoke_or_fire", "gas_smell", "exposed_wiring", "water_near_electrics"] as const;
export type KnownSignalCode = (typeof KNOWN_SIGNAL_CODES)[number];

/** One rule of the critical check that matched a report. A fact about the report: it never clears. */
export const CriticalSignalSchema = z.object({
  code: z.string(),
  raisedAt: z.iso.datetime({ offset: true }),
});
export type CriticalSignal = z.infer<typeof CriticalSignalSchema>;

/**
 * Issue's own critical check (US-14-02, shape agreed on #96; technical design §2.1 `criticalSignals`,
 * `criticalRulesVersion`, `criticalCheckStatus`). Administrators only, `null` for residents; absent before US-14-02.
 * Independent of the AI: no suggestion, retry or decision removes a signal. `pending` with no signals means "not
 * checked", never "nothing found".
 */
export const CriticalCheckSchema = z.object({
  status: z.enum(["pending", "completed"]),
  rulesVersion: z.string().nullish(),
  signals: z.array(CriticalSignalSchema),
});
export type CriticalCheck = z.infer<typeof CriticalCheckSchema>;

/**
 * With no suggestion after this long, the administrator is told "triage pending — classify manually" (technical
 * design §5, 2 minutes). The client works it out from `createdAt`; nothing changes on the issue.
 */
export const TRIAGE_PENDING_AFTER_MS = 2 * 60_000;

/*
 * Shared issue, impact and subscription (US-04). Paths from technical design §4.2 and the US-04 story:
 * `POST /issues/{id}/publication`, `PUT`/`DELETE /issues/{id}/impact`, `PUT`/`DELETE /issues/{id}/subscription`.
 * ASSUMPTION: every shape below, until US-04-01 (#89) defines them. All issue fields are optional, so the service as it
 * is today still parses.
 */

/** The administrator's reviewed summary, the only text residents of the building see (never the report text). */
export const SharedSummarySchema = z.object({
  text: z.string(),
  publishedAt: z.iso.datetime({ offset: true }),
});
export type SharedSummary = z.infer<typeof SharedSummarySchema>;

/** How many residents said the issue affects them, and whether the caller did. A count only: never who (US-04). */
export const ImpactSchema = z.object({
  count: z.number().int().nonnegative(),
  mine: z.boolean(),
});
export type Impact = z.infer<typeof ImpactSchema>;

/** `200` of `PUT`/`DELETE /issues/{id}/subscription`: the caller's own choice, off by default. */
export const SubscriptionSchema = z.object({ subscribed: z.boolean() });
export type Subscription = z.infer<typeof SubscriptionSchema>;

export const SUMMARY_MIN = 10;
export const SUMMARY_MAX = 500;

/** Body of `POST /issues/{id}/publication`: plain text of 10-500 characters, trimmed, no control characters. */
export const PublicationRequestSchema = z.object({
  summary: z
    .string()
    .trim()
    .superRefine((summary, ctx) => {
      const length = codePointLength(summary);
      if (length === 0) ctx.addIssue({ code: "custom", message: "required" });
      else if (length < SUMMARY_MIN) ctx.addIssue({ code: "custom", message: "too_short" });
      else if (length > SUMMARY_MAX) ctx.addIssue({ code: "custom", message: "too_long" });
      else if (hasForbiddenCharacter(summary)) ctx.addIssue({ code: "custom", message: "invalid_characters" });
    }),
});
export type PublicationRequest = z.infer<typeof PublicationRequestSchema>;

/** `200` of `POST /issues/{id}/publication`. Publishing changes the issue, so it moves the version. */
export const PublicationResultSchema = z.object({
  issueId: z.string(),
  version: z.number().int(),
  summary: SharedSummarySchema,
});
export type PublicationResult = z.infer<typeof PublicationResultSchema>;

/** One issue as `GET /issues` and `GET /issues/{id}` return it, already projected for the caller's role. */
export const IssueSchema = z.object({
  issueId: z.string(),
  buildingId: z.string(),
  buildingName: z.string(),
  /** The area of the first report: what the issue is about, without exposing anyone's text. */
  areaLabel: z.string(),
  state: IssueStateSchema,
  /** Sent back as `If-Match` on every change. */
  version: z.number().int(),
  createdAt: z.iso.datetime({ offset: true }),
  triage: TriageDecisionSchema.nullable(),
  suggestion: TriageSuggestionSchema.nullable(),
  criticalCheck: CriticalCheckSchema.nullish(),
  /** Published to the building's residents (US-04). Absent or `null` while unpublished. */
  summary: SharedSummarySchema.nullish(),
  /** With a published summary: the impact count, and whether the caller confirmed. */
  impact: ImpactSchema.nullish(),
  /** The caller's own notification subscription to this issue (US-04, used by US-10). */
  subscribed: z.boolean().optional(),
});
export type Issue = z.infer<typeof IssueSchema>;

/**
 * `GET /issues?buildingId&page&size`: issues of administered buildings, and the issues of the caller's own reports,
 * newest first, one page at a time. `triage.reason` is always `null` here.
 */
export const IssuePageSchema = z.object({
  items: z.array(IssueSchema),
  page: z.number().int(),
  size: z.number().int(),
  hasMore: z.boolean(),
});
export type IssuePage = z.infer<typeof IssuePageSchema>;

export const HistoryVisibilitySchema = z.enum(["public", "internal"]);
export const HistoryActorSchema = z.enum(["resident", "administrator", "contractor", "system"]);
export type HistoryActor = z.infer<typeof HistoryActorSchema>;

/**
 * Entry types the client explains. The first three exist in the service (#140); `critical_alert_raised` is an
 * ASSUMPTION for US-14-02 (#96), `summary_published` one for US-04-01 (#89), and the others are ASSUMPTION names for the Dispatch events Issue projects into history
 * (technical design §3.3). Any other type is
 * still shown, as a generic update, so a new type never breaks the page.
 */
export const KNOWN_HISTORY_TYPES = [
  "report_received",
  "triage_suggested",
  "triage_decided",
  "critical_alert_raised",
  "summary_published",
  "intervention_approved",
  "contractor_accepted",
  "contractor_declined",
  "booking_confirmed",
  "booking_confirmed_manually",
  "intervention_started",
  "completion_reported",
  "resolved",
  "cancelled",
] as const;
export type KnownHistoryType = (typeof KNOWN_HISTORY_TYPES)[number];

/** One entry of `GET /issues/{id}/history`, oldest first. Residents receive `public` entries only, without reasons. */
export const HistoryEntrySchema = z.object({
  sequence: z.number().int(),
  type: z.string(),
  visibility: HistoryVisibilitySchema,
  actorRole: HistoryActorSchema,
  occurredAt: z.iso.datetime({ offset: true }),
  category: IssueCategorySchema.nullable().optional(),
  urgency: IssueUrgencySchema.nullable().optional(),
  /** Internal: administrators only. */
  reason: z.string().nullable().optional(),
  /** `triage_suggested` only: who suggested it (agreed on #108, from `TriageSuggested.origin`). */
  origin: SuggestionOriginSchema.nullish(),
  /**
   * `triage_suggested` only: whether the suggestion was applied. `false` when it named a version the issue had already
   * left, before or after a decision (agreed on #108; technical design §3.3).
   */
  applied: z.boolean().optional(),
  /** `critical_alert_raised` only: the matched rule's code (agreed on #96), worded by the client. */
  code: z.string().nullish(),
});
export type HistoryEntry = z.infer<typeof HistoryEntrySchema>;
export const HistorySchema = z.object({ items: z.array(HistoryEntrySchema) });
export type History = z.infer<typeof HistorySchema>;

export const TRIAGE_REASON_MAX = 500;

/** Nothing chosen is `required`; a value the service does not know is `invalid`, as the service answers. */
const choice = (issue: { input?: unknown }) =>
  issue.input === undefined || issue.input === "" ? "required" : "invalid";

/** Body of `POST /issues/{id}/triage` (contract examples §1.2): category, urgency and a reason of 1-500 characters. */
export const TriageRequestSchema = z.object({
  category: z.enum(IssueCategorySchema.options, { error: choice }),
  urgency: z.enum(IssueUrgencySchema.options, { error: choice }),
  reason: z
    .string()
    .trim()
    .superRefine((reason, ctx) => {
      const length = codePointLength(reason);
      if (length === 0) ctx.addIssue({ code: "custom", message: "required" });
      else if (length > TRIAGE_REASON_MAX) ctx.addIssue({ code: "custom", message: "too_long" });
      else if (hasForbiddenCharacter(reason)) ctx.addIssue({ code: "custom", message: "invalid_characters" });
    }),
});
export type TriageRequest = z.infer<typeof TriageRequestSchema>;
/** The form's own values: nothing chosen yet is `""`. */
export interface TriageDraft {
  category: IssueCategory | "";
  urgency: IssueUrgency | "";
  reason: string;
}

/** `200` of `POST /issues/{id}/triage` (contract examples §1.2). */
export const TriageResultSchema = z.object({
  issueId: z.string(),
  state: IssueStateSchema,
  version: z.number().int(),
  category: IssueCategorySchema,
  urgency: IssueUrgencySchema,
  decisionSource: z.literal("human"),
});
export type TriageResult = z.infer<typeof TriageResultSchema>;

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
