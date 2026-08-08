import json, os

with open('temp_mynethers_en_us.json', 'r', encoding='utf-8') as f:
    en = json.load(f)

with open('temp_mynethers_zh_cn.json', 'r', encoding='utf-8') as f:
    zh = json.load(f)

# Missing keys translations
missing_translations = {
    "block.mynethersdelight.blazier.too_cold": "火焰燃烧得不够炽热。",
    "block.mynethersdelight.blazier.too_hot": "火焰太过猛烈。",
    "block.mynethersdelight.blazier_block": "烈焰炉",
    "block.mynethersdelight.golden_trophy": "黄金奖杯",
    "item.minersdelight.egg_soup_cup": "蛋花汤杯",
    "item.minersdelight.rock_soup_cup": "石头汤杯",
    "item.minersdelight.spicy_hoglin_stew_cup": "辣味疣猪炖肉杯",
    "item.minersdelight.spicy_noodle_soup_cup": "辣味面条汤杯",
    "item.minersdelight.strider_stew_cup": "炽足兽炖肉杯",
    "item.mynethersdelight.pepper_powder": "辣椒粉",
    "mynethersdelight.configuration.blazierCookingTimeMultiplier": "烈焰炉烹饪时间倍率",
    "mynethersdelight.configuration.blazierCookingTimeMultiplier.tooltip": "在每种烈焰炉模式下乘以烹饪时间，同时保持原始 6 / 3 / 1 / 6 的时间比例。数值越大，耗时越长。",
    "mynethersdelight.configuration.crafting": "合成",
    "mynethersdelight.configuration.crafting.button": "合成",
    "mynethersdelight.configuration.crafting.tooltip": "配置可合成功能和烈焰炉行为。",
    "mynethersdelight.configuration.enableBlazier": "启用烈焰炉",
    "mynethersdelight.configuration.enableBlazier.tooltip": "烈焰炉总开关。禁用后将隐藏创造模式标签页中的物品和配方，并停止其行为。",
    "mynethersdelight.configuration.enableFrogMagmaCakeBehavior": "启用岩浆蛋糕行为",
    "mynethersdelight.configuration.enableFrogMagmaCakeBehavior.tooltip": "允许青蛙寻找岩浆蛋糕和岩浆蛋糕片，并让玩家可以直接喂食蛋糕片给青蛙。",
    "mynethersdelight.configuration.enablePiglinFoodTrades": "启用猪灵食物交易",
    "mynethersdelight.configuration.enablePiglinFoodTrades.tooltip": "允许猪灵以物易物交换此模组的食物物品和炽足兽肉块。特殊狩猎相关交易不受影响。",
    "mynethersdelight.configuration.enableResurgentSoilPropagation": "启用复苏泥土传播",
    "mynethersdelight.configuration.enableResurgentSoilPropagation.tooltip": "允许复苏泥土和复苏泥土耕地传播附近植物。",
    "mynethersdelight.configuration.enableStoneCabinets": "启用石质橱柜",
    "mynethersdelight.configuration.enableStoneCabinets.tooltip": "启用下界砖、红色下界砖和石砖橱柜。禁用后将隐藏创造模式标签页中的物品和配方。",
    "mynethersdelight.configuration.farming": "农业",
    "mynethersdelight.configuration.farming.button": "农业",
    "mynethersdelight.configuration.farming.tooltip": "配置复苏泥土和复苏泥土耕地行为。",
    "mynethersdelight.configuration.generatePowderyCane": "生成粉状甘蔗",
    "mynethersdelight.configuration.generatePowderyCane.tooltip": "控制粉状甘蔗丛是否在绯红森林中自然生成。",
    "mynethersdelight.configuration.piglinFoodTradeChance": "猪灵食物交易几率",
    "mynethersdelight.configuration.piglinFoodTradeChance.tooltip": "猪灵以物易物时给予此模组食物或炽足兽肉块的几率。使用小数百分比：0.25 表示 25%。",
    "mynethersdelight.configuration.resurgentFarmlandHeatSearchRadius": "耕地热源搜索半径",
    "mynethersdelight.configuration.resurgentFarmlandHeatSearchRadius.tooltip": "用于搜索有效热源的水平半径。垂直范围为此值的一半。设为 0 则禁用热源湿润。",
    "mynethersdelight.configuration.resurgentSoilGrowthRange": "复苏泥土生长范围",
    "mynethersdelight.configuration.resurgentSoilGrowthRange.tooltip": "复苏泥土和复苏泥土耕地在施加生长效果时跟随连接植物的最大垂直距离。",
    "mynethersdelight.configuration.resurgentSoilTickMultiplier": "复苏泥土刻倍率",
    "mynethersdelight.configuration.resurgentSoilTickMultiplier.tooltip": "乘以复苏泥土和复苏泥土耕地进行的生长、转化和传播尝试次数。耕地湿润和干燥不受影响。",
    "mynethersdelight.configuration.section.mynethersdelight.common.toml": "通用设置",
    "mynethersdelight.configuration.section.mynethersdelight.common.toml.title": "通用设置",
    "mynethersdelight.configuration.settings": "通用",
    "mynethersdelight.configuration.settings.button": "通用",
    "mynethersdelight.configuration.settings.tooltip": "配置通用游戏设置。",
    "mynethersdelight.configuration.title": "下界乐事 - 设置",
    "mynethersdelight.configuration.world": "世界",
    "mynethersdelight.configuration.world.button": "世界",
    "mynethersdelight.configuration.world.tooltip": "配置世界生成。",
    "tooltip.mynethersdelight.blazier.heat": "热量：%s",
    "tooltip.mynethersdelight.blazier.heat.baking": "烈焰",
    "tooltip.mynethersdelight.blazier.heat.campfire": "明火",
    "tooltip.mynethersdelight.blazier.heat.extinguished": "炉底",
    "tooltip.mynethersdelight.blazier.heat.smelting": "白热",
    "tooltip.mynethersdelight.blazier.heat.smoking": "余烬",
}

# Merge: existing zh_cn + missing translations
merged = {**zh, **missing_translations}

print(f"英文键数: {len(en)}")
print(f"原有中文键数: {len(zh)}")
print(f"新增翻译键数: {len(missing_translations)}")
print(f"最终中文键数: {len(merged)}")
print(f"缺失键数: {len([k for k in en if k not in merged])}")

# Save to resource pack
output_path = r"D:/Minecraft_III/Minecraft-Server/食韵筑家专用材质包v1.21.1/assets/mynethersdelight/lang/zh_cn.json"
os.makedirs(os.path.dirname(output_path), exist_ok=True)
with open(output_path, 'w', encoding='utf-8') as f:
    json.dump(merged, f, ensure_ascii=False, indent=0)
print(f"\n文件已保存: {output_path}")

# Verify JSON
with open(output_path, 'r', encoding='utf-8') as f:
    verify = json.load(f)
print(f"JSON 有效: {len(verify)} 个键")

# Cleanup
os.remove('temp_mynethers_en_us.json')
os.remove('temp_mynethers_zh_cn.json')
print("临时文件已清理")
