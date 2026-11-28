package pt.isep.bmc.notification.domain.notification;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A delivery channel (ADR-007): {@code in_app} now; {@code email} or
 * {@code web_push} would be further adapters behind the same port. Stored in
 * the delivery log, so it is a short lower-case token.
 */
public record ChannelId(String value) {

    // Before IN_APP: static fields initialise in order, and IN_APP's constructor uses it.
    private static final Pattern FORM = Pattern.compile("[a-z][a-z0-9_]{0,15}");

    public static final ChannelId IN_APP = new ChannelId("in_app");

    public ChannelId {
        Objects.requireNonNull(value, "value");
        if (!FORM.matcher(value).matches()) {
            throw new IllegalArgumentException("a channel id is a lower-case token of at most 16 characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
