# Issue service

Bounded context **Issue** of BMC ([technical design §2.1](../../documents/07-technical-design.md#21-issue-service)): buildings, areas, memberships, reports, issues and their history. Spring Boot 4.1 on Java 25, MySQL 8.4 with Flyway ([ADR-002](../../documents/adr/ADR-002-technology-stack.md)).

Implemented so far: US-01-01 ([#85](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/85)), meaning report submission and queries, with building permissions and 24-hour idempotency.

## Architecture

The layers are hexagonal, also called onion or clean architecture ([ADR-009](../../documents/adr/ADR-009-service-internal-architecture.md)). Dependencies point inwards only. `ArchitectureTest` (ArchUnit) enforces this and fails `mvn verify` on a wrong dependency.

```text
pt.isep.bmc.issue
├── domain/            pure Java: no Spring, JPA, Jackson or servlet types
│   ├── building/      BuildingId, Area, AreaKind
│   ├── membership/    Membership, UserId (Keycloak sub), BuildingAccess (access matrix rules)
│   ├── report/        Report, ReportText (10–4,000 code points), ReportIntake (domain service)
│   ├── issue/         Issue, IssueState, IssueHistoryEntry, CriticalCheckStatus
│   ├── event/         DomainEvent: ReportSubmitted, TriageRequested
│   └── shared/        DomainException (sealed): ValidationException, InvalidAreaException, AccessDeniedException
├── application/       use cases and ports; depends only on domain
│   ├── port/in/       SubmitReportUseCase, GetReportQuery, ListReportsQuery, GetProfileQuery, ...
│   ├── port/out/      repositories, EventOutbox, IdempotencyStore, TransactionRunner, IdGenerator, read models
│   ├── command/       SubmitReportService, PurgeExpiredIdempotencyKeysService
│   ├── query/         ReportQueryService, ProfileQueryService, BuildingAreasQueryService
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
| `GET /buildings/{id}/areas` | Members of the building | Areas for the report form |

- **Errors** are `application/problem+json` with `code` and `correlationId`:
  - `400 VALIDATION_FAILED` (with `errors[]`) or `INVALID_AREA`
  - `401 UNAUTHENTICATED`
  - `403 CSRF_HEADER_REQUIRED`, for a state-changing request without the CSRF header
  - `404 NOT_FOUND`, for anything unknown or outside the caller's scope
  - `405` for any attempt to modify a report
  - `422 IDEMPOTENCY_KEY_REUSED`
  - `503 DEPENDENCY_UNAVAILABLE`, which never comes with a receipt

  Error bodies never contain the report text.
- **CSRF** ([ADR-005](../../documents/adr/ADR-005-authentication-authorisation.md#csrf)). Every `POST`, `PUT`, `PATCH` or `DELETE` must carry the custom header named by `CSRF_HEADER` (default `X-BMC-CSRF`), with any non-blank value. A cross-origin page cannot add it without a CORS pre-flight, and there is no CORS. Reads need no header. The check runs before token validation, so such a request gets `403` even without a token.
- **Idempotency.** The key is scoped per user and operation and kept for 24 hours. The same key and body replay the original `201`, including when several retries run at the same time. The same key with a different body gets `422`.
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
