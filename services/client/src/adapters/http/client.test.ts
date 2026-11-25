import { describe, expect, it, vi } from "vitest";
import { z } from "zod";
import { createHttpClient, CSRF_HEADER, type Fetcher } from "@/adapters/http/client";
import { ApiError, ContractError, NetworkError } from "@/shared/lib/errors";

const json = (body: unknown, init: ResponseInit = {}) =>
  new Response(JSON.stringify(body), { headers: { "Content-Type": "application/json" }, ...init });

function client(response: Response | Error) {
  const fetcher = vi.fn<Fetcher>(async () => {
    if (response instanceof Error) throw response;
    return response;
  });
  const headersOf = () => new Headers(fetcher.mock.calls[0]?.[1]?.headers);
  return { http: createHttpClient("", fetcher), fetcher, headersOf };
}

const Thing = z.object({ id: z.string() });

describe("createHttpClient", () => {
  it("parses a 2xx body with its contract", async () => {
    const { http, fetcher } = client(json({ id: "a", extra: true }));
    await expect(http.get("/api/issue/things/a", Thing)).resolves.toEqual({ id: "a" });
    expect(fetcher.mock.calls[0]?.[1]).toMatchObject({ method: "GET", credentials: "same-origin", cache: "no-store" });
  });

  it("sends no CSRF or idempotency header on reads, and a correlation id always", async () => {
    const { http, headersOf } = client(json({ id: "a" }));
    await http.get("/x", Thing);
    expect(headersOf().has(CSRF_HEADER)).toBe(false);
    expect(headersOf().has("Idempotency-Key")).toBe(false);
    expect(headersOf().get("X-Correlation-Id")).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("adds the CSRF header, an Idempotency-Key and JSON on a POST", async () => {
    const { http, fetcher, headersOf } = client(json({ id: "a" }, { status: 201 }));
    await http.request("POST", "/x", { schema: Thing, body: { name: "n" } });
    expect(headersOf().get(CSRF_HEADER)).toBe("1");
    expect(headersOf().get("Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/);
    expect(headersOf().get("Content-Type")).toBe("application/json");
    expect(fetcher.mock.calls[0]?.[1]?.body).toBe(JSON.stringify({ name: "n" }));
  });

  it("reuses a given Idempotency-Key and sends If-Match", async () => {
    const { http, headersOf } = client(new Response(null, { status: 204 }));
    await http.request("POST", "/x", { idempotencyKey: "key-1", ifMatch: 7 });
    expect(headersOf().get("Idempotency-Key")).toBe("key-1");
    expect(headersOf().get("If-Match")).toBe('"7"');
  });

  it("sends the CSRF header but no Idempotency-Key on other writes", async () => {
    const { http, headersOf } = client(new Response(null, { status: 204 }));
    await http.request("DELETE", "/x");
    expect(headersOf().get(CSRF_HEADER)).toBe("1");
    expect(headersOf().has("Idempotency-Key")).toBe(false);
  });

  it("turns a problem+json body into an ApiError with its code and correlation id", async () => {
    const { http } = client(
      json(
        {
          title: "Conflict",
          detail: "The issue changed.",
          code: "VERSION_CONFLICT",
          correlationId: "c-1",
          currentVersion: 4,
        },
        { status: 409 },
      ),
    );
    const error = await http.get("/x", Thing).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 409,
      code: "VERSION_CONFLICT",
      message: "The issue changed.",
      correlationId: "c-1",
      extensions: { currentVersion: 4 },
    });
  });

  it("turns a non-problem error page into a generic ApiError", async () => {
    const { http } = client(new Response("<html>Bad gateway</html>", { status: 502 }));
    await expect(http.get("/x", Thing)).rejects.toMatchObject({ status: 502, code: "UNKNOWN_ERROR" });
  });

  it("turns a failed fetch into a NetworkError", async () => {
    const { http } = client(new TypeError("Failed to fetch"));
    await expect(http.get("/x", Thing)).rejects.toBeInstanceOf(NetworkError);
  });

  it("rejects a 2xx body that breaks the contract", async () => {
    const { http } = client(json({ id: 1 }));
    const error = await http.get("/x", Thing).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ContractError);
    expect((error as ContractError).issues[0]).toContain("id");
  });
});
