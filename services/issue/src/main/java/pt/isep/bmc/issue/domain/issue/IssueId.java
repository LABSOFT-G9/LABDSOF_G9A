package pt.isep.bmc.issue.domain.issue;

import java.util.Objects;
import java.util.UUID;

public record IssueId(UUID value) {

    public IssueId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
