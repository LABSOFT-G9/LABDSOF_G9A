import { CloudOff, ListChecks, type LucideIcon, Sparkles } from "lucide-react";
import type { SuggestionOrigin } from "@/contracts/issue";

/**
 * One icon per origin (US-14-03), always next to words: the AI, the rules, or the rules because the AI was unavailable.
 * A human decision has its own (`UserCheck`).
 */
export const ORIGIN_ICONS: Record<SuggestionOrigin, LucideIcon> = {
  ai: Sparkles,
  rules: ListChecks,
  fallback: CloudOff,
};
