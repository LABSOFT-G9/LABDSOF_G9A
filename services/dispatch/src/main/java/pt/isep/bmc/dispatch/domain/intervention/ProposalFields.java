package pt.isep.bmc.dispatch.domain.intervention;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import pt.isep.bmc.dispatch.domain.shared.FieldViolation;
import pt.isep.bmc.dispatch.domain.shared.PlainText;

/**
 * Field rules shared by every proposal version (US-06, US-07): a draft,
 * changed terms and a counterproposal. Each adds its violations to the list,
 * so a request reports every invalid field at once.
 */
public final class ProposalFields {

    static final int SCOPE_MIN = 10;
    static final int SCOPE_MAX = 2_000;
    static final int TERMS_MIN = 1;
    static final int TERMS_MAX = 1_000;

    static final String FIELD_SCOPE = "scopeExtract";
    static final String FIELD_WINDOW_START = "window.start";
    static final String FIELD_WINDOW_END = "window.end";
    static final String FIELD_TERMS = "terms";
    static final String FIELD_COST_AMOUNT = "proposedCost.amount";
    static final String FIELD_COST_CURRENCY = "proposedCost.currency";
    /** The last instant a MySQL {@code DATETIME(3)} column holds, in UTC. */
    static final Instant LATEST_STORABLE = Instant.parse("9999-12-31T23:59:59.999Z");

    /** A decimal with at most two decimals, as text so that no JSON number is rounded. */
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,7}(\\.[0-9]{1,2})?");

    private ProposalFields() {
    }

    static Optional<String> scope(String raw, List<FieldViolation> violations) {
        return text(raw, FIELD_SCOPE, SCOPE_MIN, SCOPE_MAX, violations);
    }

    static Optional<String> terms(String raw, List<FieldViolation> violations) {
        return text(raw, FIELD_TERMS, TERMS_MIN, TERMS_MAX, violations);
    }

    public static Optional<String> text(String raw, String field, int min, int max, List<FieldViolation> violations) {
        Optional<String> reason = PlainText.violation(raw, min, max);
        reason.ifPresent(r -> violations.add(new FieldViolation(field, r)));
        return reason.isPresent() ? Optional.empty() : Optional.of(raw.strip());
    }

    /**
     * RFC 3339 with an offset, kept in UTC at millisecond precision; the start
     * is in the future and the end after it.
     */
    public static Optional<TimeWindow> window(String rawStart, String rawEnd, Instant now, List<FieldViolation> violations) {
        Optional<Instant> start = instant(rawStart, FIELD_WINDOW_START, violations);
        Optional<Instant> end = instant(rawEnd, FIELD_WINDOW_END, violations);
        boolean valid = start.isPresent() && end.isPresent();
        if (start.isPresent() && !start.get().isAfter(now)) {
            violations.add(new FieldViolation(FIELD_WINDOW_START, "not_in_future"));
            valid = false;
        }
        if (start.isPresent() && end.isPresent() && !end.get().isAfter(start.get())) {
            violations.add(new FieldViolation(FIELD_WINDOW_END, "not_after_start"));
            valid = false;
        }
        return valid ? Optional.of(new TimeWindow(start.get(), end.get())) : Optional.empty();
    }

    /**
     * An optional cost estimate: both fields or neither. The amount is a
     * decimal string greater than zero, at most 9,999,999.99; the currency is
     * {@code EUR}.
     */
    static Optional<Money> cost(String rawAmount, String rawCurrency, List<FieldViolation> violations) {
        if (rawAmount == null && rawCurrency == null) {
            return Optional.empty();
        }
        boolean valid = true;
        if (rawAmount == null || rawAmount.isBlank()) {
            violations.add(new FieldViolation(FIELD_COST_AMOUNT, "required"));
            valid = false;
        } else if (!AMOUNT.matcher(rawAmount).matches()) {
            violations.add(new FieldViolation(FIELD_COST_AMOUNT, "invalid_format"));
            valid = false;
        } else if (new BigDecimal(rawAmount).signum() <= 0) {
            violations.add(new FieldViolation(FIELD_COST_AMOUNT, "not_positive"));
            valid = false;
        }
        if (rawCurrency == null || rawCurrency.isBlank()) {
            violations.add(new FieldViolation(FIELD_COST_CURRENCY, "required"));
            valid = false;
        } else if (!Money.EUR.equals(rawCurrency)) {
            violations.add(new FieldViolation(FIELD_COST_CURRENCY, "unsupported"));
            valid = false;
        }
        return valid ? Optional.of(new Money(new BigDecimal(rawAmount), rawCurrency)) : Optional.empty();
    }

    static Optional<Instant> instant(String raw, String field, List<FieldViolation> violations) {
        if (raw == null || raw.isBlank()) {
            violations.add(new FieldViolation(field, "required"));
            return Optional.empty();
        }
        Instant instant;
        try {
            instant = OffsetDateTime.parse(raw).toInstant().truncatedTo(ChronoUnit.MILLIS);
        } catch (DateTimeParseException e) {
            violations.add(new FieldViolation(field, "invalid_format"));
            return Optional.empty();
        }
        if (instant.isAfter(LATEST_STORABLE)) {
            violations.add(new FieldViolation(field, "out_of_range"));
            return Optional.empty();
        }
        return Optional.of(instant);
    }
}
