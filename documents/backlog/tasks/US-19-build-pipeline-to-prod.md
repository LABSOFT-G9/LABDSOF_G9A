# [US-19] Build the pipeline to production

**Status:** local draft, not published to GitHub. Query GitHub for the `[US-19]` title prefix before creating the issue.

## Planning

- Epic: [EP-04 — Engineering and quality](../epics/EP-04-engineering-and-quality.md).
- Priority: Must for the academic release (proposed).
- Estimate: not set; estimate at planning.
- Sprint: S3.
- Dependencies: [US-18](US-18-build-pipeline-to-dev.md); the production environment from [US-17](US-17-set-up-aws-infrastructure.md); named tech leads and DevOps users for the tag ruleset and environment reviewers (CI/CD §14); [US-22](US-22-control-access-and-secrets.md).
- Suggested labels: `Story`, `devops`.

## Story

As a **tech lead**, I want to **release a version to production by pushing a protected tag, with an approval step and a tested rollback**, so that **production changes are deliberate, traceable and reversible**.

## Scope

Implement `release.yml`, the tag ruleset and the `production` environment from [CI/CD strategy §7 and §8](../../devops/CICD.md), including the procedures for fixing a bad release and for rollback.

Not covered: building and signing the image ([US-18](US-18-build-pipeline-to-dev.md)); creating the production infrastructure ([US-17](US-17-set-up-aws-infrastructure.md)).

## Acceptance criteria

- [ ] Only users allowed by the tag ruleset can create a release tag; others are rejected.
- [ ] A release tag triggers `release.yml`, which promotes an image already built, scanned and signed on `main` (no rebuild) and waits for approval from a required reviewer in the `production` environment.
- [ ] Deployment to production uses production-only credentials and verifies the image signature before starting.
- [ ] The release records version, commit SHA, image digest, approver and time.
- [ ] A rollback to the previous version is rehearsed once in dev, with the steps and duration documented.
- [ ] The "fixing a bad release" procedure (§7) is rehearsed and written up with the real result.
- [ ] A schema-changing release requires a recent passing restore test ([US-21](US-21-back-up-and-restore-data.md)).

## Verification and risks

Rehearse a release and a rollback end to end, and try to tag as an unauthorised user. Risks: one account owning everything (§11.5); untested rollback; a rollback that cannot undo a schema migration.

## Ready / Done

- [ ] Ready: estimate recorded, tech leads and reviewers named, production environment exists, [DoR](../../governance/definition-of-ready.md) reviewed.
- [ ] Done: acceptance criteria met, rehearsal evidence stored, ruleset and environment settings documented with evidence.
