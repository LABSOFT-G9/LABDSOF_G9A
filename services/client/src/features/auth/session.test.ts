import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { createContainer } from "@/app/container";
import type { AppContext } from "@/app/context";
import { readSession, writeSession } from "@/features/auth/api";
import { expireSession, loadAuth } from "@/features/auth/session";
import type { AuthSession } from "@/ports/auth-session";
import { ApiError, NetworkError } from "@/shared/lib/errors";
import { testConfig } from "@/test/render-app";

function contextWith(me: AuthSession["me"]): AppContext {
  const auth: AuthSession = { me, login: vi.fn(), logout: vi.fn() };
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return { queryClient, config: testConfig, services: createContainer(testConfig, { auth }, {}) };
}

const session = { sub: "u", name: "U", email: "u@bmc.test", memberships: [], platformRoles: [], contractor: null };

describe("loadAuth", () => {
  it("is signed-in when /me names someone", async () => {
    await expect(loadAuth(contextWith(async () => session))).resolves.toEqual({ kind: "signed-in", session });
  });

  it("is anonymous when there is no session", async () => {
    await expect(loadAuth(contextWith(async () => null))).resolves.toEqual({ kind: "anonymous" });
  });

  it.each([new NetworkError(), new ApiError(503, "DEPENDENCY_UNAVAILABLE", "down")])(
    "is unreachable when the API cannot answer (%s)",
    async (error) => {
      await expect(
        loadAuth(
          contextWith(async () => {
            throw error;
          }),
        ),
      ).resolves.toEqual({ kind: "unreachable" });
    },
  );

  it("asks the server once, then reads memory", async () => {
    const me = vi.fn(async () => session);
    const context = contextWith(me);
    await loadAuth(context);
    await loadAuth(context);
    expect(me).toHaveBeenCalledTimes(1);
  });
});

describe("expireSession", () => {
  it("wipes the user's cached data, sets the session to null and re-runs the guards", async () => {
    const context = contextWith(async () => session);
    writeSession(context.queryClient, session);
    context.queryClient.setQueryData(["issues", "list"], [{ id: "private" }]);
    const invalidate = vi.fn(async () => undefined);

    await expireSession(context, invalidate);

    expect(context.queryClient.getQueryData(["issues", "list"])).toBeUndefined();
    expect(readSession(context.queryClient)).toBeNull();
    expect(invalidate).toHaveBeenCalledOnce();
  });
});
