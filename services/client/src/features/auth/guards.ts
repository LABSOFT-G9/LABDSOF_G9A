import { notFound, redirect } from "@tanstack/react-router";
import type { BuildingRole, PlatformRole } from "@/contracts/issue";
import type { AuthState } from "@/features/auth/session";
import type { Session } from "@/ports/auth-session";

/*
 * Guards are UX, never the security control: every service authenticates and authorises each request itself, and
 * answers 404 for what the caller may not see (ADR-005).
 */

/** Everything under the `_app` layout: no session → the login page, which returns here afterwards. */
export function requireSession({
  context,
  location,
}: {
  context: { auth: AuthState };
  location: { href: string };
}): Session {
  if (context.auth.kind !== "signed-in") {
    throw redirect({ to: "/login", search: { redirect: location.href } });
  }
  return context.auth.session;
}

/** Areas a role does not have are "not found", so their existence is not revealed. */
export function requirePlatformRole(session: Session, role: PlatformRole): void {
  if (!hasPlatformRole(session, role)) throw notFound();
}

export const hasPlatformRole = (session: Session | null | undefined, role: PlatformRole): boolean =>
  !!session && session.platformRoles.includes(role);

/** Roles are per building: "administrator of building X", never "an administrator". */
export const hasBuildingRole = (session: Session | null | undefined, buildingId: string, role: BuildingRole): boolean =>
  !!session && session.memberships.some((m) => m.buildingId === buildingId && m.role === role);

export const isBuildingMember = (session: Session | null | undefined): boolean =>
  !!session && session.memberships.length > 0;
