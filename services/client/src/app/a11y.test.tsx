import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { axe } from "vitest-axe";
import { renderApp } from "@/test/render-app";

/*
 * Automated accessibility check of every shell page (NFR-07): axe-core over the rendered DOM. It catches missing
 * labels, names, roles and landmarks; it does not replace the manual keyboard pass. Two rules are off because the
 * test DOM applies no CSS:
 * - `color-contrast`: there are no computed colours to measure.
 * - `landmark-unique`: the shell renders the "Main" navigation twice, the sidebar (`hidden md:flex`) and the bottom
 *   bar (`md:hidden`). A browser shows exactly one of them at any width, but without CSS both are visible here.
 */
const options = { rules: { "color-contrast": { enabled: false }, "landmark-unique": { enabled: false } } };

async function expectNoViolations(container: HTMLElement) {
  const results = await axe(container, options);
  expect(results.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.length})`)).toEqual([]);
}

describe("accessibility (axe)", () => {
  it("really runs in this test DOM: an unlabelled field is a violation", async () => {
    const container = document.createElement("main");
    container.innerHTML = '<input type="text" />';
    document.body.append(container);
    const results = await axe(container, options);
    container.remove();
    expect(results.violations.map((v) => v.id)).toContain("label");
  });

  it("login page", async () => {
    const { container } = renderApp("/login");
    await screen.findByRole("heading", { name: "Sign in to BMC" });
    await expectNoViolations(container);
  });

  it.each([
    ["/dashboard", "administrator-a", "Hello, Synthetic"],
    ["/issues", "resident-a1", "Issues"],
    ["/settings", "resident-a1", "Settings"],
    ["/admin", "platform-staff", "Admin"],
    ["/nowhere", "resident-a1", "Page not found"],
  ])("%s as %s", async (path, persona, heading) => {
    localStorage.setItem("bmc-mock-persona", persona);
    const { container } = renderApp(path);
    await screen.findByRole("heading", { level: 1, name: heading });
    await expectNoViolations(container);
  });
});
