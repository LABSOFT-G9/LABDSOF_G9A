# bmc-messaging-spring-boot-starter

Shared messaging code of the BMC Spring services ([ADR-003](../../documents/adr/ADR-003-asynchronous-messaging-rabbitmq.md), [technical design 5](../../documents/07-technical-design.md#5-asynchronous-workflow-and-consistency)): the outbox publisher, the idempotent consumer and the CloudEvents envelope. It holds no domain types. The payload schemas are in [`contracts/proto`](../../contracts).

No service depends on it yet. Wiring it into Issue, and RabbitMQ in the stack, are the next part of EN-02-02 (#78).

## What it does

| Part | Behaviour |
|---|---|
| Outbox publisher | Reads `pending` rows of the service's `outbox` table in order and sends each stored message to `bmc.events` with its routing key: persistent, `mandatory`, with a correlated publisher confirm. A row becomes `sent` only after a positive confirm with no returned message. Anything else leaves it `pending` and ends the pass. |
| Backoff | After a failed or returned publish, or a database error, the publisher waits 2 s, then twice as long each time up to 5 min, without an attempt limit. A success resets it. |
| Idempotent consumer | For each message of the service's queue, records the event ID in `processed_events` and runs the handler in one transaction, then acknowledges. A repeated delivery is acknowledged without running the handler. |
| Failed event | The transaction rolls back and the message goes back to the queue after a short pause. The queue's delivery limit moves it to the dead-letter queue. |
| Event that can never work | Rejected to the dead-letter queue without a retry: a body that is not an envelope, an unsupported `schemaversion`, an envelope whose `source.type` is not the routing key it arrived with, a type with no handler, or a `RejectEventException` from the handler. |
| Clean-up | Processed event IDs older than the retention (7 days, the minimum) are deleted every hour. |
| Metrics | `bmc.outbox.pending`, `bmc.outbox.oldest.pending.seconds` (outbox lag), `bmc.outbox.published`, `bmc.outbox.returned`, `bmc.outbox.failures`, `bmc.inbox.processed`, `bmc.inbox.duplicates`, `bmc.inbox.rejected`, `bmc.inbox.failures`. |

It declares no exchange, queue or binding. The deployment declares them, and a queue that is missing at start does not stop the service.

Payloads and exception messages are never logged: `TriageRequested` carries report text.

## Using it in a service

1. Add the dependency and the RabbitMQ settings the publisher needs. It refuses to start without the two publisher settings.

   ```yaml
   spring:
     rabbitmq:
       publisher-confirm-type: correlated
       publisher-returns: true
   bmc:
     messaging:
       outbox:
         enabled: true
       inbox:
         enabled: true
         queue: issue.inbox
   ```

2. Create the two tables in the service's own migrations (below).
3. Write outbox rows in the same transaction as the change. The `payload` is the complete CloudEvents message.
4. Register one `EventHandler` bean per event type the queue is bound to.

   ```java
   @Component
   class TriageSuggestedHandler implements EventHandler {
       public String eventType() { return "TriageSuggested"; }
       public void handle(CloudEvent event) { /* apply by event.aggregateVersion() */ }
   }
   ```

The handler runs only for an event that was not processed before, inside the transaction that records its ID. Out-of-order events are the handler's concern: apply by version and ignore stale ones.

### Tables

```sql
CREATE TABLE outbox (
    id                CHAR(36) CHARACTER SET ascii NOT NULL,
    event_type        VARCHAR(80) CHARACTER SET ascii NOT NULL,
    routing_key       VARCHAR(120) CHARACTER SET ascii NOT NULL,
    payload           JSON NOT NULL,
    status            VARCHAR(10) CHARACTER SET ascii NOT NULL, -- 'pending' or 'sent'
    attempts          INT NOT NULL,
    created_at        DATETIME(6) NOT NULL,                     -- UTC
    published_at      DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY ix_outbox_pending (status, created_at)
);

CREATE TABLE processed_events (
    event_id     CHAR(36) CHARACTER SET ascii NOT NULL,
    event_type   VARCHAR(80) CHARACTER SET ascii NOT NULL,
    source       VARCHAR(40) CHARACTER SET ascii NOT NULL,
    processed_at DATETIME(6) NOT NULL,                          -- UTC
    PRIMARY KEY (event_id),
    KEY ix_processed_events_processed_at (processed_at)
);
```

The publisher uses only the `outbox` columns above; a service may keep more (Issue's table also has the aggregate, building and correlation IDs).

### Settings

All under `bmc.messaging`. The defaults are the starting values of technical design 4.6. They are proposals, not measured results.

| Property | Default | Meaning |
|---|---|---|
| `exchange` | `bmc.events` | Topic exchange for every event. |
| `outbox.enabled` | `false` | Start the publisher. |
| `outbox.poll-interval` | `1s` | Pause when nothing waits. |
| `outbox.batch-size` | `50` | Rows locked per pass. |
| `outbox.confirm-timeout` | `5s` | Wait for one confirm. |
| `outbox.backoff-initial`, `outbox.backoff-max` | `2s`, `5m` | Pause after a failure. |
| `outbox.not-published-types` | empty | Event types that stay in the outbox and are never sent. |
| `inbox.enabled` | `false` | Start the consumer. |
| `inbox.queue` | none | The service's queue. Required when the inbox is enabled. |
| `inbox.prefetch` | `10` | Unacknowledged messages sent ahead. |
| `inbox.supported-schema-versions` | `1` | Accepted `schemaversion` values. |
| `inbox.retry-delay` | `2s` | Pause before a failed message goes back to the queue. |
| `inbox.processed-retention` | `7d` | How long processed IDs are kept. Less than 7 days is refused. |
| `inbox.purge-interval` | `1h` | Pause between clean-ups. |

`outbox.not-published-types` exists because a message bound to no queue is returned, and a returned message stops the publisher until a queue is bound. Some Issue events have no consumer outside Issue (`ReportSubmitted`, for example; open in [ADR-001](../../documents/adr/ADR-001-backend-boundaries.md)). Listing a type here keeps its rows `pending` and out of the lag metric. The cleaner fix is not to write such rows at all.

## What the tests found

Observed with RabbitMQ 4.4.1 and MySQL 8.4 in `mvn verify`; these are spike results for EN01-U2, items 4 and 5.

| # | Finding | Consequence |
|---|---|---|
| 1 | A message returned to a quorum queue with `basic.nack` and requeue does not count towards `x-delivery-limit`: a handler that always failed was called 793 times in 20 s and never dead-lettered. RabbitMQ documents this change for 4.3 and later ([quorum queues, "Poison message handling"](https://www.rabbitmq.com/docs/quorum-queues), accessed 2026-11-24). | The consumer returns a failed message with `basic.reject` and requeue, which does count. |
| 2 | With `x-delivery-limit` 5, a message that always fails reaches the handler 6 times before it is dead-lettered: the first delivery plus 5 redeliveries. | ADR-003 says "up to 5 deliveries". A limit of 4 would give that. Not changed here. |
| 3 | The broker confirms a message that no queue is bound to; only the return shows it. | As ADR-003 expected (E84). The publisher checks the return before the confirm. |
| 4 | A row committed while the broker is unreachable is sent by a later pass, and a row confirmed but not yet marked `sent` is sent a second time with the same event ID. | At-least-once holds; the consumer's deduplication is what makes it safe. |

## Limits

- **Broker permissions are not tested.** The tests use the broker's administrator. Whether a service user without configure permission can consume from a deployment-declared queue is still to check (ADR-003, "Verification").
- **Replay keeps the routing key.** The consumer compares the envelope's `source.type` with the routing key of the delivery. A replay from a dead-letter queue must publish with the original routing key, or the message is rejected again.
- **Order is per instance.** Rows are locked with `FOR UPDATE SKIP LOCKED`, so two instances never send the same row together, but with two instances the order across batches is not kept. That is acceptable because consumers apply events by aggregate version, not by arrival order (technical design 3.3).
- **Row locks are held while waiting for confirms.** A pass keeps its database transaction, and the locks on its pending rows, open until every confirm of the batch has arrived or one has failed. With a slow broker that is up to `outbox.batch-size` times `outbox.confirm-timeout`. Only pending outbox rows are locked, so the service's own writes are not blocked, but do not combine a large batch with a long timeout.
- **No retention for `sent` rows.** Nothing deletes them yet (EN01-U6).
- **The retry pause blocks the consumer thread.** One failing message delays the ones behind it by `inbox.retry-delay` per attempt.

## Build and test

```bash
mvn verify   # needs Docker: starts MySQL 8.4 and RabbitMQ 4.4 with Testcontainers
```

Unit tests are `*Test`; tests against the real containers are `*IT`.
