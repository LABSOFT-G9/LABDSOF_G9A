import { type QueryClient, queryOptions } from "@tanstack/react-query";
import type { AuthSession, Session } from "@/ports/auth-session";

export const sessionKey = ["session"] as const;

export const sessionQuery = (auth: AuthSession) =>
  queryOptions({
    queryKey: sessionKey,
    queryFn: () => auth.me(),
    staleTime: 5 * 60_000,
    // The root route waits for this answer: fail fast and let the login page offer the retry, instead of holding
    // every navigation through the default backoff while the API is down.
    retry: false,
  });

export const readSession = (queryClient: QueryClient): Session | null | undefined =>
  queryClient.getQueryData<Session | null>(sessionKey);

export const writeSession = (queryClient: QueryClient, session: Session | null) =>
  queryClient.setQueryData<Session | null>(sessionKey, session);
