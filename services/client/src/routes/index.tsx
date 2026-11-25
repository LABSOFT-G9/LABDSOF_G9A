import { createFileRoute, redirect } from "@tanstack/react-router";
import { safeRedirect } from "@/shared/lib/redirect";
import { takeReturnTo } from "@/shared/lib/return-to";

/**
 * `/` is where the edge lands after sign-in (#80). With a session, go back to the page the sign-in started from, if
 * one was remembered; otherwise to the dashboard, whose guard sends anyone without a session to the login page.
 */
export const Route = createFileRoute("/")({
  beforeLoad: ({ context }) => {
    if (context.auth.kind === "signed-in") {
      // `href`, because the remembered path may carry ?query and #hash; `safeRedirect`, because storage is untrusted.
      throw redirect({ href: safeRedirect(takeReturnTo()), replace: true });
    }
    throw redirect({ to: "/dashboard", replace: true });
  },
});
