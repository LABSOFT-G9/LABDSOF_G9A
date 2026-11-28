import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { Intervention } from "@/contracts/dispatch";
import { fromLocalInput } from "@/features/interventions/window-input";
import { ApiError } from "@/shared/lib/errors";
import { booking, intervention } from "@/test/dispatch-fixtures";
import { stubDispatchApi } from "@/test/fakes";
import { renderApp } from "@/test/render-app";

/** US-08-02: the booking status and the manual confirmation, on the issue page, over mock adapters. */

const UNIT_2B = "0199f2a1-0000-7000-8000-000000002003"; // Building A, scheduled
const LIFT = "0199f2a1-0000-7000-8000-000000002006"; // Building A, in coordination, booking failed

const signInAs = (persona: string) => localStorage.setItem("bmc-mock-persona", persona);
const bookingCard = async () =>
  (await screen.findByRole("heading", { name: "Booking" })).closest("[data-slot=card]") as HTMLElement;
const status = (card: HTMLElement) =>
  within(card).getByText("Status", { selector: "dt" }).nextElementSibling as HTMLElement;
const source = (card: HTMLElement) =>
  within(card).getByText("Source", { selector: "dt" }).nextElementSibling as HTMLElement;

function withIntervention(...items: Intervention[]) {
  const listInterventions = vi.fn(async () => ({ items }));
  return { listInterventions, dispatch: stubDispatchApi({ listInterventions }) };
}

describe("booking status (administrator)", () => {
  it.each([
    ["requested", "Pending", "Waiting for the contractor's calendar to answer.", "Not confirmed yet"],
    ["uncertain", "Uncertain", "BMC checks with it before sending again", "Not confirmed yet"],
    ["rejected", "Rejected", "The contractor's calendar is busy at that time.", "Not confirmed yet"],
    ["failed", "Failed", "The calendar stayed unavailable after five attempts.", "Not confirmed yet"],
    ["confirmed", "Confirmed", "CAL-SYN-9", "Calendar"],
  ] as const)("shows a %s booking with its source", async (state, label, detail, from) => {
    signInAs("administrator-a");
    const current = booking({
      state,
      source: state === "confirmed" ? "calendar" : null,
      externalRef: state === "confirmed" ? "CAL-SYN-9" : null,
      reasonCode: state === "rejected" ? "slot_conflict" : state === "failed" ? "retries_exhausted" : null,
      pendingAction: state === "requested" ? "create" : state === "uncertain" ? "reconcile" : null,
    });
    const { dispatch } = withIntervention(
      intervention({ state: state === "confirmed" ? "booked" : "scheduling_pending", booking: current }),
    );
    renderApp(`/issues/${UNIT_2B}`, { services: { dispatch } });

    const card = await bookingCard();
    expect(within(status(card)).getByText(label)).toBeInTheDocument();
    expect(within(card).getByText(detail, { exact: false })).toBeInTheDocument();
    expect(source(card)).toHaveTextContent(from);
    expect(within(card).getByText("Synthetic Plumbing Co.")).toBeInTheDocument();
  });

  it("names how a manual confirmation was agreed, and why", async () => {
    signInAs("administrator-a");
    renderApp(`/issues/${UNIT_2B}`);
    const card = await bookingCard();
    expect(within(status(card)).getByText("Confirmed")).toBeInTheDocument();
    expect(source(card)).toHaveTextContent("Manual, agreed by phone");
    expect(within(card).getByText("Agreed by phone after the calendar was unavailable.")).toBeInTheDocument();
    // A booked intervention cannot be confirmed again.
    expect(within(card).queryByRole("button", { name: "Confirm booking" })).not.toBeInTheDocument();
  });

  it("calls out a discrepancy with its reason", async () => {
    signInAs("administrator-a");
    const { dispatch } = withIntervention(
      intervention({
        state: "booked",
        booking: booking({ state: "discrepancy", source: "calendar", reasonCode: "missing_at_provider" }),
      }),
    );
    renderApp(`/issues/${UNIT_2B}`, { services: { dispatch } });
    const card = await bookingCard();
    const alert = within(card).getByRole("alert");
    expect(alert).toHaveTextContent("This booking needs checking");
    expect(alert).toHaveTextContent("The calendar no longer has this booking. BMC did not make it again.");
    expect(within(status(card)).getByText("Discrepancy")).toBeInTheDocument();
  });

  it("shows a pending cancellation until the calendar confirms it", async () => {
    signInAs("administrator-a");
    const { dispatch } = withIntervention(
      intervention({
        state: "cancellation_pending",
        booking: booking({ state: "confirmed", source: "calendar", pendingAction: "cancel" }),
        cancellation: { reasonCode: "no_longer_needed", note: null },
      }),
    );
    renderApp(`/issues/${UNIT_2B}`, { services: { dispatch } });
    const card = await bookingCard();
    expect(within(card).getByRole("alert")).toHaveTextContent("Cancellation pending");
    expect(within(card).getByText("Cancelling the booking in the calendar.")).toBeInTheDocument();
  });

  it("lists the earlier attempts with their reasons", async () => {
    signInAs("administrator-a");
    renderApp(`/issues/${LIFT}`);
    const card = await bookingCard();
    const earlier = within(card).getByRole("heading", { name: "Earlier booking attempts" }).parentElement!;
    expect(within(earlier).getByText("Rejected")).toBeInTheDocument();
    expect(within(earlier).getByText("The contractor's calendar is busy at that time.")).toBeInTheDocument();
    expect(within(status(card)).getByText("Failed")).toBeInTheDocument();
  });

  it("shows nothing while Dispatch does not know the issue, or before the terms are agreed", async () => {
    signInAs("administrator-a");
    const listInterventions = vi.fn(async () => {
      throw new ApiError(404, "NOT_FOUND", "Not found.");
    });
    const view = renderApp(`/issues/${UNIT_2B}`, { services: { dispatch: stubDispatchApi({ listInterventions }) } });
    await screen.findByRole("heading", { level: 1, name: "Unit 2B" });
    await waitFor(() => expect(listInterventions).toHaveBeenCalled());
    expect(screen.queryByRole("heading", { name: "Booking" })).not.toBeInTheDocument();
    view.unmount();

    const { dispatch } = withIntervention(intervention({ state: "sent", booking: null }));
    renderApp(`/issues/${UNIT_2B}`, { services: { dispatch } });
    await screen.findByRole("heading", { level: 1, name: "Unit 2B" });
    await waitFor(() => expect(dispatch.listInterventions).toHaveBeenCalled());
    expect(screen.queryByRole("heading", { name: "Booking" })).not.toBeInTheDocument();
  });

  it("is not shown to residents, and Dispatch is not asked", async () => {
    signInAs("resident-a1");
    const { dispatch, listInterventions } = withIntervention(intervention());
    renderApp(`/issues/${UNIT_2B}`, { services: { dispatch } });
    await screen.findByRole("heading", { level: 1, name: "Unit 2B" });
    expect(screen.queryByRole("heading", { name: "Booking" })).not.toBeInTheDocument();
    expect(listInterventions).not.toHaveBeenCalled();
  });
});

describe("manual confirmation (administrator)", () => {
  it("confirms the agreed window by hand, and the booking then shows as manual", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    renderApp(`/issues/${LIFT}`);
    const card = await bookingCard();
    const form = within(card).getByRole("form", { name: "Confirm the booking by hand" });
    // The agreed window is filled in.
    expect(within(form).getByLabelText("Start")).not.toHaveValue("");
    await user.selectOptions(within(form).getByRole("combobox", { name: "Agreed by" }), "Phone");
    await user.type(within(form).getByRole("textbox", { name: "Reason" }), "Agreed by phone this morning.");
    await user.click(within(form).getByRole("button", { name: "Confirm booking" }));

    await waitFor(() => expect(within(status(card)).getByText("Confirmed")).toBeInTheDocument());
    expect(source(card)).toHaveTextContent("Manual, agreed by phone");
    expect(within(card).getByText("Agreed by phone this morning.")).toBeInTheDocument();
    expect(within(card).queryByRole("form")).not.toBeInTheDocument();
  });

  it("explains missing fields, ties each message to its field and focuses the first one", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    const confirmManually = vi.fn();
    const { listInterventions } = withIntervention(intervention({ booking: booking({ state: "failed" }) }));
    renderApp(`/issues/${UNIT_2B}`, {
      services: { dispatch: stubDispatchApi({ listInterventions, confirmManually }) },
    });
    const form = within(await bookingCard()).getByRole("form", { name: "Confirm the booking by hand" });
    fireEvent.change(within(form).getByLabelText("Start"), { target: { value: "" } });
    await user.click(within(form).getByRole("button", { name: "Confirm booking" }));

    expect(within(form).getByRole("alert")).toHaveTextContent("The booking was not confirmed.");
    expect(within(form).getByLabelText("Start")).toHaveFocus();
    expect(within(form).getByLabelText("Start")).toHaveAccessibleDescription("Give the start.");
    expect(within(form).getByRole("combobox", { name: "Agreed by" })).toHaveAccessibleDescription(
      "Choose how it was agreed.",
    );
    expect(within(form).getByRole("textbox", { name: "Reason" })).toHaveAccessibleDescription(
      expect.stringContaining("Give a reason."),
    );
    expect(confirmManually).not.toHaveBeenCalled();
  });

  it("sends the window in UTC with the version it was opened on", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    const confirmManually = vi.fn(async () => ({
      interventionId: "x",
      state: "booked" as const,
      version: 5,
      bookingState: "confirmed" as const,
    }));
    const current = intervention({ version: 4, booking: booking({ state: "failed" }) });
    const { listInterventions } = withIntervention(current);
    renderApp(`/issues/${UNIT_2B}`, {
      services: { dispatch: stubDispatchApi({ listInterventions, confirmManually }) },
    });
    const form = within(await bookingCard()).getByRole("form", { name: "Confirm the booking by hand" });
    fireEvent.change(within(form).getByLabelText("Start"), { target: { value: "2030-01-15T09:30" } });
    fireEvent.change(within(form).getByLabelText("End"), { target: { value: "2030-01-15T11:00" } });
    await user.selectOptions(within(form).getByRole("combobox", { name: "Agreed by" }), "In person");
    await user.type(within(form).getByRole("textbox", { name: "Reason" }), "  Agreed on site.  ");
    await user.click(within(form).getByRole("button", { name: "Confirm booking" }));

    await waitFor(() => expect(confirmManually).toHaveBeenCalled());
    expect(confirmManually).toHaveBeenCalledWith(
      current.interventionId,
      4,
      {
        window: { start: fromLocalInput("2030-01-15T09:30"), end: fromLocalInput("2030-01-15T11:00") },
        source: "in_person",
        reason: "Agreed on site.",
      },
      expect.any(String),
    );
  });

  it("on a conflict, confirms nothing, keeps what was typed and offers to reload", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    const listInterventions = vi
      .fn()
      .mockResolvedValueOnce({ items: [intervention({ booking: booking({ state: "uncertain" }) })] })
      .mockResolvedValue({
        items: [
          intervention({ state: "booked", version: 5, booking: booking({ state: "confirmed", source: "calendar" }) }),
        ],
      });
    const confirmManually = vi.fn(async () => {
      throw new ApiError(409, "VERSION_CONFLICT", "Changed.", "c-77", { currentVersion: 5, currentState: "booked" });
    });
    renderApp(`/issues/${UNIT_2B}`, {
      services: { dispatch: stubDispatchApi({ listInterventions, confirmManually }) },
    });
    const card = await bookingCard();
    const form = within(card).getByRole("form", { name: "Confirm the booking by hand" });
    await user.selectOptions(within(form).getByRole("combobox", { name: "Agreed by" }), "Email");
    await user.type(within(form).getByRole("textbox", { name: "Reason" }), "Agreed by email.");
    await user.click(within(form).getByRole("button", { name: "Confirm booking" }));

    const alert = await within(form).findByRole("alert");
    expect(alert).toHaveTextContent("The intervention changed");
    expect(alert).toHaveTextContent("Reference: c-77");
    expect(within(form).getByRole("textbox", { name: "Reason" })).toHaveValue("Agreed by email.");

    await user.click(within(alert).getByRole("button", { name: "Reload" }));
    // The calendar confirmed it meanwhile: the form is gone and the booking shows its real source.
    await waitFor(() => expect(within(status(card)).getByText("Confirmed")).toBeInTheDocument());
    expect(source(card)).toHaveTextContent("Calendar");
    expect(within(card).queryByRole("form")).not.toBeInTheDocument();
  });

  it("shows the service's field errors under their fields", async () => {
    const user = userEvent.setup();
    signInAs("administrator-a");
    const confirmManually = vi.fn(async () => {
      throw new ApiError(400, "VALIDATION_FAILED", "Invalid.", undefined, {
        errors: [{ field: "window.start", reason: "not_in_future" }],
      });
    });
    const { listInterventions } = withIntervention(intervention({ booking: booking({ state: "failed" }) }));
    renderApp(`/issues/${UNIT_2B}`, {
      services: { dispatch: stubDispatchApi({ listInterventions, confirmManually }) },
    });
    const form = within(await bookingCard()).getByRole("form", { name: "Confirm the booking by hand" });
    await user.selectOptions(within(form).getByRole("combobox", { name: "Agreed by" }), "Phone");
    await user.type(within(form).getByRole("textbox", { name: "Reason" }), "By phone.");
    await user.click(within(form).getByRole("button", { name: "Confirm booking" }));

    await waitFor(() =>
      expect(within(form).getByLabelText("Start")).toHaveAccessibleDescription("The start must be in the future."),
    );
    expect(within(form).getByLabelText("Start")).toHaveFocus();
  });
});
