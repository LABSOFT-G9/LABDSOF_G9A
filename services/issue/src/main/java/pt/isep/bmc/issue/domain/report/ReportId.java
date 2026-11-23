package pt.isep.bmc.issue.domain.report;

import java.util.Objects;
import java.util.UUID;

public record ReportId(UUID value) {

    public ReportId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
