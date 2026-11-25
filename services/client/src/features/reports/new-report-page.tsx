import { ReportForm } from "@/features/reports/report-form";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";

export function NewReportPage() {
  return (
    <>
      <PageHeader title={t.reports.newTitle} subtitle={t.reports.newSubtitle} />
      <ReportForm />
    </>
  );
}
