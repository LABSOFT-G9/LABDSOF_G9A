import { createFileRoute, Outlet } from "@tanstack/react-router";
import { requireSession, useSession } from "@/features/auth";
import { AppShell } from "@/features/shell";

/**
 * Everything behind a sign-in. The guard is UX, not a data boundary: every API route enforces authentication and
 * authorisation itself (ADR-005).
 */
export const Route = createFileRoute("/_app")({
  beforeLoad: (options) => ({ session: requireSession(options) }),
  component: AppLayout,
});

function AppLayout() {
  const session = useSession();
  return (
    <AppShell session={session}>
      <Outlet />
    </AppShell>
  );
}
