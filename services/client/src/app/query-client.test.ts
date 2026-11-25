import { afterEach, describe, expect, it, vi } from "vitest";
import { retryPolicy } from "@/app/query-client";
import { ApiError, ContractError, NetworkError } from "@/shared/lib/errors";

describe("retryPolicy", () => {
  afterEach(() => vi.unstubAllGlobals());

  it.each([
    [new NetworkError(), true],
    [new ApiError(503, "DEPENDENCY_UNAVAILABLE", "down"), true],
    [new ApiError(429, "RATE_LIMITED", "slow down"), true],
    [new ApiError(404, "NOT_FOUND", "gone"), false],
    [new ApiError(409, "VERSION_CONFLICT", "changed"), false],
    [new ContractError("GET /x", ["id: required"]), false],
  ])("retries %s: %s", (error, expected) => {
    expect(retryPolicy(0, error)).toBe(expected);
  });

  it("gives up after three attempts", () => {
    expect(retryPolicy(3, new NetworkError())).toBe(false);
  });

  it("never retries while offline", () => {
    vi.stubGlobal("navigator", { onLine: false });
    expect(retryPolicy(0, new ApiError(503, "DEPENDENCY_UNAVAILABLE", "down"))).toBe(false);
  });
});
