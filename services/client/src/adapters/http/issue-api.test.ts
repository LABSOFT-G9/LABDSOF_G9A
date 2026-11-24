import { describe, expect, it, vi } from "vitest";
import { createHttpClient } from "@/adapters/http/client";
import { httpIssueApi } from "@/adapters/http/issue-api";

const receipt = {
  reportId: "r1",
  issueId: "i1",
  state: "received",
  version: 1,
  submittedAt: "2026-11-24T10:00:00Z",
};
const summary = {
  reportId: "r1",
  issueId: "i1",
  buildingId: "b1",
  areaId: "a1",
  areaLabel: "Garage level -1",
  source: "resident",
  authoredByMe: true,
  submittedAt: "2026-11-24T10:00:00.123Z",
  issue: { state: "received", version: 1 },
};

function setup(body: unknown, status = 200) {
  const fetcher = vi.fn(async () => Response.json(body, { status }));
  return { api: httpIssueApi(createHttpClient("", fetcher)), fetcher };
}

const lastCall = (fetcher: ReturnType<typeof setup>["fetcher"]) => {
  const [url, init] = fetcher.mock.lastCall as unknown as [string, RequestInit];
  return { url, init, headers: new Headers(init.headers) };
};

describe("httpIssueApi (US-01 reports)", () => {
  it("submits a report with the caller's Idempotency-Key and the CSRF header", async () => {
    const { api, fetcher } = setup(receipt, 201);
    const report = { buildingId: "b1", areaId: "a1", text: "The garage door does not close." };
    await expect(api.submitReport(report, "key-1")).resolves.toEqual(receipt);
    const { url, init, headers } = lastCall(fetcher);
    expect(url).toBe("/api/issue/reports");
    expect(init.method).toBe("POST");
    expect(headers.get("Idempotency-Key")).toBe("key-1");
    expect(headers.get("X-BMC-CSRF")).toBeTruthy();
    expect(JSON.parse(init.body as string)).toEqual(report);
  });

  it("lists reports with paging and an optional building filter", async () => {
    const { api, fetcher } = setup({ items: [summary], page: 1, size: 20, hasMore: false });
    await expect(api.listReports({ page: 1, buildingId: "b 1" })).resolves.toMatchObject({ items: [summary] });
    expect(lastCall(fetcher).url).toBe("/api/issue/reports?page=1&buildingId=b+1");
    await api.listReports({});
    expect(lastCall(fetcher).url).toBe("/api/issue/reports?page=0");
  });

  it("reads one report and a building's areas, encoding path segments", async () => {
    const { api, fetcher } = setup({ ...summary, text: "Some text here" });
    await api.getReport("r/1");
    expect(lastCall(fetcher).url).toBe("/api/issue/reports/r%2F1");

    const areas = setup([{ areaId: "a1", label: "Garage", kind: "common_area" }]);
    await expect(areas.api.listAreas("b1")).resolves.toHaveLength(1);
    expect(lastCall(areas.fetcher).url).toBe("/api/issue/buildings/b1/areas");
  });

  it("keeps the service's field errors on a 400", async () => {
    const problem = { code: "VALIDATION_FAILED", errors: [{ field: "text", reason: "too_short" }] };
    const { api } = setup(problem, 400);
    await expect(api.submitReport({ buildingId: "b", areaId: "a", text: "x" }, "k")).rejects.toMatchObject({
      status: 400,
      code: "VALIDATION_FAILED",
      extensions: { errors: [{ field: "text", reason: "too_short" }] },
    });
  });
});
