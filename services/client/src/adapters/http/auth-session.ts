import type { HttpClient } from "@/adapters/http/client";
import { type Profile, ProfileSchema } from "@/contracts/issue";
import type { AuthSession, Session } from "@/ports/auth-session";
import { isApiError } from "@/shared/lib/errors";

export interface EdgeAuthConfig {
  /** The edge OIDC plugin's LoginUri (ADR-005). */
  readonly loginUri: string;
  /** The edge OIDC plugin's logout path. */
  readonly logoutUri: string;
  readonly navigate?: (url: string) => void;
}

/** The wire profile as the client's session model. Fields `/me` does not carry yet get their "none" value. */
export function toSession(profile: Profile): Session {
  return {
    sub: profile.userId,
    name: profile.displayName,
    email: null,
    memberships: profile.memberships.map((m) => ({ ...m, unit: null })),
    platformRoles: [],
    contractor: null,
  };
}

/**
 * Session through the Backend-for-Frontend at the edge (ADR-005): Traefik's OIDC plugin holds the tokens and the
 * browser only an HttpOnly cookie. `GET /api/issue/me` answers who it is; a 401 means "no session".
 */
/** The page is being replaced: nothing after this should run, so the promise stays pending (see the port). */
const leave = () => new Promise<never>(() => undefined);

export function httpAuthSession(http: HttpClient, config: EdgeAuthConfig): AuthSession {
  const navigate = config.navigate ?? ((url: string) => window.location.assign(url));
  return {
    async me() {
      try {
        return toSession(await http.get("/api/issue/me", ProfileSchema));
      } catch (error) {
        if (isApiError(error, 401)) return null;
        throw error;
      }
    },
    async login(returnTo) {
      // How the plugin returns the user to `returnTo` is a detail of spike item 3; the parameter name may change.
      navigate(`${config.loginUri}?redirect=${encodeURIComponent(returnTo)}`);
      return leave();
    },
    async logout() {
      navigate(config.logoutUri);
      return leave();
    },
  };
}
