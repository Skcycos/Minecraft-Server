#!/usr/bin/env python3
"""Generate the TCTH Field Guide chef-cookbook data (tags + categories).

Canonical sources:
  - 配方与经济管理/统一配方表/食物三档分类表.csv   (tier authority)
  - Server/kubejs/config/food_recipe_export/food_recipe_outputs.csv
                                                  (edible authority)

Output (inside the tcth-chef preset):
  - data/tcth/tags/item/chef_common.json   (COMMON  tier, lowest on conflict)
  - data/tcth/tags/item/chef_t2.json       (T2      tier)
  - data/tcth/tags/item/chef_t3.json       (T3      tier)
  - data/tcth/tags/item/chef_catalog.json  (union, nested #tag references)
  - data/tcth/fieldguide/categories/chef_common.json / chef_t2.json / chef_t3.json
  - field_guide_coverage.md                (coverage report)

Rules:
  - an item must have a valid tier in the tier CSV AND be edible
    ("是否可食用=是") in the outputs CSV (intersection);
  - kaleidoscope_cookery:raw_dough and everything in #tcth:not_dishes are
    always excluded; items explicitly listed in #tcth:dishes are re-added;
  - on tier conflicts the LOWEST tier wins (simple recipes must not grant
    high-tier rewards);
  - the three tier tags are mutually exclusive; the catalog is their union.

Safety:
  - staging tree first, full JSON validation, ResourceLocation validation,
    path-traversal defence, mutual-exclusion / duplicate / raw_dough /
    non-edible checks;
  - atomic swap on success, old directory preserved on failure;
  - deterministic: two consecutive runs produce byte-identical files
    (sorted values, fixed JSON formatting);
  - stale files are removed by the full-directory swap.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import re
import shutil
import sys
from collections import Counter, defaultdict
from pathlib import Path

TIER_MAP = {"1": "COMMON", "2": "T2", "3": "T3"}
VALID_TIERS = ("COMMON", "T2", "T3")
RAW_DOUGH = "kaleidoscope_cookery:raw_dough"
CATEGORY_ICONS = {
    "COMMON": "tcth:textures/gui/fieldguide/chef_common.png",
    "T2": "tcth:textures/gui/fieldguide/chef_t2.png",
    "T3": "tcth:textures/gui/fieldguide/chef_t3.png",
}
CATEGORY_SORT = {"COMMON": 1, "T2": 2, "T3": 3}

_NS_RE = re.compile(r"^[a-z0-9_.-]+$")
_PATH_RE = re.compile(r"^[a-z0-9/._-]+$")

_SCRIPT = Path(__file__).resolve()
_WORKSPACE = _SCRIPT.parent.parent.parent.parent
_PROJECT = _SCRIPT.parent.parent

DEFAULT_TIER_CSV = _WORKSPACE / "配方与经济管理/统一配方表/食物三档分类表.csv"
DEFAULT_OUTPUTS_CSV = _WORKSPACE / "Server/kubejs/config/food_recipe_export/food_recipe_outputs.csv"
DEFAULT_PRESET = _PROJECT / "docs/presets/tcth-chef"
STAGING = _PROJECT / "build/tmp/field_guide_tags"

TIER_TAG_NAMES = {"COMMON": "chef_common", "T2": "chef_t2", "T3": "chef_t3"}
TAG_DIR = "data/tcth/tags/item"
CATEGORY_DIR = "data/tcth/fieldguide/categories"

# Field Guide 1.13.4 grants auto-populated ITEM entries an implicit OBTAIN
# trigger (picking up / slotting the item unlocks the entry). To keep the
# unlock source exclusive to DishCookedEvent (TCTH), every auto-populated
# category pins a never-satisfied prerequisite: tryUnlock() (pickup/eat/scan)
# is rejected by canUnlock(), while TCTH's direct unlock() ignores
# prerequisites and still works.
UNLOCK_GATE = "tcth:chef_cookbook_gate"


def parse_product_id(raw: str) -> tuple[str, str] | None:
    """Return (namespace, path) for a valid 'ns:path' id, else None."""
    raw = raw.strip()
    if ":" not in raw:
        return None
    ns, _, path = raw.partition(":")
    if not _NS_RE.match(ns) or not _PATH_RE.match(path):
        return None
    if ".." in ns or ".." in path:
        return None
    if ns.startswith(".") or ns.endswith(".") or path.startswith(".") or path.endswith("."):
        return None
    return ns, path


def read_tag_values(tag_json: Path) -> set[str]:
    """Read a Minecraft item tag JSON, returning the raw value strings."""
    if not tag_json.exists():
        return set()
    obj = json.loads(tag_json.read_text(encoding="utf-8"))
    if not isinstance(obj, dict) or not isinstance(obj.get("values"), list):
        raise RuntimeError(f"invalid tag file: {tag_json}")
    return {str(v) for v in obj["values"]}


def load_tier_map(csv_path: Path) -> tuple[dict[str, list[str]], list[str]]:
    """pid -> [tiers]; returns (map, malformed rows)."""
    tiers: dict[str, list[str]] = defaultdict(list)
    malformed: list[str] = []
    with open(csv_path, encoding="utf-8-sig") as f:
        rows = list(csv.reader(f))
    for r in rows[1:]:
        if len(r) < 6 or not r[5].strip():
            malformed.append(str(r[:6]))
            continue
        pid = r[5].strip()
        if parse_product_id(pid) is None:
            malformed.append(f"bad id: {pid!r}")
            continue
        tier = r[0].strip()
        if tier not in TIER_MAP:
            malformed.append(f"no tier: {pid!r} (tier={tier!r})")
            continue
        tiers[pid].append(TIER_MAP[tier])
    return dict(tiers), malformed


def load_edible(csv_path: Path) -> tuple[set[str], list[str]]:
    """Set of edible product ids; returns (set, malformed rows)."""
    edible: set[str] = set()
    malformed: list[str] = []
    with open(csv_path, encoding="utf-8-sig") as f:
        rows = list(csv.reader(f))
    for r in rows[1:]:
        if len(r) < 6 or not r[3].strip():
            continue
        pid = r[3].strip()
        if parse_product_id(pid) is None:
            malformed.append(f"bad id: {pid!r}")
            continue
        if r[5].strip() == "是":
            edible.add(pid)
    return edible, malformed


def validate_staging(staging: Path, expected_tags: int, expected_categories: int) -> None:
    files = sorted(staging.rglob("*.json"))
    if len(files) != expected_tags + expected_categories:
        raise RuntimeError(f"staging file count {len(files)} != {expected_tags + expected_categories}")
    for f in files:
        try:
            json.loads(f.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            raise RuntimeError(f"invalid JSON in {f}: {e}") from e
        rel = f.relative_to(staging)
        parts = rel.parts
        if parts[0] == "data" and parts[1] == "tcth" and parts[2] == "tags":
            ns = parts[-2]
            path = parts[-1][: -len(".json")]
            if not _NS_RE.match(ns) or not _PATH_RE.match(path):
                raise RuntimeError(f"invalid resource location in {f}")


# Files this generator owns inside tags/item and fieldguide/categories.
MANAGED_TAG_FILES = {"chef_common.json", "chef_t2.json", "chef_t3.json", "chef_catalog.json"}
MANAGED_CATEGORY_FILES = {"chef_common.json", "chef_t2.json", "chef_t3.json"}


def install_files(pairs: list[tuple[Path, Path]], stale_root: Path, managed: set[str]) -> None:
    """Install (source, target) pairs file-by-file (atomic rename each).

    Never touches unrelated files (e.g. the preset's own chef_meals.json): only
    files in the staging tree are installed, and stale-file cleanup is limited
    to the managed set. On any failure the already-installed files stay and
    untouched targets keep their previous content.
    """
    for src, _ in pairs:
        json.loads(src.read_text(encoding="utf-8"))
    installed: set[Path] = set()
    for src, dst in pairs:
        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp = dst.with_suffix(dst.suffix + ".tmp")
        shutil.copyfile(src, tmp)
        os.replace(tmp, dst)
        installed.add(dst)
    if stale_root.exists():
        keep = {d.name for d in installed if d.parent == stale_root}
        for f in list(stale_root.iterdir()):
            if f.is_file() and f.name in managed and f.name not in keep:
                f.unlink()


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def sha256_of_dir(directory: Path) -> dict[str, str]:
    return {
        str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(directory.rglob("*.json"))
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tier-csv", type=Path, default=DEFAULT_TIER_CSV)
    parser.add_argument("--outputs-csv", type=Path, default=DEFAULT_OUTPUTS_CSV)
    parser.add_argument("--preset", type=Path, default=DEFAULT_PRESET)
    parser.add_argument("--report", type=Path, default=None,
                        help="write the coverage report (markdown) to this path")
    parser.add_argument("--hash-out", type=Path, default=None,
                        help="write per-file SHA-256 (determinism check) to this path")
    args = parser.parse_args()

    preset = args.preset.resolve()
    if preset != DEFAULT_PRESET.resolve():
        print(f"preset must be {DEFAULT_PRESET}; refusing {preset}", file=sys.stderr)
        return 2

    tier_map, tier_malformed = load_tier_map(args.tier_csv)
    edible, out_malformed = load_edible(args.outputs_csv)

    # Exclusion / extension tags from the preset's own data (or the mod JAR).
    not_dishes = read_tag_values(preset / TAG_DIR / "not_dishes.json")
    not_dishes.add(RAW_DOUGH)
    dishes = read_tag_values(preset / TAG_DIR / "dishes.json")

    resolved: dict[str, str] = {}
    conflicts: list[tuple[str, list[str]]] = []
    for pid in sorted(edible):
        if pid in not_dishes:
            continue
        tiers = tier_map.get(pid)
        if not tiers:
            continue
        unique = list(dict.fromkeys(tiers))
        if len(unique) > 1:
            conflicts.append((pid, unique))
        resolved[pid] = min(unique, key=lambda t: VALID_TIERS.index(t))
    # Explicit extension: tcth:dishes re-adds items (even without a tier row).
    for pid in sorted(dishes):
        if pid in not_dishes or parse_product_id(pid) is None:
            continue
        resolved.setdefault(pid, "COMMON")

    by_tier: dict[str, list[str]] = {t: [] for t in VALID_TIERS}
    for pid, tier in resolved.items():
        by_tier[tier].append(pid)
    for t in VALID_TIERS:
        by_tier[t].sort()

    catalog = sorted(set().union(*[by_tier[t] for t in VALID_TIERS]))

    # ---- safety checks on the resolved data ----
    inter = set(by_tier["COMMON"]) & set(by_tier["T2"]) & set(by_tier["T3"])
    if inter:
        raise RuntimeError(f"tier tags are not mutually exclusive: {inter}")
    pair = (set(by_tier["COMMON"]) & set(by_tier["T2"])) | \
           (set(by_tier["T2"]) & set(by_tier["T3"])) | \
           (set(by_tier["COMMON"]) & set(by_tier["T3"]))
    if pair:
        raise RuntimeError(f"tier tags overlap: {pair}")
    if len(catalog) != len(set(catalog)):
        raise RuntimeError("catalog contains duplicates")
    if RAW_DOUGH in catalog:
        raise RuntimeError("raw_dough leaked into the catalog")
    if not_dishes & set(catalog):
        raise RuntimeError(f"excluded items leaked into the catalog: {not_dishes & set(catalog)}")
    for pid in catalog:
        if parse_product_id(pid) is None:
            raise RuntimeError(f"invalid resource location in catalog: {pid!r}")

    # ---- stage everything ----
    if STAGING.exists():
        shutil.rmtree(STAGING)
    tag_root = STAGING / TAG_DIR
    cat_root = STAGING / CATEGORY_DIR

    for tier in VALID_TIERS:
        write_json(tag_root / f"{TIER_TAG_NAMES[tier]}.json",
                   {"replace": False, "values": by_tier[tier]})
    write_json(tag_root / "chef_catalog.json",
               {"replace": False, "values": [f"#{tcth}:{TIER_TAG_NAMES[tier]}" for tcth in ("tcth",) for tier in VALID_TIERS]})

    for tier in VALID_TIERS:
        # Field Guide 1.13.4's auto_populate grants ITEM entries an implicit
        # OBTAIN trigger (pickup/slot unlocks) that NO data can disable: the
        # unlock block is registered under a synthetic id the item entries
        # never match, so getUnlockData() falls back to OBTAIN. To keep the
        # unlock source exclusive to DishCookedEvent we emit explicit entries
        # (still driven by the chef_* tags) whose unlock data IS matched, and
        # pin a never-satisfied prerequisite so tryUnlock() (pickup/eat/scan)
        # is rejected while TCTH's direct unlock() still works.
        entries = [
            {
                "type": "entry",
                "id": f"item:{pid.replace(':', '/')}",
                "unlock": {"prerequisites": [UNLOCK_GATE]},
            }
            for pid in by_tier[tier]
        ]
        write_json(cat_root / f"{TIER_TAG_NAMES[tier]}.json", {
            "sort_index": CATEGORY_SORT[tier],
            "icon": CATEGORY_ICONS[tier],
            "contents": entries,
        })

    expected_tags = 4
    expected_categories = 3
    validate_staging(STAGING, expected_tags, expected_categories)

    # File-level atomic install; unrelated preset data (dish_tiers, jobsplus,
    # arc, chef_meals tag, ...) is never touched.
    tag_root = preset / "data" / "tcth" / "tags" / "item"
    cat_root = preset / "data" / "tcth" / "fieldguide" / "categories"
    tag_pairs = [
        (STAGING / "data" / "tcth" / "tags" / "item" / n, tag_root / n)
        for n in ("chef_common.json", "chef_t2.json", "chef_t3.json", "chef_catalog.json")
    ]
    cat_pairs = [
        (STAGING / "data" / "tcth" / "fieldguide" / "categories" / n, cat_root / n)
        for n in ("chef_common.json", "chef_t2.json", "chef_t3.json")
    ]
    install_files(tag_pairs, tag_root, MANAGED_TAG_FILES)
    install_files(cat_pairs, cat_root, MANAGED_CATEGORY_FILES)

    # ---- coverage report ----
    total_rows = len(edible)
    count = Counter(resolved.values())
    lines = [
        "# TCTH Field Guide chef cookbook coverage report",
        "",
        f"- 可食用产物数 (outputs CSV): {total_rows}",
        f"- 分类表有等级的产物数: {len(tier_map)}",
        f"- 图鉴目录总数 (交集+dishes): {len(resolved)}",
        f"- COMMON: {count.get('COMMON', 0)}",
        f"- T2: {count.get('T2', 0)}",
        f"- T3: {count.get('T3', 0)}",
        f"- 等级冲突数: {len(conflicts)}（处理结果：取最低等级）",
        f"- 排除数 (not_dishes 含 raw_dough): {len(not_dishes)}",
        f"- 分类表非法/无等级行: {len(tier_malformed)}",
        f"- outputs 非法行: {len(out_malformed)}",
        f"- 生成 JSON 文件数: {expected_tags + expected_categories}",
        "",
    ]
    if conflicts:
        lines.append("## 等级冲突（已取最低等级）")
        lines.append("")
        for pid, seen in conflicts:
            lines.append(f"- `{pid}`: {seen} -> {resolved[pid]}")
        lines.append("")
    report = "\n".join(lines)

    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report, encoding="utf-8")
    if args.hash_out:
        hashes = sha256_of_dir(preset / "data" / "tcth" / "tags" / "item")
        for cat in ("chef_common", "chef_t2", "chef_t3"):
            hashes[f"categories/{cat}.json"] = hashlib.sha256(
                (preset / "data" / "tcth" / "fieldguide" / "categories" / f"{cat}.json").read_bytes()
            ).hexdigest()
        args.hash_out.parent.mkdir(parents=True, exist_ok=True)
        args.hash_out.write_text(
            "\n".join(f"{h}  {p}" for p, h in sorted(hashes.items())) + "\n",
            encoding="utf-8",
        )
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
