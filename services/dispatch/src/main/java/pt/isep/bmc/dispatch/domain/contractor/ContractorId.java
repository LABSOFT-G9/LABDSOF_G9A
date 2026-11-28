package pt.isep.bmc.dispatch.domain.contractor;

import java.util.Objects;
import java.util.UUID;

/** A contractor of Dispatch's catalogue (technical design 2.2). */
public record ContractorId(UUID value) {

    public ContractorId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
