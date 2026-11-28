import { z } from "zod";

/*
 * Dispatch service contracts, hand-written until the service publishes its OpenAPI document (ADR-002). Paths are
 * relative to the edge prefix /api/dispatch (services/dispatch README, "API"). The shapes match Dispatch's
 * `InterventionResponse`, `BookingResponse` and `BookingCommandResponse` as merged in #161 (US-08-01). Dispatch writes
 * absent values as `null`. Only what the booking screens read is parsed; zod drops the rest, so US-06-02 (#100) and
 * US-07-02 (#102) can add the proposal fields here without changing these.
 */

/** Intervention states from technical design §3.2 (`InterventionState` in services/dispatch). */
export const InterventionStateSchema = z.enum([
  "awaiting_approval",
  "sent",
  "scheduling_pending",
  "booked",
  "in_progress",
  "completion_reported",
  "cancellation_pending",
  "declined",
  "cancelled",
  "closed",
]);
export type InterventionState = z.infer<typeof InterventionStateSchema>;

/**
 * Booking states (`BookingState`, technical design 4.5). A booking is its own aggregate: its state is kept apart from
 * the intervention's.
 */
export const BookingStateSchema = z.enum([
  "requested",
  "uncertain",
  "confirmed",
  "rejected",
  "failed",
  "discrepancy",
  "cancelled",
]);
export type BookingState = z.infer<typeof BookingStateSchema>;

/** Who confirmed the booking: the calendar, or the administrator by hand. */
export const BookingSourceSchema = z.enum(["calendar", "manual"]);
export type BookingSource = z.infer<typeof BookingSourceSchema>;

/** How a manual confirmation was agreed (`ManualSource`). */
export const ManualSourceSchema = z.enum(["phone", "email", "in_person"]);
export type ManualSource = z.infer<typeof ManualSourceSchema>;

/** What the booking worker does next (`BookingAction`). */
export const BookingActionSchema = z.enum(["create", "reconcile", "verify", "orphan_check", "cancel"]);
export type BookingAction = z.infer<typeof BookingActionSchema>;

export const WindowSchema = z.object({ start: z.string(), end: z.string() });
export type Window = z.infer<typeof WindowSchema>;

/** A booking as the building's administrators see it (`BookingResponse`). */
export const BookingSchema = z.object({
  bookingId: z.string(),
  state: BookingStateSchema,
  /** `null` until confirmed. */
  source: BookingSourceSchema.nullable(),
  /** The window asked of the calendar: the agreed one. */
  requestedWindow: WindowSchema,
  /** The window booked; the administrator's for a manual confirmation. */
  bookedWindow: WindowSchema,
  externalRef: z.string().nullable(),
  /**
   * Why it failed, was rejected or is a discrepancy (services/dispatch README, "Booking"). A plain string, so a code
   * added later is still shown, as a generic reason.
   */
  reasonCode: z.string().nullable(),
  pendingAction: BookingActionSchema.nullable(),
  lastReconciledAt: z.string().nullable(),
  manual: z.object({ source: ManualSourceSchema, reason: z.string().nullable(), confirmedAt: z.string() }).nullable(),
  calls: z.array(z.object({ action: z.string(), outcome: z.string(), at: z.string() })),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type Booking = z.infer<typeof BookingSchema>;

/** One intervention, as `GET /interventions?issueId=` lists it for the building's administrators. */
export const InterventionSchema = z.object({
  interventionId: z.string(),
  issueId: z.string(),
  state: InterventionStateSchema,
  version: z.number().int(),
  contractor: z.object({ contractorId: z.string(), displayName: z.string(), synthetic: z.boolean() }),
  agreedProposalVersion: z.number().int().nullable(),
  /** The current booking; `null` before the terms are agreed. */
  booking: BookingSchema.nullable(),
  /** Every booking, oldest first: another request after a rejection or failure is a new booking. */
  bookings: z.array(BookingSchema),
  /** Why it is being cancelled or was cancelled; `null` unless a cancellation started. */
  cancellation: z.object({ reasonCode: z.string(), note: z.string().nullable() }).nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export type Intervention = z.infer<typeof InterventionSchema>;

/** `GET /interventions?issueId=`: newest first, terminal ones included. */
export const InterventionListSchema = z.object({ items: z.array(InterventionSchema) });
export type InterventionList = z.infer<typeof InterventionListSchema>;

/** `BookingCommands.REASON_MAX` in services/dispatch. */
export const MANUAL_REASON_MAX = 500;

/** `POST /interventions/{id}/manual-confirmation`: the agreed window, how it was agreed and why. */
export const ManualConfirmationSchema = z.object({
  window: WindowSchema,
  source: ManualSourceSchema,
  reason: z.string(),
});
export type ManualConfirmation = z.infer<typeof ManualConfirmationSchema>;

/** Answer of the booking commands (`BookingCommandResponse`). `bookingState` is `null` for a replay. */
export const BookingCommandResultSchema = z.object({
  interventionId: z.string(),
  state: InterventionStateSchema,
  version: z.number().int(),
  bookingState: BookingStateSchema.nullable(),
});
export type BookingCommandResult = z.infer<typeof BookingCommandResultSchema>;
