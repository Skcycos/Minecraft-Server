# 8D.1 生物影窃状态、数据加载与事务框架

> 状态：**BUILD-only**。不生成正式 shadow_loot 奖励数据、不部署、不启动、
> 不烟雾、不在线测试、不做职业经验/能力树/COIN，不 commit/push。

## 1. 权威 attachment：`tcth:shadow_loot_state`

- 注册于 `TcthShadowAttachments`（`NeoForgeRegistries.ATTACHMENT_TYPES`
  DeferredRegister，随 mod 事件总线注册）。
- 状态机（`ShadowLootState`，record，compact 构造严格校验）：
  - `AVAILABLE`：默认态，不必实际写入（attachment 缺失 = AVAILABLE）；
  - `PENDING(eventId, thiefUuid, startedAt)`：事务在途，禁止再次影窃；
  - `LOOTED(eventId, itemId, count, completedAt)`：已搜刮一次，禁止再次；
  - `CORRUPT`：加载损坏，全拒。
- 自定义 `IAttachmentSerializer<CompoundTag, ShadowLootState>`：
  - 严格验证 dataVersion、NBT 类型（UUID=TAG_INT_ARRAY、itemId=TAG_STRING、
    count=TAG_INT、时间为 TAG_LONG）、ResourceLocation 严格解析（含路径穿越
    拒绝）、count 1..64、时间 ≥0；
  - **非法/未知/未来版本绝不抛出**（NeoForge 抛异常会跳过 attachment，
    与"缺失"无法区分）→ 一律返回 CORRUPT；
  - AVAILABLE 带 payload 亦为 CORRUPT。
- `blocksTheft()`：PENDING/LOOTED/CORRUPT 均 true。
- **不使用 copyOnDeath**：死亡后新实体不得继承标记；新 UUID 实体自然为
  新个体。
- marker 是"是否可再次搜刮"的唯一权威；SavedData 不参与判定。
- attachment 与审计通过 eventId 关联。

## 2. shadow_loot 数据加载器（`ShadowLootLoader`）

- 路径：`data/*/shadow_loot/<entity_namespace>/<entity_path>.json`。
- 严格 schema（`ShadowLootDefinition.parse`）：1..8 pools、每池 1..32 entries、
  weight 1..1,000,000（long 累加防溢出，约束内不可能溢出）、min/max_count
  1..4 且 min≤max；实体/物品 id 必须可解析；**任一字段非法 → 该实体定义
  整体拒绝**，不回退默认池/死亡掉落表。
- 注册为数据包 reload 监听（`TcthDataReloads`）：reload 原子替换整表、清
  stale、WARN 60 秒节流；文件级失败只影响该实体。
- **代码级硬排除**：`minecraft:wither` / `ender_dragon` / `elder_guardian` /
  `warden` 恒不可搜刮，即使数据包误配（加载器过滤 + 协调器双检查）。
- 物品 id 的注册表存在性在协调器使用点校验（未知物品 → 该实体 NO_CANDIDATE
  + 节流 WARN）。
- 未定义实体 → NO_CANDIDATE（零随机）。
- 三层随机各恰一次（`selectPool` / `selectEntry` / `rollCount`，均基于
  `RandomSource.nextInt`）。

## 3. PLAYER/ENTITY 分流（PlayerInteractHandler 重构）

- 公共入口检查（目标无关，顺序固定）：`LogicalSide.SERVER` → 未取消 →
  真实 ServerPlayer（非 FakePlayer）→ MAIN_HAND → 潜行 → 双手空 →
  ServerLevel → 目标存活/同维度 → 距离合法 → 视线。
- 分流：`target instanceof ServerPlayer` → 原 PLAYER 路径（行为不变）；
  其他 Entity → ENTITY 路径。
- 实体开关组合（在实体协调器内检查）：
  `enabled && shadowThiefIntegrationEnabled && shadowEntityTheftEnabled &&
  shadowRealAssetTransfersEnabled`——**不读取 shadowPlayerTheftEnabled**。
- 实体路径附加检查：OPAC 实体交互保护（复用 `ShadowCompositeProtectionService`，
  `onEntityInteraction`）、非硬排除、存在合法 shadow_loot 定义、attachment
  AVAILABLE。

## 4. 实体物品事务（`ShadowEntityAttemptCoordinator` + `EntityLootTransferPlan`）

- `EntityLootTransferPlan(itemId, count)`：独立于玩家 `ItemPlan`，不复用
  "从受害者背包扣物"逻辑；交付 = 整栈进影窃者背包。
- 固定顺序（10 步）：门/上下文/审计健康/保护/attachment 健康 → 加载定义 →
  pool/entry/count 各随机一次 → 成功率恰一次（失败触发失败反应，见 §5）→
  背包整栈容量预检（不足 → TRANSFER_FAILED "inventory_full"，无事务）→
  PENDING 审计 → attachment PENDING + 回读验证 → 交付 → attachment LOOTED
  + 回读验证 → 审计 PENDING→FINAL SUCCESS → 发布单一 ShadowTheftEvent。
- 失败规则：
  - 交付失败 → 回滚已交付物品 + 恢复 attachment；恢复成功 → FAILED_CLEAN；
  - marker 写入/回读失败 → 不交付（TRANSFER_FAILED "attachment_write_failed"）；
  - LOOTED 或 FINAL 写入失败 → 回滚恰一次（物品 + attachment）；成功 →
    FAILED_CLEAN，失败 → RECOVERY_REQUIRED（保留 PENDING/CORRUPT 阻止重试）；
  - PENDING **不自动恢复 AVAILABLE**：仅管理员审计后处理；
  - **不声称崩溃级 exactly-once**：实体 NBT、playerdata、SavedData 均为
    非原子保存窗口（已如实记录于报告 §6）；
  - 背包不足不部分交付、不向地面掉落。
- 重复交互（attachment 非 AVAILABLE）→ DUPLICATE，零第二次收益、零第二次
  事件、零新增审计。

## 5. 失败反应（8D.1 §6）

- 敌对 Mob 失败时 `mob.setTarget(thief)` + 回读 `getTarget()`；事件取消或
  回读不一致只表示反应未生效，**不影响资产事务结果**。
- 非 Mob 实体不调用。
- 动物逃跑保持 DEFERRED/BLOCKED（不添加临时 Goal、不强制导航、不击退）。
- Boss/高风险强化不实现。

## 6. 非原子保存窗口（如实记录）

| 窗口 | 说明 |
|---|---|
| 实体 NBT（attachment） | `Entity.saveWithoutId` 由区块保存调度，非同步写盘 |
| playerdata | 玩家数据按 tick 保存调度，非原子 |
| SavedData（审计） | `ShadowAuditStore` 非 fsync WAL（8C.2 已有记录） |

崩溃可能留下：PENDING 审计 + attachment PENDING（重试被 PENDING 阻止，
需管理员按审计 eventId 核对后人工处理）；或物品已交付但 attachment 未
LOOTED（下一交互视为 AVAILABLE，可能重复搜刮——由审计记录核对）。恢复
方向：管理员依据 `ShadowAuditStore` 的 PENDING/FINAL 记录人工判定。

## 7. 测试与验证

- 新增 3 测试类（34 用例）：`ShadowLootStateSerializerTest`（四状态
  round-trip、缺失=AVAILABLE、坏类型/版本/UUID/RL/count → CORRUPT、
  serializer 不抛出、blocksTheft）、`ShadowLootLoaderTest`（schema 全非法
  分支、硬排除、原子替换/清 stale、三层随机恰一次、count 区间）、
  `ShadowEntityAttemptCoordinatorTest`（entity 开关不依赖 playerTheft、
  FRAMEWORK_DISABLED/硬排除/无定义/保护/非 AVAILABLE 零随机、成功事务
  PENDING→FINAL eventId 一致、满背包零事务、marker 写失败不交付、LOOTED/
  FINAL 写失败回滚恰一次、无法恢复 → RECOVERY_REQUIRED、重复交互零第二次
  收益、setTarget 成功/取消/非 Mob、未知物品 fail-closed）；`PlayerInteractHandlerTest`
  +4 分流用例（ENTITY 路由、PLAYER 无回归、客户端忽略、未注册类型忽略）。
- 合计 **suites=143 tests=1378 failures=0 errors=0 skipped=0**（8C.3.1 为
  1344，净 +34）。
- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**。
- JAR 检查：无第三方 class、无嵌套 JAR；`src/main/resources` 无 shadow_loot
  正式数据（测试用内存 JSON）。
- `git diff --check -- src docs CHANGELOG.md` 通过；未部署、未启动、未烟雾、
  未在线测试（PLAYER LIVE NOT TESTED）、未进入正式掉落数据、未 commit/push。

## 8. 修改文件清单

**新增（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowLootState.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowLootStateSerializer.java
src/main/java/com/tanrunn/tcth/impl/shadow/TcthShadowAttachments.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowLootDefinition.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowLootLoader.java
src/main/java/com/tanrunn/tcth/impl/shadow/EntityLootTransferPlan.java
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowEntityAttemptCoordinator.java
```

**修改（main）**
```
src/main/java/com/tanrunn/tcth/impl/shadow/PlayerInteractHandler.java（PLAYER/ENTITY 分流）
src/main/java/com/tanrunn/tcth/impl/shadow/ShadowAttemptCoordinator.java（newPlayerProtectionTicks 包可见）
src/main/java/com/tanrunn/tcth/impl/brewing/TcthDataReloads.java（+shadow_loot reload）
src/main/java/com/tanrunn/tcth/TCTHIntegration.java（+attachment 注册）
src/main/java/com/tanrunn/tcth/api/shadow/ShadowTheftOutcome.java（+FAILED_CLEAN）
```

**新增（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowLootStateSerializerTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowLootLoaderTest.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowEntityAttemptCoordinatorTest.java
```

**修改（test）**
```
src/test/java/com/tanrunn/tcth/impl/shadow/PlayerInteractHandlerTest.java（+4 分流用例）
src/test/java/com/tanrunn/tcth/impl/stats/CookingStatsTrackerTest.java（reload 计数 2→3）
```

**文档**
```
docs/phase-8d.1-shadow-entity-framework-report.md（本报告）
docs/phase-8d.0-shadow-entity-authoritative-audit.md（8D.1 落地确认节）
CHANGELOG.md
```

## 9. 遗留限制

1. 事务非原子（§6 窗口如实记录）：PENDING 阻塞重试需管理员介入；崩溃后
   "物品已交付但 marker 未 LOOTED"由审计核对。
2. 动物逃跑 DEFERRED/BLOCKED；Boss/高风险强化未实现；硬排除四实体恒拒绝。
3. 不生成正式 shadow_loot 奖励数据；正式数据须经经济审计（8D.0.2 结论）。
4. 未进入在线验收；PLAYER LIVE NOT TESTED。


## 10. 8D.1.1 阻断修正（取代 8D.1 初版 BUILD PASS）

### 10.1 明确槽位物品交付（P1，`SlotItemTransaction`）

- **禁用** `Inventory.add(mutatingStack)` 与"扫描并 shrink 任意同类物品"方案。
- 单一主背包槽（0..35）：同组件栈且剩余 ≥ count，或空槽且 maxStackSize ≥ count；
- prepare 保存 slot index、beforeStack/afterStack 完整组件快照、deliveryStack 不可变快照；
- commit：槽须严格等于 beforeStack → `setItem(afterStack.copy)` → 重读严格等于 afterStack（no-op/错误写入绝不报告成功）；
- rollback：仅接受槽严格等于 afterStack → 恢复 beforeStack.copy → 重读验证；外部变化 → 拒绝且**不删除其它同类物品**；PRE 状态回滚直接成功且不写槽；
- **attachment 与物品两个恢复动作独立尝试**（不得 `&&` 短路）；
- PENDING marker 写失败后恢复失败 → **RECOVERY_REQUIRED**（非普通 TRANSFER_FAILED）；
- 不部分交付、不落地。测试真实验证背包最终内容（成功恰 +count、LOOTED/FINAL 失败后恢复、同组件槽精确恢复、外部改槽 rollback 拒绝、setItem no-op、不删他人物品）。

### 10.2 审计状态（8D.1.1 §2）

- `auditEnabled()` 在**所有随机/loader/marker/资产前**检查（关闭 → AUDIT_FAILED，零随机零 marker 零资产）；
- PENDING 后可干净恢复的失败 → 写 **FINAL FAILED_CLEAN**（不残留 PENDING）；
- 仅 FINAL 写入本身失败才允许保留 PENDING（管理员恢复）；
- LOOTED/FINAL 阶段 RECOVERY_REQUIRED 且交付数量已知 → Result **携带 receipt**；完全未交付才空 receipt；
- 每条失败路径断言 outcome、audit 状态与 append 次数、attachment 状态、背包状态四者一致。

### 10.3 数据包加载语义（P1）

- **最高优先级覆盖**：只采用 `listResourceStacks` 的**最后一个**资源（MC pack 列表低→高）；最高优先级 JSON 损坏/schema 非法 → 该实体无定义，**不回退低优先级**；
- reload 阶段获取 registry access，发布前验证：entity type 已注册（`containsKey`——`get()` 对未知 key 回退默认条目）、每个 entry 的 item 已注册、非 AIR、构造栈非空；
- 任一未知/非法条目 → 整个实体定义拒绝；coordinator 不再于随机抽中后才发现未知物品；
- 测试：双数据包优先级（高合法覆盖低 / 高坏 JSON 不回退 / 高坏 schema 不回退）、混合条目（一合法 + 一未知 → 整文件拒绝、随机调用 0）。

### 10.4 严格 attachment schema

- count 统一 1..4；AVAILABLE/CORRUPT 仅 version+state；PENDING 仅
  version/state/eventId/thief/startedAt；LOOTED 仅
  version/state/eventId/itemId/count/completedAt；
- 缺失/错类型/**未知字段**/其它状态字段/负或零时间/非法 RL/未来·零·负版本 → CORRUPT，read 不抛；
- write/read 对称：必填字段总是写出（startedAt/completedAt 强制 > 0），合法内存状态必能读回同一状态。

### 10.5 实体上下文与防刷

- 解析实际 target 后**重新取得其注册 entity type，严格等于 context.targetType**（伪造类型 → INVALID_CONTEXT）；
- 硬排除依据**实际 entity type**（即使 context 伪造 Wither 排除也无法绕过）；
- 实体路径接入全局冷却 + attempt 幂等（同 eventId、thief+target+serverTick；幂等检查在冷却前，与玩家路径一致）；
- 成功 → 全局冷却、失败 → 失败冷却、NO_CANDIDATE → 无候选冷却；failed roll 后连续右键不得立即重抽；
- marker LOOTED 仍为同一实体永久防重复权威；
- 测试：同 tick 重复、失败后冷却、下一合法窗口、伪造 targetType 绕过 Wither。

### 10.6 失败反应修正

- 仅对**明确敌对 Mob/Monster（Enemy）**执行 `setTarget(thief)`；普通动物继续
  DEFERRED 不设攻击目标；非 Mob 不调用；
- setTarget 后比较 `mob.getTarget() == thief`；取消/回读不一致只记录"反应未生效"，不影响资产 outcome；
- 测试：敌对成功、取消/回读不一致、动物、非 Mob 四分支。

### 10.7 随机顺序（固定）

门控/审计/保护/真实类型/attachment → 完整定义 → pool(1) → entry(1) → count(1，min==max 也调用) → **槽位事务可行性** → success roll(1) → PENDING audit → PENDING marker → commit → LOOTED marker → FINAL audit → 单事件。任何更早失败断言后续随机/审计/资产调用为 0。

### 10.8 验证

- 合计 **suites=143 tests=1389 failures=0 errors=0 skipped=0**（8D.1 为 1378，净 +11）；
- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- JAR：无第三方 class、无嵌套 JAR；主资源无正式 shadow_loot JSON；
- `git diff --check -- src docs CHANGELOG.md` 通过；未部署、未启动、未烟雾、
  PLAYER LIVE NOT TESTED、未进入正式掉落数据、未 commit/push。

**声明：8D.1 初版的 BUILD PASS 已被 8D.1.1 修订取代。**


## 11. 8D.1.2 提交前阻断修复（取代 8D.1.1）

### 11.1 SlotItemTransaction 真实状态分类（PRE / COMMITTED / FOREIGN）

- `commit` 即使 `getItem`/`setItem` 抛异常也强制回读槽位：等于 before →
  PRE（干净失败）；等于 after → COMMITTED；其它/不可读 → FOREIGN；
  异常不逃逸到协调器外层（不再出现 INVALID_CONTEXT "framework_exception"）；
- `rollback` 只从完整 COMMITTED 恢复（槽==afterStack → 恢复 beforeStack →
  回读验证）；**FOREIGN 绝不覆盖**（错写/外部修改后 rollback 拒绝且不删除
  其它同类物品）；
- PRE 回滚直接成功且不写槽；prepare 遍历槽位时 getItem 异常 → 无法预备。

### 11.2 AttachmentAccess 严格状态转换

- 读取使用 `getExistingDataOrNull`（缺失返回 null → AVAILABLE），**读取不再
  创建 AVAILABLE attachment**；
- 写 PENDING/LOOTED 后协调器回读验证；
- `restore(entity, expected)`：恢复前当前状态**必须等于本事务预期状态**
  （PENDING 分支/commit 失败/LOOTED 失败恢复预期 PENDING——最后一次成功写入；
  FINAL 失败恢复预期 LOOTED）；`removeData` 后回读必须为 null；
  no-op 移除、错状态、异常均不得报告恢复成功；
- 状态不明 → 保留阻断状态（RECOVERY_REQUIRED），不得重新开放实体。

### 11.3 事务异常路径

- 物品恢复与 marker 恢复始终独立尝试（无 `&&` 短路）；两者均经回读验证
  恢复 → FAILED_CLEAN（并写 FINAL FAILED_CLEAN）；
- **FINAL SUCCESS 审计失败且回滚成功 → ROLLED_BACK**；回滚失败 →
  RECOVERY_REQUIRED（带 receipt）；
- `finaliseClean` 返回 false → 不得声称审计正常收口 → RECOVERY_REQUIRED
  （审计残留 PENDING 需管理员）。

### 11.4 Loader

- registryAccess 为 null / 获取异常 → **本轮定义映射为空**（fail-closed，
  旧定义也被清除，不服务任何实体）；
- coordinator 用 `containsKey` 再 `get`（`Registry.get()` 对未知 id 回退
  默认条目，8D.1.2 防默认注册表回退）；
- weight/min_count/max_count 必须是**数学整数**（BigDecimal 校验：小数、
  NaN、指数溢出均拒绝）；
- 修正文档中资源栈顺序矛盾文字（最高优先级 = 列表**最后一个**，MC 顺序
  低→高）；
- 未知 entity/item、AIR、空 ItemStack 仍在 reload 随机前整文件拒绝。

### 11.5 Result.eventPosted

- SUCCESS 保留 `postEvent` 的真实 boolean；所有失败路径 false。

### 11.6 时间源

- `startedAt` 异常/≤0 在**写 PENDING audit 前**拒绝（TRANSFER_FAILED
  "clock_unavailable"），绝不先留 PENDING 再因状态构造异常退出。

### 11.7 测试与验证

- 新增行为测试：setItem 错写（FOREIGN 不覆盖）、写后抛异常（回读分类）、
  读回抛异常、removeData no-op、恢复错状态保留阻断、RegistryAccess null/
  抛异常映射空、小数 weight/count 拒绝、eventPosted true/false、
  FINAL 失败 ROLLED_BACK、时钟拒绝零 PENDING；每条失败路径断言背包、
  attachment、audit、receipt 四者一致；
- 合计 **suites=143 tests=1399 failures=0 errors=0 skipped=0**（8D.1.1 为
  1389，净 +10）；
- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- JAR：无第三方 class、无嵌套 JAR；主资源无正式 shadow_loot JSON；
- `git diff --check -- src docs CHANGELOG.md` 通过；未部署、未启动、未烟雾、
  PLAYER LIVE NOT TESTED、未进入正式掉落数据、未 commit/push。

**声明：8D.1.1 的 BUILD PASS 已被 8D.1.2 修订取代。**


## 12. 8D.1.3 事务最终阻断修正（取代 8D.1.2）

### 12.1 消除提交后的时钟漏洞（8D.1.3 §1）

- `buildAuditRecord` 不再内部隐式读取时间源——时间戳**显式传入**；
- attempt 在 PENDING audit 前取得并验证**一次稳定的正数时间快照**
  （`startedAt > 0`，否则 `clock_unavailable` 拒绝，零 PENDING）；此后
  PENDING/LOOTED/FINAL 全部复用该快照，**时间源绝不再读**；
- `transaction.commit` 成功后的 LOOTED/FINAL 构造与收尾整体包 try：
  任何构造/时钟异常 → 回滚物品与 attachment → **ROLLED_BACK**（恢复成功，
  审计 PENDING 保留）或 **RECOVERY_REQUIRED（携带准确 receipt）**；**绝不
  返回 INVALID_CONTEXT**；
- 测试：序列时间源（第 1 次正数、之后抛异常）→ SUCCESS 且时间源仅被调用
  1 次、物品与审计完整——不遗留"资产已移动但结果为 INVALID_CONTEXT"。

### 12.2 提交后收尾加固（8D.1.3 §2）

- 成功路径复用 attempt 初始读取的 `settings`（settleCooldown 接收显式
  settings，不再 `settingsSupplier.get()`）；
- cooldown / idempotency / event 等辅助收尾全部异常隔离（try/catch，
  best-effort，绝不覆盖 SUCCESS/ROLLED_BACK/RECOVERY_REQUIRED 及 receipt）；
- 测试：提交后 `markGlobalCooldown`/`markEventId` 抛异常 → 仍 SUCCESS、
  eventPosted 真实、物品保留、事件恰一次。

### 12.3 SlotItemTransaction rollback 回读分类（8D.1.3 §3）

- `rollback` 的 `setItem` 即使抛异常也强制回读：等于 before → PRE/恢复
  成功；等于 after → COMMITTED/恢复失败；其它或不可读 → FOREIGN；
- 测试：回写"写入成功但抛异常"→ 恢复干净（FAILED_CLEAN、物品归零、
  FINAL FAILED_CLEAN 审计）。

### 12.4 shadow_loot 路径确定性（8D.1.3 §4）

- 仅接受 `data/tcth/shadow_loot/<entity_namespace>/<entity_path>.json`
  （外层命名空间必须为 `tcth`）；非 tcth 外层**整体忽略**，与规范文件无
  遍历顺序竞争；
- 保留同一规范 ResourceLocation 内 LOW→HIGH、最高优先级坏文件不回退语义；
- 测试：非 tcth 外层映射为空；跨命名空间冲突时最高优先级 tcth 规范文件
  确定性获胜。

### 12.5 实体反馈矩阵（8D.1.3 §5）

- `consumeEntity` 补全 COOLDOWN（现有 cooldown 翻译）与 FAILED_CLEAN
  （明确技术失败反馈 technical_error）；
- 全 outcome 矩阵测试：FRAMEWORK_DISABLED/INVALID_CONTEXT 不取消零消息；
  DUPLICATE 取消但静默；其余 10 个进入尝试的 outcome 取消且**恰一条
  反馈**（verify sendSystemMessage 次数）。

### 12.6 测试与验证

- 新增 7 用例（协调器 3、loader 2、handler 1、加既有适配），合计
  **suites=143 tests=1405 failures=0 errors=0 skipped=0**（8D.1.2 为 1399，
  净 +6）；
- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- JAR：无第三方 class、无嵌套 JAR；主资源无正式 shadow_loot JSON；
- `git diff --check -- src docs CHANGELOG.md` 通过；未部署、未启动、未烟雾、
  PLAYER LIVE NOT TESTED、未进入 8D.2/职业经验/能力树/COIN、未 commit/push。

**声明：8D.1.2 的 BUILD PASS 已被 8D.1.3 修订取代。仍为 BUILD-only，
PLAYER LIVE NOT TESTED。**


## 13. 8D.2.1 / 8D.2.2 掉落经济审计与首批数据（当前结论）

- 权威审计（原料单价表 / Bountiful 悬赏池 / Lightman's Currency / 服务器
  实际注册表）→ 审计表 `docs/影窃者生物掉落经济审计表.csv`；价格证据严格
  分级（已定义 eggs=5、raw_pork=15；**猜想 raw_rabbit=12、leather=8**；
  未定价 white_wool 等）；
- **APPROVED（3）**：chicken→egg、pig→porkchop、rabbit→rabbit（L1/L2、
  可再生；pig/rabbit/egg 可经烹饪间接进入 Bountiful 需求链，间接闭环风险
  低；**兔肉保留属运营接受的低风险试行决定，rabbit=12 仍是猜想价，不冒充
  权威定价**）；
- REJECTED（7）：cow（leather 猜想价证据不足）、sheep（white_wool 未定价
  + schema 不分羊毛颜色）、wolf/cat（无候选）、iron_golem/blaze/ghast
  （核心/稀有材料）；L3、Warden、远古守卫者、Wither、末影龙恒禁；
- 首批数据 `docs/presets/tcth-shadow-entity-loot/`（3 实体 JSON +
  pack.mcmeta **pack_format 48**，不进主 JAR）；count=1、无货币/容器/装备/
  附魔/动态组件/稀有材料；
- 生成器 `ShadowLootPresetGenerator`：**decision 严格枚举（APPROVED/
  REJECTED，不 trim）**、**全表 entityId 唯一（REJECTED+APPROVED 冲突也
  失败）**、**ResourceLocation.tryParse 权威校验**（显式 namespace、严格
  小写、拒绝 `..`/绝对路径、支持合法嵌套 path）、**标准 CSV `""` 引号
  转义**（未闭合引号/非法位置/列数≠10 fail-fast）、清理边界仅 shadow_loot、
  pack_format 48；
- 测试（`ShadowLootPresetTest`，18 用例）：确定性 SHA、stale 边界、非管理
  文件保留、磁盘==生成器、生产 schema、vanilla bootstrap 注册表存在性、
  硬排除/L3=0、count=1、pack_format=48、invalid decision、REJECTED+
  APPROVED 重复、非法 RL、未闭合引号、主资源无 JSON；
- 验证：suites=144 tests=1423 failures=0 errors=0 skipped=0；仅一次 clean
  build BUILD SUCCESSFUL；JAR 纯净、主资源无 shadow_loot JSON；两次生成
  SHA 一致；git diff --check 通过；未部署/未启动/未烟雾、PLAYER LIVE
  NOT TESTED、未进入 8E、未 commit/push。详见
  `docs/phase-8d.2-shadow-loot-economy-report.md`。

> **历史废止（HISTORY，8D.2 初版结论）**：8D.2 曾 APPROVED 5 项（cow→
> leather、sheep→white_wool、chicken→egg、pig→porkchop、rabbit→rabbit）
> 并称"生肉不会造成悬赏刷取闭环"、狼"死亡掉骨头"、恶魂之泪"不可再生"、
> leather 单价为已定义——上述结论与表述**已被 8D.2.1 全部修正**：价格分级、
> 烹饪间接闭环、狼不掉落物品、ghast_tear 可再生但低效；leather 与
> white_wool 降为 REJECTED。旧 5 项决定不再生效。


## 14. 8D.3.1 首次加载与日志节流修复（8D.3.1）

### 14.1 首次 reload 注册表来源

- 根因：8D.3 在线验收发现首次启动后 `shadow_loot` 定义为空（"无物可窃"），
  手动 `/reload` 后正常——`ShadowLootLoader.apply()` 依赖
  `ServerLifecycleHooks.getCurrentServer()` 获取注册表，初始资源 reload 时
  current server 可能为 null → 发布空 definitions。
- 修复：**删除生产代码对 lifecycle current server 的依赖**；`TcthDataReloads`
  注册时显式读取 `AddReloadListenerEvent.getRegistryAccess()`（本次 reload
  已冻结的 RegistryAccess）并构造**每次 reload 独立的 bound listener**
  （`ShadowLootReloadListener`）；`ShadowLootLoader.instance()` 保留为
  definitions 仓库；初始启动与 `/reload` 走完全相同的代码路径；null/异常
  仍 fail-closed 清空；最高优先级坏文件不回退、registry containsKey、硬
  排除与原子替换语义全部保留。
- 低频 INFO：`[TCTH] Shadow loot definitions loaded: N entities`（每次
  reload 最多一条）。

### 14.2 ShadowLogThrottle 修复

- 根因：全局单时间戳 `lastWarnMillis=Long.MIN_VALUE` 后 `now - lastWarnMillis`
  溢出 → 首条 WARN 被永久抑制（8D.3 的"0 TCTH WARN"证据因此无效）。
- 修复：按消息模板分桶（不同模板互不抑制）；首条立即输出；60 秒窗口内
  同模板最多一次；时钟回拨（`now < last`）与 long 边界（负值视为未见、
  差值计算仅限非负）不溢出；缓存仅接受代码内固定模板（天然有界）；
  `resetForTesting` 完整清理。
- 测试：首条输出 / 59,999ms 抑制 / 60,000ms 放行 / 两模板独立 / 回拨 /
  Long.MIN/MAX / reset 清理（7 用例）。

### 14.3 加载行为测试（真实行为，非字符串扫描）

- 初始 reload 在 lifecycle current server 不可用场景用 event RegistryAccess
  加载 3 项；两次 reload 绑定不同 RegistryAccess 不串用；null → 清空；
  listener 使用当前 ResourceManager（/reload 与初始同路径）；
  `TcthDataReloads` 确实读取 event.getRegistryAccess() 并注册 bound
  listener（verify）。


### 14.4 8D.3.2 提交前收口

- Throttle 改为原子节流（compute 内判定 + 判定后 warn、并发恰一次、
  同值负时间节流、subtractExact 防溢出、回拨放行）；
- listener.prepare 委托 loader.prepare（单实现）；
- 加固测试 4 个（经真实 listener：优先级/坏文件/双注册表/null）；
- 版本保持 0.2.8；PLAYER LIVE 结论不变；新构建仅 BUILD PASS。

—— 8D.1 / 8D.1.1 / 8D.1.2 / 8D.1.3 / 8D.2 / 8D.2.1 / 8D.2.2 / 8D.3.1 / 8D.3.2 报告完 ——
