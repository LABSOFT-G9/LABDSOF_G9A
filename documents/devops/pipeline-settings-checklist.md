# Pipeline settings checklist

Settings that only the repository owner (Pedro) can apply by hand, from [CICD.md §11.3 and §12](CICD.md#113-how-to-set-it-up). Written for US-18 ([#71](https://github.com/Phenriquerafael/LABDSOF_G9A/issues/71)) and US-22 ([#75](https://github.com/Phenriquerafael/LABDSOF_G9A/issues/75)).

**Every item is "not applied".** The pipeline files only exist in the repository; none of these settings has been changed through the API or confirmed in the GitHub UI. Replace the status with the date, the person and a link or screenshot as evidence once an item is applied.

| # | Setting (where) | Value | Status |
|---|---|---|---|
| 1 | Ruleset `main-quality` on `main` (Settings → Rules → Rulesets) | Active, no bypass list; PR with 1 approval; Code Owner review; block force pushes and deletion | not applied |
| 2 | Same ruleset: required status check | Require `ci-required` **only after EN-02 code exists and one CI run has reported it**; before that, jobs are skipped and the check proves little | not applied |
| 3 | Tag ruleset for `v*` | Restrict creations, updates and deletions; bypass only named tech leads and DevOps (needed by US-19) | not applied |
| 4 | Actions → General → Workflow permissions | Read repository contents permission (read-only default); workflows elevate per job | not applied |
| 5 | Actions → General → Fork pull request workflows | Require approval for first-time contributors | not applied |
| 6 | Environment `dev` (Settings → Environments) | No reviewers; deployment branches: `main` only; dev-only credentials | not applied |
| 7 | Variables on `dev` | `DEV_DEPLOY_ENABLED` (leave unset or `false` until the dev host, #83, and `scripts/deploy.py` work) and `DEV_URL` (public dev URL, not a secret) | not applied |
| 8 | Environment `production` | Required reviewers (Martim, Ricardo); `v*` tags only; no admin bypass (needed by US-19) | not applied |
| 9 | Code security → Secret scanning and push protection | Enabled | not applied |
| 10 | Code security → Dependabot alerts and version updates | Enabled (`.github/dependabot.yml` is in the repository) | not applied |
| 11 | Code scanning (CodeQL) | Results uploaded by `repo-checks.yml` appear under Security → Code scanning after the first run on a repository that has code; confirm that default setup is **not** also enabled, to avoid two analyses | not applied |
| 12 | GHCR package visibility and access, after the first push | Check each `<repo>-<service>` package: visibility, and Manage Actions access for this repository (`packages: write`) | not applied |
| 13 | Collaborators | Write access for the team | not applied |
| 14 | Branches | No `dev` branch exists; no ruleset or environment refers to one | not applied |

Not in this checklist: the `production` secrets and the GHCR cleanup token (Phase 3), which belong to US-19 and US-22.
