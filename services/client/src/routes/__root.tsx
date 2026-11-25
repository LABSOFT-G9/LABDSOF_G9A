import { createRootRouteWithContext, Outlet, useRouter } from "@tanstack/react-router";
import { useEffect } from "react";
import type { AppContext } from "@/app/context";
import { type AuthState, loadAuth } from "@/features/auth";
import { UpdatePrompt } from "@/features/pwa";
import { NotFoundFallback } from "@/shared/components/fallbacks";
import { useFocusOnNavigation } from "@/shared/hooks/use-focus-on-navigation";
import { Toaster } from "@/shared/ui/sonner";

export interface RouterContext extends AppContext {
  readonly auth: AuthState;
}

/** The root resolves the session before any child guard runs, so no guard ever sees "still loading". */
export const Route = createRootRouteWithContext<RouterContext>()({
  beforeLoad: async ({ context }) => ({ auth: await loadAuth(context) }),
  component: Root,
  notFoundComponent: NotFoundFallback,
});

function Root() {
  const router = useRouter();
  useFocusOnNavigation();
  // Connection back: ask again (guards, loaders, stale queries).
  useEffect(() => {
    const online = () => void router.invalidate();
    window.addEventListener("online", online);
    return () => window.removeEventListener("online", online);
  }, [router]);
  return (
    <>
      <Outlet />
      <UpdatePrompt />
      <Toaster position="top-center" closeButton />
    </>
  );
}
