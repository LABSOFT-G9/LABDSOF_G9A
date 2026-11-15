# [US-18] Build the pipeline to dev

**Status:** local draft, not published to GitHub. Query GitHub for the `[US-18]` title prefix before creating the issue.

## Planning

- Epic: [EP-04 — Engineering and quality](../epics/EP-04-engineering-and-quality.md).
- Priority: Must (proposed).
- Estimate: not set; estimate at planning. Likely large; if it does not fit one sprint, split it into PR checks, image publishing and dev deployment.
- Sprint: S2, once the first build and tests exist (CI/CD rollout phase 2).
- Dependencies: a buildable service with at least one test (EN-02 is excluded from Sprint 1 by DEC-11); [US-16](US-16-set-up-ghcr.md); [US-17](US-17-set-up-aws-infrastructure.md) dev environment; Dockerfiles for the services.
- Suggested labels: `Story`, `devops`, `security`.

## Story

As a **developer**, I want **every pull request to be built, tested and scanned, and every merge to be published as a signed image and deployed to dev**, so that **broken or unsafe changes are caught before merge and integration problems are found early**.

## Scope

Implement `ci.yml` (pull requests) and `main.yml` (push to `main`) from [CI/CD strategy §3–§6 and §8](../../devops/CICD.md).

- Pull request: build, unit tests, gitleaks (secrets), CodeQL (SAST), hadolint (Dockerfiles). Dependabot alerts stay advisory.
- Push to `main`: build the image per service, Trivy scan with SBOM, cosign keyless signing, push to GHCR, deploy to dev, run the OWASP ZAP baseline scan.

Not covered: production release ([US-19](US-19-build-pipeline-to-prod.md)); hardened base images ([US-23](US-23-build-golden-base-images.md)). Until US-23, base images are official images pinned by digest.

## Acceptance criteria

- [ ] A pull request to `main` triggers build, tests, gitleaks, CodeQL and hadolint, and the result is visible on the PR.
- [ ] These checks are required by the `main-quality` ruleset; a PR with a failing check cannot be merged. Evidence is a screenshot or API output of the ruleset.
- [ ] A deliberately planted fake secret on a test branch fails gitleaks, and a failing unit test blocks the merge. The fake value is synthetic and never a real credential.
- [ ] A push to `main` builds an image per service and pushes it to GHCR with the tags from US-16, including the commit SHA.
- [ ] Trivy scans the final image and publishes an SBOM; the agreed severity threshold fails the build.
- [ ] Each image is signed with cosign via GitHub OIDC, and the signature can be verified with a documented command.
- [ ] Deployment to `dev` uses the `dev` GitHub environment, pulls only signed images and fails if the signature is missing or invalid. The deploy log shows the image tag and SHA.
- [ ] A smoke check (readiness endpoints) runs after deployment; a failure marks the run as failed and names the failing service.
- [ ] ZAP baseline scan runs against the team's dev environment after each deploy, never against third-party systems. Findings are stored as an artefact and high-risk findings are triaged or accepted with a written reason.
- [ ] Workflows use least-privilege `permissions:` and pinned third-party actions; images, logs and artefacts contain no secrets. Base images are pinned by digest and tool versions are fixed (§4 step 1).
- [ ] The decision on whether dev deploys on every merge or on demand (§14) is recorded.

## Verification and risks

Run test PRs (clean, failing test, fake secret). Merge a trivial change and follow it to dev. Deploy an unsigned image and expect rejection. Build from a known-vulnerable base on a test branch and expect the scan to fail. Risks: required checks added before a stable build block all merges (hence phase 2); a bad merge breaks the shared dev environment; flaky or slow CI; scan noise; ZAP false positives; `scripts/deploy.sh` is still a placeholder.

## Ready / Done

- [ ] Ready: estimate recorded, stack and test runner chosen, GHCR and dev infrastructure available, [DoR](../../governance/definition-of-ready.md) reviewed.
- [ ] Done: acceptance criteria met, workflows reviewed by DevOps and another member, `scripts/deploy.sh` no longer a placeholder, signature-verification command documented.
