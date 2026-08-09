# 阶段 7E 交付报告 — tcth:brewer 魔酿师四路线能力树

- **日期**: 2026-08-09
- **基线**: 阶段 7D.1 已通过(103 suites / 818 tests);TCTH Integration 0.2.3
- **验证方式**: 单元测试 + 一次 clean build;本阶段**不改 7B/7C 事件与奖励数值、不部署、不启动服务器、不烟雾、不在线验收**
- **结论**: **BUILD PASS(110 suites / 867 tests / 0 failures);PLAYER LIVE NOT TESTED(四路线效果均为运行时行为,需后续在线玩家验证)**

---

## 一、API 审计(javap 权威证据)

以服务器实际 JAR 反编译为准,源码仓库仅辅助。审计对象:`[Arc库]arc-9.0.0-neoforge.jar`、`[职业+]jobsplus-9.0.0-neoforge.jar`、`server-1.21.1-20240808.144430-srg.jar`。

### 1.1 饮用完成语义(`arc:on_drink`)

- `com.daqem.arc.event.triggers.PlayerEvents.onPlayerDrink(ArcServerPlayer, ItemStack)` 触发 `ActionType.DRINK`。
- 调用者:`com.daqem.arc.mixin.MixinItemStack.finishUsingItem` 在 `UseAnim.DRINK` 时调用(`invokestatic PlayerEvents.onPlayerDrink`)。
- `finishUsingItem` 仅在**实际完成使用**(饮用结束)时被 `LivingEntity` 调用;提前释放/取消不会到达 → **取消饮用不触发**由 Arc 机制保证。
- 对比:`MixinServerPlayer.eat()` 在 `Player.eat()` 之前调用 `onPlayerEat`(对应 `arc:on_eat`),同样仅在完成食用时触发。

### 1.2 伤害类型识别(`DamageSource`)

- `net.minecraft.world.damagesource.DamageSource` 提供 `is(ResourceKey<DamageType>)` / `is(TagKey<DamageType>)`。
- `net.minecraft.world.damagesource.DamageTypes` 常量(权威):
  - `MAGIC`(魔法直接伤害)、`INDIRECT_MAGIC`(间接魔法,如女巫药水/守卫者激光)、`WITHER`(凋零效果持续伤害)。
  - `IN_FIRE` / `ON_FIRE` / `LAVA`(火焰)、`FALL`(摔落)、`MOB_PROJECTILE`(弹丸)、`PLAYER_ATTACK`(近战)。
- **范围缩小说明(重要)**:Minecraft 1.21.1 **没有独立的 `poison` DamageType** — 毒是状态效果(MobEffect),不产生 `LivingDamageEvent`,因此**毒不在伤害减免范围内**。这是 javap 证据,非猜测。耐受路线仅覆盖可可靠识别的 `MAGIC` / `INDIRECT_MAGIC` / `WITHER`。

### 1.3 研修路线(`jobsplus:on_job_exp` + `job_exp_multiplier`)

- `com.daqem.jobsplus.integration.arc.action.actions.job.JobExpAction`(`on_job_exp` ActionType)存在。
- `com.daqem.jobsplus.integration.arc.reward.rewards.job.JobExpMultiplierReward`(`job_exp_multiplier`)存在。
- `jobsplus:powerup_not_active` 条件已用于枪客研修数据(排除高档),魔酿师完全复用该模式。

### 1.4 能力查询(Jobs+ 公共 API)

- 复用 `JobsServerPlayer.jobsplus$getJob(JobInstance.of(tcth:brewer)) → Job.getPowerupManager() → getPowerup(instance).getState() == ACTIVE`,与厨师/枪客完全同构。不读取/写入玩家 NBT。

---

## 二、四路线设计

| 路线 | 等级 | 节点 | 触发 | 效果 |
|---|---|---|---|---|
| 调饮 BREWING | 5/20/45 | brewing_basic/adept/expert | `BeveragePreparedEvent`(真实玩家、automated=false、COMMON/T2) | I 速度I 5s;II 速度I 8s+幸运I 8s;III 速度I 12s+幸运I 12s。高档覆盖低档,不叠加 |
| 品鉴 TASTING | 15/35/55 | tasting_basic/adept/expert | 饮用 `#tcth:brewer_drinks` 完成(`arc:on_drink`) | I 生命恢复I 5s;II 恢复I 5s+抗性I 8s;III 恢复I 5s+抗性I 8s+速度I 15s。共用 20s 冷却,成功发送后提交 |
| 耐受 RESISTANCE | 10/30/60 | resistance_basic/adept/expert | `LivingDamageEvent.Pre`,伤害为 MAGIC/INDIRECT_MAGIC/WITHER | 伤害 ×0.90 / ×0.80 / ×0.65(10%/20%/35%)。非完全免疫;火焰/摔落/近战/弹丸不生效 |
| 研修 STUDY | 25/50/75 | study_i/ii/iii | `jobsplus:on_job_exp` | 职业经验 ×1.15 / ×1.35 / ×1.60。`powerup_not_active` 保证最高档单独生效,不叠乘 |

### 2.1 调饮路线效果实现

- Java 监听 `BeveragePreparedEvent`(非 Arc 事件,无法用数据触发),查 `BREWING` 路线最高档后应用速度/幸运效果。
- 高等级覆盖:每次只应用最高档,效果时长取该档值(`brewingSpeedTicks`:I 100 / II 160 / III 240;`brewingLuckTicks`:II 160 / III 240),低档节点即使 active 也不叠加。

### 2.2 品鉴路线效果实现

- Arc 数据驱动:`arc:on_drink` + `arc:items(["#tcth:brewer_drinks"])` + `tcth:brewer_drink_cooldown` + `tcth:brewer_tasting_abilities_enabled` + `powerup_not_active`(低档排除高档)。
- reward `tcth:brewer_tasting_effects` 应用效果包,成功应用后提交 `BrewerDrinkCooldown`(20s,400 tick,logout/stop 清理)。
- 冷却条件 `tcth:brewer_drink_cooldown` 在冷却期间阻止 action;冷却仅在效果实际发送后提交(失败不提交)。

### 2.3 耐受路线实现

- Java 监听 `LivingDamageEvent.Pre`;`isMagicalDamage` 仅识别 `MAGIC` / `INDIRECT_MAGIC` / `WITHER`,按最高档乘以减免系数。FakePlayer 排除。

### 2.4 研修路线实现

- Arc 数据驱动:`jobsplus:on_job_exp` + `jobsplus:job_exp_multiplier`(`job: tcth:brewer`,multiplier 1.15/1.35/1.60),条件 `tcth:brewer_study_abilities_enabled` + `powerup_not_active`(低档排除高档)。
- 与枪客研修数据完全同构;倍率常量在 `BrewerAbilityModule.experienceMultiplier` 供一致性测试。

---

## 三、配置开关

`Server/config/tcth-common.toml`(JAR 默认值;本阶段未改服务器实际配置):

```toml
brewerAbilitiesEnabled = true             # 能力树总开关
brewerBrewingAbilitiesEnabled = true      # 调饮路线
brewerTastingAbilitiesEnabled = true      # 品鉴路线
brewerResistanceAbilitiesEnabled = true   # 魔酿耐受路线
brewerStudyAbilitiesEnabled = true        # 研修路线
brewerDrinkCooldownTicks = 400            # 品鉴共享冷却(20 s)
```

- 每条路线生效条件:框架总开关 `enabled` && `brewerIntegrationEnabled` && `brewerAbilitiesEnabled` && 本路线开关(四开关组合)。
- 所有配置读取 **fail-closed**:`RuntimeException`/`LinkageError` 视为关闭,`inverted` 条件**不得**把异常翻转为通过。
- 高频告警(WARN)按 60 s 节流(与 GunnerAbilityModule 一致)。

## 四、数据(preset + 服务器数据包)

### 4.1 powerup 节点(12)

`docs/presets/tcth-brewer/data/tcth/jobsplus/powerups/brewer/`(同步至 `Server/global_packs/required_data/tcth-brewer/`),每节点含 `job: tcth:brewer`、`icon: brewinandchewin:keg`、`price`(8/12/18)、`required_level`(5/20/45, 15/35/55, 10/30/60, 25/50/75)、`parent` 链。

### 4.2 Arc 数据(6)

`arc/brewer/powerup/`:
- 研修 3:`study_i.json` / `study_ii.json` / `study_iii.json`(`on_job_exp` + `job_exp_multiplier` + `brewer_study_abilities_enabled` + `powerup_not_active` 排除高档)。
- 品鉴 3:`tasting_basic.json` / `tasting_adept.json` / `tasting_expert.json`(`arc:on_drink` + `#tcth:brewer_drinks` + `brewer_drink_cooldown` + `brewer_tasting_abilities_enabled` + `powerup_not_active` + `brewer_tasting_effects` reward tier 1/2/3)。

### 4.3 标签

`#tcth:brewer_drinks`(64 条,7B 已建)作为品鉴路线饮用匹配 tag,预设/服务器数据包/JAR 三处一致。

## 五、代码结构

- **公共 API(零第三方引用)**:`impl/compat/jobsplus/powerup/BrewerPowerupAccess.java`、`BrewerAbilityRoute.java`、`BrewerPowerupTier.java`。
- **Jobs+ 查询 + 效果**:`impl/compat/jobsplus/powerup/BrewerAbilityModule.java`(tier 查询、调饮/耐受 Java 监听、研修倍率常量、config gating)。
- **冷却**:`impl/compat/jobsplus/powerup/BrewerDrinkCooldown.java`。
- **Arc 条件/reward**:`impl/compat/jobsplus/arc/condition/BrewerStudyAbilitiesEnabledCondition.java`、`BrewerTastingAbilitiesEnabledCondition.java`、`BrewerDrinkCooldownCondition.java`;`impl/compat/jobsplus/arc/reward/BrewerTastingEffectsReward.java`。
- **注册**:`TcthArcRegistrar` 新增 3 个 condition type + 1 个 reward type(含 verify 校验)。
- **初始化**:`JobsPlusCompatModule` 调用 `BrewerAbilityModule.init`(仅 Jobs+ 存在时加载,公共 API 无第三方引用)。

## 六、中英文能力名称与描述

`assets/tcth/lang/en_us.json` + `zh_cn.json` 各新增 **38 键,中英合计 76 键**:
- 24 powerup 键(12 节点 × 名称/描述)。
- 6 配置键、6 条件键(3 条件 × name/desc)、2 reward 键。

示例(zh_cn):
- `brewing_basic`「快手斟饮」:成功调制分级饮品后获得速度提升 I,持续 5 秒。
- `tasting_expert`「余韵绵长」:完成饮用 #tcth:brewer_drinks 饮品后获得生命恢复 I 5 秒、抗性提升 I 8 秒与速度提升 I 15 秒(20 秒冷却)。
- `resistance_expert`「铁铸味蕾」:受到的魔法、间接魔法与凋零伤害降低 35%。
- `study_iii`「魔酿研修 III」:魔酿师职业经验获取 ×1.60。

`BrewerAbilityLangTest` 逐节点验证两种语言的 12 节点 name/description(24 键/语言),且无孤儿键;描述数值由 `BrewerAbilityDataTest` 对 12 powerup 数据与 6 个 Arc 文件交叉核对。

## 七、BUILD PASS

命令:`GRADLE_USER_HOME=<repo>/.gradle-home JAVA_HOME=<JDK21> ./gradlew clean build --no-daemon`,随后 `cleanTest test --no-daemon --no-build-cache` 真实重跑。

| 阶段 | suites | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| 7D.1(前) | 103 | 818 | 0 | 0 | 0 |
| 7E(本轮) | **110** | **867** | **0** | **0** | **0** |

新增 **7 套件 / 49 用例**:

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `BrewerAbilityModuleTest` | 18 | tier 查询(Jobs+ 公共 API、非 Jobs+ player→NONE、损坏查询→NONE);调饮效果(分级事件触发速度+幸运按时长、高档覆盖低档、单事件单次应用、automated/UNKNOWN/T3/无档不触发);耐受效果(MAGIC/INDIRECT_MAGIC/WITHER 正例,火焰/摔落/弹丸/近战负例,无档不变,NaN/±Inf 非有限伤害 fail-closed);总/路线开关关闭;配置异常 fail-closed |
| `BrewerAbilityHelpersTest` | 7 | 三级互斥(highestActive)、路线节点命名空间、调饮时长常量、耐受倍率、研修倍率(不叠乘)、伤害识别正反例 |
| `BrewerDrinkCooldownTest` | 6 | 冷却 399/400 边界、首次 commit 前无冷却、clearPlayer/clearAll、条件通过/阻断/过期、与厨师品鉴冷却相互独立 |
| `BrewerTastingEffectsRewardTest` | 6 | 三档效果包、冷却成功后才提交(全失败不提交)、非 ServerPlayer 无效果、非法档位构造拒绝 |
| `BrewerAbilitiesEnabledConditionTest` | 4 | 研修/品鉴四开关组合、配置异常 fail-closed(含 inverted 不翻转) |
| `BrewerAbilityDataTest` | 4 | 12 powerup 等级/父链、研修倍率+排除高档、品鉴 on_drink+drinks+冷却+reward、预设与服务器数据包一致 |
| `BrewerAbilityLangTest` | 4 | 逐节点验证 en_us/zh_cn 12 节点 name/description(24 键/语言)、无孤儿键、配置/条件/reward 键齐全 |

- 原有 7A–7D.1 全部测试保留并通过(含 `KegPouringHandlerParameterBindingTest`、`BrewingStatsTrackerTest`、`FieldGuideBrewerUnlockTest` 等)。
- 公共 API(`BrewerPowerupAccess`/`Route`/`Tier`)只引用 Minecraft 类型,可在缺 Jobs+/Arc 时加载;Jobs+ 引用仅存在于 `BrewerAbilityModule`(条件 compat 包)。

## 八、构建产物

| 项 | 值 |
|---|---|
| 构建产物 | `mod develop/tcthintegration-template-1.21.1/build/libs/tcth-0.2.3.jar` |
| 大小 | 415,712 字节 |
| SHA-256 | `6b91e5eb78eb8306ac1e1e5c9e38e1f552ca24cbff5617b7cf4c27e147418c95` |
| 上一版(7D.1) | `139c52de15a28507985a5eb4c52c999aefc9959b56a7f4860cc4334f37661f76`(387,177 B) |
| 第三方纯净性 | JAR 内无第三方 class、无嵌套第三方 JAR |
| 新类 | `BrewerPowerupAccess`/`BrewerAbilityRoute`/`BrewerPowerupTier`/`BrewerAbilityModule`/`BrewerDrinkCooldown`、3 个 Arc condition、1 个 Arc reward |
| 新资源 | 中英文 lang 各 38 键(24 powerup + 6 配置 + 6 条件 + 2 reward),合计 76 键 |

> **部署状态(明确)**:**JAR 未部署** — `Server/mods/` 仍运行 7C.2.1 的 `tcth-0.2.3.jar`(`b725c93c…`,367,301 B);7D/7D.1/7E 构建的 `6b91e5eb…` 均未部署。**服务器数据包已同步但尚未加载** — `Server/global_packs/required_data/tcth-brewer/` 已新增 12 个 powerup + 6 个 Arc 文件(与预设逐字节一致),但服务器未启动加载。不做烟雾、不启动服务器、不在线测试。

## 九、PLAYER LIVE NOT TESTED

- 四路线效果(调饮/品鉴/耐受/研修)均为运行时行为,本阶段未在线玩家验收。
- 逻辑均由单元测试覆盖;部署后需按后续阶段做在线玩家验证:
  - 调饮:真实调制后速度/幸运效果与时长。
  - 品鉴:饮用 #tcth:brewer_drinks 后效果与 20s 冷却;取消饮用不触发。
  - 耐受:魔法/凋零伤害减免,火焰/近战不受影响。
  - 研修:魔酿师经验 ×1.15/1.35/1.60(不叠乘)。

## 十、未验证项 / 说明

1. 在线验证四路线效果与冷却。
2. 品鉴路线依赖 Arc `on_drink` 在 `finishUsingItem`(完成)触发;若实际服务器 Arc 行为与 javap 不符(如取消也触发),需在线确认并按证据修正。
3. 耐受路线不含毒(poison)——1.21.1 无独立 poison DamageType(javap 证据),报告已说明。
4. 本阶段不改 Mixin、不改 7B/7C 事件与奖励数值、不加金币/掉落/饮品复制/第二套经验、不编辑 playerdata。

## 十一、文件清单(本次新增/修改)

- 修改:`Config.java`(能力树总开关+四路线+冷却)、`TcthArcRegistrar.java`(3 条件+1 reward 注册与 verify)、`JobsPlusCompatModule.java`(BrewerAbilityModule.init)、`assets/tcth/lang/{en_us,zh_cn}.json`、`CHANGELOG.md`
- 新增(主):`powerup/BrewerPowerupAccess.java`、`BrewerAbilityRoute.java`、`BrewerPowerupTier.java`、`BrewerAbilityModule.java`、`BrewerDrinkCooldown.java`;`arc/condition/BrewerStudyAbilitiesEnabledCondition.java`、`BrewerTastingAbilitiesEnabledCondition.java`、`BrewerDrinkCooldownCondition.java`;`arc/reward/BrewerTastingEffectsReward.java`
- 新增(数据):`docs/presets/tcth-brewer/data/tcth/jobsplus/powerups/brewer/`(12)、`arc/brewer/powerup/`(6),同步至 `Server/global_packs/required_data/tcth-brewer/`
- 新增(测试):`powerup/BrewerAbilityModuleTest`、`BrewerAbilityHelpersTest`、`BrewerDrinkCooldownTest`;`arc/condition/BrewerAbilitiesEnabledConditionTest`;`arc/reward/BrewerTastingEffectsRewardTest`;`compat/brewer/BrewerAbilityDataTest`

## 十二、阶段结论

**BUILD PASS(110 suites / 867 tests / 0 failures);PLAYER LIVE NOT TESTED。**

魔酿师四路线能力树的代码/数据/测试已完成;以服务器实际 JAR 的 javap 为权威审计了 `arc:on_drink`(完成触发/取消不触发)、`DamageTypes`(魔法/间接魔法/凋零可可靠识别,毒无独立类型)、`on_job_exp`+`job_exp_multiplier`;复用厨师/枪客能力树架构,公共 API 零第三方引用,Jobs+/Arc 缺失时不解析实现类;配置 fail-closed + 60s 节流;12 powerup 保留 parent/required_level/price 且用 `powerup_not_active` 保证每路线只生效最高档。本阶段不部署、不启动服务器、不烟雾、不在线测试,等待复审。
