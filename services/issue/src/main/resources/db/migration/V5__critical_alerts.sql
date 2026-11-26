-- US-14-02: Issue's own critical check (technical design 2.1 and 5, step 1).
-- DATETIME(6) columns hold UTC, as in V1.

-- The rule set that completed the check, for example `critical-alerts-v1`.
-- `pending` means the rule set did not run: it failed, or the issue is older
-- than the check. Pending never names a rule set and completed always does, so
-- a check that did not run cannot be stored as "checked, nothing found".
ALTER TABLE issues
    ADD COLUMN critical_rules_version VARCHAR(100) CHARACTER SET ascii NULL AFTER critical_check_status,
    ADD KEY ix_issues_critical_check (critical_check_status, id),
    ADD CONSTRAINT ck_issues_critical_check CHECK (
        (critical_check_status = 'pending' AND critical_rules_version IS NULL)
        OR (critical_check_status = 'completed' AND critical_rules_version IS NOT NULL));

-- One row per rule that matched a report of the issue. Rows are only ever
-- inserted: nothing in the service updates or deletes one, so a suggestion, a
-- retry or a decision cannot clear or lower an alert (US-14). The primary key
-- makes raising the same rule twice impossible.
--
-- A row holds the rule's code, never the words that matched: the report text
-- stays in `reports`.
--
-- `history_sequence` points at the `critical_alert_raised` entry of
-- `issue_history` that records the alert. There is no foreign key to that
-- entry, as for `triage_suggestions`: both rows are written in one transaction.
CREATE TABLE issue_critical_signals (
    issue_id         CHAR(36) CHARACTER SET ascii NOT NULL,
    code             VARCHAR(40) CHARACTER SET ascii NOT NULL,
    rules_version    VARCHAR(100) CHARACTER SET ascii NOT NULL,
    history_sequence INT NOT NULL,
    raised_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (issue_id, code),
    UNIQUE KEY uq_issue_critical_signals_history (issue_id, history_sequence),
    CONSTRAINT fk_issue_critical_signals_issue FOREIGN KEY (issue_id) REFERENCES issues (id)
);
