#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对比 FD 原始配方与 UNITE 模式下实际生效配方,输出差异对照 CSV。
生效优先级:unite_farmersdelight > always > FD jar 原始。
"""
import json, os, glob, csv
HERE = os.path.dirname(os.path.abspath(__file__))
JARROOT = os.environ.get("FD_JAR_DIR", "/tmp/fd_recipe")
PROJ = os.path.dirname(os.path.dirname(HERE))
WORK = os.path.join(HERE, "_work")


COMPAT = os.path.join(JARROOT, "kc_compat/packs")
ORIG = os.path.join(JARROOT, "data/farmersdelight/recipe")
OUT = os.path.join(PROJ, "农夫乐事配方表")

def load(path):
    try:
        return json.load(open(path))
    except Exception:
        return None

def brief(recipe):
    """配方摘要(类型/产物/原料)"""
    if not recipe:
        return "—"
    rtype = recipe.get("type", "?")
    res = recipe.get("result")
    if isinstance(res, dict):
        result = res.get("id", "?")
        cnt = res.get("count", 1)
    elif isinstance(res, list):
        result = "+".join(x.get("item", {}).get("id", "?") for x in res)
        cnt = ""
    else:
        result, cnt = str(res), ""
    ings = recipe.get("ingredients") or ([recipe["ingredient"]] if "ingredient" in recipe else [])
    ing_str = ", ".join(i.get("item", i.get("tag", "?")) if isinstance(i, dict) else "?" for i in ings)
    tool = recipe.get("tool")
    if tool:
        t = tool if isinstance(tool, list) else [tool]
        tstr = "; ".join(x.get("action", x.get("tag", x.get("item", "?"))) for x in t)
        return f"{rtype} | 产物:{result}×{cnt} | 原料:[{ing_str}] | 工具:{tstr}"
    return f"{rtype} | 产物:{result}×{cnt} | 原料:[{ing_str}]"

rows = []
# 1) unite_farmersdelight 覆盖的 FD 配方
for f in sorted(glob.glob(COMPAT + "/unite_farmersdelight/data/farmersdelight/recipe/**/*.json", recursive=True)):
    rel = os.path.relpath(f, COMPAT + "/unite_farmersdelight/data/farmersdelight/recipe")
    orig_path = os.path.join(ORIG, rel)
    orig = load(orig_path)
    new = load(f)
    if orig is None:
        rows.append([rel, "—", "新增", brief(new), "unite_farmersdelight"]); continue
    if json.dumps(orig, sort_keys=True) == json.dumps(new, sort_keys=True):
        rows.append([rel, brief(orig), "无变化", brief(new), "unite_farmersdelight"]); continue
    txt = json.dumps(new)
    if "forge:false" in txt or "minecraft:barrier" in txt:
        rows.append([rel, brief(orig), "禁用", brief(new), "unite_farmersdelight"])
    elif '"item": "c:' in txt:
        rows.append([rel, brief(orig), "❌失效(bug:item引用标签)", brief(new), "unite_farmersdelight"])
    else:
        rows.append([rel, brief(orig), "修改", brief(new), "unite_farmersdelight"])

# 2) always 包中 FD 命名空间配方(未被 unite_farmersdelight 覆盖的)
unite_set = {r[0] for r in rows}
for f in sorted(glob.glob(COMPAT + "/always/data/farmersdelight/recipe/**/*.json", recursive=True)):
    rel = os.path.relpath(f, COMPAT + "/always/data/farmersdelight/recipe")
    if rel in unite_set:
        continue
    orig_path = os.path.join(ORIG, rel)
    orig = load(orig_path)
    new = load(f)
    if orig is None:
        rows.append([rel, "—", "新增(KC机器)", brief(new), "always"])
    elif json.dumps(orig, sort_keys=True) != json.dumps(new, sort_keys=True):
        rows.append([rel, brief(orig), "修改", brief(new), "always"])

with open(os.path.join(OUT, "farmersdelight_unite_diff.csv"), "w", newline="", encoding="utf-8-sig") as fp:
    w = csv.writer(fp)
    w.writerow(["配方ID", "原始配方", "UNITE后状态", "UNITE后配方", "来源包"])
    for r in rows:
        w.writerow(r)

print("共", len(rows), "条差异")
from collections import Counter
print(Counter(r[2] for r in rows))
