package pt.isep.bmc.notification.adapters.out.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Columns are {@code DATETIME(6)} holding UTC. Converting explicitly keeps
 * values independent of the JVM and session time zones.
 */
public final class Timestamps {

    private Timestamps() {
    }

    public static LocalDateTime toColumn(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant fromColumn(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
