# [US-20] See system health and get alerted, including degraded mode

**Status:** local draft, not published to GitHub. Query GitHub for the `[US-20]` title prefix before creating the issue.

## Planning

- Epic: [EP-04 — Engineering and quality](../epics/EP-04-engineering-and-quality.md).
- Priority: Must (proposed), Assignment §6.6–§6.8.
- Estimate: not set; estimate at planning.
- Sprint: S2 (health checks, correlation IDs, metrics); S3 (dashboard, alerts, degraded-mode demo).
- Dependencies: running services and [US-18](US-18-build-pipeline-to-dev.md); scraping of the database host (P-08); alert chat channel not chosen; behaviour of [US-14](US-14-continue-triage-without-ai.md) for the AI-unavailable scenario.
- Suggested labels: `Story`, `devops`, `backend`.

## Story

As an **operator**, I want to **see the health of the system on one dashboard and be alerted when something fails or runs in degraded mode**, so that **problems are noticed and handled quickly**.

## Scope

Implement the plan in [OBSERVABILITY.md](../../devops/OBSERVABILITY.md): `/healthz` and `/readyz` per service, correlation IDs, Prometheus metrics, the Grafana dashboard, the alerts of §7, log-based error reporting and one demonstrated degraded-mode scenario (AI unavailable, §8).

Not covered: backup alerts ([US-21](US-21-back-up-and-restore-data.md)); the fallback behaviour itself ([US-14](US-14-continue-triage-without-ai.md)).

## Acceptance criteria

- [ ] Each service exposes liveness and readiness endpoints; readiness reports the state of its dependencies.
- [ ] A request can be followed across services by one correlation ID in logs that hold no personal data.
- [ ] Prometheus scrapes the application and database hosts, and the Grafana dashboard shows the SLIs of §5. Dashboards are provisioned from Git.
- [ ] The alerts of §7 that apply to the chosen architecture reach the chosen chat channel; stopping a service triggers "target down" within the stated time.
- [ ] A `degraded_mode` indicator turns on when the AI service is unavailable, shows on the dashboard, triggers the warning alert and clears on recovery.
- [ ] The degraded-mode demo script (§8.4) is run once and the result recorded, with reporting still working during the failure.
- [ ] Thresholds and owners are documented and reviewed so alerts do not fire constantly.

## Verification and risks

Stop the AI service, the database exporter and one gateway-backed service in dev and check dashboard and alerts. Risks: unresolved scraping path (P-08); alert fatigue; metric labels leaking personal data; monitoring cost on a small host.

## Ready / Done

- [ ] Ready: estimate recorded, alert channel chosen, P-08 resolved or scoped out, [DoR](../../governance/definition-of-ready.md) reviewed.
- [ ] Done: acceptance criteria met, demo evidence recorded, configuration committed and reviewed.
