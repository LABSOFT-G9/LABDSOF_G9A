package pt.isep.bmc.issue.domain.issue;

import java.util.Objects;
import java.util.UUID;

public record SummaryId(UUID value) {

    public SummaryId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
