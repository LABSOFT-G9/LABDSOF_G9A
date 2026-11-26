-- US-08-01: bookings of the agreed window through the calendar (ADR-006,
-- technical design 4.5), the log of every calendar call, the contractor's
-- calendar reference and the reason of a cancellation. Times are UTC in
-- DATETIME(6). No report text, name or address is stored or sent.

ALTER TABLE contractors
    ADD COLUMN calendar_ref VARCHAR(64) NULL;

ALTER TABLE interventions
    ADD COLUMN cancel_reason_code VARCHAR(40) CHARACTER SET ascii NULL AFTER contractor_contacted,
    ADD COLUMN cancel_note        VARCHAR(500) NULL AFTER cancel_reason_code;

-- A separate aggregate (technical design 2.2): calendar retries never lock
-- the intervention. At most one active booking per intervention; rejected,
-- failed and cancelled ones stay as history. The ID is also the calendar's
-- request ID and idempotency key, and the request content never changes, so
-- every re-send is the same request.
CREATE TABLE bookings (
    id                     CHAR(36) CHARACTER SET ascii NOT NULL,
    intervention_id        CHAR(36) CHARACTER SET ascii NOT NULL,
    contractor_ref         VARCHAR(64) NOT NULL,
    building_code          VARCHAR(64) NOT NULL,
    summary                VARCHAR(200) NOT NULL,
    window_start           DATETIME(6) NOT NULL,
    window_end             DATETIME(6) NOT NULL,
    state                  VARCHAR(20) CHARACTER SET ascii NOT NULL,
    source                 VARCHAR(10) CHARACTER SET ascii NULL,
    external_ref           VARCHAR(120) NULL,
    reason_code            VARCHAR(40) CHARACTER SET ascii NULL,
    pending_action         VARCHAR(20) CHARACTER SET ascii NULL,
    attempts               INT NOT NULL,
    next_attempt_at        DATETIME(6) NULL,
    last_reconciled_at     DATETIME(6) NULL,
    manual_window_start    DATETIME(6) NULL,
    manual_window_end      DATETIME(6) NULL,
    manual_source          VARCHAR(20) CHARACTER SET ascii NULL,
    manual_reason          VARCHAR(500) NULL,
    manual_by              VARCHAR(255) COLLATE utf8mb4_bin NULL,
    manual_at              DATETIME(6) NULL,
    version                BIGINT NOT NULL,
    created_at             DATETIME(6) NOT NULL,
    updated_at             DATETIME(6) NOT NULL,
    active_intervention_id CHAR(36) CHARACTER SET ascii
        AS (CASE WHEN state IN ('rejected', 'failed', 'cancelled') THEN NULL ELSE intervention_id END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uq_bookings_active_intervention (active_intervention_id),
    KEY ix_bookings_intervention (intervention_id, created_at),
    KEY ix_bookings_due (next_attempt_at),
    CONSTRAINT fk_bookings_intervention FOREIGN KEY (intervention_id) REFERENCES interventions (id),
    CONSTRAINT ck_bookings_state CHECK (state IN ('requested', 'uncertain', 'confirmed', 'rejected', 'failed',
        'discrepancy', 'cancelled')),
    CONSTRAINT ck_bookings_action CHECK ((pending_action IS NULL) = (next_attempt_at IS NULL)),
    CONSTRAINT ck_bookings_window CHECK (window_end > window_start)
);

-- Every calendar call and its classified outcome (US-08: "persist booking
-- history, attempts and outcomes").
CREATE TABLE booking_calls (
    booking_id  CHAR(36) CHARACTER SET ascii NOT NULL,
    sequence    INT NOT NULL AUTO_INCREMENT,
    action      VARCHAR(20) CHARACTER SET ascii NOT NULL,
    outcome     VARCHAR(20) CHARACTER SET ascii NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (sequence),
    KEY ix_booking_calls_booking (booking_id, sequence),
    CONSTRAINT fk_booking_calls_booking FOREIGN KEY (booking_id) REFERENCES bookings (id)
);
