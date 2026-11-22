import { Monitor, Moon, Sun } from "lucide-react";
import { useAppConfig } from "@/app/context";
import { PageHeader } from "@/shared/components/page-header";
import { t } from "@/shared/i18n";
import { type Theme, useTheme } from "@/shared/theme";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/shared/ui/card";
import { cn } from "@/shared/lib/utils";

const OPTIONS: { value: Theme; label: string; icon: typeof Sun }[] = [
  { value: "system", label: t.theme.system, icon: Monitor },
  { value: "light", label: t.theme.light, icon: Sun },
  { value: "dark", label: t.theme.dark, icon: Moon },
];

export function SettingsPage() {
  const { theme, setTheme } = useTheme();
  const { adapters } = useAppConfig();

  return (
    <>
      <PageHeader title={t.settings.title} subtitle={t.settings.subtitle} />
      <div className="space-y-4">
        <Card>
          <CardHeader>
            <CardTitle id="theme-label" className="text-base">
              {t.theme.label}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {/* Native radios: arrow keys, focus and screen-reader semantics come for free. */}
            <fieldset aria-labelledby="theme-label" className="grid grid-cols-3 gap-2">
              {OPTIONS.map(({ value, label, icon: Icon }) => (
                <label
                  key={value}
                  className={cn(
                    "has-focus-visible:ring-ring/50 flex min-h-16 cursor-pointer flex-col items-center justify-center gap-1.5 rounded-md border text-sm has-focus-visible:ring-[3px]",
                    theme === value ? "border-foreground bg-accent font-medium" : "hover:bg-accent/60",
                  )}
                >
                  <input
                    type="radio"
                    name="theme"
                    value={value}
                    checked={theme === value}
                    onChange={() => setTheme(value)}
                    className="sr-only"
                  />
                  <Icon className="size-4" aria-hidden />
                  {label}
                </label>
              ))}
            </fieldset>
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t.settings.adapters}</CardTitle>
            <CardDescription>{t.settings.adaptersValue(adapters)}</CardDescription>
          </CardHeader>
        </Card>
      </div>
    </>
  );
}
