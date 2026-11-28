# Essential Git workflow

**Status:** proposal v0.1 · remote settings tracked in [pipeline-settings-checklist.md](../devops/pipeline-settings-checklist.md)

Git, PRs, reviews and traceability are part of §8.1 of the assignment. Having a standalone document is an organisational choice; the practices are not optional.

## Proposed workflow

1. Create/refine a work item and keep a stable ID (e.g. US-01).
2. Update the main branch and create a short-lived branch: feature/US-01-reporting, fix/BUG-12-state or docs/DISC-02-competitors.
3. Make small, purposeful commits: “US-01: validate building membership”.
4. Open a PR with the objective, item reference, changes, verification and limitations.
5. Obtain at least one review from another member; authors do not approve their own changes. GitHub enforces this on `main`: the most recent push must be approved by someone other than its pusher.
6. Merge only when applicable checks pass and relevant comments are resolved. Only the `maintainers` team (Martim, Ricardo) and admins can merge into `main`; developers open, review and approve.
7. Prefer squashing working commits; preserve authorship/co-authorship and the item reference. Delete merged branches when safe.

The default branch is `main`; the rulesets target the default branch. Do not force-push shared branches or rewrite published history without coordination.

## Remote configuration

- Repository `LABSOFT-G9/LABDSOF_G9A`; teams `maintainers` (Maintain: Martim, Ricardo) and `developers` (Write: Pedro, Afonso, Sandro); Martim is admin.
- Default branch `main`.
- Rulesets: `main-review` (PR, 1 approval by someone other than the last pusher, conversations resolved, no force push or deletion), `main-maintainers-only` (only maintainers and admins merge) and `tags-maintainers-only` (only maintainers and admins create, move or delete tags). Admins can bypass, for emergencies only.
- Required check `ci-required` once the pipeline is on `main`.
- Custom repository roles are not available on the Free plan; project roles map to teams with built-in roles ([CICD.md §11.2](../devops/CICD.md#112-roles-in-the-organization)).

Which of these are applied is tracked in the [settings checklist](../devops/pipeline-settings-checklist.md).

## Conventions

- PRs reference the local ID and issue URL. Use “Closes #N” only when a real GitHub issue exists and the PR completes it.
- Documentation accompanies scope/contract changes in the same PR.
- Resolve conflicts by reviewing both sides' behaviour and repeating affected checks.
- The pull-request checks (`pr.yml`) run only when the base branch is `main`. A pull request stacked on another branch gets none: its author and its reviewer run the affected checks locally and say which in the PR. Once it is retargeted to `main`, the checks run from the next push.
- Never commit tokens, environment files with secrets, recordings or identifiable participant notes.
- Research may commit anonymous answers after a check before the first commit and peer review before merge. Exclude identity mappings, contact lists, consent attachments and identifying context/metadata. Follow the [research handling rules](../09-security-privacy.md#41-research-data-handling-disc-01-01).
- Do not bypass security controls for a demonstration.
- A demonstrable release has a tag and notes describing content, limitations and instructions; suggested names: v0.1.0-skeleton and v0.2.0-mvp, only when they exist.
- Preferred recovery for a merged change: revert through a PR; data recovery is documented separately.

Template available at [.github/pull_request_template.md](../../.github/pull_request_template.md). See [DoD](definition-of-done.md).
