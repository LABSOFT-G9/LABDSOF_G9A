# Pipeline settings checklist

Target repository settings for the pipeline, from [CICD.md §11.3 and §12](CICD.md#113-how-to-set-it-up). Written for US-18 ([#71](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/71)) and US-22 ([#75](https://github.com/LABSOFT-G9/LABDSOF_G9A/issues/75)).

The repository is `LABSOFT-G9/LABDSOF_G9A`, owned by the free organization `LABSOFT-G9`. Changing these settings needs the repository **Admin** role.

## Access

| # | Setting | Desired state | Applied |
|---|---|---|---|
| A1 | Organization | `LABSOFT-G9`, Free plan. Custom repository roles are not available on Free, so project roles map to teams with built-in roles | yes |
| A2 | Team `maintainers` | Repository role **Maintain**. Members: Martim, Ricardo | yes |
| A3 | Team `developers` | Repository role **Write**. Members: Pedro, Afonso, Sandro | yes |
| A4 | Admin | Martim, as an individual collaborator | yes |
| A5 | Individual collaborator entries | None besides the admin; everyone else gets access through a team | no |
| A6 | Project board | Org project [LABSOFT-G9 #1](https://github.com/orgs/LABSOFT-G9/projects/1), linked to the repository, "Auto-add to project" on for this repository's issues; `maintainers` Admin and `developers` Write on the board | partly |

## Rulesets

| # | Setting | Desired state | Applied |
|---|---|---|---|
| 1 | Ruleset `main-review` on the default branch (`main`) | Pull request required; 1 approval; the most recent push approved by someone other than its pusher; stale approvals dismissed on push; conversations resolved; force pushes and deletion blocked. Bypass: **Admin** role | yes |
| 1b | Ruleset `main-maintainers-only` on `main` | Restrict updates: only the bypass list can merge or push to `main`. Bypass: `maintainers` team and **Admin** role. Developers open, review and approve PRs, but do not merge | yes |
| 2 | Required status check `ci-required` | Required in `main-review` once `pr.yml` is on `main` | no |
| 3 | Ruleset `tags-maintainers-only` | All tags (covers `v*` for `prod.yml`): creation, update and deletion restricted. Bypass: `maintainers` team and **Admin** role | yes |

The Admin bypass lets an admin merge without another person's approval. It is for emergencies only; GitHub logs each bypass under Settings → Rules → Insights.

No automation pushes to `main`: the metrics bot (`metrics.yml`) delivers its snapshot as a pull request.

## Actions, environments and security

| # | Setting (where) | Desired state | Applied |
|---|---|---|---|
| 4 | Actions → General → Workflow permissions | Read repository contents permission (read-only default); workflows elevate per job | no |
| 5 | Actions → General → Fork pull request workflows | Require approval for first-time contributors | no |
| 6 | Environment `dev` (Settings → Environments) | No reviewers; deployment branches: `main` only; dev-only credentials. Must exist before the first merge of the pipeline: `dev.yml` uses it on every merge, and GitHub creates a missing environment by itself with default settings | no |
| 7 | Variables on `dev` | `DEPLOY_ENABLED` (unset or `false` until the dev host, #83, and the deploy step in `deploy.yml` work) and `APP_URL` (public dev URL, not a secret) | no |
| 8 | Environment `production` | Required reviewers: the `maintainers` team; deployment tags `v*` only; no admin bypass. Must exist before the first `v*` tag: if `prod.yml` runs against a missing environment, GitHub creates one with no reviewers | no |
| 8b | Variables on `production` | `DEPLOY_ENABLED` (unset or `false` until the prod host exists; a release then only retags, verifies and creates the GitHub Release) and `APP_URL` | no |
| 9 | Code security → Secret scanning and push protection | Enabled | no |
| 10 | Code security → Dependabot alerts and version updates | Enabled (`.github/dependabot.yml` is in the repository) | no |
| 11 | Code scanning (CodeQL) | Results uploaded by `pr.yml` and `dev.yml` under Security → Code scanning; default setup **not** also enabled, to avoid two analyses | no |
| 12 | GHCR packages | Images `ghcr.io/labsoft-g9/labdsof_g9a-<service>`; visibility checked, and Manage Actions access for this repository (`packages: write`) | no |
| 14 | Branches | No `dev` branch; no ruleset or environment refers to one | yes |

Not in this checklist: the `production` secrets and the GHCR cleanup token (Phase 3), which belong to US-19 and US-22. Test the release gate once with a throwaway tag (CICD.md §7) before the first real release.
