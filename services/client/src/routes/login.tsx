import { createFileRoute, redirect } from "@tanstack/react-router";
import { z } from "zod";
import { LoginPage } from "@/features/auth";
import { safeRedirect } from "@/shared/lib/redirect";

const SearchSchema = z.object({ redirect: z.string().optional().catch(undefined) });

export const Route = createFileRoute("/login")({
  validateSearch: SearchSchema,
  beforeLoad: ({ context, search }) => {
    // Already signed in: straight to where they were going.
    if (context.auth.kind === "signed-in") throw redirect({ to: safeRedirect(search.redirect), replace: true });
  },
  component: LoginRoute,
});

function LoginRoute() {
  const { auth } = Route.useRouteContext();
  const { redirect: target } = Route.useSearch();
  return <LoginPage auth={auth} redirectTo={safeRedirect(target)} />;
}
