# tcth:chef 厨师职业预设（**不自动启用**）

本目录是 `tcth:chef` 职业的完整数据包预设，**TCTH 发布 JAR 不包含、不启用**
这些文件。当前测试服已启用本预设（`Server/world/datapacks/tcth-chef/`），
本目录用于：规划 `tcth:chef` 的职业、能力树、奖励与文本。

## 启用方式（复制整个目录）

把 `docs/presets/tcth-chef/` **整个目录**复制为数据包，两种做法均可：

1. 复制到 `Server/world/datapacks/tcth-chef/`（含 `pack.mcmeta` + `data/`），
   然后 `/datapack list` / `/reload` 启用；
2. 或只复制 `pack.mcmeta` 与 `data/` 到任意数据包目录。

`pack.mcmeta`（pack_format 48 = MC 1.21.1）是数据包必需的。

```
docs/presets/tcth-chef/
├── pack.mcmeta
└── data/tcth/
    ├── jobsplus/jobs/chef.json                   职业定义
    ├── jobsplus/powerups/chef/*.json             能力树骨架（UI 层，12 节点）
    ├── arc/chef/powerup/*.json                   能力树实际效果（12 个 Arc Action）
    ├── arc/chef/dish_cooked_common.json          基础奖励 COMMON
    ├── arc/chef/dish_cooked_t2.json              基础奖励 T2
    ├── arc/chef/dish_cooked_t3.json              基础奖励 T3
    ├── arc/chef/dish_cooked_excellent.json       品质额外奖励
    ├── dish_tiers/recipes/...                    按配方分级
    └── dish_tiers/items/...                      按物品分级
    （语言资源由 TCTH Integration 模组 assets/tcth/lang 提供）
```

## 能力树（阶段 3D：四条可并行路线）

职业保留 `tcth:chef`。四条路线**互不冲突，可同时发展**；每条路线内部
**只允许最高已激活节点生效**，低级与高级效果**不叠加、不叠乘**。

```
刀工路线              炉火路线              品鉴路线              研修路线
刀工入门 (5级)        熟悉灶火 (10级)       细品百味 (15级)       庖厨研修 I (25级)
└ 游刃有余 (20级)     └ 掌控炉温 (30级)    └ 食补调和 (35级)     └ 庖厨研修 II (50级)
  └ 庖丁解牛 (45级)     └ 炉火纯青 (60级)     └ 宴席余韵 (55级)     └ 庖厨研修 III (75级)
```

### 节点价格（powerup price，金币）

| 路线 | I | II | III |
|---|---:|---:|---:|
| 刀工 | 5 | 10 | 15 |
| 炉火 | 5 | 10 | 15 |
| 品鉴 | 8 | 12 | 18 |
| 研修 | 5 | 10 | 15 |

### 刀工路线（`arc:on_hurt_item` + `arc:cancel_action`）

目标物品：`#c:tools/knife`（本服务器由 Farmer's Delight 与 kaleidoscope_compat
提供，共 9 种厨刀；不硬编码具体 ID）。按每次实际耐久扣除判定，成功后阻止
本次完整耐久消耗（不修复旧损伤、不改最大耐久、不复制物品）。

| 节点 | required_level | 免耐久概率 |
|---|---|---:|
| 刀工入门 | 5 | 10% |
| 游刃有余 | 20 | 20% |
| 庖丁解牛 | 45 | 35% |

### 炉火路线（`arc:on_get_hurt` + `arc:damage_multiplier`）

只处理 `#minecraft:is_fire` 标签伤害（着火/火焰/岩浆/其他被标记为 IS_FIRE
的伤害），按标签判断、不按伤害来源名称字符串判断。伤害修改发生在伤害结算
入口（护甲等原版结算之前），乘数直接作用于原始伤害：

| 节点 | required_level | 火焰伤害降低 | 结算 |
|---|---|---|---:|
| 熟悉灶火 | 10 | 15% | ×0.85 |
| 掌控炉温 | 30 | 30% | ×0.70 |
| 炉火纯青 | 60 | 50% | ×0.50 |

永远不完全免疫；不取消事件；不改变伤害来源；护甲、抗性药水随后照常结算。

### 品鉴路线（`arc:on_eat` + `tcth:tasting_effects`）

触发料理：`#tcth:chef_meals`（保持既有标签语义）。只在真正完成食用时触发
（Arc `ServerPlayer.eat` 阶段；取消进食/非玩家实体不触发）。每个等级由
**一个**最高级 action 一次性给予完整效果包，绝不叠加触发低等级 action。

| 节点 | required_level | 效果包 |
|---|---|---:|
| 细品百味 | 15 | 生命恢复 I 5 秒 |
| 食补调和 | 35 | 生命恢复 I 5 秒 + 抗性提升 I 8 秒 |
| 宴席余韵 | 55 | 生命恢复 I 5 秒 + 抗性提升 I 8 秒 + 速度 I 15 秒 |

防刷冷却：**20 秒（400 tick）**，每玩家内存缓存（不写 playerdata），
三个节点共用同一冷却，仅在效果成功给予后提交，玩家登出/停服清理。
不影响 `taste_meal +1 XP`。

### 研修路线（`jobsplus:on_job_exp` + `jobsplus:job_exp_multiplier`）

| 节点 | required_level | 经验倍率 |
|---|---:|---:|
| 庖厨研修 I | 25 | 1.25 |
| 庖厨研修 II | 50 | 1.5 |
| 庖厨研修 III | 75 | 2.0 |

III 生效时不触发 II、I；II 生效时不触发 I。**最高倍率只能为 2 倍**，
不允许 1.25×1.5×2 叠乘、也不允许 1.25+1.5+2 叠加。

本阶段**不修改基础料理奖励**（COMMON 1–2 / T2 3–5 / T3 6–10 / 品质额外
+2–4 / taste_meal +1），只调整能力树倍率。

### 高等级覆盖低等级（互斥机制）

每条路线的高级节点 action 携带 `jobsplus:powerup_not_active` 条件排除
全部更高等级，加上 `jobsplus:powerup` holder 要求节点已激活（ACTIVE），
保证任意时刻每条路线至多一个 action 触发：

```text
刀工 I → 10%（不是 30%）；II → 20%；III → 35%（不是 65%）
炉火 I → 15%；II → 30%（不是 45%）；III → 50%（不是 95%）
品鉴 I → I 级效果包；II → II 级完整效果包（I action 不执行）；III 同理
研修 I → 1.25；II → 1.5；III → 2.0（任何情况下不叠乘）
```

四条不同路线允许同时生效，例如：刀工 II + 炉火 I + 品鉴 III + 研修 I。

### 总开关与路线开关（`config/tcth-common.toml`）

```toml
chefAbilitiesEnabled = true          # 总开关：关闭后四路线业务效果均停止
tastingEffectsEnabled = true         # 品鉴路线独立开关
fireResistanceAbilitiesEnabled = true # 炉火路线独立开关
knifeDurabilityAbilitiesEnabled = true # 刀工路线独立开关
tastingEffectCooldownTicks = 400     # 品鉴冷却（tick）
```

研修倍率由 Arc 数据控制，但总开关通过 preset 中附加的
`tcth:chef_abilities_enabled` 自定义条件实际生效（不只是一个文档声明）。
关闭开关**不会删除**玩家已购买节点；开关不影响职业数据加载与 Jobs+ GUI 显示。

## 奖励规则

每道料理（非自动化）至多命中**一个**基础奖励：

| 等级 | 经验 | 条件 |
|---|---|---|
| COMMON | 1–2 | `tcth:dish_tier = COMMON` 且 `tcth:automated = false` |
| T2 | 3–5 | `tcth:dish_tier = T2` 且 `tcth:automated = false` |
| T3 | 6–10 | `tcth:dish_tier = T3` 且 `tcth:automated = false` |

EXCELLENT/SUPERB 品质可**额外**命中一次品质奖励：**+2–4 经验**（同样要求
非自动化）。自动化料理不发放任何经验；同一事件绝不会同时命中两个基础等级。
本阶段不调整任何基础奖励数值。

## 职责划分与职业翻译

- **预设数据包**（本目录）负责：职业定义、能力节点、Arc Action（奖励与条件）、
  recipe/item 等级映射。数据包的 `data` 目录**不提供**客户端语言资源。
- **名称与描述**由 TCTH Integration 模组提供：模组 JAR 的
  `assets/tcth/lang/en_us.json` / `zh_cn.json` 包含
  `jobsplus.job.tcth.chef.name/.description` 与全部 12 个节点的
  `jobsplus.powerup.tcth.chef.<node>.name/.description`
  （Jobs+ 的 powerup Serializer 不读取 JSON 内嵌的 name/description；
  界面读取翻译键）。
- **客户端需要安装包含对应翻译资源的 TCTH Integration 匹配版本**，否则职业与
  能力节点的名称/描述会缺失。

## 署名与能力树的关系（重要）

料理署名（`tcth:cooking_signature` 组件）只是出锅料理的记录信息：
**不是**任何能力效果的触发条件，也**不是**奖励凭证。能力效果全部由
Jobs+/Arc 的 powerup 激活状态驱动；署名不参与品鉴/研修/炉火/刀工判定。

## 客户端要求（重要）

启用 Jobs+/Arc 联动或本 `tcth-chef` 预设时，**服务器与所有客户端都必须安装
匹配版本的 TCTH Integration**（TCTH 注册了自定义 Arc Action/Condition/
Reward/DataType 并携带翻译资源；客户端缺失会导致 Jobs+ GUI 无法解析或职业
文本缺失）。

## 约束

- 不修改、不删除 `Server/world/datapacks/shiyun_jobs`（除非执行停用步骤）。
- 不修改服务器职业/经济/悬赏/世界配置。
- 不修改真实 `world/playerdata`（无玩家数据迁移、不手工编辑 NBT）。
- 预设中所有数值均为正式设计值（阶段 3D）。
