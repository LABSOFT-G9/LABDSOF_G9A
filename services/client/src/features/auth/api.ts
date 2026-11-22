import { type QueryClient, queryOptions } from "@tanstack/react-query";
import type { AuthSession, Session } from "@/ports/auth-session";

export const sessionKey = ["session"] as const;

export const sessionQuery = (auth: AuthSession) =>
  queryOptions({
    queryKey: sessionKey,
    queryFn: () => auth.me(),
    staleTime: 5 * 60_000,
  });

export const readSession = (queryClient: QueryClient): Session | null | undefined =>
  queryClient.getQueryData<Session | null>(sessionKey);

export const writeSession = (queryClient: QueryClient, session: Session | null) =>
  queryClient.setQueryData<Session | null>(sessionKey, session);
