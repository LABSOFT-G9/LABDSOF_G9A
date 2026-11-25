/** Stand-in for vite-plugin-pwa's `virtual:pwa-register/react` in tests (see vitest.config.ts). */
export function useRegisterSW() {
  const noop = () => undefined;
  return {
    needRefresh: [false, noop] as const,
    offlineReady: [false, noop] as const,
    updateServiceWorker: async () => undefined,
  };
}
