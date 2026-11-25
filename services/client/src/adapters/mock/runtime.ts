import { ApiError } from "@/shared/lib/errors";

export interface MockRuntime {
  /** Wait like a network would. */
  wait(): Promise<void>;
  /** Wait like a network would, then run `work`, or fail when a failure is being simulated. */
  call<T>(work: () => T | Promise<T>): Promise<T>;
}

export interface MockRuntimeOptions {
  readonly latencyMs: number;
  /** Honour `?mockFail=<status>` (development builds only). */
  readonly allowFailures: boolean;
  readonly readSearch?: () => string;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Makes mock adapters behave like a network: latency, and failures on demand. While the address has
 * `?mockFail=<status>` (e.g. 503, 500, 404, 409), every mock data call fails with that status, so the error, retry
 * and degraded states can be seen without a backend. Remove the parameter to recover. 401 is not simulated here:
 * sign out instead.
 */
export function createMockRuntime({
  latencyMs,
  allowFailures,
  readSearch = () => window.location.search,
}: MockRuntimeOptions): MockRuntime {
  return {
    wait: () => sleep(latencyMs),
    async call(work) {
      await sleep(latencyMs);
      if (allowFailures) {
        const status = Number(new URLSearchParams(readSearch()).get("mockFail"));
        if (Number.isInteger(status) && status >= 400 && status <= 599 && status !== 401) {
          throw new ApiError(
            status,
            status === 503 ? "DEPENDENCY_UNAVAILABLE" : "SIMULATED_FAILURE",
            `Simulated failure (HTTP ${status}) from ?mockFail.`,
            "mock-correlation-id",
          );
        }
      }
      // Copies, so callers can never mutate the mock's state by accident (a real response is a fresh object too).
      return structuredClone(await work());
    },
  };
}
