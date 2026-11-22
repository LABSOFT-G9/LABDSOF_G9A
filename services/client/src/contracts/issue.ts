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

export const MembershipSchema = z.object({
  buildingId: z.string(),
  buildingName: z.string(),
  unit: z.string().nullable(),
  role: BuildingRoleSchema,
});
export type Membership = z.infer<typeof MembershipSchema>;

/**
 * `GET /me`: the caller's own profile and memberships. ASSUMPTION: the design names the operation but not its body;
 * align this schema with the Issue service when it defines `/me`. Roles are per building and resolved server-side;
 * no role travels in a token (ADR-005).
 */
export const MeSchema = z.object({
  sub: z.string(),
  name: z.string(),
  // A plain string: the client only displays it, and a strict format check here would lock out a user whose
  // identity-provider email is unusual (the root route needs /me to parse before anything renders).
  email: z.string(),
  memberships: z.array(MembershipSchema),
  platformRoles: z.array(PlatformRoleSchema).default([]),
  contractor: z.object({ id: z.string(), status: z.enum(["pending", "active", "suspended"]) }).nullable(),
});
export type Me = z.infer<typeof MeSchema>;

/** Issue states from technical design §3. */
export const IssueStateSchema = z.enum([
  "received",
  "in_triage",
  "triaged",
  "in_coordination",
  "scheduled",
  "in_progress",
  "resolved",
  "cancelled",
]);
export type IssueState = z.infer<typeof IssueStateSchema>;

export const IssueUrgencySchema = z.enum(["low", "medium", "high", "critical"]);
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
