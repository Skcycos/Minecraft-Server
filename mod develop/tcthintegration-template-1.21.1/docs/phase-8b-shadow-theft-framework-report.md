# 阶段 8B —— 统一影窃框架（shadow thief framework）

- 阶段：8B（只实现公共 API、候选/随机/成功率纯逻辑、协调器、保护接口、冷却幂等与审计数据基础）
- 范围：**不监听交互、不实现任何真实转移、不调用 Lightman's Currency、不引用 OPAC、不创建职业数据**
- 基线：TCTH 0.2.7，117 suites / 934 tests → 本阶段后 **129 suites / 1058 tests / 0 failures**
- 结论：**FRAMEWORK BUILD PASS / DATA NOT APPLICABLE / SERVER NOT STARTED / MIXIN NOT ADDED / PLAYER LIVE NOT TESTED / REAL ASSET TRANSFER NOT IMPLEMENTED / COIN BLOCKED / commit·push 未执行**

---

## 1. 范围和硬边界

本阶段交付的是**可执行但完全惰性**的影窃框架骨架：

| 交付物 | 状态 |
|---|---|
| 公共 API（`com.tanrunn.tcth.api.shadow`） | ✅ 实现 |
| 候选/随机/成功率纯逻辑 | ✅ 实现（纯函数，无实体/无背包） |
| 协调器状态机 | ✅ 实现（17 步，注入式依赖） |
| 保护服务接口 + fail-closed 默认实现 | ✅ 实现（生产默认拒绝一切） |
| 冷却/警觉/幂等（内存态） | ✅ 实现 |
| 审计 SavedData | ✅ 实现（`world/data/tcth_shadow_audit.dat`） |
| 配置项（12 项，默认安全） | ✅ 实现 |
| 事件 Dispatcher | ✅ 实现（只发最终事件） |
| `PlayerInteractEvent.EntityInteract` 监听 | ❌ 未实现（全库零引用，边界测试证明） |
| ITEM/COIN/HEALTH/HUNGER/EFFECT 生产事务 | ❌ 未实现（executor 恒失败） |
| Lightman's Currency / OPAC / Jobs+ / Arc 引用 | ❌ 零引用（边界测试证明） |
| shadow_thief 职业/能力树数据 | ❌ 不存在（资源树零文件） |
| 每日额度 | ❌ 本阶段只留接口规划（§14） |

**惰性保证**：即使 `shadowThiefIntegrationEnabled=true`，生产默认 wiring 也由
「空候选 provider + no-op executor + 全拒保护」三重 fail-closed 兜底——协调器
在保护阶段（步骤 5）即短路，候选 provider 与 executor 根本不会被调用
（`productionDefaultsNeverReachTheTransferExecutor` 测试证明）。

---

## 2. API 结构（`com.tanrunn.tcth.api.shadow`）

| 类型 | 说明 |
|---|---|
| `ShadowTheftType` | ITEM / COIN / HEALTH / HUNGER / EFFECT（玩家不能选类型） |
| `ShadowTargetKind` | PLAYER / ENTITY |
| `ShadowTheftOutcome` | SUCCESS / FAILED_ROLL / NO_CANDIDATE / PROTECTED / COOLDOWN / DUPLICATE / INVALID_CONTEXT / TRANSFER_FAILED / FRAMEWORK_DISABLED + **AUDIT_FAILED**（新增：事务已提交但审计失败——严重错误态，绝不伪报 SUCCESS） |
| `ShadowTheftReceipt` | 不可变 record：`itemId/itemCount/numericAmount/effectDurationTicks/effectId`；数值 finite 非负；工厂方法保证字段互斥；`matches(theftType)` 校验「SUCCESS 只允许对应类型字段有值」；`empty()` 为非 SUCCESS 默认 |
| `ShadowTheftEvent` | 不可取消 `Event`；`eventId/thief/targetKind/targetId/outcome/receipt/level` 非空；`BlockPos.immutable()` 拷贝；**无 settled/rewarded/cancelled 字段**；英文 Javadoc + 0.x 不稳定声明 |

API 校验测试：非空拒绝、receipt 数值校验、位置不可变、eventId 稳定、类字节扫描
无第三方引用（`ShadowApiReferenceTest`：api.shadow 零 `com.daqem`/`jobsplus`/
`lightmanscurrency`/`xaero`/Curios/Inventory/health/FoodData/MobEffect 字符串）。

---

## 3. 候选池与权重算法（`ShadowCandidatePool` / `ShadowCandidate`）

- `ShadowCandidate`：`(type, weight, successModifier, highValue)` 纯数据 record；
  非正权重拒绝；successModifier 必须 finite；**不含任何事务对象/背包/实体引用**。
- 默认权重（8A 定稿）：ITEM 30 / COIN 20 / HEALTH 20 / HUNGER 15 / EFFECT 15。
- 规则实现：
  - 池只装「当前真实可用」候选；不可用类型在抽取前 `without(type)` 移除，剩余
    权重自然重新归一（`totalWeight()` 重算，测试 `removalRenormalisesRemainingWeights`）；
  - **空池不碰随机源**（`draw` 先判空，测试 `emptyPoolDrawsNothingAndNeverCallsRandom`）；
  - 权重和用 `long` 累加，负数回绕时饱和到 `Long.MAX_VALUE`（防溢出，测试
    `hugeWeightsDoNotOverflowIntOrLong`）；
  - 抽取恰好一次：`Math.floorMod(random.nextLong(), totalWeight)`（1.21.1 的
    `RandomSource` 无 `nextLong(long)`，floorMod 单次调用、微小偏差可忽略，已注释）；
  - 删除候选/选中后不再因后续失败改抽（协调器只 draw 一次，测试
    `typeDrawHappensExactlyOnce` / `transferFailureDoesNotRedrawAndPostsNoSuccess`）。
- COIN 硬阻断：协调器步骤 7 恒定 `pool.without(COIN)`（`coin_transfer_not_atomic`），
  即使 provider 投喂 COIN 候选也到不了抽取（测试 `coinIsHardBlockedFromThePool`）。

---

## 4. 成功率向量公式（`ShadowSuccessCalculator` / `ShadowVectorMath`）

**单源常量（`ShadowVectorMath`）：**
```
WATCHED_DOT_MIN = +cos(45°) ≈ +0.7071067811865476   目标看向影窃者
BEHIND_DOT_MAX  =  cos(135°) ≈ -0.7071067811865476   影窃者在目标背后
DOT_EPSILON     = 1e-12                              边界按「被注视」处理（fail-safe 偏向目标）
```
**计算：** `dot = normalize(targetLook) · normalize(thiefPos - targetPos)`；
`watched = dot >= WATCHED_DOT_MIN - EPS`；`behind = !watched && dot <= BEHIND_DOT_MAX + EPS`；
零长度/非有限/空输入 → 双双 false（fail-closed）。互斥由构造保证，360° 逐 5° 测试证明。

**成功率（`ShadowSuccessCalculator`，输入为不可变 `ShadowSuccessContext`）：**
```
chance = base(0.35) + behind(+0.25) - watched(-0.25) - alerted(-0.20)
         - 0.02 × max(0, distance - 1.5) + candidateModifier + abilityModifier(本阶段恒 0)
clamp  [min 0.05, max 0.85]   永不 100%
NaN/Infinity（含 clamp 本身）→ 返回 0.05 常量（fail-closed）
roll   = random.nextDouble() < chance   恰好一次随机调用；恰等于 chance 时失败（`<` 单一规则，测试覆盖边界）
```
line-of-sight 输入已在 `ShadowSuccessContext` 预留（本阶段不参与公式）。

---

## 5. 协调器状态机（`ShadowAttemptCoordinator`）

严格 17 步（8A §9 原样落地），每步产出注入式依赖决定：

```
1  总开关（settingsSupplier，fail-closed 读 Config）      → FRAMEWORK_DISABLED（不发布）
2  上下文验证（服务端 level、automated、目标类型开关）     → INVALID_CONTEXT（不发布）
3  真实玩家（thief instanceof FakePlayer 拒绝）           → INVALID_CONTEXT（不发布）
4  幂等（thief+target+tick key）                          → DUPLICATE（发布）
5  保护服务（UNKNOWN/异常=拒绝）                          → PROTECTED（发布）
6  冷却（全局/无候选/失败/受害保护）                       → COOLDOWN（发布）
7  候选池（provider 异常→空池；恒定剔除 COIN）
8  空池                                                → NO_CANDIDATE（发布+短冷却）
9  类型随机恰好一次（pool.draw）
10 成功率（向量事实+警觉+距离+候选修正+能力修正=0）
11 成功判定恰好一次（roll）
12 失败                                                → FAILED_ROLL（发布+失败冷却+警觉）
13 executor.execute(context, selected)
14 失败/异常/回执类型不匹配                              → TRANSFER_FAILED（发布，不重抽）
15 审计写入（auditEnabled=false 也视为失败）
16 审计失败                                            → AUDIT_FAILED（发布，绝不 SUCCESS）
17 提交：全局冷却 + 受害保护 + 幂等 key + eventId
                                                    → SUCCESS（发布，收据非空）
```

关键性质（全部有测试）：
- **eventId 一次生成全程保持**：Result / 事件 / 审计记录共用（`eventIdIsStableAcrossResultAndPostedEvent`）。
- **SUCCESS 必须先事务后审计**（`successRequiresTransferAndAudit`）；审计失败
  `auditFailureNeverReportsSuccess` / `auditDisabledNeverReportsSuccess` /
  `auditStoreExceptionNeverReportsSuccess`；本阶段审计失败时收据仍随
  AUDIT_FAILED 事件报告（未来 WAL/预写审计需求，见 §14）。
- 发布策略统一：所有最终结果（DUPLICATE 起）都发布事件；FRAMEWORK_DISABLED /
  INVALID_CONTEXT 属框架级非尝试，不发布。奖励系统未来只能消费 SUCCESS。
- 单次异常隔离：依赖异常被捕获→节流 WARN（60 s，`ShadowLogThrottle`）→映射为
  定义内结局；顶层 catch 双重保险（`frameworkExceptionIsIsolated`）。
- 协调器自身永不改玩家财产；财产变更只发生在 executor 内。

---

## 6. 事务接口及 no-op 生产实现

- `ShadowCandidateProvider.provide(context)`：只读上下文 → 候选列表（每类型至多一个）。
- `ShadowTransferResult`：`committed+receipt` 或 `failed+reason`，二选一（record 构造校验），无部分成功。
- `ShadowTransferExecutor.execute(context, selected)`：契约同 8A §9——成功=整体原子完成、
  失败=零改动、不重抽、协调器不改财产。
- **生产默认**：`EmptyShadowCandidateProvider`（恒空） + `NoopShadowTransferExecutor`
  （恒 `failed("transfer_executor_not_implemented")`）。
- 本阶段只有测试 fake executor（提交/失败/抛异常三类）与 no-op 生产实现。

---

## 7. 保护服务 fail-closed 设计（`ShadowProtectionService` 系列）

- `ShadowProtectionResult`：ALLOWED / DENIED_AREA / DENIED_NEW_PLAYER / DENIED_TARGET /
  DENIED_SELF / DENIED_GAMEMODE / UNKNOWN。
- 规则：**UNKNOWN 视为拒绝**；服务异常视为拒绝（测试 `protectionExceptionFailsClosed`）。
- `ShadowBuiltinProtectionService`：自检顺序 ①自身目标→DENIED_SELF ②旁观/创造/全局
  游戏模式→DENIED_GAMEMODE（FakePlayer 在此也拒绝）③玩家目标死亡/断线/不可解析→
  DENIED_TARGET ④委托 `ShadowAreaProtectionProvider`（默认 denyAll→DENIED_AREA）。
- 生产默认 `ShadowProtectionService.denyAll()` 拒绝一切真实影窃；**本阶段不引用任何
  OPAC 类**——后续由条件兼容模块实现 provider 注入（8C+）。
- 边界测试证明：全拒保护下候选 provider 与 executor 零调用。

---

## 8. 冷却 / 警觉 / 幂等（`ShadowCooldownTracker`）

内存态，不写 playerdata：
- 种类：GLOBAL_COOLDOWN（成功全局）、NO_CANDIDATE（短冷却）、FAILURE（失败长冷却）、
  VICTIM_PROTECTION（成功受害保护期）、ALERT（目标警觉期）、ATTEMPT（thief+target+tick
  幂等）、EVENT_ID（eventId 幂等）。
- 容量 **4096**，超限驱逐最旧；已有 key 更新不移动（测试 `capacityEvictsOldestEntry` /
  `updatingExistingKeyDoesNotGrowTheTracker`）。
- 纯 tick 过期（`onServerTick` 推进 + 惰性清理），**零 wall-clock**；时长加法防溢出
  （饱和 `Long.MAX_VALUE`，测试 `overflowingDurationsSaturateToNeverExpiring`）。
- 登出按 UUID 清理（`onPlayerLogout`）、停服全清（`onServerStopping`）、`init()` 幂等。
- 全部 UUID 键，新旧玩家隔离测试覆盖。
- 数值来源：`ShadowFrameworkSettings`（Config 读取 fail-closed，见 §10）。

---

## 9. 审计 SavedData（`ShadowAuditStore` + `ShadowAuditRecord`）

- 名称 `tcth_shadow_audit` → 文件 `world/data/tcth_shadow_audit.dat`；
  `current(level)` 固定绑定 **overworld** DataStorage（跨维度统一）；
  **不写 vanilla playerdata**（`ShadowAuditStoreTest` 全程只操作 CompoundTag + 内存 store）。
- `dataVersion=1`；未知未来版本 → 空加载不崩溃（`unknownFutureVersionLoadsEmpty`）。
- 记录字段：eventId / thief UUID / target UUID / targetKind / theftType(nullable) /
  outcome / itemId(nullable) / itemCount / numericAmount / effectDurationTicks /
  timestamp / dimension / position(nullable)。**零 ItemStack/NBT/账户对象**。
- 防御加载：非法或 `..` 路径穿越的 ResourceLocation 丢弃、未知枚举丢弃或安全置 null、
  NaN/Infinity/负数丢弃、坏 UUID 丢弃——任何一条坏记录不拖垮世界加载
  （`invalidResourceLocationsAreDropped` / `pathTraversalResourceLocationIsDropped` /
  `unknownEnumsAreDroppedOrSafelyNulled` / `nonFiniteAndNegativeScalarsAreDropped`）。
- 容量 **10,000**：append 超限驱逐最旧（第 10001 条起）；加载同样执行上限（保留最新）。
- `setDirty()` 仅在真实写入时调用（`setDirtyOnlyOnRealWrites`）；查询返回不可变快照
  （`queryResultsAreImmutableSnapshots`）；查询仅 `has/byThief/byTarget/all`，无无限索引。
- 协调器经 `ShadowAuditWriter` 接口写入（测试可注入失败/抛异常 fake）。
- **不实现玩家查询命令（留 8F）**；审计内容不进 debug.log（协调器/调度器日志只有 eventId+结果）。

---

## 10. 配置默认值（`Config.java` 新增 12 项，全部英文注释）

| 配置 | 默认 | 说明 |
|---|---|---|
| `shadowThiefIntegrationEnabled` | **false** | 框架总开关 |
| `shadowPlayerTheftEnabled` | **false** | 玩家目标 |
| `shadowEntityTheftEnabled` | **false** | 实体目标 |
| `shadowAuditEnabled` | **true** | 审计开关；关闭=审计失败语义，SUCCESS 永不发布（fail-closed） |
| `shadowBaseSuccessChance` | 0.35 | 基础成功率 [0,1] |
| `shadowMinSuccessChance` | 0.05 | 下限 |
| `shadowMaxSuccessChance` | 0.85 | 上限（永不 100%） |
| `shadowGlobalCooldownTicks` | 200 | 成功全局冷却 |
| `shadowNoCandidateCooldownTicks` | 40 | 无候选短冷却 |
| `shadowFailureCooldownTicks` | 400 | 失败长冷却 |
| `shadowVictimProtectionTicks` | 1200 | 受害保护期 |
| `shadowAlertTicks` | 100 | 警觉窗口 |

读取封装 `ShadowFrameworkSettings`：**可注入 supplier**（`ShadowFrameworkSettingsTest`
用抛异常 supplier 证明 fail-closed 回退）；裸 JUnit 不接触已加载 ModConfigSpec；
非法/NaN/负数回退安全默认。Dispatcher 的开关读取同样 try/catch fail-closed。

---

## 11. 测试矩阵（新增 12 个测试类，124 个用例）

| 类 | 覆盖 |
|---|---|
| `ShadowTheftEventTest` | 非空校验、位置不可变、eventId 稳定、automated 保留 |
| `ShadowTheftReceiptTest` | 数值校验、per-type 互斥、empty 默认、record 不可变 |
| `ShadowApiReferenceTest` | api.shadow 零第三方/零财产 API 引用、产物无第三方 class、无职业预设 |
| `ShadowCandidatePoolTest` | 空池不碰随机、单候选、默认权重、删后重归一、非正权重拒绝、大权重 long、每次只抽一次 |
| `ShadowSuccessCalculatorTest` | 35% 基础、±25%、-20%、距离衰减、5/85 夹紧、NaN/Infinity、`<` 边界、单次随机 |
| `ShadowVectorMathTest` | 单源阈值、45°/135° 边界、互斥（360°）、零长度/非有限/空输入 |
| `ShadowCooldownTrackerTest` | 4096 上限、过期、登出、停服、生命周期幂等、新旧隔离、防溢出、零时长 no-op |
| `ShadowTheftEventDispatcherTest` | 开关、INVALID_CONTEXT（客户端/FakePlayer）、POSTED、监听器异常隔离、init 幂等 |
| `ShadowAttemptCoordinatorTest` | 开关、FakePlayer、UNKNOWN/异常保护、重复 key、冷却、无候选、抽取失败、转移失败不重抽、审计失败不 SUCCESS、异常隔离、eventId 稳定、生产默认全拒 |
| `ShadowAuditStoreTest` | 往返、dataVersion、overworld、非法 RL/路径穿越、未知枚举、NaN/负数、容量（append+load）、快照不可变、setDirty、不写 playerdata |
| `ShadowFrameworkSettingsTest` | 默认安全、supplier 注入、fail-closed 回退、非法值拒绝 |
| `ShadowBoundaryGuardTest` | 生产代码零 PlayerInteractEvent/LC/OPAC/Inventory/health/FoodData/MobEffect/Jobs+Arc、零职业预设、零部署产物、API 不引用 impl |

既有 117 套件全部保留并通过（129 = 117 + 12）。

---

## 12. XML 实际数字

```
suites=129 tests=1058 failures=0 errors=0 skipped=0
```
（`build/test-results/test/*.xml` 汇总；基线 117/934 → 新增 12 套件 / 124 用例）

---

## 13. JAR 审计（`build/libs/tcth-0.2.7.jar`，512,719 B）

- 无嵌套 JAR（zip 内无 `.jar` 条目）；
- 无第三方 class 根（`com/daqem/`、`io/github/lightman314/`、`xaero/pac/` 零命中）；
- 无 `shadow_thief` 职业/能力树资源（0 命中）；
- api/impl shadow 37 个 class 全部在包；neoforge.mods.toml 未新增任何依赖。
- `git diff --check`（本项目范围）通过。

---

## 14. 本阶段未实现项 / 后续规划

- 真实交互入口（`PlayerInteractEvent.EntityInteract`）——**8C**（本阶段被边界测试禁止）。
- ITEM/HEALTH/HUNGER/EFFECT 生产事务 —— **8C**。
- COIN 真实转账 —— **保持 BLOCKED**（LC 无原子 API，8A 已证；8C 复审补偿模式前不得开启）。
- OPAC 领地 provider —— 8C 条件兼容模块实现 `ShadowAreaProtectionProvider`。
- 每日额度 —— 接口规划：在 `ShadowFrameworkSettings` 与 `ShadowCooldownTracker` 之上
  增加 `ShadowDailyLimit`（victim→day→coreValue/attempts，day=主世界日序号
  `overworld.getDayTime()/24000`）；本阶段不落盘。
- 审计 WAL/预写 —— 规划：`AUDIT_FAILED` 态需未来「先写待审记录→转移→标记提交」以消除
  「已提交但审计失败」窗口；本阶段以 AUDIT_FAILED 显式暴露，不做伪成功。
- 新玩家保护天数、能力树数值、玩家命令（8F）。

## 15. 8C 前置门槛

1. 8C 只允许：交互入口 + 各型事务 executor +（复审通过后）COIN 补偿模式 + OPAC provider。
2. COIN 开启需复审确认补偿模式（8A §5.2 六步）并满足「审计先于 SUCCESS」。
3. 所有新增 executor 必须通过 receipts-matches 校验与原子性测试后才可接线。
4. 真机验证（8C 末尾）前不得把任何开关默认改为 true。

## 16. 精确修改文件清单

**新增（main）**
```
src/main/java/com/tanrunn/tcth/api/shadow/ShadowTheftType.java
src/main/java/com/tanrunn/tcth/api/shadow/ShadowTargetKind.java
src/main/java/com/tanrunn/tcth/api/shadow/ShadowTheftOutcome.java
src/main/java/com/tanrunn/tcth/api/shadow/ShadowTheftReceipt.java
src/main/java/com/tanrunn/tcth/api/shadow/ShadowTheftEvent.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowCandidate.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowCandidatePool.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowSuccessContext.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowSuccessCalculator.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowVectorMath.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptContext.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowCandidateProvider.java
src/main/java/com/tanrunn/tcth/impl/shadow/EmptyShadowCandidateProvider.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTransferResult.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTransferExecutor.java
src/main/java/com/tanrunn/tcth/impl/shadow/NoopShadowTransferExecutor.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowProtectionResult.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowProtectionService.java
src/main/java/com/tanrunn/tcth/impl/shadow/DenyAllShadowProtectionService.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAreaProtectionProvider.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowBuiltinProtectionService.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowCooldownTracker.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditRecord.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditWriter.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStore.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTheftEventDispatcher.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowFrameworkSettings.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowLogThrottle.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java
```

**新增（test）**
```
src/test/java/com/tanrunn/tcth/api/shadow/ShadowTheftEventTest.java
src/test/java/com/tanrunn/tcth/api/shadow/ShadowTheftReceiptTest.java
src/test/java/com/tanrunn/tcth/api/shadow/ShadowApiReferenceTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowCandidatePoolTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowSuccessCalculatorTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowVectorMathTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowCooldownTrackerTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowTheftEventDispatcherTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinatorTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStoreTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowFrameworkSettingsTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowBoundaryGuardTest.java
```

**修改（main）**
```
src/main/java/com/tanrunn/tcth/Config.java            （+12 配置项）
src/main/java/com/tanrunn/tcth/TCTHIntegration.java   （+生命周期 init ×2）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md  （本报告，新增）
CHANGELOG.md                                    （更新）
README.md / README_zh_CN.md                     （更新）
```

未修改服务器配置/世界/职业数据包；未 bump 版本；未部署 JAR。

## 17. 建议暂存范围

- 建议把本阶段改动（§16 全部文件）作为一个提交暂存；8A 报告（
  `docs/phase-8a-shadow-thief-authoritative-audit.md`）如尚未提交可并入同一提交。
- 不要纳入：`Server/` 下既有工作区改动（config 回写、accept*.out、备份目录等）与
  `.gradle-home/`——与影窃无关。
- commit message 建议（含更新日志同步）：`feat(tcth): 影窃者 8B 统一影窃框架（纯骨架，默认关闭）`。

—— 阶段 8B 报告完 ——

---

# 8B.1 修订 —— 统一影窃框架阻断修正

> 注：本节的测试数字（130 suites / 1089 tests）已被 **8B.1.1**（130 suites / 1104 tests）取代，见下文 §8B.1.1。

- 触发：8B 复审六项问题（审计与事务顺序 / 幂等修复 / 审计 schema / SavedData 上限 / 公共 API 不变量 / 位置与视线）+ Dispatcher 边界 + 失败尝试审计策略。
- 基线：8B.1 前 129 suites / 1058 tests → 8B.1 后 **130 suites / 1089 tests / 0 failures**。
- 结论：**8B.1 FRAMEWORK BUILD PASS / REAL ASSET TRANSFER NOT IMPLEMENTED / COIN BLOCKED / SERVER NOT STARTED / PLAYER LIVE NOT TESTED / commit·push 未执行**。

## 8B.1-1 审计与事务顺序（两阶段事务 + 预写审计）

| 项 | 8B | 8B.1 |
|---|---|---|
| 转移接口 | 单步 `execute()` | **`ShadowTransferExecutor` 三阶段**：`prepare`（只读校验+计划，不改资产）→ `commit`（原子转移）→ `rollback`（恢复 commit 前状态）；`ShadowTransferPlan` 为不可变计划对象 |
| 审计门 | 资产提交后才写审计 | **审计可用性门前置**：`auditEnabled=false` 或存储不可用 → 在 provider/随机源/executor 之前 `AUDIT_FAILED` 拒绝（零调用；测试 `auditDisabledRefusesBeforeAnyWork` / `auditUnavailableRefusesBeforeAnyWork`） |
| 预写审计 | 无 | commit 前先写 **PENDING** 记录；预写失败 → commit 0 次（测试 `prewriteFailureSkipsCommit`） |
| 最终写失败 | AUDIT_FAILED（带收据） | 触发**恰好一次 rollback**：成功 → `ROLLED_BACK`（收据空）；失败/异常 → **`RECOVERY_REQUIRED`**（携带已提交收据，绝不伪报 TRANSFER_FAILED 或 SUCCESS；测试 `finalAuditFailureTriggersRollbackOnceAndRollsBack` / `rollbackFailureYieldsRecoveryRequiredWithCommittedReceipt` / `rollbackExceptionYieldsRecoveryRequired`） |
| commit 失败 | — | 无收据、无 SUCCESS、PENDING 记录原位终结为 TRANSFER_FAILED（测试 `commitFailureYieldsNoReceiptNoSuccess`） |
| 生产实现 | no-op fail-closed | 仍是 **NoopShadowTransferExecutor**（prepare→null / commit→failed / rollback→false） |

**状态归属（避免同义混用）**：
- `PENDING` = **审计内部状态**（`ShadowAuditState.PENDING`），永不出现在公共结果；
- `ROLLED_BACK`、`RECOVERY_REQUIRED` = **公共结果**（`ShadowTheftOutcome` 新增）；
- `AUDIT_FAILED` 语义重新定义为「审计禁用/不可用/预写失败 → 资产操作前拒绝」，不再携带已提交收据。

**崩溃一致性限制（如实记录）**：`tcth_shadow_audit.dat` 是普通 SavedData，**不是 fsync WAL**：
崩溃可能留下「已预写 PENDING 但结局未知」的记录（同 eventId 再次尝试 → `RECOVERY_REQUIRED`，见 8B.1-2），也可能丢失最近记录；**不声称数据库级原子性**。未来 WAL/预写加固仍在规划。

## 8B.1-2 幂等修复

- **eventId 先去重（先持久后内存）**：协调器在 provider/随机/executor 之前检查
  `audit.byEventId(eventId)`（持久）→ `idempotencyTracker.hasEventId`（内存）→
  `isAttemptDuplicate`（thief+target+tick）。重复调用：provider 0 次、随机 0 次、
  executor 0 次、审计不新增记录（测试 `duplicateEventIdIsRejectedWithZeroWork`）；
  同 thief/target/tick 不同 eventId 同样只执行一次（`duplicateAttemptKeyIsRejectedEvenWithDifferentEventId`）。
- **PENDING 记录阻塞**：`byEventId` 命中 `ShadowAuditState.PENDING` → `RECOVERY_REQUIRED`
  （reason `pending_record_exists`），不重跑随机/资产（`pendingRecordForEventIdYieldsRecoveryRequired`）。
- **全失败路径结算幂等**：PROTECTED/COOLDOWN/NO_CANDIDATE/FAILED_ROLL/TRANSFER_FAILED/
  ROLLED_BACK/RECOVERY_REQUIRED/AUDIT_FAILED/SUCCESS 统一在 `finishAuditedAttempt` /
  `finishAuditRefusal` 中标记 eventId + attempt key（测试 `failurePathsSettleIdempotency`）。
- **缓存拆分**：`ShadowCooldownTracker`（全局/无候选/失败冷却 + 受害保护 + 警觉，容量 1024）
  与新增 **`ShadowIdempotencyTracker`**（EVENT_ID TTL 1h / ATTEMPT TTL 20 tick，容量 4096）
  完全独立——eventId 洪泛不驱逐安全保护记录（测试
  `safetyRecordsAreNeverEvictedByIdempotencyFloods` / `eventIdFloodNeverTouchesTheCooldownTracker`）；
  每类缓存独立容量/过期/登出/停服清理测试。

## 8B.1-3 审计 schema v1（未部署，直接修正，无需迁移）

`ShadowAuditRecord` 新字段集（全部非负/有限校验 + 交叉一致性 + position 构造时 `immutable()` 复制）：

```
eventId, thiefId, targetId, targetKind,
@Nullable targetType        // ENTITY 必须携带（构造强制）；PLAYER 必须为 null
@Nullable theftType
@Nullable outcome           // 仅 PENDING 时可为 null；FINAL 必须有 outcome
auditState                  // PENDING / FINAL
@Nullable itemId, itemCount, numericAmount,
@Nullable effectId          // EFFECT 必须携带（构造强制）
effectDurationTicks,
timestampEpochMillis        // 注入 Supplier<Long>（默认 System::currentTimeMillis）
serverTick                  // 与 epoch 分离，不冒充时间戳
dimension, @Nullable position（不可变副本）
@Nullable failureReason     // ≤256 字符、无控制字符（构造强制）；不记异常堆栈/资产 NBT
```

加载防御（8B.1 §3.9）：**缺失的 nullable 字段 → null；存在的未知 enum 值 → 丢弃整条记录**；
PENDING+outcome / FINAL 无 outcome 等不一致组合 → 丢弃；无效/路径穿越 ResourceLocation、
NaN/Infinity/负数 → 丢弃。测试：`unknownNonNullEnumsAreDropped` /
`missingNullableTheftTypeIsAllowed` / `recordValidationRejectsBadCombinations` /
`failureReasonRoundTrips` 等。

## 8B.1-4 SavedData 上限修正

- `append` 为 **eventId upsert**（原位替换 PENDING→FINAL，不增记录、保顺序；
  测试 `upsertByEventIdFinalisesPendingInPlace`）。
- **加载保留最新 10,000 条有效记录**：非法记录不占容量；顺序保持旧→新；用可识别
  eventId/timestamp 证明最旧已删、最新仍在（`loadKeepsNewestTenThousandValidRecordsWithIdentifiableIds`
  / `invalidRecordsDoNotCountTowardsTheLoadCap`），不止断言 size。
- append 与 load 同一上限语义；查询仍返回不可变快照。

## 8B.1-5 公共 API 不变量（构造器强制 + 测试）

`ShadowTheftEvent` 构造器校验：
- SUCCESS → theftType 非空且 `receipt.matches(theftType)`；
- RECOVERY_REQUIRED → 允许 committed receipt（此时必须匹配非空 theftType），空收据+null 类型亦合法；
- 其余全部结果（含 ROLLED_BACK、AUDIT_FAILED）→ receipt 必须 empty；
- 未抽类型结果允许 theftType=null；已抽类型失败可保留 theftType（receipt 仍空）；
- position 防御性复制；事件不可取消、无 settled/rewarded/cancelled。

`ShadowTheftReceipt` 交叉一致性收紧：itemId↔count>0、effectId↔duration>0 双向强制。
`ShadowAuditRecord` 交叉一致性：ENTITY↔targetType、EFFECT↔effectId、标量互斥、outcome↔auditState、
position 不可变。测试：`successRequiresMatchingTheftTypeAndReceipt` /
`nonAssetOutcomesRequireEmptyReceipt` / `rolledBackRequiresEmptyReceipt` /
`recoveryRequiredMayCarryCommittedReceipt` / `auditFailedNeverCarriesReceipt` /
`itemIdWithoutPositiveCountIsRejected` / `positionIsImmutableInRecord` 等。

## 8B.1-6 位置与视线

- `ShadowAttemptContext` 构造器对 position **直接保存 `immutable()` 副本**（不再用 equals 检测）；
  `ShadowAuditRecord` 同样复制（测试 `mutablePositionDoesNotAffectRecordAfterConstruction`）。
- 新增 `hasLineOfSight` 上下文输入；`ShadowVectorMath.computeFacts(..., hasLineOfSight)`：
  watched = 点积达标 **且** LOS=true；behind = 互斥 **且** LOS=true。
- 缺失/异常视线 fail-closed：**不授予 behind bonus，不施加 watched penalty**
  （安全策略决定：无证据不惩罚影窃者；文档化 + 测试）。
- 测试：`watchedRequiresLineOfSight` / `behindRequiresLineOfSight` /
  `lineOfSightKeepsFactsMutuallyExclusive` / `watcherBoundaryAtExactlyFortyFiveDegreesRequiresLos`。

## 8B.1-7 Dispatcher 与异常边界

- `publish(null)` 明确拒绝（`INVALID_CONTEXT`，测试 `nullEventIsExplicitlyRejected`）。
- 开关读取 catch `RuntimeException | LinkageError` fail-closed。
- 监听器异常日志加入 60 秒节流（`ShadowLogThrottle`）；init 幂等保持。
- Dispatcher 依旧不执行随机/转移/奖励。

## 8B.1-8 失败尝试审计策略（统一）

| 结果 | 审计记录 | 事件 | 幂等结算 |
|---|---|---|---|
| FRAMEWORK_DISABLED / INVALID_CONTEXT | 不创建 | 不发布 | 不结算 |
| AUDIT_FAILED（禁用/不可用/预写失败） | 不创建（存储不可用） | 不发布 | **结算**（防反复重掷） |
| DUPLICATE | 不新增 | 发布 | 已有 |
| PENDING 命中 → RECOVERY_REQUIRED | 保留原 PENDING | 发布 | 已有 |
| PROTECTED / COOLDOWN / NO_CANDIDATE / FAILED_ROLL / TRANSFER_FAILED / ROLLED_BACK / RECOVERY_REQUIRED / SUCCESS | **均创建有界记录** | 发布 | 结算 |

普通日志始终只输出 eventId+结果（节流 WARN），不输出物品/效果/余额等财产详情。

## 8B.1-9 测试矩阵（新增/重写）

- 新增 `ShadowIdempotencyTrackerTest`（9 用例）：容量/过期/登出/停服/生命周期幂等/独立性。
- 重写 `ShadowAttemptCoordinatorTest`（32 用例）：审计门零工作、预写失败 0 commit、
  commit 失败无收据、最终写失败 rollback 恰 1 次、rollback 失败 RECOVERY_REQUIRED、
  PENDING 阻塞、eventId/attempt-key 去重零工作、全失败路径幂等、epoch/serverTick 分离、
  mutable position、生产默认三重 fail-closed。
- 重写 `ShadowAuditStoreTest`（16 用例）：新 schema 往返（effectId/targetType）、upsert、
  最新 10,000 上限（可识别 id）、非法记录不占容量、未知 enum 丢弃、不可变快照。
- 扩充 `ShadowTheftEventTest`（+6，共 11）：SUCCESS/RECOVERY_REQUIRED/ROLLED_BACK/AUDIT_FAILED
  不变量。
- 扩充 `ShadowVectorMathTest`（+4）：LOS 正反例与边界。
- 扩充 `ShadowTheftReceiptTest`（+3）：交叉一致性。
- 扩充 `ShadowTheftEventDispatcherTest`（+1）：publish(null)。
- 更新 `ShadowCooldownTrackerTest`（容量 1024、移除 ATTEMPT/EVENT_ID、新增独立于洪泛的
  安全记录测试）、`ShadowFrameworkSettingsTest`（协调器新构造参数）。

## 8B.1-10 验证结果

- `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**。
- XML 汇总：**suites=130 tests=1089 failures=0 errors=0 skipped=0**。
- JAR 审计（`tcth-0.2.7.jar`，525,165 B）：无嵌套 JAR、无第三方 class 根
  （com/daqem、io/github/lightman314、xaero/pac 零命中）、无 shadow_thief 资源。
- 边界扫描：api/shadow 与 impl/shadow 零 PlayerInteractEvent / LC / OPAC /
  Inventory / health / FoodData / MobEffect / Jobs+Arc 引用。
- `git diff --check`（项目范围）通过。

## 8B.1-11 修改文件清单

**新增（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditState.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowIdempotencyTracker.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTransferPlan.java
```

**修改（main）**
```
src/main/java/com/tanrunn/tcth/api/shadow/ShadowTheftOutcome.java     （+ROLLED_BACK/RECOVERY_REQUIRED，AUDIT_FAILED 重定义）
src/main/java/com/tanrunn/tcth/api/shadow/ShadowTheftEvent.java      （结果/收据/类型不变量）
src/main/java/com/tanrunn/tcth/api/shadow/ShadowTheftReceipt.java    （交叉一致性收紧）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptContext.java （position 不可变副本 + hasLineOfSight）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowVectorMath.java     （LOS 参数）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowCooldownTracker.java（缩容 1024，移除 ATTEMPT/EVENT_ID）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTransferExecutor.java（prepare/commit/rollback）
src/main/java/com/tanrunn/tcth/impl/shadow/NoopShadowTransferExecutor.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditRecord.java    （schema v1）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditWriter.java    （upsert + byEventId）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStore.java     （上限/加载/upsert 修正）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java（审计门+两阶段+全路径幂等）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTheftEventDispatcher.java（null 拒绝/LinkageError/节流）
src/main/java/com/tanrunn/tcth/TCTHIntegration.java                 （+ShadowIdempotencyTracker.SHARED.init）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowIdempotencyTrackerTest.java     （新增）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinatorTest.java     （重写）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStoreTest.java             （重写）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowCooldownTrackerTest.java        （更新）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowVectorMathTest.java             （+LOS）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowTheftEventDispatcherTest.java   （+null）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowFrameworkSettingsTest.java      （构造参数）
src/test/java/com/tanrunn/tcth/api/shadow/ShadowTheftEventTest.java              （+不变量）
src/test/java/com/tanrunn/tcth/api/shadow/ShadowTheftReceiptTest.java            （+交叉一致性）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md   （本 8B.1 修订节）
CHANGELOG.md                                     （更新）
README.md / README_zh_CN.md                      （语义变化说明）
```

## 8B.1-12 遗留限制（如实记录）

1. SavedData 非 fsync WAL：崩溃瞬间 PENDING 记录可能悬空（→ RECOVERY_REQUIRED）或丢失最近记录；无数据库级原子性。
2. 每日额度仍未实现（8B 规划的接口不变）。
3. 真实 ITEM/HEALTH/HUNGER/EFFECT executor、交互入口、OPAC provider、能力树数值仍属 8C+。
4. COIN 仍 BLOCKED（LC 无原子转账 API；8B.1 未触碰该结论）。

—— 8B.1 修订完 ——

---

# 8B.1.1 修订 —— 事务与审计最终收口（BUILD-only）

- 触发：8B.1 复审六项收口问题（commit 后 receipt 类型不匹配 / EFFECT 记录规则 / ATTEMPT 键含 tick / PENDING 一次性告警 / 审计状态转换限制 / context 早期校验 + null 审计工厂）。
- 基线：8B.1 数字 **130 suites / 1089 tests** 已被本阶段**取代** → **130 suites / 1104 tests / 0 failures**。
- 结论：**8B.1.1 FRAMEWORK BUILD PASS / REAL ASSET TRANSFER NOT IMPLEMENTED / COIN BLOCKED / SERVER NOT STARTED / PLAYER LIVE NOT TESTED / commit·push 未执行**。

## 8B.1.1-1 commit 成功后 receipt 类型不匹配 → rollback 恰一次

8B.1 缺陷：commit 已执行但 `receipt.matches(selected.type())` 失败时返回普通
`TRANSFER_FAILED`（语义上等于「什么都没转移」），掩盖了已提交的资产。

修复（`ShadowAttemptCoordinator` 步骤 17）：
- 不匹配 → **rollback 恰好一次**：
  - rollback 成功 → `ROLLED_BACK`（收据空，reason `receipt_type_mismatch`）；
  - rollback 失败/异常 → **`RECOVERY_REQUIRED`**（reason `rollback_failed; receipt_type_mismatch`；
    收据为空——模糊收据不得冒充「已抽类型的已提交收据」，审计记录为
    RECOVERY_REQUIRED + 空标量 + 原因，资产状态如实标记为未知）。
- 绝不返回普通 TRANSFER_FAILED。
- 测试：`receiptMismatchAfterCommitRollsBackOnceToRolledBack`（rollback 计数=1、记录
  ROLLED_BACK/FINAL、事件收据空、0 SUCCESS）、`receiptMismatchAndRollbackFailureYieldsRecoveryRequired`、
  `receiptMismatchAndRollbackExceptionYieldsRecoveryRequired`。

## 8B.1.1-2 ShadowAuditRecord 资产规则按 outcome 收敛

8B.1 缺陷：构造器无条件要求「theftType=EFFECT → effectId 非空」，导致 PENDING /
FAILED_ROLL / TRANSFER_FAILED 等无资产记录无法以 EFFECT 类型落盘。

修复（`ShadowAuditRecord` 构造器）：
- 资产承载结果：**SUCCESS（恒有资产）与 RECOVERY_REQUIRED（实际携带资产时）**
  强制标量与 theftType 一致（ITEM→item 字段；COIN/HEALTH/HUNGER→numericAmount；
  EFFECT→effectId+duration）；
- 非资产结果（PENDING / FAILED_ROLL / TRANSFER_FAILED / ROLLED_BACK / NO_CANDIDATE /
  PROTECTED / COOLDOWN / DUPLICATE…）：**禁止任何资产字段**——theftType=EFFECT 且
  effectId=null 完全合法；
- RECOVERY_REQUIRED 允许空标量（资产状态模糊：如收据类型不匹配场景）；
- 测试：EFFECT 全状态机 `effectFailedRollRecordsEffectTypeWithoutEffectId` /
  `effectPrepareFailureRecordsEffectTypeWithoutEffectId` /
  `effectPendingRecordHasNoEffectId` / `effectCommitFailureRecordsEffectTypeWithoutEffectId` /
  `effectSuccessRecordsEffectId` + 存储级 `nonAssetEffectRecordsAreLegal`。

## 8B.1.1-3 ATTEMPT 键真正包含 serverTick

`ShadowIdempotencyTracker`：`markAttempt/isAttemptDuplicate(thiefId, targetId, serverTick)`，
键 = thief + target + serverTick。
- 同 tick 不同 eventId → DUPLICATE；
- 下一 tick 同一双方 → 幂等键不再误拦（其他冷却仍可拦截，但幂等键不拦）。
- 协调器全部调用点（PENDING 分支 / finishAuditedAttempt / finishAuditRefusal / 去重检查）
  统一携带 `context.serverTick()`。
- 测试：`attemptKeyIsThiefPlusTargetPlusServerTick` /
  `sameTickDifferentEventIdsAreDuplicates` / `nextTickSamePairIsNotBlockedByIdempotency`。

## 8B.1.1-4 PENDING 恢复告警只发布一次

- 步骤 5 重排：**内存 `hasEventId` 最优先** → 持久 `byEventId` → ATTEMPT 键；
- 首次发现 PENDING → `RECOVERY_REQUIRED` 事件 + **立即提交内存幂等**
  （eventId + thief/target/tick）；
- 同 JVM 再次调用 → `DUPLICATE`，**0 新事件、0 新审计**（DUPLICATE 从此不再发布事件，
  8B.1 表中「DUPLICATE 发布」同步修订）；
- 重启（全新 tracker）后由持久 PENDING 记录**再告警一次**。
- 测试：`pendingRecordForEventIdYieldsRecoveryRequired`（含同 JVM 重复断言）/
  `pendingRecordAlertsOnceMoreAfterRestart`（fresh tracker 模拟重启）。

## 8B.1.1-5 ShadowAuditStore 状态转换限制

`append` 从自由 upsert 改为受控转换：
- 新 eventId：可插入 PENDING 或 FINAL；
- 已有 PENDING：**仅允许转 FINAL（且必须带 outcome）**——预写终结路径；
- 已有 FINAL：仅允许**字节完全一致**的幂等重写（无变更）；
- 其余（FINAL→PENDING、FINAL→不同 FINAL、PENDING→PENDING）：**返回 false，原记录不动**。
- 测试：`stateTransitionsAreRestricted` / `finalInsertWithoutPendingIsAllowed`
  （新 FINAL 插入合法）。

## 8B.1.1-6 顺带收口

- `auditStoreFactory` 返回 **null** → `AUDIT_FAILED`（reason `audit_unavailable`），
  在 provider/随机/executor 之前 fail-closed（测试
  `auditFactoryReturningNullRefusesBeforeAnyWork`）。
- PLAYER/ENTITY ↔ targetType 不变量在**协调器步骤 2 早期校验**完成
  （ENTITY 缺 targetType 或 PLAYER 带 targetType → `INVALID_CONTEXT`），
  不再依赖后续记录构造异常间接判定（测试 `playerTargetWithTargetTypeIsInvalid` 与既有
  `entityContextWithoutTargetTypeIsInvalid`）。

## 8B.1.1-7 验证结果

- `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**。
- XML 汇总：**suites=130 tests=1104 failures=0 errors=0 skipped=0**（取代 8B.1 的 1089）。
- JAR 审计（`tcth-0.2.7.jar`，526,975 B）：无嵌套 JAR、无第三方 class 根、无 shadow_thief 资源。
- 边界扫描（api/shadow + impl/shadow）：零 PlayerInteractEvent / LC / OPAC /
  Inventory / health / FoodData / MobEffect / Jobs+Arc 引用。
- `git diff --check`（项目范围）通过。
- 未启动服务器、未烟雾、未部署、未 bump 版本、未 commit/push。

## 8B.1.1-8 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowIdempotencyTracker.java     （ATTEMPT 键 + serverTick）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditRecord.java            （资产规则按 outcome 收敛）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStore.java             （状态转换限制）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditWriter.java            （javadoc：受控 upsert）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java     （步骤 2/4/5/17 + tick 调用点）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinatorTest.java （+10：mismatch 回滚 ×3、
   EFFect 状态机 ×5、null 工厂、PLAYER+type、PENDING 一次性/重启）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStoreTest.java         （+3：状态转换、新 FINAL、非资产 EFFECT 记录）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowIdempotencyTrackerTest.java （+2：同 tick/次 tick 语义）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md   （本 8B.1.1 修订节）
CHANGELOG.md                                     （更新）
```

## 8B.1.1-9 遗留限制（延续 8B.1）

1. SavedData 非 fsync WAL：崩溃瞬间 PENDING 可能悬空（→ RECOVERY_REQUIRED 一次性告警）
   或丢失最近记录；无数据库级原子性。
2. 每日额度、真实 ITEM/HEALTH/HUNGER/EFFECT executor、交互入口、OPAC provider、
   能力树数值仍属 8C+。
3. COIN 仍 BLOCKED（LC 无原子转账 API）。

—— 8B.1.1 修订完 ——

---

# 8C.0 阶段报告 —— 影窃者交互入口、保护层与只读候选探测

- 范围：玩家交互入口 + 组合保护 + 只读候选探测 + 审计身份加固 + debug 命令。仍禁止真实资产转移。
- 基线：8B.1.1 数字（130 suites / 1104 tests）被本阶段**取代** → **137 suites / 1175 tests / 0 failures**。
- 结论：**INTERACTION STRUCTURAL PASS / PROTECTION STRUCTURAL PASS / REAL ASSET TRANSFER NOT IMPLEMENTED / SERVER NOT STARTED / PLAYER LIVE NOT TESTED / commit·push 未执行**。

## 8C.0-1 玩家交互入口（`PlayerInteractHandler`）

- 监听 `PlayerInteractEvent.EntityInteract`（NeoForge.EVENT_BUS，**EventPriority.LOW**——
  OPAC 自己的处理器先跑并取消受保护交互，我们跳过已取消事件，避免与 OPAC 消息重复）。
- 入口条件（全部 fail-closed）：服务端（`getSide()==SERVER` 且真实 `ServerLevel`）；
  真实 `ServerPlayer`（非 FakePlayer）；`MAIN_HAND`；潜行（`isShiftKeyDown`，服务端由包
  内标志设置）；**双手空手**；目标为其他真实玩家（非自己、非 FakePlayer、存活）；
  同维度（`target.level() == level` 同一实例）；距离（`canInteractWithEntity(boundingBox,
  entityInteractionRange())`）；事件未被其他处理器取消。
- **每个事件只调用 coordinator 一次**（测试 `validInteractionInvokesCoordinatorExactlyOnce`）；
  同 tick 重复包由 coordinator 的 ATTEMPT 键（thief+target+serverTick）去重。
- **不使用 Mixin**；不满足条件时事件原样放行（vanilla 右键无副作用）；8C.0 **不取消事件**
  （Noop executor 下无成功可取消；取消逻辑随真实 executor 落地）。
- 默认总开关继续 `false` → 监听器惰性（coordinator 第一道门 FRAMEWORK_DISABLED）。
- 上下文构造：`hasLineOfSight=false`（未做射线检测，fail-closed，8C.1 补真实 LOS）。
- 测试（15）：双手/双端/取消/非潜行/非空手/自己/FakePlayer/非玩家目标/死亡/跨维度/
  超距负例 + 正例恰一次 + 异常隔离 + 上下文字段。

## 8C.0-2 组合保护服务（`ShadowCompositeProtectionService`）

顺序（全部 fail-closed）：自身目标 → FakePlayer/旁观/创造（game mode）→ 玩家目标
不可解析/死亡/断线 → **新玩家保护**（`Stats.PLAY_TIME` 服务端已验证游戏时间 <
`shadowNewPlayerProtectionTicks`，默认 72000 tick=1h；配置读取失败 → 全员视为新玩家
拒绝）→ **出生点保护**（vanilla `MinecraftServer.isUnderSpawnProtection`，javap 验证：
主世界 + 非 OP + 半径内；position 为 null 无法评估 → 拒绝）→ **OPAC 区域 provider**。

- OPAC：`OpacProtectionProvider`（javap 权威：`OpenPACServerAPI.get(server)
  .getChunkProtection().onEntityInteraction(interactor, interactor, target, null,
  MAIN_HAND, false, false, true)`，与 OPAC 自身 EntityInteract 处理器同款调用）；
  可选依赖**字符串隔离**（`OpacProtectionProviderFactory`：`ModList.isLoaded
  ("openpartiesandclaims")` + `Class.forName`；mods.toml 零 OPAC 声明）；
  OPAC 缺失/API 异常/目标不可解析/查询异常 → 全部拒绝。
- 主城/商店保护：**无可靠坐标来源，如实不提供**（不虚报支持）。
- 生产默认：`defaults()` 接线 = 只读 provider + 组合保护 + **Noop executor**。
- 测试（18）：自身/FakePlayer/旁观/创造/死亡/断线/不可解析/新玩家/阈值读取失败/
  出生点/空位置/OPAC 缺失·允许·拒绝·UNKNOWN·异常 + provider 异常不放过。

## 8C.0-3 只读候选探测（`PlayerReadonlyCandidateProvider`）

- ITEM：**仅主背包槽 0..35**（装备 36..39 与副手 40 永不探测）；排除
  `#tcth:unstealable_items` 标签与容器组件（CONTAINER / CONTAINER_LOOT）物品；
  影窃者需有接收空间（可合并栈或空槽）。
- HEALTH：目标 `getHealth() > 2.0` 保护线且影窃者未满血。
- HUNGER：目标 `getFoodLevel() > 4` 保护线且影窃者未饱。
- EFFECT：白名单 `#tcth:stealable_effects` + 黑名单 `#tcth:unstealable_effects`
  （黑名单优先）+ `isBeneficial()` + 有限时长 + 非 ambient（信标启发式，8A 记录）。
- **COIN 恒不进入候选**（类型级保证 + 协调器剔除双保险）。
- 只读保证：不修改任何状态；候选只含类型/权重，**不向客户端泄露槽位、效果或余额**。
- 测试（23）：主背包边界、容器/标签排除、容量/合并、健康/饥饿保护线、效果五条件、
  COIN 恒缺、探测前后资产逐槽比对不变。

## 8C.0-4 生产 wiring 三重 fail-closed 保持

`ShadowAttemptCoordinator.defaults()`：只读 provider + 组合保护 + **NoopShadowTransferExecutor**
（prepare→null / commit→failed / rollback→false）。测试 `productionWiringStillInertWithSwitchesOn`：
开关全开也只能得到 PROTECTED / NO_CANDIDATE / TRANSFER_FAILED，收据恒空；
`ShadowAttemptCoordinator.defaults()` 默认总开关 OFF → FRAMEWORK_DISABLED。

## 8C.0-5 审计身份字段加固（`ShadowAuditStore`）

PENDING → FINAL 转换除状态/结果/资产字段外，必须保持 thiefId、targetId、targetKind、
targetType、theftType、dimension、position、serverTick 全部一致——禁止借同一 eventId
替换审计主体（测试 `pendingToFinalCannotSwapTheAuditSubject`：8 种身份篡改全部拒绝、
原记录保留、合法转换仍通过）。

## 8C.0-6 `/tcth debug shadow on|off|status`

- 权限 ≥3；默认关闭；内存开关（重启复位）。
- 开启后 coordinator 每行只输出 eventId、outcome、候选类型、保护结果与 reason
  （`ShadowDebug` + 协调器三处 INFO 日志），不记录背包内容、效果详情或余额。
- 测试（2）：命令切换开关 + 无权限拒绝。

## 8C.0-7 测试与验证

- 新增 7 个测试类（69 用例）：PlayerInteractHandlerTest(15)、
  ShadowCompositeProtectionServiceTest(18)、PlayerReadonlyCandidateProviderTest(23)、
  OpacProtectionProviderTest(5)、OpacProtectionProviderFactoryTest(2)、
  NoopShadowTransferExecutorTest(4)、TcthShadowDebugCommandTest(2)。
- `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**。
- XML：**suites=137 tests=1175 failures=0 errors=0 skipped=0**（取代 8B.1.1 的 1104）。
- JAR（`tcth-0.2.7.jar`，541,991 B）：无嵌套 JAR、无第三方 class（OPAC 仅 compileOnly，
  未打包）、无 shadow_thief 资源；mods.toml 零 OPAC 声明。
- 边界扫描：`PlayerInteractEvent` 仅存在于 PlayerInteractHandler；`xaero/openpartiesandclaims`
  仅存在于 OPAC provider/factory；Inventory/FoodData/MobEffect 仅存在于只读探测与标签；
  LC/Jobs+Arc/setHealth/addEffect 全库零引用。
- `git diff --check`（项目范围）通过；未启动服务器、未烟雾、未部署、未 bump、未 commit/push。

## 8C.0-8 修改文件清单

**新增（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerInteractHandler.java
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerReadonlyCandidateProvider.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTags.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowCompositeProtectionService.java
src/main/java/com/tanrunn/tcth/impl/shadow/protection/OpacProtectionProvider.java
src/main/java/com/tanrunn/tcth/impl/shadow/protection/OpacProtectionProviderFactory.java
src/main/java/com/tanrunn/tcth/impl/debug/ShadowDebug.java
src/main/resources/data/tcth/tags/item/unstealable_items.json
src/main/resources/data/tcth/tags/item/high_value_stealable_items.json
src/main/resources/data/tcth/tags/mob_effect/stealable_effects.json
src/main/resources/data/tcth/tags/mob_effect/unstealable_effects.json
```

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java  （defaults 接线 + debug 日志）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStore.java          （PENDING→FINAL 身份字段一致）
src/main/java/com/tanrunn/tcth/Config.java                               （+shadowNewPlayerProtectionTicks）
src/main/java/com/tanrunn/tcth/TCTHIntegration.java                      （+PlayerInteractHandler.init）
src/main/java/com/tanrunn/tcth/impl/command/TcthCommands.java            （+debug shadow 子命令）
build.gradle                                                             （compileOnly/testImplementation blank:openpartiesandclaims:0.29.3）
```

**新增（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerInteractHandlerTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowCompositeProtectionServiceTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerReadonlyCandidateProviderTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/NoopShadowTransferExecutorTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/protection/OpacProtectionProviderTest.java
src/test/java/com/tanrunn/tcth/impl/compat/OpacProtectionProviderFactoryTest.java
src/test/java/com/tanrunn/tcth/impl/command/TcthShadowDebugCommandTest.java
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowBoundaryGuardTest.java  （按文件白名单重构）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStoreTest.java     （+身份篡改测试）
```

**本地（git-ignored）**：`dev-mods/openpartiesandclaims-0.29.3.jar`（flatDir 依赖）。

## 8C.0-9 遗留限制

1. `hasLineOfSight=false` 恒为 false（真实射线 8C.1 补）；成功率 behind/watched 修正暂不生效。
2. EFFECT ambient 排除为 8A 启发式；白名单为管理员闸门。
3. 主城/商店保护无可靠来源，未提供。
4. 每日额度、能力树、职业数据、真实 executor（ITEM/HEALTH/HUNGER/EFFECT/COIN）仍属 8C+；
   COIN 仍 BLOCKED。
5. 事件不取消（与真实 executor 同批落地）。

—— 8C.0 报告完 ——

---

# 8C.0.1 修订 —— 结构缺口修正（BUILD-only）

- 触发：复审五项结构缺口（真实视线写入 / ITEM 逐栈容量 / 嵌套 CI 模板缺 jar / 高价值标签 / 验证数字作废）。
- 基线：8C.0 数字（137 suites / 1175 tests）已被本阶段**取代** → **137 suites / 1181 tests / 0 failures**。
- 结论：**INTERACTION STRUCTURAL PASS / PROTECTION STRUCTURAL PASS / REAL ASSET TRANSFER NOT IMPLEMENTED / SERVER NOT STARTED / PLAYER LIVE NOT TESTED / commit·push 未执行**。

## 8C.0.1-1 真实视线写入（`PlayerInteractHandler`）

- 上下文 `hasLineOfSight` 改为服务端真实射线 `thief.hasLineOfSight(victim)`：
  - `true` → behind/watched 正常按 8B.1.1 向量公式计算；
  - API 异常（`RuntimeException | LinkageError`）→ **fail-closed=false**（不授予 bonus、不施加 penalty）。
- 测试（+4）：`lineOfSightTrueIsWrittenIntoContext` / `lineOfSightFalseIsWrittenIntoContext` /
  `lineOfSightExceptionFailsClosedToFalse`（异常后 attempt 仍恰一次）/
  `lineOfSightFeedsMutuallyExclusiveFacts`（true/false 两路径均转发且 behind/watched 互斥，
  与 `ShadowVectorMathTest` 的 360° 互斥扫描呼应）。

## 8C.0.1-2 ITEM 候选逐栈容量（`PlayerReadonlyCandidateProvider`）

- 修复前：只对「第一个可偷栈」做容量判断——第一个不可接收、第二个可合并时错误地判无 ITEM。
- 修复后：遍历受害者主背包 0..35，**每个可偷栈分别判断**影窃者是否有空槽或可合并槽，
  任意一项可真实接收 1 个 → ITEM 可用；仍只读、不选择最终槽位、不修改物品。
- 测试（+2）：`itemCandidateRequiresCapacityPerStack`（回归：第一个不可接收、第二个可合并
  → ITEM 可用）、`itemCandidateAbsentWhenNoStackIsReceivable`（全部不可接收 → 无 ITEM）。

## 8C.0.1-3 嵌套 CI 模板补 jar（`.github/workflows/build.yml`）

- 新增两条固定版本（无 latest）+ 服务器真实 SHA-256 校验：
  - `brewinandchewin-4.5.0.jar` ← `https://cdn.modrinth.com/data/hIu9KJTT/versions/MbcR48Ou/BrewinAndChewin-neoforge-4.5.0%2B1.21.1.jar`
    SHA `9f6581823c2449dde4ac1e9b4f5a7cc226c42e058e656741ab392f714f443971`（服务器 JAR 实测一致）；
  - `openpartiesandclaims-0.29.3.jar` ← `https://cdn.modrinth.com/data/gF3BGWvG/versions/h4aUy171/open-parties-and-claims-neoforge-1.21.1-0.29.3.jar`
    SHA `a49d18f92dcde9489a1938012bd5a3e4ba13ed7ea4ba1ec25dc0b1a28f886028`（服务器 JAR 实测一致）。
- **实机验证**：清空临时 `dev-mods/` 后按 workflow 下载块逐条执行——11 个 jar 全部
  `sha256sum -c` OK，随后 `clean build` 通过（证明模板可离线重建本地依赖）。

## 8C.0.1-4 高价值物品标签

- `data/tcth/tags/item/high_value_stealable_items.json`：**空 values**（与
  `ShadowTags.HIGH_VALUE_STEALABLE_ITEMS` 声明及 8A 设计一致）；未填入任何未经审计物品；
  已确认打包进 JAR（`high_value_stealable_items.json` 在包内，1 命中）。

## 8C.0.1-5 验证结果

- 定向测试全绿 → `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**。
- XML：**suites=137 tests=1181 failures=0 errors=0 skipped=0**（取代 8C.0 的 1175）。
- JAR（`tcth-0.2.7.jar`，541,947 B）：无嵌套 JAR、无第三方 class（OPAC 仅 compileOnly）、
  无 shadow_thief 资源、high_value 标签在包内。
- `git diff --check -- .`（项目子树）：通过。仓库根全量检查仅剩
  `Server/config/c2me.toml` 的既有尾随空格（服务器运行回写，本阶段未触碰，与影窃无关）。
- 未启动服务器、未烟雾、未部署、未 bump、未 commit/push。

## 8C.0.1-6 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerInteractHandler.java          （hasLineOfSight 真实射线）
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerReadonlyCandidateProvider.java（逐栈容量）
.github/workflows/build.yml                                                    （+BAC/OPAC 固定版本+SHA）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerInteractHandlerTest.java          （+4 LOS 用例）
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerReadonlyCandidateProviderTest.java（+2 逐栈容量用例）
```

**确认（无需改动）**
```
src/main/resources/data/tcth/tags/item/high_value_stealable_items.json  （空 values，已在包内）
```

**本地（git-ignored）**：`dev-mods/` 已按 workflow 全量重建（11 个 jar，SHA 校验通过）。

—— 8C.0.1 修订完 ——

---

# 8C.1 阶段报告 —— 玩家资产事务引擎（BUILD-only、未启用）

- 范围：实现 ITEM / HEALTH / HUNGER / EFFECT 的真实 prepare / commit / rollback；**生产 wiring 保持 Noop**。
- 基线：8C.0.1 数字（137 suites / 1181 tests）已被本阶段**取代** → **138 suites / 1215 tests / 0 failures**。
- 结论：**TRANSACTION ENGINE BUILD PASS / PRODUCTION WIRING DISABLED / REAL PLAYER ENTRY STILL NO-OP / SERVER NOT STARTED / PLAYER LIVE NOT TESTED / commit·push 未执行**。

## 8C.1-1 事务协议重构

- `ShadowTransferResult` 三态：**COMMITTED**（带收据）/ **FAILED_CLEAN**（零改动、带原因）/
  **RECOVERY_REQUIRED**（可能已部分转移且内部回滚失败，带收据、绝不伪报普通失败）。
- `ShadowTransferPlan` sealed 接口 + 四类计划（`ItemPlan` / `HealthPlan` / `HungerPlan` /
  `EffectPlan`）+ `Generic`（测试/fake 用）：计划只存 UUID、槽位、数值与**防御性快照**
  （ItemStack.copy / MobEffectInstance 拷贝），**不持有 Player/Inventory 活引用**；
  EffectPlan 用 effectId（ResourceLocation）标识效果。
- `ShadowTransferExecutor.prepare(context, selected, random)`：prepare 可做**一次**具体对象
  选择随机（ITEM 选栈 / EFFECT 选效果）；commit 内部异常 → 内部回滚：回滚成功 →
  FAILED_CLEAN，回滚失败 → RECOVERY_REQUIRED；外层最终审计失败仍只调 rollback 一次。

## 8C.1-2 coordinator 顺序调整

旧：draw → chance → roll → prepare → …；新（8C.1 §2）：
`类型抽取恰一次(nextLong) → prepare/资产只读选择(prepare 内可选 nextInt 一次) →
成功率（含 plan.successModifier）→ 成功随机恰一次(nextDouble) → PENDING → 预提交重检 →
commit → FINAL`。
- prepare 发现资产失效 → TRANSFER_FAILED "prepare_failed"，**不重抽类型**；
- 预提交重检（8C.1 §7）：保护服务复查（`protection_drift`）、目标存活/同世界
  （`target_drift`）、距离（`distance_drift`）——任一漂移在 commit 前 fail-closed；
- executor 抛异常 → RECOVERY_REQUIRED（executor_exception），不伪报普通失败。
- 随机调用次数测试：nextLong ×1 + nextInt ×1 + nextDouble ×1（`coordinatorRandomCallCountsWithRealEngine`）。

## 8C.1-3 ITEM

- 仅受害者主背包 0..35；在「可偷且可接收」的栈中**服务端均匀选择**（一次 nextInt）；
- 真实转移 1 个、**完整组件保留**（CUSTOM_NAME 测试）；装备/副手/容器组件/unstealable 恒排除；
- `#tcth:high_value_stealable_items` → `ItemPlan.successModifier = -0.10`；
- 满背包/状态漂移 → FAILED_CLEAN（slot_drift / thief_slot_drift）；
- 回滚**精确恢复双方槽位**（快照 setItem）。
- 测试：成功+组件、均匀选择、高价值修正、prepare 失败、双漂移、部分异常内部回滚成功
  （FAILED_CLEAN）、内部回滚失败（RECOVERY_REQUIRED）、外层回滚精确。

## 8C.1-4 HEALTH

- 基础转移 1 点；目标最低保留 2 点；只治疗实际扣除量（`heal` 封顶、不超量）；
- 保存双方原始生命，commit 时按当前状态重算实际量（受目标保护线与影窃者上限约束）；
- 漂移（目标跌至保护线 / 影窃者已满）→ FAILED_CLEAN health_drift；
- 内部异常回滚 / 外层回滚均精确恢复双方生命。

## 8C.1-5 HUNGER

- 基础转移 2 点；目标最低保留 4 点；饥饿与**少量 saturation**（上限 1.0）都真实转移，
  受双方上限约束；receipt.numericAmount = 实际饥饿点数；
- 漂移 → hunger_drift；内部异常回滚（一次性抛错 → FAILED_CLEAN；永久抛错 →
  RECOVERY_REQUIRED）；外层回滚恢复双方 foodLevel/saturation 精确值。

## 8C.1-6 EFFECT

- 白名单 `#tcth:stealable_effects` 且不在 blacklist；正面、有限、非 ambient；
- 基础最多 200 tick；从目标扣除实际时间、影窃者获得同等 tick、**amplifier 不提高**；
- 影窃者已有更强（amplifier 更高）或同 amplifier 且时长 ≥ 转移量 → **不得成为可选项**；
- 目标剩余为 0 时效果移除；回滚恢复双方**精确快照**（时长/等级/标志，经
  MobEffectInstance 拷贝 + 位置键控测试 harness）。
- 测试：200 tick 上限、剩余时间封顶、更强影窃者排除、同等级时长排除、ambient/黑名单/
  非白名单过滤、漂移（效果消失 / commit 时影窃者变强）、外层回滚精确。

## 8C.1-7 防御要求

- commit 前重检：目标、槽位快照、距离/世界（coordinator 侧）与保护状态——任一漂移
  fail-closed；
- COIN 恒拒绝（`prepare` 直接返回 null，`commit` 亦拒）；
- **生产 defaults 继续 Noop**：`ShadowAttemptCoordinator.defaults()` 不接线本引擎；
  `productionWiringStillInertWithSwitchesOn` 证明开关全开也只能得到
  PROTECTED/NO_CANDIDATE/TRANSFER_FAILED、收据恒空、资产零变化。

## 8C.1-8 测试与验证

- 新增 `PlayerAssetTransferExecutorTest`（34 用例）+ 既有测试适配新协议
  （ShadowTransferResult 三态 / prepare 带 RandomSource / Generic plan / 预提交重检桩）。
- `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**。
- XML：**suites=138 tests=1215 failures=0 errors=0 skipped=0**（取代 8C.0.1 的 1181）。
- JAR（`tcth-0.2.7.jar`，559,975 B）：无嵌套、无第三方类、无 shadow_thief 预设。
- 边界守卫：资产变更 API（setHealth/setFoodLevel/setSaturation/removeEffect/forceAddEffect/
  heal/setItem/removeItem）仅允许存在于 `PlayerAssetTransferExecutor.java`；其余影窃代码
  依旧零资产变更引用；LC/Jobs+Arc 全库零引用。
- `git diff --check`（项目子树）通过；未启动服务器、未烟雾、未部署、未 bump、未 commit/push。

## 8C.1-9 修改文件清单

**新增（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTransferState.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTransferPlan.java（sealed + Generic）
src/main/java/com/tanrunn/tcth/impl/shadow/ItemPlan.java
src/main/java/com/tanrunn/tcth/impl/shadow/HealthPlan.java
src/main/java/com/tanrunn/tcth/impl/shadow/HungerPlan.java
src/main/java/com/tanrunn/tcth/impl/shadow/EffectPlan.java
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutor.java（真实引擎）
```

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTransferResult.java  （三态）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTransferExecutor.java（prepare 带 RandomSource）
src/main/java/com/tanrunn/tcth/impl/shadow/NoopShadowTransferExecutor.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java（顺序 + 预提交重检 + 三态映射）
```

**新增/修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutorTest.java（新增 34 用例）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinatorTest.java（适配）
src/test/java/com/tanrunn/tcth/impl/shadow/NoopShadowTransferExecutorTest.java（适配）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowBoundaryGuardTest.java（引擎文件白名单）
```

## 8C.1-10 遗留限制

1. **生产 wiring 未启用引擎**（BUILD-only）：`defaults()` 仍为 Noop；启用需单独复审。
2. 真实交互入口仍产生 NO-CANDIDATE/失败结果（REAL PLAYER ENTRY STILL NO-OP 指生产链路
   不经真实转移；引擎仅测试可见）。
3. COIN 仍 BLOCKED；每日额度、能力树、职业数据、经验奖励仍属后续。
4. 事件不取消；出生点/新玩家/OPAC 保护策略照旧。

—— 8C.1 报告完 ——

---

# 8C.1.1 修订 —— 影窃事务资产守恒修正（BUILD-only）

- 触发：8C.1 复审的资产守恒问题（内部恢复路径 / 严格快照 / heal 实际增量 / saturation 合法性 / EFFECT 最安全策略 / 协议加固）。
- 基线：8C.1 初版结论（138 suites / 1215 tests）**已被本阶段修订并取代** → **138 suites / 1227 tests / 0 failures**。
- 结论：**TRANSACTION ENGINE BUILD PASS / PRODUCTION WIRING DISABLED / REAL PLAYER ENTRY STILL NO-OP / SERVER NOT STARTED / PLAYER LIVE NOT TESTED / commit·push 未执行**。

## 8C.1.1-1 ITEM 内部恢复路径

- 未注册物品路径修复：`itemIdOf` 变为**可注入 seam**（`setItemIdResolverForTesting`，
  生产默认 `getItemHolder().unwrapKey()`）；返回 null 时先内部恢复——恢复成功 →
  FAILED_CLEAN "unregistered_item"，恢复失败 → **RECOVERY_REQUIRED**（携带 empty 收据，
  非空收据约束满足）；其余 commit 异常路径同样「恢复成功才 FAILED_CLEAN，失败必须
  RECOVERY_REQUIRED」。
- 测试：`unregisteredItemRestoresCleanly`（双方槽位精确恢复）、
  `unregisteredItemRestoreFailureIsRecoveryRequired`（seam + 恢复抛错）。

## 8C.1.1-2 严格快照比较（HEALTH / HUNGER / EFFECT）

- **commit 前严格比较 prepare 快照，任一方状态变化即 FAILED_CLEAN**，禁止按漂移后的
  状态重新计算继续结算：
  - HEALTH：双方 health 必须与快照完全一致（`health_drift`）；
  - HUNGER：foodLevel + saturation 四值逐一一致（`hunger_drift`）；
  - EFFECT：受害者效果实例的 duration / amplifier / ambient / visible / showIcon
    五要素与快照一致（`effect_drift`），影窃者仍不得持有该效果（`thief_effect_drift`）。
- 测试：`healthStrictSnapshotDriftFailsClean` / `hungerSaturationDriftFailsClean` /
  `effectDriftFailsClean` / `effectThiefStrongerAtCommitFailsClean`。

## 8C.1.1-3 HEALTH 实际 heal 增量

- 考虑 `LivingHealEvent` 会修改或取消 heal：apply 后**测量实际增量** `actualGain =
  thiefAfter - thiefBefore`；目标只允许扣除完全相等的数值（`actualLoss == transfer`
  且 `actualGain == transfer`，epsilon 容差）；增量为 0、数值不等 → 完整回滚；
  回滚失败 → RECOVERY_REQUIRED；receipt 记录**实际增量**。
- 测试：`healthZeroActualHealFailsCleanWithFullRestore`（heal 被取消 → 双方精确恢复）、
  `healthPartialHealMismatchFailsClean`（heal 只给 0.5 → 绝不允许「扣 1.0 得 0.5」）。

## 8C.1.1-4 HUNGER saturation 合法性

- 始终满足 `0 <= saturation <= foodLevel`；按转移后双方 foodLevel 计算可行区间：
  `sLow = max(0, vfSat - (vf - f))`，`sHigh = min(1, (tf + f) - tfSat, vfSat)`；
  `sLow > sHigh` → **不生成计划**（或 commit 前状态非法 → 不结算）；
  转移量取可行区间的最大合法值（≤1 点 budget）。
- 测试：`hungerHighSaturationInfeasibleProducesNoPlan`（高饱和无法在 1 点内守恒 →
  无计划）、`hungerSaturationTransferStaysWithinFoodLevels`（转移后双方 sat ≤ food）、
  `hungerSaturationDriftFailsClean`。

## 8C.1.1-5 EFFECT 最安全策略

- **影窃者已拥有同类效果 → 该效果不进入候选**（prepare 直接排除，不再做 amplifier/
  时长推理）；commit 前严格核对目标效果五要素；`forceAddEffect` 后**验证双方真实
  结果**（受害者恰好减少 actual、影窃者恰好增加 actual），不符 → 恢复快照 →
  FAILED_CLEAN "effect_post_mismatch" / RECOVERY_REQUIRED——绝不允许「目标 -200、
  影窃者 +100」。
- 测试：既有 `effectPrepareExcludesStrongerThief` /
  `effectPrepareExcludesLongerSameAmplifierThief`（新规则下更严格）+ post-state
  验证路径 `effectCommitTransfersUpToTwoHundredTicksAtSameAmplifier` /
  `effectTransferIsCappedByVictimRemainingTime`。

## 8C.1.1-6 协议加固

- **prepare 后类型校验**：`plan.type() != selected.type()` → 不抽成功随机、不提交
  资产，TRANSFER_FAILED "plan_type_mismatch"（coordinator 测试证明 commit/rollback
  零调用、nextDouble 零调用、资产零变化）；
- commit / rollback 同样拒绝类型不一致（FAILED_CLEAN / false）；
- `ShadowTransferResult`：COMMITTED 与 **RECOVERY_REQUIRED 均强制携带非空 receipt**
  （构造器校验）；
- **rollback 写回前验证 owned-state**：当前状态必须是本事务可能产生的状态
  （pre / 部分 / post）；外部变化绝不覆盖（ITEM 槽位异种物品、HEALTH 超出事务
  可能范围等 → 返回 false）。HEALTH 的 owned 区间按事务可能范围
  `[before, before + transfer]` 放宽（LivingHealEvent 可产生任意增量）。
- 测试：`protocolTypeMismatchRefusesCommitAndRollback` /
  `itemRollbackRefusesExternalSlotChanges` / `healthRollbackRefusesExternalChanges` /
  `coordinatorRejectsPlanTypeMismatchWithoutRoll`。

## 8C.1.1-7 测试与验证

- 新增 12 个回归用例（合计 1227）；`./gradlew clean build --no-daemon` **BUILD SUCCESSFUL**；
- XML：**suites=138 tests=1227 failures=0 errors=0 skipped=0**（取代 8C.1 的 1215）；
- JAR（`tcth-0.2.7.jar`，562,239 B）：无嵌套、无第三方类、无 shadow_thief 预设；
- 生产 `defaults()` 保持 **Noop**（未接线引擎；未进入真实交互接线、未启用职业经验/
  能力树/COIN）；`git diff --check` 通过；未部署、未启动、未烟雾、未在线测试、
  未 commit/push。

## 8C.1.1-8 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutor.java（六项守恒修正 + seam）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java（prepare 类型校验）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowTransferResult.java（RECOVERY_REQUIRED 强制收据）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutorTest.java（+12 回归用例 + 适配）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.1.1 修订节）
CHANGELOG.md
```

## 8C.1.1-9 遗留限制

1. 生产 wiring 仍未接线引擎（BUILD-only）；启用需单独复审。
2. HEALTH owned 区间为事务可能范围（heal 可被 LivingHealEvent 修改），无法区分
   同区间内的外部微小变化——文档化取舍。
3. COIN 仍 BLOCKED；每日额度、能力树、职业数据、经验奖励仍属后续。

—— 8C.1.1 修订完 ——

---

# 8C.1.2 修订 —— 回滚真实性与阶段状态收口（BUILD-only）

- 触发：8C.1.1 复审（内部/外部回滚分离、EFFECT 阶段状态、恢复写回验证、owned-state 收紧）。
- 基线：8C.1.1 数字（138 suites / 1227 tests）已被本阶段**取代** → **138 suites / 1237 tests / 0 failures**。
- 结论：**TRANSACTION ENGINE BUILD PASS / PRODUCTION WIRING DISABLED / REAL PLAYER ENTRY STILL NO-OP / SERVER NOT STARTED / PLAYER LIVE NOT TESTED / commit·push 未执行**。

## 8C.1.2-1 内部/外部回滚分离

- 每个资产类型引入**显式枚举状态机**（完整合取，禁止逐字段独立 OR 的宽松笛卡尔状态）：
  - ITEM：PRE / VICTIM_REMOVED / COMMITTED / FOREIGN；
  - HEALTH：PRE / VICTIM_REDUCED（含 LivingHealEvent 可能产生的
    `[before, before+transfer]` 单一区间，文档化）/ COMMITTED / FOREIGN；
  - HUNGER：PRE / VICTIM_REDUCED / COMMITTED / FOREIGN；
  - EFFECT：PRE / VICTIM_REMOVED / VICTIM_REMAINDER_WRITTEN / COMMITTED / FOREIGN。
- **内部回滚**（commit 异常/不匹配/未注册路径）：接受 PRE 与全部中间态 + COMMITTED，
  拒绝 FOREIGN；**外部回滚**（coordinator 最终审计失败调用）：**只接受完整 COMMITTED
  post-state**，中间态一律拒绝（`externalRollbackRefusesIntermediateItemState`）。
- 每个状态由全部相关字段的完整合取判定（ITEM 双方槽位同物同件同数、HEALTH 双方数值、
  HUNGER 四值、EFFECT 五要素）。

## 8C.1.2-2 EFFECT 阶段状态

- **commit 必须检查 `removeEffect` 返回值**：移除被取消 → FAILED_CLEAN
  "effect_remove_rejected"，不继续转移（影窃者绝不会得到效果）；
- 四合法状态全字段比较：PRE（受害者=快照、影窃者无）、VICTIM_REMOVED（受害者无、
  影窃者无）、VICTIM_REMAINDER_WRITTEN（受害者=剩余量五要素、影窃者无）、
  COMMITTED（受害者=剩余量、影窃者=actual 五要素）；
- **restore 后重新读取双方效果并逐字段验证**（duration/amplifier/ambient/visible/icon）；
- `removeEffect` 被取消、`forceAddEffect` 被拒绝或无效 → restore 绝不返回 true；
- 能恢复 → FAILED_CLEAN/ROLLED_BACK，不能恢复 → RECOVERY_REQUIRED。
- 测试：`effectCancelledRemovalAbortsTheTransfer`（commit 移除被取消 → 影窃者无效果）、
  `effectRestoreWithCancelledThiefRemovalReturnsFalse`（restore 时影窃者仍持有效果 →
  rollback=false）、`effectRestoreWithNoOpForceAddReturnsFalse`、
  `effectInternalRestoreFromEveryIntermediateState`（VICTIM_REMOVED 精确恢复）、
  `effectRestoreFromRemainderWrittenState`（VICTIM_REMAINDER_WRITTEN 精确恢复）、
  `effectExternalLookalikeEffectIsNotOverwritten`（同时长不同 amplifier/flags 的外部
  效果 → FOREIGN → 不覆盖）。

## 8C.1.2-3 ITEM/HEALTH/HUNGER 写回验证

- 每个 restore 写回后**重新读取并核对完整快照**；`setItem`/`setHealth`/`setFoodLevel`/
  `setSaturation` 被 mock 成 no-op、被钳制或部分写入 → **返回 false**（绝不虚报 true）；
- 外部 rollback 仅允许完整提交后的精确状态。
- 测试：`itemRestoreNoOpWriteNeverReportsTrue` / `healthRestoreNoOpWriteNeverReportsTrue` /
  `hungerRestoreNoOpWriteNeverReportsTrue` / `externalRollbackRefusesIntermediateItemState`。

## 8C.1.2-4 测试与验证

- 新增 10 个回归用例（合计 1237）；`./gradlew clean build --no-daemon` **BUILD SUCCESSFUL**；
- XML：**suites=138 tests=1237 failures=0 errors=0 skipped=0**（取代 8C.1.1 的 1227）；
- JAR（`tcth-0.2.7.jar`，567,632 B）：无嵌套、无第三方类、无 shadow_thief 预设；
- 生产 `defaults()` 保持 **Noop**（引擎未接线；未部署/启动/烟雾/在线测试；未接
  职业经验/能力树/COIN）；`git diff --check` 通过；未 commit/push。

## 8C.1.2-5 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutor.java（状态机分类器 + 写回验证 + 内外分离）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutorTest.java（+10 回归用例）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.1.2 修订节）
CHANGELOG.md
```

## 8C.1.2-6 遗留限制

1. 生产 wiring 仍未接线引擎（BUILD-only）；启用需单独复审。
2. HEALTH 的 VICTIM_REDUCED 状态含 LivingHealEvent 修改窗口的单一区间（heal 增量
   对事务计划不可知），其余状态均为精确枚举值——文档化。
3. COIN 仍 BLOCKED；每日额度、能力树、职业数据、经验奖励仍属后续。

—— 8C.1.2 修订完 ——

---

# 8C.1.3 修订 —— 事务提交真实性与候选池一致性收口（BUILD-only）

- 触发：8C.1.2 复审（ITEM 单件来源栈 / commit 后置验证 / HUNGER 四写入状态机 / PRE 恢复 /
  候选池与 prepare 共享可行性）。
- 基线：8C.1.2 数字（138 suites / 1237 tests）已被本阶段**取代** → **138 suites / 1250 tests / 0 failures**。
- 结论：**TRANSACTION ENGINE BUILD PASS / PRODUCTION WIRING DISABLED / REAL PLAYER ENTRY STILL NO-OP / SERVER NOT STARTED / PLAYER LIVE NOT TESTED / commit·push 未执行**。

## 8C.1.3-1 ITEM 单件来源栈

- `victimStackBefore.count == 1` 时，取走后的合法状态为 **ItemStack.EMPTY**；
  `classifyItem` 对单件栈正确识别 VICTIM_REMOVED 与 COMMITTED（此前
  `isSameItemSameComponents(EMPTY, before)` 恒 false 的缺陷已修复）；
- 测试：`itemSingleItemStackCommitsAndRollsBackExactly`（正常提交 + 外部回滚精确恢复）、
  `itemSingleItemInternalRestoreFromRemovedState`（内部恢复）。

## 8C.1.3-2 ITEM commit 后置验证

- 写入影窃者槽位后**重新读取双方槽位**：来源恰好减少 1、接收方恰好增加 1 且组件
  完全一致才 COMMITTED（`itemCommittedState` 共享谓词，分类器与后置验证同一实现）；
- `setItem`/`grow` no-op、钳制或错误写入 → 内部恢复：恢复成功 FAILED_CLEAN
  "item_commit_write_mismatch"、失败 RECOVERY_REQUIRED；
- 测试：`itemReceiverNoOpWriteIsRecoveryRequired`→ 修正为
  `itemReceiverNoOpWriteIsRecoveryRequired`（no-op 写回被后置验证捕获，内部恢复
  合法完成 → FAILED_CLEAN + 受害者精确恢复）、`itemWrongReceiverWriteRestoresCleanly`
  （错误写入 → 恢复覆盖 → FAILED_CLEAN）。

## 8C.1.3-3 HUNGER 四写入状态机

- commit 四次独立写入（victim food / victim saturation / thief food / thief
  saturation），状态机枚举**每一个真实中间阶段**：
  PRE → VICTIM_FOOD_REDUCED → VICTIM_SAT_REDUCED → THIEF_FOOD_RAISED → COMMITTED；
- 任意 setter 抛异常、no-op、部分写入均可分类并尝试恢复；**四项真实后置值全部
  等于计划值才 COMMITTED**——仅 saturation 合法不足以证明转移成功；
- 钳制到「既非 pre 也非 reduced」的值 → FOREIGN → 内部恢复拒绝 → RECOVERY_REQUIRED
  （绝不虚构干净失败）；
- 外部 rollback 仍只接受完整 COMMITTED。
- 测试：`hungerInternalRestoreFromEachSetterException`（PRE）、
  `hungerInternalRestoreFromVictimSatState`（VICTIM_FOOD_REDUCED）、
  `hungerInternalRestoreFromThiefFoodState`（VICTIM_SAT_REDUCED）、
  `hungerInternalRestoreFromThiefSatState`（THIEF_FOOD_RAISED）、
  `hungerClampedPostValuesNeverCommit`（合法但不等 → 永不 COMMITTED）。

## 8C.1.3-4 PRE 内部恢复短路

- 分类结果已是 PRE → 直接返回 true，**不重新写资产**（不触发
  setHealth/heal/forceAddEffect/setItem/setFoodLevel 等副作用与事件）——四个 restore
  路径统一生效。

## 8C.1.3-5 候选池与 prepare 共享可行性

- 新增 `ShadowFeasibility`（只读共享判定，杜绝两套易漂移的公式）：
  - `effectIsCandidateFor(thief, instance)` = 白名单/黑名单/正面/有限/非 ambient
    **且影窃者未持有同类效果**——provider 与 prepareEffect 同源；
  - `computeHungerPlan(victimFood, thiefFood)` = 完整 food/saturation 守恒可行计划
    （保护线、20 上限、0 ≤ sat ≤ foodLevel、1 点 budget）——provider 与
    prepareHunger 同源（返回 null 即不可行）。
- 一致性测试：`candidatePresentImpliesPrepareNonNullWithoutDrift`（候选存在 ⇒
  无漂移时 prepare 必非 null）、`effectCandidatePoolExcludesThiefHeldEffects`、
  `hungerCandidatePoolExcludesInfeasibleSaturation`、
  `providerPoolDrawsExactlyOnceOverRenormalisedWeights`（仅 ITEM+HUNGER 入池、
  权重重算、抽取恰一次）。

## 8C.1.3-6 测试与验证

- 新增 13 个回归用例（合计 1250）；`./gradlew clean build --no-daemon` **BUILD SUCCESSFUL**；
- XML：**suites=138 tests=1250 failures=0 errors=0 skipped=0**（取代 8C.1.2 的 1237）；
- JAR（`tcth-0.2.7.jar`，569,265 B）：无嵌套、无第三方类、无 shadow_thief 预设；
- 生产 `defaults()` 保持 **Noop**（引擎未接线；未部署/启动/烟雾/在线测试；未接
  职业经验/能力树/COIN）；`git diff --check` 通过；未 commit/push。

## 8C.1.3-7 修改文件清单

**新增（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowFeasibility.java（共享只读可行性规则）
```

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutor.java（单件栈分类器/
  ITEM 后置验证/HUNGER 五态机/PRE 短路/共享规则接入）
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerReadonlyCandidateProvider.java（共享规则接入）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutorTest.java（+13 回归用例）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowBoundaryGuardTest.java（ShadowFeasibility 白名单）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.1.3 修订节）
CHANGELOG.md
```

## 8C.1.3-8 遗留限制

1. 生产 wiring 仍未接线引擎（BUILD-only）；启用需单独复审。
2. HUNGER 钳制写回产生 FOREIGN 值 → 内部回滚拒绝 → RECOVERY_REQUIRED（8C.1.2 的
   外部变化不覆盖原则延伸，文档化）。
3. COIN 仍 BLOCKED；每日额度、能力树、职业数据、经验奖励仍属后续。

—— 8C.1.3 修订完 ——

---

# 8C.2 阶段报告 —— 玩家影窃受控生产接线（BUILD-only，不部署）

- 范围：独立真实转移总闸、引擎接入 defaults()、交互取消与反馈、每日物品受害上限、
  只读审计命令、过期注释修正。
- 基线：8C.1.3 数字（138 suites / 1250 tests）已被本阶段**取代** → **140 suites / 1274 tests / 0 failures**。
- 结论：**TRANSACTION ENGINE BUILD PASS / PRODUCTION WIRING DISABLED / REAL PLAYER ENTRY STILL NO-OP / SERVER NOT STARTED / PLAYER LIVE NOT TESTED / commit·push 未执行**（不声称 PLAYER LIVE PASS）。

## 8C.2-1 独立真实转移总闸

- 新增 `shadowRealAssetTransfersEnabled=false`；必须同时满足 `Config.ENABLED` &&
  `shadowThiefIntegrationEnabled` && `shadowPlayerTheftEnabled` &&
  `shadowRealAssetTransfersEnabled`；配置读取异常 fail-closed（defaults() 的 safeGet
  → false；settings 供应商抛错 → INVALID_CONTEXT，均零工作）。
- **闸关闭时在候选池、随机、PENDING 审计和 executor 之前拒绝**（FRAMEWORK_DISABLED
  "real_asset_transfers_disabled"）：provider/prepare/commit 全部 0 调用、nextLong/
  nextDouble 0 调用、0 审计记录、0 事件（无失败暴露）；默认配置下资产绝对不变。
- 测试：`realTransferGateOffRefusesBeforeAnyWork` / `realTransferGateOffKeepsAssetsUntouched` /
  `settingsSupplierExceptionFailsClosed`。

## 8C.2-2 defaults() 接入 PlayerAssetTransferExecutor

- `ShadowAttemptCoordinator.defaults()` 接线真实引擎 + 组合保护 + 每日上限存储；
  但被 §1 总闸锁定为惰性——生产默认 assets 不变；`NoopShadowTransferExecutor` 保留
  供隔离测试使用（`productionWiringStillInertWithSwitchesOn` 适配后仍通过）。

## 8C.2-3 交互取消与反馈（PlayerInteractHandler 消费 Result）

- FRAMEWORK_DISABLED / INVALID_CONTEXT → **不取消原交互、不提示**；
- 正式进入影窃流程后的所有结果（PROTECTED / COOLDOWN / DUPLICATE / NO_CANDIDATE /
  FAILED_ROLL / TRANSFER_FAILED / AUDIT_FAILED / ROLLED_BACK / RECOVERY_REQUIRED /
  SUCCESS）→ **取消本次 EntityInteract**（`setCanceled(true)` +
  `setCancellationResult(SUCCESS)`），防止原交互继续执行；每个事件**恰一次反馈**。
- 测试：`frameworkDisabledIsNotCancelledAndGivesNoFeedback` /
  `invalidContextIsNotCancelledAndGivesNoFeedback` / `attemptOutcomesCancelTheInteractionExactlyOnce`
  / `oneFeedbackPerEvent`。

## 8C.2-4 玩家反馈（全部 Component.translatable）

- SUCCESS：影窃者看到具体收益（物品/生命/饥饿/效果），受害者看到具体损失但
  **默认不显示影窃者身份**（`successHidesTheThiefIdentityFromTheVictim`）；
- FAILED_ROLL：受害者看到影窃者姓名；失败者获得短暂发光 + 缓慢
  （`exposeThief`，GLOWING + MOVEMENT_SLOWDOWN 100 tick）；附近玩家收到低范围
  暴露提示（`exposeNearby`，12 格，`failedRollExposesTheThiefNameAndDebuffsThem`）；
- NO_CANDIDATE 仅提示「无物可窃」；技术错误一律通用文案
  （`technicalOutcomesNeverLeakInternalReasons`——不泄露堆栈/内部 reason）。
- 新增 16 条中英文案 key（`tcth.shadow.feedback.*`）。

## 8C.2-5 每日物品受害上限

- 新增 `shadowDailyItemLossLimit`（默认保守值 3）；按**受害者 UUID + UTC 日期**
  统计成功 ITEM 次数；**在随机和事务 prepare 前检查**；达上限仅从候选池移除 ITEM，
  HEALTH/HUNGER/EFFECT 不受影响；剩余权重自然重算、每次仍只抽一次类型。
- 存储：独立 SavedData `tcth_shadow_daily_limits.dat`（overworld 绑定）；有界
  （MAX_VICTIMS 1024 / MAX_DAYS_PER_VICTIM 64）、防御加载（坏 UUID/空日期/负数跳过、
  未来版本空载）、跨重启保持、**时间源可注入**（UTC `LocalDate` supplier）。
- SUCCESS ITEM 后计数为 best-effort（存储失败绝不回滚已提交转移，节流告警）。
- COIN 继续恒定关闭。
- 测试：`ShadowDailyLimitStoreTest`（7 用例：记录/上限/UTC 换日/往返/容量/损坏 NBT/
  未来版本）+ `dailyItemLimitPrunesOnlyItemAndRenormalises` /
  `dailyItemLimitBelowCapKeepsItem` / `successfulItemTheftRecordsTheDailyLoss`。

## 8C.2-6 只读审计命令

- `/tcth shadow audit recent [limit]`（权限 ≥3）、`/tcth shadow audit player <player>
  [limit]`（权限 ≥3；普通玩家只能查询自己——无权限时仅允许 `player == 自己`）；
- 输出 eventId、时间戳、双方 UUID/在线名称、kind/type、outcome、物品/数值、维度与
  坐标；**limit 严格限制**（1..100，缺省 20），禁止一次输出全部 10 000 条；
- **不提供 reset/delete/修改资产命令**（`noResetOrDeleteCommandsExist` 断言仅
  recent/player 两个子命令）。
- 命令测试：`recentRequiresPermission` / `playerQueryWithoutPermissionOnlyAllowsSelf` /
  `limitIsStrictlyBounded`。

## 8C.2-7 文档与过期注释修正

- Config 的 8B 旧描述（“empty provider/deny-all protection”）更新为当前状态：
  真实转移默认关闭、受独立总闸控制；明确 SavedData **不是 fsync WAL**，预写与最终
  写入间的崩溃存在 RECOVERY_REQUIRED 窗口，正式服启用前需运营确认；README 中英
  同步更新；**不声称 PLAYER LIVE PASS**。
- TCTHIntegration 的阶段注释同步刷新。

## 8C.2-8 测试与验证

- 新增 24 个用例（合计 1274）；`./gradlew clean build --no-daemon` **BUILD SUCCESSFUL**；
- XML：**suites=140 tests=1274 failures=0 errors=0 skipped=0**（取代 8C.1.3 的 1250）；
- JAR（`tcth-0.2.7.jar`，583,916 B）：无嵌套、无第三方类、无 shadow_thief 预设；
- 厨师/农夫/枪客/魔酿师全部既有测试无回归（全量 140 套件全绿）；
- `git diff --check` 通过；未部署、未启动、未烟雾、未在线测试；未做职业预设、
  经验、能力树、生物影窃或 COIN；未 commit/push。

## 8C.2-9 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/Config.java                                （+2 配置项 + 注释刷新）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowFrameworkSettings.java   （+2 字段）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitWriter.java    （新增接口）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStore.java     （新增 SavedData）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java  （总闸 + 每日上限 + defaults 接线）
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerInteractHandler.java     （Result 消费 + 取消 + 反馈）
src/main/java/com/tanrunn/tcth/impl/command/TcthCommands.java            （audit 子命令 + 命令树重构）
src/main/java/com/tanrunn/tcth/TCTHIntegration.java                      （注释刷新）
src/main/resources/assets/tcth/lang/en_us.json / zh_cn.json              （+16 反馈文案）
README.md / README_zh_CN.md                                              （总闸 + 非 WAL 说明）
```

**新增（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/FakeDailyLimits.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStoreTest.java
src/test/java/com/tanrunn/tcth/impl/command/TcthShadowAuditCommandTest.java
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinatorTest.java（+7 闸/上限用例 + 构造适配）
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerInteractHandlerTest.java   （+9 取消/反馈用例）
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutorTest.java（构造适配）
src/test/java/com/tanrunn/tcth/impl/shadow/NoopShadowTransferExecutorTest.java（构造适配 + 接线断言）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowFrameworkSettingsTest.java （构造适配）
src/test/java/com/tanrunn/tcth/impl/command/TcthShadowDebugCommandTest.java（新命令路径）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.2 修订节）
CHANGELOG.md
```

## 8C.2-10 遗留限制

1. **真实转移默认关闭**；启用需运营确认（非 WAL 崩溃窗口、无在线验收）。
2. 影窃者身份隐藏为默认行为（成功时），尚无按玩家配置开关——后续阶段可选。
3. COIN 仍 BLOCKED；生物影窃（ENTITY）、职业预设、经验、能力树仍属后续阶段。
4. 暴露效果时长/半径与每日上限数值为常量/保守默认，正式平衡后续阶段。

## 8C.2.1-1 每日 ITEM 配额改为 eventId 幂等预留协议（8C.2.1 §2）

- `ShadowDailyLimitStore` 由「best-effort 事后计数」重构为**提交前预留协议**：
  - `tryReserve` —— 资产提交**前**调用；成功即占用配额（`RESERVED`）；
    `commitReservation`（SUCCESS）—— 转 `COMMITTED` 仍占用配额；
    `releaseReservation`（干净失败 / 回滚成功）—— 释放配额；
    `RECOVERY_REQUIRED` / 提交异常 —— **保留预留**（资产可能已移动，配额保守占用）。
  - 配额口径 = `RESERVED + COMMITTED`；eventId **幂等**：同 eventId 重试不双计，
    已 COMMITTED 的 eventId 重试返回 `COMMITTED_EXISTING`。
  - fail-closed：存储满 / 非法日期 / null 入参 / 异常 → `REJECTED` → 拒绝 ITEM 转移。
  - 新增 `MAX_RESERVATIONS=4096`，超出按插入序逐出最旧预留（确定性）。
  - UTC 日期字符串由协调器每次尝试捕获一次（`dailyDateSupplier` 注入，生产为
    `ShadowDailyLimitStore::today`）。
- 协调器接线：提交前 `tryReserve`（§2 时序）；`LIMIT_REACHED`/`REJECTED`/异常 →
  `TRANSFER_FAILED "daily_item_limit"`（失败关闭）；SUCCESS → `commitReservation`
  （best-effort，失败仅节流告警，`RESERVED` 继续保守占用配额）；
  `FAILED_CLEAN` 与两处 `ROLLED_BACK` → `releaseReservation`；
  两处 `RECOVERY_REQUIRED` → 保留。
- 测试：`ShadowDailyLimitStoreTest` 重构为 9 用例（生命周期 / eventId 幂等 /
  UTC 换日 / 往返持久化 / 受害者容量 / 预留容量逐出 / 非法输入 fail-closed /
  损坏 NBT / 未来版本）；`FakeDailyLimits` 同步实现预留协议；
  协调器 3 个既有上限用例适配后仍绿。

## 8C.2.1-2 审计读失败改为 fail-closed FALSE

- `ShadowFrameworkSettings.defaults()` 中 `auditEnabled` 的配置读取失败回退值
  由 `true` 改为 `false`（与其它开关一致的 fail-closed 方向）；配置项本身默认仍
  为 `true`（运营可在 toml 显式开启）。
- 测试：`defaultsAreSafe` 断言更新。

## 8C.2.1-3 反馈按 theftType 分支 + Locale.ROOT + DUPLICATE 静默（8C.2.1 §3-4）

- SUCCESS 反馈**严格按已抽取的 theftType 分支**（ITEM / HEALTH / HUNGER / EFFECT），
  不再凭 receipt 字段猜测类型（COIN 恒定不可达，走通用文案）；
- 数值格式化改用 `String.format(Locale.ROOT, "%.1f", ...)`，杜绝本地化小数点差异；
- `DUPLICATE`：仍取消原交互，但**静默**——不向任何玩家发送第二条提示；
- `exposeNearby` **排除受害者**（其已收到一次直接失败提示，不重复刷屏）。
- 测试：`duplicateIsCancelledAndSilent`（新增）；取消/反馈既有用例适配
  （SUCCESS 构造需合法 theftType）。

## 8C.2.1-4 命令树修正

- `/tcth shadow on|off|status` → 移入 `/tcth debug shadow on|off|status`
  （权限 ≥3，与 cooking/farming/gunner/brewing 调试子系统同构、同权限域）；
- `/tcth shadow audit recent|player` 路径不变（只读审计命令）；
- 测试：`TcthShadowDebugCommandTest` 切换命令路径；`TcthShadowAuditCommandTest` 全绿。

## 8C.2.1-5 测试与验证

- 全量 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- XML：**suites=140 tests=1278 failures=0 errors=0 skipped=0**（取代 8C.2-8 的 1274）；
- 全部既有测试（厨师/农夫/枪客/魔酿师）无回归；
- `git diff --check`（项目范围）通过；未部署、未启动、未烟雾、未 commit/push。

## 8C.2.1-6 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStore.java    （预留协议 + 容量/逐出 + 测试钩子）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitWriter.java   （tryReserve/commit/release + 结果枚举）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java（提交前预留 + 提交/回滚释放 + 构造参数）
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerInteractHandler.java   （theftType 分支 + Locale.ROOT + 静默 DUPLICATE）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowFrameworkSettings.java （审计读失败 fail-closed FALSE）
src/main/java/com/tanrunn/tcth/impl/command/TcthCommands.java           （命令树修正：debug shadow）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStoreTest.java（重构 9 用例）
src/test/java/com/tanrunn/tcth/impl/shadow/FakeDailyLimits.java          （预留协议重写）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinatorTest.java（11 构造点补 dailyDateSupplier）
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerInteractHandlerTest.java（+duplicateIsCancelledAndSilent + Result 6 参）
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutorTest.java（构造适配）
src/test/java/com/tanrunn/tcth/impl/shadow/NoopShadowTransferExecutorTest.java（构造适配）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowFrameworkSettingsTest.java（audit 默认断言更新）
src/test/java/com/tanrunn/tcth/impl/command/TcthShadowDebugCommandTest.java（命令路径）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.2.1 修订节）
```

## 8C.2.1-7 遗留限制

1. 预留协议随 SavedData 持久化，但 SavedData **不是 fsync WAL**：崩溃窗口内
   `RESERVED` 可能丢失或残留（残留方向保守——配额占用）；`RECOVERY_REQUIRED`
   仍保留预留，需运营介入。
2. 8C.2-10 的其余遗留限制不变（真实转移默认关闭、影窃者身份隐藏无按玩家开关、
   COIN/ENTITY/职业预设未做）。

## 8C.2.2-1 总闸组合为四开关 AND（8C.2.2 §1）

- 总闸 = `Config.ENABLED` && `shadowThiefIntegrationEnabled` &&
  `shadowPlayerTheftEnabled` && `shadowRealAssetTransfersEnabled`；任一开关
  关闭即 FRAMEWORK_DISABLED（playerTheft 从 INVALID_CONTEXT 升级为闸门拒绝）。
- `ShadowFrameworkSettings` 首位新增 `masterEnabled`（`Config.ENABLED` 的投影，
  `safeGet(..., false)` fail-closed）；其余三开关原有 fail-closed 不变——
  任一配置读取异常 ⇒ 组合结果 false，provider/随机/PENDING 审计/executor 全部
  零调用。
- 上下文校验移除 PLAYER 的 playerTheftEnabled 分支（已并入总闸）；ENTITY 开关
  校验保留。
- 测试：`configMasterDisabledYieldsFrameworkDisabled` /
  `playerTheftDisabledYieldsFrameworkDisabled`（原 INVALID_CONTEXT 用例改向）/
  `settingsSupplierExceptionFailsClosed`（已有）/ `defaultsAreSafe` 增加
  `masterEnabled()==false`（无 config 环境读取异常即 false）。

## 8C.2.2-2 dailyDateSupplier 后移至闸门之后（8C.2.2 §2）

- UTC 日期捕获从 attemptInternal 首行移到「audit gate + 存储解析」之后、幂等
  检查之前——任何功能闸门（总闸/audit）或上下文校验关闭时日期源调用次数为 0。
- 日期供应商抛异常 ⇒ `utcDay=null` ⇒ `dailyLimitAtOrOver` 保守返回 true ⇒
  仅从候选池剔除 ITEM；HEALTH/HUNGER/EFFECT 正常抽取与转移，ITEM 资产禁止移动。
- 测试：`gatesClosedNeverTouchTheDateSupplier`（总闸×2 + audit + FakePlayer 均
  0 次调用）/ `dateSupplierExceptionPrunesOnlyItemAndForbidsItemTransfer`（抽签
  只命中 HEALTH、nextLong 恰 1 次、日期源恰 1 次）。

## 8C.2.2-3 配额状态全部改为 attempt 局部（8C.2.2 §3）

- 删除 `currentDailyLimits` / `currentReservationEventId` 两个实例字段；
  `dailyLimits`、`utcDay`、`reservationEventId` 均为 attemptInternal 局部变量，
  释放/提交辅助方法显式接收 `(dailyLimits, reservationEventId, context)`——
  同一 coordinator 实例可安全连续处理多次尝试。
- 测试：`successfulItemThenCleanFailureKeepsTheFirstQuota`——同一 coordinator
  先 ITEM SUCCESS（额度 COMMITTED）再 HEALTH FAILED_CLEAN，断言第一次成功额度
  的 `itemLossCount`/`occupiedCount` 绝不被释放。

## 8C.2.2-4 预留容量语义修正（8C.2.2 §4）

- **禁止淘汰未结算条目**：索引满时仅可清理已最终结算的 COMMITTED 索引条目
  （`evictOneCommittedIndex`，按插入序选最旧），**绝不触碰 occupied 聚合**——
  任何受害者额度不会因索引淘汰重新开放；全 RESERVED 满 ⇒ `REJECTED`（fail-closed）。
- eventId 已存在但 victim/day 不一致 ⇒ `REJECTED`（劫持防护）。
- 加载时对 reservation 与 occupied 做保守交叉校验：按 (victim, day) 回补
  `occupied = max(聚合, 预留数)`，损坏 NBT 不得让占用数变小。
- 测试：`allReservedIndexFullRejectsFailClosed`（原逐出用例改向）/
  `committedIndexEvictionKeepsOccupiedCounts` / `indexEvictionNeverReopensAnyVictimQuota`
  （limit=10000，跨受害者聚合 4096×3 > 索引上限，任何受害者配额不重开）/
  `eventIdReuseWithDifferentVictimOrDayIsRejected` / `corruptedNbtNeverShrinksOccupiedCounts`。

## 8C.2.2-5 成功顺序重排：配额提交成为硬门槛（8C.2.2 §5）

- 新顺序：PENDING → reserve → asset commit → receipt validate →
  **commitReservation** → final SUCCESS audit → 结算。
- `commitReservation` 返回 false 或抛异常**不得继续 SUCCESS**：回滚恰好一次——
  回滚成功 ⇒ `release` + `ROLLED_BACK "daily_commit_failed"`；回滚失败 ⇒
  `RECOVERY_REQUIRED "rollback_failed; daily_commit_failed"`，额度尽可能保留。
- final audit 写入失败后的资产回滚成功 ⇒ **同样释放已提交额度**（原逻辑保留）。
- 三处重复 rollback 代码收敛为 `rollbackOnce`（异常隔离）。
- 测试：`quotaCommitFailureRollsBackOnceAndReleases` /
  `quotaCommitFailureRollbackFailureKeepsQuotaAndRecovery` /
  `quotaCommitExceptionNeverContinuesToSuccess` /
  `finalAuditFailureRollbackSuccessReleasesCommittedQuota`。

## 8C.2.2-6 过期文档修正（8C.2.2 §6）

- `defaults()` Javadoc 移除「no-op transfer executor」表述，改为真实引擎 +
  四开关组合闸门锁定（资产中立）。
- 类级 strict-order 注释重写为 24 步新编号；步骤 24 移除
  「best-effort 记数」旧注释（配额提交已前移至步骤 21 硬门槛）。

## 8C.2.2-7 测试与验证

- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- XML：**suites=140 tests=1290 failures=0 errors=0 skipped=0**（取代 8C.2.1-5 的 1278）；
- 反馈/DUPLICATE 静默/命令树修正原样保留，未改任何资产数值；
- `git diff --check`（项目范围）通过；未部署、未启动、未烟雾、未在线测试；
  未进入 8D、未 commit/push。

## 8C.2.2-8 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowFrameworkSettings.java（+masterEnabled 字段/读取）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java（四开关总闸/日期后移/局部状态/配额提交硬门槛/rollbackOnce/Javadoc）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStore.java（索引淘汰语义/eventId 一致性/加载回补）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinatorTest.java（+8 用例/总闸改向）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStoreTest.java（+5 用例/容量用例改向）
src/test/java/com/tanrunn/tcth/impl/shadow/FakeDailyLimits.java（eventId 一致性/commit 失败注入/occupiedCount 修正）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowFrameworkSettingsTest.java（+masterEnabled 断言/构造适配）
src/test/java/com/tanrunn/tcth/impl/shadow/NoopShadowTransferExecutorTest.java（构造适配）
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutorTest.java（构造适配）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.2.2 修订节）
CHANGELOG.md
```

## 8C.2.2-9 遗留限制

1. 索引淘汰仅删已结算 COMMITTED 索引条目（聚合不变），同 eventId 的幂等重试
   在索引淘汰后可能重新预留——聚合方向保守，重试路径由审计/幂等键拦截，几乎不可达。
2. 8C.2.1-7 的遗留限制不变（SavedData 非 WAL、真实转移默认关闭、身份隐藏无开关、
   COIN/ENTITY/职业预设未做）。

## 8C.2.3-1 SUCCESS 审计单次写入（8C.2.3 §1）

- PENDING → FINAL SUCCESS 仍只发生**一次** safeAppend（步骤 22）。
- SUCCESS 结算改走独立 `finishAfterFinalAudit`：只提交幂等键并发布事件，
  **不再调用 append**——杜绝第二次 FINAL 写入（此前 finishAuditedAttempt 会再写
  一次，靠 FINAL→byte-identical 或时间戳巧合才不产生脏记录）。
- 其他失败结果仍由 `finishAuditedAttempt` 完成一次 FINAL 写入（PENDING→FINAL
  转换）；审计写入失败只告警不降级，保持原行为。

## 8C.2.3-2 审计假与真实存储行为对齐（8C.2.3 §2）

- `InMemoryAudit` 重写为与 `ShadowAuditStore.append` 完全一致的状态机：
  新 eventId 允许插入（PENDING 或 FINAL）；PENDING→FINAL 仅当身份字段全部一致；
  FINAL→byte-identical 幂等；其余（含 PENDING→PENDING、FINAL→不同 FINAL）
  一律拒绝且保留原记录——**禁止无条件覆盖**。
- 新增协调器测试使用**真实 ShadowAuditStore**：
  - epoch supplier 每次返回新值（PENDING/FINAL 时间戳必然不同）；
  - ITEM SUCCESS；
  - 经 CountingAudit 断言 append 恰 2 次（PENDING + FINAL），无第三次写入；
  - 存储最终恰 1 条 FINAL SUCCESS，eventId/receipt(钻石×2)/theftType/双方
    UUID 全部正确；配额 COMMITTED（itemLossCount=1、occupied=1）。

## 8C.2.3-3 加载保守合并 + 持久化 failClosed（8C.2.3 §3）

- `ShadowDailyLimitStore.load` 重写为保守合并：
  - 同 UUID 重复 victim entry **合并**（不后写覆盖）；同一天重复 count 取 **max**；
  - 重复 eventId：身份/state 完全一致 → 幂等跳过；不一致 → 标记存储损坏，
    **绝不任选最后一条**；
  - reservation 推导 occupied 时不得突破 MAX_VICTIMS（超出 → failClosed）；
  - 任何「输入无法在容量内保守表达」的情形（重复 victims 数超 MAX_VICTIMS、
    days 超 MAX_DAYS_PER_VICTIM、reservations 超 MAX_RESERVATIONS、回补超限）
    → 设置**持久化 failClosed 标志**（DATA_VERSION 2→3，`failClosed` 键随
    save/load 重载）：`isAtItemLimit` 恒 true、`tryReserve` 恒 REJECTED、
    commit/release 恒 false——**不再静默丢弃后重新开放额度**；
  - future/非法版本同样 fail-closed（此前空载允许偷取，已修正）；
  - v1/v2 数据（无 failClosed 键）仍可正常迁移。

## 8C.2.3-4 损坏 NBT 回归（8C.2.3 §4）

- `duplicateVictimEntriesMergeNeverLastWriteWins`（10→1 仍为 10）、
  `duplicateDayCountsTakeTheMax`（同 day 10→1 仍为 10）、
  `conflictingDuplicateEventIdFailsClosedGlobally`、
  `reservationVictimsBeyondCapFailClosedBounded`（1024+ 受害者，内存有界且全局
  fail-closed）、`unknownFutureVersionFailsClosed`、`legacyV1AndV2DataStillLoadsAndWorks`、
  `failClosedFlagSurvivesSaveAndReload`。

## 8C.2.3-5 总闸定位说明与 8D 预告（文档，不实现 8D）

- 当前四开关总闸中 `playerTheftEnabled` 是**玩家影窃专用**门槛：8C.2.2-1 将其
  并入总闸后，PLAYER 目标在闸门处即拒绝；ENTITY 目标仍走上下文校验的
  `entityTheftEnabled` 分支。
- **8D 接入生物目标（ENTITY 影窃）时**，闸门需重构为**按 targetKind 分支**：
  总闸 = `Config.ENABLED && integrationEnabled && realAssetTransfersEnabled`
  为公共部分，再叠加 `targetKind==PLAYER ? playerTheftEnabled : entityTheftEnabled`
  的目标分支；候选池/executor/审计无需改动。**8D 暂不实现**，仅预告设计。

## 8C.2.3-6 测试与验证

- 新增 9 用例（协调器 2 + 存储 7），合计 1298；
- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- XML：**suites=140 tests=1298 failures=0 errors=0 skipped=0**（取代 8C.2.2-7 的 1290）；
- `git diff --check`（项目范围）通过；未部署、未启动、未烟雾、未在线测试；
  未进入 8D、未 commit/push。

## 8C.2.3-7 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java（+finishAfterFinalAudit，SUCCESS 单次写入）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStore.java（DATA_VERSION 3 + failClosed 持久化 + 加载保守合并）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinatorTest.java（+真实 store/CountingAudit 用例 + InMemoryAudit 状态机对齐 + 转换规则用例）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStoreTest.java（+7 回归用例/future 版本改向）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.2.3 修订节）
CHANGELOG.md
```

## 8C.2.3-8 遗留限制

1. failClosed 是保守的运营标志：一旦因损坏数据置位，需要运营手工修复/清空
   存储后才能恢复 ITEM 配额（无自动恢复）。
2. 8C.2.2-9 的遗留限制不变；8D 生物影窃未实现（见 8C.2.3-5 预告）。

## 8C.2.4-1 审计存储持久化健康状态（8C.2.4 §1）

- `ShadowAuditStore` 新增持久化 `failClosed` 标志（DATA_VERSION 1→2，`failClosed`
  键随 save/load 保留）；`isHealthy()` 即 `!failClosed`。
- 以下情形加载后进入 fail-closed，**禁止继续审计与真实资产转移**：
  future/负版本（此前空载允许偷取，已修正）、非法记录（坏 UUID/非法枚举/路径
  穿越 id/NaN/负标量/非法标量组合）、同 eventId 内容冲突（绝不任选最后一条）。
- v1 数据（无 failClosed 键）正常迁移，不误锁；健康存储 round-trip 不带标志。

## 8C.2.4-2 isHealthy() 前置闸门（8C.2.4 §2）

- `ShadowAuditWriter` 新增只读 `isHealthy()`；`ShadowAttemptCoordinator` 在
  audit gate 内（audit 解析与 null 检查之后、**候选池/日期源/随机/executor 之前**）
  检查：不健康 → `AUDIT_FAILED "audit_unhealthy"`，provider/prepare/commit/
  nextLong/nextDouble/日期源调用全部为 0，不写记录不发布事件。
- 测试 fake（InMemoryAudit×2、CountingAudit）默认 healthy，协调器测试用
  `InMemoryAudit.setUnhealthy()` 注入；CountingAudit 委托 isHealthy。

## 8C.2.4-3 审计容量语义（8C.2.4 §3）

- `MAX_RECORDS` 满时**只淘汰最旧的已结算普通 FINAL**；PENDING 预写与
  RECOVERY_REQUIRED 记录永不淘汰（append 与 load 同一规则）。
- 全部为需保留记录时新 PENDING append 返回 `false`——资产提交不得发生；
  容量拒绝不是损坏（store 保持 healthy）。
- 加载超限同样保留关键记录；全部关键而无法在上限内表达 → failClosed。

## 8C.2.4-4 每日上限存储严格加载（8C.2.4 §4）

- 非法 UUID、非法日期、负计数、非法 reservation state、缺失必要字段
  （如缺 days）→ **统一进入持久化 failClosed**，不再静默跳过；
- 字节完全一致的重复记录可接受（幂等）；
- v1/v2/v3 数据继续迁移；failClosed 时 isAtItemLimit 恒 true、
  tryReserve 恒 REJECTED、commit/release 恒 false（保守拒绝）。

## 8C.2.4-5 回归测试

- `unhealthyAuditStoreRefusesBeforeAnyWork`（fake 不健康：AUDIT_FAILED，全部
  资产调用 0）、`futureAuditVersionFailsClosedCoordinatorRefusesBeforeAnyWork`
  （真实 store 加载 future 版本 → 同样全 0）、
  `unknownFutureVersionFailsClosed` / `negativeVersionFailsClosed` /
  `invalidResourceLocationsFailClosed` / `unknownNonNullEnumsFailClosed` /
  `nonFiniteAndNegativeScalarsFailClosed` / `invalidRecordsFailClosedInsteadOfCountingTowardsTheCap`
  / `conflictingDuplicateEventIdFailsClosed` / `byteIdenticalDuplicateEventIdsAreAccepted`
  / `capacityNeverEvictsPendingOrRecoveryRequired` / `allCriticalRecordsRefuseAppend`
  / `loadOverflowKeepsCriticalRecordsElseFailsClosed` /
  `failClosedFlagSurvivesSaveAndReload`（audit）/ `legacyV1DataMigratesHealthy`
  / daily 侧 `corruptedNbtFailsClosedInsteadOfSkipping` /
  `invalidReservationStateFailsClosed` / `missingRequiredFieldsFailClosed` /
  `failClosedStoreKeepsEveryItemPathConservative`（另 8C.2.3 已有
  `failClosedFlagSurvivesSaveAndReload`、`legacyV1AndV2DataStillLoadsAndWorks`）。

## 8C.2.4-6 阶段声明

- **8C.2.3 的事务修正（SUCCESS 单次审计写入、配额提交硬门槛、PENDING→FINAL
  转换规则）确认有效**；
- **真实资产转移的启用门槛由本阶段（8C.2.4）收口**：audit 存储不健康
  （损坏/饱和/future 版本）时 AUDIT_FAILED 且零资产调用；daily 存储不健康时
  ITEM 全拒。结合 8C.2.2 的四开关总闸，生产启用前还需运营显式开启
  `Config.ENABLED` 等四个开关。

## 8C.2.4-7 测试与验证

- 新增 13 用例（audit store 8、daily store 3、协调器 2），合计 1311；
- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- XML：**suites=140 tests=1311 failures=0 errors=0 skipped=0**（取代 8C.2.3-6 的 1298）；
- `git diff --check`（项目范围）通过；未部署、未启动、未烟雾、未在线测试；
  未进入 8D、未 commit/push。

## 8C.2.4-8 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStore.java       （DATA_VERSION 2 + failClosed 持久化 + 容量保留关键记录 + 严格加载）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditWriter.java     （+isHealthy()）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java（audit 健康闸门）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStore.java （严格加载：非法输入 failClosed）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStoreTest.java        （非法→failClosed 反转 + 8 新用例）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStoreTest.java   （+3 严格加载用例）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinatorTest.java（+2 健康闸门用例 + fake isHealthy）
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerAssetTransferExecutorTest.java（fake 状态机对齐 + isHealthy）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.2.4 修订节）
CHANGELOG.md
```

## 8C.2.4-9 遗留限制

1. failClosed 是保守的运营标志：audit/daily 任一损坏置位后需人工修复存储才能
   恢复（无自动恢复）；8C.2.3-8 的其余限制不变。
2. 8D 生物影窃未实现（见 8C.2.3-5 预告）。

## 8C.2.5-1 审计存储根 schema 严格验证（8C.2.5 §1）

- `dataVersion` 必须存在且为 TAG_INT；缺失/错类型 → failClosed（不得经
  getInt/getList 默认值加载成健康空存储）。
- v1 必须有 `records` TAG_LIST 且元素必须为 TAG_COMPOUND（空列表允许）；
  元素类型错误 → failClosed。
- v2 还必须有 `failClosed` TAG_BYTE；缺失/错类型 → failClosed。

## 8C.2.5-2 recordFromTag 必填字段严格校验（8C.2.5 §2）

- 必填字段存在 + NBT 类型精确：eventId/thief/target（TAG_INT_ARRAY）、
  targetKind/auditState（TAG_STRING + 已知枚举）、itemCount/effectDurationTicks
  （TAG_INT）、numericAmount（TAG_DOUBLE）、timestampEpochMillis/serverTick
  （TAG_LONG）、dimension（TAG_STRING + 严格 id）。
- 可选字段按语义：缺失 → null；存在但类型错误 → 损坏（failClosed）；
  存在但非法 id/未知枚举同样损坏，绝不当作缺失。
- position 必须三项全无或 x/y/z 均为 TAG_INT；部分坐标 → 损坏。
- 任一错误使整个 store failClosed，而非用 0/空字符串替代。

## 8C.2.5-3 每日存储按版本严格验证（8C.2.5 §3）

- `dataVersion` 必须存在且为 TAG_INT。
- v1：victims 必需（TAG_LIST + compound 元素）；reservations/failClosed 可缺。
- v2：victims + reservations 必需。
- v3：victims + reservations + failClosed（TAG_BYTE）必需。
- entry 级：victim/eventId 必须 TAG_INT_ARRAY；day/state 必须 TAG_STRING；
  count 必须 TAG_INT —— **count 缺失不得当成 0**。
- 任一 schema 错误 → 持久化 failClosed。

## 8C.2.5-4 容量加载优化（8C.2.5 §4）

- 全关键记录（PENDING/RECOVERY_REQUIRED）已满时，后续输入只是普通 FINAL
  → 安全丢弃该 FINAL，store 保持健康，关键记录全部保留；
- 只有关键记录本身无法容纳时才 failClosed；PENDING/RECOVERY_REQUIRED
  永不牺牲。

## 8C.2.5-5 回归测试

- 两 store：缺 dataVersion、version 错类型、根列表缺失、根列表类型错误、
  列表元素非 compound、v2/v3 缺 failClosed、failClosed 错类型；
- audit：缺 timestamp/serverTick/各标量、标量错类型、部分 position、
  可选字段错类型（theftType/failureReason/targetType）、v1 最小 schema 健康；
- daily：缺 count、count 错类型、缺 reservation state、v1/v2/v3 合法最小
  schema 均可加载；
- 容量：全关键已满 + 普通 FINAL 加载 → 健康且关键全保留（普通丢弃）；
  全关键已满 + 关键 → failClosed；
- failClosed 保存重载继续拒绝（8C.2.4 用例复用）。

## 8C.2.5-6 阶段声明

- **8C.2–8C.2.5 静态安全收口完成**：SUCCESS 单次审计写入、配额事务硬门槛、
  四开关总闸、audit/daily 双存储持久化健康状态与严格 NBT schema 全部就绪；
- **仍不声称 PLAYER LIVE PASS**：未部署、未在线验收；真实资产转移仍需运营
  显式开启四开关并完成在线验收后启用。

## 8C.2.5-7 测试与验证

- 新增 23 用例（audit store 17、daily store 6），合计 1334；
- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- XML：**suites=140 tests=1334 failures=0 errors=0 skipped=0**（取代 8C.2.4-7 的 1311）；
- `git diff --check`（项目范围）通过；未部署、未启动、未烟雾、未在线测试；
  未进入 8D、未 commit/push。

## 8C.2.5-8 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStore.java        （根 schema/必填字段/容量优化）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStore.java  （按版本 schema/entry 严格校验）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStoreTest.java       （+17 schema/容量用例 + 既有适配）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStoreTest.java  （+6 schema 用例 + 既有适配）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.2.5 修订节）
CHANGELOG.md
```

## 8C.2.5-9 遗留限制

1. 静态收口不含在线验收；PLAYER LIVE PASS 不声称（见 8C.2.5-6）。
2. 8D 生物影窃未实现（见 8C.2.3-5 预告）；failClosed 恢复仍需运营人工处理。

## 8C.2.6-1 dataVersion 边界收口（8C.2.6 §1）

- 两存储（ShadowAuditStore / ShadowDailyLimitStore）只接受
  `1 <= dataVersion <= DATA_VERSION`；
- `dataVersion=0`、负数、未来版本全部持久 fail-closed（0 此前会被当作合法
  版本加载，8C.2.5 遗漏边界已补）——不得经 getInt 默认值加载成健康空存储。
- 回归：`zeroDataVersionFailsClosed`（两存储）、负版本（audit 既有 + daily
  新增）。

## 8C.2.6-2 position 坐标矩阵（8C.2.6 §2）

- 三个坐标键**完全不存在**：合法，position=null；
- **任一**坐标键存在 ⇒ 三键必须全部存在且均为 TAG_INT；
- 部分缺失、混合类型、三键全为错误类型 → 均 fail-closed（8C.2.5 的
  contains(TAG_INT) 判断把「存在但错类型」与「缺失」混为一谈，已用
  contains(key) 存在性检查 + contains(key, TAG_INT) 类型检查分离）；
- 回归：`positionAllPresentWrongTypesFailsClosed` /
  `positionMixedTypesFailsClosed` / `positionPartialFieldFailsClosed` /
  `positionAllThreeValidIntsLoads` / `positionFullyAbsentLoadsNull`。

## 8C.2.6-3 范围声明

- **不改事务、额度、成功率、资产数值、配置默认值与业务逻辑**——仅两处
  schema 边界判定修正。
- **8C.2.5 被 8C.2.6 补充修正**：8C.2.5 的 dataVersion 下界（0 被放行）与
  position「存在但错类型」两处边界由本阶段收口；8C.2–8C.2.6 静态安全收口
  完成，仍不声称 PLAYER LIVE PASS。

## 8C.2.6-4 测试与验证

- 新增 8 用例（audit 7、daily 1），合计 1342；
- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- XML：**suites=140 tests=1342 failures=0 errors=0 skipped=0**（取代 8C.2.5-7 的 1334）；
- `git diff --check -- src docs CHANGELOG.md` 通过；未部署、未启动、未烟雾、
  未在线测试、未进入 8C.3、未 commit/push。

## 8C.2.6-5 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStore.java       （dataVersion 下界 1 + position 存在/类型分离）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStore.java （dataVersion 下界 1）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowAuditStoreTest.java       （+7 边界用例）
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowDailyLimitStoreTest.java  （+1 边界用例）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.2.6 修订节）
CHANGELOG.md
```

## 8C.2.6-6 遗留限制

- 同 8C.2.5-9：静态收口不含在线验收，PLAYER LIVE PASS 不声称；8D 未实现；
  failClosed 恢复需运营人工处理。

## 8C.3.1-1 Mixin FATAL 修复与提交前收口（8C.3.1）

- **8C.2.6 JAR 首次部署发生 Sponge Mixin FATAL**：`tcth-0.2.7.jar`（8C.2.6
  构建，哈希 `bdbe2aa7…`）在服务器加载时崩溃——
  `ItemStackDurabilityMixin.shouldSkipDurability` 为 **non-private static
  helper**，Sponge Mixin 拒绝（该静态方法会被合并进目标类产生命名冲突）。
- **修复**：helper 保持/改为 `private static`（绝不因测试改回 public/
  package-private）；可测试的锄头/厨刀互斥路由逻辑抽到普通 Java 类
  `DurabilityAbilityRouter`（hoe 优先且立即结束 → 否则 knife → 双标签不
  叠加 → 每路线只抽一次）；Mixin 私有 helper 只委托该普通类。
- **测试边界**：`FarmerTillingRoutingTest` 改为测试普通路由类（不再调用
  Mixin 私有方法）；结构回归用反射断言 helper 为 private static、注入
  处理器仍经私有 helper 入口（单行契约检查，不做大段源码扫描）、私有
  helper 与 router 行为一致（四象限委托）；`FarmerTillingMixinContractTest`
  的静态契约断言同步更新（委托目标改为 router）。
- **8C.3 在线验收结论：仅 LOAD PASS，PLAYER LIVE DEFERRED**——用户主动
  跳过两名真实玩家的在线部分；服务器以修复版 JAR 加载至 Done，未进行任何
  资产转移在线验证，**不声称资产转移在线通过**。
- 详见 `docs/phase-8c.3-shadow-player-live-report.md`（JAR 三版状态：
  bdbe2aa7… 作废、2fa2143c… 当前部署版且唯一完成服务器 LOAD PASS、
  3149987a… clean-build 产物 BUILD PASS 未部署未验证；两次启动/停服证据、
  临时配置与最终恢复状态、备份与日志位置）。

## 8C.3.1-2 测试与验证

- 新增/重构用例：`mixinHelperIsPrivateStatic` / `privateHelperDelegatesToTheRouter` /
  `injectorHandlerExistsAndCallsThePrivateHelper`（路由行为三用例改测
  router），合计 1344；
- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- XML：**suites=140 tests=1344 failures=0 errors=0 skipped=0**（8C.2.6 为 1342，
  路由测试重构净 +2）；
- 本阶段未部署、未启动、未烟雾、未在线测试；Shadow 配置保持全部关闭；
  未进入 8D、未 commit/push。

## 8C.3.1-3 修改文件清单

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/powerup/DurabilityAbilityRouter.java（新增普通路由类）
src/main/java/com/tanrunn/tcth/mixin/ItemStackDurabilityMixin.java（helper 改 private static 并委托 router）
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/powerup/FarmerTillingRoutingTest.java（改测 router + 反射结构回归）
src/test/java/com/tanrunn/tcth/impl/detector/farming/FarmerTillingMixinContractTest.java（委托契约断言更新）
```

**文档**
```
docs/phase-8b-shadow-theft-framework-report.md（本 8C.3.1 修订节）
docs/phase-8c.3-shadow-player-live-report.md（新增）
CHANGELOG.md
```

## 8C.3.1-4 遗留限制

- 8C.3 在线验收仅 LOAD PASS；ITEM/HEALTH/HUNGER/EFFECT/FAILURE/PROTECTION/
  AUDIT/DAILY-LIMIT 各 PLAYER LIVE 分层均未验证（用户跳过），不得声称通过。
- 服务器部署版为 2fa2143c（当前部署版，唯一完成服务器 LOAD PASS 的版本）；
  Shadow 配置全部关闭；服务器保持停服。3149987a（8C.3.1 clean-build 产物）
  未部署、未做运行时验证，不声称 LOAD PASS。

—— 8C.3.1 报告完 ——
