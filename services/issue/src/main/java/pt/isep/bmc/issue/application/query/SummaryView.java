package pt.isep.bmc.issue.application.query;

import java.time.Instant;
import java.util.Objects;
import pt.isep.bmc.issue.domain.issue.SummaryText;

/** The current published summary of an issue: the only text about it that residents see. */
public record SummaryView(SummaryText text, Instant publishedAt) {

    public SummaryView {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
