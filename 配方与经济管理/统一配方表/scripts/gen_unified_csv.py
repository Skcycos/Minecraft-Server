#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""合并农夫乐事 + 万花筒烹饪配方,应用 UNITE 统一逻辑,以万花筒(KC)为基准输出总表 CSV。
- FD 配方:应用 unite_farmersdelight(高优先级)与 always(低优先级)数据包覆盖;
  被禁用的(forge:false/barrier)与失效的(item 引用标签)不收录(失效的注明替代);
  新增的 KC 机器配方收录。
- KC 配方:全量收录(本身即统一基准)。
"""
import json, os, glob, csv, sys, re
HERE = os.path.dirname(os.path.abspath(__file__))
JARROOT = os.environ.get("FD_JAR_DIR", "/tmp/fd_recipe")
PROJ = os.path.dirname(os.path.dirname(HERE))
WORK = os.path.join(HERE, "_work")


sys.path.insert(0, HERE)
import parse as fd
import parse_food  # 食物属性解析(含 fmt_effects)
import kc_parse  # 提供 kc_names / KC_ITEMS / KC_TAGS

# ---------- 食物属性 ----------
FOOD_PROPS = json.load(open(os.path.join(WORK, "food_props.json")))
ALL_FOOD = {}
for k, v in FOOD_PROPS.get("farmersdelight", {}).items():
    ALL_FOOD[f"farmersdelight:{k}"] = v
ALL_FOOD.update(FOOD_PROPS.get("kaleidoscope_cookery", {}))

def product_id(name):
    """从产物显示字符串提取单一物品 ID(多产物返回 None)"""
    if "+" in name:
        return None
    m = re.search(r"\(([a-z0-9_]+:[a-z0-9_/]+)\)$", name)
    return m.group(1) if m else None

def food_cols(name):
    """返回 (饥饿值, 饱和度, 效果) 显示列;非食物返回空"""
    pid = product_id(name)
    if not pid or pid not in ALL_FOOD:
        return "", "", ""
    fp = ALL_FOOD[pid]
    nut = fp.get("nutrition")
    sat_mod = fp.get("sat_mod")
    sat = ""
    if nut is not None and sat_mod is not None:
        sat = f"{nut * sat_mod * 2:.1f}"
    eff = parse_food.fmt_effects(fp.get("effects") or [])
    return (str(nut) if nut is not None else ""), sat, eff

OUT = os.path.join(PROJ, "统一配方表")
FD_RECIPE = os.path.join(JARROOT, "data/farmersdelight/recipe")
COMPAT = os.path.join(JARROOT, "kc_compat/packs")
KC_RECIPE = os.path.join(JARROOT, "kc/data/kaleidoscope_cookery/recipe")
KC_LANG = json.load(open(os.path.join(JARROOT, "kc/assets/kaleidoscope_cookery/lang/zh_cn.json")))

# ---------- 统一映射 ----------
TAGS = dict(fd.TAGS)
TAGS.update(kc_parse.KC_TAGS)
TAGS.update({
    "create:wheat_flour": "面粉(Create)",
})
# 补物品名映射(产物/原料中的 item 引用)
fd.VANILLA.update({"minecraft:hopper": "漏斗", "minecraft:tnt": "TNT"})
kc_parse.KC_ITEMS.update({"create:wheat_flour": "面粉(Create)"})

kc_names = kc_parse.kc_names
KC_ITEMS = kc_parse.KC_ITEMS

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
        if t == "neoforge:partial":
            return "部分匹配:" + parse_ingredient(ing.get("ingredient", {}))
        if "item" in ing:
            n = ing.get("count", 1); s = item_name(ing["item"])
            return f"{s}×{n}" if n != 1 else s
        if "tag" in ing:
            n = ing.get("count", 1); s = tag_name(ing["tag"])
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
            elif isinstance(r, dict) and "id" in r:
                parts.append(parse_result(r))
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
            return fd.ACTION.get(tool.get("action", ""), tool.get("action", "?"))
        if "tag" in tool:
            return tag_name(tool["tag"])
        if "item" in tool:
            return item_name(tool["item"])
    return str(tool)

def shaped_ingredients(recipe):
    key = recipe.get("key", {}); pattern = recipe.get("pattern", [])
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

# ---------- 机器映射(两模组统一) ----------
FD_MACHINE = {
    "minecraft:crafting_shaped": "工作台", "minecraft:crafting_shapeless": "工作台",
    "minecraft:smelting": "熔炉", "minecraft:smoking": "烟熏炉",
    "minecraft:campfire_cooking": "营火", "minecraft:blasting": "高炉",
    "farmersdelight:cooking": "厨锅(烹饪锅,置于炉灶上)",
    "farmersdelight:cutting": "砧板", "minecraft:smithing_transform": "锻造台",
    "farmersdelight:decomposition": "堆肥桶", "farmersdelight:food_serving": "厨锅(分装机制)",
    "farmersdelight:dough": "面团机制",
}
KC_MACHINE = {
    "minecraft:crafting_shaped": "工作台", "minecraft:crafting_shapeless": "工作台",
    "kaleidoscope_cookery:pot": "炒锅(置于炉灶上)",
    "kaleidoscope_cookery:flex_pot": "炒锅(灵活)",
    "kaleidoscope_cookery:stockpot": "汤锅(盖盖煮制)",
    "kaleidoscope_cookery:flex_stockpot": "汤锅(灵活)",
    "kaleidoscope_cookery:millstone": "石磨", "kaleidoscope_cookery:chopping_board": "菜板",
    "kaleidoscope_cookery:teapot": "茶壶", "kaleidoscope_cookery:steamer": "蒸笼",
    "kaleidoscope_cookery:rice_bowl": "盖饭(饭碗)",
}

def parse_recipe(r, machine_map):
    """解析一个配方 dict,返回 (rid, name, count, ings, machine, extra)"""
    rtype = r.get("type", "")
    machine = machine_map.get(rtype, rtype)
    if rtype == "minecraft:crafting_shaped":
        ings = shaped_ingredients(r)
    elif rtype == "minecraft:crafting_shapeless":
        ings = [parse_ingredient(i) for i in r.get("ingredients", [])]
    elif rtype in ("minecraft:smelting", "minecraft:smoking", "minecraft:campfire_cooking", "minecraft:blasting"):
        ings = [parse_ingredient(r.get("ingredient", {}))]
    elif rtype == "minecraft:smithing_transform":
        ings = [parse_ingredient(r.get("template", {})), parse_ingredient(r.get("base", {})), parse_ingredient(r.get("addition", {}))]
    elif rtype in ("kaleidoscope_cookery:pot", "kaleidoscope_cookery:flex_pot",
                   "kaleidoscope_cookery:stockpot", "kaleidoscope_cookery:flex_stockpot",
                   "farmersdelight:cooking"):
        ings = [parse_ingredient(i) for i in r.get("ingredients", [])]
    else:  # 单原料型
        ing = r.get("ingredient") or (r.get("ingredients", [{}])[0] if r.get("ingredients") else {})
        ings = [parse_ingredient(ing)]
    result_field = r.get("result") if r.get("result") is not None else r.get("results")
    name, cnt = parse_result(result_field if result_field is not None else {})
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
    if r.get("cut_count"):
        extra.append(f"切 {r['cut_count']} 刀")
    if r.get("time"):
        extra.append(f"{r['time']} 刻")
    if r.get("tea_fluid"):
        extra.append(f"茶底:{item_name(r['tea_fluid'])}")
    if r.get("carrier"):
        extra.append(f"载体:{parse_ingredient(r['carrier'])}")
    return name, cnt, ings, machine, "; ".join(extra)

# ---------- 1. FD 生效配方 ----------
def load_json(path):
    try:
        return json.load(open(path))
    except Exception:
        return None

# 收集覆盖:relpath -> (pack, data)
overrides = {}
for pack in ["unite_farmersdelight", "always"]:
    base = os.path.join(COMPAT, pack, "data/farmersdelight/recipe")
    if not os.path.isdir(base):
        continue
    for f in glob.glob(base + "/**/*.json", recursive=True):
        rel = os.path.relpath(f, base)
        overrides.setdefault(rel, (pack, load_json(f)))

rows = []
seen_orig = set()
# Create 类型机器名(本服已装 Create)
CREATE_MACHINE = {
    "create:milling": "动力磨盘(Create)", "create:mixing": "动力搅拌(Create)",
    "create:filling": "动力注液(Create)", "create:pressing": "动力压片(Create)",
    "create:mechanical_crafting": "机械合成台(Create)", "create:deploying": "机械手(Create)",
    "create:splashing": "动力清洗(Create)", "create:haunting": "幽灵化(Create)",
    "create:compacting": "动力压制(Create)", "create:cutting": "动力锯(Create)",
    "create:sandpaper_polishing": "砂纸打磨(Create)", "create:sequenced_assembly": "序列装配(Create)",
}
FD_MACHINE.update(CREATE_MACHINE)

for f in sorted(glob.glob(FD_RECIPE + "/**/*.json", recursive=True)):
    rel = os.path.relpath(f, FD_RECIPE)
    seen_orig.add(rel)
    orig = load_json(f)
    # 过滤本服未装模组的 integration(immersiveengineering / silentgear)
    if rel.startswith("integration/") and not rel.startswith("integration/create/"):
        continue
    status = "原始"
    data = orig
    src = "farmersdelight"
    if rel in overrides:
        pack, new = overrides[rel]
        txt = json.dumps(new)
        if "forge:false" in txt or "minecraft:barrier" in txt:
            status = "已禁用(UNITE)"
            data = None
        elif '"item": "c:' in txt:
            status = "已失效(UNITE bug,见说明)"
            data = None
        elif json.dumps(orig, sort_keys=True) != txt:
            status = "已修改(UNITE)"
            data = new
        else:
            status = "原始"
    if data is None:
        continue  # 禁用/失效不收录
    name, cnt, ings, machine, extra = parse_recipe(data, FD_MACHINE)
    note = ""
    if status == "已修改(UNITE)":
        note = "原料/产物已统一为万花筒物品"
    elif status == "已失效(UNITE bug,见说明)":
        note = "由 create 命名空间替代配方承接(普通熔炉烧万花筒面团)"
    rows.append(["农夫乐事", rel, name, cnt, machine, " + ".join(ings), extra,
                 *food_cols(name), status, note])

# always 包中 FD 命名空间新增(KC 机器做 FD 菜)
for f in sorted(glob.glob(os.path.join(COMPAT, "always/data/farmersdelight/recipe/**/*.json"), recursive=True)):
    rel = os.path.relpath(f, os.path.join(COMPAT, "always/data/farmersdelight/recipe"))
    if rel in seen_orig:
        continue
    data = load_json(f)
    if not data:
        continue
    name, cnt, ings, machine, extra = parse_recipe(data, KC_MACHINE)
    rows.append(["农夫乐事(万花筒机器)", rel, name, cnt, machine, " + ".join(ings), extra,
                 *food_cols(name), "新增(万花筒机器)", "用万花筒机器制作农夫乐事菜品"])

# ---------- 2. KC 全量配方 ----------
for f in sorted(glob.glob(KC_RECIPE + "/**/*.json", recursive=True)):
    rel = os.path.relpath(f, KC_RECIPE)
    data = load_json(f)
    if not data:
        continue
    name, cnt, ings, machine, extra = parse_recipe(data, KC_MACHINE)
    rows.append(["万花筒烹饪", rel, name, cnt, machine, " + ".join(ings), extra,
                 *food_cols(name), "原始(统一基准)", ""])

# ---------- 排序:机器(万花筒优先)→ 产物 ----------
KC_ORDER = ["炒锅", "汤锅", "石磨", "菜板", "茶壶", "蒸笼", "盖饭", "工作台"]
FD_ORDER = ["厨锅", "砧板", "工作台", "熔炉", "烟熏炉", "营火", "高炉", "锻造台"]

def machine_key(m):
    m2 = m.replace("(", "").replace(")", "")
    for i, k in enumerate(KC_ORDER):
        if m2.startswith(k):
            return (0, i)
    for i, k in enumerate(FD_ORDER):
        if m2.startswith(k):
            return (1, i)
    return (2, 0)

rows.sort(key=lambda r: (machine_key(r[4]), r[2]))

os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "统一配方总表.csv"), "w", newline="", encoding="utf-8-sig") as fp:
    w = csv.writer(fp)
    w.writerow(["模组", "配方ID", "产物", "数量", "机器", "原料", "附加参数", "饥饿值", "饱和度", "效果(buff)", "UNITE状态", "备注"])
    for r in rows:
        w.writerow(r)

from collections import Counter
print("总配方数:", len(rows))
print("按模组:", Counter(r[0] for r in rows))
print("按UNITE状态:", Counter(r[10] for r in rows))
print("输出:", os.path.join(OUT, "统一配方总表.csv"))
