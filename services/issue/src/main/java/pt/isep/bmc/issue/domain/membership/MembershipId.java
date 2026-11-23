package pt.isep.bmc.issue.domain.membership;

import java.util.Objects;
import java.util.UUID;

public record MembershipId(UUID value) {

    public MembershipId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
