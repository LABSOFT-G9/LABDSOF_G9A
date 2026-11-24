import { CircleDashed, Sparkles, UserCheck } from "lucide-react";
import type { Issue } from "@/contracts/issue";
import { t } from "@/shared/i18n";
import { Badge } from "@/shared/ui/badge";

/**
 * Where the issue stands on triage, as icon + text (never colour alone): a human decision, an unconfirmed suggestion,
 * or nothing yet. A suggestion is never presented as a decision (US-02).
 */
export function TriageBadge({ issue }: { issue: Pick<Issue, "triage" | "suggestion"> }) {
  if (issue.triage) {
    return (
      <Badge variant="default">
        <UserCheck aria-hidden />
        {t.issues.categories[issue.triage.category]} · {t.issues.urgency[issue.triage.urgency]}
      </Badge>
    );
  }
  if (issue.suggestion) {
    return (
      <Badge variant="outline" className="border-dashed">
        <Sparkles aria-hidden />
        {t.issues.suggested(t.issues.origins[issue.suggestion.origin])}
      </Badge>
    );
  }
  return (
    <Badge variant="outline">
      <CircleDashed aria-hidden />
      {t.issues.pending}
    </Badge>
  );
}
