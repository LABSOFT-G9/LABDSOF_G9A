# Infrastructure repository proposal (DEC-12)

This document proposes a separate infrastructure repository for BMC, owned by a new GitHub organization, and lists options for linking it to this repository. The DevOps owner (Martim) chooses among them.

Related: [decision register, DEC-12](../planning/decisions-and-feedback.md#dec-12-proposal--infrastructure-repository-2026-11-15), [ADR-002](../adr/ADR-002-technology-stack.md) (monorepo layout), [ADR-004](../adr/ADR-004-deployment-contract.md) (deployment contract), [CI/CD strategy](CICD.md), [disaster recovery](DR.md).

**Status:** proposal, recorded 2026-11-15. The organization and the infrastructure repository do not exist yet, and nothing below is applied to GitHub.

## 1. Why a separate repository

- ADR-004 places MySQL, Garage and `mysqld_exporter` on a database host and Garage on a backup host, **outside** the application host's Compose project, and leaves how they are defined open (DEVOPS-02). That configuration has its own change rhythm, its own reviewers and host access that application developers do not need.
- [CI/CD §4](CICD.md#4-golden-images) already proposes keeping golden images in their own repository (Phase 3), and [CI/CD §11.5](CICD.md#115-when-to-move-to-an-organization) describes moving to an organization for teams, roles and push restrictions.
- Host configuration, IP addresses and runbooks are easier to keep private in a separate repository, while this repository stays public.

Cost: two repositories to keep consistent, cross-repository links and, if deploys are triggered across repositories, a credential between them.

## 2. What would move and what would stay (suggestion)

| Stays in LABDSOF_G9A | Moves to the infrastructure repository |
|---|---|
| Service and client code, Dockerfiles, `compose.yaml` of the application host with its profiles (ADR-004), CI that builds, scans, signs and pushes images to GHCR ([CI/CD](CICD.md)) | Definition of the database host and the backup host (MySQL, Garage, `mysqld_exporter`, backup cron job) |
| Contracts (`contracts/proto`, `contracts/asyncapi`) and the Keycloak realm file `infra/keycloak/bmc-realm.json` ([ADR-005](../adr/ADR-005-authentication-authorisation.md)), because they change with the application | Host provisioning and hardening (packages, users, SSH, firewall, private link), whatever tool DevOps picks |
| Product, design and decision documents, including ADRs | Runbooks: backup schedule and rotation, restore exercise ([DR.md](DR.md) §6), host move |
| | Golden images, if Phase 3 of [CI/CD](CICD.md) is reached (could also be a third repository) |

The realm file is listed as staying because it is part of the application's contract; DevOps can decide otherwise.

## 3. Base information for the new repository

Only the skeleton; no infrastructure files are proposed here.

- `README.md`: purpose, link back to this repository and to ADR-004, the three hosts (application, database, backup) and who owns them, status of each.
- `AGENTS.md`: short; points to this repository's `AGENTS.md` and `labdsof-workflow` skill for shared rules (commit format `<ID>: <summary>`, reviewed PRs, no secrets, no invented evidence).
- `.github/`: the same issue and PR templates as this repository; the same labels and milestones (`Sprint A/B/C`).
- Suggested layout: `hosts/application/` (host setup only, not the app's Compose file), `hosts/database/`, `hosts/backup/`, `provisioning/`, `docs/runbooks/`.
- Secrets never in Git: host environment or GitHub environment secrets, as in [CI/CD](CICD.md) and ADR-004.

## 4. Linking options (DevOps decides)

| Option | How | Notes |
|---|---|---|
| **A. Cross-links and one board** (suggested baseline) | READMEs and `AGENTS.md` link each other; infrastructure issues reference this repository's epics with cross-repository references (`Phenriquerafael/LABDSOF_G9A#N`), and both repositories' issues are on one GitHub Project | No credentials between repositories; works whatever deploy option is chosen |
| B. Deploy by event | After a release, this repository's workflow sends `repository_dispatch` with the image digest to the infrastructure repository, which deploys it | Needs a token with access to the other repository; an organization GitHub App is preferable to a personal PAT. Keeps "deploy by digest" from CI/CD §6 |
| C. Reusable workflows | The infrastructure repository exposes a `workflow_call` deploy workflow that this repository's `main.yml`/`release.yml` call | Calling a reusable workflow in another repository needs that repository to allow access from the caller |
| D. Git submodule | One repository included in the other | Not suggested: extra steps on every clone and update, and friction with squash-merged PRs |

A and one of B or C can be combined.

## 5. Organization steps (checklist, not done)

- [ ] Create a free GitHub organization; owners and members agreed by the team.
- [ ] Teams `developers`, `tech-leads`, `devops` as in [CI/CD §11.5](CICD.md#115-when-to-move-to-an-organization).
- [ ] Create the infrastructure repository in the organization with the base information of section 3.
- [ ] Decide whether and when to transfer LABDSOF_G9A to the organization (can be a later step).
- [ ] Record the outcome in DEC-12 and update ADR-002's layout row.

## 6. Impacts to resolve before transferring LABDSOF_G9A

- **Board and metrics.** The board is the personal Project 9. [`workflow-scripts/snapshot.js`](../../workflow-scripts/snapshot.js) hard-codes `owner = "Phenriquerafael"`, `projectNumber = 9` and queries `user(login:)`; an organization project needs `organization(login:)` and a new number, and the `metrics.yml` and `check-sprints.yml` workflows and their history would need checking. Whether a personal project can hold issues from organization repositories, or be moved to the organization, is not verified here.
- **GHCR paths.** Images move from `ghcr.io/<user>/...` to `ghcr.io/<org>/...` ([CI/CD §11.5](CICD.md#115-when-to-move-to-an-organization)); workflows and deployment files must follow.
- **Links and commands.** Documents, the `labdsof-workflow` skill and `AGENTS.md` use `github.com/Phenriquerafael/LABDSOF_G9A` and `gh ... --repo Phenriquerafael/LABDSOF_G9A`. GitHub redirects transferred repositories, but the text should be updated.
- **Rulesets and environments** from [CI/CD §12](CICD.md#12-repository-settings-checklist) would be set up on the organization repository, pointing to teams instead of usernames.

## 7. Open questions

- Organization name and owners.
- Public or private infrastructure repository.
- Which linking option(s) from section 4.
- Provisioning tool, if any (DEVOPS-02 left the host definition open).
- Whether golden images share the infrastructure repository or get their own.
