import { describe, expect, it } from "vitest";
import { createMockRuntime } from "@/adapters/mock/runtime";
import { ApiError } from "@/shared/lib/errors";

describe("createMockRuntime", () => {
  it("returns a copy of the result, so callers cannot mutate mock state", async () => {
    const state = { items: [1] };
    const runtime = createMockRuntime({ latencyMs: 0, allowFailures: false, readSearch: () => "" });
    const result = await runtime.call(() => state);
    result.items.push(2);
    expect(state.items).toEqual([1]);
  });

  it("fails with the status in ?mockFail while it is present", async () => {
    const runtime = createMockRuntime({ latencyMs: 0, allowFailures: true, readSearch: () => "?mockFail=503" });
    const error = await runtime.call(() => 1).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 503, code: "DEPENDENCY_UNAVAILABLE" });
  });

  it.each(["?mockFail=401", "?mockFail=abc", "?mockFail=200", ""])("ignores %s", async (search) => {
    const runtime = createMockRuntime({ latencyMs: 0, allowFailures: true, readSearch: () => search });
    await expect(runtime.call(() => 1)).resolves.toBe(1);
  });

  it("ignores ?mockFail outside development builds", async () => {
    const runtime = createMockRuntime({ latencyMs: 0, allowFailures: false, readSearch: () => "?mockFail=500" });
    await expect(runtime.call(() => 1)).resolves.toBe(1);
  });
});
