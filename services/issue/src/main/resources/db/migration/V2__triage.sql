-- US-02-01: manual triage, state transitions and history (technical design 2.1, 3.1).
-- DATETIME(6) columns hold UTC, as in V1.

-- The current decision lives on the issue; both are NULL until an
-- administrator decides (an undecided value stays pending, never invented).
ALTER TABLE issues
    ADD COLUMN category VARCHAR(20) CHARACTER SET ascii NULL AFTER version,
    ADD COLUMN urgency  VARCHAR(10) CHARACTER SET ascii NULL AFTER category,
    ADD CONSTRAINT ck_issues_category CHECK (category IN ('plumbing', 'electrical', 'lift', 'cleaning', 'structural', 'other')),
    ADD CONSTRAINT ck_issues_urgency CHECK (urgency IN ('low', 'normal', 'high', 'critical')),
    ADD CONSTRAINT ck_issues_decided_together CHECK ((category IS NULL) = (urgency IS NULL));

-- Every triage decision, kept for good: a correction is a new row. The reason
-- may quote a private report, so it is shown to the building's administrators
-- only and is never copied into history or events.
CREATE TABLE triage_decisions (
    issue_id          CHAR(36) CHARACTER SET ascii NOT NULL,
    resulting_version BIGINT NOT NULL,
    category          VARCHAR(20) CHARACTER SET ascii NOT NULL,
    urgency           VARCHAR(10) CHARACTER SET ascii NOT NULL,
    reason            VARCHAR(500) NOT NULL,
    decided_by        VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    decided_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (issue_id, resulting_version),
    CONSTRAINT fk_triage_decisions_issue FOREIGN KEY (issue_id) REFERENCES issues (id),
    CONSTRAINT ck_triage_decisions_category CHECK (category IN ('plumbing', 'electrical', 'lift', 'cleaning', 'structural', 'other')),
    CONSTRAINT ck_triage_decisions_urgency CHECK (urgency IN ('low', 'normal', 'high', 'critical'))
);

-- Each history entry records the issue version it produced, which links a
-- `triage_decided` entry to its decision. Existing entries are all
-- `report_received`, at version 1.
ALTER TABLE issue_history
    ADD COLUMN issue_version BIGINT NOT NULL DEFAULT 1 AFTER actor_role;
ALTER TABLE issue_history
    ALTER COLUMN issue_version DROP DEFAULT;
