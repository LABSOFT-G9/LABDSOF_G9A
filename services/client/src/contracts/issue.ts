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
