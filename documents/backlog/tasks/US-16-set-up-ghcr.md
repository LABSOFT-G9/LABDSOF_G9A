# [US-16] Set up GHCR as the container registry

**Status:** local draft, not published to GitHub. Query GitHub for the `[US-16]` title prefix before creating the issue.

## Planning

- Epic: [EP-04 — Engineering and quality](../epics/EP-04-engineering-and-quality.md).
- Priority: Must (proposed).
- Estimate: not set; estimate at planning. Expected to be small.
- Sprint: S2, before the first image is published.
- Dependencies: package naming agreed with the stack (EN-01-01); access roles from [US-22](US-22-control-access-and-secrets.md).
- Suggested labels: `Story`, `devops`.

## Story

As a **DevOps engineer**, I want **a configured GitHub Container Registry with agreed naming, tags, permissions and retention**, so that **the pipelines can publish, and the hosts can pull, images safely and in a predictable way**.

## Scope

Apply [CI/CD strategy §6 and §10](../../devops/CICD.md): package names, tag scheme, visibility, who can push and pull, how the hosts authenticate, and the retention policy. Publishing images is done by the pipeline in [US-18](US-18-build-pipeline-to-dev.md).

Not covered: image signing and scanning (US-18); golden base images ([US-23](US-23-build-golden-base-images.md)).

## Acceptance criteria

- [ ] Package names and the tag scheme of §6 (commit SHA, version tags) are documented and applied to a first test image.
- [ ] Package visibility and permissions follow least privilege. Publishing uses `GITHUB_TOKEN` with `packages: write`; no personal token is used to publish.
- [ ] The application hosts pull images with a read-only credential stored as a secret on the host. It is not committed or printed in logs, and has a rotation procedure.
- [ ] The retention policy of §10.1 is documented: release tags, signatures and untagged child versions are never deleted by hand.
- [ ] If a cleanup workflow is added, it runs by hand in dry-run mode first, and its log is reviewed to confirm no released version would be deleted. If it needs a classic PAT, that is stored as a secret with an expiry and a rotation reminder.
- [ ] The decision on whether to stay on a personal account or move to an organization (§11.5) is reflected in the package setup.

## Verification and risks

Push a test image from a workflow, pull it from a host with the read-only credential, and confirm a pull without the credential fails for a private package. Risks: a cleanup tag filter matching release versions; a classic PAT that can delete any package its owner can reach; ownership tied to a personal account.

## Ready / Done

- [ ] Ready: estimate recorded, package names agreed, [DoR](../../governance/definition-of-ready.md) reviewed.
- [ ] Done: acceptance criteria met, settings documented with evidence, reviewed by someone other than the author.
