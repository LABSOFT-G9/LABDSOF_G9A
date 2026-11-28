-- US-07-03: Issue follows Dispatch's interventions (technical design 2.1, 3.3, 4.4).
-- DATETIME(6) columns hold UTC, as in V1.

-- Issue's copy of each intervention of an issue: the state Dispatch last
-- reported and the version it came with, so an older or repeated event never
-- moves it back. At most one intervention of an issue is not terminal (not
-- declined, cancelled or closed): the active one. Dispatch enforces that; Issue
-- refuses an event that would make a second one.
--
-- `contractor_user_id` is the assigned contractor's Keycloak subject, kept only
-- to address notifications (US-10). It grants no access in Issue.
CREATE TABLE issue_interventions (
    intervention_id    CHAR(36) CHARACTER SET ascii NOT NULL,
    issue_id           CHAR(36) CHARACTER SET ascii NOT NULL,
    state              VARCHAR(24) CHARACTER SET ascii NOT NULL,
    last_version       BIGINT NOT NULL,
    contractor_user_id VARCHAR(255) COLLATE utf8mb4_bin NULL,
    first_applied_at   DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (intervention_id),
    KEY ix_issue_interventions_issue (issue_id),
    CONSTRAINT fk_issue_interventions_issue FOREIGN KEY (issue_id) REFERENCES issues (id),
    CONSTRAINT ck_issue_interventions_state CHECK (state IN ('awaiting_approval', 'sent', 'scheduling_pending',
        'booked', 'in_progress', 'completion_reported', 'declined', 'cancellation_pending', 'cancelled', 'closed')),
    CONSTRAINT ck_issue_interventions_version CHECK (last_version >= 1)
);

-- Every Dispatch event that arrives, kept for good. `event_id` is the
-- CloudEvents `id`; as the primary key it makes a repeated delivery a no-op.
--
-- `outcome`: `applied` (it moved the intervention, and the issue when its
-- state maps to another), `history_only` (late, of an ended intervention, or of
-- a closed issue; `reason` says which) or `failed` (it fits no rule; kept for
-- inspection, with no effect and no history entry).
--
-- `history_sequence` points at the entry of `issue_history` that shows the
-- event. `code` is the decision, the reason code or the booking state, and
-- `window_*` the booked window: codes and times only, never free text. There is
-- no foreign key to `issues`: a failed event may name an issue Issue does not
-- have.
CREATE TABLE dispatch_events (
    event_id           VARCHAR(64) CHARACTER SET ascii NOT NULL,
    event_type         VARCHAR(40) CHARACTER SET ascii NOT NULL,
    issue_id           CHAR(36) CHARACTER SET ascii NOT NULL,
    intervention_id    CHAR(36) CHARACTER SET ascii NOT NULL,
    aggregate_version  BIGINT NOT NULL,
    intervention_state VARCHAR(24) CHARACTER SET ascii NOT NULL,
    outcome            VARCHAR(16) CHARACTER SET ascii NOT NULL,
    reason             VARCHAR(40) CHARACTER SET ascii NULL,
    history_sequence   INT NULL,
    code               VARCHAR(40) CHARACTER SET ascii NULL,
    manual             BOOLEAN NOT NULL,
    window_start       DATETIME(6) NULL,
    window_end         DATETIME(6) NULL,
    occurred_at        DATETIME(6) NOT NULL,
    received_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uq_dispatch_events_history (issue_id, history_sequence),
    KEY ix_dispatch_events_outcome (outcome, received_at),
    CONSTRAINT ck_dispatch_events_outcome CHECK (outcome IN ('applied', 'history_only', 'failed')),
    CONSTRAINT ck_dispatch_events_failed CHECK ((outcome = 'failed') = (history_sequence IS NULL)),
    CONSTRAINT ck_dispatch_events_reason CHECK ((outcome = 'applied') = (reason IS NULL)),
    CONSTRAINT ck_dispatch_events_window CHECK ((window_start IS NULL) = (window_end IS NULL))
);
