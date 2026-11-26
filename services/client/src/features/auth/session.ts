import type { AppContext } from "@/app/context";
import { readSession, sessionKey, sessionQuery, writeSession } from "@/features/auth/api";
import type { Session } from "@/ports/auth-session";
import { isUnavailable } from "@/shared/lib/errors";
import { forgetUnsentReportOfOthers } from "@/shared/lib/unsent-report";

export type AuthState =
  | { readonly kind: "signed-in"; readonly session: Session }
  | { readonly kind: "anonymous" }
  /** The API could not be reached (or asked us to slow down), so nobody knows whether there is a session. */
  | { readonly kind: "unreachable"; readonly error: unknown }
  /** The API answered something unusable (e.g. `/me` broke its contract). The login page shows it, with sign-out. */
  | { readonly kind: "failed"; readonly error: unknown };

const toState = (session: Session | null): AuthState =>
  session ? { kind: "signed-in", session } : { kind: "anonymous" };

/**
 * Resolves who is signed in BEFORE any guard runs, so no guard ever sees "still loading". A fresh session comes from
 * memory; once it is older than the query's stale time it is asked again, so an edge session that ended while the
 * app was open is noticed on the next navigation. A network blip keeps the session we already had.
 */
export async function loadAuth(context: AppContext): Promise<AuthState> {
  const { queryClient, services } = context;
  const inMemory = readSession(queryClient);
  try {
    const session = await queryClient.fetchQuery(sessionQuery(services.auth));
    // The re-ask found another account: wipe the previous one's data BEFORE any loader can read it from the cache.
    if (inMemory && session && inMemory.sub !== session.sub) await forgetUserData(context, session);
    // A report kept across sign-in belongs to its author only: gone as soon as someone else is signed in.
    if (session) forgetUnsentReportOfOthers(session.sub);
    return toState(session);
  } catch (error) {
    if (isUnavailable(error)) return inMemory === undefined ? { kind: "unreachable", error } : toState(inMemory);
    return { kind: "failed", error };
  }
}

/**
 * Forget everything that belonged to the signed-in user, in an order that leaves no window for a late response to
 * put it back: cancel in-flight queries → clear the cache → write the session we now know (usually none).
 */
async function forgetUserData({ queryClient }: AppContext, next: Session | null = null) {
  await queryClient.cancelQueries();
  queryClient.clear();
  writeSession(queryClient, next);
}

/** A sign-in just completed (mock personas): ask who it is now, so the guards need no extra round trip. */
export async function startSession(context: AppContext): Promise<Session | null> {
  await forgetUserData(context);
  return context.queryClient.fetchQuery({ ...sessionQuery(context.services.auth), staleTime: 0 });
}

let expiring = false;

/** Global 401 policy: session gone → user data gone → guards re-run and redirect to the login page. Not re-entrant. */
export async function expireSession(context: AppContext, invalidate: () => Promise<void>) {
  if (expiring) return;
  expiring = true;
  try {
    await forgetUserData(context);
    await invalidate();
  } finally {
    expiring = false;
  }
}

/**
 * Watches the session query for answers from the server (a refetch on focus, or the re-ask in `loadAuth`). When the
 * person it names changes — the session ended, or another account signed in in another tab — the previous person's
 * data is wiped and the guards re-run. Our own writes (sign-in, sign-out) only update what is known.
 */
export function watchSession(context: AppContext, invalidate: () => Promise<void>): () => void {
  let known = readSession(context.queryClient)?.sub ?? null;
  let latest: Session | null = null;
  let latestAt = 0;
  // Transitions run one at a time, each applying the NEWEST answer: if /me reports B and then C before B's cleanup
  // ran, no cleanup can write B back over C. (Running after the cache's notification also avoids re-entering it.)
  let queue = Promise.resolve();
  return context.queryClient.getQueryCache().subscribe((event) => {
    if (event.type !== "updated" || event.query.queryKey[0] !== sessionKey[0]) return;
    if (event.action.type !== "success") return;
    const session = (event.query.state.data as Session | null | undefined) ?? null;
    const previous = known;
    known = session?.sub ?? null;
    if (event.action.manual || previous === null || previous === known) return;
    latest = session;
    latestAt = event.query.state.dataUpdatedAt;
    queue = queue
      .then(async () => {
        const target = latest;
        // Something newer was written since this answer (loadAuth already wiped for it, or a sign-in/out): skip.
        if ((context.queryClient.getQueryState(sessionKey)?.dataUpdatedAt ?? 0) > latestAt) return;
        await context.queryClient.cancelQueries();
        // While cancelling, the user may have signed out (or in) here: if the cache no longer holds the answer this
        // cleanup was for, our own write is newer and wins. Never put another account back over it.
        if ((readSession(context.queryClient)?.sub ?? null) !== (target?.sub ?? null)) return;
        context.queryClient.clear();
        writeSession(context.queryClient, target);
        await invalidate();
      })
      .catch(() => undefined);
  });
}

/** Sign-out: local data first (nothing lingers if the request fails), then the adapter (the BFF leaves the page). */
export async function signOut(context: AppContext) {
  await forgetUserData(context);
  await context.services.auth.logout();
}
