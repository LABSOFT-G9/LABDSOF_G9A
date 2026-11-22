import { createContext, type ReactNode, use, useCallback, useEffect, useState } from "react";

export type Theme = "light" | "dark" | "system";
/** Same key as public/theme-init.js, which applies the theme before the first paint. */
const KEY = "bmc-theme";
const COLORS = { light: "#ffffff", dark: "#0a0a0a" } as const;

const read = (): Theme => {
  try {
    const value = localStorage.getItem(KEY);
    return value === "light" || value === "dark" ? value : "system";
  } catch {
    return "system";
  }
};

const prefersDark = () => window.matchMedia("(prefers-color-scheme: dark)").matches;

function apply(theme: Theme) {
  const dark = theme === "dark" || (theme === "system" && prefersDark());
  document.documentElement.classList.toggle("dark", dark);
  document.documentElement.style.colorScheme = dark ? "dark" : "light";
  for (const meta of document.querySelectorAll('meta[name="theme-color"]')) {
    // System: each tag goes back to the colour of its own media query (index.html's pair). Explicit: both follow it.
    const forDark = theme === "system" ? (meta.getAttribute("media") ?? "").includes("dark") : dark;
    meta.setAttribute("content", forDark ? COLORS.dark : COLORS.light);
  }
}

interface ThemeValue {
  readonly theme: Theme;
  readonly resolved: "light" | "dark";
  setTheme(theme: Theme): void;
}

const ThemeContext = createContext<ThemeValue | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(read);
  const [systemDark, setSystemDark] = useState(prefersDark);

  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => setSystemDark(media.matches);
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, []);

  useEffect(() => apply(theme), [theme, systemDark]);

  const setTheme = useCallback((next: Theme) => {
    try {
      if (next === "system") localStorage.removeItem(KEY);
      else localStorage.setItem(KEY, next);
    } catch {
      // Storage blocked: the choice lasts for this page only.
    }
    setThemeState(next);
  }, []);

  const resolved = theme === "system" ? (systemDark ? "dark" : "light") : theme;
  return <ThemeContext value={{ theme, resolved, setTheme }}>{children}</ThemeContext>;
}

export function useTheme(): ThemeValue {
  const value = use(ThemeContext);
  if (!value) throw new Error("useTheme() must be used inside <ThemeProvider>.");
  return value;
}
