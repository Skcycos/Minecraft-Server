# 阶段 7B：统一饮品完成事件 + Brewin' And Chewin Keg 检测

日期：2026-08-09

## 结论分层

| 层级 | 状态 |
|---|---|
| **DATA PASS** | 64 个 COMMON+T2 正式标签与 tier 映射生成（确定性、清 stale、与 CSV 一致；T3 不入） |
| **BUILD PASS** | 91 suites / 732 tests / 0 failures（`248953a3`） |
| **MIXIN LOAD PASS** | `KegPouringMixin` 应用成功，零注入错误（`248953a3` 唯一一次无玩家烟雾） |
| **PLAYER LIVE** | **NOT TESTED**（不做在线验收） |
| commit/push | **未做** |

---

## 一、公共 API（`com.tanrunn.tcth.api.brewing`）

| 类型 | 内容 |
|---|---|
| `BeveragePreparedEvent` | eventId(UUID)/player/recipeId/result(防御复制)/device/tier/automated/level/position；**无奖励/settled 字段**；`getResult()` 每次 copy |
| `BeverageDevice` | KEG / SHAKER / BARREL / BLENDER / OTHER |
| `BeverageTier` | **UNKNOWN / COMMON / T2 / T3**（无 T3_CANDIDATE/INGREDIENT，7A.1） |

公共 API 零第三方模组引用（`BrewerDataConsistencyTest.publicApiHasNoThirdPartyTypes` 守护）。

## 二、集中 Dispatcher（`BrewerIntegrationDispatcher`）

- **总开关**：`Config.ENABLED` + `Config.BREWER_INTEGRATION_ENABLED`（默认 false）双开，任一 false → `DISABLED`（fail-closed）
- **服务端**：`level == null || isClientSide()` → `INVALID_CONTEXT`
- **eventId**：每次 `UUID.randomUUID()` 唯一
- **FakePlayer**：`instanceof FakePlayer` → `automated=true`，player 置 null
- 空 result → `NOT_A_BEVERAGE`；配置异常 → fail-closed `DISABLED`
- **无经验/金币/统计/能力树**（事件不含奖励状态）

## 三、正式数据（`scripts/generate_brewer_data.py`）

- `data/tcth/tags/item/brewer_drinks.json`：**64 项**（COMMON 18 + T2 46），运行时标签
- `data/tcth/brewer/tiers.json`：运行时视图（`{tiers: {UNKNOWN, COMMON, T2}}`），逐物品 tier 映射
- **T3 候选 / 原料 / 容器 / 排除不入运行时**（留在 7A CSV 审计）
- 生成器确定性（两次字节一致）、每次重写（清 stale）、校验与 CSV 一致
- Java 侧 `BeverageTierManager`（`SimpleJsonResourceReloadListener`）从 `data/tcth/brewer/tiers.json` 加载，**不直接读审计草案**

## 四、BAC Keg（javap 权威，BrewinAndChewin 4.5.0）

### 真实交付分支

```text
useItemOn:
  空手 → List.of() → openKegMenu            （非取餐）
  手持 → extractInWorld(stack,1,instabuild)  → List<ItemStack>
        List 非空 → forEach(lambda$useItemOn$0)  真实交付
            lambda: sameItem→noop / isEmpty→setItemInHand(替换) /
                    Inventory.add→背包 / add=false→Player.drop(掉地)
        List 空   → updateTemperature + openKegMenu  （0 事件）
```

### Mixin（`KegPouringMixin`）

- 注入点：`KegBlock.useItemOn` 中 **`List.isEmpty()` INVOKE AFTER**（List 已入 local 10；首个尝试的 `extractInWorld` AFTER 因 LVT 不兼容已修正）
- `@Local` 捕获 `List<ItemStack>`：非空 → 取**真实首元素交付栈** → `KegPouringAdapter`
- **recipeId 固定 null**（7A.1：`KegPouringRecipe` 无 id；不用 lastRecipeID，不伪造 RecipeHolder）
- **只签名/发布真实交付栈**，不构造假 ItemStack
- 非正式饮品（tier=UNKNOWN）、空结果、失败 → 0 事件
- 每次实际交付恰 1 事件（`List.isEmpty` AFTER 每调用一次）
- `requiredMods=["brewinandchewin"]` 条件加载；第三方引用限 BAC compat 包
- **异常限制**：Mixin 运行时无 THROW 注入点，`extractInWorld` 抛异常则不发布（stateless，无泄漏）——如实记录

### 依赖

- `dev-mods/brewinandchewin-4.5.0.jar`（SHA `9f658182…`）+ `compileOnly blank:brewinandchewin:4.5.0` + `testImplementation`

## 五、调试命令

`/tcth debug brewing on|off|status`（默认关，只观察已确认事件；`BrewingDebug` 纯内存开关，不写配置）。

## 六、测试

| 测试 | 用例 | 覆盖 |
|---|---|---|
| `BrewerIntegrationTest` | 12 | API 非空/防御复制/唯一 eventId；dispatcher 开关/服务端/FakePlayer 结构守卫/配置异常 fail-closed；Keg adapter T2 发布/UNKNOWN 拒绝/空栈拒绝；枚举 |
| `BrewerDataConsistencyTest` | 5 | tag 64/排除非运行时/tiers 运行时视图/BAC requiredMods 门控/公共 API 零第三方 |

原有测试全部保留（`CookingStatsTrackerTest` 更新为 2 个 reload listener 断言）。

**全量：91 suites / 732 tests / 0 failures / 0 errors / 0 skipped**

## 七、最终证据

| 项 | 值 |
|---|---|
| JAR SHA-256 | `248953a3610e4f13fc6d5d20a02504bda24d41db07f4a48a4daf98a4e8b80a34` |
| JAR 大小 | 329,003 B |
| 部署=构建 | 一致 |
| 烟雾 | Done=1、KegPouringMixin 应用、无 InvalidInjection/InjectionError/MixinApplyError、TCTH ERROR/WARN=0、FG 122/190/24、正常停服 |

## 八、边界遵守

- 未部署 tcth-brewer 职业预设（tiers=0 是预期）
- 未启用经验、未改 chef/farmer/gunner/UNITE/playerdata
- 未 commit/push

## 九、建议暂存清单（不得自行 commit）

- `src/main/java/com/tanrunn/tcth/api/brewing/**`（3 新）
- `src/main/java/com/tanrunn/tcth/impl/brewing/BeverageTierManager.java`（新）
- `src/main/java/com/tanrunn/tcth/impl/event/BrewerIntegrationDispatcher.java`（新）
- `src/main/java/com/tanrunn/tcth/impl/debug/BrewingDebug.java`（新）
- `src/main/java/com/tanrunn/tcth/mixin/brewinandchewin/KegPouringMixin.java`（新）
- `src/main/java/com/tanrunn/tcth/impl/compat/brewinandchewin/KegPouringAdapter.java`（新）
- `src/main/resources/brewinandchewin_compat.mixins.json` + `neoforge.mods.toml`（+BAC mixin）
- `Config.java`（+brewerIntegrationEnabled）、`TcthCommands.java`、`TCTHIntegration.java`、`CookingStatsTracker.java`
- `scripts/generate_brewer_data.py`、`validate_brewer_7a1.py`
- `docs/presets/tcth-brewer/data/tcth/**`（brewer_drinks/tiers 运行时视图）
- `dev-mods/brewinandchewin-4.5.0.jar`、`build.gradle`（+BAC 依赖）
- 测试 `BrewerIntegrationTest`/`BrewerDataConsistencyTest`/`CookingStatsTrackerTest`
- 本报告 `docs/phase-7b-mystic-brewer-integration.md`

**7B 完成。PLAYER LIVE NOT TESTED。等待复审。不 commit/push。**

---

# 阶段 7B.1：阻断修正

日期：2026-08-09（追加）

> **撤回 7B 初版结论**：7B 初版在 `List.isEmpty` AFTER（forEach 前）即声称"交付完成"并发布——**不成立**。7B.1 改为仅在**实际交付完成点**（替换/背包成功/掉落）之后发布。7B 初版 MIXIN LOAD 结论以 `248953a3` 为限作废，以本报告 7B.1 为准。

## 结论分层（7B.1 更新）

| 层级 | 状态 |
|---|---|
| **DATA PASS** | 64 标签打入主资源 + 64 逐物品 tier（`beverage_tiers/items/<ns>/<path>.json`），生成器同步/清 stale/拒穿越/确定性 |
| **BUILD PASS** | 92 suites / 741 tests / 0 failures |
| **MIXIN LOAD PASS** | `KegPouringMixin`（static handler 三分支）应用成功，零注入错误（`215b4971` 唯一一次最终烟雾） |
| **PLAYER LIVE** | **NOT TESTED** |
| commit/push | **未做** |

## 一、Keg 发布时机修正（核心阻断）

**7B 初版缺陷**：注入 `List.isEmpty()` AFTER（forEach 前）发布——交付可能随后失败（lambda 三分支中的异常），且非交付路径（同物 noop）也触发。**撤回该结论。**

**7B.1 正确方案**（javap `lambda$useItemOn$0` 为权威，static target）：

```text
lambda$useItemOn$0(result, player, hand, held):
  0: isSameItemSameComponents(result, held) → return      // 同物：不交付
  8: result.isEmpty → setItemInHand(hand, result) → ret   // 替换手中
 24: Inventory.add(result) → true → return                 // 背包成功
 35: Player.drop(result, false) → return                   // 背包满掉落
```

| 注入 | 类型 | 发布时机 |
|---|---|---|
| `setItemInHand` AFTER | @Inject | 替换完成后恰 1 次 |
| `Inventory.add` | @Redirect | 返回 true（背包成功）后恰 1 次（player 从 `inventory.player` 取） |
| `Player.drop` AFTER | @Inject | 掉落后恰 1 次 |

- 同物 noop、原容器/空瓶、失败 → 0 事件
- 背包满掉落**也发布**
- **static handler**（lambda 是 static 方法；实例 handler 触发 `InvalidInjectionException`，烟雾中已修复）
- `@Redirect` 不携带 lambda 额外参数（改从 inventory.player 取玩家）

## 二、主资源数据（DATA PASS）

- `src/main/resources/data/tcth/tags/item/brewer_drinks.json`：**64 条正式饮品标签**（COMMON 18 + T2 46）
- `src/main/resources/data/tcth/beverage_tiers/items/<ns>/<path>.json`：**64 个逐物品 tier**（每文件仅 `{"tier":"COMMON"}` 或 `{"tier":"T2"}`）
- `BeverageTierManager` 原子加载（`SimpleJsonResourceReloadListener`，`Map.copyOf`）；**删除运行时对单体 `brewer/tiers.json` 的读取**（该文件仅 docs/presets 审计草案）
- 生成器 `generate_brewer_data.py`：同步主资源、清 stale（整个 folder 重写）、拒路径穿越（`_ID_RE` + `..` 检查）、确定性（两次字节一致）

## 三、Reload 解耦

- 新增 `TcthDataReloads`（独立注册类，`register` 幂等 synchronized）：注册 DishTierManager + BeverageTierManager
- `CookingStatsTracker` 移除 reload 注册（解耦；料理统计关闭时 brewer tiers 仍加载）
- `TCTHIntegration` 构造注册 `TcthDataReloads.register(NeoForge.EVENT_BUS)`

## 四、FakePlayer 谓词化

`BrewerIntegrationDispatcher.fakePlayerPredicate`（可注入 `Predicate<ServerPlayer>`）：
- 生产默认 `p -> p instanceof FakePlayer`
- 测试 `setFakePlayerPredicateForTesting` 证明：真人=false、自动 actor=true、null player=automated
- **不再用源码字符串搜索冒充行为测试**

## 五、测试（全量 92/741/0）

| 测试 | 覆盖 |
|---|---|
| `KegPouringMixinDeliveryTest`（新 5） | 三分支 AFTER 发布、无 List.isEmpty 前发布、三分支齐全 |
| `BrewerDataConsistencyTest`（更新） | 主资源 64 标签/64 逐物品/每文件 COMMON/T2/审计草案保留/BAC requiredMods/公共 API 零第三方 |
| `BrewerIntegrationTest`（更新） | 谓词化 FakePlayer 真/假路径、dispatcher 开关/服务端/异常 fail-closed |
| `CookingStatsTrackerTest`（更新） | reload 解耦（TcthDataReloads 注册） |

## 六、最终证据

| 项 | 值 |
|---|---|
| JAR SHA-256 | `215b49717ac73281ec07324b65809b533ffe9c1c531bcf6326a6317b65849e02` |
| JAR 大小 | 346,768 B |
| 部署=构建 | 一致 |
| 烟雾 | Done=1、KegPouringMixin 应用、无 InvalidInjection/InjectionError/MixinApplyError、TCTH ERROR/WARN=0、FG 122/190/24、正常停服 |

## 七、边界遵守

- 未部署 tcth-brewer 职业预设、未启用经验、未改 chef/farmer/gunner/UNITE/playerdata
- 未 commit/push

**7B.1 完成。7B 初版"真实交付完成"结论已撤回。PLAYER LIVE NOT TESTED。等待复审。不 commit/push。**

---

# 阶段 7B.1.1：单点修正（BUILD ONLY）

日期：2026-08-09（追加）

> **历史版本说明**：旧 `215b4971` 被记录为**存在背包分支缺陷**的版本（`Inventory.add` 后发布可能为空的 result；position 用玩家坐标冒充）。本修正不改变注入目标与签名，BUILD ONLY，不重复烟雾、不部署、不在线测试。

## 一、Inventory.add 发布修正

原缺陷：`tcth$onInventoryAdd` 在 `inventory.add(result)` 之后用 `result` 发布——add 会把原栈 shrink 到 0，发布空栈（缺陷）。

修正（javap 语义 + 真实变异测试）：

```java
int beforeCount = result.getCount();       // ① 快照前记原 count
ItemStack snapshot = result.copy();        // ② add 前复制快照
boolean added = inventory.add(result);     // ③ add（可能 shrink 原栈）
if (added) {
    int moved = beforeCount - result.getCount();   // ④ 实际加入量
    if (moved > 0) {
        publishAfterDelivery(player, snapshot.copyWithCount(moved));  // ⑤ 快照副本+实际量
    }
}
```

- **不得用 add 后可能已为空的 result 发布**（用 `snapshot.copyWithCount(moved)`）
- `moved > 0` 才发布；add 失败/未移动 → 0 事件（drop 分支负责）

## 二、Keg 坐标修正

`publishAfterDelivery` 的 position 改为 **`null`**（static lambda 无法可靠取得设备坐标）；**禁止用玩家坐标冒充设备坐标**（移除 `BlockPos.containing(player...)`）。

## 三、变异行为测试（`KegPouringMutationTest`，3 用例）

| 用例 | 断言 |
|---|---|
| `addSuccessShrinksToZeroStillPublishesOneEventWithCorrectCount` | add 成功把原栈 shrink 到 0 → **恰 1 事件**、result=交付饮品、count=1、position=null |
| `addFailsWithoutMovingPublishesNothing` | add 失败未移动 → **0 事件**（drop 分支负责） |
| `unknownTierAddStillPublishesNothing` | UNKNOWN tier 成功 add 也不发布 |

真实变异模拟（非源码字符串扫描）：快照→shrink→moved 计算→发布。

## 四、验证（BUILD ONLY）

- 定向测试：`KegPouringMutationTest` + `KegPouringMixinDeliveryTest` + `BrewerIntegrationTest` 全绿
- **clean build：93 suites / 744 tests / 0 failures**，XML 全绿干净
- 新 JAR SHA：**`f35a06a2fe9aa40f9b07c718dc5b84545dfbfc65cd43d1a4bc8516350dc8b8a7`**（346,824 B）——**仅记录未部署**
- 服务器保持旧部署 `215b4971`（历史缺陷版）
- 不重复烟雾、不部署、不在线测试、不 commit/push

**7B.1.1 完成。停止等待复审。**
