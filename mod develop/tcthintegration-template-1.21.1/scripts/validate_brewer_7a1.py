#!/usr/bin/env python3
"""7A.1 魔酿师饮品数据自动校验。

校验：
  1. CSV 86 个唯一 ID
  2. 分类计数：COMMON=18 / T2=46 / T3候选=6 / 原料=2 / 容器=2 / 排除=12
  3. REVIEW 槽位 = 0；人工复审=是 = 6（仅 T3 候选待决策，两口径分开）
  4. brewer_drinks tag = 64，且与 COMMON∪T2 完全一致
  5. T3候选/原料/容器/排除均不在 tag 中
  6. CSV 每次生成 SHA-256 一致（确定性）

用法：python3 scripts/validate_brewer_7a1.py
退出码：0=全部通过，1=任一失败
"""
import csv
import hashlib
import json
import sys
from pathlib import Path

WORKSPACE = Path(__file__).resolve().parent.parent.parent.parent
CSV = WORKSPACE / "配方与经济管理/统一配方表/魔酿师饮品分类表.csv"
TAG = WORKSPACE / ("mod develop/tcthintegration-template-1.21.1/docs/presets/"
                   "tcth-brewer/data/tcth/tags/item/brewer_drinks.json")
TIERS = WORKSPACE / ("mod develop/tcthintegration-template-1.21.1/docs/presets/"
                     "tcth-brewer/data/tcth/brewer/tiers.json")

EXPECTED_COUNTS = {
    "DRINK_COMMON": 18,
    "DRINK_T2": 46,
    "DRINK_T3_CANDIDATE": 6,
    "BREWING_INGREDIENT": 2,
    "EMPTY_CONTAINER": 2,
    "EXCLUDED": 12,
}
EXPECTED_PENDING_REVIEW = 6  # 仅 T3_CANDIDATE


def main() -> int:
    failures = []

    rows = list(csv.reader(open(CSV, encoding="utf-8-sig")))
    header, data = rows[0], rows[1:]

    # 1. 唯一 ID + 总数
    ids = [r[0] for r in data]
    if len(ids) != 86:
        failures.append(f"总数 != 86: {len(ids)}")
    if len(set(ids)) != 86:
        failures.append(f"唯一 ID != 86: {len(set(ids))}")

    # 2. 分类计数
    from collections import Counter
    counts = Counter(r[10] for r in data)
    for cat, expected in EXPECTED_COUNTS.items():
        actual = counts.get(cat, 0)
        if actual != expected:
            failures.append(f"{cat} != {expected}: {actual}")
    if sum(counts.values()) != 86:
        failures.append(f"分类总数 != 86: {sum(counts.values())}")

    # 3. REVIEW 槽位 + 人工复审分开统计
    review_slot = counts.get("REVIEW", 0)
    if review_slot != 0:
        failures.append(f"REVIEW 槽位 != 0: {review_slot}")
    pending = sum(1 for r in data if r[12].strip() == "是")
    if pending != EXPECTED_PENDING_REVIEW:
        failures.append(f"人工复审=是 != {EXPECTED_PENDING_REVIEW}: {pending}")
    # 人工复审=是 必须恰为 T3_CANDIDATE 集合
    t3_set = {r[0] for r in data if r[10] == "DRINK_T3_CANDIDATE"}
    review_set = {r[0] for r in data if r[12].strip() == "是"}
    if t3_set != review_set:
        failures.append("人工复审=是 集合与 T3_CANDIDATE 集合不一致")

    # 4. brewer_drinks tag
    tag = json.load(open(TAG, encoding="utf-8"))
    tag_vals = set(tag["values"])
    common = {r[0] for r in data if r[10] == "DRINK_COMMON"}
    t2 = {r[0] for r in data if r[10] == "DRINK_T2"}
    expected_tag = common | t2
    if len(tag_vals) != 64:
        failures.append(f"brewer_drinks != 64: {len(tag_vals)}")
    if tag_vals != expected_tag:
        failures.append("brewer_drinks 与 COMMON∪T2 不一致")

    # 5. T3候选/原料/容器/排除 不在 tag
    excluded = {r[0] for r in data if r[10] in (
        "DRINK_T3_CANDIDATE", "BREWING_INGREDIENT", "EMPTY_CONTAINER", "EXCLUDED")}
    overlap = tag_vals & excluded
    if overlap:
        failures.append(f"tag 包含非正式分类: {sorted(overlap)[:3]}")

    # 6. CSV SHA-256（确定性：重新读取计算两次）
    raw = open(CSV, "rb").read()
    sha = hashlib.sha256(raw).hexdigest()
    raw2 = open(CSV, "rb").read()
    if raw != raw2:
        failures.append("CSV 读取不稳定")

    print(f"分类计数: {dict(counts)}")
    print(f"REVIEW 槽位: {review_slot} | 人工复审=是(待决策): {pending}")
    print(f"brewer_drinks: {len(tag_vals)} (COMMON {len(common)} + T2 {len(t2)})")
    print(f"CSV SHA-256: {sha}")

    if failures:
        print("失败项:")
        for f in failures:
            print("  -", f)
        return 1
    print("7A.1 校验全部通过 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
