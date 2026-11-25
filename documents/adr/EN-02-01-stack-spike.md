# EN-02-01 — Stack spike: results (EN01-U2)

**Date:** 2026-11-24
**Author:** Ricardo (P2, Tech Lead); experiments and drafting AI-assisted
**Issue:** [#77](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/77) (EN-02-01), under EN-02 ([#68](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/68))
**Scope:** items 1–10 of EN01-U2 in the [decision register](../planning/decisions-and-feedback.md#5-en-01-01-unresolved-details), and the starting values of EN01-U7 ([technical design 4.6](../07-technical-design.md#46-retry-timeout-and-lease-values-proposal)). Items 11–14 are out of scope (#77).

The spike was not run as one two-day exercise. Most items were answered by the first implementation pull requests; items 5 to 8 were completed with experiments on 2026-11-24. Three of the pull requests cited are open and not merged, so their evidence can still change in review.

## 1. Results

| # | Item | Outcome | Evidence |
|---|---|---|---|
| 1 | Keycloak 26.8 on MySQL 8.4 with realm import | Pass | PR #139 (open); [2.1](#21-keycloak-on-mysql-item-1) |
| 2 | Flyway against MySQL 8.4 | Pass | PR #134 (merged); [2.2](#22-flyway-on-mysql-item-2) |
| 3 | traefik-oidc-auth: `401`, token forwarding, cookie, logout, renewal | Partly tested | PR #139 (open); [2.3](#23-edge-sign-in-plugin-item-3) |
| 4 | Outbox publisher survives a kill between commit and publish | Partly tested: the kill is simulated | PR #142 (open); [2.4](#24-outbox-publisher-item-4) |
| 5 | Quorum queue dead-letters at least once to the DLQ | Pass, with two findings | PR #142 (open) and experiment; [2.5](#25-dead-lettering-and-the-delivery-limit-item-5) |
| 6 | Buf-generated Java and Python code round-trips proto3 JSON | Pass, with one finding | Experiment on PR #141 (open); [2.6](#26-java-and-python-round-trip-item-6) |
| 7 | pika consumer in the Flask image | Pass in the base image | Experiment; [2.7](#27-pika-consumer-item-7) |
| 8 | Memory of the full Compose stack on amd64 and arm64 | Partly tested: amd64 only | Measurement; [2.8](#28-memory-item-8) |
| 9 | The same storage adapter against Garage and R2 | Not tested | [2.9](#29-storage-adapter-item-9) |
| 10 | ArchUnit on Java 25 | Pass | PR #134 (merged); [2.10](#210-archunit-on-java-25-item-10) |

No item failed, so no ADR is reopened. Two findings correct ADR-003 ([section 4](#4-what-changes-in-the-decisions)).

## 2. Evidence by item

### 2.1 Keycloak on MySQL (item 1)

- **What ran:** the image of PR #139 (`infra/keycloak`, from `quay.io/keycloak/keycloak:26.8.2`, built with `KC_DB=mysql`) against `mysql:8.4` in the local stack, started from empty.
- **Observed on 2026-11-24:** Keycloak's log shows `Realm 'bmc' imported` and `Bootstrap completed in 43.200000 seconds`; the synthetic accounts then sign in.
- **In CI:** the job "Sign-in end to end (local stack)" passes on PR #139 at `4ce888b`.
- **Note:** the stock 26.8.2 image has one HIGH finding in the scan (CVE-2026-102554), with a dated exception proposed in PR #139 for Martim to decide.

### 2.2 Flyway on MySQL (item 2)

- **What ran:** the Issue service's `V1__reporting.sql` and its repeatable seed, applied by Flyway to `mysql:8.4` in the integration tests (`services/issue`, `*IT`, Testcontainers).
- **In CI:** "Build and test (issue)" passes on `main` since PR #134.

### 2.3 Edge sign-in plugin (item 3)

Checked by `scripts/e2e-signin.sh` of PR #139 (19 checks; passed locally on 2026-11-24 and in CI), with plugin version v0.24.0:

| Question | Outcome |
|---|---|
| An API call without a session answers `401`, not a redirect | Pass |
| The plugin forwards the access token and the Issue service accepts it (audience `bmc-api`) | Pass |
| The session cookie is `HttpOnly` | Pass |
| `/logout` ends the session | Pass |
| A bearer token sent by the client without a session is refused | Pass |
| `SameSite=Strict` through the login redirects | Not tested in a browser: the script uses curl, which does not apply `SameSite` rules |
| Keycloak's token sizes fit in the cookie | Not measured: sign-in works for the synthetic accounts, which have few claims |
| Token renewal | Not tested: no check waits for an access token to expire |

Finding: the plugin ignores a return path given to `/oidc/login` and always lands on `/` (recorded on #80; the client has to keep the path itself).

### 2.4 Outbox publisher (item 4)

Tests of the messaging starter in PR #142 (`OutboxPublisherIT`), against MySQL 8.4 and RabbitMQ 4.4.1:

| Test | Shows |
|---|---|
| `aRowCommittedWhileTheBrokerIsDownIsSentWhenItIsBack` | A committed row that could not be published is sent by a later publisher |
| `aRowConfirmedButNotMarkedIsSentAgain` | A row confirmed by the broker but not yet marked `sent` is sent a second time with the same event ID |
| `aMessageBoundToNoQueueIsReturnedAndItsRowStaysPending` | The broker confirms an unroutable message; only the return shows it |

Why only partly: the kill is simulated (a failing update, and a connection to a closed port), not a JVM killed in the middle of a publish. The three behaviours above hold; a test that kills the process is still missing.

### 2.5 Dead-lettering and the delivery limit (item 5)

Queues declared as ADR-003 says: quorum, `x-delivery-limit` 5, `x-dead-letter-strategy` `at-least-once`, `x-overflow` `reject-publish`, dead-letter exchange `bmc.dlx`.

| Finding | Evidence |
|---|---|
| A message that always fails reaches its DLQ | `InboxListenerIT.afterTheDeliveryLimitTheEventIsInTheDeadLetterQueue` (PR #142); pika experiment: `ai-triage.requests.dlq` held 1 message afterwards |
| **`basic.nack` with requeue does not count towards the limit.** The first version of the consumer used it: the handler ran 793 times in 20 s and the message was never dead-lettered | Local test run of PR #142 before the fix, reported in its description; RabbitMQ documents the change for 4.3 and later ([E90](README.md#spike-en-02-01-accessed-2026-11-24)) |
| `basic.reject` with requeue counts | Same test after the fix; pika experiment below |
| **A limit of 5 gives 6 deliveries**: the first one and 5 redeliveries | Both the Java test and the pika experiment |
| `at-least-once` needs `reject-publish` | A queue declared without it logged: `Falling back to dead-letter-strategy at-most-once … because configured dead-letter-strategy at-least-once is incompatible with effective overflow strategy drop-head` |
| Without `x-delivery-limit` the limit is 20 | Broker log: `delivery_limit not set, defaulting to 20` |

pika experiment, rejecting every delivery with requeue:

```text
delivery 1: redelivered=False x-delivery-count=None x-acquired-count=None
delivery 2: redelivered=True x-delivery-count=1 x-acquired-count=1
delivery 3: redelivered=True x-delivery-count=2 x-acquired-count=2
delivery 4: redelivered=True x-delivery-count=3 x-acquired-count=3
delivery 5: redelivered=True x-delivery-count=4 x-acquired-count=4
delivery 6: redelivered=True x-delivery-count=5 x-acquired-count=5
deliveries before the queue stopped redelivering: 6
```

Also checked for ADR-003's "Verification", with a definitions file loaded when the broker starts ([`en-02-01-spike/definitions.json`](en-02-01-spike/definitions.json)) and one broker user per service without configure permission:

| Action | Result |
|---|---|
| The broker loads users, permissions, topic permissions, exchanges, queues and bindings from the file at start | Loaded: `Applying definitions from file at '/etc/rabbitmq/definitions.json'` |
| `issue` publishes `issue.TriageRequested` | Allowed, confirmed |
| `issue` publishes `ai-triage.TriageSuggested` | Refused: `403 ACCESS_REFUSED - write access to topic 'ai-triage.TriageSuggested' in exchange 'bmc.events'` |
| `issue` publishes `issue.ReportSubmitted`, bound to no queue, with `mandatory` | Returned |
| `ai-triage` declares its own queue passively | Allowed |
| `ai-triage` declares a new queue | Refused: `403 ACCESS_REFUSED - configure access to queue` |
| `ai-triage` binds its queue to `issue.#` | Refused: `403 ACCESS_REFUSED - write access to queue` |
| `ai-triage` reads `issue.inbox` | Refused: `403 ACCESS_REFUSED - read access to queue` |

Limit: the Spring consumer of PR #142 was tested with the broker's administrator only. The passive declaration being allowed suggests it will start as a restricted user, but that run is still missing.

### 2.6 Java and Python round trip (item 6)

- **What ran:** code generated by buf 1.71.0 from `contracts/proto` (PR #141) with the plugins `protocolbuffers/java:v36.2` and `protocolbuffers/python:v36.2`. Java 25 with protobuf-java 4.36.2 wrote five events as JSON; Python 3.14.10 with protobuf 7.36.2 parsed them and wrote them back; Java parsed that and compared with the originals. Python also built a `TriageSuggested` of its own for Java to read.
- **Events:** `IssueStateChanged` (a `false` value, unset optional fields, non-ASCII text), `TriageRequested` (emoji and a line break), `TriageSuggested` (optional fields), `BookingConfirmed` (timestamps), `NotificationRequested` (repeated field and map).
- **Result:** all six comparisons pass.

Finding: Java's `JsonFormat` parser refuses a field it does not know (`InvalidProtocolBufferException`) unless it is built with `ignoringUnknownFields()`. A consumer must use that option, or adding a field to an event breaks every consumer that has older generated code. Python's parser was not tested for this.

Scripts: [`en-02-01-spike/`](en-02-01-spike/README.md).

### 2.7 pika consumer (item 7)

- **What ran:** pika 1.5.0 on Python 3.14.10 in the base image of `services/ai-triage/Dockerfile` (`python:3.14-slim`, same digest), as the broker user `ai-triage`.
- **Result:** it consumed `TriageRequested` from `ai-triage.requests` with manual acknowledgement, published `TriageSuggested` with `mandatory` and a publisher confirm, acknowledged the request after the confirm, and the suggestion arrived in `issue.inbox`.

```text
consumed TriageRequested from issue routing key issue.TriageRequested content type application/cloudevents+json redelivered False
published TriageSuggested (confirmed, routed) and acknowledged the request
```

Findings and limits:

- pika 1.5.0 marks `BlockingConnection` as deprecated and announces its removal in pika 2.0. The AI service should start on the replacement the warning names.
- It did not run inside the built AI image next to gunicorn. How the consumer runs beside the web workers (its own process or a thread) is for US-03-01.

### 2.8 Memory (item 8)

Local stack of PR #139 (`compose.yaml` with `compose.local.yaml`) on amd64 (x86_64, Debian 13, 12 CPUs, 15.5 GiB), read with `docker stats` on 2026-11-24 after a start from empty and one run of `scripts/e2e-signin.sh` (a few sign-ins and one report):

| Container | Memory |
|---|---|
| keycloak | 689 MiB (limit 1,250 MiB) |
| mysql | 516 MiB |
| issue | 371 MiB |
| calendar-sim | 175 MiB |
| dispatch | 173 MiB |
| notification | 173 MiB |
| traefik | 74 MiB |
| ai-triage | 42 MiB |
| mailpit | 14 MiB |
| client | 10 MiB |
| **Total** | **2,237 MiB** |

RabbitMQ is not in the stack yet. Alone, idle, with five quorum queues, `rabbitmq:4.4-management` used 119 MiB.

Findings and limits:

- One reading, without load. Dispatch, Notification and the calendar simulator are still empty skeletons, so their numbers will grow.
- Only Keycloak and Mailpit have a memory limit. The Java services take the JVM default: the Issue JVM reported a maximum heap of 4,158,652,416 bytes on this host, a quarter of its memory. On a small host the default shrinks with it; a limit per service is still to set (EN01-U3).
- **arm64 was not measured.** No arm64 machine was used.

### 2.9 Storage adapter (item 9)

Not tested. No service has a storage adapter yet: attachments are not in a Sprint B story and report photos are post-MVP. ADR-010 stays unverified.

### 2.10 ArchUnit on Java 25 (item 10)

`services/issue/src/test/java/pt/isep/bmc/issue/ArchitectureTest.java` (ArchUnit 1.5.1, five rules for the hexagonal layers of ADR-009) runs on JDK 25 in "Build and test (issue)" and passes on `main` since PR #134.

## 3. EN01-U7 values

None of the values was measured under load: there is no load yet. They stay starting values.

| Mechanism | Value in technical design 4.6 | After the spike |
|---|---|---|
| Outbox publisher | Poll 1 s; backoff 2 s doubling to 5 min; no attempt limit | Kept. Implemented in PR #142. New: 5 s wait for a confirm, 50 rows per pass |
| Consumer delivery | `x-delivery-limit` 5, then DLQ | Kept as 5, which gives 6 deliveries. The team decides whether to keep it or set 4 for "up to 5 deliveries" |
| Redelivery pacing | Open in ADR-003 | New: the consumer waits 2 s before it returns a failed message, so a short outage does not use up the limit at once |
| Processed event IDs | At least 7 days | Kept: 7 days, cleaned every hour; less is refused |
| DLQ messages | Expire after 7 days | Declared in the experiments; the expiry was not waited for |
| AI call, triage pending notice, calendar call | 10 s and 2 attempts; 2 min; 5 s and 5 attempts | Kept unchanged: no AI or calendar code exists to exercise them |

## 4. What changes in the decisions

| # | Change | Where |
|---|---|---|
| 1 | A failed message is returned with `basic.reject` and requeue, never `basic.nack` | ADR-003 record; technical design 4.6 (in PR #142) |
| 2 | "Up to 5 deliveries" is 6 with a limit of 5 | ADR-003 record; value open for the team |
| 3 | Consumers parse payloads with unknown fields ignored | Contracts README, with the first consumer |
| 4 | The topology can be declared by a definitions file at broker start, with per-service users that cannot declare or bind | Confirms ADR-003, item 2; the file comes with RabbitMQ in the stack (#78) |

## 5. Still open after the spike

| Item | What is missing | Owner |
|---|---|---|
| 3 | A browser pass for `SameSite=Strict`, cookie size with real token sizes, token renewal | Ricardo, with EN-02-04 (#80) |
| 4 | A test that kills the publisher's process between the confirm and the commit | Ricardo, with #78 |
| 5 | The Spring consumer as a restricted broker user | Ricardo, with #78 |
| 7 | The consumer inside the AI image, beside gunicorn | Sandro, US-03-01 |
| 8 | arm64, load, and memory limits per service | Martim (EN01-U3) |
| 9 | Storage adapter on Garage and R2 | With the first story that stores a file |
| EN01-U7 | Values under load | With the NFR-04 tests |
