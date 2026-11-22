import type { AppContext } from "@/app/context";
import { readSession, sessionKey, sessionQuery, writeSession } from "@/features/auth/api";
import type { Session } from "@/ports/auth-session";
import { isUnavailable } from "@/shared/lib/errors";

export type AuthState =
  | { readonly kind: "signed-in"; readonly session: Session }
  | { readonly kind: "anonymous" }
  /** The API could not be reached, so nobody knows whether there is a session. */
  | { readonly kind: "unreachable" };

const toState = (session: Session | null): AuthState =>
  session ? { kind: "signed-in", session } : { kind: "anonymous" };

/**
 * Resolves who is signed in BEFORE any guard runs, so no guard ever sees "still loading". Cheap after the first
 * time: the session query is in memory.
 */
export async function loadAuth({ queryClient, services }: AppContext): Promise<AuthState> {
  const inMemory = readSession(queryClient);
  if (inMemory !== undefined) return toState(inMemory);
  try {
    return toState(await queryClient.fetchQuery(sessionQuery(services.auth)));
  } catch (error) {
    if (isUnavailable(error)) return { kind: "unreachable" };
    throw error;
  }
}

/**
 * Forget everything that belonged to the signed-in user, in an order that leaves no window for a late response to
 * put it back: cancel in-flight queries → clear the cache → session null.
 */
async function forgetUserData({ queryClient }: AppContext) {
  await queryClient.cancelQueries();
  queryClient.clear();
  writeSession(queryClient, null);
}

/** A sign-in just completed (mock personas): ask who it is now, so the guards need no extra round trip. */
export async function startSession(context: AppContext): Promise<Session | null> {
  await forgetUserData(context);
  await context.queryClient.invalidateQueries({ queryKey: sessionKey, exact: true });
  const session = await context.queryClient.fetchQuery({ ...sessionQuery(context.services.auth), staleTime: 0 });
  return session;
}

let expiring = false;

/** Global 401 policy: session gone → user data gone → guards re-run and redirect to the login page. Not re-entrant. */
export async function expireSession(context: AppContext, invalidate: () => Promise<void>) {
  if (expiring || readSession(context.queryClient) === null) return;
  expiring = true;
  try {
    await forgetUserData(context);
    await invalidate();
  } finally {
    expiring = false;
  }
}

/** Sign-out: local data first (nothing lingers if the request fails), then the adapter (the BFF leaves the page). */
export async function signOut(context: AppContext) {
  await forgetUserData(context);
  await context.services.auth.logout();
}
