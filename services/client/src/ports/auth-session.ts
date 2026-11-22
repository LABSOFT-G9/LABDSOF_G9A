import type { Me } from "@/contracts/issue";

/** Who is signed in, as the client sees it. Today it is exactly the Issue service's `/me`. */
export type Session = Me;

/**
 * The authentication facade (ADR-005, ADR-009). The browser never holds a token: with the BFF at the edge, the
 * session is an HttpOnly cookie the client cannot read, so all it can do is ask who it is and navigate.
 */
export interface AuthSession {
  /** The signed-in user, or `null` when there is no session. Throws `NetworkError`/`ApiError` when it cannot tell. */
  me(): Promise<Session | null>;
  /** Start a sign-in that returns to `returnTo` (a same-origin path). The HTTP adapter leaves the page. */
  login(returnTo: string): Promise<void>;
  /** End the session. The HTTP adapter leaves the page. */
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
