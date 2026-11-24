# Issue service

Bounded context **Issue** of BMC ([technical design §2.1](../../documents/07-technical-design.md#21-issue-service)): buildings, areas, memberships, reports, issues and their history. Spring Boot 4.1 on Java 25, MySQL 8.4 with Flyway ([ADR-002](../../documents/adr/ADR-002-technology-stack.md)).

Implemented so far:

- US-01-01 ([#85](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/85)): report submission and queries, with building permissions and 24-hour idempotency.
- US-02-01 ([#87](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/87)): manual triage with `If-Match`, the triage rows of the state table, issue queries and history projected by role.
- US-04-01 ([#89](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/89)): the shared summary an administrator publishes, who sees a shared issue, impact confirmations and subscriptions.

## Architecture

The layers are hexagonal, also called onion or clean architecture ([ADR-009](../../documents/adr/ADR-009-service-internal-architecture.md)). Dependencies point inwards only. `ArchitectureTest` (ArchUnit) enforces this and fails `mvn verify` on a wrong dependency.

```text
pt.isep.bmc.issue
├── domain/            pure Java: no Spring, JPA, Jackson or servlet types
│   ├── building/      BuildingId, Area, AreaKind
│   ├── membership/    Membership, UserId (Keycloak sub), BuildingAccess (access matrix rules)
│   ├── report/        Report, ReportText (10–4,000 code points), ReportIntake (domain service)
│   ├── issue/         Issue, IssueState, IssueTriage (domain service), TriageDecision, TriageReason, IssueHistoryEntry
│   ├── event/         DomainEvent: ReportSubmitted, TriageRequested, IssueTriaged, IssueStateChanged
│   └── shared/        DomainException (sealed): validation, invalid area, access denied, forbidden, version conflict, invalid transition
├── application/       use cases and ports; depends only on domain
│   ├── port/in/       SubmitReportUseCase, DecideTriageUseCase, GetReportQuery, GetIssueQuery, GetProfileQuery, ...
│   ├── port/out/      repositories, EventOutbox, IdempotencyStore, TransactionRunner, IdGenerator, read models
│   ├── command/       SubmitReportService, DecideTriageService, PurgeExpiredIdempotencyKeysService
│   ├── query/         ReportQueryService, IssueQueryService, ProfileQueryService, BuildingAreasQueryService
│   ├── idempotency/   IdempotencyKey, IdempotentExecutor (one effect per key)
│   └── error/         NotFoundException, IdempotencyKeyReusedException, StorageUnavailableException
├── adapters/in/       driving: REST controllers + DTOs, problem+json mapping, correlation ID, scheduled purge
├── adapters/out/      driven: JPA (aggregates), JDBC (idempotency keys, read models), outbox, UUIDv7
└── config/            composition root: @Configuration wiring, security, Flyway seed, scheduling
```

- **Dependency injection.** Use cases are plain classes. Only `config/UseCaseConfig` creates them, by constructor injection, from their ports. Adapters implement the ports.
- **Transactions** are a port (`TransactionRunner`), not an annotation, so the application layer stays framework-free.
- **CQRS** exists only as packages. Commands go through the domain and the outbox. Queries read SQL projections of the same tables.
- **Adapters never depend on each other.** JPA entities are adapter classes, never the domain objects.

## API

Paths are service-relative. The edge serves them under `/api/issue` and strips that prefix. Every endpoint needs a bearer access token.

| Operation | Who | Result |
|---|---|---|
| `POST /reports` + `Idempotency-Key` | Resident of the building, or its administrator (source `administrator_on_behalf`) | `201`, `ETag: "1"`, `{reportId, issueId, state: "received", version: 1, submittedAt}` |
| `GET /reports/{id}` | The author, or an administrator of the building | Text, area, source, `authoredByMe` and the issue's `{state, version}`. Never the author's identity |
| `GET /reports?buildingId=&page=&size=` | Any user | Own reports, plus every report of the buildings the caller administers, newest first. No text |
| `GET /me` | Any user | Profile and active memberships, with building names |
| `GET /issues?buildingId=&page=&size=` | Any user | Issues of the buildings the caller administers, the issues the caller reported, and the issues with a published summary in the buildings the caller belongs to, newest first. Each item: `{issueId, buildingId, buildingName, areaLabel, state, version, createdAt, triage, suggestion, summary, impact, subscribed}`. No report text, no triage reason |
| `GET /issues/{id}` | Administrators of the building; whoever reported it; once a summary is published, every member of the building | The same item, `ETag` with the version. `triage` is `null` until decided, else `{category, urgency, reason, decidedAt, decisionSource: "human"}`; `reason` is `null` for residents. `suggestion` is `null` until US-03-03. `summary` is `null` until published, else `{text, publishedAt}`. `impact` is `null` until published, else `{count, mine}`. `subscribed` is the caller's own choice |
| `GET /issues/{id}/history` | The same | `{items: [{sequence, type, visibility, actorRole, occurredAt, category, urgency, reason}]}`, oldest first. Residents get public entries only, without reasons. Roles, never identities |
| `POST /issues/{id}/triage` + `If-Match: "<version>"` + `Idempotency-Key` | Administrators of the building | Body `{category, urgency, reason}`. `200`, `ETag` with the new version, `{issueId, state: "triaged", version, category, urgency, decisionSource: "human"}` |
| `POST /issues/{id}/publication` + `If-Match: "<version>"` + `Idempotency-Key` | Administrators of the building | Body `{summary}`. `200`, `ETag` with the new version, `{issueId, version, summary: {text, publishedAt}}`. Publishing again replaces the summary |
| `PUT` / `DELETE /issues/{id}/impact` | Residents of the building, for themselves, once a summary is published | `200`, `{count, mine}`. Safe to repeat |
| `PUT` / `DELETE /issues/{id}/subscription` | Anyone who may see the issue, for themselves | `200`, `{subscribed}`. Safe to repeat |
| `GET /buildings/{id}/areas` | Members of the building | Areas for the report form |

- **Errors** are `application/problem+json` with `code` and `correlationId`:
  - `400 VALIDATION_FAILED` (with `errors[]`) or `INVALID_AREA`
  - `401 UNAUTHENTICATED`
  - `403 FORBIDDEN`, when the caller can see an issue but may not do this to it: triage or publish as a resident, confirm impact as an administrator
  - `403 CSRF_HEADER_REQUIRED`, for a state-changing request without the CSRF header
  - `404 NOT_FOUND`, for anything unknown or outside the caller's scope
  - `405` for any attempt to modify a report, or to set an issue's state directly
  - `409 VERSION_CONFLICT` (with `currentVersion`, `currentState`), `INVALID_TRANSITION` (with `currentState`) or `NOT_PUBLISHED` (impact on an issue with no summary yet)
  - `422 IDEMPOTENCY_KEY_REUSED`
  - `503 DEPENDENCY_UNAVAILABLE`, which never comes with a receipt

  Error bodies never contain the report text or a triage reason.
- **CSRF** ([ADR-005](../../documents/adr/ADR-005-authentication-authorisation.md#csrf)). Every `POST`, `PUT`, `PATCH` or `DELETE` must carry the custom header named by `CSRF_HEADER` (default `X-BMC-CSRF`), with any non-blank value. A cross-origin page cannot add it without a CORS pre-flight, and there is no CORS. Reads need no header. The check runs before token validation, so such a request gets `403` even without a token.
- **Idempotency.** The key is scoped per user and operation and kept for 24 hours. The same key and body replay the original `201`, including when several retries run at the same time. The same key with a different body gets `422`.
- **Triage** (technical design 3.1 and 3.3). Categories: `plumbing`, `electrical`, `lift`, `cleaning`, `structural`, `other`. Urgencies: `low`, `normal`, `high`, `critical`. Reason: 1 to 500 characters, internal.
  - From `received`, `in_triage` or `triaged` to `triaged`; a decision can be corrected by deciding again, and every decision is kept. From any later state: `409 INVALID_TRANSITION`.
  - `If-Match` is the quoted version the administrator was looking at. If the issue changed since: `409 VERSION_CONFLICT`, and nothing is overwritten. Of several decisions based on the same version, exactly one wins.
  - The decision, the issue's next version, the history entry and the `IssueTriaged` and `IssueStateChanged` outbox rows are one transaction. Events never carry the reason.
  - Works without AI. A suggestion never sets category or urgency, and a late one changes nothing (`Issue.suggestTriage`); recording suggestions is US-03-03 ([#108](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/108)).
- **Sharing with the building** (US-04, access matrix).
  - **Summary.** Plain text, trimmed, 10 to 500 characters; reasons `required`, `too_short`, `too_long`, `invalid_characters`. Only an administrator of the building publishes it. It is the only text about an issue that residents see; the report text stays private. Every published summary is kept.
  - **Version.** Publishing raises the issue's version and nothing else: state, category and urgency stay. `If-Match` and `Idempotency-Key` work as for triage. The summary, the issue's next version, a public `summary_published` history entry and the `IssueSummaryPublished` and `IssueStateChanged` outbox rows are one transaction. Events carry the summary's ID, never its text.
  - **Visibility.** A published summary makes the issue visible to every member of its building, with what a reporter sees: no reason, no suggestion, public history only. Other buildings, contractors and revoked members get `404`.
  - **Impact.** At most one confirmation per resident and issue; only the count is shown, never who. It changes neither the issue nor its version, and produces no event or history entry. Reporting is not a confirmation. Administrators see the count and get `403` if they try to confirm.
  - **Subscription.** The caller's own choice, off by default and separate from impact. Turning it off removes no access. Nothing reads it yet: recipients are chosen in US-10-03 ([#110](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/110)).
- **One transaction per report.** The report, the issue, the first history entry, both outbox rows (`ReportSubmitted`, `TriageRequested`, as CloudEvents JSON, status `pending`) and the key are committed together. The request waits for neither AI nor the broker. The publisher that sends outbox rows to RabbitMQ is part of EN-02-02 ([#78](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/78)).

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost`, `3306`, `issue`, `issue`, empty | MySQL |
| `OIDC_ISSUER_URI` | empty | Keycloak realm issuer. **When empty, every API call answers `401`** (fail closed until EN-02-04) |
| `OIDC_JWK_SET_URI` | `<issuer>/protocol/openid-connect/certs` | Signing keys |
| `OIDC_AUDIENCE` | `bmc-api` | Required `aud`. When blank, the service refuses to start |
| `CSRF_HEADER` | `X-BMC-CSRF` | Header required on state-changing requests. Provisional name until EN01-U6 settles it; the client must send the same one. When blank, the service refuses to start |
| `BMC_SEED_SYNTHETIC` | `false` | `true` loads `db/seed/R__synthetic_fixtures.sql` (synthetic buildings and accounts). Never set it in production |
| `MANAGEMENT_PORT` | `8081` | `/healthz` (liveness) and `/readyz` (readiness, includes MySQL). Never routed by Traefik |

The synthetic user IDs in the seed are the Keycloak `sub` values that the synthetic realm (EN-02-04, [#80](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/80)) must assign.

## Build and test

```bash
mvn verify   # unit + ArchUnit (surefire, *Test) and MySQL 8.4 integration tests (failsafe, *IT; needs Docker)
```

The integration tests start MySQL with Testcontainers, using the same image pin as `compose.local.yaml`. They sign tokens with a local key served as a JWKS, so the real validation path runs: signature, issuer, audience, expiry and `sub`. They cover:

- the access matrix
- concurrent retries
- a restart
- the database going down
- an empty issuer configuration
- a state-changing request without the CSRF header
- report text never appearing in logs, even at DEBUG
