import type { IssueSummary, Me } from "@/contracts/issue";
import type { Persona } from "@/ports/auth-session";

/*
 * Synthetic data only: no real people, addresses or buildings (AGENTS.md). Emails use the reserved .test TLD.
 */

export const BUILDINGS = {
  a: { buildingId: "0190a1b2-0000-7000-8000-00000000000a", buildingName: "Demo Building A" },
  b: { buildingId: "0190a1b2-0000-7000-8000-00000000000b", buildingName: "Demo Building B" },
} as const;

interface MockPersona extends Persona {
  readonly session: Me;
}

export const PERSONAS: readonly MockPersona[] = [
  {
    id: "resident",
    label: "Resident",
    description: "Lives in unit 2B of Demo Building A. Reports faults.",
    session: {
      sub: "mock-resident",
      name: "Demo Resident",
      email: "resident@bmc.test",
      memberships: [{ ...BUILDINGS.a, unit: "2B", role: "resident" }],
      platformRoles: [],
      contractor: null,
    },
  },
  {
    id: "administrator",
    label: "Building administrator",
    description: "Administers Demo Buildings A and B. Triages and coordinates.",
    session: {
      sub: "mock-administrator",
      name: "Demo Administrator",
      email: "administrator@bmc.test",
      memberships: [
        { ...BUILDINGS.a, unit: null, role: "administrator" },
        { ...BUILDINGS.b, unit: null, role: "administrator" },
      ],
      platformRoles: [],
      contractor: null,
    },
  },
  {
    id: "contractor",
    label: "Contractor",
    description: "A validated contractor. No building membership.",
    session: {
      sub: "mock-contractor",
      name: "Demo Contractor",
      email: "contractor@bmc.test",
      memberships: [],
      platformRoles: [],
      contractor: { id: "0190a1b2-0000-7000-8000-0000000000c1", status: "active" },
    },
  },
  {
    id: "platform-staff",
    label: "Platform staff",
    description: "Validates contractor registrations. Sees the admin area.",
    session: {
      sub: "mock-platform-staff",
      name: "Demo Platform Staff",
      email: "staff@bmc.test",
      memberships: [],
      platformRoles: ["platform_staff"],
      contractor: null,
    },
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
      category: "Lifts",
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
      category: "Electrical",
      urgency: "low",
      createdAt: hoursAgo(now, 72),
      version: 5,
    },
    {
      id: "0190a1b2-0000-7000-8000-000000000104",
      buildingId: BUILDINGS.b.buildingId,
      title: "Water leak in the entrance hall",
      state: "in_progress",
      category: "Plumbing",
      urgency: "high",
      createdAt: hoursAgo(now, 30),
      version: 6,
    },
  ];
}
