import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Report, ReportReceipt, ReportSummary, SubmitReport } from "@/contracts/issue";
import { ApiError, NetworkError } from "@/shared/lib/errors";
import { keepUnsentReport } from "@/shared/lib/unsent-report";
import { stubIssueApi } from "@/test/fakes";
import { renderApp } from "@/test/render-app";

/** US-01-02: the report form, the receipt and the report view, through the whole app (router, guards, mocks). */

const areas = [
  { areaId: "0199f2a0-0000-7000-8000-00000000a001", label: "Garage level -1", kind: "common_area" as const },
  { areaId: "0199f2a0-0000-7000-8000-00000000a003", label: "Unit 2B", kind: "unit" as const },
];
const receipt: ReportReceipt = {
  reportId: "0199f2a0-0000-7000-8000-0000000e0001",
  issueId: "0199f2a0-0000-7000-8000-0000000e1001",
  state: "received",
  version: 1,
  submittedAt: "2026-11-24T10:00:00Z",
};
const TEXT = "The garage door does not close.";

async function fillForm(user: ReturnType<typeof userEvent.setup>, text = TEXT) {
  await screen.findByRole("heading", { level: 1, name: "Report a fault" });
  const area = screen.getByRole("combobox", { name: "Where is the fault?" });
  await waitFor(() => expect(area).toBeEnabled());
  await user.selectOptions(area, "Garage level -1 (Common area)");
  await user.type(screen.getByRole("textbox", { name: "What is wrong?" }), text);
}

beforeEach(() => {
  localStorage.setItem("bmc-mock-persona", "resident-a1");
});

describe("report form (mock Issue service)", () => {
  it("submits by keyboard, shows the receipt, and the report can be viewed again later", async () => {
    const user = userEvent.setup();
    renderApp("/reports/new");
    await screen.findByRole("heading", { level: 1, name: "Report a fault" });

    // The resident has one building, so it is already chosen.
    expect(screen.getByRole("combobox", { name: "Building" })).toHaveDisplayValue("Synthetic Building A");
    const area = screen.getByRole("combobox", { name: "Where is the fault?" });
    await waitFor(() => expect(area).toBeEnabled());
    area.focus();
    await user.selectOptions(area, "Garage level -1 (Common area)");
    await user.tab();
    expect(screen.getByRole("textbox", { name: "What is wrong?" })).toHaveFocus();
    await user.keyboard(TEXT);
    await user.tab();
    expect(screen.getByRole("button", { name: "Send report" })).toHaveFocus();
    await user.keyboard("{Enter}");

    const heading = await screen.findByRole("heading", { level: 2, name: "Report received" });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(screen.getByText("Received")).toBeInTheDocument();
    const reportId = screen.getByText("Report ID").nextElementSibling!.textContent!;
    expect(reportId).toMatch(/^[0-9a-f-]{36}$/);

    await user.click(screen.getByRole("link", { name: "View report" }));
    await screen.findByRole("heading", { level: 1, name: "Report · Garage level -1" });
    expect(screen.getByText(TEXT)).toBeInTheDocument();
    expect(screen.getByText(reportId)).toBeInTheDocument();
    expect(screen.getByText("Your report")).toBeInTheDocument();

    await user.click(screen.getByRole("link", { name: "All reports" }));
    await screen.findByRole("heading", { level: 1, name: "Reports" });
    expect(await screen.findByRole("link", { name: "Garage level -1" })).toBeInTheDocument();
  });

  it("explains invalid fields, ties each message to its field, and focuses the first one", async () => {
    const user = userEvent.setup();
    renderApp("/reports/new");
    await screen.findByRole("heading", { level: 1, name: "Report a fault" });
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Where is the fault?" })).toBeEnabled());
    await user.type(screen.getByRole("textbox", { name: "What is wrong?" }), "Too short");
    await user.click(screen.getByRole("button", { name: "Send report" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The report was not sent. Check the highlighted fields.",
    );
    const area = screen.getByRole("combobox", { name: "Where is the fault?" });
    expect(area).toHaveFocus();
    expect(area).toHaveAttribute("aria-invalid", "true");
    expect(area).toHaveAccessibleDescription(expect.stringContaining("Choose where the fault is."));
    expect(screen.getByRole("textbox", { name: "What is wrong?" })).toHaveAccessibleDescription(
      expect.stringContaining("Use at least 10 characters."),
    );
    expect(screen.queryByRole("heading", { name: "Report received" })).not.toBeInTheDocument();
  });

  it("shows the service's field errors on the right field", async () => {
    const user = userEvent.setup();
    const issues = stubIssueApi({
      listAreas: async () => areas,
      submitReport: async () => {
        throw new ApiError(400, "INVALID_AREA", "The area does not exist.", "corr-1", {
          errors: [{ field: "areaId", reason: "not_in_building" }],
        });
      },
    });
    renderApp("/reports/new", { services: { issues } });
    await fillForm(user);
    await user.click(screen.getByRole("button", { name: "Send report" }));

    const area = screen.getByRole("combobox", { name: "Where is the fault?" });
    await waitFor(() => expect(area).toHaveFocus());
    expect(area).toHaveAccessibleDescription(expect.stringContaining("This area is not in the selected building."));
  });

  it("never shows a receipt when the report was not saved, and retries with the same key and body", async () => {
    const user = userEvent.setup();
    const submitReport = vi
      .fn<(report: SubmitReport, key: string) => Promise<ReportReceipt>>()
      .mockRejectedValueOnce(new ApiError(503, "DEPENDENCY_UNAVAILABLE", "Try later.", "corr-503"))
      .mockResolvedValueOnce(receipt);
    renderApp("/reports/new", { services: { issues: stubIssueApi({ listAreas: async () => areas, submitReport }) } });
    await fillForm(user);
    await user.click(screen.getByRole("button", { name: "Send report" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Your report was not saved");
    expect(alert).toHaveTextContent("Reference: corr-503");
    expect(screen.queryByText("Report received")).not.toBeInTheDocument();
    // The text is kept for the retry.
    expect(screen.getByRole("textbox", { name: "What is wrong?" })).toHaveValue(TEXT);

    await user.click(within(alert).getByRole("button", { name: "Try again" }));
    await screen.findByRole("heading", { level: 2, name: "Report received" });
    expect(submitReport).toHaveBeenCalledTimes(2);
    const [first, second] = submitReport.mock.calls;
    expect(second![1]).toBe(first![1]);
    expect(second![0]).toEqual(first![0]);
    expect(first![0]).toEqual({ buildingId: expect.any(String), areaId: areas[0]!.areaId, text: TEXT });
  });

  describe("when the session ended while the report was being written", () => {
    /** The service answers `401`, as it does once the edge session is gone; the persona is gone with it. */
    const sessionEnded = () =>
      vi
        .fn<(report: SubmitReport, key: string) => Promise<ReportReceipt>>()
        .mockImplementationOnce(async () => {
          localStorage.removeItem("bmc-mock-persona");
          throw new ApiError(401, "UNAUTHENTICATED", "Sign in to continue.");
        })
        .mockResolvedValue(receipt);

    it("gives the text back after sign-in, says nothing was saved, and sends it as a new action", async () => {
      const user = userEvent.setup();
      const submitReport = sessionEnded();
      renderApp("/reports/new", { services: { issues: stubIssueApi({ listAreas: async () => areas, submitReport }) } });
      await fillForm(user);
      await user.click(screen.getByRole("button", { name: "Send report" }));

      await screen.findByRole("heading", { name: "Sign in to BMC" });
      await user.click(screen.getByRole("button", { name: /Resident A1/ }));

      await screen.findByRole("heading", { level: 1, name: "Report a fault" });
      expect(screen.getByText("Your report was not sent")).toBeInTheDocument();
      expect(screen.getByRole("textbox", { name: "What is wrong?" })).toHaveValue(TEXT);
      const area = screen.getByRole("combobox", { name: "Where is the fault?" });
      await waitFor(() => expect(area).toHaveDisplayValue("Garage level -1 (Common area)"));
      // Taken once: nothing stays in the tab.
      expect(sessionStorage.getItem("bmc-unsent-report")).toBeNull();

      await user.click(screen.getByRole("button", { name: "Send report" }));
      await screen.findByRole("heading", { level: 2, name: "Report received" });
      const [first, second] = submitReport.mock.calls;
      expect(second![0]).toEqual(first![0]);
      // The `401` stored nothing, so this is a new action with a new key.
      expect(second![1]).not.toBe(first![1]);

      // Sent: the notice is gone, also from the next, empty form.
      expect(screen.queryByText("Your report was not sent")).not.toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Report another fault" }));
      expect(screen.getByRole("textbox", { name: "What is wrong?" })).toHaveValue("");
      expect(screen.queryByText("Your report was not sent")).not.toBeInTheDocument();
    });

    it("removes the author's kept report when they sign out", async () => {
      const user = userEvent.setup();
      keepUnsentReport("0199f2a0-0000-7000-8000-000000000101", { buildingId: "b1", areaId: "a1", text: TEXT });
      renderApp("/reports");
      await screen.findByRole("heading", { level: 1, name: "Reports" });
      expect(sessionStorage.getItem("bmc-unsent-report")).toContain(TEXT);

      await user.click(screen.getByRole("button", { name: "Account menu" }));
      await user.click(await screen.findByRole("menuitem", { name: "Sign out" }));
      await screen.findByRole("heading", { name: "Sign in to BMC" });
      expect(sessionStorage.getItem("bmc-unsent-report")).toBeNull();
    });

    it("does not keep an area that is no longer in the building", async () => {
      const user = userEvent.setup();
      const submitReport = vi.fn<(report: SubmitReport, key: string) => Promise<ReportReceipt>>();
      keepUnsentReport("0199f2a0-0000-7000-8000-000000000101", {
        buildingId: "0199f2a0-0000-7000-8000-00000000b001",
        areaId: "an-area-since-removed",
        text: TEXT,
      });
      renderApp("/reports/new", { services: { issues: stubIssueApi({ listAreas: async () => areas, submitReport }) } });

      await screen.findByText("Your report was not sent");
      const area = screen.getByRole("combobox", { name: "Where is the fault?" });
      await waitFor(() => expect(area).toBeEnabled());
      expect(area).toHaveValue("");
      await user.click(screen.getByRole("button", { name: "Send report" }));
      expect(area).toHaveFocus();
      expect(submitReport).not.toHaveBeenCalled();
    });

    it("removes a kept report on any page once someone else is signed in, and keeps the author's", async () => {
      const kept = { buildingId: "b1", areaId: "a1", text: TEXT };
      keepUnsentReport("someone-else", kept);
      const first = renderApp("/reports");
      await screen.findByRole("heading", { level: 1, name: "Reports" });
      expect(sessionStorage.getItem("bmc-unsent-report")).toBeNull();
      first.unmount();

      keepUnsentReport("0199f2a0-0000-7000-8000-000000000101", kept);
      renderApp("/reports");
      await screen.findByRole("heading", { level: 1, name: "Reports" });
      expect(sessionStorage.getItem("bmc-unsent-report")).toContain(TEXT);
    });

    it("never gives the text to someone else who signs in on the same tab", async () => {
      const user = userEvent.setup();
      renderApp("/reports/new", {
        services: { issues: stubIssueApi({ listAreas: async () => areas, submitReport: sessionEnded() }) },
      });
      await fillForm(user);
      await user.click(screen.getByRole("button", { name: "Send report" }));

      await screen.findByRole("heading", { name: "Sign in to BMC" });
      expect(sessionStorage.getItem("bmc-unsent-report")).toContain(TEXT);
      await user.click(screen.getByRole("button", { name: /Resident A2/ }));

      await screen.findByRole("heading", { level: 1, name: "Report a fault" });
      // Removed as soon as the other person is known, before the form is shown.
      expect(sessionStorage.getItem("bmc-unsent-report")).toBeNull();
      expect(screen.getByRole("textbox", { name: "What is wrong?" })).toHaveValue("");
      expect(screen.queryByText("Your report was not sent")).not.toBeInTheDocument();
    });
  });

  it("uses a new key once the report is changed after a failure", async () => {
    const user = userEvent.setup();
    const submitReport = vi
      .fn<(report: SubmitReport, key: string) => Promise<ReportReceipt>>()
      .mockRejectedValueOnce(new ApiError(503, "DEPENDENCY_UNAVAILABLE", "Try later."))
      .mockResolvedValueOnce(receipt);
    renderApp("/reports/new", { services: { issues: stubIssueApi({ listAreas: async () => areas, submitReport }) } });
    await fillForm(user);
    await user.click(screen.getByRole("button", { name: "Send report" }));
    await screen.findByRole("alert");

    await user.type(screen.getByRole("textbox", { name: "What is wrong?" }), " Since Monday.");
    await user.click(screen.getByRole("button", { name: "Send report" }));
    await screen.findByRole("heading", { level: 2, name: "Report received" });
    expect(submitReport.mock.calls[1]![1]).not.toBe(submitReport.mock.calls[0]![1]);
  });

  it("locks the fields while the report is being sent, so its receipt is never lost", async () => {
    const user = userEvent.setup();
    let answer!: (receipt: ReportReceipt) => void;
    const submitReport = vi.fn(
      () =>
        new Promise<ReportReceipt>((resolve) => {
          answer = resolve;
        }),
    );
    renderApp("/reports/new", { services: { issues: stubIssueApi({ listAreas: async () => areas, submitReport }) } });
    await fillForm(user);
    await user.click(screen.getByRole("button", { name: "Send report" }));

    const text = screen.getByRole("textbox", { name: "What is wrong?" });
    await waitFor(() => expect(text).toBeDisabled());
    expect(screen.getByRole("combobox", { name: "Where is the fault?" })).toBeDisabled();
    expect(screen.getByRole("combobox", { name: "Building" })).toBeDisabled();
    await user.type(text, " Since Monday.");
    expect(text).toHaveValue(TEXT);

    answer(receipt);
    await screen.findByRole("heading", { level: 2, name: "Report received" });
    expect(submitReport).toHaveBeenCalledTimes(1);
  });

  it("does not claim the report was lost when the server never answered", async () => {
    const user = userEvent.setup();
    const submitReport = async (): Promise<ReportReceipt> => {
      throw new NetworkError();
    };
    renderApp("/reports/new", { services: { issues: stubIssueApi({ listAreas: async () => areas, submitReport }) } });
    await fillForm(user);
    await user.click(screen.getByRole("button", { name: "Send report" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Your report was not confirmed");
  });
});

describe("report view", () => {
  const report: Report = {
    ...receipt,
    buildingId: "0199f2a0-0000-7000-8000-00000000b001",
    areaId: areas[0]!.areaId,
    areaLabel: "Garage level -1",
    source: "resident",
    authoredByMe: false,
    issue: { state: "received", version: 1 },
    text: '<img src=x onerror="alert(1)"> Water <b>everywhere</b>',
  };

  it("shows the text as text, never as HTML", async () => {
    localStorage.setItem("bmc-mock-persona", "administrator-a");
    const { container } = renderApp(`/reports/${report.reportId}`, {
      services: { issues: stubIssueApi({ getReport: async () => report }) },
    });
    await screen.findByRole("heading", { level: 1, name: "Report · Garage level -1" });
    expect(screen.getByText(report.text)).toBeInTheDocument();
    expect(container.querySelector("img, b")).toBeNull();
    expect(screen.getByText("Reported by a resident")).toBeInTheDocument();
  });

  it("answers not found for a report that is unknown or not the caller's", async () => {
    renderApp("/reports/someone-elses", {
      services: {
        issues: stubIssueApi({
          getReport: async () => {
            throw new ApiError(404, "NOT_FOUND", "Not found.");
          },
        }),
      },
    });
    await screen.findByRole("heading", { level: 1, name: "Page not found" });
  });

  it("gives the error page a heading and a title, and focuses it after navigating", async () => {
    const user = userEvent.setup();
    const summary = Object.fromEntries(Object.entries(report).filter(([key]) => key !== "text")) as ReportSummary;
    renderApp("/reports", {
      services: {
        issues: stubIssueApi({
          listReports: async () => ({ items: [summary], page: 0, size: 20, hasMore: false }),
          getReport: async () => {
            throw new ApiError(409, "INVALID_TRANSITION", "Boom.", "corr-409");
          },
        }),
      },
    });
    await user.click(await screen.findByRole("link", { name: "Garage level -1" }));
    const heading = await screen.findByRole("heading", { level: 1, name: "Something went wrong" });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(document.title).toBe("Something went wrong · BMC");
    expect(screen.getByText("Reference: corr-409")).toBeInTheDocument();
  });
});

describe("reports list", () => {
  it("offers to report a fault when there are no reports", async () => {
    renderApp("/reports");
    await screen.findByText("No reports yet.");
    expect(screen.getAllByRole("link", { name: "Report a fault" }).length).toBeGreaterThan(0);
  });

  it("is in the navigation for building members only", async () => {
    renderApp("/dashboard");
    await screen.findByRole("heading", { level: 1 });
    const nav = screen.getAllByRole("navigation", { name: "Main" })[0]!;
    expect(within(nav).getByRole("link", { name: "Reports" })).toBeInTheDocument();
  });
});
