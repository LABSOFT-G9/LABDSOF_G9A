# Event contracts

The messages the BMC services exchange through RabbitMQ ([ADR-003](../documents/adr/ADR-003-asynchronous-messaging-rabbitmq.md), [technical design 4.4](../documents/07-technical-design.md#44-event-contracts)).

| Folder | Content |
|---|---|
| `proto/` | One Buf module. Each event payload is a Protobuf message; these files are the only source of the payload fields. |
| `asyncapi/asyncapi.yaml` | AsyncAPI 3 document: the exchange, the queues, who publishes and who consumes, and the CloudEvents envelope. |

No service uses the generated types yet, and the broker is not in the stack yet. Both come with the messaging starter of EN-02-02 (#78).

## On the wire

Each message is a CloudEvents 1.0 structured JSON document, published to the topic exchange `bmc.events` with the routing key `<producer>.<eventType>` (for example `issue.IssueStateChanged`). Its `data` member is the payload in the [proto3 JSON mapping](https://protobuf.dev/programming-guides/json/) (accessed 2026-11-24).

```json
{ "specversion": "1.0",
  "id": "0199f2a1-0000-7000-8000-000000006003",
  "type": "IssueStateChanged",
  "source": "issue",
  "subject": "0199f2a1-0000-7000-8000-000000002001",
  "time": "2026-11-03T09:40:27Z",
  "datacontenttype": "application/json",
  "schemaversion": 1,
  "aggregateversion": 3,
  "buildingid": "0199f2a0-0000-7000-8000-00000000b001",
  "correlationid": "c-7f9d",
  "data": { "issueId": "0199f2a1-0000-7000-8000-000000002001",
            "buildingId": "0199f2a0-0000-7000-8000-00000000b001",
            "state": "triaged", "version": 3, "dispatchable": true,
            "category": "plumbing", "urgency": "high",
            "areaLabel": "Garage level -1" } }
```

## Packages

| Package | Producer (`source`) | Events |
|---|---|---|
| `bmc.issue.v1` | `issue` | `ReportSubmitted`, `TriageRequested`, `IssueTriaged`, `IssueSummaryPublished`, `IssueStateChanged`, `MembershipGranted`, `MembershipRevoked`, `IssueResolved`, `IssueCancelled`, `IssueReopened` |
| `bmc.aitriage.v1` | `ai-triage` | `TriageSuggested` |
| `bmc.dispatch.v1` | `dispatch` | `InterventionApproved`, `ContractorResponded`, `InterventionDeclined`, `InterventionCancellationPending`, `InterventionCancelled`, `BookingConfirmed`, `BookingStateChanged`, `InterventionStarted`, `CompletionReported`, `ReworkRequested` |
| `bmc.notification.v1` | `issue` or `dispatch` | `NotificationRequested` |

The version in the package (`v1`) is the envelope's `schemaversion` (1).

## Queues and bindings

| Queue | Consumer | Bound routing keys |
|---|---|---|
| `ai-triage.requests` | AI triage | `issue.TriageRequested` |
| `issue.inbox` | Issue | `ai-triage.TriageSuggested` and the ten `dispatch.*` events of `bmc.dispatch.v1` |
| `dispatch.inbox` | Dispatch | `issue.IssueStateChanged`, `issue.MembershipGranted`, `issue.MembershipRevoked`, `issue.IssueResolved`, `issue.IssueCancelled` |
| `notification.inbox` | Notification | `issue.NotificationRequested`, `dispatch.NotificationRequested` |

`ReportSubmitted`, `IssueTriaged`, `IssueSummaryPublished` and `IssueReopened` have no consumer outside Issue. Whether they are published at all is open in [ADR-001](../documents/adr/ADR-001-backend-boundaries.md): a message published with `mandatory` and bound to no queue is returned, so its outbox row would stay pending.

## Rules for the payloads

| Topic | Rule | Reason |
|---|---|---|
| Identifiers | `string` (UUID text) | As in the REST API. |
| States, categories and other fixed sets | `string` with the allowed values in the field comment, not a Protobuf `enum` | The values are the lower-case tokens of the REST API and of the design examples (`triaged`, `plumbing`). Protobuf enum names would be a second vocabulary (`ISSUE_STATE_TRIAGED`) on the wire. Each consumer decides what an unknown value means for it. |
| Versions and counters | `int32` | proto3 JSON writes `int64` as a string; the examples use numbers. |
| Times | `google.protobuf.Timestamp` | RFC 3339 in JSON. |
| A value that may be missing | `optional` | Absent, never a made-up default. JSON `null` is read as absent. |
| Default values | Producers also print fields that hold the default (`false`, `0`, `""`); consumers read an absent field as its default | Printers leave defaults out unless told otherwise (Java `alwaysPrintFieldsWithNoPresence()`, Python `always_print_fields_with_no_presence=True`). |
| Private data | Only `TriageRequested` carries report text and only an invitation's `NotificationRequested` carries an address | Technical design 4.4. Payloads are never logged. |

A comment marked `OPEN` names a value set the design has not fixed yet and the story that settles it.

## Changing a contract

`buf lint` and `buf breaking` run on every pull request (job "Protobuf contracts (buf)"). `buf breaking` uses the `WIRE_JSON` rules against `main`.

- Add fields with new numbers. Never change or reuse a field number or a JSON name.
- A change that breaks consumers needs a new package (`v2`) and a new `schemaversion`.
- Events that no service publishes yet are listed under `breaking.ignore` in `proto/buf.yaml`, so their owners can still change them. Remove the line in the pull request that first publishes the event.
- A new event needs its `.proto` file, its message and channel entries in `asyncapi/asyncapi.yaml`, and its row in technical design 4.4. `scripts/check-contracts.sh` fails when the two folders disagree.

## Local checks

```bash
docker run --rm -v "$PWD":/w -w /w bufbuild/buf:1.71.0 lint contracts/proto
docker run --rm -v "$PWD":/w -w /w bufbuild/buf:1.71.0 format --diff --exit-code contracts/proto
scripts/check-contracts.sh
npx @asyncapi/cli validate contracts/asyncapi/asyncapi.yaml
```
