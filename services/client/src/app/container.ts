import { httpAuthSession } from "@/adapters/http/auth-session";
import { createHttpClient } from "@/adapters/http/client";
import { httpIssueApi } from "@/adapters/http/issue-api";
import { createMockIdentity, mockAuthSession } from "@/adapters/mock/auth-session";
import { mockIssueApi } from "@/adapters/mock/issue-api";
import { createMockRuntime } from "@/adapters/mock/runtime";
import { systemClock } from "@/adapters/system-clock";
import type { AdapterMode, AppConfig } from "@/app/config";
import type { Services } from "@/ports/services";

type SwitchablePort = "auth" | "issues";

/**
 * Per-port override of VITE_ADAPTERS. Uncomment a line to wire one port differently from the rest, e.g. the real
 * Issue service while sign-in stays mocked. This is the only place that knows which adapter implements which port.
 */
const PORT_ADAPTERS: Partial<Record<SwitchablePort, AdapterMode>> = {
  // issues: "http",
};

/**
 * The composition root of the client (ADR-009): builds every adapter once and returns the ports. No DI library:
 * a function and a React context are enough. `overrides` replaces any port (tests pass fakes here).
 */
export function createContainer(
  config: AppConfig,
  overrides: Partial<Services> = {},
  portAdapters: Partial<Record<SwitchablePort, AdapterMode>> = PORT_ADAPTERS,
): Services {
  const modeOf = (port: SwitchablePort): AdapterMode => portAdapters[port] ?? config.adapters;
  const clock = overrides.clock ?? systemClock;

  const http = createHttpClient();
  const runtime = createMockRuntime({ latencyMs: config.mockLatencyMs, allowFailures: config.dev });
  const identity = createMockIdentity();
  const mockAuth = mockAuthSession(identity, runtime);

  const authIsMocked = modeOf("auth") === "mock";
  const auth = authIsMocked
    ? mockAuth
    : httpAuthSession(http, { loginUri: config.loginUri, logoutUri: config.logoutUri });

  const services: Services = {
    auth,
    issues: modeOf("issues") === "mock" ? mockIssueApi(identity, runtime, clock, authIsMocked) : httpIssueApi(http),
    clock,
    ...(authIsMocked ? { devPersonas: mockAuth } : {}),
  };
  return { ...services, ...overrides };
}
