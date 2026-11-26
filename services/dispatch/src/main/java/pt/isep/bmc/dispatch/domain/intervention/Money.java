package pt.isep.bmc.dispatch.domain.intervention;

import java.math.BigDecimal;
import java.util.Objects;

/** An amount in euros with at most two decimals, greater than zero (US-07 cost estimate). */
public record Money(BigDecimal amount, String currency) {

    public static final String EUR = "EUR";
    /** Fits {@code DECIMAL(9,2)}. */
    public static final BigDecimal MAX = new BigDecimal("9999999.99");

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (!EUR.equals(currency)) {
            throw new IllegalArgumentException("only EUR is supported");
        }
        if (amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("amount out of range");
        }
        amount = amount.setScale(2);
    }

    /** {@code 85.00}: two decimals, no exponent. */
    public String amountText() {
        return amount.toPlainString();
    }
}
