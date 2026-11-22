import { describe, expect, it } from "vitest";
import { createContainer } from "@/app/container";
import { testConfig } from "@/test/render-app";

describe("createContainer", () => {
  it("wires mock adapters and offers dev personas in mock mode", () => {
    const services = createContainer(testConfig, {}, {});
    expect(services.devPersonas?.list().map((p) => p.id)).toEqual([
      "resident",
      "administrator",
      "contractor",
      "platform-staff",
    ]);
  });

  it("wires HTTP adapters and no personas in http mode", () => {
    const services = createContainer({ ...testConfig, adapters: "http" }, {}, {});
    expect(services.devPersonas).toBeUndefined();
  });

  it("lets one port differ from the rest", async () => {
    const services = createContainer({ ...testConfig, adapters: "http" }, {}, { example: "mock" });
    await expect(services.example.listPeople({})).resolves.toHaveLength(6);
    expect(services.devPersonas).toBeUndefined();
  });

  it("lets tests replace any port", async () => {
    const issues = { list: async () => [] };
    expect(createContainer(testConfig, { issues }, {}).issues).toBe(issues);
  });
});
