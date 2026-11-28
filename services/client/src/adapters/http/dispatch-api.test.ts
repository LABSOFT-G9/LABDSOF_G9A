import { describe, expect, it, vi } from "vitest";
import { createHttpClient } from "@/adapters/http/client";
import { httpDispatchApi } from "@/adapters/http/dispatch-api";
import { ContractError } from "@/shared/lib/errors";
import { intervention, WINDOW } from "@/test/dispatch-fixtures";

function setup(body: unknown, status = 200) {
  const fetcher = vi.fn(async () => Response.json(body, { status }));
  return { api: httpDispatchApi(createHttpClient("", fetcher)), fetcher };
}

const lastCall = (fetcher: ReturnType<typeof setup>["fetcher"]) => {
  const [url, init] = fetcher.mock.lastCall as unknown as [string, RequestInit];
  return { url, init, headers: new Headers(init.headers) };
};

describe("httpDispatchApi (US-08 booking)", () => {
  it("lists an issue's interventions and drops the fields the booking screens do not read", async () => {
    // What Dispatch sends an administrator also has the proposals and completions (US-07, US-09).
    const wire = { ...intervention(), proposal: { proposalVersion: 1 }, proposals: [], completions: [] };
    const { api, fetcher } = setup({ items: [wire] });
    const list = await api.listInterventions("i 1");
    expect(lastCall(fetcher).url).toBe("/api/dispatch/interventions?issueId=i+1");
    expect(list.items[0]).toEqual(intervention());
    expect(list.items[0]).not.toHaveProperty("proposals");
  });

  it("refuses a booking state it does not know, instead of showing a wrong one", async () => {
    const { api } = setup({
      items: [intervention({ booking: { ...intervention().booking!, state: "booked" as never } })],
    });
    await expect(api.listInterventions("i1")).rejects.toBeInstanceOf(ContractError);
  });

  it("confirms by hand with If-Match, the caller's Idempotency-Key and the CSRF header", async () => {
    const answer = { interventionId: "v/1", state: "booked", version: 5, bookingState: "confirmed" };
    const { api, fetcher } = setup(answer);
    const confirmation = { window: WINDOW, source: "phone" as const, reason: "Agreed by phone with the contractor." };
    await expect(api.confirmManually("v/1", 4, confirmation, "key-1")).resolves.toEqual(answer);
    const { url, init, headers } = lastCall(fetcher);
    expect(url).toBe("/api/dispatch/interventions/v%2F1/manual-confirmation");
    expect(init.method).toBe("POST");
    expect(headers.get("If-Match")).toBe('"4"');
    expect(headers.get("Idempotency-Key")).toBe("key-1");
    expect(headers.get("X-BMC-CSRF")).toBeTruthy();
    expect(JSON.parse(init.body as string)).toEqual(confirmation);
  });
});
