import type { BookingCommandResult, InterventionList, ManualConfirmation } from "@/contracts/dispatch";

/** The Dispatch service, as the client needs it. Grows one method per use case (ADR-009). */
export interface DispatchApi {
  /**
   * The issue's interventions, newest first, terminal ones included (US-08). Administrators of the issue's building
   * only; `404` for anyone else, and for an issue Dispatch does not know yet.
   */
  listInterventions(issueId: string, signal?: AbortSignal): Promise<InterventionList>;

  /**
   * Confirm the booking by hand (US-08): the administrator agreed the window with the contractor by phone, email or in
   * person. Only while the intervention is `scheduling_pending`. `version` is the intervention version the form was
   * opened on, sent as `If-Match` (`409 VERSION_CONFLICT` if it changed); `409 INVALID_TRANSITION` once it is booked.
   */
  confirmManually(
    interventionId: string,
    version: number,
    confirmation: ManualConfirmation,
    idempotencyKey: string,
  ): Promise<BookingCommandResult>;
}
