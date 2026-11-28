# Notification service

Bounded context **Notification** of BMC ([technical design §2.3](../../documents/07-technical-design.md#23-notification-service), [ADR-007](../../documents/adr/ADR-007-notification-service.md)): the in-app inbox and its delivery log. Spring Boot 4.1 on Java 25, MySQL 8.4 with Flyway ([ADR-002](../../documents/adr/ADR-002-technology-stack.md)).

Implemented so far, US-10-01 ([#91](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/91)):

- reading `NotificationRequested` into one notification per recipient, handed to every registered channel through `NotificationFacade` and the `NotificationChannel` port (ADR-007). The in-app inbox is the only channel;
- deduplication per `notificationId`, recipient and channel, across replays, concurrent deliveries and restarts;
- limited retries, with refused and exhausted requests logged and counted by event and correlation ID only;
- `GET /notifications`: the caller's own inbox, newest first, paged, with read state, an unread count, and marking one or all read.

Not yet:

- RabbitMQ and the messaging starter (EN-02-02, [#78](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/78)), so no event reaches this service yet;
- the producer side: Issue choosing recipients and publishing `NotificationRequested` (US-10-03, [#110](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/110));
- the client inbox (US-10-02, [#92](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/92));
- other channels (email, push), preferences (`GET`/`PUT /preferences`), invitations and the Keycloak address lookup of ADR-007. US-10 delivers one in-app channel. See [Adding a channel](#adding-a-channel).

## Architecture

The layers are hexagonal, as in Issue and Dispatch ([ADR-009](../../documents/adr/ADR-009-service-internal-architecture.md)). `ArchitectureTest` (ArchUnit) enforces them and fails `mvn verify` on a wrong dependency.

```text
pt.isep.bmc.notification
├── domain/            pure Java: no Spring, Jackson or servlet types
│   ├── notification/  NotificationRequest (validated), Notification, ChannelId, TemplateCatalogue, Template, ParamKind, Priority
│   └── shared/        UserId (Keycloak sub), DomainException (sealed), ValidationException, FieldViolation
├── application/       use cases and ports; depends only on domain
│   ├── command/       NotificationFacade (every recipient × every channel, claimed in the delivery log first)
│   ├── query/         NotificationQueryService
│   └── port/          out: NotificationChannel, DeliveryLog, InboxReadModel, TransactionRunner, IdGenerator
├── adapters/in/       NotificationRequestedHandler (retries, logs, metrics), REST controller + DTO, problem+json, correlation ID
├── adapters/out/      channel/InAppChannelAdapter (writes the inbox), JDBC delivery log and inbox reader, UUIDv7
└── config/            composition root: wiring, security, consumer retry settings
```

## API

Paths are service-relative. The edge serves them under `/api/notifications` and strips that prefix, so the client calls `GET /api/notifications/notifications`. Every endpoint needs a bearer access token; state-changing requests need the CSRF header (`X-BMC-CSRF`), as in Issue. Only the read state can be changed through the API.

| Operation | Who | Result |
|---|---|---|
| `GET /notifications?limit=&before=` | Any authenticated user | `{items, nextBefore}`, the caller's own notifications, newest first. `limit` is 1–100 (default 20). Pass `nextBefore` as `before` for the next page; it is `null` on the last page. Each item is `{id, templateKey, title, link, priority, params, createdAt, read, readAt}`; `readAt` is `null` while unread |
| `GET /notifications/unread-count` | Any authenticated user | `{unread}`, for a badge |
| `POST /notifications/{id}/read` | The recipient | `204`, also when it was already read; the first `readAt` is kept. `404` for an unknown ID or another user's notification, which are not told apart |
| `POST /notifications/read-all` | Any authenticated user | `{marked}`: how many of the caller's notifications were unread and are now read |

- **Who.** The only filter is the token's `sub`. No parameter names a user, so nobody can read another inbox. A `before` taken from another inbox only bounds the caller's own rows.
- **`title`** is rendered by the service from the template. `templateKey` and `params` let the client render its own text instead.
- **`link`** is a client path, for example `/issues/<issueId>`, or `null` when the recipient has no page to open (contractor templates, until the client has a contractor page). The page itself is authorised by Issue on every read, so a link never grants access: a user who lost access gets Issue's `404`.
- **Errors** are `application/problem+json` with `code` and `correlationId`: `400 VALIDATION_FAILED` with every invalid field, `401`, `403 CSRF_HEADER_REQUIRED` for a `POST` without the header, `404 NOT_FOUND`, `503 DEPENDENCY_UNAVAILABLE` with `Retry-After` when the database is down.

## Receiving `NotificationRequested`

The payload is `contracts/proto/bmc/notification/v1/notification_requested.proto` in the proto3 JSON mapping. `adapters/in/messaging/NotificationRequestedHandler` takes one delivery and returns its outcome:

| Outcome | When | What the RabbitMQ listener will do (EN-02-02) |
|---|---|---|
| `DELIVERED` | At least one recipient got a new notification | Acknowledge |
| `DUPLICATE` | Every recipient already had it: a redelivery or a replay | Acknowledge |
| `REJECTED` | The request is invalid. Refused at once, because a retry cannot fix it | Reject to the dead-letter queue |
| `EXHAUSTED` | A temporary failure (database unavailable, or a transaction rolled back by a concurrent one) happened `max-attempts` times (default 5, 2 s apart), or any other failure happened once: a defect is not retried | Reject to the dead-letter queue |

**Rules.**

- **One delivery per `notificationId`, recipient and channel.** The facade claims each delivery in the delivery log, whose primary key (request, recipient, channel) is the gate, and only then calls the channel. Every recipient on every channel is written in one transaction, so either all get it or none do. Two deliveries racing on the same request block on the key, and the loser finds the winner's rows. A deadlock between them is retried. Distinct `notificationId`s stay separate, even for the same event and recipient.
- **Templates.** `templateKey` must be in `TemplateCatalogue`, and `params` may hold only the parameters that template lists, each of a fixed kind: a UUID, a lower-case token, a one-line label of at most 120 characters, or an RFC 3339 time. There is no free-text kind, so a producer cannot slip report text into a notification through an extra parameter: the request is refused. US-10-03 must use these keys and parameters:

  | `templateKey` | Required params | Optional params | Link |
  |---|---|---|---|
  | `report.received` | `reportId` | | `/reports/{reportId}` |
  | `issue.triaged` | `issueId` | `category`, `urgency` | `/issues/{issueId}` |
  | `issue.summary_published` | `issueId` | `areaLabel` | `/issues/{issueId}` |
  | `intervention.contractor_responded` | `issueId`, `interventionId`, `response` | | `/issues/{issueId}` |
  | `booking.confirmed` | `issueId`, `interventionId` | `windowStart`, `windowEnd` | `/issues/{issueId}` |
  | `booking.not_recorded` | `issueId`, `interventionId` | | `/issues/{issueId}` |
  | `booking.cancelled` | `issueId`, `interventionId` | | `/issues/{issueId}` |
  | `intervention.completed` | `issueId`, `interventionId` | | `/issues/{issueId}` |
  | `issue.resolved` | `issueId` | `resolutionKind` | `/issues/{issueId}` |
  | `issue.cancelled` | `issueId` | | `/issues/{issueId}` |
  | `issue.reopened` | `issueId` | `reasonCode` | `/issues/{issueId}` |
  | `contractor.request_sent` | `interventionId` | `areaLabel` | none |
  | `contractor.booking_cancelled` | `interventionId` | | none |

- **Priority** is `low`, `normal` or `high`. An absent or empty value is `normal`. The contract left the values open for US-10; this is the US-10-01 proposal.
- **Recipients** are 1–500 Keycloak `sub`s; repeats count once. Who is notified is the producer's decision (ADR-007): Notification never reads memberships.
- **Invitations** (`recipientAddress`, `inviteLink`) need the email channel and are refused with `unsupported_channel` for now. Neither value is logged.
- **Logs and metrics.** A refused or exhausted request is logged at `WARN` or `ERROR` with its event ID, correlation ID and, for a refusal, the invalid fields with their reasons. Values and exception messages are never logged. Counters: `bmc.notification.requests{outcome}` and `bmc.notification.delivered` (per recipient and channel).
- **A notification failure never undoes a business change.** Issue and Dispatch only commit an outbox row in their own transaction. This service has its own database, and nothing in Issue or Dispatch waits for it.

**Not wired yet.** The RabbitMQ listener that calls the handler comes with the messaging starter in EN-02-02 (#78), together with deduplication by event ID (`processed_events`). Until then, the integration tests feed synthetic `NotificationRequested` events (`support/SyntheticRequests`) through the same handler. When it is wired, the queue's delivery limit bounds redeliveries, and the handler's own attempts bound each delivery.

## Adding a channel

A channel is one adapter that implements `application/port/out/NotificationChannel`:

```java
public interface NotificationChannel {
    ChannelId id();                                    // stored in the delivery log, e.g. "email"
    DeliveryResult deliver(Notification notification); // SENT now, or PENDING to send after the commit
}
```

Register it as a bean and the facade calls it for every recipient. The producers, the event, the facade and the delivery log stay the same. `deliver` runs inside the receiving transaction, and only once per request, recipient and channel. A channel that calls an outside system, such as SMTP, must answer `PENDING` and send after the commit, so that a slow server never holds the message (ADR-007). That sender, its retries, the `failed` state, preferences (`skipped_by_preference`) and the Keycloak address lookup are the work of the story that adds email. `NotificationFacadeTest` shows a second channel getting its own delivery row.

## Configuration

The variables are the same as Issue's (see [its README](../issue/README.md#configuration)), with `DB_NAME` and `DB_USER` defaulting to `notification`. The consumer's retries are `bmc.notification.consumer.max-attempts` (5) and `bmc.notification.consumer.retry-delay` (2 s); both are proposals to calibrate (ADR-007, EN01-U7), not measured values. There is no synthetic seed: Issue seeds no issues or reports, so seeded notifications would link to pages that do not exist.

## Build and test

```bash
mvn verify   # unit + ArchUnit (surefire, *Test) and MySQL 8.4 integration tests (failsafe, *IT; needs Docker)
```

The unit tests cover the request's validation and the template catalogue, the facade and the query with in-memory fakes of the ports (`application/InMemoryPorts`), including a second channel, and the handler's outcomes, retries and logs.

The integration tests use Testcontainers and locally signed tokens, as Issue's and Dispatch's do. They cover:

- every US-10 update (triage, contractor response, booking, completion, closure, contractor request) reaching its recipient with a title and a link;
- redelivery and 8 concurrent deliveries of one request, which create one notification per recipient;
- distinct requests kept separate, and an invalid request creating nothing;
- only the recipient reading a notification, paging, and a cursor from another inbox;
- read state: unread by default, the unread count, marking one (first time kept, `404` for another user's) or all read, and the CSRF header on both (`ReadStateIT`);
- tokens, the CSRF header, and health on the management port only;
- a restart, after which a replay is still a duplicate (`RestartIT`);
- the database down: limited attempts, the exhausted request logged and counted without its payload, and `503` on reads (`StorageFailureIT`);
- payloads kept out of DEBUG logs (`LogPrivacyIT`).
