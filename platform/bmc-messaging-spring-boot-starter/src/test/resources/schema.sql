-- The two tables the starter reads and writes, as a service's migration
-- declares them (the same columns as services/issue, V1__reporting.sql).
CREATE TABLE IF NOT EXISTS outbox (
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

CREATE TABLE IF NOT EXISTS processed_events (
    event_id     CHAR(36) CHARACTER SET ascii NOT NULL,
    event_type   VARCHAR(80) CHARACTER SET ascii NOT NULL,
    source       VARCHAR(40) CHARACTER SET ascii NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    KEY ix_processed_events_processed_at (processed_at)
);

-- What a test handler changes, to count effects.
CREATE TABLE IF NOT EXISTS effects (
    event_id CHAR(36) CHARACTER SET ascii NOT NULL,
    note     VARCHAR(80) NOT NULL
);
