#!/usr/bin/env python3
"""Deploy placeholder: scripts/deploy.py <env> <tag>.

Safe stub until the deploy target exists (US-17, #70/#83). It validates its
arguments, refuses `latest`, prints what a real deploy would run and exits
non-zero so that nobody mistakes it for a working deployment. It holds no
secrets and no host names.
"""
from __future__ import annotations

import re
import sys

ENVIRONMENTS = ("dev", "prod")
# sha-<sha> from main.yml, or a release version such as 1.4.0 from release.yml.
TAG = re.compile(r"^(sha-[0-9a-f]{7,40}|[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?)$")


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(f"usage: {argv[0]} <{'|'.join(ENVIRONMENTS)}> <tag>", file=sys.stderr)
        return 2
    env, tag = argv[1], argv[2]
    if env not in ENVIRONMENTS:
        print(f"error: unknown environment {env!r} (expected one of {', '.join(ENVIRONMENTS)})", file=sys.stderr)
        return 2
    if tag == "latest":
        print("error: refusing to deploy 'latest'; use sha-<sha> or a version tag", file=sys.stderr)
        return 2
    if not TAG.fullmatch(tag):
        print(f"error: tag {tag!r} is neither sha-<sha> nor a version like 1.4.0", file=sys.stderr)
        return 2

    print(f"Would deploy tag {tag} to {env} with:")
    print(f"  BMC_TAG={tag} docker compose pull && BMC_TAG={tag} docker compose up -d")
    print("error: deploy target not configured (US-17); nothing was deployed", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
