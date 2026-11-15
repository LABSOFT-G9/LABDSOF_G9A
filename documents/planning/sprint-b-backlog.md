# Sprint B — backlog proposal

**Status:** draft for planning; **no committed items**.

The term Sprint B was specified in the Sprint 1 phases, but its correspondence with Sprint 1/2/3, duration and dates is not documented. DEC-03 must clarify this. This plan assumes only a next work cycle that prepares/demonstrates a minimal slice; it does not rename Sprint 2.

**Scope constraint recorded on 2026-10-10 (DEC-11):** the evaluator clarified that the walking skeleton is optional in Sprint 1, and the team will not develop it this sprint. The implementation objective, EN-02/US-01/US-02 candidates and demonstration below apply only to a later implementation cycle. If Sprint B falls within Sprint 1, select discovery/design work instead; EN-01 remains design work without a required executable prototype.

## Candidate objective

Demonstrate a persisted text report and authorised status query while closing decisions and gathering evidence supporting the MVP.

## Candidates in dependency order

Full IDs and criteria are in the [product backlog](../05-product-backlog.md).

| Order | Item | Candidate outcome | Missing dependencies/DoR | Estimate | Owner | Selected |
|---|---|---|---|---|---|---|
| 1 | DISC-03 | Scope, schedule and priorities discussed | Participants/date, question and session duration | To be estimated | To be assigned | No |
| 2 | DISC-01 | Initial user evidence | Recruitment, owner and retention | To be estimated | To be assigned | No |
| 3 | EN-01 | Sufficient design/stack/security for the skeleton | DEC-04/07, technical review | To be estimated | To be assigned | No |
| 4 | EN-02 | Executable build, test and containers | EN-01, environment and checks | To be estimated | To be assigned | No |
| 5 | US-01 | Report/query with building membership | DEC-07, EN-01/02, reviewed criteria | To be estimated | To be assigned | No |
| 6 | US-02 | Basic states and manual correction | US-01, transition rules | To be estimated | To be assigned | No |

DISC-02 (competitors) may replace a candidate if it offers greater value for decisions. This does not authorise committing to all items without capacity.

## Planning record

| Field | Value |
|---|---|
| Correspondence with official sprint | To be confirmed in DEC-03 |
| Dates and duration | Board iteration and milestone: 1 November to 5 December 2026 (35 days); official correspondence still to be confirmed in DEC-03 |
| Individual availability / absences | To be filled in |
| Total estimate and contingency | To be calculated by the team |
| Items actually selected | None |
| Final agreed objective | Pending |
| Planning date/participants | Pending |

## Board publication on 2026-11-15

On 2026-11-15, day 15 of the Sprint B iteration (1 November to 5 December 2026), Ricardo published the Sprint B work breakdown on the board. It has 48 new issues (#68–#115): EN-02 and its six sub-tasks, DevOps stories US-16 to US-23, and the layer sub-tasks of the Sprint B stories. Allocation and the later revisions are in the [backlog README](../backlog/README.md#sprint-b-sub-issues-and-allocation). The Sprint B burndown will show a step in scope once the 2026-11-15 snapshot is collected. This is added scope, not a data error.

The table above was not filled in at that point. Team capacity, availability and a confirmed selection that fits capacity are still **not recorded**, and DEC-03 is unchanged. The new items start in Backlog, without estimates on EN-02 or the DevOps stories, until the team records the DoR check.

Risks accepted on 2026-11-15 (Ricardo, after the board review). The full report-to-closure MVP stays in Sprint B. Owners stay as allocated even though the load is uneven: counting only the Sprint B sub-tasks published on 2026-11-15, Ricardo has 11, Pedro 9, Martim 6, Afonso 6 and Sandro 3. Martim also owns the Sprint B stories US-16 and US-18 and the carried-over #12. The schedule depends on EN-02 starting this week. Revisit both at the next sprint review. If EN-02 slips, descope from the end of the journey first (US-09, then US-08).

## DevOps stories shared on 2026-11-15

Ricardo shared this list in the planning session of 2026-11-15. It is the source of issues #69–#76 and is recorded here as shared, without further detail. The sprint column uses the list's own S2/S3 labels. On the board, S2 work is in the Sprint B milestone and S3 work in Sprint C, as the release plan does for the MVP. The official correspondence is still to be confirmed in DEC-03.

| ID | Story | Sprint in the list |
|---|---|---|
| US-16 | Set up GHCR: naming, tags, permissions, pull credential and retention | S2 |
| US-17 | Set up the infrastructure in AWS: dev first, then prod | S2 for dev, S3 for prod |
| US-18 | Build the pipeline to dev: CI checks, signed image, push, deploy and ZAP | S2 |
| US-19 | Build the pipeline to prod: protected tag, approval, rollback | S3 |
| US-20 | See health and get alerted, including degraded mode | S2–S3 |
| US-21 | Back up and restore data | S3 |
| US-22 | Control access and secrets | S2–S3 |
| US-23 | Build golden base images | S3 |

## DoR gate

- [ ] Team agreement and capacity known.
- [ ] Each candidate's criteria and dependencies reviewed.
- [ ] Estimates recorded and blocking decisions resolved.
- [ ] [DoR](../governance/definition-of-ready.md) reviewed per item; owner/reviewer agreed and DoR date/author recorded before Ready.
- [ ] Selection fits capacity and supports the objective.

Items without DoR remain in the product backlog. A spike may be selected to resolve uncertainty if it has a question, effort limit and verifiable output.

## Proposed demonstration and tracking

An authorised test user records an issue; after restarting the service, they query the same ID. Another building cannot read/modify the issue. Show the build, automated test and containers; reporting does not depend on AI.

Update progress on the board and reflect decisions at the review. See [walking skeleton](../10-walking-skeleton.md).

Reference: [Sprint Backlog — Mountain Goat Software](https://www.mountaingoatsoftware.com/agile/scrum/artifacts/sprint-backlog), accessed on 2026-10-07. This document prepares for team selection; it is not an approved commitment.
