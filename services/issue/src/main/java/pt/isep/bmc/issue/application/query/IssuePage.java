package pt.isep.bmc.issue.application.query;

import java.util.List;

/** One page of issues, newest first. */
public record IssuePage(List<IssueView> items, int page, int size, boolean hasMore) {

    public IssuePage {
        items = List.copyOf(items);
    }
}
