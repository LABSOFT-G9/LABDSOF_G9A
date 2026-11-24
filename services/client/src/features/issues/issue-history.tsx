import {
  CalendarCheck,
  CalendarClock,
  CircleCheck,
  CircleSlash,
  ClipboardCheck,
  Hammer,
  HandHelping,
  Inbox,
  Lock,
  type LucideIcon,
  Siren,
  Sparkles,
  ThumbsDown,
  ThumbsUp,
  UserCheck,
} from "lucide-react";
import { type HistoryEntry, KNOWN_HISTORY_TYPES, type KnownHistoryType } from "@/contracts/issue";
import { ORIGIN_ICONS } from "@/features/issues/origin-icons";
import { t } from "@/shared/i18n";
import { formatDateTime } from "@/shared/lib/format";
import { Badge } from "@/shared/ui/badge";

const ICONS: Record<KnownHistoryType, LucideIcon> = {
  report_received: Inbox,
  triage_suggested: Sparkles,
  triage_decided: UserCheck,
  critical_alert_raised: Siren,
  intervention_approved: ThumbsUp,
  contractor_accepted: HandHelping,
  contractor_declined: ThumbsDown,
  booking_confirmed: CalendarCheck,
  booking_confirmed_manually: CalendarClock,
  intervention_started: Hammer,
  completion_reported: ClipboardCheck,
  resolved: CircleCheck,
  cancelled: CircleSlash,
};

const isKnown = (type: string): type is KnownHistoryType => (KNOWN_HISTORY_TYPES as readonly string[]).includes(type);

/** A suggestion's entry names who suggested it (US-14-03); without an origin it stays generic. */
function describe(entry: HistoryEntry, type: KnownHistoryType | null): { label: string; Icon: LucideIcon } {
  if (type === "triage_suggested" && entry.origin) {
    return { label: t.issues.suggestedBy[entry.origin], Icon: ORIGIN_ICONS[entry.origin] };
  }
  return { label: type ? t.issues.events[type] : t.issues.events.other, Icon: type ? ICONS[type] : Inbox };
}

/**
 * The issue's history, oldest first. Each step has its own icon and words, so approval, the contractor's answer and
 * the booking (manual or confirmed with the calendar) never blur into one status (US-02). Internal entries, which only
 * administrators receive, say so. A suggestion recorded after a human decision is kept with its own source but changed
 * nothing (technical design §3.3), so it says it was not applied.
 */
export function IssueHistory({ entries }: { entries: readonly HistoryEntry[] }) {
  if (entries.length === 0) return <p className="text-muted-foreground text-sm">{t.issues.historyEmpty}</p>;
  const firstDecision = entries.find((entry) => entry.type === "triage_decided")?.sequence;
  return (
    <ol className="space-y-4">
      {entries.map((entry) => {
        const type = isKnown(entry.type) ? entry.type : null;
        const { label, Icon } = describe(entry, type);
        const late = type === "triage_suggested" && firstDecision !== undefined && entry.sequence > firstDecision;
        return (
          <li key={entry.sequence} className="flex gap-3">
            <Icon className="text-muted-foreground mt-0.5 size-4 shrink-0" aria-hidden />
            <div className="min-w-0 space-y-1 text-sm">
              <p className="font-medium">{label}</p>
              <p className="text-muted-foreground">
                {t.issues.actors[entry.actorRole]} ·{" "}
                <time dateTime={entry.occurredAt}>{formatDateTime(entry.occurredAt)}</time>
              </p>
              {entry.category && entry.urgency ? (
                <p>
                  {t.issues.categories[entry.category]} · {t.issues.urgency[entry.urgency]}
                </p>
              ) : null}
              {entry.reason ? (
                <p className="break-words whitespace-pre-wrap">
                  <span className="text-muted-foreground">{t.issues.reason}: </span>
                  {entry.reason}
                </p>
              ) : null}
              {late ? (
                <p className="text-muted-foreground flex items-center gap-1">
                  <CircleSlash className="size-3.5 shrink-0" aria-hidden />
                  {t.issues.lateSuggestion}
                </p>
              ) : null}
              {entry.visibility === "internal" ? (
                <Badge variant="outline">
                  <Lock aria-hidden />
                  {t.issues.internal}
                </Badge>
              ) : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
