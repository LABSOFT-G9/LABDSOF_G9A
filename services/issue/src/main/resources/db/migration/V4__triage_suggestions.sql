-- US-03-03: triage suggestions recorded from `TriageSuggested` (technical design 2.1, 3.1, 4.4).
-- DATETIME(6) columns hold UTC, as in V1.

-- Every suggestion that arrives, kept for good: the one that moved the issue to
-- `in_triage` (`applied`) and the late ones, which changed nothing. A
-- suggestion is never a decision: `issues.category` and `issues.urgency` stay
-- NULL until an administrator decides.
--
-- `event_id` is the ID of the event that carried the suggestion. As the primary
-- key it makes a repeated delivery of the same event a no-op.
--
-- `rationale` is written from a private report: administrators of the building
-- only, never in a list, a history entry, an event or a log.
--
-- `history_sequence` points at the `triage_suggested` entry of `issue_history`
-- that records the arrival. There is no foreign key to that entry: both rows
-- are written in one transaction, and the entry is flushed after this row.
CREATE TABLE triage_suggestions (
    event_id              CHAR(36) CHARACTER SET ascii NOT NULL,
    issue_id              CHAR(36) CHARACTER SET ascii NOT NULL,
    history_sequence      INT NOT NULL,
    source_version        BIGINT NOT NULL,
    applied               BOOLEAN NOT NULL,
    category              VARCHAR(20) CHARACTER SET ascii NOT NULL,
    urgency               VARCHAR(10) CHARACTER SET ascii NOT NULL,
    rationale             VARCHAR(240) NOT NULL,
    needs_review          BOOLEAN NOT NULL,
    origin                VARCHAR(10) CHARACTER SET ascii NOT NULL,
    model_version         VARCHAR(100) CHARACTER SET ascii NULL,
    prompt_version        VARCHAR(100) CHARACTER SET ascii NULL,
    rules_version         VARCHAR(100) CHARACTER SET ascii NULL,
    output_schema_version VARCHAR(100) CHARACTER SET ascii NULL,
    failure_code          VARCHAR(100) CHARACTER SET ascii NULL,
    recorded_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uq_triage_suggestions_history (issue_id, history_sequence),
    CONSTRAINT fk_triage_suggestions_issue FOREIGN KEY (issue_id) REFERENCES issues (id),
    CONSTRAINT ck_triage_suggestions_category CHECK (category IN ('plumbing', 'electrical', 'lift', 'cleaning', 'structural', 'other')),
    CONSTRAINT ck_triage_suggestions_urgency CHECK (urgency IN ('low', 'normal', 'high', 'critical')),
    CONSTRAINT ck_triage_suggestions_origin CHECK (origin IN ('ai', 'rules', 'fallback'))
);
