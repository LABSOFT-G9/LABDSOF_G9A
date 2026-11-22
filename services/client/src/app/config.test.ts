import { describe, expect, it } from "vitest";
import { readConfig } from "@/app/config";

describe("readConfig", () => {
  it("defaults to mock adapters and the placeholder edge paths", () => {
    expect(readConfig({})).toEqual({
      adapters: "mock",
      mockLatencyMs: 300,
      loginUri: "/oidc/login",
      logoutUri: "/logout",
      dev: false,
    });
  });

  it("reads the variables, treating empty values as unset", () => {
    const config = readConfig({
      VITE_ADAPTERS: "http",
      VITE_MOCK_LATENCY_MS: "",
      VITE_LOGIN_URI: "/oidc/start",
      DEV: true,
    });
    expect(config).toMatchObject({ adapters: "http", mockLatencyMs: 300, loginUri: "/oidc/start", dev: true });
  });

  it.each([
    { VITE_ADAPTERS: "graphql" },
    { VITE_LOGIN_URI: "https://idp.test/login" },
    { VITE_LOGOUT_URI: "//x" },
    { VITE_LOGIN_URI: "/sign-in" },
  ])("fails loudly on %o", (env) => {
    expect(() => readConfig(env)).toThrow(/Invalid client configuration/);
  });
});
