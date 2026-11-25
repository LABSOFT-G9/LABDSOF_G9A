import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { axe } from "vitest-axe";
import { stubIssueApi } from "@/test/fakes";
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
    ["/issues", "administrator-a", "Issues"],
    ["/issues/0199f2a1-0000-7000-8000-000000002001", "administrator-a", "Entrance hall"],
    ["/issues/0199f2a1-0000-7000-8000-000000002003", "resident-a1", "Unit 2B"],
    // Critical alert, fallback suggestion with its failure, and the "triage pending" notice (US-03-02).
    ["/issues/0199f2a1-0000-7000-8000-000000002005", "administrator-b", "Garage"],
    ["/issues/0199f2a1-0000-7000-8000-000000002002", "administrator-a", "Garage level -1"],
    // US-04-02: the shared summary, impact and subscription controls.
    ["/issues/0199f2a1-0000-7000-8000-000000002002", "resident-a1", "Garage level -1"],
    ["/reports", "resident-a1", "Reports"],
    ["/reports/new", "resident-a1", "Report a fault"],
    ["/settings", "resident-a1", "Settings"],
    ["/admin", "platform-staff", "Admin"],
    ["/nowhere", "resident-a1", "Page not found"],
  ])("%s as %s", async (path, persona, heading) => {
    localStorage.setItem("bmc-mock-persona", persona);
    const { container } = renderApp(path);
    await screen.findByRole("heading", { level: 1, name: heading });
    await expectNoViolations(container);
  });

  it("report view", async () => {
    localStorage.setItem("bmc-mock-persona", "resident-a1");
    const report = {
      reportId: "r1",
      issueId: "i1",
      buildingId: "0199f2a0-0000-7000-8000-00000000b001",
      areaId: "0199f2a0-0000-7000-8000-00000000a001",
      areaLabel: "Garage level -1",
      text: "The garage door does not close.",
      source: "resident" as const,
      authoredByMe: true,
      submittedAt: "2026-11-24T10:00:00Z",
      issue: { state: "received" as const, version: 1 },
    };
    const { container } = renderApp("/reports/r1", {
      services: { issues: stubIssueApi({ getReport: async () => report }) },
    });
    await screen.findByRole("heading", { level: 1, name: "Report · Garage level -1" });
    await expectNoViolations(container);
  });

  it("report form with field errors", async () => {
    localStorage.setItem("bmc-mock-persona", "resident-a1");
    const { container } = renderApp("/reports/new");
    const submit = await screen.findByRole("button", { name: "Send report" });
    submit.click();
    await screen.findByRole("alert");
    await expectNoViolations(container);
  });
});
