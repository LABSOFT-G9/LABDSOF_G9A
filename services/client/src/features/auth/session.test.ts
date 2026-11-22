import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { createContainer } from "@/app/container";
import type { AppContext } from "@/app/context";
import { readSession, sessionKey, writeSession } from "@/features/auth/api";
import { expireSession, loadAuth, watchSession } from "@/features/auth/session";
import type { AuthSession, Session } from "@/ports/auth-session";
import { ApiError, ContractError, NetworkError } from "@/shared/lib/errors";
import { testConfig } from "@/test/render-app";

function contextWith(me: AuthSession["me"]): AppContext {
  const auth: AuthSession = { me, login: vi.fn(), logout: vi.fn() };
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return { queryClient, config: testConfig, services: createContainer(testConfig, { auth }, {}) };
}

const session: Session = {
  sub: "u",
  name: "U",
  email: "u@bmc.test",
  memberships: [],
  platformRoles: [],
  contractor: null,
};
const other: Session = { ...session, sub: "someone-else" };
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
/** Makes the cached session older than the query's 5-minute stale time. */
const age = (context: AppContext) =>
  context.queryClient.getQueryCache().find({ queryKey: sessionKey })!.setState({ dataUpdatedAt: 0 });

describe("loadAuth", () => {
  it("is signed-in when /me names someone", async () => {
    await expect(loadAuth(contextWith(async () => session))).resolves.toEqual({ kind: "signed-in", session });
  });

  it("is anonymous when there is no session", async () => {
    await expect(loadAuth(contextWith(async () => null))).resolves.toEqual({ kind: "anonymous" });
  });

  it.each([new NetworkError(), new ApiError(503, "DEPENDENCY_UNAVAILABLE", "down")])(
    "is unreachable when the API cannot answer and nothing is known (%s)",
    async (error) => {
      const me = vi.fn(async () => {
        throw error;
      });
      await expect(loadAuth(contextWith(me))).resolves.toEqual({ kind: "unreachable" });
      expect(me).toHaveBeenCalledOnce(); // no retries holding the navigation
    },
  );

  it("is failed, not a crash, when /me answers something unusable", async () => {
    const error = new ContractError("GET /api/issue/me", ["email: required"]);
    const state = await loadAuth(
      contextWith(async () => {
        throw error;
      }),
    );
    expect(state).toEqual({ kind: "failed", error });
  });

  it("reads a fresh session from memory", async () => {
    const me = vi.fn(async () => session);
    const context = contextWith(me);
    await loadAuth(context);
    await loadAuth(context);
    expect(me).toHaveBeenCalledTimes(1);
  });

  it("asks again once the session is stale, and notices that it ended", async () => {
    const me = vi.fn<AuthSession["me"]>(async () => session);
    const context = contextWith(me);
    await loadAuth(context);
    age(context);
    me.mockResolvedValueOnce(null);
    await expect(loadAuth(context)).resolves.toEqual({ kind: "anonymous" });
    expect(me).toHaveBeenCalledTimes(2);
  });

  it("keeps the known session through a network blip", async () => {
    const me = vi.fn<AuthSession["me"]>(async () => session);
    const context = contextWith(me);
    await loadAuth(context);
    age(context);
    me.mockRejectedValueOnce(new NetworkError());
    await expect(loadAuth(context)).resolves.toEqual({ kind: "signed-in", session });
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

  it("still re-runs the guards when a refetch already set the session to null", async () => {
    const context = contextWith(async () => null);
    writeSession(context.queryClient, null);
    const invalidate = vi.fn(async () => undefined);
    await expireSession(context, invalidate);
    expect(invalidate).toHaveBeenCalledOnce();
  });
});

describe("watchSession", () => {
  async function signedIn(next: () => Promise<Session | null>) {
    const me = vi.fn<AuthSession["me"]>(async () => session);
    const context = contextWith(me);
    await loadAuth(context);
    const invalidate = vi.fn(async () => undefined);
    watchSession(context, invalidate);
    context.queryClient.setQueryData(["issues", "list"], [{ id: "private" }]);
    me.mockImplementationOnce(next);
    return { context, invalidate };
  }

  it("wipes data and re-runs the guards when a refetch finds the session ended", async () => {
    const { context, invalidate } = await signedIn(async () => null);
    await context.queryClient.refetchQueries({ queryKey: sessionKey });
    await flush();
    expect(context.queryClient.getQueryData(["issues", "list"])).toBeUndefined();
    expect(readSession(context.queryClient)).toBeNull();
    expect(invalidate).toHaveBeenCalledOnce();
  });

  it("wipes the previous person's data when another account signed in", async () => {
    const { context, invalidate } = await signedIn(async () => other);
    await context.queryClient.refetchQueries({ queryKey: sessionKey });
    await flush();
    expect(context.queryClient.getQueryData(["issues", "list"])).toBeUndefined();
    expect(readSession(context.queryClient)).toEqual(other);
    expect(invalidate).toHaveBeenCalledOnce();
  });

  it("does nothing when the refetch names the same person", async () => {
    const { context, invalidate } = await signedIn(async () => session);
    await context.queryClient.refetchQueries({ queryKey: sessionKey });
    await flush();
    expect(context.queryClient.getQueryData(["issues", "list"])).toBeDefined();
    expect(invalidate).not.toHaveBeenCalled();
  });

  it("ignores our own writes (sign-in, sign-out)", async () => {
    const { context, invalidate } = await signedIn(async () => session);
    writeSession(context.queryClient, null);
    await flush();
    expect(invalidate).not.toHaveBeenCalled();
  });
});
