import type { Area, HistoryEntry, Issue } from "@/contracts/issue";
import type { Persona, Session } from "@/ports/auth-session";

/*
 * Synthetic data only: no real people, addresses or buildings (AGENTS.md). Users, buildings and memberships are the
 * Issue service's synthetic seed (services/issue/src/main/resources/db/seed/R__synthetic_fixtures.sql), with the same
 * IDs and names, so switching a port to HTTP shows the same people and buildings. The contractor link is the one in
 * the contract examples (documents/07a-contract-examples.md). Emails use the reserved .test TLD; `/me` has no email
 * yet, so they exist only here.
 */

const id = (suffix: string) => `0199f2a0-0000-7000-8000-${suffix}`;

export const BUILDINGS = {
  a: { buildingId: id("00000000b001"), buildingName: "Synthetic Building A" },
  b: { buildingId: id("00000000b002"), buildingName: "Synthetic Building B" },
} as const;

/** The seeded areas of each building, keyed by building ID. */
export const AREAS: Readonly<Record<string, readonly Area[]>> = {
  [BUILDINGS.a.buildingId]: [
    { areaId: id("00000000a001"), label: "Garage level -1", kind: "common_area" },
    { areaId: id("00000000a002"), label: "Entrance hall", kind: "common_area" },
    { areaId: id("00000000a003"), label: "Unit 2B", kind: "unit" },
  ],
  [BUILDINGS.b.buildingId]: [
    { areaId: id("00000000a101"), label: "Garage", kind: "common_area" },
    { areaId: id("00000000a102"), label: "Roof terrace", kind: "common_area" },
  ],
};

interface MockPersona extends Persona {
  readonly session: Session;
}

const person = (
  userId: string,
  name: string,
  email: string,
  memberships: Session["memberships"] = [],
  extra: Partial<Pick<Session, "platformRoles" | "contractor">> = {},
): Session => ({
  sub: id(userId),
  name,
  email,
  memberships,
  platformRoles: extra.platformRoles ?? [],
  contractor: extra.contractor ?? null,
});

export const PERSONAS: readonly MockPersona[] = [
  {
    id: "resident-a1",
    label: "Resident A1",
    description: "Resident of Synthetic Building A, unit 2B. Reports faults.",
    session: person("000000000101", "Synthetic Resident A1", "resident.a1@bmc.test", [
      { membershipId: id("00000000d001"), ...BUILDINGS.a, unit: "2B", role: "resident" },
    ]),
  },
  {
    id: "resident-a2",
    label: "Resident A2",
    description: "Another resident of Synthetic Building A.",
    session: person("000000000102", "Synthetic Resident A2", "resident.a2@bmc.test", [
      { membershipId: id("00000000d002"), ...BUILDINGS.a, unit: null, role: "resident" },
    ]),
  },
  {
    id: "resident-b1",
    label: "Resident B1",
    description: "Resident of Synthetic Building B.",
    session: person("000000000111", "Synthetic Resident B1", "resident.b1@bmc.test", [
      { membershipId: id("00000000d003"), ...BUILDINGS.b, unit: null, role: "resident" },
    ]),
  },
  {
    id: "administrator-a",
    label: "Administrator A",
    description: "Administers Synthetic Building A. Triages and coordinates.",
    session: person("000000000201", "Synthetic Administrator A", "administrator.a@bmc.test", [
      { membershipId: id("00000000d004"), ...BUILDINGS.a, unit: null, role: "administrator" },
    ]),
  },
  {
    id: "administrator-b",
    label: "Administrator B",
    description: "Administers Synthetic Building B.",
    session: person("000000000202", "Synthetic Administrator B", "administrator.b@bmc.test", [
      { membershipId: id("00000000d005"), ...BUILDINGS.b, unit: null, role: "administrator" },
    ]),
  },
  {
    id: "contractor",
    label: "Contractor",
    description: "Synthetic Plumbing Co. No building membership.",
    session: person("000000000301", "Synthetic Contractor", "contractor@bmc.test", [], {
      contractor: { id: id("000000000401"), status: "active" },
    }),
  },
  {
    id: "platform-staff",
    label: "Platform staff",
    description: "Mock only, not in the seed. Validates contractor registrations.",
    session: person("000000000501", "Synthetic Platform Staff", "staff@bmc.test", [], {
      platformRoles: ["platform_staff"],
    }),
  },
];

const hoursAgo = (now: Date, hours: number) => new Date(now.getTime() - hours * 3_600_000).toISOString();

/** An issue as the mock Issue service stores it: the full record, before projecting it for the caller. */
export interface MockIssueRecord extends Issue {
  /** Users whose reports belong to this issue: they may follow it. */
  readonly reporterIds: readonly string[];
  readonly history: readonly HistoryEntry[];
}

const issueId = (suffix: string) => `0199f2a1-0000-7000-8000-${suffix}`;
const residentA1 = id("000000000101");
const residentA2 = id("000000000102");
const residentB1 = id("000000000111");

/**
 * Issues in every situation the US-02 screens must tell apart: an unconfirmed AI suggestion, no decision yet, and
 * human decisions followed by approval, contractor acceptance and booking (manual and synchronised) as separate steps.
 */
export function seedIssues(now: Date): MockIssueRecord[] {
  const at = (hours: number) => hoursAgo(now, hours);
  const entry = (sequence: number, rest: Omit<HistoryEntry, "sequence">): HistoryEntry => ({ sequence, ...rest });
  const received = (hours: number) =>
    entry(1, { type: "report_received", visibility: "public", actorRole: "resident", occurredAt: at(hours) });
  return [
    {
      issueId: issueId("000000002001"),
      ...BUILDINGS.a,
      areaLabel: "Entrance hall",
      state: "in_triage",
      version: 2,
      createdAt: at(3),
      triage: null,
      suggestion: {
        category: "electrical",
        urgency: "high",
        origin: "ai",
        rationale: "Mentions exposed wires next to the light switch by the main door.",
        needsReview: false,
        failureCode: null,
        suggestedAt: at(2.9),
        rulesVersion: null,
        modelVersion: "mock-model-v1",
      },
      reporterIds: [residentA1],
      history: [
        received(3),
        entry(2, {
          type: "triage_suggested",
          visibility: "internal",
          actorRole: "system",
          occurredAt: at(2.9),
          category: "electrical",
          urgency: "high",
          origin: "ai",
        }),
      ],
    },
    {
      issueId: issueId("000000002002"),
      ...BUILDINGS.a,
      areaLabel: "Garage level -1",
      state: "received",
      version: 1,
      createdAt: at(20),
      triage: null,
      suggestion: null,
      reporterIds: [residentA2],
      history: [received(20)],
    },
    {
      issueId: issueId("000000002003"),
      ...BUILDINGS.a,
      areaLabel: "Unit 2B",
      state: "scheduled",
      version: 6,
      createdAt: at(72),
      triage: {
        category: "plumbing",
        urgency: "normal",
        reason: "Slow leak under the kitchen sink. No electrical risk.",
        decidedAt: at(70),
        decisionSource: "human",
      },
      suggestion: null,
      reporterIds: [residentA1],
      history: [
        received(72),
        entry(2, {
          type: "triage_decided",
          visibility: "public",
          actorRole: "administrator",
          occurredAt: at(70),
          category: "plumbing",
          urgency: "normal",
          reason: "Slow leak under the kitchen sink. No electrical risk.",
        }),
        // US-14-03: the AI answered after the administrator had decided. Kept with its source; it changed nothing.
        entry(3, {
          type: "triage_suggested",
          visibility: "internal",
          actorRole: "system",
          occurredAt: at(69.5),
          category: "plumbing",
          urgency: "low",
          origin: "ai",
        }),
        entry(4, {
          type: "intervention_approved",
          visibility: "public",
          actorRole: "administrator",
          occurredAt: at(60),
        }),
        entry(5, { type: "contractor_accepted", visibility: "public", actorRole: "contractor", occurredAt: at(50) }),
        entry(6, {
          type: "booking_confirmed_manually",
          visibility: "public",
          actorRole: "administrator",
          occurredAt: at(40),
        }),
      ],
    },
    {
      issueId: issueId("000000002004"),
      ...BUILDINGS.b,
      areaLabel: "Roof terrace",
      state: "in_progress",
      version: 7,
      createdAt: at(30),
      triage: {
        category: "structural",
        urgency: "high",
        reason: "Loose tiles above the terrace door.",
        decidedAt: at(29),
        decisionSource: "human",
      },
      suggestion: null,
      reporterIds: [residentB1],
      history: [
        received(30),
        entry(2, {
          type: "triage_decided",
          visibility: "public",
          actorRole: "administrator",
          occurredAt: at(29),
          category: "structural",
          urgency: "high",
          reason: "Loose tiles above the terrace door.",
        }),
        entry(3, {
          type: "intervention_approved",
          visibility: "public",
          actorRole: "administrator",
          occurredAt: at(28),
        }),
        entry(4, { type: "contractor_accepted", visibility: "public", actorRole: "contractor", occurredAt: at(27) }),
        entry(5, { type: "booking_confirmed", visibility: "public", actorRole: "system", occurredAt: at(26) }),
        entry(6, { type: "intervention_started", visibility: "public", actorRole: "contractor", occurredAt: at(2) }),
      ],
    },
    {
      // US-03/US-14: the AI timed out, so the fallback rules answered with a lower urgency than Issue's own critical
      // check. The alert stays; the administrator decides.
      issueId: issueId("000000002005"),
      ...BUILDINGS.b,
      areaLabel: "Garage",
      state: "in_triage",
      version: 2,
      createdAt: at(1),
      triage: null,
      suggestion: {
        category: "electrical",
        urgency: "normal",
        origin: "fallback",
        rationale: "Rules matched “socket” and “sparks”: electrical.",
        needsReview: true,
        failureCode: "timeout",
        suggestedAt: at(0.95),
        rulesVersion: "rules-v1",
        modelVersion: null,
      },
      criticalAlert: { reason: "The report mentions smoke near an electrical socket.", raisedAt: at(1) },
      reporterIds: [residentB1],
      history: [
        received(1),
        entry(2, { type: "critical_alert_raised", visibility: "internal", actorRole: "system", occurredAt: at(1) }),
        entry(3, {
          type: "triage_suggested",
          visibility: "internal",
          actorRole: "system",
          occurredAt: at(0.95),
          category: "electrical",
          urgency: "normal",
          origin: "fallback",
        }),
      ],
    },
  ];
}
