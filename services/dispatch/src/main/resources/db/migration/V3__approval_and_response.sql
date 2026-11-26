-- US-07-01: approval of a proposal version, the contractor's response,
-- changed terms and Dispatch's transactional outbox (ADR-003).
-- Times are UTC in DATETIME(6). Free text (scope, terms, note) never leaves
-- this database in an event.

ALTER TABLE interventions
    ADD COLUMN approved_proposal_version INT NULL AFTER current_proposal_version,
    ADD COLUMN agreed_proposal_version   INT NULL AFTER approved_proposal_version,
    ADD COLUMN contractor_contacted      BOOLEAN NOT NULL DEFAULT FALSE AFTER agreed_proposal_version,
    ADD KEY ix_interventions_contractor (contractor_id, contractor_contacted, created_at);

-- intervention_version: the intervention's version that created the proposal
-- (1 for every draft written before this migration).
ALTER TABLE proposal_versions
    ADD COLUMN intervention_version          BIGINT NOT NULL DEFAULT 1 AFTER terms,
    ADD COLUMN cost_amount                   DECIMAL(9, 2) NULL AFTER terms,
    ADD COLUMN cost_currency                 CHAR(3) CHARACTER SET ascii NULL AFTER cost_amount,
    ADD COLUMN approved_by                   VARCHAR(255) COLLATE utf8mb4_bin NULL,
    ADD COLUMN approved_contractor_id        CHAR(36) CHARACTER SET ascii NULL,
    ADD COLUMN approved_intervention_version BIGINT NULL,
    ADD COLUMN approved_at                   DATETIME(6) NULL,
    ADD CONSTRAINT ck_proposal_versions_author CHECK (author_role IN ('administrator', 'contractor')),
    ADD CONSTRAINT ck_proposal_versions_cost CHECK ((cost_amount IS NULL) = (cost_currency IS NULL)),
    ADD CONSTRAINT ck_proposal_versions_approval CHECK ((approved_at IS NULL) = (approved_by IS NULL)),
    ADD CONSTRAINT fk_proposal_versions_approved_contractor
        FOREIGN KEY (approved_contractor_id) REFERENCES contractors (id);
ALTER TABLE proposal_versions ALTER COLUMN intervention_version DROP DEFAULT;

-- At most one response per proposal version. The note goes with a decline
-- only and is shown to the building's administrators, never put in an event.
CREATE TABLE contractor_responses (
    intervention_id          CHAR(36) CHARACTER SET ascii NOT NULL,
    proposal_version         INT NOT NULL,
    decision                 VARCHAR(20) CHARACTER SET ascii NOT NULL,
    reason_code              VARCHAR(40) CHARACTER SET ascii NULL,
    note                     VARCHAR(500) NULL,
    counter_proposal_version INT NULL,
    intervention_version     BIGINT NOT NULL,
    responded_by             VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    responded_at             DATETIME(6) NOT NULL,
    PRIMARY KEY (intervention_id, proposal_version),
    CONSTRAINT fk_contractor_responses_proposal
        FOREIGN KEY (intervention_id, proposal_version) REFERENCES proposal_versions (intervention_id, number),
    CONSTRAINT ck_contractor_responses_decision CHECK (decision IN ('accept', 'decline', 'counterpropose')),
    CONSTRAINT ck_contractor_responses_reason CHECK ((decision = 'decline') = (reason_code IS NOT NULL)),
    CONSTRAINT ck_contractor_responses_counter
        CHECK ((decision = 'counterpropose') = (counter_proposal_version IS NOT NULL))
);

-- Transactional outbox, the same table as Issue's: CloudEvents JSON written
-- with the change and published by the messaging starter (EN-02-02).
CREATE TABLE outbox (
    id                CHAR(36) CHARACTER SET ascii NOT NULL,
    event_type        VARCHAR(80) CHARACTER SET ascii NOT NULL,
    routing_key       VARCHAR(120) CHARACTER SET ascii NOT NULL,
    aggregate_id      CHAR(36) CHARACTER SET ascii NOT NULL,
    aggregate_version BIGINT NOT NULL,
    building_id       CHAR(36) CHARACTER SET ascii NOT NULL,
    correlation_id    VARCHAR(64) CHARACTER SET ascii NOT NULL,
    payload           JSON NOT NULL,
    status            VARCHAR(10) CHARACTER SET ascii NOT NULL,
    attempts          INT NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    published_at      DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY ix_outbox_pending (status, created_at),
    CONSTRAINT ck_outbox_status CHECK (status IN ('pending', 'sent'))
);
