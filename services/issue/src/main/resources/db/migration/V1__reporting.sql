-- US-01-01: buildings, memberships, reports, issues, history, outbox and
-- idempotency keys (technical design 2.1, 4.1, 4.6 and 5).
-- IDs are UUIDv7 strings; times are UTC in DATETIME(6). Rows of memberships
-- are never deleted (status 'revoked' instead). User IDs (Keycloak `sub`)
-- use a binary collation: they compare exactly, never case-insensitively.

CREATE TABLE organisations (
    id   CHAR(36) CHARACTER SET ascii NOT NULL,
    name VARCHAR(200) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE buildings (
    id              CHAR(36) CHARACTER SET ascii NOT NULL,
    organisation_id CHAR(36) CHARACTER SET ascii NOT NULL,
    name            VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_buildings_organisation FOREIGN KEY (organisation_id) REFERENCES organisations (id)
);

CREATE TABLE areas (
    id          CHAR(36) CHARACTER SET ascii NOT NULL,
    building_id CHAR(36) CHARACTER SET ascii NOT NULL,
    label       VARCHAR(200) NOT NULL,
    kind        VARCHAR(20) CHARACTER SET ascii NOT NULL,
    PRIMARY KEY (id),
    KEY ix_areas_building (building_id),
    CONSTRAINT fk_areas_building FOREIGN KEY (building_id) REFERENCES buildings (id),
    CONSTRAINT ck_areas_kind CHECK (kind IN ('common_area', 'unit'))
);

-- Profile only: id is the Keycloak `sub`; no credentials (ADR-005).
CREATE TABLE users (
    id           VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    display_name VARCHAR(200) NULL,
    PRIMARY KEY (id)
);

CREATE TABLE memberships (
    id          CHAR(36) CHARACTER SET ascii NOT NULL,
    user_id     VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    building_id CHAR(36) CHARACTER SET ascii NOT NULL,
    role        VARCHAR(20) CHARACTER SET ascii NOT NULL,
    status      VARCHAR(20) CHARACTER SET ascii NOT NULL,
    valid_from  DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NULL,
    version     BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_memberships_user_building_role (user_id, building_id, role),
    KEY ix_memberships_building (building_id),
    CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_memberships_building FOREIGN KEY (building_id) REFERENCES buildings (id),
    CONSTRAINT ck_memberships_role CHECK (role IN ('resident', 'administrator')),
    CONSTRAINT ck_memberships_status CHECK (status IN ('active', 'revoked')),
    CONSTRAINT ck_memberships_version CHECK (version >= 1)
);

CREATE TABLE issues (
    id                    CHAR(36) CHARACTER SET ascii NOT NULL,
    building_id           CHAR(36) CHARACTER SET ascii NOT NULL,
    area_id               CHAR(36) CHARACTER SET ascii NOT NULL,
    state                 VARCHAR(30) CHARACTER SET ascii NOT NULL,
    version               BIGINT NOT NULL,
    critical_check_status VARCHAR(20) CHARACTER SET ascii NOT NULL,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_issues_building (building_id, created_at),
    CONSTRAINT fk_issues_building FOREIGN KEY (building_id) REFERENCES buildings (id),
    CONSTRAINT fk_issues_area FOREIGN KEY (area_id) REFERENCES areas (id),
    CONSTRAINT ck_issues_version CHECK (version >= 1)
);

-- Private text: readable through the API only by the author and the
-- building's administrators (security access matrix).
CREATE TABLE reports (
    id             CHAR(36) CHARACTER SET ascii NOT NULL,
    issue_id       CHAR(36) CHARACTER SET ascii NOT NULL,
    building_id    CHAR(36) CHARACTER SET ascii NOT NULL,
    area_id        CHAR(36) CHARACTER SET ascii NOT NULL,
    author_user_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    text           TEXT NOT NULL,
    source         VARCHAR(30) CHARACTER SET ascii NOT NULL,
    submitted_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_reports_author (author_user_id, submitted_at),
    KEY ix_reports_building (building_id, submitted_at),
    KEY ix_reports_issue (issue_id),
    CONSTRAINT fk_reports_issue FOREIGN KEY (issue_id) REFERENCES issues (id),
    CONSTRAINT fk_reports_area FOREIGN KEY (area_id) REFERENCES areas (id),
    CONSTRAINT ck_reports_source CHECK (source IN ('resident', 'administrator_on_behalf'))
);

-- Actor role only; no identities or free text.
CREATE TABLE issue_history (
    issue_id    CHAR(36) CHARACTER SET ascii NOT NULL,
    sequence    INT NOT NULL,
    type        VARCHAR(40) CHARACTER SET ascii NOT NULL,
    visibility  VARCHAR(10) CHARACTER SET ascii NOT NULL,
    actor_role  VARCHAR(20) CHARACTER SET ascii NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (issue_id, sequence),
    CONSTRAINT fk_issue_history_issue FOREIGN KEY (issue_id) REFERENCES issues (id),
    CONSTRAINT ck_issue_history_visibility CHECK (visibility IN ('public', 'internal'))
);

-- Transactional outbox (ADR-003): CloudEvents JSON written with the change,
-- published later. `TriageRequested` payloads hold report text: never log them.
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

-- 24 h idempotency (technical design 4.1/4.6). The primary key serialises
-- concurrent retries; the request is kept only as a SHA-256 hash.
CREATE TABLE idempotency_keys (
    actor_id         VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    operation        VARCHAR(64) CHARACTER SET ascii NOT NULL,
    idem_key         VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash     CHAR(64) CHARACTER SET ascii NOT NULL,
    response_status  SMALLINT NOT NULL,
    resource_id      CHAR(36) CHARACTER SET ascii NOT NULL,
    resource_version BIGINT NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    expires_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (actor_id, operation, idem_key),
    KEY ix_idempotency_keys_expiry (expires_at)
);
