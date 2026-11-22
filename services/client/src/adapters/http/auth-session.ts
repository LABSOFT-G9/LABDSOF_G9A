import type { HttpClient } from "@/adapters/http/client";
import { MeSchema } from "@/contracts/issue";
import type { AuthSession } from "@/ports/auth-session";
import { isApiError } from "@/shared/lib/errors";

export interface EdgeAuthConfig {
  /** The edge OIDC plugin's LoginUri (ADR-005). */
  readonly loginUri: string;
  /** The edge OIDC plugin's logout path. */
  readonly logoutUri: string;
  readonly navigate?: (url: string) => void;
}

/**
 * Session through the Backend-for-Frontend at the edge (ADR-005): Traefik's OIDC plugin holds the tokens and the
 * browser only an HttpOnly cookie. `GET /api/issue/me` answers who it is; a 401 means "no session".
 */
export function httpAuthSession(http: HttpClient, config: EdgeAuthConfig): AuthSession {
  const navigate = config.navigate ?? ((url: string) => window.location.assign(url));
  return {
    async me() {
      try {
        return await http.get("/api/issue/me", MeSchema);
      } catch (error) {
        if (isApiError(error, 401)) return null;
        throw error;
      }
    },
    async login(returnTo) {
      // How the plugin returns the user to `returnTo` is a detail of spike item 3; the parameter name may change.
      navigate(`${config.loginUri}?redirect=${encodeURIComponent(returnTo)}`);
    },
    async logout() {
      navigate(config.logoutUri);
    },
  };
}
