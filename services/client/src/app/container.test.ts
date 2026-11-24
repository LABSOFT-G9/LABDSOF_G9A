import { describe, expect, it } from "vitest";
import { createContainer } from "@/app/container";
import { testConfig } from "@/test/render-app";
import { stubIssueApi } from "@/test/fakes";

describe("createContainer", () => {
  it("wires mock adapters and offers dev personas in mock mode", () => {
    const services = createContainer(testConfig, {}, {});
    expect(services.devPersonas?.list().map((p) => p.id)).toEqual([
      "resident-a1",
      "resident-a2",
      "resident-b1",
      "administrator-a",
      "administrator-b",
      "contractor",
      "platform-staff",
    ]);
  });

  it("wires HTTP adapters and no personas in http mode", () => {
    const services = createContainer({ ...testConfig, adapters: "http" }, {}, {});
    expect(services.devPersonas).toBeUndefined();
  });

  it("lets one port differ from the rest", async () => {
    const services = createContainer({ ...testConfig, adapters: "http" }, {}, { issues: "mock" });
    // Issues mocked while sign-in is real: no persona to filter by, so every synthetic issue is listed.
    await expect(services.issues.list()).resolves.toHaveLength(4);
    expect(services.devPersonas).toBeUndefined();
  });

  it("lets tests replace any port", async () => {
    const issues = stubIssueApi({ list: async () => [] });
    expect(createContainer(testConfig, { issues }, {}).issues).toBe(issues);
  });
});
