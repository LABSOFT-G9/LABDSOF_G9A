-- US-06-01: Dispatch foundation. DATETIME(6) columns hold UTC.

-- 24 h idempotency (technical design 4.1/4.6), as in Issue. The primary key
-- serialises concurrent retries; the request is kept only as a SHA-256 hash.
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
