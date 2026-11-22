import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import { isApiError, isClientError, NetworkError } from "@/shared/lib/errors";

/** How long data counts as fresh by default. Mutations invalidate what they change. */
export const STALE_TIME_MS = 30_000;
const MAX_RETRIES = 3;

/** A 4xx is a definite answer; network errors, 429 and 5xx retry with backoff, but not while offline. */
export const retryPolicy = (failureCount: number, error: unknown): boolean =>
  !isClientError(error) && failureCount < MAX_RETRIES && (navigator.onLine || !(error instanceof NetworkError));

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
