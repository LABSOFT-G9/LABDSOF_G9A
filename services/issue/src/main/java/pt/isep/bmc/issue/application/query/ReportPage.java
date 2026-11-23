package pt.isep.bmc.issue.application.query;

import java.util.List;

/** @param hasMore true when another page follows */
public record ReportPage(List<ReportSummary> items, int page, int size, boolean hasMore) {

    public ReportPage {
        items = List.copyOf(items);
    }
}
