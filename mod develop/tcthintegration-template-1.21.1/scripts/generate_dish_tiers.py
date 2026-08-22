#!/usr/bin/env python3
"""Generate tcth dish-tier item mappings from the canonical CSV.

Canonical source: 配方与经济管理/统一配方表/食物三档分类表.csv
Output: docs/presets/tcth-chef/data/tcth/dish_tiers/items

Rules:
  - tier 1 -> COMMON, 2 -> T2, 3 -> T3
  - one JSON file per unique product id
  - when the same product id appears with multiple tiers, the LOWEST tier
    wins (never silently overwrite; reported in the coverage report)
  - kaleidoscope_cookery:raw_dough is always excluded
  - rows without a tier (non-dish tools/decoration) are not mapped
  - malformed rows are isolated and reported
  - ResourceLocations are validated: namespace [a-z0-9_.-], path [a-z0-9/._-]

Stale-file safety:
  - a complete staging tree is written under <project>/build/tmp
  - the staging tree is validated (file count, JSON parse, tier, ids)
  - only then is the existing items dir replaced atomically (old dir kept as
    .stale until the swap succeeds; never cleared on parse/generation failure)

Deterministic: running twice produces identical output and removes stale files.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import shutil
import sys
from collections import Counter, defaultdict
from pathlib import Path

TIER_MAP = {"1": "COMMON", "2": "T2", "3": "T3"}
EXCLUDE = {
    "kaleidoscope_cookery:raw_dough",
    # TCTH chef economy exclusion: keep the food/recipe data authoritative,
    # but do not generate a dish tier and therefore no chef job XP mapping.
    "farmersdelight:cooked_chicken_cuts",
}
VALID_TIERS = ("COMMON", "T2", "T3")

# Minecraft ResourceLocation rules: lowercase namespace [a-z0-9_.-],
# path [a-z0-9/._-] (paths may contain '/').
_NS_RE = re.compile(r"^[a-z0-9_.-]+$")
_PATH_RE = re.compile(r"^[a-z0-9/._-]+$")

# Script lives at <workspace>/mod develop/<project>/scripts/; the canonical CSV
# sits at the workspace root and the output inside the project docs.
_SCRIPT = Path(__file__).resolve()
_WORKSPACE = _SCRIPT.parent.parent.parent.parent
_PROJECT = _SCRIPT.parent.parent

DEFAULT_CSV = _WORKSPACE / "配方与经济管理/统一配方表/食物三档分类表.csv"
DEFAULT_OUT = _PROJECT / "docs/presets/tcth-chef/data/tcth/dish_tiers/items"
STAGING = _PROJECT / "build/tmp/dish_tiers_items"


def parse_product_id(raw: str) -> tuple[str, str] | None:
    """Return (namespace, path) for a valid 'ns:path' id, else None."""
    raw = raw.strip()
    if ":" not in raw:
        return None
    ns, _, path = raw.partition(":")
    if not _NS_RE.match(ns) or not _PATH_RE.match(path):
        return None
    # Path-traversal defence: reject dot-dot and leading/trailing dots.
    if ".." in ns or ".." in path:
        return None
    if ns.startswith(".") or ns.endswith(".") or path.startswith(".") or path.endswith("."):
        return None
    return ns, path


def validate_staging(staging: Path, expected: int) -> None:
    """Validate the staging tree; raise on any problem."""
    files = sorted(staging.rglob("*.json"))
    if len(files) != expected:
        raise RuntimeError(f"staging file count {len(files)} != expected {expected}")
    for f in files:
        try:
            obj = json.loads(f.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            raise RuntimeError(f"invalid JSON in {f}: {e}") from e
        if obj.get("tier") not in VALID_TIERS:
            raise RuntimeError(f"invalid tier in {f}: {obj!r}")
        rel = f.relative_to(staging)
        ns, path = rel.parts[0], "/".join(rel.parts[1:])[:-len(".json")]
        if not _NS_RE.match(ns) or not _PATH_RE.match(path):
            raise RuntimeError(f"invalid resource location in {f}")


def swap_atomic(target: Path) -> None:
    """Replace target dir with the validated staging dir, keeping a .stale copy."""
    if not target.exists():
        os.replace(STAGING, target)
        return
    stale = target.parent / (target.name + ".stale")
    if stale.exists():
        shutil.rmtree(stale)
    os.replace(target, stale)
    try:
        os.replace(STAGING, target)
    except Exception:
        # roll back
        os.replace(stale, target)
        raise
    shutil.rmtree(stale)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--report", type=Path, default=None,
                        help="write the coverage report (markdown) to this path")
    args = parser.parse_args()

    # Output directory is pinned to the preset items dir.
    if args.out.resolve() != DEFAULT_OUT.resolve():
        print(f"output must be {DEFAULT_OUT}; refusing {args.out}", file=sys.stderr)
        return 2

    with open(args.csv, encoding="utf-8-sig") as f:
        rows = list(csv.reader(f))
    data = rows[1:]

    total = len(data)
    tiers: dict[str, list[str]] = defaultdict(list)
    malformed: list[str] = []
    unmapped: list[str] = []

    for r in data:
        if len(r) < 6 or not r[5].strip():
            malformed.append(str(r[:6]))
            continue
        pid = r[5].strip()
        if parse_product_id(pid) is None:
            malformed.append(f"bad id: {pid!r}")
            continue
        if pid in EXCLUDE:
            continue
        tier = r[0].strip()
        if tier not in TIER_MAP:
            unmapped.append(f"{pid} (tier={tier!r})")
            continue
        tiers[pid].append(TIER_MAP[tier])

    resolved: dict[str, str] = {}
    conflicts: list[tuple[str, list[str]]] = []
    for pid, seen in tiers.items():
        unique = list(dict.fromkeys(seen))
        if len(unique) > 1:
            conflicts.append((pid, unique))
        resolved[pid] = min(unique, key=lambda t: VALID_TIERS.index(t))

    # ---- stage into build/tmp ----
    if STAGING.exists():
        shutil.rmtree(STAGING)
    STAGING.mkdir(parents=True)
    for pid, tier in sorted(resolved.items()):
        ns, path = parse_product_id(pid)
        target = STAGING / ns / f"{path}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps({"tier": tier}, ensure_ascii=False) + "\n", encoding="utf-8")

    # ---- validate then atomically swap ----
    validate_staging(STAGING, len(resolved))
    swap_atomic(args.out)
    written = len(resolved)

    count = Counter(resolved.values())
    lines = [
        "# TCTH dish tier coverage report",
        "",
        f"- CSV 数据行总数: {total}",
        f"- 合法产物数: {len(tiers)}",
        f"- 唯一产物数: {len(tiers)}",
        f"- COMMON (等级1): {count.get('COMMON', 0)}",
        f"- T2 (等级2): {count.get('T2', 0)}",
        f"- T3 (等级3): {count.get('T3', 0)}",
        f"- 重复产物数: {sum(1 for p in tiers if len(tiers[p]) > 1)}",
        f"- 等级冲突数: {len(conflicts)}（处理结果：取最低等级）",
        f"- 排除数 (raw_dough): {len(EXCLUDE)}",
        f"- 未映射数（无等级/非料理）: {len(unmapped)}",
        f"- 非法行数: {len(malformed)}",
        f"- 生成 JSON 文件数: {written}",
        "",
    ]
    if conflicts:
        lines.append("## 等级冲突（已取最低等级）")
        lines.append("")
        for pid, seen in conflicts:
            lines.append(f"- `{pid}`: {seen} -> {resolved[pid]}")
        lines.append("")
    lines.append("## 未映射（非料理物品，不生成映射）")
    lines.append("")
    for u in unmapped:
        lines.append(f"- `{u}`")
    lines.append("")
    lines.append("## 非法行")
    lines.append("")
    for m in malformed:
        lines.append(f"- `{m}`")
    lines.append("")

    report = "\n".join(lines)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report, encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
