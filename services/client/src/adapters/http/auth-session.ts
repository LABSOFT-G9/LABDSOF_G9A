import type { HttpClient } from "@/adapters/http/client";
import { type PlatformRole, PlatformRoleSchema, type Profile, ProfileSchema } from "@/contracts/issue";
import type { AuthSession, Session } from "@/ports/auth-session";
import { isApiError } from "@/shared/lib/errors";
import { rememberReturnTo } from "@/shared/lib/return-to";

export interface EdgeAuthConfig {
  /** The edge OIDC plugin's LoginUri (ADR-005). */
  readonly loginUri: string;
  /** The edge OIDC plugin's logout path. */
  readonly logoutUri: string;
  readonly navigate?: (url: string) => void;
}

/** Shown when the account has neither a name nor an email. */
export const UNNAMED_USER = "Signed-in user";

const isPlatformRole = (role: string): role is PlatformRole => PlatformRoleSchema.safeParse(role).success;

/**
 * The wire profile as the client's session model. Fields `/me` does not carry get their "none" value, and platform
 * roles the client does not know are dropped (they would unlock nothing here).
 */
export function toSession(profile: Profile): Session {
  const email = profile.email ?? null;
  return {
    sub: profile.userId,
    name: profile.displayName || email || UNNAMED_USER,
    email,
    memberships: profile.memberships.map((m) => ({ ...m, unit: null })),
    platformRoles: profile.platformRoles.filter(isPlatformRole),
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
      // The edge plugin ignores `?redirect=` and always lands on `/` (#80): keep the path in the tab instead; the `/`
      // route takes it once `/me` names someone.
      rememberReturnTo(returnTo);
      navigate(config.loginUri);
      return leave();
    },
    async logout() {
      navigate(config.logoutUri);
      return leave();
    },
  };
}
