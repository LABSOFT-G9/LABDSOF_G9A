import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { Issue, TriageResult } from "@/contracts/issue";
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

  it("decides on the version the form was opened on, even after a background refetch", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    const opened: Issue = {
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
      .mockResolvedValueOnce(opened)
      .mockResolvedValue({
        ...opened,
        state: "triaged",
        version: 2,
        triage: {
          category: "lift",
          urgency: "low",
          reason: null,
          decidedAt: "2026-11-24T10:05:00Z",
          decisionSource: "human",
        },
      });
    const triage = vi.fn(async () => {
      throw new ApiError(409, "VERSION_CONFLICT", "Changed.", undefined, {
        currentVersion: 2,
        currentState: "triaged",
      });
    });
    const { router } = renderApp("/issues/i1", {
      services: { issues: stubIssueApi({ getIssue, getHistory: async () => ({ items: [] }), triage }) },
    });
    await screen.findByRole("heading", { level: 1, name: "Entrance hall" });
    await user.selectOptions(screen.getByRole("combobox", { name: "Category" }), "Plumbing");
    await user.selectOptions(screen.getByRole("combobox", { name: "Urgency" }), "High");
    await user.type(screen.getByRole("textbox", { name: "Reason" }), "Water near a socket.");

    // A colleague decided meanwhile; a refetch (window focus, reconnect, stale time) brings version 2 in.
    await router.options.context.queryClient.invalidateQueries({ queryKey: ["issues"] });
    await screen.findByText("Lift · Low");
    expect(screen.getByText("This issue has changed since you opened it")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Save decision" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Someone changed this issue");
    // Sent on version 1, the one the choices were made on: the service refuses it and nothing is replaced.
    expect(triage).toHaveBeenCalledWith("i1", 1, expect.anything(), expect.any(String));
  });

  it("locks the fields while the decision is being saved, so its answer is never lost", async () => {
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
    let answer!: (result: TriageResult) => void;
    const triage = vi.fn(
      () =>
        new Promise<TriageResult>((resolve) => {
          answer = resolve;
        }),
    );
    renderApp("/issues/i1", {
      services: {
        issues: stubIssueApi({ getIssue: async () => issue, getHistory: async () => ({ items: [] }), triage }),
      },
    });
    await screen.findByRole("heading", { level: 1, name: "Entrance hall" });
    await user.selectOptions(screen.getByRole("combobox", { name: "Category" }), "Lift");
    await user.selectOptions(screen.getByRole("combobox", { name: "Urgency" }), "Low");
    const reason = screen.getByRole("textbox", { name: "Reason" });
    await user.type(reason, "Check.");
    await user.click(screen.getByRole("button", { name: "Save decision" }));

    await waitFor(() => expect(reason).toBeDisabled());
    expect(screen.getByRole("combobox", { name: "Category" })).toBeDisabled();
    await user.type(reason, " More.");
    expect(reason).toHaveValue("Check.");

    answer({ issueId: "i1", state: "triaged", version: 2, category: "lift", urgency: "low", decisionSource: "human" });
    await screen.findByText("Decision saved.");
    expect(triage).toHaveBeenCalledTimes(1);
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
    await screen.findByText("Triage suggested by the AI (not a decision)");
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

describe("suggestion (US-03-02)", () => {
  const CRITICAL = "0199f2a1-0000-7000-8000-000000002005"; // Building B, Garage: AI timed out, fallback, critical alert
  const PENDING = "0199f2a1-0000-7000-8000-000000002002"; // Garage level -1, received 20 hours ago, no suggestion
  const NOW = new Date("2026-11-24T10:00:00Z");
  const stubbed = (overrides: Partial<Issue>): Issue => ({
    issueId: "i1",
    buildingId: "0199f2a0-0000-7000-8000-00000000b001",
    buildingName: "Synthetic Building A",
    areaLabel: "Entrance hall",
    state: "received",
    version: 1,
    createdAt: NOW.toISOString(),
    triage: null,
    suggestion: null,
    ...overrides,
  });
  const renderStubbed = (issue: Issue) =>
    renderApp("/issues/i1", {
      services: {
        issues: stubIssueApi({ getIssue: async () => issue, getHistory: async () => ({ items: [] }) }),
        clock: { now: () => NOW },
      },
    });

  it("shows the suggestion as unconfirmed, with its origin and explanation", async () => {
    signInAs("administrator-a");
    renderApp(`/issues/${SUGGESTED}`);
    const panel = await screen.findByRole("region", { name: "Suggestion" });
    expect(within(panel).getByText("Unconfirmed · AI")).toBeInTheDocument();
    expect(within(panel).getByText("Electrical")).toBeInTheDocument();
    expect(within(panel).getByText("High")).toBeInTheDocument();
    expect(within(panel).getByText(/exposed wires next to the light switch/)).toBeInTheDocument();
    expect(within(panel).getByText(/Not a decision until you save one below/)).toBeInTheDocument();
    expect(within(panel).queryByText("AI could not be used")).not.toBeInTheDocument();
    expect(screen.queryByText("Critical alert")).not.toBeInTheDocument();
  });

  it("keeps a critical alert in view when the suggestion disagrees, and after the decision", async () => {
    const user = userEvent.setup();
    signInAs("administrator-b");
    const { router } = renderApp("/issues");
    const garage = await screen.findByRole("link", { name: "Garage" });
    expect(within(card(garage)).getByText("Critical alert")).toBeInTheDocument();
    expect(within(card(garage)).getByText("Unconfirmed suggestion (rules, AI unavailable)")).toBeInTheDocument();

    await router.navigate({ to: "/issues/$issueId", params: { issueId: CRITICAL } });
    await screen.findByRole("heading", { level: 1, name: "Garage" });
    const banner = screen
      .getByText("Critical alert", { selector: "[data-slot=alert-title]" })
      .closest("[data-slot=alert]") as HTMLElement;
    expect(banner).toHaveAttribute("role", "note");
    expect(banner).toHaveTextContent("The report mentions smoke near an electrical socket.");
    expect(banner).toHaveTextContent("The suggestion says Normal, but the alert still stands.");
    expect(banner).toHaveTextContent("no suggestion can clear or lower this alert");

    const panel = screen.getByRole("region", { name: "Suggestion" });
    expect(panel).toHaveTextContent("AI could not be used");
    expect(panel).toHaveTextContent(
      "The AI did not answer in time. This suggestion comes from the fallback rules instead.",
    );
    expect(panel).toHaveTextContent("The suggester asks for a careful review of this one.");

    // Confirming the lower suggested urgency is the administrator's call; the alert does not go away because of it.
    await user.click(screen.getByRole("button", { name: "Fill in the suggestion" }));
    expect(screen.getByRole("combobox", { name: "Urgency" })).toHaveDisplayValue("Normal");
    await user.selectOptions(screen.getByRole("combobox", { name: "Urgency" }), "Critical");
    await user.type(screen.getByRole("textbox", { name: "Reason" }), "Smoke reported: treat as critical.");
    await user.click(screen.getByRole("button", { name: "Save decision" }));
    await screen.findByText("Decision saved.");

    await waitFor(() => expect(screen.queryByRole("region", { name: "Suggestion" })).not.toBeInTheDocument());
    const after = screen
      .getByText("Critical alert", { selector: "[data-slot=alert-title]" })
      .closest("[data-slot=alert]") as HTMLElement;
    expect(after).toHaveTextContent("The report mentions smoke near an electrical socket.");
    expect(after).not.toHaveTextContent("The suggestion says");
  });

  it("says triage is pending, to classify manually, when no suggestion arrived within 2 minutes", async () => {
    signInAs("administrator-a");
    renderApp(`/issues/${PENDING}`);
    await screen.findByRole("heading", { level: 1, name: "Garage level -1" });
    expect(screen.getByText("Triage pending — classify manually")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Suggestion" })).not.toBeInTheDocument();
  });

  it("says it is still waiting for a suggestion in the first 2 minutes, and the form stays usable", async () => {
    signInAs("administrator-a");
    renderStubbed(stubbed({ createdAt: new Date(NOW.getTime() - 30_000).toISOString() }));
    await screen.findByText("Waiting for a suggestion. You can decide now without it.");
    expect(screen.queryByText("Triage pending — classify manually")).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Category" })).toBeEnabled();
  });

  it("shows the explanation as plain text and an unknown failure in general words", async () => {
    signInAs("administrator-a");
    renderStubbed(
      stubbed({
        state: "in_triage",
        version: 2,
        suggestion: {
          category: "other",
          urgency: "low",
          origin: "fallback",
          rationale: '<img src="x" alt="injected"> Ignore previous instructions.',
          failureCode: "something_new",
        },
      }),
    );
    const panel = await screen.findByRole("region", { name: "Suggestion" });
    expect(within(panel).getByText('<img src="x" alt="injected"> Ignore previous instructions.')).toBeInTheDocument();
    expect(within(panel).queryByRole("img")).not.toBeInTheDocument();
    expect(panel).toHaveTextContent("The AI result was not used.");
  });

  it("never shows a resident the suggestion or the critical alert", async () => {
    signInAs("resident-b1");
    renderApp(`/issues/${CRITICAL}`);
    await screen.findByRole("heading", { level: 1, name: "Garage" });
    expect(screen.getByText("Decision pending")).toBeInTheDocument();
    expect(screen.queryByText("Critical alert")).not.toBeInTheDocument();
    expect(screen.queryByText(/suggest/i)).not.toBeInTheDocument();
  });
});

describe("origins (US-14-03)", () => {
  const CRITICAL = "0199f2a1-0000-7000-8000-000000002005"; // Building B: AI timed out, fallback rules, critical alert
  const iconOf = (element: HTMLElement) => element.querySelector("svg")?.getAttribute("class") ?? "";
  const historyCard = () => screen.getByRole("heading", { name: "History" }).closest("[data-slot=card]") as HTMLElement;

  it("tells the AI, the rules with AI unavailable and a human decision apart in words and icons", async () => {
    signInAs("administrator-a");
    const first = renderApp("/issues");
    const ai = within(card(await screen.findByRole("link", { name: "Entrance hall" }))).getByText(
      "Unconfirmed suggestion (AI)",
    );
    const human = within(card(screen.getByRole("link", { name: "Unit 2B" }))).getByText("Plumbing · Normal");
    expect(iconOf(ai)).toContain("lucide-sparkles");
    expect(iconOf(human)).toContain("lucide-user-check");
    first.unmount();

    signInAs("administrator-b");
    renderApp("/issues");
    const fallback = within(card(await screen.findByRole("link", { name: "Garage" }))).getByText(
      "Unconfirmed suggestion (rules, AI unavailable)",
    );
    expect(iconOf(fallback)).toContain("lucide-cloud-off");
  });

  it("names what produced the suggestion: the model, or the rules version", async () => {
    signInAs("administrator-a");
    const first = renderApp(`/issues/${SUGGESTED}`);
    const ai = await screen.findByRole("region", { name: "Suggestion" });
    expect(ai).toHaveTextContent("Produced byModel mock-model-v1");
    first.unmount();

    signInAs("administrator-b");
    renderApp(`/issues/${CRITICAL}`);
    const fallback = await screen.findByRole("region", { name: "Suggestion" });
    expect(within(fallback).getByText("Unconfirmed · rules, AI unavailable")).toBeInTheDocument();
    expect(fallback).toHaveTextContent("Produced byRules rules-v1");
  });

  it("shows a rules result with AI turned off as rules, without claiming the AI failed", async () => {
    signInAs("administrator-a");
    const issue: Issue = {
      issueId: "i1",
      buildingId: "0199f2a0-0000-7000-8000-00000000b001",
      buildingName: "Synthetic Building A",
      areaLabel: "Entrance hall",
      state: "in_triage",
      version: 2,
      createdAt: "2026-11-24T10:00:00Z",
      triage: null,
      suggestion: { category: "lift", urgency: "normal", origin: "rules", rulesVersion: "rules-v1" },
    };
    renderApp("/issues/i1", {
      services: { issues: stubIssueApi({ getIssue: async () => issue, getHistory: async () => ({ items: [] }) }) },
    });
    const panel = await screen.findByRole("region", { name: "Suggestion" });
    expect(within(panel).getByText("Unconfirmed · rules")).toBeInTheDocument();
    expect(iconOf(within(panel).getByText("Unconfirmed · rules"))).toContain("lucide-list-checks");
    expect(panel).toHaveTextContent("Rules rules-v1");
    expect(panel).not.toHaveTextContent("AI could not be used");
  });

  it("shows in the history who suggested, the critical alert, and a late suggestion as not applied", async () => {
    signInAs("administrator-b");
    const first = renderApp(`/issues/${CRITICAL}`);
    await screen.findByText("Triage suggested by the rules: AI unavailable (not a decision)");
    expect(within(historyCard()).getByText("Critical alert raised")).toBeInTheDocument();
    expect(within(historyCard()).queryByText("Arrived after the decision: not applied")).not.toBeInTheDocument();
    first.unmount();

    signInAs("administrator-a");
    renderApp(`/issues/${SCHEDULED}`);
    const late = await screen.findByText("Triage suggested by the AI (not a decision)");
    expect(late.closest("li")).toHaveTextContent("Arrived after the decision: not applied");
    expect(late.closest("li")).toHaveTextContent("Plumbing · Low");
    // The decision itself is untouched by the late result.
    expect(screen.getByText("Plumbing · Normal", { selector: "[data-slot=badge]" })).toBeInTheDocument();
  });

  it("keeps a suggestion entry without an origin generic", async () => {
    signInAs("administrator-a");
    const issue: Issue = {
      issueId: "i1",
      buildingId: "0199f2a0-0000-7000-8000-00000000b001",
      buildingName: "Synthetic Building A",
      areaLabel: "Entrance hall",
      state: "in_triage",
      version: 2,
      createdAt: "2026-11-24T10:00:00Z",
      triage: null,
      suggestion: null,
    };
    const entry = {
      sequence: 2,
      type: "triage_suggested",
      visibility: "internal" as const,
      actorRole: "system" as const,
      occurredAt: "2026-11-24T10:00:30Z",
    };
    renderApp("/issues/i1", {
      services: { issues: stubIssueApi({ getIssue: async () => issue, getHistory: async () => ({ items: [entry] }) }) },
    });
    await screen.findByText("Triage suggested (not a decision)");
  });

  it("never shows a resident who suggested or the critical alert in the history", async () => {
    signInAs("resident-b1");
    renderApp(`/issues/${CRITICAL}`);
    await screen.findByText("Report received");
    expect(within(historyCard()).queryByText(/suggested|Critical alert/)).not.toBeInTheDocument();
  });
});

describe("shared issue (US-04-02)", () => {
  const SHARED = "0199f2a1-0000-7000-8000-000000002002"; // Garage level -1: shared with Building A, Resident A2's report
  const sharedCard = () => screen.getByRole("heading", { name: "Shared with the building" }).closest("section")!;

  it("shows residents of the building the shared summary and count, not the report", async () => {
    const user = userEvent.setup();
    signInAs("resident-a1");
    renderApp("/issues");
    const link = await screen.findByRole("link", { name: "Garage level -1" });
    expect(within(card(link)).getByText("Shared")).toBeInTheDocument();

    await user.click(link);
    await screen.findByRole("heading", { level: 1, name: "Garage level -1" });
    const shared = sharedCard();
    expect(shared).toHaveTextContent("The garage door on level -1 does not close fully.");
    expect(shared).toHaveTextContent("1 resident says this affects them.");
    expect(shared).toHaveTextContent("Only the number is shown, never who.");
    expect(screen.queryByRole("button", { name: "Save decision" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Share with the building" })).not.toBeInTheDocument();
  });

  it("confirms and withdraws the impact by keyboard, with messages in words, and keeps the status", async () => {
    const user = userEvent.setup();
    signInAs("resident-a1");
    renderApp(`/issues/${SHARED}`);
    await screen.findByRole("heading", { level: 1, name: "Garage level -1" });

    const confirm = screen.getByRole("button", { name: "This affects me too" });
    expect(confirm).toHaveAccessibleDescription(expect.stringContaining("It does not change the urgency"));
    confirm.focus();
    await user.keyboard("{Enter}");
    await screen.findByText("Your confirmation was recorded.");
    expect(sharedCard()).toHaveTextContent("2 residents say this affects them.");
    expect(sharedCard()).toHaveTextContent("You said this affects you.");

    await user.click(screen.getByRole("button", { name: "Withdraw my confirmation" }));
    await screen.findByText("Your confirmation was withdrawn. You can still follow this issue here.");
    expect(sharedCard()).toHaveTextContent("1 resident says this affects them.");
    // Withdrawing never removes access: the status and history are still there.
    expect(screen.getAllByText("Received").length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { name: "History" })).toBeInTheDocument();
  });

  it("subscribes and unsubscribes separately from the impact", async () => {
    const user = userEvent.setup();
    signInAs("resident-a1");
    renderApp(`/issues/${SHARED}`);
    const box = await screen.findByRole("checkbox", { name: "Notify me when this issue changes" });
    expect(box).not.toBeChecked();

    await user.click(box);
    await screen.findByText("You will be notified when this issue changes.");
    expect(box).toBeChecked();
    expect(sharedCard()).toHaveTextContent("1 resident says this affects them.");

    box.focus();
    await user.keyboard(" ");
    await screen.findByText("Notifications for this issue are off. You can still check its status here.");
    expect(box).not.toBeChecked();
    expect(screen.getByRole("button", { name: "This affects me too" })).toBeInTheDocument();
  });

  it("shows a failed confirmation as an error and changes nothing", async () => {
    const user = userEvent.setup();
    signInAs("resident-a1");
    const issue: Issue = {
      issueId: "i1",
      buildingId: "0199f2a0-0000-7000-8000-00000000b001",
      buildingName: "Synthetic Building A",
      areaLabel: "Garage level -1",
      state: "received",
      version: 2,
      createdAt: "2026-11-24T10:00:00Z",
      triage: null,
      suggestion: null,
      summary: { text: "The garage door does not close.", publishedAt: "2026-11-24T10:05:00Z" },
      impact: { count: 1, mine: false },
      subscribed: false,
    };
    const setImpact = vi.fn(async () => {
      throw new ApiError(503, "DEPENDENCY_UNAVAILABLE", "Try again later.", "c-503");
    });
    renderApp("/issues/i1", {
      services: {
        issues: stubIssueApi({ getIssue: async () => issue, getHistory: async () => ({ items: [] }), setImpact }),
      },
    });
    await user.click(await screen.findByRole("button", { name: "This affects me too" }));
    await screen.findByText(/c-503/);
    expect(sharedCard()).toHaveTextContent("1 resident says this affects them.");
    expect(screen.queryByText("Your confirmation was recorded.")).not.toBeInTheDocument();
  });

  it("is not visible to another building's residents", async () => {
    signInAs("resident-b1");
    renderApp(`/issues/${SHARED}`);
    await screen.findByRole("heading", { level: 1, name: "Page not found" });
  });

  it("lets the administrator share a summary after a triage decision, without a false conflict", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    renderApp(`/issues/${SUGGESTED}`);
    await screen.findByRole("heading", { level: 1, name: "Entrance hall" });
    expect(screen.getByText(/Not shared yet/)).toBeInTheDocument();

    // Triage first: it moves the version.
    await user.click(screen.getByRole("button", { name: "Fill in the suggestion" }));
    await user.type(screen.getByRole("textbox", { name: "Reason" }), "Exposed wiring by the door.");
    await user.click(screen.getByRole("button", { name: "Save decision" }));
    await screen.findByText("Decision saved.");

    const summary = screen.getByRole("textbox", { name: "Summary for residents" });
    await user.type(summary, "Too short");
    await user.click(screen.getByRole("button", { name: "Share the summary" }));
    expect(await screen.findByText("Use at least 10 characters.")).toBeInTheDocument();
    expect(summary).toHaveFocus();
    expect(summary).toHaveAccessibleDescription(expect.stringContaining("Use at least 10 characters."));

    await user.clear(summary);
    await user.type(summary, "The entrance light switch is unsafe. An electrician is being arranged.");
    await user.click(screen.getByRole("button", { name: "Share the summary" }));
    await screen.findByText("Summary shared with the building.");
    expect(screen.queryByText("Someone changed this issue")).not.toBeInTheDocument();
    expect(
      screen.getByText("The entrance light switch is unsafe. An electrician is being arranged."),
    ).toBeInTheDocument();
    expect(screen.getByText("No resident has said this affects them yet.")).toBeInTheDocument();
    const history = screen.getByRole("heading", { name: "History" }).closest("[data-slot=card]") as HTMLElement;
    await waitFor(() => expect(within(history).getByText("Summary shared with the building")).toBeInTheDocument());
  });

  it("lets the administrator change the summary, or cancel, and shows the count but no confirm button", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    renderApp(`/issues/${SHARED}`);
    await screen.findByRole("heading", { level: 1, name: "Garage level -1" });
    expect(screen.getByText("1 resident says this affects them.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "This affects me too" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Change the summary" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByText(/does not close fully/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Change the summary" }));
    const summary = screen.getByRole("textbox", { name: "Summary for residents" });
    await user.clear(summary);
    await user.type(summary, "The garage door on level -1 is being repaired on Thursday.");
    await user.click(screen.getByRole("button", { name: "Update the summary" }));
    await screen.findByText("Summary shared with the building.");
    expect(screen.getByText("The garage door on level -1 is being repaired on Thursday.")).toBeInTheDocument();
  });

  it("on a conflict, overwrites nothing and offers to reload", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    const issue: Issue = {
      issueId: "i1",
      buildingId: "0199f2a0-0000-7000-8000-00000000b001",
      buildingName: "Synthetic Building A",
      areaLabel: "Entrance hall",
      state: "triaged",
      version: 3,
      createdAt: "2026-11-24T10:00:00Z",
      triage: null,
      suggestion: null,
    };
    const publish = vi.fn(async () => {
      throw new ApiError(409, "VERSION_CONFLICT", "Changed.", "c-409", { currentVersion: 4, currentState: "triaged" });
    });
    renderApp("/issues/i1", {
      services: {
        issues: stubIssueApi({ getIssue: async () => issue, getHistory: async () => ({ items: [] }), publish }),
      },
    });
    await user.type(
      await screen.findByRole("textbox", { name: "Summary for residents" }),
      "The entrance light is out.",
    );
    await user.click(screen.getByRole("button", { name: "Share the summary" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Someone changed this issue");
    expect(publish).toHaveBeenCalledWith("i1", 3, { summary: "The entrance light is out." }, expect.any(String));
    expect(screen.getByRole("textbox", { name: "Summary for residents" })).toHaveValue("The entrance light is out.");
    expect(within(alert).getByRole("button", { name: "Reload the issue" })).toBeInTheDocument();
  });
});
