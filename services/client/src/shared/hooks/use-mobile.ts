import { useSyncExternalStore } from "react";

/**
 * Below this the app is the phone layout: bottom tabs instead of the sidebar (which becomes a sheet).
 * Matches Tailwind's `md` (the shell switches its classes at `md:`).
 */
const MOBILE_BREAKPOINT = 768;
const QUERY = `(max-width: ${MOBILE_BREAKPOINT - 1}px)`;

const subscribe = (onChange: () => void) => {
  const media = window.matchMedia(QUERY);
  media.addEventListener("change", onChange);
  return () => media.removeEventListener("change", onChange);
};

export function useIsMobile(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(QUERY).matches,
    () => false,
  );
}
