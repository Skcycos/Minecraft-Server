# 阶段 6B / 6B.1：新增食物模组正式厨师合并 + DD/FD 设备适配

日期：2026-08-08

## 结论分层（禁止混淆）

| 层级 | 状态 |
|---|---|
| 数据合并（权威表 / dish_tiers / tags / FG） | **完成**（6B；本阶段未重做） |
| Shift-click recipeId 捕获修正 | **完成（6B.1）** |
| `./gradlew clean build --rerun-tasks` | **BUILD PASS**（源码；见测试 XML） |
| **部署=构建证据（6B.1.1）** | **完成**（`bfee193f…`） |
| 服务器无玩家烟雾加载 | **DATA PASS / MIXIN LOAD PASS**（仅对 `bfee193f` 有效） |
| DD/FD 玩家取餐实测 | **PLAYER LIVE NOT TESTED** |
| Bakeries 设备 Mixin | **DEFERRED**（无可靠统一 ResultSlot） |
| 整盘 / 饮品 / 营火 / 悬赏经济 | **未进入** |
| commit/push | **未做** |

---

## A. 167 道料理正式合并结果

### 输入

- `配方与经济管理/统一配方表/新增食物模组厨师合并预览.csv`（NEW=167, COMMON=38, T2=129）
- 6A.3 §10 / 6B.0 预览报告

### 权威表

- 文件：`配方与经济管理/统一配方表/食物三档分类表.csv`
- 原 428 行 **未改档次**；追加 167 行
- **unique ID = 595**
- 等级码：1=355 / 2=195 / 3=24 / 空=21
- COMMON 写等级 1，T2 写等级 2，**无等级 3**

### dish_tiers（`generate_dish_tiers.py`）

| 指标 | 数量 |
|---|---|
| item tier JSON | **572** |
| COMMON / T2 / T3 | **353 / 195 / 24** |
| recipe tier | **1**（`farmersdelight:cooking/cooked_rice` 未删） |
| raw_dough | **不生成** |
| 连续两次生成 items 树 SHA | **一致** |

### 标签与 Field Guide

| 指标 | 数量 |
|---|---|
| chef_common / chef_t2 / chef_t3 | **122 / 187 / 24** |
| 三标签互斥 | **是** |
| FG 显式 entry | **333**（122+187+24） |
| entry id | `item:<namespace>/<path>` |
| 解锁 | `tcth:chef_cookbook_gate`（仅 DishCookedEvent） |
| SERVING / DRINK / RAW / INGREDIENT | **未加入** |
| 新 T3 | **0** |

部署路径（均已同步预设）：

- `docs/presets/tcth-chef/`
- `Server/world/datapacks/tcth-chef/`
- **`Server/global_packs/required_data/tcth-chef/`**（服务器实际加载源；初轮仅同步 world 导致 FG 仍 84/58/24，已修正再烟雾）

---

## B. Dungeon's Delight Monster Pot

### javap 证据（服务器 JAR 1.5.0）

- 类：`net.yirmiri.dungeonsdelight.common.block.monster_pot.MonsterPotResultSlot`
- 继承：`SlotItemHandler`
- 字段：`public final MonsterPotBlockEntity tileEntity`
- 签名：`void onTake(Player, ItemStack)`
- `checkTakeAchievements` → `MonsterPotBlockEntity.awardUsedRecipes(Player, List)`（服务端清空 `usedRecipeTracker`）
- BE：`Object2IntOpenHashMap<ResourceLocation> usedRecipeTracker`（private，Accessor 读取）
- 无单独 public getRecipeUsed 可靠路径；**recipeId 仅在 tracker size==1 时上报，否则 null**

### 实现

| 项 | 内容 |
|---|---|
| CookingDevice | `DUNGEONS_DELIGHT_MONSTER_POT`（另预留 `BAKERIES_OVEN` / `BAKERIES_BLENDER` 枚举，**未发射事件**） |
| Mixin 配置 | `dungeonsdelight_compat.mixins.json` + `requiredMods=["dungeonsdelight"]` |
| Mixin | `MonsterPotResultSlotMixin`（见 6B.1 捕获时机） |
| Accessor | `MonsterPotBlockEntityAccessor#usedRecipeTracker` |
| Adapter | `DungeonsDelightDishAdapter`（公共 API 无 DD 类型） |
| 分类门 | `DishClassifier.isDish` |
| 署名 | `onTake` HEAD `DishSignatureService.sign`；发布异常 best-effort 恢复旧署名 |
| 编译依赖 | `compileOnly blank:dungeonsdelight:1.5.0`（不打包） |

### 6B.1：Shift-click recipeId 捕获修正（DD + FD 同源）

**初版问题：** 仅在 `onTake` HEAD 读 tracker。Shift-click 顺序为：

```text
onQuickCraft → checkTakeAchievements → awardUsedRecipes（清空 tracker）→ onTake
```

因此 `onTake` HEAD 时 tracker 常已为空，Shift-click 的 recipeId 丢失。

**修正（FD `CookingPotResultSlotMixin` + DD `MonsterPotResultSlotMixin`）：**

| 注入点 | 职责 |
|---|---|
| `checkTakeAchievements` **HEAD** | 用 `RecipeTrackerSnapshot.capture` 读 tracker；仅 size==1 写入；**null 不覆盖**已有非空快照 |
| `onTake` **HEAD** | 仅对真实交付栈署名（经 `DishClassifier.isDish`） |
| `onTake` **RETURN** | **唯一**发布 `DishCookedEvent` 的位置 |
| `finally` | 清空 recipeId / 旧署名快照，防止下一事件继承 |

**预期：**

- 普通点击：`onTake` → `checkTakeAchievements` 仍捕获单 recipe；RETURN 发布 1 次  
- Shift-click：`checkTakeAchievements` 先捕获；`onTake` 不擦除；RETURN 发布 1 次  
- **不在** `onQuickCraft` 发布事件  
- `recipeId=null` **不**阻止 item tier fallback  
- `onTake` 为 **void**，无 boolean 失败返回值  

共享逻辑：`com.tanrunn.tcth.impl.compat.cooking.RecipeTrackerSnapshot`（FD/DD 同一语义）。

**JAR 实证（dev-mods / 服务器同版本）：**  
`javap -c` 证明 FD/DD ResultSlot 中 `onQuickCraft`/`onTake` 均调用 `checkTakeAchievements`，且 `checkTakeAchievements` 调用 `awardUsedRecipes`（见 `CookingPotJarLifecycleTest`）。

### 未玩家验证

- 实际 Monster Pot / Cooking Pot 取餐事件次数（含 Shift-click）
- 署名/失败回滚在真玩家路径上的表现  
**不得**把单元测试或 Mixin 加载写成玩家实测通过。
---

## C. Bakeries 设备矩阵（审计 → DEFERRED）

权威 JAR：`bakeries-1.21.1-NeoForge-1.0.1`

| 设备 | BlockEntity | Menu | 玩家领取入口 | 结论 |
|---|---|---|---|---|
| Oven | `OvenBlockEntity` | `OvenMenu` + `OvenSlot`（普通 Slot） | 无专用 ResultSlot/onTake；`removeItem`/`takeItem` 兼自动化 | **DEFERRED** |
| Blender | `BlenderBlockEntity` + `OutputItemHandler` | `BlenderMenu` | `clicked` / output handler；多为中间产物 | **DEFERRED**（多数 INGREDIENT） |

原则：无 javap 证明的统一「玩家领取最终 FOOD」公共入口，**不写猜测 Mixin**。枚举已预留 `BAKERIES_*`，待后续专用注入点。

---

## 测试实际数字（6B.1 重跑）

| 套件 | 结果 |
|---|---|
| `export_phase6a_audit.test.mjs` | **47 passed** |
| `export_phase6b0_merge_preview.test.mjs` | **19 passed** |
| `./gradlew clean build --rerun-tasks --no-daemon` | **BUILD SUCCESSFUL** |
| Gradle test XML 汇总 | **suites=84 / tests=682 / failures=0 / errors=0 / skipped=0** |

新增/加强：

- `RecipeTrackerSnapshotTest`（空/单/多/Shift 不覆盖/clear/下一事件）  
- `CookingPotJarLifecycleTest`（javap 对 FD/DD 实际 JAR）  
- `ChefMerge6bDataTest`：对合并预览 **167 条逐项**断言权威/item tier/FG 分类  
- `DishSignatureServiceBoundaryTest`（空栈/署名覆盖可恢复）

---

## JAR 与部署证据

### 6B.1 初版烟雾证据作废

初版烟雾时 `Server/mods/tcth-0.2.2.jar` 实际仍为：

| 项 | 值 |
|---|---|
| 大小 | 296882 B |
| SHA-256 | `6e73800d290d583244513978154693629d97e4e53e7b1fdfb864cc77b14cbfda` |

`debug.log` 中曾出现旧注入名 `tcth$captureRecipeId(Player, ItemStack, CallbackInfo)`。  
**该次 MIXIN LOAD 证据作废，不得再引用为 6B.1 修正版加载通过。**

### 6B.1.1 纠正后（唯一有效部署证据）

| 位置 | 大小 | SHA-256 |
|---|---|---|
| `build/libs/tcth-0.2.2.jar` | **298630** B | `bfee193fbf0ce7616581756707252515acdd2637565145ef381bcabf6cd919e5` |
| `Server/mods/tcth-0.2.2.jar` | **298630** B | `bfee193fbf0ce7616581756707252515acdd2637565145ef381bcabf6cd919e5` |

构建 = 部署（已 `sha256` 双端核对）。旧 JAR 备份于 `backup-6b1.1-pre-deploy-*/`。

第三方 class / 嵌套 JAR：**无**。
---

## 部署与备份

| 路径 | 说明 |
|---|---|
| 备份 | `backup-6b-pre-merge-20260808-172826/`（权威 CSV、预设、旧 JAR、world datapack） |
| 新 JAR | `Server/mods/tcth-0.2.2.jar` |
| 预设 | `docs/presets/tcth-chef/` |
| 世界包 | `Server/world/datapacks/tcth-chef/` |
| **全局包（实载）** | `Server/global_packs/required_data/tcth-chef/` |

### 烟雾（无玩家 · 6B.1.1 正确 JAR）

日志：`Server/smoke6b11.out` / `Server/smoke6b11c.out` + `logs/debug.log`

| 检查 | 结果 |
|---|---|
| Done | **是**（~3.8–4.4s） |
| FG 分类 | **122 / 187 / 24** |
| InvalidInjectionException | **未发现** |
| MixinApplyError | **未发现** |
| NoClassDefFoundError | **未发现** |
| TCTH ERROR/WARN | **0** |
| 停服 | `stop` 命令 |
| All dimensions are saved | **是**（`smoke6b11c.out`） |
| 残留进程 | **无** |

#### debug.log 新注入方法（FD + DD 均出现）

必须存在（已确认）：

```text
farmersdelight_compat.mixins.json:CookingPotResultSlotMixin
  tcth$captureRecipeIdOnAchievements(ItemStack, CallbackInfo)
  tcth$signOnTake(Player, ItemStack, CallbackInfo)
  tcth$onDishTaken(Player, ItemStack, CallbackInfo)

dungeonsdelight_compat.mixins.json:MonsterPotResultSlotMixin
  tcth$captureRecipeIdOnAchievements(ItemStack, CallbackInfo)
  tcth$signOnTake(Player, ItemStack, CallbackInfo)
  tcth$onDishTaken(Player, ItemStack, CallbackInfo)
```

必须不存在：

```text
tcth$captureRecipeId(Player, ItemStack, CallbackInfo)
```

（本轮 `debug.log`：**OLD_METHOD_ABSENT**。）

```
DATA PASS
BUILD PASS
MIXIN LOAD PASS   ← 仅针对 bfee193f 部署 + 上述 debug 证据
PLAYER LIVE NOT TESTED
```

---

## 回滚

1. 停服  
2. 恢复 `backup-6b-pre-merge-20260808-172826/jars/tcth-0.2.2.jar` → `Server/mods/`  
3. 恢复 `backup-6b-pre-merge-*/食物三档分类表.csv`  
4. `rsync` 备份中的 `tcth-chef-preset` → `docs/presets`、`world/datapacks`、`global_packs/required_data/tcth-chef`  
5. 启动验证 FG 回到 84/58/24  

---

## 建议暂存清单（不得自行 commit）

- `配方与经济管理/统一配方表/食物三档分类表.csv`
- `mod develop/.../docs/presets/tcth-chef/**`（dish_tiers / tags / fieldguide）
- `Server/world/datapacks/tcth-chef/**`（若纳入版本控制）
- `Server/global_packs/required_data/tcth-chef/**`
- `Server/mods/tcth-0.2.2.jar`（或仅源码构建产物）
- Java：`CookingDevice.java`、`impl/compat/dungeonsdelight/*`、`mixin/dungeonsdelight/*`
- `dungeonsdelight_compat.mixins.json`、`neoforge.mods.toml`、`build.gradle`
- 测试：`ChefMerge6bDataTest`、`DungeonsDelight*`、`FieldGuideDataTest` 计数更新、`Phase4a4` DD 包例外
- 本报告 `docs/phase-6b-chef-new-food-integration-report.md`

**禁止**提交 playerdata / JobsData / UNITE / 悬赏经济。

---

## 未在线验证清单

- [ ] 玩家在 Monster Pot / Cooking Pot **普通点击**取餐：1 事件 + recipeId（单 tracker）  
- [ ] 玩家 **Shift-click** 取餐：1 事件 + recipeId 仍正确（6B.1 修正点）  
- [ ] 署名写入真实交付栈；旧署名被覆盖  
- [ ] 新 167 道料理出锅解锁 Field Guide  
- [ ] Bakeries 烤箱玩家取物（当前无 Mixin）  

**6B.1.1 部署证据修正完成。等待复审。不 commit/push。**
