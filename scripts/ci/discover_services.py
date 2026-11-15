#!/usr/bin/env python3
"""Discover deployable services by the presence of a Dockerfile.

Layout contract (until EN-02-02, #78, fixes one): every service has its own
Dockerfile. The Dockerfile's directory is the build context and its name is the
service name (services/issue/Dockerfile -> "issue"). The language is inferred
from the files next to the Dockerfile:

    pom.xml                              -> maven
    pyproject.toml / requirements*.txt   -> python
    package.json                         -> node

Directories documents/, workflow-scripts/, node_modules/, target/ and .git/ are
ignored: diagram and metrics helpers are not services.

Writes key=value lines to $GITHUB_OUTPUT (stdout when unset):
    services      JSON array of {name, dir, dockerfile, lang}
    has_services  true|false
    codeql        JSON array of CodeQL languages present
    has_codeql    true|false
    has_proto     true|false (contracts/proto exists)
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

IGNORED = {"documents", "workflow-scripts", "node_modules", "target", ".git"}


def walk(root: Path):
    """Yield (relative dir, filenames), pruning ignored directories."""
    for current, dirs, files in os.walk(root):
        dirs[:] = sorted(d for d in dirs if d not in IGNORED)
        yield Path(current).relative_to(root), files


def detect_lang(root: Path, rel: Path, files: list[str]) -> str:
    if "pom.xml" in files:
        return "maven"
    if "pyproject.toml" in files or any(re.fullmatch(r"requirements.*\.txt", f) for f in files):
        return "python"
    if "package.json" in files:
        return "node"
    return "unknown"


def sanitize(name: str) -> str:
    # Image names must be lowercase and use only [a-z0-9._-].
    return re.sub(r"[^a-z0-9._-]", "-", name.lower())


def discover(root: Path) -> dict[str, str]:
    services: list[dict[str, str]] = []
    codeql: list[str] = []

    def add(lang: str) -> None:
        if lang not in codeql:
            codeql.append(lang)

    for rel, files in walk(root):
        if "Dockerfile" in files:
            name = sanitize((rel.name or root.resolve().name))
            services.append(
                {
                    "name": name,
                    "dir": rel.as_posix(),
                    "dockerfile": (rel / "Dockerfile").as_posix(),
                    "lang": detect_lang(root, rel, files),
                }
            )
        if "pom.xml" in files:
            add("java-kotlin")
        if any(f.endswith(".py") for f in files):
            add("python")
        if "package.json" in files:
            add("javascript-typescript")

    names = [s["name"] for s in services]
    dups = sorted({n for n in names if names.count(n) > 1})
    if dups:
        sys.exit(f"::error::Duplicate service names (directory names must be unique): {', '.join(dups)}")

    return {
        "services": json.dumps(services, separators=(",", ":")),
        "has_services": str(bool(services)).lower(),
        "codeql": json.dumps(codeql, separators=(",", ":")),
        "has_codeql": str(bool(codeql)).lower(),
        "has_proto": str((root / "contracts" / "proto").is_dir()).lower(),
    }


def main(argv: list[str]) -> int:
    root = Path(argv[1] if len(argv) > 1 else ".")
    lines = "".join(f"{k}={v}\n" for k, v in discover(root).items())
    target = os.environ.get("GITHUB_OUTPUT")
    if target:
        with open(target, "a", encoding="utf-8") as out:
            out.write(lines)
    else:
        sys.stdout.write(lines)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
