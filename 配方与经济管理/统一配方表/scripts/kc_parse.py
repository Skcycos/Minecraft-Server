#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解析 Kaleidoscope Cookery 1.4.1 (1.21.1) 全部配方,输出 CSV。
复用农夫乐事解析脚本的中文映射,并补充本模组特有内容。"""
import json, os, glob, csv, sys
HERE = os.path.dirname(os.path.abspath(__file__))
JARROOT = os.environ.get("FD_JAR_DIR", "/tmp/fd_recipe")
PROJ = os.path.dirname(os.path.dirname(HERE))
WORK = os.path.join(HERE, "_work")


sys.path.insert(0, HERE)
import parse as fd  # 复用 FD 脚本的 VANILLA/TAGS/fd_names 等映射与解析函数

BASE = os.path.join(JARROOT, "kc/data/kaleidoscope_cookery/recipe")
LANG = os.path.join(JARROOT, "kc/assets/kaleidoscope_cookery/lang/zh_cn.json")
OUT = os.path.join(PROJ, "万花筒烹饪配方表")

# ---------- KC 物品中文名 ----------
lang = json.load(open(LANG))
kc_names = {}
for k, v in lang.items():
    if k.startswith(("item.kaleidoscope_cookery.", "block.kaleidoscope_cookery.")):
        rid = k.split(".", 1)[1].replace(".", ":")
        kc_names[rid] = v

# KC 特有标签补充
KC_TAGS = {
    "c:cobblestones": "圆石", "c:crops/chilipepper": "辣椒", "c:crops/lettuce": "生菜",
    "c:dough": "面团", "c:fences/wooden": "木栅栏", "c:flour": "面粉",
    "c:foods/cooked_rice": "熟米饭", "c:gems/diamond": "钻石", "c:grain/rice": "稻米",
    "c:gravels": "砂砾", "c:ingots/copper": "铜锭", "c:ingots/gold": "金锭",
    "c:nuggets/iron": "铁粒", "c:raw_fishes": "生鱼", "c:raw_meats": "生肉",
    "c:sands/colorless": "沙子", "c:sands/red": "红沙", "c:seeds": "种子",
    "c:vegetables": "蔬菜",
    "kaleidoscope_cookery:caterpillars": "毛虫", "kaleidoscope_cookery:straw_bale": "稻草捆",
    "kaleidoscope_cookery:straw_hat": "草帽",
}
TAGS = dict(fd.TAGS)
TAGS.update(KC_TAGS)

# 本模组用到的额外原版物品
KC_ITEMS = {
    "kaleidoscope_cookery:recipe_item": "模组占位物品",
    "minecraft:andesite": "安山岩", "minecraft:basalt": "玄武岩", "minecraft:blackstone": "黑石",
    "minecraft:diorite": "闪长岩", "minecraft:granite": "花岗岩", "minecraft:gilded_blackstone": "镶金黑石",
    "minecraft:polished_andesite": "磨制安山岩", "minecraft:polished_basalt": "磨制玄武岩",
    "minecraft:polished_blackstone": "磨制黑石", "minecraft:polished_deepslate": "磨制深板岩",
    "minecraft:polished_diorite": "磨制闪长岩", "minecraft:polished_granite": "磨制花岗岩",
    "minecraft:smooth_basalt": "平滑玄武岩", "minecraft:smooth_quartz": "平滑石英块",
    "minecraft:smooth_stone": "平滑石头", "minecraft:smooth_sandstone": "平滑砂岩",
    "minecraft:smooth_red_sandstone": "平滑红砂岩", "minecraft:sandstone": "砂岩",
    "minecraft:red_sandstone": "红砂岩", "minecraft:quartz_bricks": "石英砖",
    "minecraft:quartz_pillar": "石英柱", "minecraft:chiseled_quartz_block": "錾制石英块",
    "minecraft:coal": "煤炭", "minecraft:coal_ore": "煤矿石", "minecraft:iron_ore": "铁矿石",
    "minecraft:gold_ore": "金矿石", "minecraft:copper_ore": "铜矿石", "minecraft:diamond_ore": "钻石矿石",
    "minecraft:emerald_ore": "绿宝石矿石", "minecraft:redstone_ore": "红石矿石",
    "minecraft:lapis_ore": "青金石矿石", "minecraft:nether_gold_ore": "下界金矿石",
    "minecraft:nether_quartz_ore": "下界石英矿石", "minecraft:deepslate_coal_ore": "深层煤矿石",
    "minecraft:deepslate_iron_ore": "深层铁矿石", "minecraft:deepslate_gold_ore": "深层金矿石",
    "minecraft:deepslate_copper_ore": "深层铜矿石", "minecraft:deepslate_diamond_ore": "深层钻石矿石",
    "minecraft:deepslate_emerald_ore": "深层绿宝石矿石", "minecraft:deepslate_redstone_ore": "深层红石矿石",
    "minecraft:deepslate_lapis_ore": "深层青金石矿石",
    "minecraft:chorus_fruit": "紫颂果", "minecraft:popped_chorus_fruit": "爆裂紫颂果",
    "minecraft:cactus": "仙人掌", "minecraft:dragon_breath": "龙息", "minecraft:ender_eye": "末影之眼",
    "minecraft:ender_pearl": "末影珍珠", "minecraft:fern": "蕨", "minecraft:large_fern": "大型蕨",
    "minecraft:flower_pot": "花盆", "minecraft:gunpowder": "火药", "minecraft:lapis_lazuli": "青金石",
    "minecraft:nautilus_shell": "鹦鹉螺壳", "minecraft:raw_copper": "粗铜", "minecraft:raw_gold": "粗金",
    "minecraft:raw_iron": "粗铁", "minecraft:redstone": "红石粉", "minecraft:slime_ball": "粘液球",
    "minecraft:tropical_fish": "热带鱼", "minecraft:pink_petals": "粉红色花瓣", "minecraft:pitcher_plant": "瓶子草",
    "minecraft:black_wool": "黑色羊毛", "minecraft:blue_wool": "蓝色羊毛", "minecraft:brown_wool": "棕色羊毛",
    "minecraft:cyan_wool": "青色羊毛", "minecraft:gray_wool": "灰色羊毛", "minecraft:green_wool": "绿色羊毛",
    "minecraft:light_blue_wool": "淡蓝色羊毛", "minecraft:light_gray_wool": "淡灰色羊毛",
    "minecraft:lime_wool": "黄绿色羊毛", "minecraft:magenta_wool": "品红色羊毛",
    "minecraft:orange_wool": "橙色羊毛", "minecraft:pink_wool": "粉红色羊毛",
    "minecraft:purple_wool": "紫色羊毛", "minecraft:red_wool": "红色羊毛",
    "minecraft:white_wool": "白色羊毛", "minecraft:yellow_wool": "黄色羊毛",
    "minecraft:blaze_powder": "烈焰粉", "minecraft:blue_ice": "蓝冰", "minecraft:composter": "堆肥桶",
    "minecraft:glistering_melon_slice": "闪烁的西瓜片", "minecraft:grindstone": "砂轮",
    "minecraft:heavy_weighted_pressure_plate": "重型测重压力板", "minecraft:lily_pad": "睡莲",
    "minecraft:nether_star": "下界之星", "minecraft:phantom_membrane": "幻翼膜",
    "minecraft:pufferfish": "河豚", "minecraft:sculk": "幽匿块", "minecraft:soul_campfire": "灵魂营火",
    "minecraft:stone_button": "石按钮", "minecraft:water": "水",
}

def item_name(rid):
    if rid in kc_names:
        return f"{kc_names[rid]}({rid})"
    if rid in fd.fd_names:
        return f"{fd.fd_names[rid]}({rid})"
    if rid in KC_ITEMS:
        return f"{KC_ITEMS[rid]}({rid})"
    if rid in fd.VANILLA:
        return f"{fd.VANILLA[rid]}({rid})"
    return rid

def tag_name(tid):
    if tid in TAGS:
        return f"{TAGS[tid]}(#{tid})"
    return f"#{tid}"

def parse_ingredient(ing):
    if isinstance(ing, list):
        return "任选其一:[" + " | ".join(parse_ingredient(i) for i in ing) + "]"
    if isinstance(ing, dict):
        t = ing.get("type")
        if t == "neoforge:difference":
            return f"{parse_ingredient(ing.get('base'))}(除{parse_ingredient(ing.get('subtracted'))})"
        if t == "neoforge:compound":
            return "任选其一:[" + " | ".join(parse_ingredient(c) for c in ing.get("children", [])) + "]"
        if "item" in ing:
            n = ing.get("count", 1)
            s = item_name(ing["item"])
            return f"{s}×{n}" if n != 1 else s
        if "tag" in ing:
            n = ing.get("count", 1)
            s = tag_name(ing["tag"])
            return f"{s}×{n}" if n != 1 else s
    return str(ing)

def parse_result(res):
    if isinstance(res, dict) and "id" in res:
        return item_name(res["id"]), res.get("count", 1)
    if isinstance(res, str):
        return item_name(res), 1
    return str(res), 1

# 机器映射
MACHINE = {
    "minecraft:crafting_shaped": "工作台",
    "minecraft:crafting_shapeless": "工作台",
    "kaleidoscope_cookery:pot": "炒锅(置于炉灶上,依次加油/加料/翻炒/盛菜)",
    "kaleidoscope_cookery:flex_pot": "炒锅(灵活配方,可替换原料)",
    "kaleidoscope_cookery:stockpot": "汤锅(加汤底/食材,盖盖煮制)",
    "kaleidoscope_cookery:flex_stockpot": "汤锅(灵活配方,可替换原料)",
    "kaleidoscope_cookery:millstone": "石磨",
    "kaleidoscope_cookery:chopping_board": "菜板",
    "kaleidoscope_cookery:teapot": "茶壶",
    "kaleidoscope_cookery:steamer": "蒸笼",
    "kaleidoscope_cookery:rice_bowl": "盖饭(饭碗:菜+米饭)",
}

recipes = []
for f in sorted(glob.glob(os.path.join(BASE, "**/*.json"), recursive=True)):
    try:
        r = json.load(open(f))
    except Exception as e:
        print("跳过", f, e); continue
    rtype = r.get("type", "")
    rid = os.path.relpath(f, BASE).replace(".json", "").replace("/", "_")
    machine = MACHINE.get(rtype, rtype)
    if rtype == "minecraft:crafting_shaped":
        key = r.get("key", {}); pattern = r.get("pattern", [])
        counts = {}
        for row in pattern:
            for ch in row:
                if ch != " ":
                    counts[ch] = counts.get(ch, 0) + 1
        ings = []
        for ch, cnt in counts.items():
            ing = key.get(ch)
            if not ing: continue
            s = parse_ingredient(ing)
            ings.append(f"{s}×{cnt}" if cnt > 1 else s)
    elif rtype == "minecraft:crafting_shapeless":
        ings = [parse_ingredient(i) for i in r.get("ingredients", [])]
    elif rtype in ("kaleidoscope_cookery:pot", "kaleidoscope_cookery:flex_pot",
                   "kaleidoscope_cookery:stockpot", "kaleidoscope_cookery:flex_stockpot"):
        ings = [parse_ingredient(i) for i in r.get("ingredients", [])]
    else:  # millstone / chopping_board / teapot / steamer / rice_bowl 单原料
        ings = [parse_ingredient(r.get("ingredient", {}))]
    name, cnt = parse_result(r.get("result", {}))
    extra = []
    if r.get("cut_count"):
        extra.append(f"切 {r['cut_count']} 刀")
    if r.get("time"):
        extra.append(f"{r['time']} 刻")
    if r.get("tea_fluid"):
        extra.append(f"茶底:{item_name(r['tea_fluid'])}")
    if r.get("carrier"):
        extra.append(f"载体:{parse_ingredient(r['carrier'])}")
    recipes.append({
        "rid": rid, "name": name, "count": cnt, "ings": ings,
        "machine": machine, "extra": "; ".join(extra), "type": rtype,
    })

print("解析配方总数:", len(recipes))
from collections import Counter
print(Counter(r["type"] for r in recipes))

os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "kaleidoscope_cookery_recipes.csv"), "w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerow(["配方ID", "产物", "数量", "机器", "原料", "附加参数"])
    for r in recipes:
        w.writerow([r["rid"], r["name"], r["count"], r["machine"], " + ".join(r["ings"]), r["extra"]])
print("CSV 已写入:", os.path.join(OUT, "kaleidoscope_cookery_recipes.csv"))
