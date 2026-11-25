/*
 * Captures the browser's install prompt as early as possible (imported first in main.tsx, so it is listed in
 * package.json "sideEffects"). `beforeinstallprompt` is non-standard and Chromium-only (ADR-011).
 */

interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
  readonly userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

export type InstallPlatform = "prompt" | "ios" | "macos-safari" | "none";

let deferred: BeforeInstallPromptEvent | null = null;
const listeners = new Set<() => void>();
const notify = () => listeners.forEach((listener) => listener());

if (typeof window !== "undefined") {
  window.addEventListener("beforeinstallprompt", (event) => {
    event.preventDefault();
    deferred = event as BeforeInstallPromptEvent;
    notify();
  });
  window.addEventListener("appinstalled", () => {
    deferred = null;
    notify();
  });
}

export const subscribeInstall = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

export function isStandalone(): boolean {
  return (
    window.matchMedia("(display-mode: standalone)").matches ||
    (navigator as Navigator & { standalone?: boolean }).standalone === true
  );
}

/** How this browser installs, from the ADR-011 table. Recomputed on every prompt change. */
export function installPlatform(
  userAgent = navigator.userAgent,
  maxTouchPoints = navigator.maxTouchPoints,
): InstallPlatform {
  if (isStandalone()) return "none";
  if (deferred) return "prompt";
  const isIos = /iPhone|iPad|iPod/.test(userAgent) || (/Macintosh/.test(userAgent) && maxTouchPoints > 1);
  const isSafari = /Safari/.test(userAgent) && !/Chrome|Chromium|CriOS|FxiOS|EdgiOS|Edg\//.test(userAgent);
  if (isIos && isSafari) return "ios";
  if (/Macintosh/.test(userAgent) && isSafari) return "macos-safari";
  return "none";
}

export async function promptInstall(): Promise<void> {
  const event = deferred;
  if (!event) return;
  deferred = null;
  await event.prompt();
  await event.userChoice.catch(() => undefined);
  notify();
}
