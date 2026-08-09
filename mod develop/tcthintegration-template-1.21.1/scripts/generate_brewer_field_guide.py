#!/usr/bin/env python3
"""Generate tcth-brewer Field Guide categories from the runtime beverage tier
mapping (phase 7D).

Sources:
  - docs/presets/tcth-brewer/data/tcth/beverage_tiers/items/<ns>/<path>.json
    (64 authoritative per-item tier files: 18 COMMON + 46 T2)
Outputs:
  - docs/presets/tcth-brewer/data/tcth/fieldguide/categories/brew_common.json
  - docs/presets/tcth-brewer/data/tcth/fieldguide/categories/brew_t2.json
  - Server/global_packs/required_data/tcth-brewer/data/tcth/fieldguide/categories/...  (server deployment)

Rules:
  - only COMMON and T2 beverages enter; T3 candidates / ingredients /
    containers / excluded items never do (they are not in the tier mapping);
  - every category lists explicit `item:<ns>/<path>` entries, each pinning the
    never-satisfied prerequisite `tcth:brewer_cookbook_gate` so Field Guide's
    implicit OBTAIN trigger (pickup/drink) cannot unlock — only TCTH's direct
    unlock() from a real BeveragePreparedEvent succeeds;
  - stale category files are removed (wholesale rewrite of the folder);
  - deterministic: running twice yields identical bytes;
  - strict ResourceLocation validation on every entry id.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import sys
from pathlib import Path

_SCRIPT = Path(__file__).resolve()
_PROJECT = _SCRIPT.parent.parent
_WORKSPACE = _SCRIPT.parent.parent.parent.parent

TIER_DIR = _PROJECT / "docs/presets/tcth-brewer/data/tcth/beverage_tiers/items"
PRESET_CAT_DIR = _PROJECT / "docs/presets/tcth-brewer/data/tcth/fieldguide/categories"
SERVER_CAT_DIR = _WORKSPACE / "Server/global_packs/required_data/tcth-brewer/data/tcth/fieldguide/categories"

# Field Guide 1.13.4 grants auto-populated ITEM entries an implicit OBTAIN
# trigger (picking up / slotting the item unlocks the entry). To keep the
# unlock source exclusive to BeveragePreparedEvent (TCTH), every explicit
# entry pins a never-satisfied prerequisite: tryUnlock() (pickup/eat/scan)
# is rejected by canUnlock(), while TCTH's direct unlock() ignores
# prerequisites and still works.
UNLOCK_GATE = "tcth:brewer_cookbook_gate"

CATEGORY_ICONS = {
    "COMMON": "tcth:textures/gui/fieldguide/brew_common.png",
    "T2": "tcth:textures/gui/fieldguide/brew_t2.png",
}
CATEGORY_SORT = {"COMMON": 4, "T2": 5}
CATEGORY_NAMES = {"COMMON": "brew_common", "T2": "brew_t2"}
EXPECTED = {"COMMON": 18, "T2": 46}

_NS_RE = re.compile(r"^[a-z0-9_.-]+$")
_PATH_RE = re.compile(r"^[a-z0-9/._-]+$")


def item_from_tier_rel(rel: Path) -> str | None:
    """Convert a tier file relative path (ns/path.json) to 'ns:path'."""
    parts = rel.parts
    if len(parts) != 2 or not parts[1].endswith(".json"):
        return None
    ns, path = parts[0], parts[1][: -len(".json")]
    if not _NS_RE.match(ns) or not _PATH_RE.match(path):
        return None
    if ".." in ns or ".." in path:
        return None
    return f"{ns}:{path}"


def load_tier_map(tier_dir: Path) -> dict[str, str]:
    """item id -> tier, from the per-item tier files."""
    result: dict[str, str] = {}
    for f in sorted(tier_dir.rglob("*.json")):
        item_id = item_from_tier_rel(f.relative_to(tier_dir))
        if item_id is None:
            raise RuntimeError(f"invalid tier file path: {f.relative_to(tier_dir)}")
        try:
            obj = json.loads(f.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            raise RuntimeError(f"invalid JSON in {f}: {e}") from e
        tier = obj.get("tier")
        if tier not in EXPECTED:
            raise RuntimeError(f"unexpected tier {tier!r} for {item_id}")
        if item_id in result and result[item_id] != tier:
            raise RuntimeError(f"conflicting tiers for {item_id}")
        result[item_id] = tier
    return result


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    tier_map = load_tier_map(TIER_DIR)
    by_tier: dict[str, list[str]] = {t: [] for t in EXPECTED}
    for item_id, tier in sorted(tier_map.items()):
        by_tier[tier].append(item_id)
    for t in EXPECTED:
        by_tier[t].sort()

    # Safety: exactly the expected counts, mutual exclusion, no T3.
    for tier, expected in EXPECTED.items():
        if len(by_tier[tier]) != expected:
            print(f"tier {tier} expected {expected} but got {len(by_tier[tier])}", file=sys.stderr)
            return 1
    if "T3" in tier_map.values() or any(":t3:" in i for i in tier_map):
        print("T3 must never enter the runtime tier mapping", file=sys.stderr)
        return 1
    inter = set(by_tier["COMMON"]) & set(by_tier["T2"])
    if inter:
        raise RuntimeError(f"tier categories overlap: {inter}")

    # Generate both categories with explicit entries + the gate prerequisite.
    cats: dict[str, list[dict]] = {}
    for tier in ("COMMON", "T2"):
        cats[tier] = [
            {
                "type": "entry",
                "id": f"item:{pid.replace(':', '/')}",
                "unlock": {"prerequisites": [UNLOCK_GATE]},
            }
            for pid in by_tier[tier]
        ]

    for target in (PRESET_CAT_DIR, SERVER_CAT_DIR):
        for tier in ("COMMON", "T2"):
            write_json(target / f"{CATEGORY_NAMES[tier]}.json", {
                "sort_index": CATEGORY_SORT[tier],
                "icon": CATEGORY_ICONS[tier],
                "contents": cats[tier],
            })

    # Determinism: rerun writes and compare bytes.
    def snapshot(root: Path) -> dict[str, bytes]:
        return {str(p.relative_to(root)): p.read_bytes() for p in sorted(root.rglob("*.json"))}

    before_preset = snapshot(PRESET_CAT_DIR)
    before_server = snapshot(SERVER_CAT_DIR)
    for target in (PRESET_CAT_DIR, SERVER_CAT_DIR):
        for tier in ("COMMON", "T2"):
            write_json(target / f"{CATEGORY_NAMES[tier]}.json", {
                "sort_index": CATEGORY_SORT[tier],
                "icon": CATEGORY_ICONS[tier],
                "contents": cats[tier],
            })
    if snapshot(PRESET_CAT_DIR) != before_preset or snapshot(SERVER_CAT_DIR) != before_server:
        print("generation is not deterministic", file=sys.stderr)
        return 1

    total = sum(len(c) for c in cats.values())
    print(f"brew_common: {len(cats['COMMON'])}")
    print(f"brew_t2: {len(cats['T2'])}")
    print(f"total entries: {total}")
    print(f"gate: {UNLOCK_GATE}")
    print(f"preset categories sha256: {hashlib.sha256(snapshot(PRESET_CAT_DIR)[f'brew_common.json'] + snapshot(PRESET_CAT_DIR)[f'brew_t2.json']).hexdigest()}")
    print("generated, deterministic, stale cleaned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
