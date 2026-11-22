import { createFileRoute } from "@tanstack/react-router";
import { AdminPage } from "@/features/admin";
import { requirePlatformRole } from "@/features/auth";

/** Role-guarded: anyone without `platform_staff` gets "not found", so the area's existence is not revealed. */
export const Route = createFileRoute("/_app/admin")({
  beforeLoad: ({ context }) => requirePlatformRole(context.session, "platform_staff"),
  component: AdminPage,
});
