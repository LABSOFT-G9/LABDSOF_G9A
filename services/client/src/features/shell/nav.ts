import { LayoutDashboard, type LucideIcon, Settings, ShieldCheck, Users, Wrench } from "lucide-react";
import { hasPlatformRole, isBuildingMember } from "@/features/auth";
import type { Session } from "@/ports/auth-session";
import { t } from "@/shared/i18n";

export interface NavItem {
  readonly to: "/dashboard" | "/issues" | "/example" | "/admin" | "/settings";
  readonly label: string;
  readonly icon: LucideIcon;
  /** Hides the entry for users who could not use it. UX only: the route and the API enforce access themselves. */
  readonly visible?: (session: Session) => boolean;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { to: "/dashboard", label: t.nav.dashboard, icon: LayoutDashboard },
  { to: "/issues", label: t.nav.issues, icon: Wrench, visible: isBuildingMember },
  { to: "/example", label: t.nav.example, icon: Users },
  { to: "/admin", label: t.nav.admin, icon: ShieldCheck, visible: (s) => hasPlatformRole(s, "platform_staff") },
  { to: "/settings", label: t.nav.settings, icon: Settings },
];

export const navFor = (session: Session) => NAV_ITEMS.filter((item) => item.visible?.(session) ?? true);
