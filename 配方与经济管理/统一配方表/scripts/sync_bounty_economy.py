#!/usr/bin/env python3
"""同步「食韵筑家III」悬赏经济配置。

该脚本只管理本服务器的自定义食物价格、职业法令、金币奖励和
KubeJS Tooltip 注册表。配方来源仍以「食物配方导出 CSV」为准。
"""

from __future__ import annotations

import csv
import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
POOL_DIR = ROOT / "Server/config/bountiful/bounty_pools"
DECREE_DIR = ROOT / "Server/config/bountiful/bounty_decrees"
TABLE_DIR = ROOT / "配方与经济管理/统一配方表"
REGISTRY_PATH = ROOT / "Server/kubejs/startup_scripts/bounty_food_registry.js"


# 多产出配方按「整份成本 ÷ 产出数量」计价；稀有材料使用实际成本锚点。
PRICE_OVERRIDES = {
    "kaleidoscope_cookery:cold_cut_ham_slices": 90,
    "farmersdelight:cake_slice": 8,
    "farmersdelight:chocolate_pie_slice": 10,
    "farmersdelight:sweet_berry_cheesecake_slice": 10,
    "farmersdelight:apple_pie_slice": 9,
    "farmersdelight:pumpkin_pie_slice": 9,
    "farmersdelight:pumpkin_slice": 5,
    "farmersdelight:cooked_chicken_cuts": 5,
    "kaleidoscope_cookery:cooked_cut_small_meats": 5,
    "kaleidoscope_cookery:cooked_cow_offal": 7,
    "kaleidoscope_cookery:cooked_pork_belly": 10,
    "kaleidoscope_cookery:cooked_lamb_chops": 10,
    "farmersdelight:cooked_salmon_slice": 5,
    "farmersdelight:cooked_cod_slice": 5,
    "farmersdelight:kelp_roll_slice": 5,
    "kaleidoscope_cookery:sweet_and_sour_ender_pearls": 61,
    "kaleidoscope_cookery:watermelon_platter": 12,
    "farmersdelight:fruit_salad": 70,
    "farmersdelight:pumpkin_soup": 85,
    "farmersdelight:roasted_mutton_chops": 91,
    # 金制食材的材料价下限；不再额外叠加 T3×2.45，避免变成金币印钞机。
    "kaleidoscope_cookery:golden_salad": 524,
}

# 原料成本为已按产出数量分摊到单个产物的成本。
# 值依次为：原料成本、难度系数、增益系数、通用池系数、计算说明。
PRICE_DETAILS = {
    "kaleidoscope_cookery:cold_cut_ham_slices": (82, 1.15, 1.0, 0.95, "(8×熟五花肉10+碗2) × 1.15 × 0.95"),
    "farmersdelight:cake_slice": (40 / 7, 1.15, 1.15, 0.95, "蛋糕40 ÷ 7片 × 1.15 × 1.15 × 0.95"),
    "farmersdelight:chocolate_pie_slice": (35 / 4, 1.15, 1.0, 0.95, "巧克力派35 ÷ 4片 × 1.15 × 0.95"),
    "farmersdelight:sweet_berry_cheesecake_slice": (35 / 4, 1.15, 1.0, 0.95, "甜浆果芝士派35 ÷ 4片 × 1.15 × 0.95"),
    "farmersdelight:apple_pie_slice": (32 / 4, 1.15, 1.0, 0.95, "苹果派32 ÷ 4片 × 1.15 × 0.95"),
    "farmersdelight:pumpkin_pie_slice": (30 / 4, 1.15, 1.0, 0.95, "南瓜派30 ÷ 4片 × 1.15 × 0.95"),
    "farmersdelight:pumpkin_slice": (17 / 4, 1.15, 1.0, 0.95, "南瓜17 ÷ 4片 × 1.15 × 0.95"),
    "farmersdelight:cooked_chicken_cuts": (8 / 2, 1.15, 1.0, 0.95, "熟鸡肉8 ÷ 2份 × 1.15 × 0.95"),
    "kaleidoscope_cookery:cooked_cut_small_meats": (8 / 2, 1.15, 1.0, 0.95, "熟鸡肉8 ÷ 2份 × 1.15 × 0.95（最便宜路径）"),
    "kaleidoscope_cookery:cooked_cow_offal": (11 / 2, 1.15, 1.0, 0.95, "熟牛肉11 ÷ 2份 × 1.15 × 0.95"),
    "kaleidoscope_cookery:cooked_pork_belly": (17 / 2, 1.15, 1.0, 0.95, "熟猪排17 ÷ 2份 × 1.15 × 0.95"),
    "kaleidoscope_cookery:cooked_lamb_chops": (17 / 2, 1.15, 1.0, 0.95, "熟羊肉17 ÷ 2份 × 1.15 × 0.95"),
    "farmersdelight:cooked_salmon_slice": (9 / 2, 1.15, 1.0, 0.95, "熟鲑鱼9 ÷ 2片 × 1.15 × 0.95"),
    "farmersdelight:cooked_cod_slice": (8 / 2, 1.15, 1.0, 0.95, "熟鳕鱼8 ÷ 2片 × 1.15 × 0.95"),
    "farmersdelight:kelp_roll_slice": (12 / 3, 1.15, 1.0, 0.95, "海带寿司卷12 ÷ 3片 × 1.15 × 0.95"),
    "kaleidoscope_cookery:sweet_and_sour_ender_pearls": (65, 1.15, 0.85, 0.95, "(末影珍珠20+末影之眼45) × 1.15 × 0.85 × 0.95"),
    "kaleidoscope_cookery:watermelon_platter": (11, 1.05, 1.0, 1.0, "(3×西瓜片3+碗2) × 1.05"),
    "farmersdelight:fruit_salad": (31, 1.65, 1.35, 1.0, "(苹果6+2×西瓜片3+2×浆果6+南瓜片5+碗2) × 1.65 × 1.35"),
    "farmersdelight:pumpkin_soup": (30, 2.45, 1.15, 1.0, "(南瓜片5+绿叶菜4+生猪肉15+奶6) × 2.45 × 1.15"),
    "farmersdelight:roasted_mutton_chops": (32, 2.45, 1.15, 1.0, "(熟羊排10+甜菜根7+碗2+米饭7+番茄6) × 2.45 × 1.15"),
    "kaleidoscope_cookery:golden_salad": (524, 1.0, 1.0, 1.0, "2×金苹果206+2×金胡萝卜28+2×闪烁西瓜片27+碗2（材料价下限）"),
}

# 龙息尚无玩家市场价格，不放入常驻收购。
REMOVE_FROM_BOUNTIES = {"kaleidoscope_cookery:oolong"}

REFERENCE_OVERRIDES = {
    "minecraft:melon_slice": (3, "配方派生", "1个西瓜可切出9片，按整瓜25除以9向上取整"),
    "farmersdelight:pumpkin_slice": (5, "配方派生", "1个南瓜17可切出4片，含少量切配加工价"),
    "farmersdelight:kelp_roll_slice": (5, "配方派生", "1个海带寿司卷可切出3片"),
    "farmersdelight:cooked_chicken_cuts": (5, "配方派生", "1份熟鸡肉可切出2份"),
    "kaleidoscope_cookery:cooked_cut_small_meats": (5, "配方派生", "按最便宜的1份熟鸡肉切出2份"),
    "kaleidoscope_cookery:cooked_cow_offal": (7, "配方派生", "1份熟牛肉可切出2份"),
    "kaleidoscope_cookery:cooked_pork_belly": (10, "配方派生", "1份熟猪排可切出2份"),
    "kaleidoscope_cookery:cooked_lamb_chops": (10, "配方派生", "1份熟羊肉可切出2份"),
    "farmersdelight:cooked_salmon_slice": (5, "配方派生", "1份熟鲑鱼可切出2片"),
    "farmersdelight:cooked_cod_slice": (5, "配方派生", "1份熟鳕鱼可切出2片"),
    "minecraft:ender_eye": (45, "配方派生", "末影珍珠20+烈焰粉25"),
    "minecraft:golden_apple": (206, "配方派生", "8金锭×25+苹果6"),
    "minecraft:golden_carrot": (28, "配方派生", "8金粒×3+胡萝卜4"),
    "minecraft:glistering_melon_slice": (27, "配方派生", "8金粒×3+西瓜片3"),
    "minecraft:dragon_breath": (60, "探索锚点", "暂不进常驻悬赏，待玩家市场形成后再校准"),
}


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def update_objective_pools() -> None:
    for pool_name in ("food_common_objs", "food_t2_objs", "food_t3_objs"):
        path = POOL_DIR / f"{pool_name}.json"
        data = read_json(path)
        for key in list(data["content"]):
            entry = data["content"][key]
            item_id = entry["content"]
            if item_id in REMOVE_FROM_BOUNTIES:
                del data["content"][key]
                continue
            if item_id in PRICE_OVERRIDES:
                entry["unitWorth"] = PRICE_OVERRIDES[item_id]
            # Bountiful 8.0.0-beta.2 的 repRequired 只对奖励是硬门槛。
            entry.pop("repRequired", None)
        write_json(path, data)

    farmer_path = POOL_DIR / "farmer_objs.json"
    farmer = read_json(farmer_path)
    for entry in farmer["content"].values():
        if entry["content"] == "minecraft:melon_slice":
            entry["unitWorth"] = 3
    write_json(farmer_path, farmer)


def coin_entry(entry_id: str, max_amount: int) -> dict:
    return {
        "replace": True,
        "content": {
            entry_id: {
                "type": "item",
                "content": "lightmanscurrency:coin_gold",
                "amount": {"min": 1, "max": max_amount},
                "unitWorth": 100,
                "rarity": "RARE",
                "weightMult": 0.08,
                "repRequired": 10,
                "forbids": [
                    {"type": "item", "content": f"lightmanscurrency:coin_{coin}"}
                    for coin in ("copper", "iron", "gold", "emerald", "diamond", "netherite")
                ],
            }
        },
    }


def update_rewards_and_decrees() -> None:
    write_json(POOL_DIR / "gold_currency_rews.json", coin_entry("cook_gold_coin_high_tier", 6))
    write_json(POOL_DIR / "farmer_gold_currency_rews.json", coin_entry("farmer_gold_coin_high_tier", 4))

    farmer = read_json(DECREE_DIR / "farmer.json")
    farmer["objectives"] = ["farmer_objs"]
    farmer["rewards"] = ["_all_rews", "farmer_gold_currency_rews"]
    write_json(DECREE_DIR / "farmer.json", farmer)

    cook = read_json(DECREE_DIR / "cook.json")
    cook["objectives"] = ["food_common_objs", "food_t2_objs", "food_t3_objs"]
    cook["rewards"] = ["_all_rews", "gold_currency_rews"]
    write_json(DECREE_DIR / "cook.json", cook)

    config_path = ROOT / "Server/config/bountiful/bountiful.json"
    config = read_json(config_path)
    config["bounty"]["allowDecreeMixing"] = False
    write_json(config_path, config)


def update_reference_table() -> None:
    path = TABLE_DIR / "原料单价参考表.csv"
    with path.open(newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.reader(handle))
    by_id = {row[0]: row for row in rows[1:]}
    for item_id, (price, source, note) in REFERENCE_OVERRIDES.items():
        row = by_id.get(item_id)
        if row is None:
            row = [item_id, str(price), source, note]
            rows.append(row)
            by_id[item_id] = row
        else:
            row[:] = [item_id, str(price), source, note]
    rows[1:] = sorted(rows[1:], key=lambda row: row[0])
    with path.open("w", newline="", encoding="utf-8-sig") as handle:
        csv.writer(handle).writerows(rows)


def update_price_tables() -> None:
    price_path = TABLE_DIR / "菜品悬赏定价表.csv"
    with price_path.open(newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.reader(handle))
    for row in rows[1:]:
        item_id = row[4]
        if item_id in PRICE_OVERRIDES:
            price = PRICE_OVERRIDES[item_id]
            raw_cost, difficulty, buff, common_factor, explanation = PRICE_DETAILS[item_id]
            theory = raw_cost * difficulty * buff * common_factor
            row[7] = f"{raw_cost:.2f}".rstrip("0").rstrip(".")
            row[8] = str(difficulty)
            row[9] = str(buff)
            row[10] = str(common_factor)
            row[11] = f"{theory:.2f}".rstrip("0").rstrip(".")
            row[12] = str(price)
            row[15] = str(price * int(row[13]))
            row[16] = str(price * int(row[14]))
            row[18] = f"{explanation} = {theory:.2f} → ceil → {price}"
            row[22] = "2026-08-04经济审查：多产出按单个产物分摊，稀有原料按实际成本锚定"
            row[23] = "bounty_pools JSON（经济审查同步）"
        if item_id in REMOVE_FROM_BOUNTIES:
            row[2] = "排除"
            row[21] = ""
            row[22] = "龙息属于稀有探索材料，市场价未稳定，暂停常驻收购"
            row[23] = "2026-08-04经济审查"
    with price_path.open("w", newline="", encoding="utf-8-sig") as handle:
        csv.writer(handle).writerows(rows)

    tier_path = TABLE_DIR / "食物三档分类表.csv"
    with tier_path.open(newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.reader(handle))
    for row in rows[1:]:
        item_id = row[5]
        if item_id in PRICE_OVERRIDES:
            row[23] = str(PRICE_OVERRIDES[item_id])
        if item_id in REMOVE_FROM_BOUNTIES:
            row[0] = ""
            row[1] = "排除（稀有探索材料）"
            row[2] = "排除"
            row[3] = "龙息价格未稳定，不进常驻收购"
            row[22] = ""
            row[23] = ""
    with tier_path.open("w", newline="", encoding="utf-8-sig") as handle:
        csv.writer(handle).writerows(rows)


def load_food_entries() -> list[dict]:
    pools = (
        ("food_common_objs", "T1", "通用/基础"),
        ("food_t2_objs", "T2", "家常菜"),
        ("food_t3_objs", "T3", "名菜"),
    )
    result = []
    for pool_name, tier, tier_name in pools:
        data = read_json(POOL_DIR / f"{pool_name}.json")
        for entry in data["content"].values():
            result.append(
                {
                    "id": entry["content"],
                    "tier": tier,
                    "tier_name": tier_name,
                    "unit_worth": entry["unitWorth"],
                    "amount_min": entry["amount"]["min"],
                    "amount_max": entry["amount"]["max"],
                }
            )
    return result


def write_registry() -> None:
    entries = load_food_entries()
    lines = [
        "// 食韵筑家III：烟火长歌",
        "// 悬赏食物清单（由 scripts/sync_bounty_economy.py 从三个 food_* 池同步）",
        "",
        "const BOUNTY_FOOD_ENTRIES = [",
    ]
    for entry in entries:
        lines.append(
            "  { id: '%s', tier: '%s', tierName: '%s', unitWorth: %d, "
            "amountMin: %d, amountMax: %d },"
            % (
                entry["id"],
                entry["tier"],
                entry["tier_name"],
                entry["unit_worth"],
                entry["amount_min"],
                entry["amount_max"],
            )
        )
    lines.extend(
        [
            "]",
            "",
            "global.SYBountyFood = {",
            "  entries: BOUNTY_FOOD_ENTRIES,",
            "  byId: Object.fromEntries(BOUNTY_FOOD_ENTRIES.map(e => [e.id, e])),",
            "  tooltipTitle: '§6★ 悬赏收购',",
            "  tooltipHint: '§7食韵筑家 · 可在告示板悬赏中交付收购'",
            "}",
            "",
            "console.info(`[食韵筑家] 已注册悬赏食物 ${global.SYBountyFood.entries.length} 项（startup）`)",
            "",
        ]
    )
    REGISTRY_PATH.write_text("\n".join(lines), encoding="utf-8")


def validate() -> None:
    entries = load_food_entries()
    ids = [entry["id"] for entry in entries]
    if len(ids) != len(set(ids)):
        raise RuntimeError("食物悬赏池存在重复物品 ID")
    if any(entry["unit_worth"] <= 0 for entry in entries):
        raise RuntimeError("unitWorth 必须为正数")
    if any(entry["amount_min"] <= 0 or entry["amount_min"] > entry["amount_max"] for entry in entries):
        raise RuntimeError("数量区间非法")
    if any("repRequired" in read_json(POOL_DIR / f"{name}.json")["content"][key]
           for name in ("food_common_objs", "food_t2_objs", "food_t3_objs")
           for key in read_json(POOL_DIR / f"{name}.json")["content"]):
        raise RuntimeError("目标池中仍存在无效 repRequired")


def main() -> None:
    update_objective_pools()
    update_rewards_and_decrees()
    update_reference_table()
    update_price_tables()
    write_registry()
    validate()
    counts = {
        name: len(read_json(POOL_DIR / f"{name}.json")["content"])
        for name in ("food_common_objs", "food_t2_objs", "food_t3_objs")
    }
    print("悬赏经济同步完成：", counts, "合计", sum(counts.values()))


if __name__ == "__main__":
    main()
