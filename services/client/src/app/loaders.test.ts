import { isNotFound } from "@tanstack/react-router";
import { describe, expect, it } from "vitest";
import { routeData } from "@/app/loaders";
import { ApiError } from "@/shared/lib/errors";

describe("routeData", () => {
  it("passes data through", async () => {
    await expect(routeData(Promise.resolve(42))).resolves.toBe(42);
  });

  it("turns an API 404 into the router's not-found", async () => {
    const thrown = await routeData(Promise.reject(new ApiError(404, "NOT_FOUND", "gone"))).catch((e: unknown) => e);
    expect(isNotFound(thrown)).toBe(true);
  });

  it("lets every other error reach the route's error component", async () => {
    const error = new ApiError(403, "FORBIDDEN", "no");
    await expect(routeData(Promise.reject(error))).rejects.toBe(error);
  });
});
