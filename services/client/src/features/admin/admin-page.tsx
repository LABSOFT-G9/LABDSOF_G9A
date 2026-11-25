import { ShieldCheck } from "lucide-react";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia } from "@/shared/ui/empty";

/** Role-guarded area (see src/routes/_app/admin.tsx): the example of `requirePlatformRole`. */
export function AdminPage() {
  return (
    <>
      <PageHeader title={t.admin.title} subtitle={t.admin.subtitle} />
      <Empty className="border">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <ShieldCheck aria-hidden />
          </EmptyMedia>
          <EmptyDescription>{t.admin.placeholder}</EmptyDescription>
        </EmptyHeader>
      </Empty>
    </>
  );
}
