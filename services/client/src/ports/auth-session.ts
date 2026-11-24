import type { BuildingRole, PlatformRole } from "@/contracts/issue";

/** One building the signed-in person belongs to, and their role there. */
export interface SessionMembership {
  readonly membershipId: string;
  readonly buildingId: string;
  readonly buildingName: string;
  /** Not in the Issue service's `/me` yet, so always `null` over HTTP. */
  readonly unit: string | null;
  readonly role: BuildingRole;
}

/**
 * Who is signed in, as the client sees it. Built from the Issue service's `GET /me` (`ProfileSchema`) by the auth
 * adapter. `/me` does not yet say whether the person is platform staff or a contractor, nor give an email (asked on
 * EN-02-04 #80), so over HTTP `email` is `null`, `platformRoles` is empty and `contractor` is `null`; the mock
 * personas fill them so those screens can be built.
 */
export interface Session {
  /** The identity provider's subject; the Issue service calls it `userId`. */
  readonly sub: string;
  readonly name: string;
  readonly email: string | null;
  readonly memberships: readonly SessionMembership[];
  readonly platformRoles: readonly PlatformRole[];
  readonly contractor: { readonly id: string; readonly status: "pending" | "active" | "suspended" } | null;
}

/**
 * The authentication facade (ADR-005, ADR-009). The browser never holds a token: with the BFF at the edge, the
 * session is an HttpOnly cookie the client cannot read, so all it can do is ask who it is and navigate.
 */
export interface AuthSession {
  /** The signed-in user, or `null` when there is no session. Throws `NetworkError`/`ApiError` when it cannot tell. */
  me(): Promise<Session | null>;
  /**
   * Start a sign-in that returns to `returnTo` (a same-origin path). The HTTP adapter leaves the page and its
   * promise never settles, so code after `await login()` runs only with adapters that stay (the mock).
   */
  login(returnTo: string): Promise<void>;
  /** End the session. Like `login`, the HTTP adapter leaves the page and never settles. */
  logout(): Promise<void>;
}

/** A synthetic user to sign in as. Mock adapter only: there is no such thing with a real identity provider. */
export interface Persona {
  readonly id: string;
  readonly label: string;
  readonly description: string;
}

/** Present only when the mock adapters are wired: lets the login page offer synthetic personas. */
export interface DevPersonas {
  list(): readonly Persona[];
  signInAs(personaId: string): Promise<void>;
}
