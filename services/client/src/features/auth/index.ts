export { sessionKey, sessionQuery } from "@/features/auth/api";
export { LoginPage } from "@/features/auth/components/login-page";
export { SignOutButton } from "@/features/auth/components/sign-out-button";
export {
  hasBuildingRole,
  hasPlatformRole,
  isBuildingMember,
  requirePlatformRole,
  requireSession,
} from "@/features/auth/guards";
export { useSession } from "@/features/auth/hooks";
export { type AuthState, expireSession, loadAuth, signOut, startSession, watchSession } from "@/features/auth/session";
