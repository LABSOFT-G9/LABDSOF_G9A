# Dispatch service

Bounded context **Dispatch** of BMC ([technical design §2.2](../../documents/07-technical-design.md#22-dispatch-service)): contractors, interventions and their proposal versions, with read projections of Issue's memberships and issues. Spring Boot 4.1 on Java 25, MySQL 8.4 with Flyway ([ADR-002](../../documents/adr/ADR-002-technology-stack.md)).

Implemented so far:

- US-06-01 ([#99](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/99)):
  - the service foundation, following the Issue service;
  - the synthetic contractor catalogue, eligibility and its documented order;
  - draft interventions (`awaiting_approval`) and replacing a draft's contractor;
  - the issue and membership projections, applied by version from Issue's events.
- US-07-01 ([#101](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/101)):
  - approval of a proposal version, which sends the request;
  - the assigned contractor's answer: accept, decline with a reason, or counterpropose;
  - changed terms by the administrator before the answer;
  - the contractor's own view of the requests sent to them;
  - the transactional outbox with `InterventionApproved`, `ContractorResponded` and `InterventionDeclined`.
- US-08-01 ([#103](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/103)):
  - booking the agreed window through `CalendarPort` and the calendar simulator (`services/calendar-sim`), by a worker that reconciles before any re-send;
  - manual confirmation, another booking request, reconciliation on demand and before the window;
  - the administrator's cancellation, pending while the calendar booking is cancelled;
  - `BookingConfirmed`, `BookingStateChanged`, `InterventionCancellationPending` and `InterventionCancelled`.

- US-09-01 ([#105](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/105)):
  - start, completion (by the contractor, or an administrator with a reason) and rework, with every completion report kept;
  - Issue's `IssueResolved` and `IssueCancelled` end the issue's intervention (technical design 4.3 step 8), through the booking cancellation of US-08.

Not yet: RabbitMQ and the messaging starter (EN-02-02, [#78](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/78)), so no event reaches this service and the outbox rows stay `pending`. The consumer of `IssueResolved` and `IssueCancelled` then only calls `IssueEvents.closure` and `ApplyIssueClosureUseCase`, as it does for the projections. Also not yet: the Google Calendar adapter (US-13) and costs (US-11).

## Architecture

The layers are hexagonal, as in Issue ([ADR-009](../../documents/adr/ADR-009-service-internal-architecture.md)). `ArchitectureTest` (ArchUnit) enforces them and fails `mvn verify` on a wrong dependency.

```text
pt.isep.bmc.dispatch
├── domain/            pure Java: no Spring, JPA, Jackson or servlet types
│   ├── projection/    ProjectedIssue, ProjectedMembership (applied by version), AdministratorAccess
│   ├── contractor/    Contractor, ContractorEligibility (who is eligible, in which order)
│   ├── intervention/  Intervention, InterventionState, ProposalVersion, TimeWindow, InterventionDrafting (domain service)
│   └── shared/        UserId (Keycloak sub), BuildingId, IssueId, PlainText, DomainException (sealed)
├── application/       use cases and ports; depends only on domain
│   ├── command/       CreateInterventionService, ReplaceContractorService, ProjectionService, PurgeExpiredIdempotencyKeysService
│   ├── query/         EligibleContractorsQueryService, InterventionQueryService
│   └── port/, idempotency/, error/
├── adapters/in/       REST controllers + DTOs, problem+json, correlation ID, scheduled purge, Issue event mapping
├── adapters/out/      JPA (interventions), JDBC (projections, catalogue, read model, idempotency keys), UUIDv7
└── config/            composition root: wiring, security, Flyway seed, scheduling
```

## API

Paths are service-relative. The edge serves them under `/api/dispatch` and strips that prefix. Every endpoint needs a bearer access token; every `POST` and `PATCH` needs the CSRF header (`X-BMC-CSRF`), as in Issue.

| Operation | Who | Result |
|---|---|---|
| `GET /contractors?issueId=` | Administrators of the issue's building | `{issueId, criteria, items}`. `criteria` is `{category, issueState, dispatchable, eligibility, order}`. Each item is `{contractorId, displayName, synthetic, specialisations, matched, unknown}` |
| `POST /interventions` + `Idempotency-Key` | The same | Body `{issueId, contractorId, scopeExtract, window: {start, end}, terms}`. `201`, `ETag: "1"`, `Location`, `{interventionId, state: "awaiting_approval", version: 1, proposalVersion: 1, contractorId}`. Sends nothing |
| `PATCH /interventions/{id}` + `If-Match: "<version>"` + `Idempotency-Key` | The same, for the intervention's building | Body `{contractorId}`. Only in `awaiting_approval`. `200`, `ETag` with the new version, `{interventionId, state, version, contractorId}` |
| `POST /interventions/{id}/approval` + `If-Match` + `Idempotency-Key` | Administrators of the intervention's building | Body `{proposalVersion}`, the current one. `200`, `{interventionId, state, version, approvedProposalVersion}`: `sent`, or `scheduling_pending` for a contractor's counterproposal. Writes `InterventionApproved` |
| `POST /interventions/{id}/proposals` + `If-Match` + `Idempotency-Key` | The same | Body `{scopeExtract, window, terms}`, as a draft. From `awaiting_approval` or `sent`. `200`, `{interventionId, state: "awaiting_approval", version, proposalVersion}`. An earlier approval no longer counts. No event |
| `POST /interventions/{id}/response` + `If-Match` + `Idempotency-Key` | The assigned contractor, once sent | Body `{proposalVersion, decision}` with `decision` `accept`, `decline` (+ `reasonCode`, optional `note`) or `counterpropose` (+ `window`, `terms`, optional `proposedCost: {amount, currency}`). `200`, `{interventionId, state, version, proposalVersion}`. Writes `ContractorResponded`, or `InterventionDeclined` for a decline |
| `GET /interventions/{id}` | Administrators of the building; the assigned contractor, once sent | `ETag` with the version. Administrators: `{interventionId, issueId, state, version, contractor, proposal, proposals, approvedProposalVersion, agreedProposalVersion, booking, bookings, cancellation, completions, createdAt, updatedAt}`, where `proposal` is the current version and `proposals` every version with `proposedCost`, `approvedAt` and the contractor's `response`. The contractor: `{interventionId, state, version, contractor, work: {category, urgency, areaLabel}, awaitingResponseTo, agreedProposalVersion, proposals, booking: {state, source, window}, completions, updatedAt}` |
| `GET /interventions?issueId=` | Administrators of the issue's building | `{items}`, newest first, terminal ones included |
| `POST /interventions/{id}/manual-confirmation` + `If-Match` + `Idempotency-Key` | Administrators of the building | Body `{window, source, reason}`, `source` `phone`, `email` or `in_person`. Only in `scheduling_pending`. `200`, `booked`. Writes `BookingConfirmed` with source `manual` |
| `POST /interventions/{id}/booking` + `If-Match` + `Idempotency-Key` | The same | Another request for the agreed window after `rejected` or `failed`. `200` |
| `POST /interventions/{id}/booking/reconciliation` | The same | `202`: the worker checks the calendar-confirmed booking now |
| `POST /interventions/{id}/cancellation` + `If-Match` + `Idempotency-Key` | The same | Body `{reasonCode, note?}`. `200`, `cancelled`, or `cancellation_pending` until the calendar booking is cancelled |
| `POST /interventions/{id}/start` + `If-Match` + `Idempotency-Key` | The assigned contractor | From `booked`: `in_progress`. Writes `InterventionStarted` |
| `POST /interventions/{id}/completion` + `If-Match` + `Idempotency-Key` | The assigned contractor; administrators with a reason | Body `{summary, performedAt, reason?}`. From `booked` or `in_progress`: `completion_reported`. Writes `CompletionReported`; not a closure |
| `POST /interventions/{id}/rework` + `If-Match` + `Idempotency-Key` | Administrators of the building | Body `{reason}`. From `completion_reported`: `in_progress`. Writes `ReworkRequested` |
| `GET /interventions?assigned=me` | Contractors | `{items}`: the requests sent to the caller, last changed first, in the contractor's shape |

**Rules (US-06):**

- **Who.** Only an active `administrator` membership of the issue's building, in Dispatch's membership projection, counts (technical design 4.3). Anyone else gets `404`: residents, contractors, other buildings' administrators, and callers asking about an issue the projection does not know. A revocation takes effect as soon as `MembershipRevoked` is applied.
- **Eligible.** A contractor is eligible if it is active, covers the issue's building and has the issue's decided category as a specialisation. All three must be known, so an issue without a decided category has nobody eligible.
- **Order.** By display name ignoring case, then the exact name, then the contractor ID. Price, distance and availability are listed under `unknown`, never guessed.
- **Draft fields.** `scopeExtract` is 10–2,000 characters and `terms` 1–1,000. Both are trimmed, counted as Unicode code points, and refused with control characters. `window.start` is in the future and `window.end` is after it: RFC 3339 with an offset, stored in UTC at millisecond precision. A time after `9999-12-31T23:59:59.999Z` in UTC is refused with `out_of_range`, because the database cannot hold it. There is no maximum length.
- **Check order.** Each check answers before the next one runs:
  1. Issue known and caller administers its building (`404`).
  2. Fields (`400`, every invalid field at once). `issueId` is the exception: the controller needs it to find the issue, so a missing or malformed `issueId` answers `400` with that field alone, before step 1.
  3. Issue dispatchable (`409 INVALID_TRANSITION` with `currentState`).
  4. No other non-terminal intervention (`409 INVALID_TRANSITION` with `activeInterventionId`).
  5. Contractor eligible now (`400`, `contractorId`: `not_eligible`; an unknown contractor answers the same).
- **Replacing a contractor.** The checks run in this order:
  1. Administrator of the building (`404`).
  2. Valid `contractorId` (`400`).
  3. Still `awaiting_approval` (`409 INVALID_TRANSITION`).
  4. Version (`409 VERSION_CONFLICT` with `currentVersion`, so the client reloads).
  5. Issue still dispatchable (`409 INVALID_TRANSITION`). Here `currentState` is the issue's state, not the intervention's: both lists have `in_progress` and `cancelled`, and the draft itself is still `awaiting_approval`.
  6. Eligible now (`400 not_eligible`).

  The draft is read again under its row lock before the version check, so two replacements sent with the same `If-Match` answer `200` and `409 VERSION_CONFLICT`. The same holds for every command below; their proposal versions are read with a locking read too, so they see a version another transaction has just added.

  Every successful save moves the version, even when it saves the same contractor, because the save re-checked eligibility. The terms (proposal version 1) stay as they were.
- **Approval (US-07).** The checks run in this order:
  1. Administrator of the building (`404`).
  2. `proposalVersion` present (`400`).
  3. Still `awaiting_approval` (`409 INVALID_TRANSITION`), then the version (`409 VERSION_CONFLICT`).
  4. `proposalVersion` is the current one (`400 not_current`).
  5. Issue still dispatchable (`409`, the issue's `currentState`).
  6. The contractor is still eligible (`422 CONTRACTOR_NOT_ELIGIBLE`) and has a login (`422 CONTRACTOR_HAS_NO_ACCOUNT`).

  Approving an administrator's version sends it: `sent`. Approving a contractor's counterproposal is their acceptance as well, so the terms are agreed: `scheduling_pending` (technical design 3.2). Once a request was sent, the contractor sees the intervention and cannot be replaced (`PATCH` answers `422 CONTRACTOR_ALREADY_CONTACTED`); another contractor needs a new intervention after a decline.
- **The contractor's answer (US-07).** Only the contractor whose login is the catalogue entry's, after the request was sent; anyone else gets `404`. Then the fields (`400`), the state `sent` (`409`), the version (`409`), `proposalVersion` is the approved one (`400 not_current`), and the issue (`409`). Decline reasons are `unavailable`, `outside_expertise`, `terms_not_acceptable` and `other`; the `note` (1–500 characters) is for the building's administrators and never goes into an event or a log. A counterproposal keeps the administrator's scope extract and sets a new window and terms, with an optional cost in `EUR` as a decimal string (`"85.00"`, at most 9,999,999.99). Fields that do not belong to the decision are ignored. Silence is never acceptance: a request without an answer stays `sent`.
- **Changed terms (US-07).** A new administrator version from `awaiting_approval` or `sent`, back in `awaiting_approval`: an approval sent earlier no longer counts, so the contractor cannot answer it (`409`), and the contractor does not see the new terms until they are approved. Agreed terms (`scheduling_pending`) do not change.
- **Booking (US-08).** Agreement (an acceptance, or the approval of a counterproposal) saves a `requested` booking in its transaction, before any call. The booking ID is the calendar's request ID and idempotency key, and the request content is stored, so every re-send is the same request: the contractor's `calendar_ref`, the building ID as building code, and a summary made from the category ("Plumbing intervention"). Never a name, an address or report text. Without a calendar reference, or with a window that has started, the booking fails at once.
  - **The worker** (`BookingWorker`, every second) claims the booking that is due, calls the calendar outside any transaction (5-second timeout), then applies the reply only to the version it claimed. A booking changed meanwhile keeps its new state, whose own lookup finds anything the call created. Everything is in the database, so a restart resumes: a claim's 60-second lease ends and the booking is due again.
  - **Outcomes (technical design 4.5).** A valid confirmation (it echoes the request ID, has a reference and the requested window): `confirmed`, the intervention `booked`, `BookingConfirmed`. `409`: `rejected` (`slot_conflict`). `422`: `failed` (`contract_defect`), logged as an error, never retried. `503` or no connection: retried after 5, 10, 20 and 40 seconds, then `failed` (`retries_exhausted`). A timeout or any doubtful `2xx`: `uncertain`, looked up before any re-send; found is `confirmed`, absent is re-sent with the same key.
  - **Checks.** A calendar-confirmed booking is looked up again 24 hours before its window, and on demand (`…/booking/reconciliation`). Missing is a `discrepancy` (`missing_at_provider`), never recreated.
  - **By hand.** The administrator confirms with a window, a source and a reason (`confirmed`, source `manual`). An automatic request that was still `requested` or `uncertain` is looked up once more; a booking found there is a `discrepancy` (`found_after_manual`), never a second confirmation. After `rejected` or `failed` the administrator can also ask again (`…/booking`): a new booking with a new ID.
  - **What the contractor sees:** the booking's state, source and window.
- **Cancellation.** Codes `no_longer_needed`, `contractor_unavailable`, `rescheduling`, `other`, with an optional note for administrators (never in an event or log). Before agreement it is `cancelled` at once. From `scheduling_pending` on, a booking that exists or may exist (requested, uncertain, confirmed by the calendar, or manual with an outstanding request) is cancelled at the calendar first: `cancellation_pending`, retried without limit until the calendar answers, then `cancelled`. A late confirmation never revives a cancelled intervention.
- **Work and completion (US-09).** The checks run in the usual order: who (`404`), fields (`400`), state and version (`409`), then the issue: a `resolved` or `cancelled` issue takes no more work (`409` with the issue's state). A completion report has a summary (10–2,000 characters) and `performedAt` (not in the future). An administrator reporting for the contractor gives a reason, which the contractor does not see. Rework records its reason on the report it sends back, and the next report gets the next number, so the history keeps every report. No cost until US-11. The summary and the reasons stay in Dispatch: the events carry only the intervention's state.
- **Issue's closure (technical design 3.2, 4.3 step 8).** `IssueResolved` or `IssueCancelled`, under the issue projection row's lock:
  - `completion_reported` becomes `closed`, with no event, because Issue made the closure;
  - an earlier state is cancelled with `issue_resolved` or `issue_cancelled`, through `cancellation_pending` while the booking is cancelled at the calendar;
  - a terminal or pending intervention does not change, so a repeated or late event has no effect.
- **Events.** Each change writes one outbox row in its transaction, as CloudEvents JSON with the proto3 JSON `data` of `contracts/proto/bmc/dispatch/v1` and routing key `dispatch.<type>`. They carry IDs, versions, codes and the new `interventionState`, never the scope, terms or note. A repeated command writes nothing again.
- **One active intervention per issue.** Saving a draft locks the issue's projection row, and so does applying an Issue event (technical design 4.3 step 8). A unique key on the active intervention per issue backs this up.
- **Idempotency.** Same as Issue: the key is scoped per user and operation and kept for 24 hours. A replay re-checks the caller's permissions. The same key with a different body gets `422 IDEMPOTENCY_KEY_REUSED`.
- **Errors** are `application/problem+json` with `code` and `correlationId`, as in Issue. A deadlock between concurrent transactions answers `503 DEPENDENCY_UNAVAILABLE` with `Retry-After`; nothing was saved.

## Projections and events

Dispatch decides with its own copies of Issue's data. Only Issue's events write them, never a request:

- `issue_projection` comes from `IssueStateChanged`;
- `membership_projection` comes from `MembershipGranted` and `MembershipRevoked`.

Each event is applied by its version, so a repeated, late or out-of-order event changes nothing, and a replayed grant cannot undo a later revocation. `adapters/in/messaging/IssueEvents` reads the events' `data` (the proto3 JSON of `contracts/proto/bmc/issue/v1`).

**Not wired yet.** The RabbitMQ handlers that call `ProjectionService` come with the messaging starter in EN-02-02 (#78). Deduplication by event ID is the starter's job, and so is its `processed_events` table. Until then, the integration tests feed synthetic events through the same mapping and use case.

## Synthetic seed

`BMC_SEED_SYNTHETIC=true` (local and dev only) loads `db/seed/R__synthetic_fixtures.sql`:

- **Catalogue.** Six synthetic contractors. Only `Synthetic Plumbing Co.` (`…e001`) is linked to a Keycloak account, `synthetic.contractor@bmc.example` (`sub …0301`). The others have no login (`user_id` NULL), so a request to one of them cannot be approved (`422 CONTRACTOR_HAS_NO_ACCOUNT`). Each has a calendar reference, `SIM-CTR-001` to `SIM-CTR-006`.
- **Temporary projection stand-in**, until RabbitMQ is wired:
  - the five memberships Issue seeds, with the same IDs and versions;
  - one dispatchable plumbing issue in Synthetic Building A, `0199f2a0-0000-7000-8000-00000000f001`.

  **This issue exists in Dispatch only.** Issue has no issue with this ID, so the client cannot open it from an issue page. Use its ID directly, for example `GET /api/dispatch/contractors?issueId=0199f2a0-0000-7000-8000-00000000f001` as `synthetic.administrator.a@bmc.example`. That lists `Synthetic Aqua Plumbers`, then `Synthetic Plumbing Co.`.

## Configuration

The variables are the same as Issue's (see [its README](../issue/README.md#configuration)), with `DB_NAME` and `DB_USER` defaulting to `dispatch`. `BMC_SEED_SYNTHETIC` loads the seed above. `CALENDAR_BASE_URL` (default `http://calendar-sim:8080`, the Compose service) is the calendar simulator; each call times out after 5 seconds.

## Build and test

```bash
mvn verify   # unit + ArchUnit (surefire, *Test) and MySQL 8.4 integration tests (failsafe, *IT; needs Docker)
```

The unit tests cover the domain rules, and the use cases at the application layer with in-memory fakes of the ports (`application/InMemoryPorts`), as in Issue: the check order of a draft, a replacement, an approval, an answer and changed terms, the events they write, replays, the booking worker against an in-process calendar (`FakeCalendar`) and the booking commands, and projections applied by version, with their retries.

The integration tests use Testcontainers and locally signed tokens, as Issue's do. They cover:

- the access matrix, including a revoked administrator;
- eligibility and its order;
- every validation error at once;
- the `409`s, including two replacements or two approvals sent from the same version, and an acceptance racing changed terms;
- approval, acceptance, decline and counterproposal over HTTP, with the outbox rows they write and the contractor's view;
- idempotent replays and key reuse, concurrent retries with one key, expired keys and their purge (`IdempotencyIT`);
- concurrent drafts for one issue;
- events that race for one projection row;
- booking over HTTP against a calendar stub with the simulator's contract (`BookingIT`): confirmation, a timeout after creation, a conflict and a manual confirmation, exhausted retries and another request, a pending cancellation, a lost booking found on demand, a contractor without a calendar reference;
- the calendar adapter against the real simulator (`SimulatorCalendarAdapterIT`), only when `CALENDAR_SIMULATOR_URL` is set (see the class for the command);
- start, completion, rework and Issue's closure events over HTTP (`ExecutionIT`);
- the database down (`StorageFailureIT`) and private text kept out of DEBUG logs (`LogPrivacyIT`);
- a restart;
- the synthetic seed.
