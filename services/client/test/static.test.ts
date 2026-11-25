// @vitest-environment node
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const read = (path: string) => readFileSync(resolve(import.meta.dirname, "..", path), "utf8");

/** EN-02-05: a restrictive CSP and no third-party script. These checks keep the files honest. */
describe("static delivery", () => {
  it("index.html has no inline script, so script-src 'self' holds", () => {
    const html = read("index.html");
    const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\b[^>]*>/gi)];
    expect(scripts.length).toBeGreaterThan(0);
    for (const [, attributes, body] of scripts) {
      expect(attributes).toMatch(/\bsrc="\//);
      expect(body?.trim()).toBe("");
    }
  });

  it("index.html loads nothing from another origin", () => {
    expect(read("index.html")).not.toMatch(/(src|href)="(https?:)?\/\//);
  });

  it("nginx serves a restrictive Content-Security-Policy on every response", () => {
    const conf = read("nginx.conf");
    const csp = conf.match(/Content-Security-Policy "([^"]+)" always/)?.[1] ?? "";
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).not.toMatch(/script-src[^;]*'unsafe-(inline|eval)'/);
    expect(csp).not.toMatch(/https?:/);
  });

  it("vite preview sends the same CSP as nginx", () => {
    const nginx = read("nginx.conf").match(/Content-Security-Policy "([^"]+)" always/)?.[1];
    const vite = read("vite.config.ts").match(/const CSP =\s*"([^"]+)"/)?.[1];
    expect(vite).toBeDefined();
    expect(vite).toBe(nginx);
  });

  it("the service worker never caches API responses and lets edge navigations through (ADR-011)", () => {
    const vite = read("vite.config.ts");
    expect(vite).toMatch(/runtimeCaching: \[\]/);
    expect(vite).toMatch(/registerType: "prompt"/);
    expect(vite).toMatch(/navigateFallbackDenylist: EDGE_PATHS\.map/);
    const edge = vite.match(/const EDGE_PATHS = \[([^\]]+)\]/)?.[1] ?? "";
    for (const path of ["/api", "/oidc", "/logout", "/auth"]) expect(edge).toContain(`"${path}"`);
  });

  it("nginx answers 404 on every edge path, never index.html, and only on those", () => {
    // While a backend is starting or down, Traefik does not route it and its path falls through to this container.
    const edge = [
      ...(read("vite.config.ts").match(/const EDGE_PATHS = \[([^\]]+)\]/)?.[1] ?? "").matchAll(/"\/([^"]+)"/g),
    ]
      .map(([, name]) => name)
      .sort();
    const block = read("nginx.conf").match(/location ~ \^\/\(([^)]+)\)\(\/\|\$\) \{\s*return 404;\s*\}/);
    expect(block, "nginx.conf has no `location ~ ^/(...)(/|$) { return 404; }` block").not.toBeNull();
    expect(block![1].split("|").sort()).toEqual(edge);

    const pattern = new RegExp(`^/(${block![1]})(/|$)`);
    for (const path of ["/auth", "/auth/admin/", "/auth/realms/bmc/.well-known/openid-configuration", "/api/issue/me"])
      expect(path).toMatch(pattern);
    for (const path of ["/oidc/login", "/logout"]) expect(path).toMatch(pattern);
    for (const path of ["/", "/reports", "/issues/1", "/authors", "/apiary", "/assets/index.js"])
      expect(path).not.toMatch(pattern);
  });

  it("index.html declares no manifest of its own (vite-plugin-pwa injects it)", () => {
    expect(read("index.html")).not.toContain('rel="manifest"');
  });
});
