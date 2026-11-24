import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { ApiError } from "@/shared/lib/errors";
import { renderApp } from "@/test/render-app";
import { stubIssueApi } from "@/test/fakes";

/** The whole client over mock adapters: routing, guards, session, shell and a data page together. */
describe("app (mock adapters)", () => {
  it("sends an anonymous visitor to the login page, then back where they were going", async () => {
    const user = userEvent.setup();
    const { router } = renderApp("/issues");

    await screen.findByRole("heading", { name: "Sign in to BMC" });
    expect(router.state.location.search).toMatchObject({ redirect: "/issues" });

    await user.click(screen.getByRole("button", { name: /Resident A1/ }));

    await screen.findByRole("heading", { level: 1, name: "Issues" });
    expect(router.state.location.pathname).toBe("/issues");
    expect(await screen.findByText("Lift stuck between floors 3 and 4")).toBeInTheDocument();
    // Membership filtering: Synthetic Building B's issue is not the resident's.
    expect(screen.queryByText("Water leak in the entrance hall")).not.toBeInTheDocument();
  });

  it("returns to a target with a query and a hash after sign-in", async () => {
    const user = userEvent.setup();
    const { router } = renderApp("/issues?building=a#results");

    await screen.findByRole("heading", { name: "Sign in to BMC" });
    await user.click(screen.getByRole("button", { name: /Resident A1/ }));

    const heading = await screen.findByRole("heading", { level: 1, name: "Issues" });
    await waitFor(() => expect(heading).toHaveFocus()); // focus follows the sign-in navigation too
    expect(router.state.location.pathname).toBe("/issues");
    expect(router.state.location.search).toEqual({ building: "a" });
    expect(router.state.location.hash).toBe("results");
  });

  it("titles each page and moves focus to its heading after navigating", async () => {
    const user = userEvent.setup();
    localStorage.setItem("bmc-mock-persona", "resident-a1");
    renderApp("/dashboard");

    await screen.findByRole("heading", { level: 1, name: "Hello, Synthetic" });
    await waitFor(() => expect(document.title).toBe("Hello, Synthetic · BMC"));

    const nav = screen.getAllByRole("navigation", { name: "Main" })[0]!;
    await user.click(within(nav).getByRole("link", { name: "Settings" }));

    const heading = await screen.findByRole("heading", { level: 1, name: "Settings" });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(document.title).toBe("Settings · BMC");
  });

  it("hides the admin area from non-staff as not found", async () => {
    localStorage.setItem("bmc-mock-persona", "resident-a1");
    renderApp("/admin");
    await screen.findByText("Page not found");
  });

  it("shows the admin area to platform staff", async () => {
    localStorage.setItem("bmc-mock-persona", "platform-staff");
    renderApp("/admin");
    await screen.findByRole("heading", { level: 1, name: "Admin" });
  });

  it("signs out to the login page", async () => {
    const user = userEvent.setup();
    localStorage.setItem("bmc-mock-persona", "administrator-a");
    const { router } = renderApp("/dashboard");

    await screen.findByRole("heading", { level: 1, name: "Hello, Synthetic" });
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(await screen.findByRole("menuitem", { name: "Sign out" }));

    await screen.findByRole("heading", { name: "Sign in to BMC" });
    expect(router.state.location.pathname).toBe("/login");
    expect(localStorage.getItem("bmc-mock-persona")).toBeNull();
  });

  it("ends the session globally when any request answers 401", async () => {
    localStorage.setItem("bmc-mock-persona", "resident-a1");
    const issues = stubIssueApi({
      list: async () => {
        throw new ApiError(401, "UNAUTHENTICATED", "Session expired.");
      },
    });
    const { router } = renderApp("/issues", { services: { issues } });

    await screen.findByRole("heading", { name: "Sign in to BMC" });
    await waitFor(() => expect(router.state.location.search).toMatchObject({ redirect: "/issues" }));
  });

  it("shows a failed request in place, with its reference and a retry", async () => {
    localStorage.setItem("bmc-mock-persona", "resident-a1");
    const issues = stubIssueApi({
      list: async () => {
        throw new ApiError(409, "INVALID_TRANSITION", "That change is no longer possible.", "corr-42");
      },
    });
    renderApp("/issues", { services: { issues } });

    const message = await screen.findByText("That change is no longer possible.");
    const box = message.closest<HTMLElement>("[role=status]");
    expect(box).not.toBeNull();
    expect(within(box!).getByText("Reference: corr-42")).toBeInTheDocument();
    expect(within(box!).getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
