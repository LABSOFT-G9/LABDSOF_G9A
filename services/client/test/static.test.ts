// @vitest-environment node
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const read = (path: string) => readFileSync(resolve(import.meta.dirname, "..", path), "utf8");

/** EN-02-05: a restrictive CSP and no third-party script. These checks keep the files honest. */
describe("static delivery", () => {
  it("index.html has no inline script, so script-src 'self' holds", () => {
    const html = read("index.html");
    const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/g)];
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
});
