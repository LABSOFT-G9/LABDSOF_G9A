import { createMemoryHistory } from "@tanstack/react-router";
import { render } from "@testing-library/react";
import type { AppConfig } from "@/app/config";
import { AppRouterProvider, createAppRouter } from "@/app/router";
import type { Services } from "@/ports/services";
import { ThemeProvider } from "@/shared/theme";
import { TooltipProvider } from "@/shared/ui/tooltip";

export const testConfig: AppConfig = {
  adapters: "mock",
  mockLatencyMs: 0,
  loginUri: "/oidc/login",
  logoutUri: "/logout",
  dev: false,
};

/** The whole app (real router, real container) at `path`, over mock adapters unless `services` replaces ports. */
export function renderApp(
  path: string,
  { services, config }: { services?: Partial<Services>; config?: Partial<AppConfig> } = {},
) {
  const router = createAppRouter({
    config: { ...testConfig, ...config },
    ...(services ? { services } : {}),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  const view = render(
    <ThemeProvider>
      <TooltipProvider>
        <AppRouterProvider router={router} />
      </TooltipProvider>
    </ThemeProvider>,
  );
  return { router, ...view };
}
