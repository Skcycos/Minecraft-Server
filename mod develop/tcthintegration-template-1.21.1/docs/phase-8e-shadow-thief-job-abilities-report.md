# 阶段 8E / 8E.1 / 8E.2 / 8E.2.1 / 8E.2.2 / 8E.2.3 / 8E.2.4 / 8E.2.5 —— tcth:shadow_thief 正式职业、成功经验结算与四路线能力树

- 阶段：8E + 8E.1 + 8E.2 + 8E.2.1 + 8E.2.2 + 8E.2.3 + 8E.2.4 + 8E.2.5（BUILD-only 边界：不部署、不启动服务器、不烟雾、不在线验收、不修改
  Server/config 与 playerdata、不开启任何 Shadow 生产开关、不 commit/push）
- 基线：0.2.8（suites=145 / tests=1443）→ 本阶段 **0.2.9**
- 结论：**8E JOB & ABILITY TREE BUILD PASS / 8E XP SETTLEMENT BUILD PASS /
  8E.1 TEST PASS / 8E.2 CLEAN BUILD PASS / 8E.2 JAR AUDIT PASS /
  8E.2.1 DEFECTIVE (SUPERSEDED) /
  8E.2.2 DEFECTIVE (SUPERSEDED — 额度持久化缺陷) /
  8E.2.3 BLOCKER FIX BUILD PASS / 8E.2.3 JAR AUDIT PASS (DEFECTIVE — 容量生命周期未收口) /
  8E.2.4 LONG-TERM CAPACITY BUILD PASS / 8E.2.4 JAR AUDIT PASS (HISTORICAL —
  CAPACITY ORDER DEFECT) /
  8E.2.5 ROLLING CAPACITY TRANSACTION BUILD PASS / 8E.2.5 JAR AUDIT PASS /
  SERVER NOT STARTED / JAR NOT DEPLOYED / PLAYER LIVE NOT
  TESTED / SHADOW SWITCHES STILL FALSE / COIN STILL BLOCKED /
  commit·push NOT DONE**

---

## 1. 当前 Git 基线

| 项 | 值 | 证据 |
|---|---|---|
| 仓库分支 | `main` | `git branch --show-current` |
| fetch 后 main 与 origin/main | **同步**（`## main...origin/main`，无 ahead/behind） | `git status -sb` |
| 基线提交 | `8bd98599` feat(tcth): 完成影窃者生物影窃体系；`7ef221cf` docs: 更新影窃者生物影窃日志（其后另有用户既有部署/日志补记提交，未触碰） | `git log --oneline` |
| 本阶段 | **未 commit、未 push**；工作区既有未提交内容（Server 运行文件/备份/日志/JAR/源码参考）全部原样保留 | `git status --short` |
| 版本 | 0.2.8 → **0.2.9** | `gradle.properties` |

## 2. Jobs+/Arc JAR 权威证据

| 权威源 | 版本 | SHA-256 | 用途 |
|---|---|---|---|
| `Server/mods/[职业+]jobsplus-9.0.0-neoforge.jar` | 9.0.0 | `5f06f40317ea727afd79ddd02790d6b840702999115d439fb09e8c6c77e5b5be` | Powerup JSON schema（`job/icon/parent/price/required_level/type`）、`PowerupInstance.passedHolderCondition` 要求 `PowerupState.ACTIVE`、语言键 `jobsplus.powerup.<ns>.<path>.name/description` |
| `Server/mods/[Arc库]arc-9.0.0-neoforge.jar` | 9.0.0 | `bd165916f75a9c7c5d8ccd9aaf8ca4a151eaffa68280a562da7084428343cd50` | `ActionDataType.register` 泛型注册、Action 序列化容忍空 `rewards`、`ActionData.getData` |
| 源码参考 | ArcLib `1.21` @ `051b008`；JobsPlus `1.21` @ `0a15e53` | — | 仅辅助理解调用意图；与服务器 JAR 版本匹配（8A 已证） |

本阶段未重新 javap 服务器 JAR——8A/8D 已对 Jobs+/Arc 公共 API 链
（`JobsServerPlayer → jobsplus$getJob(JobInstance.of(RL)) → Job →
JobPowerupManager.getPowerup(PowerupInstance.of(RL)) → PowerupState.ACTIVE`
与 `ActionDataBuilder.sendToAction`）完成字节码实证；本阶段全部实现仅使用
这些已实证的公共 API。

## 3. 职业与 12 节点表

预设：`docs/presets/tcth-shadow-thief/`（pack_format 48，**不进主 JAR**）。

- 职业 `tcth:shadow_thief`：`price=10`、`max_level=100`、
  `is_default=false`、图标 `minecraft:echo_shard`、背景 deepslate；
  JSON 内**不硬编码名称/描述**，使用语言键
  `jobsplus.job.tcth.shadow_thief.name/.description`（中英双语已加入主模组
  `assets/tcth/lang/`）。

| 路线 | 节点 | required_level | price | parent |
|---|---|---|---|---|
| 妙手 Sleight | `sleight_of_hand_i` 顺手牵羊 | 5 | 5 | — |
| 妙手 | `sleight_of_hand_ii` 探囊取物 | 20 | 10 | `tcth:shadow_thief/sleight_of_hand_i` |
| 妙手 | `sleight_of_hand_iii` 无影之手 | 45 | 15 | `tcth:shadow_thief/sleight_of_hand_ii` |
| 夺生 Life Siphon | `life_siphon_i` 偷生 | 10 | 5 | — |
| 夺生 | `life_siphon_ii` 噬息 | 30 | 10 | `tcth:shadow_thief/life_siphon_i` |
| 夺生 | `life_siphon_iii` 夺命契约 | 60 | 15 | `tcth:shadow_thief/life_siphon_ii` |
| 窃法 Spell Theft | `spell_theft_i` 盗取余韵 | 15 | 5 | — |
| 窃法 | `spell_theft_ii` 移花接木 | 35 | 10 | `tcth:shadow_thief/spell_theft_i` |
| 窃法 | `spell_theft_iii` 窃法宗师 | 55 | 15 | `tcth:shadow_thief/spell_theft_ii` |
| 潜影 Shadow Escape | `shadow_escape_i` 敛息 | 25 | 5 | — |
| 潜影 | `shadow_escape_ii` 遁影 | 50 | 10 | `tcth:shadow_thief/shadow_escape_i` |
| 潜影 | `shadow_escape_iii` 无踪 | 75 | 15 | `tcth:shadow_thief/shadow_escape_ii` |

互斥：四路线合计 12 个 `data/tcth/arc/shadow_thief/powerup/<node>.json`
（holder=`jobsplus:powerup`，holder 要求 ACTIVE 由 Jobs+ 自身保证），
`jobsplus:powerup_not_active`：I 排除 II+III、II 排除 III、III 无排除；
空 rewards（纯声明）——节点效果由 Java 快照层驱动，Java 侧
`ShadowAbilityTier.highestActive` 强制「三级同时 ACTIVE 也只取最高档」，
与数据声明双保险。

## 4. 经验档位（数据驱动）

`data/tcth/arc/shadow_thief/*.json`（holder=`jobsplus:job`，
`tcth:on_shadow_theft_success` + `jobsplus:job_exp`）：

| 目标 | 类型 | min | max |
|---|---|---|---|
| ENTITY | 任意成功搜刮 | 1 | 2 |
| PLAYER | ITEM | 3 | 5 |
| PLAYER | HEALTH | 2 | 4 |
| PLAYER | HUNGER | 2 | 4 |
| PLAYER | EFFECT | 4 | 6 |

Action 仅在全部满足时发送：`outcome=SUCCESS`、receipt 非空且匹配
theftType、`automated=false`、thief 为真实 ServerPlayer、审计已成功 FINAL
（协调器契约：SUCCESS 仅在 FINAL 写入后发布）、非 RECOVERY_REQUIRED、
eventId 未结算。以下全部 0 经验：COIN / FAILED_ROLL / NO_CANDIDATE /
COOLDOWN / DUPLICATE / PROTECTED / TRANSFER_FAILED / FAILED_CLEAN /
ROLLED_BACK / RECOVERY_REQUIRED / AUDIT_FAILED / FRAMEWORK_DISABLED /
INVALID_CONTEXT、automated=true。不发金币、不发物品、不复制掉落、无第二套
经验。

ActionData：`target_kind`、`theft_type`、`target_type`（玩家目标缺省）、
`item_id`/`item_count`（仅 ITEM）、`numeric_amount`（HEALTH/HUNGER）、
`effect_id`/`effect_duration_ticks`（仅 EFFECT）、`automated`。

## 5. 每日经验上限（玩家目标）

- 独立 SavedData `tcth_shadow_experience_limits.dat`
  （`ShadowExperienceLimitStore`，DATA_VERSION=**2**（8E.1），严格 schema，
  overworld 绑定，不与物品每日受害上限存储混用）。
- 键：同一 thief UUID + target UUID + UTC 日期；上限
  `shadowMaxExperienceRewardsPerPairPerDay=3`（默认）。
- eventId 幂等预留协议：发送前 `tryReserve`（占用配额）→ Arc **明确**失败
  `releaseReservation`（可重试）→ 发送成功 `commitReservation`（保持占用）
  → 提交失败/状态不明保守占用（禁止重复经验）。
- **崩溃恢复（8E.1 §1，撤回 8E 的"RESERVED 重启安全"结论）**：持久化的
  RESERVED 在加载时一律迁移为保守的 **RECOVERY** 状态——该 eventId 的
  Arc 发送结果不明，`tryReserve` 返回 `RECOVERY_EXISTING`，**绝不重发**、
  额度继续占用、不得当作明确失败释放；`COMMITTED`/`RECOVERY` 一律禁止
  经 failed-send 路径释放（`releaseReservation` 仅对同一 JVM 的 RESERVED
  有效）；v1 数据显式迁移测试（v1 的 RESERVED → RECOVERY）。
- 容量有界（MAX_PAIRS 1024 / MAX_DAYS_PER_PAIR 64 / MAX_RESERVATIONS
  4096，仅可淘汰已结算 COMMITTED 索引，聚合绝不回开）；损坏 NBT、未来/
  零/负版本、容量无法安全表达 → 持久 failClosed（查询恒 true、预留恒
  REJECTED）。UTC 日期源可注入。
- **64 日滚动保留窗口（8E.2.4）**：COMMITTED eventId 持久幂等保证窗口
  为 **64 UTC 日**，非永久保证。`tryReserve` 原子清理超过当前日−63 天的
  COMMITTED 条目（含 reservation 索引与 occupied 聚合同步删除）；清理仅在
  守卫通过后执行，REJECTED/LIMIT_REACHED 不触发；RESERVED/RECOVERY 永不
  自动清理（未决记录保守占用直到显式解决）；空 pair 清理后删除释放
  MAX_PAIRS 容量。occupied 聚合删除前通过 `hasAnyReservationOnDay` 检查
  该日期是否仍有 RESERVED/RECOVERY 条目，有则保留聚合。
- **64 日边界（8E.1 §2）**：新日期插入触发淘汰时聚合与 reservation 索引
  同步处理——最旧日期的 COMMITTED 索引可删（聚合不回开）；最旧日期含
  RESERVED/RECOVERY 未决记录时保守 REJECTED，绝不生成重启后必损坏的
  NBT；已淘汰旧日期不会被 reservation 交叉校验回补。
- 实体目标不进入该存储（仍由 LOOTED 一次性状态限制，不额外增加重复收益）。
- 登出清理内存缓存（事件 id 缓存按 thief 清理）、停服清理内存状态。

## 6. 四路线数值

| 路线 | I | II | III |
|---|---|---|---|
| 妙手 | 成功率 +0.05；冷却 200→180；高价值 -0.10 | 成功率 +0.10；冷却 200→160；高价值 -0.05 | 成功率 +0.15；冷却 200→140；高价值 0 |
| 夺生（仅玩家） | HEALTH 1 / HUNGER 2 | HEALTH 2 / HUNGER 3 | HEALTH 4 / HUNGER 4 |
| 窃法（仅玩家） | 效果 ≤200 ticks | ≤400 ticks | ≤600 ticks |
| 潜影（玩家+实体） | 成功：速度 I 4 s；失败暴露 ×0.8 | 成功：速度 I 6 s + 隐身 2 s；×0.6 | 成功：速度 II 8 s + 隐身 4 s；×0.4 |

- 妙手成功率仍受 `shadowMinSuccessChance`/`shadowMaxSuccessChance`（0.85
  硬上限）约束；背后/被注视/警觉/距离修正顺序保持确定；全部数值 finite；
  不绕过 `unstealable_items`、不绕过空间检查、单次仍只转移 1 件、不改每日
  物品受害上限、三级不叠加。
- 夺生：目标生命最低保留 2 点、饱食度保护线不变；影窃者满血/已饱不入候选；
  只恢复实际扣除量；LivingHealEvent 取消/修改仍严格守恒（实际增量测量）；
  0≤saturation≤foodLevel；候选池与 prepare 共享 `ShadowAbilityValues` +
  `ShadowFeasibility.computeHungerPlan(…, tierTransfer)` 同一数值源（测试
  证明「候选可用但 prepare 无漂移返回 null」不会发生）；严格快照、
  owned-state 分类、内部/外部回滚语义全部保留；不杀目标、不破保护线、
  三级不叠加。
- 窃法：不提高 amplifier、不复制完整剩余时间、不偷 ambient/永久/非法时长、
  不偷 `unstealable_effects`、必须属 `stealable_effects`；影窃者已持同类
  效果不入候选（沿用最安全策略）；removeEffect/forceAddEffect 失败回滚；
  duration/amplifier/ambient/visible/icon 严格比较；receipt 记录真实转移
  时长；候选与 prepare 共享同一最大时长源；白名单/黑名单语义不变；三级不叠加。
- 潜影：只在最终 SUCCESS 且审计 FINAL 后授予成功效果（效果授予失败不回滚
  已完成的资产事务）；FAILED_ROLL 才应用失败保护（暴露时长
  ×1.0/0.8/0.6/0.4，仅在玩家路径的 exposeThief）；NO_CANDIDATE/COOLDOWN/
  DUPLICATE 等不得获得逃脱增益；攻击（服务端权威 `AttackEntityEvent`，
  过滤客户端）或再次影窃（发生在随机/事务前）立即解除 TCTH 授予的隐身；
  解除前验证当前效果仍匹配 TCTH 签名（amplifier、自然衰减窗口
  `granted − elapsed − 2 ≤ duration ≤ granted − elapsed`、非 ambient、
  visible、icon）；标记有界（512）、登出/停服清理、不持久化到 SavedData；
  III 不触发 I/II。**所有权边界（8E.1 §3 修订，撤回 8E 的"只清标记"表述）**：
  所有权由服务端权威 `MobEffectEvent` 链维护（javap 实证 NeoForge
  21.1.247）——TCTH 授予走短期内部 guard；外部替换（Added 的被覆盖实例
  匹配 marker）、外部移除（Remove）、自然到期（Expired）均立即清 marker；
  攻击/再次影窃只删除仍匹配自然衰减窗口签名的当前隐身，外部更长/更短/
  相同时长/不同 amplifier/ambient/visible/icon 均不误删。固有限制（如实
  记录）：`forceAddEffect` 不发布 Added 事件，字节级与 TCTH 授予完全
  相同的替换实例无法与自然衰减区分，按 TCTH 拥有处理；此为当前 MC 版本
  事件 API 不可分辨的边界。

## 7. 能力查询架构

```
PlayerInteractHandler（每尝试恰一次）→ ShadowAbilityAccess.snapshotFor(thief)
  → [Jobs+ 已装] ShadowAbilityModule（compat，Config 主门 + 每路线开关
    → Jobs+ 公共 API 链 → ShadowAbilityTier.highestActive）
  → ShadowAbilitySnapshot（纯 MC 记录：4 路线档位）
  → ShadowAttemptContext.abilities()
    → 候选池（PlayerReadonlyCandidateProvider 夺生/窃法可行性）
    → 成功率（ShadowSuccessContext.abilityModifier）
    → prepare（PlayerAssetTransferExecutor 档位数值）
    → 冷却（sleightGlobalCooldownTicks）
    → 反馈层（PlayerInteractHandler 暴露时长缩放）
```

- 只通过 Jobs+ 公共 API 查询 ACTIVE；不读 NBT；不修改 Jobs+ 内部状态；
  不做长期缓存（每次尝试实时查询一次）；异常/LinkageError → NONE；无
  Jobs+/无职业/未激活节点 → 基础行为；每次尝试最多查询一次；同一快照
  贯穿候选池/成功率/prepare/冷却/反馈；事务深层零 Jobs+ 查询；能力模块
  不改变抽取类型（玩家始终不能选 ITEM/HEALTH/HUNGER/EFFECT）。
- 快照为纯 TCTH/MC 类型（`ShadowAbilityTier/Route/Snapshot/Values` 在
  `impl/shadow`），Jobs+ 对象绝不进入公共或事务层。
- 依赖隔离：无 Jobs+ 时 `ShadowAbilityAccess` 默认返回 NONE 快照，基础
  事务照常加载运行。

## 8. 配置与 fail-closed

新增（Config.java）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `shadowRewardsEnabled` | **false** | 经验奖励总开关 |
| `shadowAbilitiesEnabled` | true | 能力树主开关 |
| `shadowSleightAbilitiesEnabled` | true | 妙手路线 |
| `shadowLifeSiphonAbilitiesEnabled` | true | 夺生路线 |
| `shadowSpellTheftAbilitiesEnabled` | true | 窃法路线 |
| `shadowEscapeAbilitiesEnabled` | true | 潜影路线 |
| `shadowMaxExperienceRewardsPerPairPerDay` | 3 | 每日每对经验上限 |

能力条件组合：`Config.ENABLED && shadowThiefIntegrationEnabled &&
shadowAbilitiesEnabled && routeEnabled`。任何配置读取异常：奖励条件直接
false、能力查询 NONE、inverted 不得将异常翻转为 true、WARN 按
`ShadowLogThrottle` 60 秒模板节流。既有
`shadowThiefIntegrationEnabled`/`shadowPlayerTheftEnabled`/
`shadowEntityTheftEnabled`/`shadowRealAssetTransfersEnabled` 默认值
**保持不变（全部 false）**。

8E.1 进一步收口奖励异常边界：`ShadowRewardModule.onShadowTheft`、三个
配置 supplier、store factory/store 调用以及 Arc action sender 均对
`RuntimeException | LinkageError` fail-closed；明确发送失败才释放本 JVM
的 RESERVED，发送抛异常或结果不明则保守占用、不得重发。高频失败日志仍
统一由 `ShadowLogThrottle` 以 60 秒窗口节流；Dispatcher 同样隔离
`RuntimeException | LinkageError`，单个坏事件不得击穿服务器 tick。

## 9. 依赖隔离

- 公共 `api.shadow` 零 Jobs+/Arc 引用（`ShadowApiReferenceTest` 字节级
  扫描 + 本阶段未向 api.shadow 增加任何文件）。
- Jobs+ 实现（`ShadowAbilityModule`/`ShadowRewardModule`/Arc 条件与
  Action）仅在 Jobs+ 存在时由 `CompatLoader` 加载；Arc 注册仅在 Jobs+ 且
  Arc 存在时执行（`JobsPlusCompatModule.onModConstruction`）。
- 无 Jobs+ 时 Shadow 基础事务照常加载（快照恒 NONE）；无 Arc 时
  `JobsPlusCompatModule` 自禁用并告警（既有行为）。
- 不捕获 `NoClassDefFoundError` 作为正常控制流（只 catch 后 fail-closed）；
  不把第三方 class 打进 JAR；不产生嵌套 JAR；`docs/presets/tcth-shadow-thief`
  不打入主 JAR；COIN 未接线（协调器恒剔除）；不改 GD656；不改其他职业
  奖励数值。

## 10. 测试矩阵

8E 初版新增 14 个测试类并扩展既有测试（相对 8D.3.2 净 +147 用例）；
8E.1 在同 159 个 suites 上再增加 23 个阻断回归用例（1590→1613）：

**预设（`ShadowThiefPresetTest`）**：job id/is_default/max_level/icon、
12 节点 required_level/price/parent 精确、每路线 I 排除 II/III、II 排除
III、III 无排除、holder=`jobsplus:powerup`（ACTIVE 由 Jobs+ 强制）、5 个
XP Action 的 min/max 档位与条件、中英 12 节点 name+description 全部存在
且非空、描述数值与代码/JSON 一致（+5%/+10%/+15%、200→180/160/140、
1/2/4、10/20/30 s、×0.8/0.6/0.4）、预设不进主 JAR、pack_format 48。

**能力查询**：`ShadowAbilityTierTest`（NONE/I/II/III、三级同时 ACTIVE 只取
III）、`ShadowAbilityRouteTest`（12 节点 id 与 location）、
`ShadowAbilitySnapshotTest`、`ShadowAbilityAccessTest`（默认 NONE、异常
fail-closed、每调用恰一次）、`ShadowAbilityModuleTest`（购买未 ACTIVE →
NONE、全部 ACTIVE → III、Jobs+ 异常 → NONE、四路线互不串线、主/路线开关
门控、配置异常 → NONE）。

**经验**：`ShadowRewardModuleTest`（开关×3 关闭 0 发送、非 SUCCESS 全 0、
automated 0、空/错配 receipt 0、eventId 重复只发一次、首次发送失败释放
预留可重试、成功后不可重复、重启后 COMMITTED_EXISTING 不重发、玩家每日
3 次第四次 0、换日恢复、实体不受对组限制、存储不可用玩家 0 实体照发、
登出/停服清理、模块异常不破 tick；8E.1 新增 RECOVERY 重启后绝不重发、
RECOVERY 不额外消耗新额度槽、配置/store/action sender 的 LinkageError
隔离、发送者抛异常时保守占用）、`ShadowExperienceLimitStoreTest`
（预留/提交/释放生命周期、eventId 幂等、eventId 复用不同对/日拒绝、
对组独立、UTC 换日、重启持久化、损坏 NBT/未来/零/负版本 fail-closed、
容量 fail-closed、提交索引淘汰聚合不回开、日期源可注入；8E.1 新增
COMMITTED/RECOVERY 重启后不可释放、v1 RESERVED→RECOVERY 迁移、
RECOVERY 二次保存加载稳定、65 连续日 save/load 健康、双 save/load
稳定、最旧日期含未决预留时拒绝新日期）。

**妙手**：协调器测试 +5%/+10%/+15%（0.4 roll 在 0.35 失败、0.50 成功）、
85% 上限不绕过（0.84 仍失败）、冷却 180/160/140、高价值 -0.10/-0.05/0、
三级不叠加（highestActive）、类型随机调用次数不增加（既有
`coordinatorRandomCallCountsWithRealEngine` 保留）。

**夺生/窃法**：`PlayerAssetTransferExecutorTest` HEALTH 1/2/4、HUNGER
2/3/4、效果 200/400/600、保护线与上限仍生效、amplifier 不提高、候选与
prepare 全档位一致（`candidatePresentImpliesPrepareNonNullAcrossTiers`）、
`PlayerReadonlyCandidateProviderTest` 档位一致（基础 2 点有候选、夺生 III
4 点无候选——与 prepare 同源）、满血/满饥饿负例（既有保留）。

**潜影**：`ShadowEscapeEffectsTest` 各档速度/隐身时长与放大器、NONE 不
授予、addEffect 被拒不记标记、授予失败不抛、攻击解除（仅服务端）、再次
尝试解除、外部更长/ambient 替换不误删、标记过期只清、登出/停服清理、
有界 512、III 不触发 I/II；8E.1 新增外部 Added 替换、Remove、Expired
生命周期清 marker、TCTH 自身授予 guard、外部更短/相同时长/不同
amplifier/flags 不误删以及自然衰减 ±2 tick 边界；协调器测试 SUCCESS 才授予
（FAILED_ROLL 0 标记）、实体路径同样覆盖；`PlayerInteractHandlerTest`
快照每尝试恰一次并进入 context、再次尝试解除、暴露时长 100/80/60/40。

**冷却与异常边界（8E.1）**：`ShadowAbilityValuesTest` 验证
`base <= reduction → 0`，否则安全执行 `base - reduction`；负数、0、
小于 reduction 均为 0，`Long.MAX_VALUE` 正确得到 `MAX - reduction`，
200→180/160/140 契约不变。奖励与 Dispatcher 的连续异常回归验证
100 次失败仍为 0 奖励、0 未捕获异常、0 tick 崩溃。

**回归**：8A–8D 全部既有测试保留（159 suites 全绿）；chef/farmer/gunner/
brewer 全部既有测试保留；Shadow transaction/audit/attachment/loader 测试
未删除；`ShadowBoundaryGuardTest` 为新增文件补充白名单（
`ShadowEscapeEffects` 使用 MobEffect/addEffect/removeEffect），仍为
文件级结构守卫，行为测试为主。

## 11. 8E.2.5 事务顺序修正

- `tryReserve` 采用“只读规划 → 全部校验 → 一次性应用”：规划副本同时计算
  retention cutoff、可删除的 COMMITTED eventId/日期聚合、空 pair、清理后
  reservation/pair/day 视图、必要轮换和最终 RESERVED 写入。
- retention、容量守卫和 MAX_DAYS_PER_PAIR 日期轮换统一基于同一份清理后虚拟
  状态；清理后目标 pair 消失时重新从计划状态建 pair，不读取过期的旧引用。
- 保留严格 64 UTC 日窗口：只有严格早于当前日−63 日的 COMMITTED 可清理；
  RESERVED/RECOVERY 永不自动清理。LIMIT_REACHED、非法输入、规划异常或最终
  容量不足直接 REJECTED，失败路径不执行维护清理，save() NBT 与调用前一致。
- 修正 8E.2.4 的假容量证明：测试在触发未来日期请求前显式断言
  `reservationCount()==4096` 与 `pairCount()==1024`；新增当前 pair 完全过期、
  未决全容量和清理后仍不足容量的零变更回归。

## 12. 验证数字与 JAR 状态

```
suites=159 tests=1650 failures=0 errors=0 skipped=0
```

- 8E 初版曾执行 `./gradlew clean build --no-daemon` 并得到 159/1590。
- 8E.1 执行了定向测试与全量 `test` 任务，XML 汇总为 **159/1613/0/0/0**
  （净 +23）。
- 8E.2 最终 clean build 成功，XML 汇总为 159/1613/0/0/0。
- 8E.2.1 阻断修正 clean build 成功，XML 汇总为 159/1618/0/0/0（净 +5）。
  **8E.2.1 JAR（SHA-256 `82210735…`）已标记为缺陷产物**（Dispatcher
  sendToAction 异常误返回 CLEAR_FAILURE、事件驱动 marker 即时清、tombstone
  未持久化无容量上限）。
- 8E.2.2 第二轮阻断修正 clean build 成功，XML 汇总为 159/1632/0/0/0（净 +14）。
  **8E.2.2 JAR（SHA-256 `a7a77d74…`）已标记为缺陷产物**（tryReserve 拒绝
  路径通过 `computeIfAbsent` 创建空 pair 条目，导致 pairCount 在拒绝后增长）。
- 8E.2.3 单点收口 clean build 成功，XML 汇总为 159/1637/0/0/0（净 +5）。
  **8E.2.3 JAR（SHA-256 `3fe508b3…`）已标记为容量生命周期未收口的历史产物**
  （一次性 pair 索引永久占满后全服 PLAYER 影窃经验停止，无 COMMITTED 长期
  回收机制）。
- **8E.2.4 历史 BUILD-only 产物**：XML 汇总为 **159/1646/0/0/0**；
  JAR `tcth-0.2.9.jar`（711,232 B、SHA-256
  `307cadcf63786ea15c0e576360fb0abfddc63598e4959f7e43851e065f8955f4`，
  构建时间 2026-08-17 16:46:23）存在容量清理顺序缺陷：容量守卫可能在
  retention cleanup 之前拒绝请求。
- **8E.2.5 当前 JAR**：`build/libs/tcth-0.2.9.jar`，**713,069 B**，
  SHA-256 **`738fb393d16b311930074d72de280162fa7627a74952208de484084d93efb1b6`**，
  构建时间 **2026-08-17 17:20:06**；取代 8E.2.4 历史产物。
- **8E.2.5 JAR 审计通过**：无嵌套 JAR、无第三方 class、预设数据未进入主
  JAR（`docs/presets/tcth-shadow-thief/` 留在项目目录）、en_us.json 与
  zh_cn.json 各 260 键（含 56 个影窃者翻译键）存在且完整、Mixin/NeoForge
  元数据完整、版本确为 0.2.9。
- XML 无 `<failure>`、无 `<error>`、system-out/system-err 无
  AssertionFailedError、无 "Exception in thread"。
- `git diff --check -- src docs CHANGELOG.md README.md README_zh_CN.md
  gradle.properties`：通过。

## 13. 未验证项（如实记录）

- 无服务器加载（SERVER NOT STARTED）：0.2.9 未在任何服务器/客户端加载过，
  Arc 动作 JSON（含空 rewards 的互斥声明）与 Jobs+ powerup JSON 的运行时
  解析只在源码/结构层面核对，未实机验证。
- 无在线验收（PLAYER LIVE NOT TESTED）：职业购买/激活、经验发放、能力
  生效、每日上限、潜影隐身解除均未在线验证；8C.3/8D.3 的在线证据不转移
  给本阶段产物。
- 实体目标的经验档位（1–2）沿用 8D.3 在线证据的框架结论，8E 未在线复核。
- `powerup_not_active` 互斥数据为纯声明（空 rewards）：运行时的实际互斥
  由 Java `highestActiveTier` 保证；Arc 对空 rewards Action 的实机解析
  未验证（序列化器源码确认容忍缺失/空 rewards）。
- 能力数值（成功率加成、冷却缩减、转移量、效果时长）为阶段契约值，未经
  服务器平衡验证。
- 8E.2 已完成最终 clean build 与 JAR 审计；8E.1 的测试结论已被 8E.2 的
  clean build 产物确认（同 159/1613/0/0/0）。

## 14. 后续部署/在线验收清单

1. ~~先执行一次最终 `./gradlew clean build --no-daemon`~~（**8E.2 已完成**：
   suites=159 / tests=1613 / failures=0 / errors=0 / skipped=0；新 JAR
   707,448 B、SHA-256 `72e27ab5…`，审计通过）。
2. 在所有 Shadow 开关保持 false 的前提下部署新 JAR 与
   `docs/presets/tcth-shadow-thief/` 数据包，完成一次服务器 LOAD 验证；
   不把 8E 初版或 8D 的运行证据转移给新产物。
3. LOAD PASS 后，运营再按验收范围临时显式开启：`enabled`、
   `shadowThiefIntegrationEnabled`、
   `shadowPlayerTheftEnabled`（或实体路径开关）、
   `shadowRealAssetTransfersEnabled`、`shadowRewardsEnabled`（奖励总开关
   默认 false，必须显式开启）。
4. 确认 Jobs+ GUI 中职业/节点名称与描述显示正常（语言键随主模组）；在线
   验收：购买并激活单节点 → 效果数值与描述一致；三级同时激活 → 只
   按最高档生效；成功经验档位（ENTITY 1–2、PLAYER 四档）；每日每对 3 次
   上限与换日恢复；重启后 COMMITTED/RECOVERY 不重发；潜影隐身攻击/
   再次影窃解除；外部替换/移除/到期不误删；失败暴露时长缩放。
5. 服务器加载验证：0 TCTH ERROR/WARN（节流修复后证据有效）、
   `tcth:on_shadow_theft_success` 与条件注册存在（DEBUG verify 行）。
6. 上线前确认审计 SavedData 与经验上限 SavedData 的 fail-closed 状态
   （无损坏标志）。

## 15. 精确建议暂存范围

建议作为单个提交暂存（本阶段全部文件）：

```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAbilityTier.java        （新增）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAbilityRoute.java       （新增）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAbilitySnapshot.java    （新增）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAbilityValues.java      （新增）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAbilityAccess.java      （新增）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowEscapeEffects.java      （新增）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowExperienceLimitWriter.java（新增）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowExperienceLimitStore.java（新增）
src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/ShadowRewardModule.java（新增）
src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/powerup/ShadowPowerupAccess.java（新增）
src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/powerup/ShadowAbilityModule.java（新增）
src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/ShadowTheftSuccessAction.java（新增）
src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/ShadowTheftSuccessActionDispatcher.java（新增）
src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/Shadow*.java（新增 7 类）
src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/TcthArcRegistrar.java（修改）
src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/JobsPlusCompatModule.java（修改）
src/main/java/com/tanrunn/tcth/impl/shadow/{ShadowAttemptContext,
  ShadowFeasibility, PlayerReadonlyCandidateProvider, PlayerAssetTransferExecutor,
  ItemPlan, ShadowAttemptCoordinator, ShadowEntityAttemptCoordinator,
  PlayerInteractHandler}.java（修改）
src/main/java/com/tanrunn/tcth/Config.java（+7 配置）
src/main/java/com/tanrunn/tcth/TCTHIntegration.java（+潜影生命周期）
src/main/resources/assets/tcth/lang/en_us.json / zh_cn.json（+40 键）
docs/presets/tcth-shadow-thief/（新增：pack.mcmeta + README + 职业 + 12 powerup + 5 arc + 12 互斥）
src/test/java/com/tanrunn/tcth/impl/shadow/Shadow{AbilityTier,AbilityRoute,
  AbilitySnapshot,AbilityValues,AbilityAccess,EscapeEffects,ExperienceLimitStore,
  ThiefPreset}Test.java（新增 8 类）
src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/ShadowRewardModuleTest.java（新增）
src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/powerup/ShadowAbilityModuleTest.java（新增）
src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/ShadowTheftSuccessActionDispatcherTest.java（新增）
src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/Shadow*.java（新增 3 类）
src/test/java/com/tanrunn/tcth/impl/shadow/{ShadowAttemptCoordinatorTest,
  ShadowEntityAttemptCoordinatorTest, PlayerInteractHandlerTest,
  PlayerAssetTransferExecutorTest, PlayerReadonlyCandidateProviderTest,
  ShadowBoundaryGuardTest}.java（修改）
docs/phase-8e-shadow-thief-job-abilities-report.md（新增）
CHANGELOG.md / README.md / README_zh_CN.md（修改）
gradle.properties（0.2.9）
```

**不要纳入**：`Server/` 下全部既有工作区改动（config 回写、日志、备份、
playerdata、JAR）、`.reasonix`、`.gradle-home`、源码参考仓库、dev-mods。

## 16. 8E.1 提交前阻断收口

8E.1 不改变职业 ID、节点 ID、经验数值、能力数值、配置默认值或 Shadow
生产开关，仅处理提交前发现的五组持久化与边界问题：

1. **经验预留崩溃恢复**：经验上限 SavedData 升至 dataVersion 2；磁盘上的
   RESERVED 加载为 RECOVERY，代表 Arc 发送结果不明。RECOVERY 与
   COMMITTED 均继续占用额度且不能由失败发送路径释放，同 eventId 不重发；
   同一 JVM 内明确失败的 RESERVED 仍可释放并重试。
2. **64 日容量一致性**：淘汰日期时同步处理聚合和 reservation 索引；只有
   已结算 COMMITTED 索引可安全淘汰，存在 RESERVED/RECOVERY 的最旧日期
   时拒绝新日期，避免写出重启后无法保守表达的状态。
3. **潜影隐身所有权**：以 NeoForge `MobEffectEvent.Added/Remove/Expired`
   维护 marker 生命周期，并以内部授予 guard 区分 TCTH 自身写入；攻击或
   再次影窃仅移除仍符合自然衰减 ±2 tick 签名的 TCTH 隐身，外部替换、
   移除和到期只清 marker，不误删外部效果。完全同字节实例经
   `forceAddEffect` 替换仍是当前 API 无法区分的已知限制。
4. **奖励链异常隔离**：配置 supplier、store factory/store 调用、Arc
   sender 与 Dispatcher 的 `RuntimeException | LinkageError` 均被隔离并
   fail-closed；发送结果不明保守占用预留，所有高频错误继续按 60 秒节流。
5. **冷却算术**：避免饱和减法对 `Long.MAX_VALUE` 得出错误结果；统一为
   `base <= reduction ? 0 : base - reduction`，既有 180/160/140 数值不变。

验证状态：定向测试与全量 test 均通过；XML 为 **159 suites / 1613 tests /
0 failures / 0 errors / 0 skipped**（相对 8E 初版净 +23）。8E.2 已执行
最终 clean build 并生成新 JAR（707,448 B、SHA-256 `72e27ab5…`），
JAR 审计通过（无嵌套 JAR、无第三方 class、预设未进入主 JAR、中英文资源
完整、版本 0.2.9）；服务器未启动、未部署、未在线验收。

## 17. 回滚方式

- 本阶段未部署任何服务器内容：**无需服务器回滚**。
- 若已按 §13 部署：移除
  `Server/global_packs/required_data/tcth-shadow-thief/` 数据包即关闭职业/
  节点/经验（能力快照在无职业时恒 NONE，基础行为不受影响）；将
  `tcth-common.toml` 中本阶段 7 个新键恢复默认（`shadowRewardsEnabled=
  false` 等；其余为默认值）或整体还原部署前备份。
- 数据：经验上限 SavedData 损坏时进入 fail-closed（不发放经验），需运营
  人工清档；审计与既有 SavedData 不受影响。8E.1 已将该存储 schema 升至
  dataVersion 2，但本阶段尚未部署，所以服务器当前不存在由本阶段产生的
  v2 迁移。未来部署前必须备份 `world/data/tcth_shadow_experience_limits.dat`；
  若已生成 v2 文件，不得直接用只识别 v1 的旧 JAR 覆盖并声称无数据风险。
- 代码：在**尚未部署且未生成 v2 SavedData**的当前状态，可整体回退本阶段
  源码/预设/语言/文档；若未来已部署，则同时按上条处理数据文件与配置备份。

---

### 验收声明

- 8E JOB & ABILITY TREE BUILD PASS（预设结构 + 统一快照查询 + 四路线数值，
  全部经单元/结构测试）
- 8E XP SETTLEMENT BUILD PASS（Arc Action + 每日对组上限 + 预留协议，单元
  测试覆盖）
- 8E.1 TEST PASS（159 suites / 1613 tests / 0 failures）
- **8E.2 CLEAN BUILD PASS**（159 suites / 1613 tests / 0 failures / 0 errors / 0 skipped）
- **8E.2 JAR AUDIT PASS**
- **8E.2.1 DEFECTIVE (SUPERSEDED)** — Dispatcher sendToAction 异常误返回
  CLEAR_FAILURE、事件驱动 marker 即时清、tombstone 未持久化无容量上限
- **8E.2.2 DEFECTIVE (SUPERSEDED)** — tryReserve 拒绝路径通过
  `computeIfAbsent` 创建空 pair 条目（额度持久化缺陷）
- **8E.2.3 BLOCKER FIX BUILD PASS**（159 suites / 1637 tests / 0 failures /
  0 errors / 0 skipped；+5 新测试：拒绝路径纯化 + canceled Expired）
- **8E.2.3 JAR AUDIT PASS**（710,475 B、SHA-256 `3fe508b3…`、取代 8E.2.2
  缺陷产物；无嵌套 JAR、无第三方 class、预设未进入主 JAR、中英文资源完整、
  版本 0.2.9）——**已标记为容量生命周期未收口的历史产物**
- **8E.2.4 LONG-TERM CAPACITY BUILD PASS**（159 suites / 1646 tests / 0
  failures / 0 errors / 0 skipped；+9 新测试：64 日滚动保留窗口 + 边界）
- **8E.2.4 JAR AUDIT PASS**（711,232 B、SHA-256 `307cadcf…`、取代 8E.2.3
  历史产物；容量清理顺序存在缺陷；无嵌套 JAR、无第三方 class、预设未进入
  主 JAR、中英文资源完整、版本 0.2.9）——**历史 BUILD-only 产物**
- **8E.2.5 ROLLING CAPACITY TRANSACTION BUILD PASS**（159 suites / 1650
  tests / 0 failures / 0 errors / 0 skipped）
- **8E.2.5 JAR AUDIT PASS**（713,069 B、SHA-256
  `738fb393d16b311930074d72de280162fa7627a74952208de484084d93efb1b6`、
  构建时间 2026-08-17 17:20:06；无嵌套 JAR、无第三方 class、预设未进入主
  JAR、中英文资源各 260 键/56 个影窃者键、版本 0.2.9）
- SERVER NOT STARTED
- JAR NOT DEPLOYED
- PLAYER LIVE NOT TESTED
- SHADOW SWITCHES STILL FALSE（integration/player/entity/realTransfers/
  rewards 全部默认 false，未修改任何服务器配置）
- COIN STILL BLOCKED
- commit/push NOT DONE

**本阶段不声称：** 服务器加载通过（未启动）、在线经验/能力/上限/隐身解除
已验证（未在线验收）、Arc 空 rewards 互斥声明的实机解析已验证（仅源码
级核对）、职业数值经过服务器平衡验证。

**已知限制（如实记录）：** 字节级完全相同外部替换不可区分——forceAdd 的
实例若与 TCTH 授予的实例完全一致（amplifier/ambient/visible/icon/duration），
延迟 reconcile 无法区分自然衰减与外部替换，按 TCTH 拥有处理。

—— 阶段 8E / 8E.1 / 8E.2 / 8E.2.1 / 8E.2.2 / 8E.2.3 / 8E.2.4 / 8E.2.5 / 8E.2.5.1 报告完 ——
