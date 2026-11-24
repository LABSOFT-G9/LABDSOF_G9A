import type { IssueSummary } from "@/contracts/issue";
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

export function seedIssues(now: Date): IssueSummary[] {
  return [
    {
      id: "0190a1b2-0000-7000-8000-000000000101",
      buildingId: BUILDINGS.a.buildingId,
      title: "Lift stuck between floors 3 and 4",
      state: "triaged",
      category: "lift",
      urgency: "critical",
      createdAt: hoursAgo(now, 3),
      version: 3,
    },
    {
      id: "0190a1b2-0000-7000-8000-000000000102",
      buildingId: BUILDINGS.a.buildingId,
      title: "Garage door does not close",
      state: "received",
      category: null,
      urgency: null,
      createdAt: hoursAgo(now, 20),
      version: 1,
    },
    {
      id: "0190a1b2-0000-7000-8000-000000000103",
      buildingId: BUILDINGS.a.buildingId,
      title: "Stairwell light flickering on floor 2",
      state: "scheduled",
      category: "electrical",
      urgency: "low",
      createdAt: hoursAgo(now, 72),
      version: 5,
    },
    {
      id: "0190a1b2-0000-7000-8000-000000000104",
      buildingId: BUILDINGS.b.buildingId,
      title: "Water leak in the entrance hall",
      state: "in_progress",
      category: "plumbing",
      urgency: "high",
      createdAt: hoursAgo(now, 30),
      version: 6,
    },
  ];
}
