#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 recipes_raw.json 生成 Markdown 数据表。"""
import json, os, re
HERE = os.path.dirname(os.path.abspath(__file__))
JARROOT = os.environ.get("FD_JAR_DIR", "/tmp/fd_recipe")
PROJ = os.path.dirname(os.path.dirname(HERE))
WORK = os.path.join(HERE, "_work")


WORK = os.path.join(HERE, "_work")
OUT = os.path.join(PROJ, "农夫乐事配方表")
recipes = json.load(open(os.path.join(WORK, "recipes_raw.json")))

def clean_name(n):
    return re.sub(r"\([^)]*\)$", "", n)

def sort_key(r):
    return clean_name(r["name"])

def fmt_ings(r):
    return " + ".join(r["ings"]) if r["ings"] else "—"

def md_table(rows):
    lines = ["| 产物 | 数量 | 原料 | 机器/工具 | 附加参数 | 配方ID |",
             "|---|---|---|---|---|---|"]
    for r in rows:
        extra = r["extra"] or "—"
        lines.append(f"| {r['name']} | {r['count']} | {fmt_ings(r)} | {r['machine']} | {extra} | `{r['rid']}` |")
    return "\n".join(lines)

def is_food(r):
    n = clean_name(r["name"])
    return any(k in r["rid"] for k in ["food", "soup", "stew", "sandwich", "pie", "cake", "cookie",
        "pasta", "salad", "juice", "tea", "stuffed", "roast", "bacon", "ham", "patty", "dough",
        "flour", "butter", "cheese", "minced", "smoked", "slice", "crust", "stock", "sauce",
        "sugar", "egg", "milk", "cooked_", "raw_", "barbecue", "fried", "grilled", "curry",
        "wrap", "bowl", "stew", "noodle", "rice", "porridge", "salad", "smoothie", "cabbage_roll",
        "mutton", "chicken", "beef", "pork", "cod", "salmon", "rabbit", "kelp", "onion", "tomato",
        "cabbage", "rice_panicle", "pumpkin", "mushroom", "wild_", "seeds", "straw", "canvas"])

def category(r):
    """启发式分类:汤/炖菜、三明治、甜点、饮品、主食、原料、基础/装饰"""
    rid = r["rid"]; n = clean_name(r["name"])
    if "soup" in rid or "stew" in rid or "汤" in n or "煲" in n: return "汤/炖菜"
    if "sandwich" in rid or "三明治" in n: return "三明治"
    if any(k in rid for k in ["pie", "cake", "cookie", "pudding", "donut", "cupcake", "chocolate", "sweet"]) or "派" in n or "蛋糕" in n or "曲奇" in n: return "甜点/烘焙"
    if any(k in rid for k in ["juice", "tea", "milk", "beer", "wine", "coffee", "cocktail", "smoothie", "hot_chocolate", "melon_juice"]) or "汁" in n or "茶" in n or "酒" in n or "奶" in n: return "饮品"
    if any(k in rid for k in ["pasta", "noodle", "rice", "porridge", "salad", "wrap", "taco", "curry", "barbecue", "fried", "grilled", "stuffed", "roast", "bacon_and", "bacon_sandwich"]) or "面" in n or "饭" in n or "串" in n or "沙拉" in n: return "主食/菜肴"
    if any(k in rid for k in ["minced", "patty", "dough", "flour", "butter", "cheese", "smoked", "crust", "stock", "sauce", "sugar", "fried_egg", "cooked_", "raw_", "roasted", "slice", "cabbage_roll", "kelp_roll", "bacon", "ham", "egg", "milk"]) or "馅" in n or "饼" in n or "切片" in n or "奶酪" in n: return "食材/原料"
    return "食物(其他)"

# 机器分组
groups = {
    "厨锅": [r for r in recipes if r["type"] == "farmersdelight:cooking"],
    "砧板": [r for r in recipes if r["type"] == "farmersdelight:cutting"],
    "工作台": [r for r in recipes if r["type"] in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless")],
    "炉灶": [r for r in recipes if r["type"] in ("minecraft:smelting", "minecraft:smoking", "minecraft:campfire_cooking", "minecraft:blasting")],
    "锻造台": [r for r in recipes if r["type"] == "minecraft:smithing_transform"],
}
for g in groups:
    groups[g].sort(key=sort_key)

# README
foods = [r for r in recipes if is_food(r)]
foods.sort(key=sort_key)
with open(os.path.join(OUT, "README.md"), "w", encoding="utf-8") as f:
    f.write("""# 农夫乐事 (Farmer's Delight) 菜品与原料数据表

> 数据来源:模组内置数据包 `data/farmersdelight/recipe/`(FarmersDelight 1.3.2 / MC 1.21.1 / NeoForge)
> 提取方式:直接解析 jar 内配方 JSON,非游戏内实测;若有 KubeJS 覆盖配方,以游戏内为准。

## 统计

| 类别 | 配方数 |
|---|---|
| 厨锅烹饪(菜品) | %d |
| 砧板切割 | %d |
| 工作台合成 | %d |
| 熔炉/烟熏炉/营火/高炉 | %d |
| 锻造台 | %d |
| **合计** | **%d** |

## 文件

| 文件 | 内容 |
|---|---|
| `01-厨锅烹饪.md` | 厨锅(烹饪锅)制作的全部菜品,含烹饪时间与经验 |
| `02-砧板切割.md` | 砧板 + 工具切割/加工配方 |
| `03-工作台与炉灶.md` | 工作台合成、熔炉/烟熏炉/营火/高炉烧制、锻造台 |
| `farmersdelight_recipes.csv` | 全量数据表(配方ID/产物/数量/机器/原料/附加参数),可用 Excel 打开 |

## 机器速查

| 机器 | 作用 | 说明 |
|---|---|---|
| **厨锅**(`cooking_pot`,置于炉灶上) | 烹饪多原料菜品 | 需燃料,产出汤/炖菜/主菜 |
| **砧板**(`cutting_board`) | 切割/加工 | 需持刀或其他工具右键,产物常带概率掉落 |
| **工作台** | 普通合成 | 三明治、派、原料、设施 |
| **熔炉/烟熏炉/营火/高炉** | 烧制 | 熟肉、肉饼、面包等 |
| **锻造台** | 升级 | 钻石刀 → 下界合金刀 |

> 未收录:`food_serving`(厨锅分装机制,无固定配方)与 `dough`(面团遇水机制)为逻辑配方。
""" % (len(groups["厨锅"]), len(groups["砧板"]), len(groups["工作台"]),
       len(groups["炉灶"]), len(groups["锻造台"]), len(recipes)))

# 01 厨锅
with open(os.path.join(OUT, "01-厨锅烹饪.md"), "w", encoding="utf-8") as f:
    f.write("# 01 · 厨锅烹饪(菜品)\n\n")
    f.write("> 机器:**厨锅**(`farmersdelight:cooking_pot`,须放在点燃的炉灶上);原料默认各 1 份。\n\n")
    f.write(md_table(groups["厨锅"]) + "\n")

# 02 砧板
with open(os.path.join(OUT, "02-砧板切割.md"), "w", encoding="utf-8") as f:
    f.write("# 02 · 砧板切割\n\n")
    f.write("> 机器:**砧板**(`farmersdelight:cutting_board`);需手持指定工具右键。产物可能带概率(掉落率)。\n\n")
    f.write(md_table(groups["砧板"]) + "\n")

# 03 工作台与炉灶
with open(os.path.join(OUT, "03-工作台与炉灶.md"), "w", encoding="utf-8") as f:
    f.write("# 03 · 工作台合成 / 炉灶烧制 / 锻造台\n\n")
    f.write("## 工作台合成\n\n")
    f.write(md_table(groups["工作台"]) + "\n\n")
    f.write("## 熔炉 / 烟熏炉 / 营火 / 高炉\n\n")
    f.write("> 同类配方常有多种烧制方式(熔炉 200 刻 / 烟熏炉 100 刻 / 营火 600 刻 / 高炉 100 刻)。\n\n")
    f.write(md_table(groups["炉灶"]) + "\n\n")
    f.write("## 锻造台\n\n")
    f.write(md_table(groups["锻造台"]) + "\n")

print("Markdown 已生成:", len(recipes), "条配方")
for g in groups:
    print(f"  {g}: {len(groups[g])}")
