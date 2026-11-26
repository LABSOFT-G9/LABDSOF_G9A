-- US-06-01: projections of Issue's data, the contractor catalogue, and draft
-- interventions (technical design 2.2, 3.2 and 4.3). DATETIME(6) columns hold
-- UTC. No raw report text is stored anywhere in Dispatch.

-- Dispatch's copy of Issue's issues, written only from IssueStateChanged and
-- applied by version. Creating an intervention locks the row (4.3 step 8).
CREATE TABLE issue_projection (
    issue_id             CHAR(36) CHARACTER SET ascii NOT NULL,
    building_id          CHAR(36) CHARACTER SET ascii NOT NULL,
    state                VARCHAR(32) CHARACTER SET ascii NOT NULL,
    version              BIGINT NOT NULL,
    dispatchable         BOOLEAN NOT NULL,
    category             VARCHAR(32) CHARACTER SET ascii NULL,
    urgency              VARCHAR(32) CHARACTER SET ascii NULL,
    area_label           VARCHAR(120) NOT NULL,
    published_summary_id CHAR(36) CHARACTER SET ascii NULL,
    applied_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (issue_id)
);

-- Dispatch's copy of Issue's memberships, written only from MembershipGranted
-- and MembershipRevoked and applied by the membership's version, so a
-- replayed grant cannot undo a later revocation (4.3 step 3).
CREATE TABLE membership_projection (
    membership_id CHAR(36) CHARACTER SET ascii NOT NULL,
    user_id       VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    building_id   CHAR(36) CHARACTER SET ascii NOT NULL,
    role          VARCHAR(20) CHARACTER SET ascii NOT NULL,
    active        BOOLEAN NOT NULL,
    version       BIGINT NOT NULL,
    applied_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (membership_id),
    KEY ix_membership_projection_user (user_id)
);

-- The contractor catalogue: synthetic in Sprint B (US-06), filled by the
-- synthetic seed only. user_id is the Keycloak sub of the contractor's account,
-- NULL for an entry without one.
CREATE TABLE contractors (
    id           CHAR(36) CHARACTER SET ascii NOT NULL,
    user_id      VARCHAR(255) COLLATE utf8mb4_bin NULL,
    display_name VARCHAR(120) NOT NULL,
    active       BOOLEAN NOT NULL,
    synthetic    BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_contractors_user (user_id)
);

-- Issue categories the contractor works on (Issue's category values).
CREATE TABLE contractor_specialisations (
    contractor_id CHAR(36) CHARACTER SET ascii NOT NULL,
    category      VARCHAR(32) CHARACTER SET ascii NOT NULL,
    PRIMARY KEY (contractor_id, category),
    CONSTRAINT fk_contractor_specialisations_contractor FOREIGN KEY (contractor_id) REFERENCES contractors (id)
);

-- Buildings the contractor works in.
CREATE TABLE contractor_coverage (
    contractor_id CHAR(36) CHARACTER SET ascii NOT NULL,
    building_id   CHAR(36) CHARACTER SET ascii NOT NULL,
    PRIMARY KEY (contractor_id, building_id),
    KEY ix_contractor_coverage_building (building_id),
    CONSTRAINT fk_contractor_coverage_contractor FOREIGN KEY (contractor_id) REFERENCES contractors (id)
);

-- Interventions (3.2). building_id, projected_issue_version and
-- projection_applied_at are the snapshot of the issue projection row used at
-- creation (4.3 step 4). active_issue_id is the issue while the intervention is
-- not terminal, so the unique key allows at most one active intervention per
-- issue even if two requests got past the row lock.
CREATE TABLE interventions (
    id                       CHAR(36) CHARACTER SET ascii NOT NULL,
    issue_id                 CHAR(36) CHARACTER SET ascii NOT NULL,
    building_id              CHAR(36) CHARACTER SET ascii NOT NULL,
    contractor_id            CHAR(36) CHARACTER SET ascii NOT NULL,
    state                    VARCHAR(32) CHARACTER SET ascii NOT NULL,
    version                  BIGINT NOT NULL,
    current_proposal_version INT NOT NULL,
    projected_issue_version  BIGINT NOT NULL,
    projection_applied_at    DATETIME(6) NOT NULL,
    created_by               VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    created_at               DATETIME(6) NOT NULL,
    updated_at               DATETIME(6) NOT NULL,
    active_issue_id          CHAR(36) CHARACTER SET ascii
        AS (CASE WHEN state IN ('declined', 'cancelled', 'closed') THEN NULL ELSE issue_id END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uq_interventions_active_issue (active_issue_id),
    KEY ix_interventions_issue (issue_id, created_at),
    CONSTRAINT fk_interventions_contractor FOREIGN KEY (contractor_id) REFERENCES contractors (id),
    CONSTRAINT ck_interventions_state CHECK (state IN ('awaiting_approval', 'sent', 'scheduling_pending', 'booked',
        'in_progress', 'completion_reported', 'cancellation_pending', 'declined', 'cancelled', 'closed'))
);

-- Proposal versions (2.2). Version 1 is written with the draft by the
-- administrator; US-07 adds changed terms and counterproposals. The scope
-- extract is the administrator's own text, never the report text.
CREATE TABLE proposal_versions (
    intervention_id CHAR(36) CHARACTER SET ascii NOT NULL,
    number          INT NOT NULL,
    author_role     VARCHAR(20) CHARACTER SET ascii NOT NULL,
    scope_extract   VARCHAR(2000) NOT NULL,
    window_start    DATETIME(6) NOT NULL,
    window_end      DATETIME(6) NOT NULL,
    terms           VARCHAR(1000) NOT NULL,
    created_by      VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (intervention_id, number),
    CONSTRAINT fk_proposal_versions_intervention FOREIGN KEY (intervention_id) REFERENCES interventions (id),
    CONSTRAINT ck_proposal_versions_window CHECK (window_end > window_start)
);
