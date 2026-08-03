#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解析 Farmer's Delight 1.3.2 (1.21.1) 全部配方,生成数据表。"""
import json, os, glob, csv
HERE = os.path.dirname(os.path.abspath(__file__))
JARROOT = os.environ.get("FD_JAR_DIR", "/tmp/fd_recipe")
PROJ = os.path.dirname(os.path.dirname(HERE))
WORK = os.path.join(HERE, "_work")

from collections import Counter

BASE = os.path.join(JARROOT, "data/farmersdelight/recipe")
OUT = os.path.join(PROJ, "农夫乐事配方表")
WORK = os.path.join(HERE, "_work")

# ---------- 中文映射 ----------
lang = json.load(open(os.path.join(JARROOT, "assets/farmersdelight/lang/zh_cn.json")))
fd_names = {}
for k, v in lang.items():
    if k.startswith(("item.farmersdelight.", "block.farmersdelight.")):
        rid = k.split(".", 1)[1].replace(".", ":")
        fd_names[rid] = v

# 原版物品常用中文映射
WOODS = {"oak": "橡木", "spruce": "云杉木", "birch": "白桦木", "jungle": "丛林木",
         "acacia": "金合欢木", "dark_oak": "深色橡木", "mangrove": "红树木", "cherry": "樱花木",
         "crimson": "绯红木", "warped": "诡异木", "bamboo": "竹"}
WOOD_PARTS = {"boat": "船", "chest_boat": "储物船", "raft": "竹筏", "chest_raft": "储物竹筏",
              "button": "按钮", "door": "门", "fence": "栅栏", "fence_gate": "栅栏门",
              "hanging_sign": "悬挂牌", "pressure_plate": "压力板", "slab": "台阶", "trapdoor": "活板门", "sign": "告示牌"}
EXTRA_VANILLA = {
    "minecraft:amethyst_block": "紫水晶块", "minecraft:baked_potato": "烤马铃薯",
    "minecraft:brick": "红砖", "minecraft:bricks": "红砖块", "minecraft:bucket": "桶",
    "minecraft:campfire": "营火", "minecraft:chest_minecart": "箱子矿车", "minecraft:cocoa_beans": "可可豆",
    "minecraft:deepslate": "深板岩", "minecraft:dirt": "泥土", "minecraft:glow_lichen": "发光地衣",
    "minecraft:hanging_roots": "垂根", "minecraft:hay_block": "干草块", "minecraft:hopper_minecart": "漏斗矿车",
    "minecraft:ice": "冰", "minecraft:nether_bricks": "下界砖块", "minecraft:netherite_ingot": "下界合金锭",
    "minecraft:netherite_upgrade_smithing_template": "下界合金升级锻造模板", "minecraft:quartz_block": "石英块",
    "minecraft:saddle": "鞍", "minecraft:tnt_minecart": "TNT 矿车", "minecraft:torchflower": "火把花",
    "minecraft:wooden_shovel": "木锹", "minecraft:mud": "泥巴",
    "minecraft:leather_boots": "皮革靴", "minecraft:leather_chestplate": "皮革胸甲",
    "minecraft:leather_helmet": "皮革头盔", "minecraft:leather_leggings": "皮革护腿",
    "minecraft:leather_horse_armor": "皮革马铠",
    "minecraft:amethyst_shard": "紫水晶碎片", "minecraft:book": "书",
    "minecraft:cobbled_deepslate": "深板岩圆石", "minecraft:lead": "拴绳",
    "minecraft:nether_brick": "下界砖", "minecraft:packed_mud": "泥坯",
    "minecraft:painting": "画", "minecraft:quartz": "下界石英", "minecraft:scaffolding": "脚手架",
    "minecraft:black_dye": "黑色染料", "minecraft:blue_dye": "蓝色染料", "minecraft:brown_dye": "棕色染料",
    "minecraft:cyan_dye": "青色染料", "minecraft:gray_dye": "灰色染料", "minecraft:green_dye": "绿色染料",
    "minecraft:light_blue_dye": "淡蓝色染料", "minecraft:light_gray_dye": "淡灰色染料",
    "minecraft:lime_dye": "黄绿色染料", "minecraft:magenta_dye": "品红色染料",
    "minecraft:orange_dye": "橙色染料", "minecraft:pink_dye": "粉红色染料",
    "minecraft:purple_dye": "紫色染料", "minecraft:red_dye": "红色染料",
    "minecraft:white_dye": "白色染料", "minecraft:yellow_dye": "黄色染料",
}
for w, wn in WOODS.items():
    for part, pn in WOOD_PARTS.items():
        EXTRA_VANILLA[f"minecraft:{w}_{part}"] = f"{wn}{pn}"
for w, wn in WOODS.items():
    if w == "bamboo":
        EXTRA_VANILLA["minecraft:stripped_bamboo_block"] = "去皮竹块"
    elif w in ("crimson", "warped"):
        EXTRA_VANILLA[f"minecraft:stripped_{w}_stem"] = f"去皮{wn}菌柄"
        EXTRA_VANILLA[f"minecraft:stripped_{w}_hyphae"] = f"去皮{wn}菌核"
    else:
        EXTRA_VANILLA[f"minecraft:stripped_{w}_log"] = f"去皮{wn}原木"
        EXTRA_VANILLA[f"minecraft:stripped_{w}_wood"] = f"去皮{wn}"
VANILLA = {
    "minecraft:apple": "苹果", "minecraft:wheat": "小麦", "minecraft:sugar": "糖",
    "minecraft:egg": "鸡蛋", "minecraft:milk_bucket": "奶桶", "minecraft:carrot": "胡萝卜",
    "minecraft:potato": "马铃薯", "minecraft:beetroot": "甜菜根", "minecraft:beetroot_soup": "甜菜汤",
    "minecraft:mushroom_stew": "蘑菇煲", "minecraft:brown_mushroom": "棕色蘑菇", "minecraft:red_mushroom": "红色蘑菇",
    "minecraft:chicken": "生鸡肉", "minecraft:cooked_chicken": "熟鸡肉", "minecraft:beef": "生牛肉",
    "minecraft:cooked_beef": "牛排", "minecraft:porkchop": "生猪排", "minecraft:cooked_porkchop": "熟猪排",
    "minecraft:mutton": "生羊肉", "minecraft:cooked_mutton": "熟羊肉", "minecraft:cod": "生鳕鱼",
    "minecraft:cooked_cod": "熟鳕鱼", "minecraft:salmon": "生鲑鱼", "minecraft:cooked_salmon": "熟鲑鱼",
    "minecraft:rabbit": "生兔肉", "minecraft:cooked_rabbit": "熟兔肉", "minecraft:rabbit_stew": "兔肉煲",
    "minecraft:bread": "面包", "minecraft:cake": "蛋糕", "minecraft:pumpkin_pie": "南瓜派",
    "minecraft:cookie": "曲奇", "minecraft:bowl": "碗", "minecraft:stick": "木棍",
    "minecraft:paper": "纸", "minecraft:string": "线", "minecraft:leather": "皮革",
    "minecraft:bone": "骨头", "minecraft:flint": "燧石", "minecraft:clay_ball": "黏土球",
    "minecraft:clay": "黏土块", "minecraft:gravel": "砂砾", "minecraft:sand": "沙子",
    "minecraft:ink_sac": "墨囊", "minecraft:kelp": "海带", "minecraft:dried_kelp": "干海带",
    "minecraft:sweet_berries": "甜浆果", "minecraft:glow_berries": "发光浆果", "minecraft:honey_bottle": "蜜瓶",
    "minecraft:honeycomb": "蜜脾", "minecraft:glass_bottle": "玻璃瓶", "minecraft:snowball": "雪球",
    "minecraft:bone_meal": "骨粉", "minecraft:wheat_seeds": "小麦种子", "minecraft:beetroot_seeds": "甜菜种子",
    "minecraft:pumpkin_seeds": "南瓜种子", "minecraft:melon_seeds": "西瓜种子", "minecraft:torch": "火把",
    "minecraft:lantern": "灯笼", "minecraft:water_bucket": "水桶", "minecraft:nether_wart": "下界疣",
    "minecraft:blaze_rod": "烈焰棒", "minecraft:magma_cream": "岩浆膏",
    "minecraft:golden_carrot": "金胡萝卜", "minecraft:golden_apple": "金苹果", "minecraft:emerald": "绿宝石",
    "minecraft:diamond": "钻石", "minecraft:iron_ingot": "铁锭", "minecraft:gold_ingot": "金锭",
    "minecraft:copper_ingot": "铜锭", "minecraft:stone": "石头", "minecraft:cobblestone": "圆石",
    "minecraft:oak_log": "橡木原木", "minecraft:spruce_log": "云杉原木", "minecraft:birch_log": "白桦原木",
    "minecraft:jungle_log": "丛林原木", "minecraft:acacia_log": "金合欢原木", "minecraft:dark_oak_log": "深色橡木原木",
    "minecraft:mangrove_log": "红树木原木", "minecraft:cherry_log": "樱花原木", "minecraft:crimson_stem": "绯红菌柄",
    "minecraft:warped_stem": "诡异菌柄", "minecraft:oak_wood": "橡木", "minecraft:spruce_wood": "云杉木",
    "minecraft:birch_wood": "白桦木", "minecraft:jungle_wood": "丛林木", "minecraft:acacia_wood": "金合欢木",
    "minecraft:dark_oak_wood": "深色橡木", "minecraft:mangrove_wood": "红树木", "minecraft:cherry_wood": "樱花木",
    "minecraft:crimson_hyphae": "绯红菌核", "minecraft:warped_hyphae": "诡异菌核",
    "minecraft:oak_planks": "橡木木板", "minecraft:spruce_planks": "云杉木板", "minecraft:birch_planks": "白桦木板",
    "minecraft:jungle_planks": "丛林木板", "minecraft:acacia_planks": "金合欢木板", "minecraft:dark_oak_planks": "深色橡木木板",
    "minecraft:mangrove_planks": "红树木板", "minecraft:cherry_planks": "樱花木板", "minecraft:bamboo_planks": "竹板",
    "minecraft:crimson_planks": "绯红木板", "minecraft:warped_planks": "诡异木板",
    "minecraft:dandelion": "蒲公英", "minecraft:poppy": "虞美人", "minecraft:blue_orchid": "兰花",
    "minecraft:allium": "绒球葱", "minecraft:azure_bluet": "蓝花美耳草", "minecraft:red_tulip": "红色郁金香",
    "minecraft:orange_tulip": "橙色郁金香", "minecraft:white_tulip": "白色郁金香", "minecraft:pink_tulip": "粉色郁金香",
    "minecraft:oxeye_daisy": "滨菊", "minecraft:cornflower": "矢车菊", "minecraft:lily_of_the_valley": "铃兰",
    "minecraft:wither_rose": "凋灵玫瑰", "minecraft:sunflower": "向日葵", "minecraft:lilac": "丁香",
    "minecraft:rose_bush": "玫瑰丛", "minecraft:peony": "牡丹", "minecraft:pumpkin": "南瓜",
    "minecraft:melon_slice": "西瓜片", "minecraft:melon": "西瓜", "minecraft:snow": "雪",
    "minecraft:bamboo": "竹子", "minecraft:sugar_cane": "甘蔗",
    "minecraft:glass": "玻璃", "minecraft:iron_nugget": "铁粒", "minecraft:gold_nugget": "金粒",
    "minecraft:spider_eye": "蜘蛛眼", "minecraft:rotten_flesh": "腐肉",
    "minecraft:furnace": "熔炉", "minecraft:minecart": "矿车", "minecraft:furnace_minecart": "动力矿车",
    "minecraft:oak_sign": "橡木告示牌", "minecraft:spruce_sign": "云杉告示牌",
    "minecraft:birch_sign": "白桦告示牌", "minecraft:jungle_sign": "丛林告示牌", "minecraft:acacia_sign": "金合欢告示牌",
    "minecraft:dark_oak_sign": "深色橡木告示牌", "minecraft:mangrove_sign": "红树告示牌",
    "minecraft:spruce_door": "云杉门", "minecraft:spruce_trapdoor": "云杉活板门", "minecraft:spruce_fence": "云杉栅栏",
    "minecraft:spruce_fence_gate": "云杉栅栏门", "minecraft:spruce_pressure_plate": "云杉压力板",
    "minecraft:spruce_button": "云杉按钮", "minecraft:spruce_boat": "云杉船", "minecraft:spruce_hanging_sign": "云杉悬挂牌",
    "minecraft:oak_trapdoor": "橡木活板门", "minecraft:oak_door": "橡木门", "minecraft:oak_fence": "橡木栅栏",
    "minecraft:oak_fence_gate": "橡木栅栏门", "minecraft:oak_pressure_plate": "橡木压力板",
    "minecraft:oak_button": "橡木按钮", "minecraft:oak_boat": "橡木船", "minecraft:oak_hanging_sign": "橡木悬挂牌",
    "minecraft:birch_trapdoor": "白桦活板门", "minecraft:birch_door": "白桦门",
    "minecraft:cherry_sign": "樱花告示牌", "minecraft:cherry_door": "樱花门", "minecraft:cherry_trapdoor": "樱花活板门",
    "minecraft:cherry_fence": "樱花栅栏", "minecraft:cherry_fence_gate": "樱花栅栏门", "minecraft:cherry_pressure_plate": "樱花压力板",
    "minecraft:cherry_button": "樱花按钮", "minecraft:cherry_boat": "樱花船", "minecraft:cherry_hanging_sign": "樱花悬挂牌",
    "minecraft:bamboo_block": "竹块", "minecraft:bamboo_mosaic": "竹马赛克",
    "minecraft:chain": "锁链", "minecraft:iron_bars": "铁栏杆", "minecraft:chest": "箱子",
    "minecraft:barrel": "木桶", "minecraft:bow": "弓", "minecraft:crimson_fungus": "绯红菌", "minecraft:warped_fungus": "诡异菌",
    **EXTRA_VANILLA
}

# 标签中文描述
TAGS = {
    "c:crops/wheat": "小麦", "c:crops/carrot": "胡萝卜", "c:crops/potato": "马铃薯",
    "c:crops/beetroot": "甜菜根", "c:crops/tomato": "番茄", "c:crops/cabbage": "卷心菜",
    "c:crops/onion": "洋葱", "c:crops/rice": "稻米", "c:foods/bread": "面包",
    "c:foods/cooked_bacon": "熟培根", "c:foods/leafy_green": "绿叶菜", "c:foods/raw_chicken": "生鸡肉",
    "c:foods/raw_bacon": "生培根", "c:foods/raw_beef": "生牛肉", "c:foods/raw_pork": "生猪排",
    "c:foods/raw_mutton": "生羊肉", "c:foods/raw_fish": "生鱼", "c:foods/cooked_fish": "熟鱼",
    "c:foods/cooked_chicken": "熟鸡肉", "c:foods/vegetable": "蔬菜", "c:foods/milk": "奶",
    "c:foods/butter": "黄油", "c:foods/cheese": "奶酪", "c:foods/egg": "蛋",
    "c:foods/flour": "面粉", "c:foods/dough": "面团", "c:foods/pasta": "意面",
    "c:foods/pie_crust": "派皮", "c:foods/cooked_porkchop": "熟猪排",
    "c:foods/raw_meat": "生肉", "c:foods/cooked_meat": "熟肉",
    "c:foods/mushroom": "蘑菇", "c:foods/fruit": "水果", "c:foods/cooked_egg": "熟蛋",
    "c:foods/sugar": "糖", "c:foods/soup": "汤", "c:foods/honey": "蜂蜜",
    "c:foods/raw_salmon": "生鲑鱼", "c:foods/cooked_salmon": "熟鲑鱼",
    "c:foods/raw_cod": "生鳕鱼", "c:foods/cooked_cod": "熟鳕鱼", "c:foods/raw_rabbit": "生兔肉",
    "c:foods/cooked_rabbit": "熟兔肉", "c:foods/cake": "蛋糕", "c:foods/cookie": "曲奇",
    "c:foods/cooked_beef": "熟牛肉", "c:foods/cooked_mutton": "熟羊肉",
    "c:foods/kelp": "海带", "c:foods/raw_meats": "生肉",
    "c:seeds/wheat": "小麦种子", "c:seeds/beetroot": "甜菜种子",
    "minecraft:planks": "木板", "minecraft:logs": "原木", "minecraft:log": "原木",
    "minecraft:axes": "斧", "minecraft:pickaxes": "镐", "minecraft:shovels": "锹",
    "minecraft:hoes": "锄", "minecraft:swords": "剑", "minecraft:shears": "剪刀",
    "minecraft:knives": "刀", "minecraft:slabs": "台阶", "minecraft:stairs": "楼梯",
    "minecraft:signs": "告示牌", "minecraft:saplings": "树苗", "minecraft:flowers": "花",
    "minecraft:small_flowers": "小花", "minecraft:wool": "羊毛",
    "minecraft:crops": "作物", "minecraft:leaves": "树叶", "minecraft:sand": "沙子",
    "minecraft:terracotta": "陶瓦", "minecraft:planks": "木板",
    "minecraft:wooden_trapdoors": "木活板门", "minecraft:wooden_doors": "木门",
    "minecraft:wooden_fences": "木栅栏", "minecraft:wooden_pressure_plates": "木压力板",
    "minecraft:wooden_buttons": "木按钮", "minecraft:boats": "船",
    "minecraft:dirt": "泥土", "minecraft:stone_pressure_plates": "石压力板",
    "minecraft:stone_buttons": "石按钮", "minecraft:stone_bricks": "石砖",
    "minecraft:oak_logs": "橡木原木", "minecraft:birch_logs": "白桦原木",
    "minecraft:spruce_logs": "云杉原木", "minecraft:jungle_logs": "丛林原木",
    "minecraft:acacia_logs": "金合欢原木", "minecraft:dark_oak_logs": "深色橡木原木",
    "minecraft:mangrove_logs": "红树木原木", "minecraft:cherry_logs": "樱花原木",
    "minecraft:crimson_stems": "绯红菌柄", "minecraft:warped_stems": "诡异菌柄",
    "minecraft:wool_carpets": "地毯", "minecraft:wooden_slabs": "木台阶",
    "minecraft:wooden_stairs": "木楼梯", "minecraft:fences": "栅栏", "minecraft:fence_gates": "栅栏门",
    "minecraft:leaves": "树叶", "minecraft:flowers": "花",
    "farmersdelight:tools/knives": "刀(任意)", "farmersdelight:straw": "稻草",
    "farmersdelight:canvas_signs": "帆布告示牌", "farmersdelight:hanging_canvas_signs": "悬挂帆布告示牌",
    "c:bones": "骨头", "c:buckets/water": "水桶", "c:chocolate": "巧克力",
    "c:drinks/milk": "奶", "c:eggs": "蛋", "c:foods/berry": "浆果",
    "c:foods/safe_raw_fish": "安全生鱼(不含河豚)", "c:ingots/iron": "铁锭",
    "c:milk": "奶", "c:mushrooms": "蘑菇", "c:tools/knife": "刀", "c:tools/shear": "剪刀",
    "c:dyes/black": "黑色染料", "c:dyes/blue": "蓝色染料", "c:dyes/brown": "棕色染料",
    "c:dyes/cyan": "青色染料", "c:dyes/gray": "灰色染料", "c:dyes/green": "绿色染料",
    "c:dyes/light_blue": "淡蓝色染料", "c:dyes/light_gray": "淡灰色染料",
    "c:dyes/lime": "黄绿色染料", "c:dyes/magenta": "品红色染料", "c:dyes/orange": "橙色染料",
    "c:dyes/pink": "粉红色染料", "c:dyes/purple": "紫色染料", "c:dyes/red": "红色染料",
    "c:dyes/white": "白色染料", "c:dyes/yellow": "黄色染料",
}

ACTION = {
    "axe_dig": "斧", "pickaxe_dig": "镐", "shovel_dig": "锹", "hoe_dig": "锄",
    "sword_dig": "剑", "shears_dig": "剪刀", "knife": "刀", "knife_cut": "刀",
    "axe_strip": "斧(剥皮)", "shovel_flatten": "锹(铲平)", "hoe_till": "锄(耕地)",
    "pickaxe_harvest": "镐", "any": "任意工具",
}

def item_name(rid):
    if rid in fd_names:
        return f"{fd_names[rid]}({rid})"
    if rid in VANILLA:
        return f"{VANILLA[rid]}({rid})"
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
            base = parse_ingredient(ing.get("base"))
            sub = parse_ingredient(ing.get("subtracted"))
            return f"{base}(除{sub})"
        if t == "neoforge:compound":
            return "任选其一:[" + " | ".join(parse_ingredient(c) for c in ing.get("children", [])) + "]"
        if t == "neoforge:partial":
            return "部分匹配:" + parse_ingredient(ing.get("ingredient", {}))
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
    if isinstance(res, str):
        return item_name(res), 1
    if isinstance(res, dict):
        if "id" in res:
            return item_name(res["id"]), res.get("count", 1)
        if "item" in res:
            inner = res["item"]
            if isinstance(inner, dict):
                return item_name(inner["id"]), inner.get("count", 1)
            return item_name(inner), 1
    if isinstance(res, list):
        parts = []
        for r in res:
            if isinstance(r, dict) and "item" in r:
                parts.append(parse_result(r["item"]))
            else:
                parts.append(parse_result(r))
        return " + ".join(p[0] for p in parts), parts[0][1]
    return str(res), 1

def parse_tool(tool):
    if isinstance(tool, list):
        return "或".join(parse_tool(t) for t in tool)
    if isinstance(tool, dict):
        t = tool.get("type")
        if t == "farmersdelight:item_ability":
            return ACTION.get(tool.get("action", ""), tool.get("action", "?"))
        if "tag" in tool:
            return tag_name(tool["tag"])
        if "item" in tool:
            return item_name(tool["item"])
    return str(tool)

def shaped_ingredients(recipe):
    key = recipe.get("key", {})
    pattern = recipe.get("pattern", [])
    counts = {}
    for row in pattern:
        for ch in row:
            if ch != " ":
                counts[ch] = counts.get(ch, 0) + 1
    out = []
    for ch, cnt in counts.items():
        ing = key.get(ch)
        if not ing:
            continue
        s = parse_ingredient(ing)
        out.append(f"{s}×{cnt}" if cnt > 1 else s)
    return out

MACHINE = {
    "minecraft:crafting_shaped": "工作台",
    "minecraft:crafting_shapeless": "工作台",
    "minecraft:smelting": "熔炉",
    "minecraft:smoking": "烟熏炉",
    "minecraft:campfire_cooking": "营火",
    "minecraft:blasting": "高炉",
    "farmersdelight:cooking": "厨锅(置于炉灶上)",
    "farmersdelight:cutting": "砧板",
    "farmersdelight:decomposition": "堆肥桶",
    "minecraft:smithing_transform": "锻造台",
    "farmersdelight:food_serving": "厨锅(分装机制)",
    "farmersdelight:dough": "面团机制",
}

recipes = []

def load_dir(d):
    for f in sorted(glob.glob(os.path.join(d, "*.json"))):
        try:
            r = json.load(open(f))
        except Exception as e:
            print("跳过", f, e); continue
        rtype = r.get("type", "")
        rid = os.path.basename(f).replace(".json", "")
        machine = MACHINE.get(rtype, rtype)
        if rtype == "minecraft:crafting_shaped":
            ings = shaped_ingredients(r)
        elif rtype == "minecraft:crafting_shapeless":
            ings = [parse_ingredient(i) for i in r.get("ingredients", [])]
        elif rtype in ("minecraft:smelting", "minecraft:smoking", "minecraft:campfire_cooking", "minecraft:blasting"):
            ings = [parse_ingredient(r.get("ingredient", {}))]
        elif rtype == "farmersdelight:cooking":
            ings = [parse_ingredient(i) for i in r.get("ingredients", [])]
        elif rtype == "farmersdelight:cutting":
            ings = [parse_ingredient(i) for i in r.get("ingredients", [])]
        elif rtype == "minecraft:smithing_transform":
            ings = [parse_ingredient(r.get("template", {})), parse_ingredient(r.get("base", {})), parse_ingredient(r.get("addition", {}))]
        else:
            ings = []
        name, cnt = parse_result(r.get("result", {}))
        extra = []
        if rtype == "farmersdelight:cutting":
            extra.append("工具:" + parse_tool(r.get("tool", "?")))
            res = r.get("result", [])
            if isinstance(res, list) and any(isinstance(x, dict) and "chance" in x for x in res):
                ch = res[0].get("chance", 1.0)
                if ch < 1.0:
                    extra.append(f"掉落率{ch*100:.0f}%")
        if rtype in ("minecraft:smelting", "minecraft:smoking", "minecraft:campfire_cooking", "minecraft:blasting"):
            extra.append(f"{r.get('cookingtime', 200)}刻")
            extra.append(f"经验{r.get('experience', 0.1)}")
        if rtype == "farmersdelight:cooking":
            extra.append(f"{r.get('cookingtime', 200)}刻")
            extra.append(f"经验{r.get('experience', 1.0)}")
        recipes.append({
            "rid": rid, "name": name, "count": cnt, "ings": ings,
            "machine": machine, "extra": "; ".join(extra), "type": rtype,
            "tab": r.get("recipe_book_tab", ""),
        })

for d in ["", "cooking", "cutting", "salvaging"]:
    load_dir(os.path.join(BASE, d) if d else BASE)

print("解析配方总数:", len(recipes))
print(Counter(r["type"] for r in recipes))

os.makedirs(OUT, exist_ok=True)
os.makedirs(WORK, exist_ok=True)
with open(os.path.join(OUT, "farmersdelight_recipes.csv"), "w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerow(["配方ID", "产物", "数量", "机器", "原料", "附加参数"])
    for r in recipes:
        w.writerow([r["rid"], r["name"], r["count"], r["machine"], " + ".join(r["ings"]), r["extra"]])
print("CSV 已写入:", os.path.join(OUT, "farmersdelight_recipes.csv"))
json.dump(recipes, open(os.path.join(WORK, "recipes_raw.json"), "w"), ensure_ascii=False, indent=1)
print("完成")
