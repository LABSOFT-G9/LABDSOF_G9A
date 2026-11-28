package pt.isep.bmc.issue.domain.issue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import pt.isep.bmc.issue.domain.report.ReportText;

/**
 * What one run of the critical check found: either the rule set ran and these
 * are the codes that matched (maybe none), or it did not run. The second case
 * keeps the issue's check {@code pending}: a report is never lost, and its
 * receipt never waits, because a rule set failed (US-14).
 *
 * <p>{@link #failure()} names the exception class only. A message could quote
 * the report, which never goes to a log.
 */
public final class CriticalFindings {

    /** A rule's stable code, as the API and the history show it. */
    static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final int MAX_VERSION_LENGTH = 100;

    private final String rulesVersion;
    private final List<String> codes;
    private final String failure;

    private CriticalFindings(String rulesVersion, List<String> codes, String failure) {
        this.rulesVersion = rulesVersion;
        this.codes = codes;
        this.failure = failure;
    }

    /** Runs the rules on the text. Never throws: a rule set that fails, or answers nonsense, did not check. */
    public static CriticalFindings of(CriticalRules rules, ReportText text) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(text, "text");
        try {
            String version = rules.version();
            List<String> codes = List.copyOf(new LinkedHashSet<>(rules.evaluate(text)));
            if (version == null || version.isBlank() || version.length() > MAX_VERSION_LENGTH
                    || !codes.stream().allMatch(code -> CODE.matcher(code).matches())) {
                return new CriticalFindings(null, List.of(), IllegalStateException.class.getName());
            }
            return new CriticalFindings(version, codes, null);
        } catch (RuntimeException e) {
            return new CriticalFindings(null, List.of(), e.getClass().getName());
        }
    }

    /** A check that ran and matched nothing; the start for {@link #and}. */
    public static CriticalFindings none(String rulesVersion) {
        return new CriticalFindings(Objects.requireNonNull(rulesVersion, "rulesVersion"), List.of(), null);
    }

    /** A check that did not run, for a reason other than the rule set throwing. */
    public static CriticalFindings notChecked(String failure) {
        return new CriticalFindings(null, List.of(), Objects.requireNonNull(failure, "failure"));
    }

    /**
     * This check and another one on the same issue (one per report): the
     * codes of both when both ran, otherwise not checked. An issue is never
     * "checked" on some of its reports only.
     */
    public CriticalFindings and(CriticalFindings other) {
        if (!completed()) {
            return this;
        }
        if (!other.completed()) {
            return other;
        }
        LinkedHashSet<String> both = new LinkedHashSet<>(codes);
        both.addAll(other.codes);
        return new CriticalFindings(rulesVersion, List.copyOf(both), null);
    }

    /** Whether the rule set ran. False means "not checked", whatever {@link #codes()} says. */
    public boolean completed() {
        return failure == null;
    }

    /** The rule set that ran; empty when it did not. */
    public Optional<String> rulesVersion() {
        return Optional.ofNullable(rulesVersion);
    }

    /** The codes that matched, in the rule set's order; always empty when the check did not run. */
    public List<String> codes() {
        return codes;
    }

    /** The class of the exception that stopped the check; empty when it ran. */
    public Optional<String> failure() {
        return Optional.ofNullable(failure);
    }
}
