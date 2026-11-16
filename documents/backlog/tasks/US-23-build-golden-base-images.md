# [US-23] Build and maintain golden base images

**Status:** local draft, not published to GitHub. Query GitHub for the `[US-23]` title prefix before creating the issue.

## Planning

- Epic: [EP-04 — Engineering and quality](../epics/EP-04-engineering-and-quality.md).
- Priority: Should (proposed). CI/CD §4 marks this as phase 3, "not needed until the app runs and we have more than one base image".
- Estimate: not set; estimate at planning.
- Sprint: S3.
- Dependencies: [US-16](US-16-set-up-ghcr.md), [US-18](US-18-build-pipeline-to-dev.md) (image build, scan and signing working with digest-pinned official images); the base-image decision in CI/CD §14; the languages and runtimes chosen in EN-01-01.
- Suggested labels: `Story`, `devops`, `security`.

## Story

As a **DevOps engineer**, I want **our own hardened, signed base images that every service builds from**, so that **all services share a minimal, scanned and traceable foundation and base-image updates happen in one place**.

## Scope

Implement [CI/CD strategy §4](../../devops/CICD.md) in its own repository with its own pipeline: one golden image per runtime and build tooling actually used (for example runtime and build variants). Migrate service Dockerfiles to build `FROM` a golden image pinned by digest, one service at a time. Registry retention is handled in [US-16](US-16-set-up-ghcr.md).

Not covered: the basic image pipeline for services ([US-18](US-18-build-pipeline-to-dev.md)); images for languages the team does not use.

## Acceptance criteria

- [ ] The golden-image repository exists with its own pipeline and release cadence, and at least one image per runtime in use.
- [ ] Each image meets the §4 definition: minimal upstream, non-root by default, no shell or package manager in runtime variants, only required packages.
- [ ] Each image is scanned with zero unwaived critical or high CVEs; waivers are written, reasoned and reviewed. An SBOM is attached.
- [ ] Each image is signed with cosign, published to GHCR and consumed by digest.
- [ ] Images are rebuilt on a schedule (for example weekly) and when the upstream image or a critical CVE changes.
- [ ] Renovate or Dependabot opens digest-bump PRs in the service repositories, and these PRs go through the normal pipeline.
- [ ] At least one service is migrated to a golden image with a PR; the remaining services follow one at a time and a Dockerfile building from Docker Hub directly is flagged by CI.

## Verification and risks

Build an image with a known-vulnerable package on a test branch and expect the scan to fail; verify signature and SBOM; check that a service image built from the golden image runs as non-root. Risks: effort out of proportion to the academic project (this story may be dropped or reduced to digest-pinned official images if capacity is short); stale golden images if the schedule fails; no-shell runtime images that are harder to debug.

## Ready / Done

- [ ] Ready: estimate recorded, base-image decision recorded, runtimes known, [DoR](../../governance/definition-of-ready.md) reviewed.
- [ ] Done: acceptance criteria met, at least one service running on a golden image in dev, workflow reviewed.
