import {
  CalendarClock,
  CircleCheck,
  CircleDashed,
  CircleSlash,
  Hammer,
  Inbox,
  ListChecks,
  type LucideIcon,
  Route,
  Tags,
} from "lucide-react";
import type { IssueState } from "@/contracts/issue";
import { t } from "@/shared/i18n";
import { Badge } from "@/shared/ui/badge";

const ICONS: Record<IssueState, LucideIcon> = {
  received: Inbox,
  in_triage: CircleDashed,
  triaged: Tags,
  in_coordination: Route,
  scheduled: CalendarClock,
  in_progress: Hammer,
  awaiting_validation: ListChecks,
  resolved: CircleCheck,
  cancelled: CircleSlash,
};

/** State as icon + text, never colour alone (NFR-07). */
export function IssueStateBadge({ state }: { state: IssueState }) {
  const Icon = ICONS[state];
  return (
    <Badge variant={state === "resolved" || state === "cancelled" ? "secondary" : "outline"}>
      <Icon aria-hidden />
      {t.issues.states[state]}
    </Badge>
  );
}
