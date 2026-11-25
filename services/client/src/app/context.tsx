import type { QueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { createContext, type ReactNode, use } from "react";
import type { AppConfig } from "@/app/config";
import type { Services } from "@/ports/services";

/** What every loader, guard and component may depend on. Lives on the router context (loaders, guards). */
export interface AppContext {
  readonly queryClient: QueryClient;
  readonly services: Services;
  readonly config: AppConfig;
}

const ServicesContext = createContext<Pick<AppContext, "services" | "config"> | null>(null);

/** Makes the container's ports available to components (ADR-009's `<ServicesProvider>` / `useServices()`). */
export function ServicesProvider({
  services,
  config,
  children,
}: Pick<AppContext, "services" | "config"> & { children: ReactNode }) {
  return <ServicesContext value={{ services, config }}>{children}</ServicesContext>;
}

function useAppServices() {
  const value = use(ServicesContext);
  if (!value) throw new Error("useServices() must be used inside <ServicesProvider>.");
  return value;
}

/** The ports, wired by the composition root. Components call ports only through here (or through query options). */
export const useServices = (): Services => useAppServices().services;

export const useAppConfig = (): AppConfig => useAppServices().config;

/** The full context (query client included), for code that orchestrates more than one port, like signing out. */
export function useAppContext(): AppContext {
  return useRouter().options.context;
}
