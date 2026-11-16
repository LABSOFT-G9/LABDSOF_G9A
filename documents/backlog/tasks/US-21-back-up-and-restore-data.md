# [US-21] Back up and restore data

**Status:** local draft, not published to GitHub. Query GitHub for the `[US-21]` title prefix before creating the issue.

## Planning

- Epic: [EP-04 — Engineering and quality](../epics/EP-04-engineering-and-quality.md).
- Priority: Must for the academic release (proposed), NFR-09.
- Estimate: not set; estimate at planning.
- Sprint: S3.
- Dependencies: database host and backup location decided ([DR.md §9](../../devops/DR.md), ADR-004, ADR-010); photo retention rule (DEC-07) open; [US-20](US-20-see-health-and-get-alerted.md) for backup alerts.
- Suggested labels: `Story`, `devops`, `security`.

## Story

As an **administrator**, I want **nightly backups that are verified and a tested restore procedure**, so that **reports and attachments can be recovered within the agreed time after a failure**.

## Scope

Implement the proposal in [DR.md](../../devops/DR.md): nightly backup of the four service databases and the `bmc-attachments` bucket, integrity checks, the restore runbook and the restore test. The RPO and RTO targets are proposals and need confirmation.

Not covered: off-host cloud copy (optional, outside the academic release); a multi-node or high-availability database.

## Acceptance criteria

- [ ] RPO and RTO targets are confirmed by the team and recorded.
- [ ] A scheduled job produces nightly backups of the databases and attachments, storing checksum, size, time and row counts.
- [ ] The spike confirms that Keycloak's signing keys are in its database dump.
- [ ] Backups are access-restricted or encrypted, contain no secrets, and follow the decided retention policy (including photos, DEC-07).
- [ ] A failed or missing backup raises an alert (heartbeat and failure message, [OBSERVABILITY.md §7](../../devops/OBSERVABILITY.md)).
- [ ] A restore into a scratch environment follows the runbook (§6): integrity checks pass, services start, the smoke journey works and the duration is within the RTO.
- [ ] Restore evidence (date, copy name, duration, counts, result, who ran it) contains no personal data.
- [ ] The restore test is repeated before any release that changes the schema; a failure raises an issue before the next release.

## Verification and risks

Delete a record or database in the scratch environment and restore from the latest backup. Risks: untested runbook; single-node Garage and a database host failure taking backups with it; unresolved photo retention.

## Ready / Done

- [ ] Ready: estimate recorded, backup location decided and ADR updated, [DoR](../../governance/definition-of-ready.md) reviewed.
- [ ] Done: acceptance criteria met, restore evidence stored, [DR.md](../../devops/DR.md) updated from "proposal" to the observed result.
