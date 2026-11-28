package pt.isep.bmc.dispatch.domain.projection;

import java.util.Objects;
import java.util.UUID;

/** A building membership, identified by Issue. */
public record MembershipId(UUID value) {

    public MembershipId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
