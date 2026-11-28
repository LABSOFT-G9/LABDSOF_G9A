package pt.isep.bmc.notification.domain.notification;

import java.util.Optional;

/**
 * How prominently the client shows a notification. The contract left the
 * values open for US-10 ({@code priority} in
 * {@code notification_requested.proto}); US-10-01 proposes these three, with
 * an absent or empty value read as {@code normal}. Priority never changes
 * whether a notification is created.
 */
public enum Priority {
    LOW, NORMAL, HIGH;

    public String token() {
        return name().toLowerCase();
    }

    /** An empty value is {@link #NORMAL}; an unknown one is empty. */
    public static Optional<Priority> fromToken(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.of(NORMAL);
        }
        for (Priority priority : values()) {
            if (priority.token().equals(token)) {
                return Optional.of(priority);
            }
        }
        return Optional.empty();
    }
}
