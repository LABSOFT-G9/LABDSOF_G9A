# Calendar simulator

A controllable external calendar for Dispatch ([ADR-006](../../documents/adr/ADR-006-calendar-simulator.md), [technical design §4.5](../../documents/07-technical-design.md#45-calendarsimulator-contract-outline)). Dispatch books through its `CalendarPort`. This simulator is the adapter's target for integration tests, offline work and the failure demos (§6.5, §6.8): every outcome of §4.5 can be made to happen on demand. An always-successful stub would not test anything (US-08).

Spring Boot 4.1 on Java 25. Built for US-08-01 ([#103](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/103)); the Dispatch adapter comes in the next pull request of that issue.

- **Internal network only.** Nothing routes to it from the edge, and it has no authentication.
- **In memory.** A restart loses every booking, which is the `discrepancy` case of §4.5.
- **No private data.** A request holds the booking ID, the contractor's calendar reference, the window, a building code and a short summary. Never a name, address or report text. Nothing is logged.

## Calendar API

The contract of §4.5, with the shapes of [annex A 1.5](../../documents/07a-contract-examples.md#15-booking-with-a-timeout-and-reconciliation-dispatch--calendar-simulator). `openapi.yaml` describes it. A booking is `{requestId, externalRef, status, window: {start, end}}`; times are answered in UTC.

| Operation | Answers |
|---|---|
| `POST /bookings` + `Idempotency-Key` (= `requestId`) | `201` booked; `200` the same booking again (same request ID and content); `409 SLOT_CONFLICT` (the same contractor reference has a confirmed booking whose window overlaps); `422 INVALID_REQUEST` with every invalid field, or `422 IDEMPOTENCY_KEY_REUSED` (the same request ID with other content); `503 UNAVAILABLE` |
| `GET /bookings?requestId=` | `200 {items}`: the booking, cancelled or not, or none. Used to reconcile before any re-send |
| `POST /bookings/{externalRef}/cancellation` + `Idempotency-Key` | `200` cancelled, also when it was cancelled before; `404 NOT_FOUND`; `503` |

At most one booking exists per request ID, even when retries arrive at the same time (NFR-11).

## Scripting failures

Only with `SIM_CONTROL_ENABLED=true`; otherwise `/control` answers `404`. Each operation (`create`, `query`, `cancel`) has a queue of outcomes; each call takes the next one, and with an empty queue the calendar works normally.

| Outcome | `create` | `query` | `cancel` |
|---|---|---|---|
| `ok` | Normal | Normal | Normal |
| `conflict` | `409`, nothing booked | — | — |
| `unavailable` | `503`, nothing booked | `503` | `503`, nothing cancelled |
| `invalid` | `201` without `externalRef` or `window`, nothing booked | An item without `externalRef` | — |
| `delay` + `delayMs` (≤ 60,000) | Waits, then normal | Waits, then normal | Waits, then normal |
| `timeout_after_create` | Books, then waits `SIM_HANG` (15 s, beyond Dispatch's 5 s timeout) before answering | — | — |

| Control call | Effect |
|---|---|
| `POST /control/outcomes` `{operation, outcomes: [{type, delayMs?}]}` | Appends to the queue. `204`, or `422` with the invalid entries |
| `GET /control/outcomes`, `DELETE /control/outcomes` | Shows or empties the queues |
| `GET /control/bookings` | Every booking held |
| `DELETE /control/bookings/{externalRef}` | Loses one booking, for a `discrepancy` |
| `DELETE /control/bookings` | Loses every booking and empties the queues, as a restart does |

For example, a timeout after the booking was made, then an unavailable reconciliation:

```bash
curl -X POST localhost:8080/control/outcomes -H 'Content-Type: application/json' \
  -d '{"operation":"create","outcomes":[{"type":"timeout_after_create"}]}'
curl -X POST localhost:8080/control/outcomes -H 'Content-Type: application/json' \
  -d '{"operation":"query","outcomes":[{"type":"unavailable"}]}'
```

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SIM_CONTROL_ENABLED` | `false` (`true` in `compose.local.yaml`) | Whether `/control` exists |
| `SIM_HANG` | `PT15S` | How long `timeout_after_create` waits |
| `MANAGEMENT_PORT` | `8081` | `/healthz` and `/readyz` |

## Build and test

```bash
mvn verify   # calendar rules (CalendarTest) and the API over HTTP (BookingApiTest, ControlDisabledTest)
```

`BookingApiTest` covers each outcome above: a client that gives up after 500 ms while the booking is made, 12 concurrent retries with one key (one booking), and reconciliation. `ControlDisabledTest` checks that `/control` is off by default.
