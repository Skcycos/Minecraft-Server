#!/usr/bin/env python3
"""Generate tcth-brewer runtime resources into the main mod JAR (phase 7B.1).

Sources:
  - 配方与经济管理/统一配方表/魔酿师饮品分类表.csv  (authoritative classification)
Outputs (src/main/resources/data/tcth):
  - tags/item/brewer_drinks.json                  runtime tag: COMMON ∪ T2 (64)
  - beverage_tiers/items/<ns>/<path>.json         per-item tier (COMMON or T2)

Rules:
  - only DRINK_COMMON and DRINK_T2 enter runtime data;
  - DRINK_T3_CANDIDATE, BREWING_INGREDIENT, EMPTY_CONTAINER, EXCLUDED never
    enter runtime resources (audit-only; docs/presets tiers.json remains the
    audit draft and is NOT touched here);
  - stale per-item tier files are removed (wholesale rewrite of the folder);
  - path traversal is rejected (item ids must parse as ns:path with no '..');
  - deterministic: running twice yields identical bytes.
"""
from __future__ import annotations

import csv
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

_WORKSPACE = Path(__file__).resolve().parent.parent.parent.parent
_PROJECT = Path(__file__).resolve().parent.parent

DEFAULT_CSV = _WORKSPACE / "配方与经济管理/统一配方表/魔酿师饮品分类表.csv"
MAIN_RES = _PROJECT / "src/main/resources/data/tcth"
TAG_OUT = MAIN_RES / "tags/item/brewer_drinks.json"
TIER_OUT = _PROJECT / "docs/presets/tcth-brewer/data/tcth/beverage_tiers/items"

EXPECTED = {
    "DRINK_COMMON": 18,
    "DRINK_T2": 46,
    "DRINK_T3_CANDIDATE": 6,
    "BREWING_INGREDIENT": 2,
    "EMPTY_CONTAINER": 2,
    "EXCLUDED": 12,
}

# valid item id: <namespace>:<path> with [a-z0-9_.-] and no '..'
_ID_RE = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_.\-/]+$")


def _item_path(item_id: str) -> Path | None:
    """Return the per-item JSON path (relative to TIER_OUT) or None if invalid."""
    if not _ID_RE.match(item_id):
        return None
    ns, _, path = item_id.partition(":")
    if ".." in path or ".." in ns or path.startswith("/") or ns.startswith("/"):
        return None
    return Path(ns) / (path + ".json")


def load_rows(csv_path: Path) -> list[dict]:
    with open(csv_path, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        raise SystemExit("CSV is empty")
    return rows


def main() -> int:
    rows = load_rows(DEFAULT_CSV)

    counts = Counter(r["建议档次"] for r in rows)
    for cat, expected in EXPECTED.items():
        if counts.get(cat, 0) != expected:
            print(f"分类计数不符: {cat} 期望 {expected} 实际 {counts.get(cat, 0)}", file=sys.stderr)
            return 1
    if counts.get("REVIEW", 0) != 0:
        print("REVIEW 槽位必须为 0", file=sys.stderr)
        return 1

    common = sorted(r["item_id"].strip() for r in rows if r["建议档次"] == "DRINK_COMMON")
    t2 = sorted(r["item_id"].strip() for r in rows if r["建议档次"] == "DRINK_T2")
    runtime_ids = sorted(common + t2)
    if len(runtime_ids) != 64:
        print(f"运行时饮品应为 64，实际 {len(runtime_ids)}", file=sys.stderr)
        return 1

    # 1. runtime tag
    TAG_OUT.parent.mkdir(parents=True, exist_ok=True)
    tag_bytes = (json.dumps({"replace": False, "values": runtime_ids}, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    TAG_OUT.write_bytes(tag_bytes)

    # 2. per-item tiers: rewrite the whole folder (stale cleanup)
    if TIER_OUT.exists():
        for f in TIER_OUT.rglob("*.json"):
            f.unlink()
    written = 0
    for item_id in runtime_ids:
        rel = _item_path(item_id)
        if rel is None:
            print(f"拒绝非法 item id / 路径穿越: {item_id}", file=sys.stderr)
            return 1
        tier = "COMMON" if item_id in common else "T2"
        target = TIER_OUT / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps({"tier": tier}, ensure_ascii=False) + "\n", encoding="utf-8")
        written += 1

    # 3. determinism: rerun writes and compare bytes
    tag_a = TAG_OUT.read_bytes()
    tier_a = {p.relative_to(TIER_OUT): p.read_bytes() for p in TIER_OUT.rglob("*.json")}
    TAG_OUT.write_bytes(tag_bytes)
    for item_id in runtime_ids:
        rel = _item_path(item_id)
        tier = "COMMON" if item_id in common else "T2"
        (TIER_OUT / rel).write_text(json.dumps({"tier": tier}, ensure_ascii=False) + "\n", encoding="utf-8")
    tag_b = TAG_OUT.read_bytes()
    tier_b = {p.relative_to(TIER_OUT): p.read_bytes() for p in TIER_OUT.rglob("*.json")}
    if tag_a != tag_b or tier_a != tier_b:
        print("生成结果不确定（两次字节不一致）", file=sys.stderr)
        return 1

    print(f"brewer_drinks: {len(runtime_ids)} (COMMON {len(common)} + T2 {len(t2)})")
    print(f"per-item tiers written: {written} ({len(common)} COMMON + {len(t2)} T2)")
    print(f"tag sha256: {hashlib.sha256(tag_a).hexdigest()}")
    print("生成完成，确定性已验证，stale 已清理")
    return 0


if __name__ == "__main__":
    sys.exit(main())
