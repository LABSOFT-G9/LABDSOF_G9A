import { QueryClientProvider } from "@tanstack/react-query";
import { createRouter, type RouterHistory, RouterProvider } from "@tanstack/react-router";
import { readConfig, type AppConfig } from "@/app/config";
import { createContainer } from "@/app/container";
import { type AppContext, ServicesProvider } from "@/app/context";
import { createQueryClient } from "@/app/query-client";
import { expireSession, watchSession } from "@/features/auth";
import { routeTree } from "@/routeTree.gen";
import { ErrorFallback, NotFoundFallback, PendingFallback } from "@/shared/components/fallbacks";
import type { Services } from "@/ports/services";

export interface CreateAppRouterOptions {
  readonly config?: AppConfig;
  /** Replace ports (tests). */
  readonly services?: Partial<Services>;
  readonly history?: RouterHistory;
}

/** The composition root of the web app: one container, one query client, one router. */
export function createAppRouter({ config = readConfig(), services, history }: CreateAppRouterOptions = {}) {
  const container = createContainer(config, services);
  const queryClient = createQueryClient({
    // Global 401 policy: the session is gone, so is the user's data; every guard re-runs (to /login?redirect=…).
    onUnauthenticated: () => void expireSession(context, () => router.invalidate()),
  });
  const context: AppContext = { queryClient, services: container, config };

  const router = createRouter({
    routeTree,
    context: { ...context, auth: { kind: "anonymous" } },
    ...(history ? { history } : {}),
    // TanStack Query owns freshness; the router must not keep its own cache on top.
    defaultPreloadStaleTime: 0,
    defaultPreload: "intent",
    defaultPendingMs: 400,
    defaultPendingMinMs: 300,
    defaultPendingComponent: PendingFallback,
    defaultErrorComponent: ErrorFallback,
    defaultNotFoundComponent: NotFoundFallback,
    scrollRestoration: true,
  });
  // The session query refetches on its own (window focus, the re-ask in loadAuth): react when it names someone else.
  watchSession(context, () => router.invalidate());
  return router;
}

export type AppRouter = ReturnType<typeof createAppRouter>;

export function AppRouterProvider({ router }: { router: AppRouter }) {
  const { queryClient, services, config } = router.options.context;
  return (
    <ServicesProvider services={services} config={config}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ServicesProvider>
  );
}

declare module "@tanstack/react-router" {
  interface Register {
    router: AppRouter;
  }
}
