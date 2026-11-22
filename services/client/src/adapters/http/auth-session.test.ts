import { describe, expect, it, vi } from "vitest";
import { httpAuthSession } from "@/adapters/http/auth-session";
import { createHttpClient } from "@/adapters/http/client";

const me = {
  sub: "u1",
  name: "Demo",
  email: "demo@bmc.test",
  memberships: [],
  platformRoles: [],
  contractor: null,
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
  it("loads the session from GET /api/issue/me", async () => {
    const { auth, fetcher } = setup(Response.json(me));
    await expect(auth.me()).resolves.toEqual(me);
    expect(fetcher).toHaveBeenCalledWith("/api/issue/me", expect.objectContaining({ method: "GET" }));
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
