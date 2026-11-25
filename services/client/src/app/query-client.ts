import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import { ContractError, isApiError, isClientError } from "@/shared/lib/errors";

/** How long data counts as fresh by default. Mutations invalidate what they change. */
export const STALE_TIME_MS = 30_000;
const MAX_RETRIES = 3;

/**
 * Network errors, 429 and 5xx retry with backoff. Not retried: a 4xx (a definite answer), a ContractError (the same
 * body will break the contract again) and anything while offline (the `online` event re-runs the loaders instead).
 */
export const retryPolicy = (failureCount: number, error: unknown): boolean =>
  navigator.onLine && failureCount < MAX_RETRIES && !isClientError(error) && !(error instanceof ContractError);

const retryDelay = (attempt: number): number => Math.min(800 * 2 ** attempt, 10_000);

/** The one QueryClient. Server state lives here and nowhere else: no hand-written caches. */
export function createQueryClient({ onUnauthenticated }: { onUnauthenticated: () => void }): QueryClient {
  const onError = (error: unknown) => {
    if (isApiError(error, 401)) onUnauthenticated();
  };
  return new QueryClient({
    queryCache: new QueryCache({ onError }),
    mutationCache: new MutationCache({ onError }),
    defaultOptions: {
      queries: { retry: retryPolicy, retryDelay, staleTime: STALE_TIME_MS, gcTime: 10 * 60_000 },
      // Writes are never retried automatically: a retry is the user's decision (and reuses the Idempotency-Key).
      mutations: { retry: false },
    },
  });
}
