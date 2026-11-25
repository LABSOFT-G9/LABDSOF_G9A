import { Download, Share } from "lucide-react";
import { useSyncExternalStore } from "react";
import { installPlatform, promptInstall, subscribeInstall } from "@/features/install/install";
import { t } from "@/shared/i18n";
import { Button } from "@/shared/ui/button";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from "@/shared/ui/sheet";

/** One-click install on Chromium; guided steps on Safari; nothing when installed or unsupported (ADR-011). */
export function InstallButton() {
  const platform = useSyncExternalStore(
    subscribeInstall,
    () => installPlatform(),
    () => "none" as const,
  );

  if (platform === "prompt") {
    return (
      <Button variant="ghost" size="sm" onClick={() => void promptInstall()}>
        <Download aria-hidden />
        <span className="hidden sm:inline">{t.pwa.install}</span>
        <span className="sr-only sm:hidden">{t.pwa.install}</span>
      </Button>
    );
  }
  if (platform !== "ios" && platform !== "macos-safari") return null;

  return (
    <Sheet>
      <SheetTrigger asChild>
        <Button variant="ghost" size="sm">
          <Download aria-hidden />
          <span className="hidden sm:inline">{t.pwa.install}</span>
          <span className="sr-only sm:hidden">{t.pwa.install}</span>
        </Button>
      </SheetTrigger>
      <SheetContent side="bottom" className="pb-safe">
        <SheetHeader>
          <SheetTitle>{t.pwa.installTitle}</SheetTitle>
          <SheetDescription>{platform === "ios" ? null : t.pwa.macSafari}</SheetDescription>
        </SheetHeader>
        {platform === "ios" ? (
          <ol className="space-y-3 px-4 pb-6 text-sm">
            {t.pwa.iosSteps.map((step, index) => (
              <li key={step} className="flex items-center gap-3">
                <span className="bg-foreground text-background flex size-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold">
                  {index + 1}
                </span>
                {step}
                {index === 0 ? <Share className="size-4" aria-hidden /> : null}
              </li>
            ))}
          </ol>
        ) : null}
      </SheetContent>
    </Sheet>
  );
}
