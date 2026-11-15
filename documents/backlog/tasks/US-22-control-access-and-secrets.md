# [US-22] Control repository access and manage secrets

**Status:** local draft, not published to GitHub. Query GitHub for the `[US-22]` title prefix before creating the issue.

## Planning

- Epic: [EP-04 — Engineering and quality](../epics/EP-04-engineering-and-quality.md).
- Priority: Must (proposed).
- Estimate: not set; estimate at planning.
- Sprint: S2 (rulesets, CODEOWNERS, secrets rules); S3 (environments and the single-owner decision).
- Dependencies: named tech leads and DevOps users (CI/CD §14); personal account vs. organization decision (§11.5).
- Suggested labels: `Story`, `devops`, `security`.

## Story

As a **tech lead**, I want **branch, tag and pipeline changes to need the right approvals and secrets to be stored and rotated safely**, so that **no single person can change production by accident and no credential leaks**.

## Scope

Apply [CI/CD strategy §9, §11 and §12](../../devops/CICD.md): repository settings checklist, rulesets, CODEOWNERS for `.github/workflows`, Dockerfiles and infrastructure, environment secrets and reviewers. The `main-quality` ruleset is a proposal and is not confirmed as applied.

## Acceptance criteria

- [ ] The settings checklist (§12) is completed with evidence for each item; non-applicable items are recorded as such.
- [ ] CODEOWNERS requires DevOps approval for pipeline, image and infrastructure paths.
- [ ] Secrets are stored only as GitHub secrets scoped to the environment that needs them; a full-history gitleaks scan passes.
- [ ] The rules of §9.1 and a leaked-credential rotation procedure are documented in the onboarding notes.
- [ ] Access roles (§11.1–11.2) are assigned to named members and the limits of a personal account are documented.
- [ ] The decision to stay on a personal repository or move to an organization is recorded.

## Verification and risks

Try to push to `main`, edit a workflow without approval and read a production secret from a fork PR, expecting rejection. Risks: the single owner can bypass rules; documentation treated as "active" without evidence; secrets reused across environments.

## Ready / Done

- [ ] Ready: estimate recorded, named users available, [DoR](../../governance/definition-of-ready.md) reviewed.
- [ ] Done: acceptance criteria met, evidence attached, reviewed by someone other than the author.
