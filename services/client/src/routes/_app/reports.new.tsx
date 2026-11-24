import { createFileRoute } from "@tanstack/react-router";
import { NewReportPage } from "@/features/reports";

export const Route = createFileRoute("/_app/reports/new")({
  component: NewReportPage,
});
