import type { Booking, Intervention } from "@/contracts/dispatch";

/** Synthetic Dispatch answers for tests, in the wire shape (`InterventionResponse`, `BookingResponse`). */

export const WINDOW = { start: "2026-12-03T09:00:00Z", end: "2026-12-03T11:00:00Z" };

export function booking(overrides: Partial<Booking> = {}): Booking {
  return {
    bookingId: "0199f2a3-0000-7000-8000-000000000001",
    state: "requested",
    source: null,
    requestedWindow: WINDOW,
    bookedWindow: WINDOW,
    externalRef: null,
    reasonCode: null,
    pendingAction: "create",
    lastReconciledAt: null,
    manual: null,
    calls: [],
    createdAt: "2026-11-28T10:00:00Z",
    updatedAt: "2026-11-28T10:00:00Z",
    ...overrides,
  };
}

export function intervention(overrides: Partial<Intervention> = {}): Intervention {
  const current = overrides.booking === undefined ? booking() : overrides.booking;
  return {
    interventionId: "0199f2a2-0000-7000-8000-000000000001",
    issueId: "0199f2a1-0000-7000-8000-000000002003",
    state: "scheduling_pending",
    version: 4,
    contractor: {
      contractorId: "0199f2a0-0000-7000-8000-000000000401",
      displayName: "Synthetic Plumbing Co.",
      synthetic: true,
    },
    agreedProposalVersion: 1,
    booking: current,
    bookings: current ? [current] : [],
    cancellation: null,
    createdAt: "2026-11-27T10:00:00Z",
    updatedAt: "2026-11-28T10:00:00Z",
    ...overrides,
  };
}
