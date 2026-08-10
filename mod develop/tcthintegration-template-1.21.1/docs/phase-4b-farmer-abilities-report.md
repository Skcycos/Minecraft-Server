# 阶段 4B 交付报告 — tcth:farmer 农夫四路线能力树

- **日期**: 2026-08-10
- **基线**: 阶段 7F 已通过(115 suites 前基线 110 suites / 867 tests);TCTH Integration 0.2.6
- **验证方式**: 服务器实际 JAR javap 审计 + 单元测试 + `clean build`;**不部署、不启动服务器、不烟雾、不在线测试**
- **结论**: **BUILD PASS(115 suites / 913 tests / 0 failures);PLAYER LIVE NOT TESTED(四路线效果均为运行时行为,需后续在线玩家验证)**
- **勘误(2026-08-11,阶段 4C)**:耕作路线**原设计为数据驱动(`arc:on_hurt_item` + `arc:cancel_action`)不可行**——4C 在线验收发现 NeoForge 21.1.247 的 ItemStack patch 将真实耐久逻辑移入 `hurtAndBreak(ServerLevel, LivingEntity, Consumer)`,Arc 9.0.0 注入的 ServerPlayer 版重载从未被调用,`arc:on_hurt_item` 在此环境永不触发(刀工同机制同样失效)。耕作已改为 **Java 驱动 mixin**(`ItemStackDurabilityMixin`,注入 LivingEntity 版,概率 10/20/35),3 个耕作 arc 文件删除;在线实测 10%/26%/34% 通过。详见 `docs/phase-4c-farmer-abilities-online-report.md` 第二节/第三节。

---

## 一、API 审计(javap 权威证据)

以服务器实际 JAR 反编译为准。审计对象:`[Arc库]arc-9.0.0-neoforge.jar`、`[职业+]jobsplus-9.0.0-neoforge.jar`、FarmersDelight 等。

### 1.1 锄类工具标签

- 服务器全部 mods/libraries JAR 中 **不存在 `#c:tools/hoes`** 标签(仅有 `#c:tools/knife`、`#c:tools/wrench` 等,由 Farmer's Delight / FramedBlocks / Scorched Guns 分别提供)。
- 原版 **`#minecraft:hoes`** 标签存在,并被 collectors_caravan / Scorched Guns / caverns_and_chasms 以追加方式扩展(琥珀锄、anthralite_hoe、铜锄系/银锄/暗金锄)。
- **结论:耕作路线工具标签采用 `#minecraft:hoes`**(原版标签 + 模组追加,覆盖全部真实锄类;不复制、不修复工具)。

### 1.2 耐久损耗入口(`arc:on_hurt_item`)

- `com.daqem.arc.mixin.MixinItemStack.hurtAndBreak` @Inject 整个 `ItemStack.hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer<Item>, CallbackInfo)`:
  1. 玩家为 `ArcServerPlayer` 且非创造(`hasInfiniteMaterials` false)且物品可损伤;
  2. 在**副本**上执行 `EnchantmentHelper.processDurabilityChange` 与 `setDamageValue`(仅模拟,不影响原物品);
  3. 调用 `PlayerEvents.onPlayerHurtItem` → 触发 `arc:on_hurt_item` action;
  4. 返回 `ActionResult.shouldCancelAction()` 为真 → `ci.cancel()` → **原方法体跳过,原物品未损伤**。
- **结论:`arc:on_hurt_item` + `arc:cancel_action` 是权威的耐久免除入口**(chef 刀工路线同机制,已在 7D.1 数据验证);耕作路线复用。

### 1.3 畜牧事件源(Arc 内置)

- `arc:on_breed_animal`(BreedAnimalAction)、`arc:on_tame_animal`(TameAnimalAction)为 Arc 原生事件类型。
- 剪羊毛无独立事件类型,沿用现有 tcth-farmer `shear_sheep.json` 的 `arc:on_interact_entity` + 条件组(`minecraft:sheep` + `arc:ready_for_shearing` + 主/副手剪刀)。
- 三个事件均只由真实玩家成功操作触发(Arc 触发语义,失败操作/机械路径不触发)。

## 二、实现

### 2.1 四路线 12 节点

| 路线 | 节点(等级) | 效果 | 驱动方式 |
|---|---|---|---|
| 耕作 TILLING | tilling_basic(5)/adept(20)/expert(45) | 锄耐久免损 10%/20%/35% | **Java 驱动**(`ItemStackDurabilityMixin` 注入 NeoForge 运行时 `hurtAndBreak(LivingEntity)` 重载;4C 勘误:原数据驱动方案因 Arc on_hurt_item 失效已废弃) |
| 丰收 HARVEST | harvest_basic(10)/adept(30)/expert(60) | 急迫 I 5s / 急迫I+速度I 8s / 急迫I 12s+速度I 12s | **Java 驱动**(`CropHarvestedEvent` → `FarmerAbilityModule.onCropHarvested`),共享 10 s 冷却 |
| 畜牧 LIVESTOCK | livestock_basic(15)/adept(35)/expert(55) | 恢复I 5s / +抗性I 8s / +速度I 15s | 数据驱动(breed/tame/shear 三事件 × 3 档 = 9 个 arc 文件),共享 20 s 冷却 |
| 研修 STUDY | study_i(25)/ii(50)/iii(75) | 经验 ×1.15/×1.35/×1.60 | 数据驱动(`jobsplus:on_job_exp` + `job_exp_multiplier`) |

- 12 个 powerup 的 `parent` 链完整(首节点无 parent,II/III 指向低一档),`required_level` 精确,`price` 8/12/18。
- 每路线 `powerup_not_active` 保证最高档互斥:档 I 排除档 II+III,档 II 排除档 III,档 III 不排除(chef/brewer 同模式;JSON 测试逐一断言)。

### 2.2 新增源码

| 文件 | 说明 |
|---|---|
| `powerup/FarmerAbilityRoute.java` | 四路线枚举(节点链) |
| `powerup/FarmerPowerupTier.java` | 档位枚举(NONE/I/II/III,highestActive) |
| `powerup/FarmerPowerupAccess.java` | 可测适配层(仅 Minecraft 类型,零 Jobs+/Arc 引用) |
| `powerup/FarmerAbilityModule.java` | tier 查询(Jobs+ 公共 API,fail-closed)+ 丰收 Java 处理器 + 倍率常量 |
| `powerup/FarmerHarvestCooldown.java` | 丰收 10 s 冷却(内存,登出/停服清理,成功才 commit) |
| `powerup/FarmerLivestockCooldown.java` | 畜牧 20 s 冷却(同上) |
| `arc/condition/HoeDurabilityEnabledCondition.java` | `tcth:hoe_durability_enabled` 开关条件(fail-closed 不随 inverted 翻转) |
| `arc/condition/FarmerStudyAbilitiesEnabledCondition.java` | `tcth:farmer_study_abilities_enabled` 开关条件 |
| `arc/condition/FarmerLivestockAbilitiesEnabledCondition.java` | `tcth:farmer_livestock_abilities_enabled` 开关条件 |
| `arc/condition/FarmerLivestockCooldownCondition.java` | `tcth:farmer_livestock_cooldown` 冷却条件 |
| `arc/reward/FarmerLivestockEffectsReward.java` | `tcth:farmer_livestock_effects`(tier 1..3 效果包,成功才 commit 冷却) |

### 2.3 注册与配置

- `TcthArcRegistrar`:注册 3 条件 + 1 奖励类型,`verifyRegistrations()` 逐 id 核对(含 4 条新增 DEBUG 检查)。
- `Config`:5 个开关(`farmerAbilitiesEnabled` 总开关 + `tillingDurabilityAbilitiesEnabled` / `farmerHarvestAbilitiesEnabled` / `farmerLivestockAbilitiesEnabled` / `farmerStudyAbilitiesEnabled`)+ 2 个冷却时长(`farmerHarvestCooldownTicks`=200 / `farmerLivestockCooldownTicks`=400)。
- 配置读取失败等高频路径的日志节流**统一为 60 秒**(三个开关条件 `WARN_THROTTLE_NS` = 60 s,`FarmerAbilityModule` 查询/处理器节流同为 60 s);fail-closed 语义不受 inverted 翻转。
- `JobsPlusCompatModule`:`FarmerAbilityModule.init` + 生命周期注册(Jobs+ 缺失时不加载实现类)。
- 语言:`zh_cn` 34 key + `en_us` 34 key(**每种语言 34 个,两种语言合计 68**):12 节点 × 2(name/description)= 24 个能力名称/描述键已逐节点验证,另有 4 条件 × 2(name/desc)与 1 奖励 × 2 共 10 键。

### 2.4 数据(presets + 服务器部署副本)

- `docs/presets/tcth-farmer/data/tcth/jobsplus/powerups/farmer/`:12 个节点 JSON。
- `docs/presets/tcth-farmer/data/tcth/arc/farmer/powerup/`:12 个 arc 文件(畜牧 9 + 研修 3;耕作/丰收均为 Java 驱动无 arc 文件——4C 勘误后耕作 arc 3 个已删除)。
- 已同步 `Server/global_packs/required_data/tcth-farmer/`(**已同步但尚未加载**——数据包文件已复制到服务器目录,服务器未启动,未经过加载验证;presets 与部署副本逐字节一致由测试断言)。
- **JAR 未部署**:本阶段 BUILD-only,`Server/mods/` 仍为 0.2.6,未构建/未部署 4B 代码。

## 三、测试覆盖(新增 46 tests)

| 套件 | 覆盖 |
|---|---|
| `FarmerAbilityModuleTest`(20) | tier 查询(NONE/非法/路由独立/异常隔离)、急迫/速度 ticks 常量、真实收获 II 档双效果、automated 负例、无档无效果不 commit、10 s 冷却窗口内阻断/过期恢复、总开关/路线开关、异常 fail-closed、研修倍率常量 |
| `FarmerCooldownsTest`(6) | 两个冷却的窗口边界(200/400 tick)、按玩家隔离、clearPlayer、clearAll |
| `FarmerLivestockEffectsRewardTest`(6) | tier 1/2/3 效果包精确(100/160/300 ticks,等级 I)、失败不 commit、非 ServerPlayer 无效果、非法 tier 构造拒绝 |
| `FarmerAbilityConditionTest`(10) | 三个开关条件的四门控组合、**异常 fail-closed 且 inverted 不翻转**、冷却条件通过/阻断/翻转/非 ServerPlayer、网络序列化 inverted 对称、**节流 60 秒常量断言(反射)、节流窗口内连续失败保持 fail-closed(100 次)** |
| `FarmerAbilityTreePresetTest`(14) | 12 节点完整、required_level/parent/price/图标、三档互斥(I 排除 II+III 等)、耕作 cancel 10/20/35 + `#minecraft:hoes`、畜牧三事件 × tier 包 + 冷却条件、剪羊毛条件组、研修倍率 + 单奖励、**presets 与服务器部署副本逐字节一致** |

## 四、BUILD PASS

`./gradlew clean build`:115 suites / 913 tests / 0 failures / 0 errors / 0 skipped(7F 基线 867 → 913,新增 46 farmer 测试;全部真实重跑)。

## 五、未验证项(DEFERRED)

1. 在线验证四路线效果与冷却(需真人玩家:锄耐久免损概率、收获急迫/速度、畜牧三事件效果、研修倍率)。
2. 剪羊毛组合条件(`arc:on_interact_entity` + sheep + 剪刀)需在线确认实际交互语义与 Arc 数据加载。
3. 耕作 35% 免损(档 III)概率的统计显著性抽样未做(单元测试仅验证数据配置与入口机制)。

## 六、说明与边界

- 不增加作物、动物产物、金币或第二套经验;研修倍率只作用于既有 `jobsplus:job_exp` 结算。
- 冷却按玩家隔离、内存态、登出/停服清理,不写 playerdata。
- Jobs+/Arc 缺失时不解析实现类;公共 API(`FarmerPowerupAccess`)零第三方引用。
- 本阶段不部署、不启动服务器、不烟雾、不 commit/push。
