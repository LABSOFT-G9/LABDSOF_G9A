import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { Issue } from "@/contracts/issue";
import { ApiError } from "@/shared/lib/errors";
import { stubIssueApi } from "@/test/fakes";
import { renderApp } from "@/test/render-app";

/** US-02-02: the issue list, the triage form and the history view, through the whole app over mock adapters. */

const SUGGESTED = "0199f2a1-0000-7000-8000-000000002001"; // Entrance hall, in_triage, AI suggestion
const SCHEDULED = "0199f2a1-0000-7000-8000-000000002003"; // Unit 2B, human decision, approved, accepted, booked

const signInAs = (persona: string) => localStorage.setItem("bmc-mock-persona", persona);
const card = (link: HTMLElement) => link.closest("li")!;

describe("issue list", () => {
  it("tells a decision, an unconfirmed suggestion and a pending decision apart, in words", async () => {
    signInAs("administrator-a");
    renderApp("/issues");
    const suggested = await screen.findByRole("link", { name: "Entrance hall" });
    expect(within(card(suggested)).getByText("Unconfirmed suggestion (AI)")).toBeInTheDocument();
    expect(within(card(suggested)).getByText("In triage")).toBeInTheDocument();

    const pending = screen.getByRole("link", { name: "Garage level -1" });
    expect(within(card(pending)).getByText("Decision pending")).toBeInTheDocument();

    const decided = screen.getByRole("link", { name: "Unit 2B" });
    expect(within(card(decided)).getByText("Plumbing · Normal")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Roof terrace" })).not.toBeInTheDocument();
  });
});

describe("triage (administrator)", () => {
  it("confirms a suggestion by keyboard and records it as a human decision", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    renderApp(`/issues/${SUGGESTED}`);
    await screen.findByRole("heading", { level: 1, name: "Entrance hall" });
    expect(screen.getByText("Not a decision. The administrator confirms or corrects it below.")).toBeInTheDocument();

    const fill = screen.getByRole("button", { name: "Fill in the suggestion" });
    fill.focus();
    await user.keyboard("{Enter}");
    expect(screen.getByRole("combobox", { name: "Category" })).toHaveDisplayValue("Electrical");
    expect(screen.getByRole("combobox", { name: "Urgency" })).toHaveDisplayValue("High");
    // Filling the form is not a decision: nothing is saved until the administrator says so.
    expect(screen.getByText("Unconfirmed suggestion (AI)")).toBeInTheDocument();

    await user.click(screen.getByRole("textbox", { name: "Reason" }));
    await user.keyboard("Exposed wiring by the door.");
    await user.tab();
    expect(screen.getByRole("button", { name: "Save decision" })).toHaveFocus();
    await user.keyboard("{Enter}");

    await screen.findByText("Decision saved.");
    const decision = screen.getByText("Triage", { selector: "dt" }).nextElementSibling as HTMLElement;
    await waitFor(() => expect(within(decision).getByText("Electrical · High")).toBeInTheDocument());
    expect(within(decision).getByText(/Decided by the administrator/)).toBeInTheDocument();
    expect(screen.getAllByText("Triaged").length).toBeGreaterThan(0);
    const history = screen.getByRole("heading", { name: "History" }).closest("[data-slot=card]") as HTMLElement;
    await waitFor(() => expect(within(history).getByText("Triage decided")).toBeInTheDocument());
    expect(within(history).getByText("Exposed wiring by the door.", { exact: false })).toBeInTheDocument();
  });

  it("explains missing fields, ties each message to its field and focuses the first one", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    renderApp(`/issues/${SUGGESTED}`);
    await screen.findByRole("heading", { level: 1, name: "Entrance hall" });
    await user.click(screen.getByRole("button", { name: "Save decision" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The decision was not saved.");
    const category = screen.getByRole("combobox", { name: "Category" });
    expect(category).toHaveFocus();
    expect(category).toHaveAccessibleDescription("Choose a category.");
    expect(screen.getByRole("combobox", { name: "Urgency" })).toHaveAccessibleDescription("Choose the urgency.");
    expect(screen.getByRole("textbox", { name: "Reason" })).toHaveAccessibleDescription(
      expect.stringContaining("Give a reason."),
    );
  });

  it("on a conflict, overwrites nothing, keeps the choices and offers to reload", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    const issue: Issue = {
      issueId: "i1",
      buildingId: "0199f2a0-0000-7000-8000-00000000b001",
      buildingName: "Synthetic Building A",
      areaLabel: "Entrance hall",
      state: "received",
      version: 1,
      createdAt: "2026-11-24T10:00:00Z",
      triage: null,
      suggestion: null,
    };
    const getIssue = vi
      .fn<() => Promise<Issue>>()
      .mockResolvedValueOnce(issue)
      .mockResolvedValue({
        ...issue,
        state: "triaged",
        version: 2,
        triage: {
          category: "lift",
          urgency: "low",
          reason: "Decided by a colleague.",
          decidedAt: "2026-11-24T10:05:00Z",
          decisionSource: "human",
        },
      });
    const triage = vi.fn(async () => {
      throw new ApiError(409, "VERSION_CONFLICT", "Changed.", "c-81b4", { currentVersion: 2, currentState: "triaged" });
    });
    renderApp("/issues/i1", {
      services: { issues: stubIssueApi({ getIssue, getHistory: async () => ({ items: [] }), triage }) },
    });
    await screen.findByRole("heading", { level: 1, name: "Entrance hall" });
    await user.selectOptions(screen.getByRole("combobox", { name: "Category" }), "Plumbing");
    await user.selectOptions(screen.getByRole("combobox", { name: "Urgency" }), "High");
    await user.type(screen.getByRole("textbox", { name: "Reason" }), "Water near a socket.");
    await user.click(screen.getByRole("button", { name: "Save decision" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Someone changed this issue");
    expect(alert).toHaveTextContent("Reference: c-81b4");
    expect(triage).toHaveBeenCalledWith("i1", 1, expect.anything(), expect.any(String));
    expect(screen.getByRole("textbox", { name: "Reason" })).toHaveValue("Water near a socket.");

    await user.click(within(alert).getByRole("button", { name: "Reload the issue" }));
    await screen.findByText("Lift · Low");
    expect(screen.queryByText("Someone changed this issue")).not.toBeInTheDocument();
    // The administrator's own choices are still in the form, to save on the new version if they still want to.
    expect(screen.getByRole("combobox", { name: "Category" })).toHaveDisplayValue("Plumbing");
    expect(screen.getByRole("textbox", { name: "Reason" })).toHaveValue("Water near a socket.");
  });

  it("explains when the issue can no longer be triaged", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    renderApp(`/issues/${SUGGESTED}`, {
      services: {
        issues: stubIssueApi({
          getIssue: async () => ({
            issueId: SUGGESTED,
            buildingId: "0199f2a0-0000-7000-8000-00000000b001",
            buildingName: "Synthetic Building A",
            areaLabel: "Entrance hall",
            state: "triaged",
            version: 3,
            createdAt: "2026-11-24T10:00:00Z",
            triage: null,
            suggestion: null,
          }),
          getHistory: async () => ({ items: [] }),
          triage: async () => {
            throw new ApiError(409, "INVALID_TRANSITION", "No.", undefined, { currentState: "in_coordination" });
          },
        }),
      },
    });
    await screen.findByRole("heading", { level: 1, name: "Entrance hall" });
    await user.selectOptions(screen.getByRole("combobox", { name: "Category" }), "Lift");
    await user.selectOptions(screen.getByRole("combobox", { name: "Urgency" }), "Low");
    await user.type(screen.getByRole("textbox", { name: "Reason" }), "Check.");
    await user.click(screen.getByRole("button", { name: "Save decision" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "This issue is In coordination, so its triage can no longer change.",
    );
  });

  it("shows no triage form once the issue has moved past triage, and shows administrators the reason", async () => {
    signInAs("administrator-a");
    renderApp(`/issues/${SCHEDULED}`);
    await screen.findByRole("heading", { level: 1, name: "Unit 2B" });
    expect(screen.queryByRole("button", { name: "Save decision" })).not.toBeInTheDocument();
    expect(screen.getByText(/Slow leak under the kitchen sink/, { selector: "dd p" })).toBeInTheDocument();
  });

  it("marks entries only administrators can see", async () => {
    signInAs("administrator-a");
    renderApp(`/issues/${SUGGESTED}`);
    await screen.findByText("Triage suggested (not a decision)");
    expect(screen.getByText("Internal: administrators only")).toBeInTheDocument();
  });

  it("answers not found for another building's issue", async () => {
    signInAs("administrator-b");
    renderApp(`/issues/${SUGGESTED}`);
    await screen.findByRole("heading", { level: 1, name: "Page not found" });
  });
});

describe("status and history (resident)", () => {
  it("shows approval, the contractor's answer and the booking as separate steps, public only", async () => {
    signInAs("resident-a1");
    renderApp(`/issues/${SCHEDULED}`);
    await screen.findByRole("heading", { level: 1, name: "Unit 2B" });
    await screen.findByText("Request approved by the administrator");
    expect(screen.getByText("Contractor accepted the request")).toBeInTheDocument();
    expect(screen.getByText("Booking confirmed manually")).toBeInTheDocument();
    expect(screen.getAllByText("Scheduled").length).toBeGreaterThan(0);
    expect(screen.queryByRole("button", { name: "Save decision" })).not.toBeInTheDocument();
    // The administrator's reason is internal.
    expect(screen.queryByText(/Slow leak under the kitchen sink/)).not.toBeInTheDocument();
    expect(screen.queryByText("Internal: administrators only")).not.toBeInTheDocument();
  });

  it("never shows a resident the AI suggestion: the decision is simply pending", async () => {
    signInAs("resident-a1");
    renderApp(`/issues/${SUGGESTED}`);
    await screen.findByRole("heading", { level: 1, name: "Entrance hall" });
    expect(screen.getByText("Decision pending")).toBeInTheDocument();
    await screen.findByText("Report received");
    expect(screen.queryByText(/suggest/i)).not.toBeInTheDocument();
  });

  it("follows the issue from the resident's own report", async () => {
    const user = userEvent.setup();
    signInAs("resident-a1");
    renderApp("/reports/new");
    const area = await screen.findByRole("combobox", { name: "Where is the fault?" });
    await waitFor(() => expect(area).toBeEnabled());
    await user.selectOptions(area, "Entrance hall (Common area)");
    await user.type(screen.getByRole("textbox", { name: "What is wrong?" }), "The intercom does not ring.");
    await user.click(screen.getByRole("button", { name: "Send report" }));
    await user.click(await screen.findByRole("link", { name: "View report" }));
    await user.click(await screen.findByRole("link", { name: "Follow the issue" }));
    await screen.findByRole("heading", { level: 1, name: "Entrance hall" });
    await screen.findByText("Report received");
  });
});
