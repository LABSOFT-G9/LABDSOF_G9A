import { createFileRoute, redirect } from "@tanstack/react-router";

/** `/` → the dashboard; its guard sends anyone without a session to the login page. */
export const Route = createFileRoute("/")({
  beforeLoad: () => {
    throw redirect({ to: "/dashboard", replace: true });
  },
});
