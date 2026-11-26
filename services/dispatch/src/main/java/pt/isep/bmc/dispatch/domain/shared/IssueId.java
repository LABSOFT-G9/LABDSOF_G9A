package pt.isep.bmc.dispatch.domain.shared;

import java.util.Objects;
import java.util.UUID;

/** An issue, identified by Issue (the owner of issues). */
public record IssueId(UUID value) {

    public IssueId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
