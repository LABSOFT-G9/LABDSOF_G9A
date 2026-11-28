-- US-10-01: the in-app inbox and its delivery log (technical design 2.3,
-- ADR-007). DATETIME(6) columns hold UTC. Rows hold template keys, identifiers
-- and labels, never rendered or private text.

-- Idempotency of NotificationRequested for every channel: one row per
-- request, recipient and channel, however often the message is delivered. The
-- facade claims the row ('pending') before the channel runs, then records the
-- channel's result. Only the in-app channel exists in US-10-01, and it is
-- 'sent' in the same transaction; 'failed' and 'skipped_by_preference' are for
-- a later channel such as email.
CREATE TABLE deliveries (
    request_id        CHAR(36) CHARACTER SET ascii NOT NULL,           -- notificationId of the request
    recipient_user_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,       -- Keycloak sub
    channel           VARCHAR(16) CHARACTER SET ascii NOT NULL,        -- 'in_app'
    state             VARCHAR(24) CHARACTER SET ascii NOT NULL,
    attempts          INT NOT NULL,
    notification_id   CHAR(36) CHARACTER SET ascii NULL,               -- the inbox row, for in_app
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (request_id, recipient_user_id, channel)
);

-- The inbox. id is a UUIDv7, so ordering by id is ordering by creation time;
-- the client pages with "before=<id>".
CREATE TABLE notifications (
    id                CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id        CHAR(36) CHARACTER SET ascii NOT NULL,
    recipient_user_id VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    template_key      VARCHAR(80) CHARACTER SET ascii NOT NULL,
    params            JSON NOT NULL,
    priority          VARCHAR(10) CHARACTER SET ascii NOT NULL,
    source_event_id   VARCHAR(64) CHARACTER SET ascii NOT NULL,
    created_at        DATETIME(6) NOT NULL,
    read_at           DATETIME(6) NULL,                                -- NULL while unread
    PRIMARY KEY (id),
    UNIQUE KEY uq_notifications_request_recipient (request_id, recipient_user_id),
    KEY ix_notifications_recipient (recipient_user_id, id),
    KEY ix_notifications_unread (recipient_user_id, read_at)
);
