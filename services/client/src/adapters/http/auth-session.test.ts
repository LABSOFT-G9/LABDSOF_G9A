import { describe, expect, it, vi } from "vitest";
import { httpAuthSession } from "@/adapters/http/auth-session";
import { createHttpClient } from "@/adapters/http/client";

/** `GET /me` exactly as the Issue service answers it (ProfileResponse, services/issue README). */
const me = {
  userId: "0199f2a0-0000-7000-8000-000000000101",
  displayName: "Synthetic Resident A1",
  memberships: [
    {
      membershipId: "0199f2a0-0000-7000-8000-00000000d001",
      buildingId: "0199f2a0-0000-7000-8000-00000000b001",
      buildingName: "Synthetic Building A",
      role: "resident",
    },
  ],
};

function setup(response: Response) {
  const navigate = vi.fn();
  const fetcher = vi.fn(async () => response);
  const auth = httpAuthSession(createHttpClient("", fetcher), {
    loginUri: "/oidc/login",
    logoutUri: "/logout",
    navigate,
  });
  return { auth, navigate, fetcher };
}

describe("httpAuthSession (BFF at the edge)", () => {
  it("loads the session from GET /api/issue/me and maps it to the session model", async () => {
    const { auth, fetcher } = setup(Response.json(me));
    await expect(auth.me()).resolves.toEqual({
      sub: me.userId,
      name: me.displayName,
      email: null,
      memberships: [{ ...me.memberships[0], unit: null }],
      platformRoles: [],
      contractor: null,
    });
    expect(fetcher).toHaveBeenCalledWith("/api/issue/me", expect.objectContaining({ method: "GET" }));
  });

  it("rejects a /me that breaks the contract instead of guessing", async () => {
    const { auth } = setup(Response.json({ sub: "u1", name: "Old shape", memberships: [] }));
    await expect(auth.me()).rejects.toMatchObject({ name: "ContractError" });
  });

  it("rejects an unknown building role", async () => {
    const { auth } = setup(Response.json({ ...me, memberships: [{ ...me.memberships[0], role: "owner" }] }));
    await expect(auth.me()).rejects.toMatchObject({ name: "ContractError" });
  });

  it("treats 401 as no session", async () => {
    const { auth } = setup(new Response(null, { status: 401 }));
    await expect(auth.me()).resolves.toBeNull();
  });

  it("propagates other failures (the session is unknown, not absent)", async () => {
    const { auth } = setup(new Response(null, { status: 503 }));
    await expect(auth.me()).rejects.toMatchObject({ status: 503 });
  });

  it("navigates to the edge login and logout paths, and never settles (the page is leaving)", async () => {
    const { auth, navigate } = setup(new Response(null, { status: 204 }));
    const settled = vi.fn();
    void auth.login("/issues?x=1").then(settled, settled);
    expect(navigate).toHaveBeenLastCalledWith("/oidc/login?redirect=%2Fissues%3Fx%3D1");
    void auth.logout().then(settled, settled);
    expect(navigate).toHaveBeenLastCalledWith("/logout");
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(settled).not.toHaveBeenCalled();
  });
});
