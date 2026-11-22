import { useEffect } from "react";
import { toast } from "sonner";
import { useRegisterSW } from "virtual:pwa-register/react";
import { t } from "@/shared/i18n";

const CHECK_EVERY_MS = 60 * 60_000;

/**
 * Registers the service worker and, when a new version is waiting, asks before reloading ("prompt", ADR-011):
 * never a silent reload, so a half-filled form is never lost.
 */
export function UpdatePrompt() {
  const {
    needRefresh: [needRefresh],
    updateServiceWorker,
  } = useRegisterSW({
    onRegisteredSW(_url, registration) {
      if (!registration) return;
      // Long-lived tabs and installed apps check for a new version every hour.
      setInterval(() => void registration.update().catch(() => undefined), CHECK_EVERY_MS);
    },
  });

  useEffect(() => {
    if (!needRefresh) return;
    toast(t.pwa.updateAvailable, {
      id: "pwa-update",
      duration: Number.POSITIVE_INFINITY,
      action: { label: t.pwa.reload, onClick: () => void updateServiceWorker(true) },
    });
  }, [needRefresh, updateServiceWorker]);

  return null;
}
