import json, os

with open('temp_jobsplus_en_us.json', 'r', encoding='utf-8') as f:
    en = json.load(f)

t = {}

# Batch 1: Commands, GUI
t["jobsplus.command.does_not_have_job"] = "%s 没有职业 %s。"
t["jobsplus.command.arguments.enum.invalid"] = "枚举常量必须是 %s 之一，找到 %s"
t["jobsplus.command.set.level.success"] = "将玩家 %3$s 的职业 %1$s 等级设为 %2$d。"
t["jobsplus.command.set.level.success_new_job"] = "添加新职业 %1$s 并将玩家 %3$s 的等级设为 %2$d。"
t["jobsplus.command.set.level.cannot_add_job"] = "无法将职业 %1$s 添加到玩家 %2$s。"
t["jobsplus.command.set.level.removed_job"] = "已从 %2$s 移除职业 %1$s。"
t["jobsplus.command.set.level.does_not_have_job"] = "等级不能为 0，因为玩家没有该职业。"
t["jobsplus.command.set.level.cannot_be_higher_than_max"] = "等级不能超过 %d。"
t["jobsplus.command.set.level.invalid_target"] = "目标玩家无效。"
t["jobsplus.command.set.experience.success"] = "成功将玩家 %3$s 的职业 %1$s 经验设为 %2$d。"
t["jobsplus.command.set.experience.already_max_level"] = "该职业已达到最高等级。"
t["jobsplus.command.set.experience.experience_too_high"] = "经验不能超过当前等级的最大经验 (%d)。"
t["jobsplus.command.set.coins.success"] = "成功将玩家 %2$s 的硬币设为 %1$d。"
t["jobsplus.command.set.powerup.success"] = "将职业 %2$s 的能力 %1$s 设为 %3$s。"
t["jobsplus.command.set.powerup.success_remove"] = "成功从职业 %2$s 移除能力 %1$s。"
t["jobsplus.command.set.powerup.not_available_for_job"] = "能力 %1$s 对职业 %2$s 不可用。"
t["jobsplus.command.set.powerup.success_clear"] = "成功清除职业 %2$s 的所有能力。"
t["jobsplus.gui.title.jobs"] = "职业"
t["jobsplus.gui.title.powerups"] = "能力"
t["jobsplus.gui.title.confirmation"] = "确认"
t["jobsplus.gui.tab.left.all"] = "全部职业"
t["jobsplus.gui.tab.left.performing"] = "正在从事"
t["jobsplus.gui.tab.left.not_performing"] = "未从事"
t["jobsplus.gui.tab.right.info"] = "职业信息"
t["jobsplus.gui.tab.right.crafting"] = "物品限制"
t["jobsplus.gui.tab.right.power_ups"] = "能力"
t["jobsplus.gui.tab.right.exp"] = "如何获取经验（点击查看）"
t["jobsplus.gui.tab.side.toggle_prefix"] = "切换前缀"
t["jobsplus.gui.tab.side.toggle_boss_bar"] = "切换Boss血条"
t["jobsplus.gui.active"] = "活跃："
t["jobsplus.gui.jobs"] = "职业"
t["jobsplus.gui.exp"] = "经验：%s"
t["jobsplus.gui.level"] = "等级：%s"
t["jobsplus.gui.coins"] = "硬币"
t["jobsplus.gui.coins.top"] = "硬币：%s"
t["jobsplus.gui.price"] = "价格：%s %s"
t["jobsplus.gui.price.coins"] = "硬币"
t["jobsplus.gui.want_this_job"] = "想要这个职业？"
t["jobsplus.gui.want_this_job.price"] = "想要这个职业？价格：%s %s"
t["jobsplus.gui.job.start"] = "开始从事此职业。"
t["jobsplus.gui.job.start.description.free"] = "确定要开始从事 %s 职业？"
t["jobsplus.gui.job.start.description.not_free"] = "确定要花费 %s 硬币开始从事 %s 职业？"
t["jobsplus.gui.job.powerup.start"] = "购买能力。"
t["jobsplus.gui.job.powerup.start.description"] = "确定要花费 %s 硬币购买能力 %s？"
t["jobsplus.gui.job.powerup.required_level"] = "所需等级：%s"
t["jobsplus.gui.job.powerup.price"] = "价格：%s 硬币"
t["jobsplus.gui.job.stop"] = "停止从事此职业。"
t["jobsplus.gui.restrictions"] = "物品限制"
t["jobsplus.gui.powerups.powerups"] = "能力"
t["jobsplus.gui.powerups.open_menu"] = "打开能力菜单"
t["jobsplus.gui.powerups.available"] = "可用：%s"
t["jobsplus.gui.powerups.active"] = "已激活：%s"
t["jobsplus.gui.powerups.inactive"] = "未激活：%s"
t["jobsplus.gui.powerups.price"] = "每个 %s 硬币"
t["jobsplus.gui.no_job_selected.info.1"] = ""
t["jobsplus.gui.no_job_selected.info.2"] = "选择一个职业查看更多信息。"
t["jobsplus.gui.no_job_selected.info.3"] = ""
t["jobsplus.gui.no_job_selected.info.4"] = ""
t["jobsplus.gui.no_job_selected.info.5"] = ""
t["jobsplus.gui.no_job_selected.crafting.1"] = ""
t["jobsplus.gui.no_job_selected.crafting.2"] = "选择一个职业查看"
t["jobsplus.gui.no_job_selected.crafting.3"] = "其合成配方。"
t["jobsplus.gui.no_job_selected.crafting.4"] = ""
t["jobsplus.gui.no_job_selected.crafting.5"] = ""
t["jobsplus.gui.no_job_selected.powerups.1"] = ""
t["jobsplus.gui.no_job_selected.powerups.2"] = "选择一个职业查看"
t["jobsplus.gui.no_job_selected.powerups.3"] = "其能力。"
t["jobsplus.gui.no_job_selected.powerups.4"] = ""
t["jobsplus.gui.no_job_selected.powerups.5"] = ""
t["jobsplus.gui.no_job_selected.how_to_get_exp.1"] = ""
t["jobsplus.gui.no_job_selected.how_to_get_exp.2"] = "选择一个职业查看"
t["jobsplus.gui.no_job_selected.how_to_get_exp.3"] = "获取职业经验的"
t["jobsplus.gui.no_job_selected.how_to_get_exp.4"] = "可能方式。"
t["jobsplus.gui.no_job_selected.how_to_get_exp.5"] = ""
t["jobsplus.gui.loading"] = "加载中..."
t["jobsplus.gui.cancel"] = "取消"
t["jobsplus.gui.confirm"] = "确认"
t["jobsplus.gui.confirmation.yes"] = "是"
t["jobsplus.gui.confirmation.cancel"] = "取消"
t["jobsplus.gui.confirmation.back"] = "返回"
t["jobsplus.gui.confirmation.start_job_free"] = "确定要开始从事此职业？"
t["jobsplus.gui.confirmation.start_job_paid"] = "确定要花费 %s 硬币开始从事此职业？"
t["jobsplus.gui.confirmation.error.not_enough_coins_start"] = "你没有足够的硬币 (%s) 来开始从事此职业。"
t["jobsplus.gui.confirmation.stop_job_free"] = "确定要停止从事此职业？"
t["jobsplus.gui.confirmation.buy_power_up"] = "确定要花费 %s 硬币购买此能力？"
t["jobsplus.gui.confirmation.error.not_enough_coins_powerup"] = "你没有足够的硬币 (%s) 购买此能力。"
t["jobsplus.gui.confirmation.error.job_not_enabled"] = "你没有在从事此职业。"
t["jobsplus.gui.click_for_details"] = "（点击查看详情）"
t["jobsplus.gui.restriction.required_level"] = "所需等级："
t["jobsplus.gui.restriction.no_required_level"] = "无所需等级"
t["jobsplus.gui.restriction.restriction_types"] = "限制类型："
t["jobsplus.gui.restriction.no_restrictions"] = "无限制"
t["jobsplus.gui.restriction.crafting"] = "合成"
t["jobsplus.gui.restriction.smelting"] = "熔炼"
t["jobsplus.gui.restriction.brewing"] = "酿造"
t["jobsplus.gui.restriction.enchanting"] = "附魔"
t["jobsplus.gui.restriction.repairing"] = "修复"
t["jobsplus.gui.restriction.use_right_click"] = "右键使用"
t["jobsplus.gui.restriction.break_block"] = "破坏方块"
t["jobsplus.gui.restriction.break_block_with_item"] = "用物品破坏方块"
t["jobsplus.gui.restriction.place_block"] = "放置方块"
t["jobsplus.gui.restriction.hurt_entity"] = "用物品伤害实体"
t["jobsplus.gui.item_restriction.required_level"] = "所需等级：%s"
t["jobsplus.gui.item_restriction.no_restrictions"] = "未找到限制。"
t["jobsplus.job.none"] = "无"

# Batch 2: Actions
actions = [
    ('on_place_place', '放置方块时'), ('on_swim', '游泳时'), ('on_swim_start', '开始游泳时'),
    ('on_swim_stop', '停止游泳时'), ('on_walk', '行走时'), ('on_walk_start', '开始行走时'),
    ('on_walk_stop', '停止行走时'), ('on_sprint', '冲刺时'), ('on_sprint_start', '开始冲刺时'),
    ('on_sprint_stop', '停止冲刺时'), ('on_crouch', '潜行时'), ('on_crouch_start', '开始潜行时'),
    ('on_crouch_stop', '停止潜行时'), ('on_elytra_fly', '鞘翅飞行时'), ('on_elytra_fly_start', '开始鞘翅飞行时'),
    ('on_elytra_fly_stop', '停止鞘翅飞行时'), ('on_place_block', '放置方块时'), ('on_break_block', '破坏方块时'),
    ('on_interact_block', '与方块交互时'), ('on_job_exp', '获得职业经验时'), ('on_job_level_up', '职业升级时'),
    ('on_death', '死亡时'), ('on_get_hurt', '受伤时'), ('on_kill_entity', '击杀实体时'),
    ('on_hurt_entity', '伤害实体时'), ('on_craft_item', '合成物品时'), ('on_drop_item', '丢弃物品时'),
    ('on_use_item', '使用物品时'), ('on_advancement', '获得进度时'), ('on_eat', '进食时'),
    ('on_drink', '饮水时'), ('on_throw_item', '投掷物品时'), ('on_shoot_projectile', '发射弹射物时'),
    ('on_brew_potion', '酿造药水时'), ('on_effect_added', '获得效果时'), ('on_smelt_item', '熔炼物品时'),
    ('on_enchant_item', '附魔物品时'), ('on_plant_crop', '种植作物时'), ('on_harvest_crop', '收获作物时'),
    ('on_tame_animal', '驯服动物时'), ('on_interact_entity', '与实体交互时'), ('on_breed_animal', '繁殖动物时'),
    ('on_fished_up_item', '钓起物品时'), ('on_strip_log', '剥皮时'), ('on_grind_item', '研磨时'),
    ('on_use_anvil', '使用铁砧时'),
]
for k, v in actions:
    t[f"jobsplus.action.{k}"] = v

# Batch 3: Restrictions, inventory, keybindings, job names
t["jobsplus.restrictions"] = "限制："
inv = [('cant_craft','合成'),('cant_smelt','熔炼'),('cant_brew','酿造'),('cant_enchant','附魔'),
       ('cant_repair','修复'),('cant_use_item','使用'),('cant_break_block','破坏'),
       ('cant_item_break_block','用...破坏'),('cant_place_block','放置'),('cant_hurt_entity','攻击')]
for k, v in inv:
    t[f"jobsplus.inventory.{k}"] = v
t["jobsplus.inventory.bypass"] = "因创造模式绕过限制"
t["key.categories.jobsplus"] = "Jobs+"
t["key.jobsplus.open_menu"] = "打开 Jobs+ 界面"
t["jobsplus.job.exp.gain"] = "+%s %s"
t["jobsplus.job.level_up"] = "%1$s 以 %3$s 身份达到了 %2$s 级！"
t["jobsplus.job.level_up.toast"] = "达到 %s 级！"
t["jobsplus.job.item_unlocked.toast"] = "解锁新物品！"

jobs = [
    ('alchemist', '炼金术士', '炼金术士是药水与酿造大师。使用炼金术士的能力让药水效果更强！'),
    ('builder', '建筑师', '建筑师是建造大师。使用建筑师的能力减少坠落伤害。对建造高层建筑很有用！'),
    ('digger', '挖掘者', '挖掘者是挖掘泥土、沙子、砂石的大师。使用挖掘者的能力挖得更快并获得更多更好的掉落！'),
    ('enchanter', '附魔师', '附魔师是附魔盔甲与工具的大师。使用附魔师的能力在附魔时获得更多经验！'),
    ('farmer', '农夫', '农夫是种植作物与养殖动物的大师。使用农夫的能力从作物中获得更多掉落！'),
    ('fisherman', '渔夫', '渔夫是钓鱼大师。使用渔夫的能力将钓鱼竿当作抓钩使用！'),
    ('hunter', '猎人', '猎人狩猎动物与怪物的大师。使用猎人的能力精通你的剑与弓！'),
    ('lumberjack', '伐木工', '伐木工是砍伐木材的大师。使用伐木工的能力砍得更快并获得更多掉落！'),
    ('miner', '矿工', '矿工是开采矿石与石头的大师。使用矿工的能力挖得更快并获得更多掉落！'),
    ('smith', '铁匠', '铁匠是熔炼矿石与制作工具的大师。使用铁匠的能力让你的盔甲更耐用！'),
]
for k, name, desc in jobs:
    t[f"jobsplus.job.jobsplus.{k}.name"] = name
    t[f"jobsplus.job.jobsplus.{k}.description"] = desc

# GUI errors
t["jobsplus.gui.jobs.not_enough_coins.title"] = "硬币不足"
t["jobsplus.gui.jobs.not_enough_coins.description"] = "你没有足够的硬币执行此操作。"
t["jobsplus.gui.jobs.not_high_enough_level.title"] = "等级不足"
t["jobsplus.gui.jobs.not_high_enough_level.description"] = "你的职业等级不够高，无法执行此操作。"
t["jobsplus.gui.jobs.max_jobs_reached.title"] = "职业已达上限"
t["jobsplus.gui.jobs.max_jobs_reached.description"] = "你已达到可从事职业的最大数量。"

# Powerup roots
for k, name, desc in jobs:
    t[f"jobsplus.powerup.jobsplus.{k}.root.name"] = name
    t[f"jobsplus.powerup.jobsplus.{k}.root.description"] = desc

# Errors
t["jobsplus.error.job_not_found"] = "未找到职业 '%s'。"
t["jobsplus.error.max_jobs_reached"] = "你已达到可从事职业的最大数量。"
t["jobsplus.error.not_enough_coins"] = "你没有足够的硬币执行此操作。"
t["jobsplus.error.not_high_enough_level"] = "你的职业等级不够高，无法执行此操作。"

# Arc rewards
t["arc.reward.job_exp"] = "职业经验"
t["arc.reward.description.job_exp"] = "获得 %s 到 %s 点职业经验。"
t["arc.reward.job_exp_multiplier"] = "职业经验倍率"
t["arc.reward.description.job_exp_multiplier"] = "将职业 %s 的经验乘以 %s。"
t["arc.reward.job_coin"] = "职业硬币"
t["arc.reward.description.job_coin"] = "获得 %s 枚职业硬币。"
t["arc.action.on_job_exp"] = "职业经验"
t["arc.action.description.on_job_exp"] = "获得职业经验时触发。"
t["arc.action.on_job_level_up"] = "职业升级"
t["arc.action.description.on_job_level_up"] = "职业升级时触发。"
t["arc.condition.job_experience_percentage"] = "职业经验百分比"
t["arc.condition.description.job_experience_percentage"] = "如果职业经验百分比高于 %s。"
t["arc.condition.job_level"] = "职业等级"
t["arc.condition.description.job_level"] = "如果职业 %s 的等级高于 %s。"
t["arc.condition.powerup_not_active"] = "能力未激活"
t["arc.condition.description.powerup_not_active"] = "如果能力 %s 未激活。"
t["arc.condition.has_job"] = "拥有职业"
t["arc.condition.description.has_job"] = "如果玩家拥有职业 %s。"

# YAMLConfig
yaml_entries = [
    ('yamlconfig.jobsplus', 'Jobs+'),
    ('yamlconfig.jobsplus.jobsplus-common', '通用'),
    ('yamlconfig.jobsplus.jobsplus-common.debug', '调试'),
    ('yamlconfig.jobsplus.jobsplus-common.debug.is_debug', '启用调试'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs', '职业'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs.enable_default_jobs', '启用默认职业'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs.amount_of_free_jobs', '免费职业数量'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs.max_jobs', '最大职业数量'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs.broadcast_level_up_messages', '广播升级消息'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs.coins', '硬币'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs.coins.coins_per_level_up', '每级升级获得硬币'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs.experience', '经验'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs.experience.show_xp_in_action_bar', '在动作栏显示经验'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs.experience.xp_multiplier', '经验倍率'),
    ('yamlconfig.jobsplus.jobsplus-common.jobs.experience.use_decimal_values_for_xp', '经验使用小数值'),
]
for k, v in yaml_entries:
    t[k] = v

# === Powerups (using proper Roman numerals) ===
roman = {1:'i', 2:'ii', 3:'iii', 4:'iv', 5:'v'}

# Alchemist
for i, pct in [(1,20),(2,40),(3,60),(4,80),(5,100)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.alchemist.harmful_potion_immunity_{suf}.name"] = f"有害药水免疫 {i}"
    t[f"jobsplus.powerup.jobsplus.alchemist.harmful_potion_immunity_{suf}.description"] = f"对有害药水获得 {pct}% 免疫力。"
    t[f"jobsplus.powerup.jobsplus.alchemist.longer_potions_{suf}.name"] = f"延长药水 {i}"
    t[f"jobsplus.powerup.jobsplus.alchemist.longer_potions_{suf}.description"] = f"药水持续时间延长 {pct}%。"
    t[f"jobsplus.powerup.jobsplus.alchemist.stronger_potions_{suf}.name"] = f"强化药水 {i}"
    t[f"jobsplus.powerup.jobsplus.alchemist.stronger_potions_{suf}.description"] = f"药水有 {pct}% 几率变得更强。"

for i, mult in [(1,1.5),(2,2),(3,3)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.alchemist.job_exp_{suf}.name"] = f"职业经验 {i}"
    t[f"jobsplus.powerup.jobsplus.alchemist.job_exp_{suf}.description"] = f"职业经验乘以 {mult} 倍。"

# Builder
for i, pct in [(1,20),(2,40),(3,60)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.builder.less_fall_damage_{suf}.name"] = f"减少坠落伤害 {i}"
    t[f"jobsplus.powerup.jobsplus.builder.less_fall_damage_{suf}.description"] = f"减少 {pct}% 坠落伤害。"

# Digger
for i, pct in [(1,5),(2,10),(3,20)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.digger.double_drops_{suf}.name"] = f"双倍掉落 {i}"
    t[f"jobsplus.powerup.jobsplus.digger.double_drops_{suf}.description"] = f"挖掘时有 {pct}% 几率获得双倍掉落。"

gold_data = [(1,'1%','金锭'),(2,'3%','金锭'),(3,'5%','金锭'),(4,'0.5%','金块'),(5,'1%','金块')]
for i, chance, item in gold_data:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.digger.gold_digger_{suf}.name"] = f"黄金挖掘者 {i}"
    t[f"jobsplus.powerup.jobsplus.digger.gold_digger_{suf}.description"] = f"挖掘时有 {chance} 几率找到{item}。"

for i, pct in [(1,10),(2,20),(3,30),(4,40),(5,50)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.digger.shovel_efficiency_{suf}.name"] = f"锹效率 {i}"
    t[f"jobsplus.powerup.jobsplus.digger.shovel_efficiency_{suf}.description"] = f"锹挖掘速度提高 {pct}%。"
    t[f"jobsplus.powerup.jobsplus.digger.shovel_unbreaking_{suf}.name"] = f"锹耐久 {i}"
    t[f"jobsplus.powerup.jobsplus.digger.shovel_unbreaking_{suf}.description"] = f"锹使用时损耗降低 {pct}%。"

# Enchanter
for i, mult in [(1,1.5),(2,2),(3,2.5)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.enchanter.exp_boost_{suf}.name"] = f"经验提升 {i}"
    t[f"jobsplus.powerup.jobsplus.enchanter.exp_boost_{suf}.description"] = f"从方块和生物获得的经验乘以 {mult} 倍。"
    t[f"jobsplus.powerup.jobsplus.enchanter.job_exp_{suf}.name"] = f"职业经验 {i}"
    t[f"jobsplus.powerup.jobsplus.enchanter.job_exp_{suf}.description"] = f"职业经验乘以 {int(mult) if mult == int(mult) else mult} 倍。"

# Farmer
for i, pct in [(1,5),(2,10),(3,20)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.farmer.double_drops_{suf}.name"] = f"双倍掉落 {i}"
    t[f"jobsplus.powerup.jobsplus.farmer.double_drops_{suf}.description"] = f"收获作物时有 {pct}% 几率获得双倍掉落。"
    t[f"jobsplus.powerup.jobsplus.farmer.job_exp_{suf}.name"] = f"职业经验 {i}"
    t[f"jobsplus.powerup.jobsplus.farmer.job_exp_{suf}.description"] = f"职业经验乘以 {1.5 if i==1 else (2 if i==2 else 3)} 倍。"

# Fisherman
t["jobsplus.powerup.jobsplus.fisherman.grappling_hook_i.name"] = "抓钩 I"
t["jobsplus.powerup.jobsplus.fisherman.grappling_hook_i.description"] = "将钓鱼竿变成抓钩，可用于拉向目标位置。"
t["jobsplus.powerup.jobsplus.fisherman.grappling_hook_ii.name"] = "抓钩 II"
t["jobsplus.powerup.jobsplus.fisherman.grappling_hook_ii.description"] = "抓钩拉得更远。"
t["jobsplus.powerup.jobsplus.fisherman.grappling_hook_iii.name"] = "抓钩 III"
t["jobsplus.powerup.jobsplus.fisherman.grappling_hook_iii.description"] = "抓钩拉得更远。"
for i, mult in [(1,1.5),(2,2),(3,3)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.fisherman.job_exp_{suf}.name"] = f"职业经验 {i}"
    t[f"jobsplus.powerup.jobsplus.fisherman.job_exp_{suf}.description"] = f"职业经验乘以 {int(mult)} 倍。"

# Hunter
for i, pct in [(1,10),(2,20),(3,30),(4,40),(5,50)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.hunter.attack_speed_{suf}.name"] = f"攻击速度 {i}"
    t[f"jobsplus.powerup.jobsplus.hunter.attack_speed_{suf}.description"] = f"攻击速度提高 {pct}%。"
    t[f"jobsplus.powerup.jobsplus.hunter.bow_unbreaking_{suf}.name"] = f"弓耐久 {i}"
    t[f"jobsplus.powerup.jobsplus.hunter.bow_unbreaking_{suf}.description"] = f"弓使用时损耗降低 {pct}%。"
    t[f"jobsplus.powerup.jobsplus.hunter.sword_unbreaking_{suf}.name"] = f"剑耐久 {i}"
    t[f"jobsplus.powerup.jobsplus.hunter.sword_unbreaking_{suf}.description"] = f"剑使用时损耗降低 {pct}%。"

fire_data = [(1,'25%'),(2,'50%'),(3,'100%')]
for i, pct in fire_data:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.hunter.fire_arrows_{suf}.name"] = f"火焰箭 {i}"
    t[f"jobsplus.powerup.jobsplus.hunter.fire_arrows_{suf}.description"] = f"箭矢有 {pct} 几率点燃目标。"

multi_data = [(1,'10%','3'),(2,'25%','3'),(3,'25%','5'),(4,'50%','5')]
for i, pct, count in multi_data:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.hunter.multiple_arrows_{suf}.name"] = f"多重箭 {i}"
    t[f"jobsplus.powerup.jobsplus.hunter.multiple_arrows_{suf}.description"] = f"弓有 {pct} 几率射出 {count} 支箭。"

# Lumberjack
for i, pct in [(1,10),(2,20),(3,30),(4,40),(5,50)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.lumberjack.axe_efficiency_{suf}.name"] = f"斧效率 {i}"
    t[f"jobsplus.powerup.jobsplus.lumberjack.axe_efficiency_{suf}.description"] = f"斧砍伐速度提高 {pct}%。"
    t[f"jobsplus.powerup.jobsplus.lumberjack.axe_unbreaking_{suf}.name"] = f"斧耐久 {i}"
    t[f"jobsplus.powerup.jobsplus.lumberjack.axe_unbreaking_{suf}.description"] = f"斧使用时损耗降低 {pct}%。"
for i, pct in [(1,5),(2,10),(3,20)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.lumberjack.double_drops_{suf}.name"] = f"双倍掉落 {i}"
    t[f"jobsplus.powerup.jobsplus.lumberjack.double_drops_{suf}.description"] = f"砍伐木材时有 {pct}% 几率获得双倍掉落。"

# Miner
for i, pct in [(1,5),(2,10),(3,20)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.miner.double_drops_{suf}.name"] = f"双倍掉落 {i}"
    t[f"jobsplus.powerup.jobsplus.miner.double_drops_{suf}.description"] = f"采矿时有 {pct}% 几率获得双倍掉落。"
for i, pct in [(1,10),(2,20),(3,30),(4,40),(5,50)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.miner.pickaxe_efficiency_{suf}.name"] = f"镐效率 {i}"
    t[f"jobsplus.powerup.jobsplus.miner.pickaxe_efficiency_{suf}.description"] = f"镐挖掘速度提高 {pct}%。"
    t[f"jobsplus.powerup.jobsplus.miner.pickaxe_unbreaking_{suf}.name"] = f"镐耐久 {i}"
    t[f"jobsplus.powerup.jobsplus.miner.pickaxe_unbreaking_{suf}.description"] = f"镐使用时损耗降低 {pct}%。"

# Smith
for i, pct in [(1,10),(2,20),(3,30),(4,40),(5,50)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.smith.armor_unbreaking_{suf}.name"] = f"盔甲耐久 {i}"
    t[f"jobsplus.powerup.jobsplus.smith.armor_unbreaking_{suf}.description"] = f"盔甲受攻击时损耗降低 {pct}%。"
for i, mult in [(1,1.5),(2,2),(3,3)]:
    suf = roman[i]
    t[f"jobsplus.powerup.jobsplus.smith.job_exp_{suf}.name"] = f"职业经验 {i}"
    t[f"jobsplus.powerup.jobsplus.smith.job_exp_{suf}.description"] = f"职业经验乘以 {int(mult)} 倍。"

# Verify
missing = [k for k in en if k not in t]
extra = [k for k in t if k not in en]
print(f"English: {len(en)} keys")
print(f"Translated: {len(t)} keys")
print(f"Missing: {len(missing)}")
print(f"Extra: {len(extra)}")
if missing:
    print("MISSING:")
    for k in missing[:10]:
        print(f"  {k}")

# Save
output_path = r"D:/Minecraft_III/Minecraft-Server/食韵筑家专用材质包v1.21.1/assets/jobsplus/lang/zh_cn.json"
os.makedirs(os.path.dirname(output_path), exist_ok=True)
with open(output_path, 'w', encoding='utf-8') as f:
    json.dump(t, f, ensure_ascii=False, indent=0)
print(f"\nFile saved: {output_path}")

# Verify JSON
with open(output_path, 'r', encoding='utf-8') as f:
    verify = json.load(f)
print(f"JSON valid: {len(verify)} keys")

# Cleanup
os.remove('temp_jobsplus_en_us.json')
print("Done!")
