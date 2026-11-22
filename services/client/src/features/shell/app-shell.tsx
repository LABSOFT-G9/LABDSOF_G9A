import { Link, useRouterState } from "@tanstack/react-router";
import { type ReactNode, useEffect, useRef } from "react";
import { useAppConfig } from "@/app/context";
import { InstallButton } from "@/features/install";
import { navFor } from "@/features/shell/nav";
import { UserMenu } from "@/features/shell/user-menu";
import type { Session } from "@/ports/auth-session";
import { Logo } from "@/shared/components/logo";
import { OfflineBanner } from "@/shared/components/offline-banner";
import { ThemeToggle } from "@/shared/components/theme-toggle";
import { t } from "@/shared/i18n";
import { Badge } from "@/shared/ui/badge";
import {
  Sidebar,
  SidebarContent,
  SidebarGroup,
  SidebarGroupContent,
  SIDEBAR_STORAGE_KEY,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarTrigger,
} from "@/shared/ui/sidebar";

/**
 * After a navigation inside the app, move focus to the new page's <h1> (or <main>), so keyboard and screen-reader
 * users land on the new content and hear its name, instead of staying on the link they used. Not on first load.
 */
function useFocusOnNavigation() {
  const pathname = useRouterState({ select: (state) => state.resolvedLocation?.pathname });
  const previous = useRef(pathname);
  useEffect(() => {
    if (previous.current === pathname) return;
    previous.current = pathname;
    const main = document.getElementById("main");
    const target = main?.querySelector<HTMLElement>("h1") ?? main;
    target?.focus();
  }, [pathname]);
}

const readSidebarOpen = () => {
  try {
    return localStorage.getItem(SIDEBAR_STORAGE_KEY) !== "false";
  } catch {
    return true;
  }
};

/**
 * The signed-in layout: a sidebar from 768 px, a bottom tab bar below it; a top bar with the install button, theme
 * toggle and account menu. Skip link first, one <main>, visible focus everywhere (NFR-07).
 */
export function AppShell({ session, children }: { session: Session; children: ReactNode }) {
  const { adapters } = useAppConfig();
  const items = navFor(session);
  useFocusOnNavigation();

  return (
    <SidebarProvider defaultOpen={readSidebarOpen()}>
      <a
        href="#main"
        className="bg-background focus-visible:ring-ring/50 sr-only z-50 rounded-md px-3 py-2 text-sm font-medium focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus-visible:ring-[3px]"
      >
        {t.app.skipToContent}
      </a>

      <Sidebar collapsible="icon" className="hidden md:flex">
        <SidebarHeader>
          <Link to="/dashboard" className="flex items-center gap-2 px-1 py-1.5">
            <Logo className="size-7 shrink-0" />
            <span className="truncate text-sm font-semibold group-data-[collapsible=icon]:hidden">
              {t.app.fullName}
            </span>
          </Link>
        </SidebarHeader>
        <SidebarContent>
          <SidebarGroup>
            <SidebarGroupContent>
              <nav aria-label={t.nav.main}>
                <SidebarMenu>
                  {items.map((item) => (
                    <SidebarMenuItem key={item.to}>
                      <SidebarMenuButton asChild tooltip={item.label}>
                        <Link to={item.to} activeProps={{ "data-active": true, "aria-current": "page" }}>
                          <item.icon aria-hidden />
                          <span>{item.label}</span>
                        </Link>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  ))}
                </SidebarMenu>
              </nav>
            </SidebarGroupContent>
          </SidebarGroup>
        </SidebarContent>
      </Sidebar>

      <SidebarInset className="min-w-0">
        <header className="bg-background/80 sticky top-0 z-30 border-b backdrop-blur pt-safe">
          <div className="flex h-14 items-center gap-2 px-4">
            <SidebarTrigger className="hidden md:inline-flex" />
            <Link to="/dashboard" className="flex items-center gap-2 md:hidden" aria-label={t.nav.dashboard}>
              <Logo />
            </Link>
            {adapters === "mock" ? (
              <Badge variant="outline" title={t.app.mockModeHint}>
                {t.app.mockMode}
              </Badge>
            ) : null}
            <div className="ml-auto flex items-center gap-1">
              <InstallButton />
              <ThemeToggle />
              <UserMenu session={session} />
            </div>
          </div>
          <OfflineBanner />
        </header>

        <main id="main" tabIndex={-1} className="mx-auto w-full max-w-5xl flex-1 px-4 py-6 pb-28 outline-none md:pb-10">
          {children}
        </main>

        <nav
          aria-label={t.nav.main}
          className="bg-background/95 fixed inset-x-0 bottom-0 z-30 border-t backdrop-blur md:hidden pb-safe"
        >
          <ul className="flex">
            {items.map((item) => (
              <li key={item.to} className="flex-1">
                <Link
                  to={item.to}
                  className="text-muted-foreground focus-visible:ring-ring/50 flex min-h-14 flex-col items-center justify-center gap-1 text-[11px] font-medium outline-none focus-visible:ring-[3px] focus-visible:ring-inset"
                  activeProps={{ className: "text-foreground", "aria-current": "page" }}
                >
                  <item.icon className="size-5" aria-hidden />
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      </SidebarInset>
    </SidebarProvider>
  );
}
