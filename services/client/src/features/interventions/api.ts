import { mutationOptions, type QueryClient, queryOptions } from "@tanstack/react-query";
import type { Intervention, InterventionList, ManualConfirmation } from "@/contracts/dispatch";
import { issueKeys } from "@/features/issues";
import type { DispatchApi } from "@/ports/dispatch-api";
import { isApiError } from "@/shared/lib/errors";

/** Key factory: every intervention query key starts with "interventions", so one invalidation reaches them all. */
export const interventionKeys = {
  all: ["interventions"] as const,
  forIssue: (issueId: string) => [...interventionKeys.all, "issue", issueId] as const,
};

/** How often a booking the worker is still handling is asked again. */
export const BOOKING_POLL_MS = 10_000;

/**
 * Still moving without anyone acting: the worker is calling or checking the calendar, or a cancellation waits for it.
 * The administrator watches these change, so they are asked again.
 */
export const isBookingInFlight = (intervention: Intervention | undefined) =>
  !!intervention &&
  (intervention.state === "cancellation_pending" ||
    (!!intervention.booking &&
      (intervention.booking.state === "requested" ||
        intervention.booking.state === "uncertain" ||
        intervention.booking.pendingAction !== null)));

/**
 * The issue's interventions, newest first. Dispatch answers `404` for an issue it does not know yet (its projection
 * of Issue's events lags behind): that is shown as no intervention, not as an error.
 */
export const issueInterventionsQuery = (dispatch: DispatchApi, issueId: string) =>
  queryOptions({
    queryKey: interventionKeys.forIssue(issueId),
    queryFn: async ({ signal }): Promise<InterventionList> => {
      try {
        return await dispatch.listInterventions(issueId, signal);
      } catch (error) {
        if (isApiError(error, 404)) return { items: [] };
        throw error;
      }
    },
    refetchInterval: (query) => (isBookingInFlight(query.state.data?.items[0]) ? BOOKING_POLL_MS : false),
  });

export interface ManualConfirmationVariables {
  readonly version: number;
  readonly confirmation: ManualConfirmation;
  readonly idempotencyKey: string;
}

/**
 * The confirmation books the intervention; Issue moves the issue to `scheduled` when it applies `BookingConfirmed`
 * (US-07-03), so the issue is refetched too. On a conflict nothing is, so the form keeps what the administrator typed.
 */
export const manualConfirmationMutation = (
  dispatch: DispatchApi,
  queryClient: QueryClient,
  issueId: string,
  interventionId: string,
) =>
  mutationOptions({
    mutationFn: ({ version, confirmation, idempotencyKey }: ManualConfirmationVariables) =>
      dispatch.confirmManually(interventionId, version, confirmation, idempotencyKey),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: interventionKeys.forIssue(issueId) }),
        queryClient.invalidateQueries({ queryKey: issueKeys.all }),
      ]);
    },
  });
