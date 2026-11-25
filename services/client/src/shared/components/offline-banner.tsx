import { WifiOff } from "lucide-react";
import { useOnline } from "@/shared/hooks/use-online";
import { t } from "@/shared/i18n";

/** Online-only app (ADR-011): say so while the connection is gone. Announced politely, without moving focus. */
export function OfflineBanner() {
  const online = useOnline();
  return (
    <div role="status" aria-live="polite">
      {online ? null : (
        <p className="bg-foreground text-background flex items-center justify-center gap-2 px-4 py-2 text-sm">
          <WifiOff className="size-4 shrink-0" aria-hidden />
          {t.common.offline}
        </p>
      )}
    </div>
  );
}
