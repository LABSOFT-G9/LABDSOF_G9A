package pt.isep.bmc.issue.domain.issue;

import java.util.List;
import pt.isep.bmc.issue.domain.report.ReportText;

/**
 * A versioned set of rules that look for signs of danger in a report
 * (technical design 5, step 1; ADR-008, ownership). Issue's own, independent
 * of the AI triage service: it needs no provider, no broker and no network.
 *
 * <p>A rule set may fail. {@link CriticalFindings#of} turns a failure into
 * "not checked", which is never shown as "checked, nothing found".
 */
public interface CriticalRules {

    /** For example {@code critical-alerts-v1}; stored with every signal. */
    String version();

    /** The codes of the rules the text matches, each once; empty when none does. */
    List<String> evaluate(ReportText text);
}
