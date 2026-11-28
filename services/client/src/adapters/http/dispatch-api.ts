import type { HttpClient } from "@/adapters/http/client";
import { BookingCommandResultSchema, InterventionListSchema } from "@/contracts/dispatch";
import type { DispatchApi } from "@/ports/dispatch-api";

const PREFIX = "/api/dispatch";
const segment = encodeURIComponent;

/** The Dispatch service behind the edge prefix /api/dispatch (technical design §4.1). */
export function httpDispatchApi(http: HttpClient): DispatchApi {
  return {
    listInterventions: (issueId, signal) =>
      http.get(
        `${PREFIX}/interventions?${new URLSearchParams({ issueId }).toString()}`,
        InterventionListSchema,
        signal,
      ),
    confirmManually: (interventionId, version, confirmation, idempotencyKey) =>
      http.request("POST", `${PREFIX}/interventions/${segment(interventionId)}/manual-confirmation`, {
        schema: BookingCommandResultSchema,
        body: confirmation,
        ifMatch: version,
        idempotencyKey,
      }),
  };
}
