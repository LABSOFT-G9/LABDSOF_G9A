# [US-17] Set up the infrastructure in AWS

**Status:** local draft, not published to GitHub. Query GitHub for the `[US-17]` title prefix before creating the issue.

## Planning

- Epic: [EP-04 — Engineering and quality](../epics/EP-04-engineering-and-quality.md).
- Priority: Must (proposed).
- Estimate: not set; estimate at planning. Likely large; split into dev and prod environments if needed.
- Sprint: S2 for dev; S3 for prod.
- Dependencies: **decision to use AWS.** The repository currently records the provider as open (P-07, EN01-U3, CI/CD §14), and [DR.md §9](../../devops/DR.md) says the earlier AWS proposal conflicts with [ADR-004](../../adr/ADR-004-deployment-contract.md). Record the decision and update ADR-004 before work starts. Also needed: AWS account and budget owner, [US-22](US-22-control-access-and-secrets.md) for access and secrets.
- Suggested labels: `Story`, `devops`.

## Story

As a **DevOps engineer**, I want **the dev and production infrastructure created in AWS in a repeatable and documented way**, so that **the pipelines have somewhere to deploy and the architecture is demonstrably running**.

## Scope

Create what [ADR-004](../../adr/ADR-004-deployment-contract.md) and the [physical views](../../07b-physical-views.md) require, on AWS:

- the application host (Docker Compose, Traefik gateway, services) and the separate database host (MySQL and Garage), with the private link between them (open in ADR-004);
- networks and security groups matching the `edge`, `internal` and `egress` split;
- DNS and TLS for the gateway;
- a place for backups (the proposed Backup host in [DR.md](../../devops/DR.md), if adopted);
- credentials for the pipelines to deploy.

Not covered: the deployment steps themselves ([US-18](US-18-build-pipeline-to-dev.md), [US-19](US-19-build-pipeline-to-prod.md)); monitoring ([US-20](US-20-see-health-and-get-alerted.md)); backups ([US-21](US-21-back-up-and-restore-data.md)).

## Acceptance criteria

- [ ] The decision to use AWS is recorded, ADR-004 is updated, and a budget and a spending alert are set. Prices and free-tier limits are checked on the vendor pages, with dates.
- [ ] A dev environment exists with an application host and a database host, matching the physical views, and the services' Compose stack starts on it.
- [ ] Only the gateway is reachable from the internet; the database host accepts connections only from the application host over the private link. Security groups are documented and reviewed.
- [ ] TLS is served for the public name, and the certificate renews automatically.
- [ ] The infrastructure is described as code or as a reproducible runbook, whichever the team decides; another member rebuilds it from that description in a clean state and records the result.
- [ ] Deploy credentials for the pipelines are least-privilege and short-lived where possible (for example GitHub OIDC to AWS), stored as environment secrets, and never in the repository.
- [ ] A production environment is created with separate credentials and separate data from dev, and only after the dev environment works (S3).
- [ ] No personal data or real secrets are loaded; dev uses synthetic data only.

## Verification and risks

Rebuild the dev environment from the description, then run a port scan from outside the team's network on the dev address only to confirm that only the expected ports answer. Risks: cost overrun; provider and ADR-004 inconsistent; a private link between hosts that is still undefined; credentials with wider rights than needed; a single account for dev and prod.

## Ready / Done

- [ ] Ready: estimate recorded, AWS decision and ADR update done, budget owner named, [DoR](../../governance/definition-of-ready.md) reviewed.
- [ ] Done: acceptance criteria met, environment documented with evidence, cost estimate recorded, reviewed by someone other than the author.
