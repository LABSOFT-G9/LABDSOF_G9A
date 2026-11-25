import { Moon, Sun } from "lucide-react";
import { t } from "@/shared/i18n";
import { useTheme } from "@/shared/theme";
import { Button } from "@/shared/ui/button";

/** Flips between light and dark. "Follow the system" is in Settings. */
export function ThemeToggle() {
  const { resolved, setTheme } = useTheme();
  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label={t.theme.toggle}
      onClick={() => setTheme(resolved === "dark" ? "light" : "dark")}
    >
      {resolved === "dark" ? <Sun aria-hidden /> : <Moon aria-hidden />}
    </Button>
  );
}
