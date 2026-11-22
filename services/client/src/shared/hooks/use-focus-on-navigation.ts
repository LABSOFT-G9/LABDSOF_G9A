import { useRouterState } from "@tanstack/react-router";
import { useEffect, useRef } from "react";

/**
 * After every navigation (sign-in and sign-out included), move focus to the new page's <h1>, or its <main>, so
 * keyboard and screen-reader users land on the new content and hear its name (WCAG 2.4.3). Not on the first load,
 * and without scrolling: the router owns scroll restoration and #hash targets. Used once, in the root route.
 */
export function useFocusOnNavigation() {
  const pathname = useRouterState({ select: (state) => state.resolvedLocation?.pathname });
  const previous = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (pathname === undefined) return;
    const first = previous.current === undefined;
    const changed = previous.current !== pathname;
    previous.current = pathname;
    if (first || !changed) return;
    const main = document.getElementById("main");
    const target = main?.querySelector<HTMLElement>("h1") ?? main;
    target?.focus({ preventScroll: true });
  }, [pathname]);
}
