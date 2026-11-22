import { Building2, HardHat, ShieldCheck } from "lucide-react";
import { useSession } from "@/features/auth";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { Badge } from "@/shared/ui/badge";
import { Card, CardDescription, CardHeader, CardTitle } from "@/shared/ui/card";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/shared/ui/empty";

export function DashboardPage() {
  const session = useSession();
  const firstName = session.name.split(" ")[0] ?? session.name;

  return (
    <>
      <PageHeader title={t.dashboard.greeting(firstName)} subtitle={t.dashboard.subtitle} />

      <div className="mb-6 flex flex-wrap gap-2">
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
            <li key={membership.buildingId}>
              <Card>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2 text-base">
                    <Building2 className="size-4" aria-hidden />
                    {membership.buildingName}
                  </CardTitle>
                  <CardDescription className="flex flex-wrap items-center gap-2">
                    <Badge variant={membership.role === "administrator" ? "default" : "outline"}>
                      {t.dashboard.roles[membership.role]}
                    </Badge>
                    {membership.unit ? <span>{t.dashboard.unit(membership.unit)}</span> : null}
                  </CardDescription>
                </CardHeader>
              </Card>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
