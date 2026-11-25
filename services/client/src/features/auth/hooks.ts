import { useQuery } from "@tanstack/react-query";
import { useRouteContext } from "@tanstack/react-router";
import { useServices } from "@/app/context";
import { sessionQuery } from "@/features/auth/api";
import type { Session } from "@/ports/auth-session";

/**
 * The signed-in user, live (it updates when the session query revalidates). Only for routes under `_app`, whose
 * guard admitted a session.
 */
export function useSession(): Session {
  const { auth } = useServices();
  const { data } = useQuery(sessionQuery(auth));
  // While signing out the query is already null but the layout has not unmounted yet: keep the admitted session for
  // that last render instead of crashing.
  const admitted = useRouteContext({ from: "/_app", select: (c) => c.session });
  return data ?? admitted;
}
