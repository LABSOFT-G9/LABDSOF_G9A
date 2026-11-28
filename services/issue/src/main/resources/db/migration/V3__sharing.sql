-- US-04-01: shared summary, impact confirmations and subscriptions (technical
-- design 2.1 and 4.2). DATETIME(6) columns hold UTC, as in V1.
--
-- Only additions: a nullable column and new tables, so the previous release
-- still works against this schema if the image has to be rolled back.

-- Every published summary, kept for good: publishing again is a new row. The
-- text is written by an administrator for the whole building; it is never the
-- report text.
CREATE TABLE issue_summaries (
    id                CHAR(36) CHARACTER SET ascii NOT NULL,
    issue_id          CHAR(36) CHARACTER SET ascii NOT NULL,
    resulting_version BIGINT NOT NULL,
    text              VARCHAR(500) NOT NULL,
    published_by      VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    published_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_issue_summaries_version (issue_id, resulting_version),
    CONSTRAINT fk_issue_summaries_issue FOREIGN KEY (issue_id) REFERENCES issues (id)
);

-- The current summary of an issue; NULL until one is published. Once set, the
-- residents of the building may see the issue. No foreign key back to
-- issue_summaries: that table already points at issues, and a cycle would get
-- in the way of deleting an issue's data. The service writes both rows in one
-- transaction.
ALTER TABLE issues
    ADD COLUMN published_summary_id CHAR(36) CHARACTER SET ascii NULL AFTER critical_check_status;

-- "This affects me too": at most one row per resident and issue. Withdrawing
-- deletes the row. Only the count is ever shown, never who.
CREATE TABLE impact_confirmations (
    issue_id     CHAR(36) CHARACTER SET ascii NOT NULL,
    user_id      VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (issue_id, user_id),
    CONSTRAINT fk_impact_confirmations_issue FOREIGN KEY (issue_id) REFERENCES issues (id)
);

-- A user's own choice to be notified about an issue. Separate from impact and
-- off by default: no row means not subscribed.
CREATE TABLE issue_subscriptions (
    issue_id      CHAR(36) CHARACTER SET ascii NOT NULL,
    user_id       VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    subscribed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (issue_id, user_id),
    CONSTRAINT fk_issue_subscriptions_issue FOREIGN KEY (issue_id) REFERENCES issues (id)
);
