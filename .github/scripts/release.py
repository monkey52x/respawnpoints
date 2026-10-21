#!/usr/bin/env python3
"""
Release helper for Minecraft mod publishing.
Reads release.toml, release-versions.toml, and GitHub release assets to build
publish matrix for mc-publish action.
"""

import argparse
import json
import os
import re
import sys
import tomllib
from pathlib import Path
from typing import Any

# ──────────────────────────────────────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────────────────────────────────────

def load_toml(path: Path) -> dict:
    return tomllib.loads(path.read_text(encoding="utf-8"))

def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))

def write_json(path: Path, data: Any) -> None:
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")

def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, text=True, capture_output=True, **kw)

def gh(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return run(["gh"] + cmd, **kw)

# ──────────────────────────────────────────────────────────────────────────────
# Commands
# ──────────────────────────────────────────────────────────────────────────────

def cmd_matrix(args: argparse.Namespace) -> None:
    """Build publish matrix from release assets and version table."""
    assets = load_json(Path(args.assets))
    versions = load_toml(Path(args.versions))

    # Parse tag like "v1.0.9" -> "1.0.9"
    tag = args.tag.lstrip("v")
    mod_version = tag

    # Build version info from release-versions.toml
    # Expected structure:
    # [versions.fabric]
    # "1.21.1" = { java_version = "21", minecraft_version_range_fabric = "1.21.1" }
    # [versions.neoforge]
    # "1.21.1" = { java_version = "21", minecraft_version_range_forge = "1.21.1" }

    mc_versions = {}
    for loader in ("fabric", "neoforge"):
        for mc, data in versions.get(loader, {}).items():
            mc_versions.setdefault(mc, {})[loader] = data

    # Parse assets from release
    assets = []
    for asset in load_json(Path("assets.json")).get("assets", []):
        name = asset["name"]
        if name.endswith(".jar") and not any(x in name for x in ("-sources.jar", "-javadoc.jar", "-dev.jar")):
            assets.append(name)

    if args.only != "*":
        pattern = re.compile(args.only.replace("*", ".*"))
        assets = [a for a in assets if re.fullmatch(pattern, a)]

    # Build matrix entries
    matrix = []
    for jar_name in assets:
        # Parse filename: respawnpoints-fabric-1.21.1-1.0.9.jar
        # or respawnpoints-neoforge-1.21.1-1.0.9.jar
        m = re.match(r"respawnpoints-(fabric|neoforge)-(.+)-(.+)\.jar$", Path(jar_name).name)
        if not m:
            print(f"Skip unknown jar: {jar_name}", file=sys.stderr)
            continue

        loader, mc, mod_version = m.groups()
        loader_name = m.group(1)

        # Get version info from table
        ver_info = mc_versions.get(mc, {}).get(loader, {})
        java_version = ver_info.get("java_version", "21")
        game_version = ver_info.get(f"minecraft_version_range_{'forge' if loader == 'neoforge' else 'fabric'}", "1.21.1")

        # Build extra info for mc-publish
        extra = []
        if args.modrinth:
            extra.append("--modrinth")

        entry = {
            "file": f"respawnpoints-{loader}-{mc}-{mod_version}.jar",
            "extra": ["--modrinth"] if True else [],  # always modrinth for now
        }
        matrix.append(entry)

    print(json.dumps({"matrix": matrix, "version_type": "release"}))
    sys.exit(0)


def cmd_meta(args: argparse.Namespace) -> None:
    """Resolve metadata for a single jar file."""
    # This is called per-job in publish.yml matrix
    # Args: FILE, EXTRA, --dir, --versions, --version-type
    pass  # Simplified - mc-publish handles most metadata


def cmd_collect(args: argparse.Namespace) -> None:
    """Collect built jars for release attachment."""
    pass


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_matrix = sub.add_parser("matrix")
    p_matrix.add_argument("--assets", required=True)
    p_matrix.add_argument("--versions", required=True)
    p_matrix.add_argument("--tag", required=True)
    p_matrix.add_argument("--only", default="*")

    sub.add_parser("meta").add_argument("file").add_argument("extra").add_argument("--dir").add_argument("--versions").add_argument("--version-type")
    sub.add_parser("collect").add_argument("--dir")

    args = parser.parse_args()

    if args.cmd == "matrix":
        cmd_matrix(args)
    elif args.cmd == "meta":
        cmd_meta(args)
    elif args.cmd == "collect":
        cmd_collect(args)
    else:
        parser.error(f"Unknown command: {args.cmd}")


if __name__ == "__main__":
    main()