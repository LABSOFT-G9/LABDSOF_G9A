import { Building2, HardHat, ShieldCheck } from "lucide-react";
import { useSession } from "@/features/auth";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { Badge } from "@/shared/ui/badge";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/shared/ui/empty";

export function DashboardPage() {
  const session = useSession();
  const firstName = session.name.split(" ")[0] ?? session.name;

  return (
    <>
      <PageHeader title={t.dashboard.greeting(firstName)} subtitle={t.dashboard.subtitle} />

      <div className="mb-6 flex flex-wrap gap-2 empty:hidden">
        {session.platformRoles.includes("platform_staff") ? (
          <Badge variant="secondary">
            <ShieldCheck aria-hidden />
            {t.dashboard.platformStaff}
          </Badge>
        ) : null}
        {session.contractor ? (
          <Badge variant="secondary">
            <HardHat aria-hidden />
            {t.dashboard.contractor(session.contractor.status)}
          </Badge>
        ) : null}
      </div>

      {session.memberships.length === 0 ? (
        <Empty className="border">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <Building2 aria-hidden />
            </EmptyMedia>
            <EmptyTitle>{t.dashboard.noMemberships}</EmptyTitle>
            <EmptyDescription>{t.dashboard.noMembershipsHint}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2">
          {session.memberships.map((membership) => (
            // A directory plate, as in a building's lobby: the building's name large, your place in it below.
            <li
              key={membership.buildingId}
              className="bg-sidebar text-sidebar-foreground dark:bg-secondary flex min-h-36 flex-col justify-between gap-6 rounded-lg p-5"
            >
              <h2 className="flex items-start gap-3 text-2xl">
                <Building2 className="mt-1 size-5 shrink-0 opacity-70" aria-hidden />
                {membership.buildingName}
              </h2>
              <p className="border-sidebar-border flex flex-wrap items-center justify-between gap-2 border-t pt-3 text-sm">
                <span className="font-semibold">{t.dashboard.roles[membership.role]}</span>
                {membership.unit ? <span className="tabular">{t.dashboard.unit(membership.unit)}</span> : null}
              </p>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
