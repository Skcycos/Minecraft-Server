#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 FoodValues(农夫乐事)/ModFoods(万花筒) 字节码提取食物属性:
nutrition(饥饿值)、saturationModifier、effects(效果/时长/等级/概率)、alwaysEdible。
输出 JSON:{item_id: {nutrition, sat_mod, effects: [...], always_edible}}"""
import subprocess, re, json, os
HERE = os.path.dirname(os.path.abspath(__file__))
JARROOT = os.environ.get("FD_JAR_DIR", "/tmp/fd_recipe")
PROJ = os.path.dirname(os.path.dirname(HERE))
WORK = os.path.join(HERE, "_work")


WORK = os.path.join(HERE, "_work")

VANILLA_EFFECT_CN = {
    "absorption": "伤害吸收", "speed": "速度", "slowness": "缓慢", "haste": "急迫",
    "mining_fatigue": "挖掘疲劳", "strength": "力量", "instant_health": "瞬间治疗",
    "instant_damage": "瞬间伤害", "jump_boost": "跳跃提升", "nausea": "反胃",
    "regeneration": "生命恢复", "resistance": "抗性提升", "fire_resistance": "抗火",
    "water_breathing": "水下呼吸", "invisibility": "隐身", "blindness": "失明",
    "night_vision": "夜视", "hunger": "饥饿", "weakness": "虚弱", "poison": "中毒",
    "wither": "凋零", "health_boost": "生命提升", "saturation": "饱和",
    "glowing": "发光", "levitation": "飘浮", "luck": "幸运", "unluck": "霉运",
    "slow_falling": "缓降", "conduit_power": "潮涌能量", "dolphins_grace": "海豚的恩惠",
    "bad_omen": "不祥之兆", "hero_of_the_village": "村庄英雄", "darkness": "黑暗",
    "infested": "寄生", "oozing": "渗浆", "raider": "袭击", "trial_omen": "试炼之兆",
    "wind_charged": "充能风", "weaving": "编织", "village_hero": "村庄英雄",
    "confusion": "反胃", "movement_speed": "速度", "jump": "跳跃提升",
    "dig_speed": "急迫", "dig_slowdown": "挖掘疲劳", "damage_resistance": "抗性提升",
    "damage_boost": "力量", "move_slowdown": "缓慢", "heal": "生命恢复",
}
KC_EFFECT_CN = {
    "flatulence": "胀气", "tundra_strider": "寒带疾行", "warmth": "温暖",
    "satiated_shield": "饱腹代偿", "vigor": "活力", "sulfur": "硫磺", "mustard": "芥末",
    "preservation": "保鲜", "hinder": "迟滞", "projectile_dodge": "弹射闪避",
    "instant_smelting": "即时熔炼", "vitality": "生机",
}

def javap_text(cls_path):
    r = subprocess.run(["javap", "-v", "-c", "-p", cls_path], capture_output=True, text=True)
    return r.stdout

def parse_int(line):
    m = re.search(r"iconst_([0-5])|bipush\s+(\d+)|sipush\s+(\d+)|(?:ldc|ldc_w)\s+#\d+\s+//\s+(?:int|Integer)\s+(\d+)", line)
    return int(next(g for g in m.groups() if g is not None)) if m else None

def parse_float(line):
    m = re.search(r"fconst_([012])|(?:ldc|ldc_w)\s+#\d+\s+//\s+float\s+([0-9.]+)f?", line)
    if not m:
        return None
    return {"0": 0.0, "1": 1.0, "2": 2.0}.get(m.group(1)) if m.group(1) else float(m.group(2))

def bootstrap_map(text):
    """BootstrapMethods 段:bootstrap 编号 → lambda 方法名"""
    m = re.search(r"BootstrapMethods:\n(.*)", text, re.S)
    out = {}
    if m:
        cur = None
        for line in m.group(1).splitlines():
            head = re.match(r"\s*(\d+):", line)
            if head:
                cur = head.group(1)
                continue
            lm = re.search(r"REF_invokeStatic\s+[\w/$]+\.(lambda\$static\$\d+)", line)
            if lm and cur is not None:
                out[int(cur)] = lm.group(1)
    return out

def parse_lambda_effects(text, bmap):
    """解析所有效果 lambda,返回 {lambda_name: {holder,duration,amplifier}}"""
    eff = {}
    for lname in set(bmap.values()):
        m = re.search(r"private static .*?\b" + re.escape(lname) + r"\(\);.*?Code:\s*\n(.*?)(?=\n\s{2}\S|\Z)", text, re.S)
        if not m:
            continue
        body = m.group(1)
        holder = None
        mm = re.search(r"getstatic\s+#\d+\s+//\s+Field\s+([\w/$]+)\.([A-Z_]+):", body)
        if mm:
            cls, fname = mm.group(1), mm.group(2)
            holder = ("minecraft:" + fname.lower()) if "MobEffects" in cls else fname.lower()
        else:
            mn = re.search(r"(?:sipush|bipush|iconst_[0-5])\s*(\d+)\s*\n\s*\d+:\s+invokestatic\s+#\d+\s+//\s+Method\s+\S*nourishment:\(I\)", body)
            if not mn:
                # iconst 形式
                mi = re.search(r"iconst_([0-5])\s*\n\s*\d+:\s+invokestatic\s+#\d+\s+//\s+Method\s+\S*nourishment:\(I\)", body)
                if mi:
                    mn = mi
            if mn:
                holder, duration = "farmersdelight:nourishment", int(mn.group(1))
        if not holder:
            continue
        # 构造参数
        mc = re.search(r"invokespecial\s+#\d+\s+//\s+Method\s+net/minecraft/world/effect/MobEffectInstance\.\"<init>\":\(Lnet/minecraft/core/Holder;(II(?:ZZ)?|I)\)V", body)
        if mc and "nourishment" not in holder:
            prefix = body[: mc.start()]
            ints = [v for line in prefix.splitlines() if (v := parse_int(line)) is not None]
            sig = mc.group(1)
            if sig == "I":
                duration, amplifier = ints[-1], 0
            elif sig == "IIZZ":
                duration, amplifier = ints[-4], ints[-3]
            else:
                duration, amplifier = ints[-2], ints[-1]
        elif "nourishment" in holder:
            amplifier = 0
        else:
            duration = amplifier = None
        eff[lname] = {"holder": holder, "duration": duration, "amplifier": amplifier if amplifier is not None else 0}
    return eff

def parse_food_class(cls_path, field_to_item):
    text = javap_text(cls_path)
    bmap = bootstrap_map(text)
    lam_eff = parse_lambda_effects(text, bmap)
    m = re.search(r"static \{\};.*?Code:\s*\n(.*?)(?=\n\s*$|\Z)", text, re.S)
    body = m.group(1) if m else ""
    result = {}
    pending = {"nutrition": None, "sat": None, "fx": [], "always": False}
    direct = False  # 直接构造 FoodProperties 模式
    prev = ""
    for line in body.splitlines():
        pm = re.search(r"putstatic\s+#\d+\s+//\s+Field\s+([A-Z_0-9]+):", line)
        if pm:  # 一个 builder 完成:保存并重置
            name = pm.group(1)
            if name in field_to_item:
                result[field_to_item[name]] = {
                    "nutrition": pending["nutrition"], "sat_mod": pending["sat"],
                    "effects": pending["fx"], "always_edible": pending["always"]}
            pending = {"nutrition": None, "sat": None, "fx": [], "always": False}
            direct = False
            prev = line
            continue
        if "FoodProperties$Builder" in line and "new" in line:
            pending = {"nutrition": None, "sat": None, "fx": [], "always": False}
            direct = False
            prev = line
            continue
        if "new" in line and "FoodProperties" in line and "Builder" not in line:
            pending = {"nutrition": None, "sat": None, "fx": [], "always": False}
            direct = True
            prev = line
            continue
        if direct:
            # 直接构造:取第一个 int 为 nutrition,第一个 float 为 sat
            if pending["nutrition"] is None:
                v = parse_int(line)
                if v is not None:
                    pending["nutrition"] = v
            if pending["sat"] is None:
                v = parse_float(line)
                if v is not None:
                    pending["sat"] = v
            prev = line
            continue
        if "nutrition:(I)" in line and pending["nutrition"] is None:
            pending["nutrition"] = parse_int(prev)
        elif "saturationModifier:(F)" in line and pending["sat"] is None:
            pending["sat"] = parse_float(prev)
        elif "alwaysEdible:" in line:
            pending["always"] = True
        elif "invokedynamic" in line and "Supplier" in line:
            im = re.search(r"InvokeDynamic #(\d+)", line)
            if im:
                lname = bmap.get(int(im.group(1)))
                pending["fx"].append({"fx": lam_eff.get(lname), "chance": 1.0})
        elif "effect:(Ljava/util/function/Supplier;F)" in line:
            if pending["fx"]:
                pending["fx"][-1]["chance"] = parse_float(prev) or 1.0
        prev = line
    return result

def fmt_effects(effects):
    out = []
    for e in effects:
        fx = e.get("fx") or {}
        holder = fx.get("holder", "?")
        if holder.startswith("minecraft:"):
            cn = VANILLA_EFFECT_CN.get(holder.split(":", 1)[1], holder.split(":", 1)[1])
        elif holder in ("farmersdelight:nourishment", "nourishment"):
            cn = "营养"
        elif holder in ("farmersdelight:comfort", "comfort"):
            cn = "舒适"
        else:
            cn = KC_EFFECT_CN.get(holder, holder)
        if holder == "?" or holder is None:
            cn = "?"
        lvl = fx.get("amplifier", 0) + 1
        dur = fx.get("duration")
        dur_s = ""
        if dur:
            mins, secs = dur // 1200, (dur % 1200) // 20
            dur_s = f"{mins}分" + (f"{secs}秒" if secs else "") if mins else f"{secs}秒"
        chance = e.get("chance", 1.0)
        ch_s = "" if chance >= 1.0 else f"({int(chance*100)}%)"
        s = f"{cn}{lvl}" if lvl > 1 else cn
        if dur_s:
            s += f" {dur_s}"
        if ch_s:
            s += ch_s
        out.append(s)
    return "、".join(out) if out else ""

def kc_field_to_item(name):
    s = name.lower()
    for suf in ("_block", "_item"):
        if s.endswith(suf):
            return s[: -len(suf)]
    return s

FD_FIELDS = {
    "CABBAGE": "cabbage", "TOMATO": "tomato", "ONION": "onion", "APPLE_CIDER": "apple_cider",
    "FRIED_EGG": "fried_egg", "TOMATO_SAUCE": "tomato_sauce", "WHEAT_DOUGH": "wheat_dough",
    "RAW_PASTA": "raw_pasta", "PIE_CRUST": "pie_crust", "PUMPKIN_SLICE": "pumpkin_slice",
    "CABBAGE_LEAF": "cabbage_leaf", "MINCED_BEEF": "minced_beef", "BEEF_PATTY": "beef_patty",
    "CHICKEN_CUTS": "chicken_cuts", "COOKED_CHICKEN_CUTS": "cooked_chicken_cuts",
    "BACON": "bacon", "COOKED_BACON": "cooked_bacon", "COD_SLICE": "cod_slice",
    "COOKED_COD_SLICE": "cooked_cod_slice", "SALMON_SLICE": "salmon_slice",
    "COOKED_SALMON_SLICE": "cooked_salmon_slice", "MUTTON_CHOPS": "mutton_chops",
    "COOKED_MUTTON_CHOPS": "cooked_mutton_chops", "HAM": "ham", "SMOKED_HAM": "smoked_ham",
    "POPSICLE": "popsicle", "COOKIES": "cookies", "CAKE_SLICE": "cake_slice",
    "PIE_SLICE": "pie_slice", "FRUIT_SALAD": "fruit_salad", "GLOW_BERRY_CUSTARD": "glow_berry_custard",
    "MIXED_SALAD": "mixed_salad", "NETHER_SALAD": "nether_salad", "BARBECUE_STICK": "barbecue_stick",
    "EGG_SANDWICH": "egg_sandwich", "CHICKEN_SANDWICH": "chicken_sandwich", "HAMBURGER": "hamburger",
    "BACON_SANDWICH": "bacon_sandwich", "MUTTON_WRAP": "mutton_wrap", "DUMPLINGS": "dumplings",
    "STUFFED_POTATO": "stuffed_potato", "CABBAGE_ROLLS": "cabbage_rolls", "SALMON_ROLL": "salmon_roll",
    "COD_ROLL": "cod_roll", "KELP_ROLL": "kelp_roll", "KELP_ROLL_SLICE": "kelp_roll_slice",
    "COOKED_RICE": "cooked_rice", "BONE_BROTH": "bone_broth", "BEEF_STEW": "beef_stew",
    "VEGETABLE_SOUP": "vegetable_soup", "FISH_STEW": "fish_stew", "ONION_SOUP": "onion_soup",
    "CHICKEN_SOUP": "chicken_soup", "FRIED_RICE": "fried_rice", "PUMPKIN_SOUP": "pumpkin_soup",
    "BAKED_COD_STEW": "baked_cod_stew", "NOODLE_SOUP": "noodle_soup", "BACON_AND_EGGS": "bacon_and_eggs",
    "RATATOUILLE": "ratatouille", "STEAK_AND_POTATOES": "steak_and_potatoes",
    "PASTA_WITH_MEATBALLS": "pasta_with_meatballs", "PASTA_WITH_MUTTON_CHOP": "pasta_with_mutton_chop",
    "MUSHROOM_RICE": "mushroom_rice", "ROASTED_MUTTON_CHOPS": "roasted_mutton_chops",
    "VEGETABLE_NOODLES": "vegetable_noodles", "SQUID_INK_PASTA": "squid_ink_pasta",
    "GRILLED_SALMON": "grilled_salmon", "ROAST_CHICKEN": "roast_chicken", "STUFFED_PUMPKIN": "stuffed_pumpkin",
    "HONEY_GLAZED_HAM": "honey_glazed_ham", "SHEPHERDS_PIE": "shepherds_pie", "GLEAMING_SALAD": "gleaming_salad",
    "DOG_FOOD": "dog_food",
}

if __name__ == "__main__":
    fd_food = parse_food_class(os.path.join(JARROOT, "fd_food/vectorwing/farmersdelight/common/FoodValues.class"), FD_FIELDS)
    kc_cls = os.path.join(JARROOT, "kc/com/github/ysbbbbbb/kaleidoscopecookery/init/ModFoods.class")
    kc_fields = {}
    for fname in re.findall(r"public static final net\.minecraft\.world\.food\.FoodProperties ([A-Z_0-9]+);",
                            javap_text(kc_cls)):
        kc_fields[fname] = "kaleidoscope_cookery:" + kc_field_to_item(fname)
    kc_food = parse_food_class(kc_cls, kc_fields)
    os.makedirs(WORK, exist_ok=True)
    json.dump({"farmersdelight": fd_food, "kaleidoscope_cookery": kc_food},
              open(os.path.join(WORK, "food_props.json"), "w"), ensure_ascii=False, indent=1)
    print("FD:", len(fd_food), "条目")
    for k in ["onion", "chicken_soup", "apple_cider", "honey_glazed_ham", "dog_food", "glow_berry_custard", "beef_stew"]:
        v = fd_food.get(k)
        if v:
            print(f"  {k}: 饥{v['nutrition']} 饱modifier{v['sat_mod']} 效果:[{fmt_effects(v['effects'])}]")
    print("KC:", len(kc_food), "条目")
    for k in ["kaleidoscope_cookery:lettuce", "kaleidoscope_cookery:tomato", "kaleidoscope_cookery:baozi",
              "kaleidoscope_cookery:dongpo_pork", "kaleidoscope_cookery:chili"]:
        v = kc_food.get(k)
        if v:
            print(f"  {k}: 饥{v['nutrition']} 饱modifier{v['sat_mod']} 效果:[{fmt_effects(v['effects'])}]")
