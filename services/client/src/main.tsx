// First: catches the browser's install prompt even if it fires before React mounts.
import "@/features/install/install";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AppRouterProvider, createAppRouter } from "@/app/router";
import { ThemeProvider } from "@/shared/theme";
import { TooltipProvider } from "@/shared/ui/tooltip";
import "@/styles.css";

const router = createAppRouter();

createRoot(document.getElementById("root") as HTMLElement).render(
  <StrictMode>
    <ThemeProvider>
      <TooltipProvider delayDuration={300}>
        <AppRouterProvider router={router} />
      </TooltipProvider>
    </ThemeProvider>
  </StrictMode>,
);
