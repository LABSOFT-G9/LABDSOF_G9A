import { describe, expect, it, vi } from "vitest";
import { createHttpClient, type Fetcher } from "@/adapters/http/client";
import { httpExampleApi } from "@/adapters/http/example-api";
import { ApiError } from "@/shared/lib/errors";

const emily = {
  id: 1,
  firstName: "Emily",
  lastName: "Johnson",
  email: "emily.johnson@x.dummyjson.com",
  image: "https://dummyjson.com/icon/emilys/128",
  company: { name: "Dooley, Kozey and Cronin", title: "Sales Manager", department: "Engineering" },
};

/** A fresh Response per call: a body can be read only once. */
function setup(response: Response) {
  const fetcher = vi.fn<Fetcher>(async () => response.clone());
  return { api: httpExampleApi(createHttpClient("", fetcher)), fetcher };
}

describe("httpExampleApi (reference adapter)", () => {
  it("maps the wire DTO to the UI model, making the avatar same-origin", async () => {
    const { api } = setup(Response.json(emily));
    await expect(api.getPerson(1)).resolves.toEqual({
      id: 1,
      name: "Emily Johnson",
      email: "emily.johnson@x.dummyjson.com",
      avatarUrl: "/api/example/icon/emilys/128",
      jobTitle: "Sales Manager",
      company: "Dooley, Kozey and Cronin",
    });
  });

  it("lists through /users, and through /users/search when there is a query", async () => {
    const page = { users: [emily], total: 1, skip: 0, limit: 24 };
    const { api, fetcher } = setup(Response.json(page));
    await api.listPeople({});
    await api.listPeople({ search: " emily " });
    expect(String(fetcher.mock.calls[0]?.[0])).toMatch(/^\/api\/example\/users\?limit=24&select=/);
    expect(String(fetcher.mock.calls[1]?.[0])).toMatch(/^\/api\/example\/users\/search\?.*q=emily/);
  });

  it("maps empty fields of a created person to null", async () => {
    const created = { ...emily, id: 209, image: "", company: { name: "", title: "", department: "" } };
    const { api } = setup(Response.json(created));
    await expect(
      api.createPerson({ firstName: "Emily", lastName: "Johnson", email: "e@x.test" }),
    ).resolves.toMatchObject({ id: 209, avatarUrl: null, jobTitle: null, company: null });
  });

  it("surfaces an unknown id as a 404 ApiError (the route turns it into not-found)", async () => {
    const { api } = setup(Response.json({ message: "User with id '9' not found" }, { status: 404 }));
    const error = await api.getPerson(9).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 404 });
  });
});
