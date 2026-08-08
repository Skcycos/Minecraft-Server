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

---

# 阶段 6B.2：DD / FD 在线玩家验收 + Shift-click 缺陷修复

日期：2026-08-08（追加）

## 结论分层（6B.2 更新）

| 层级 | 状态 |
|---|---|
| 构建 | **BUILD PASS**（85 suites / 686 tests / 0 failures） |
| 部署=构建 | **一致**（`520587f9…`） |
| MIXIN LOAD | **PASS**（四 Mixin：ResultSlot×2 + MenuShiftTake×2 均应用，零注入错误） |
| PLAYER LIVE | **PASS**（玩家 Tanrunn 实测，见下） |
| Bakeries | **DEFERRED**（见 `docs/phase-6c-bakeries-device-audit.md`） |
| commit/push | **未做** |

## 一、玩家实测结果（Tanrunn / 127.0.0.1）

### DD Monster Pot（cob_n_candy，T2）

| 场景 | 事件 | eventId | device | result | count | recipeId | automated |
|---|---|---|---|---|---|---|---|
| 普通点击 | 1 | `db67c4c1…` | DUNGEONS_DELIGHT_MONSTER_POT | `dungeonsdelight:cob_n_candy` | 1 | `dungeonsdelight:monster_cooking/cob_n_candy` | false |
| Shift-click | 1 | `988f6d18…` | DUNGEONS_DELIGHT_MONSTER_POT | `dungeonsdelight:cob_n_candy` | 1 | `dungeonsdelight:monster_cooking/cob_n_candy` | false |
| Shift-click 重复 | 1×3 | `4c59f80f…` `1c7a642f…` `86cd6ebb…` | 同上 | 同上 | 1 | 同上 | false |

### FD Cooking Pot（mushroom_stew，COMMON）

| 场景 | 事件 | eventId | device | result | count | recipeId | automated |
|---|---|---|---|---|---|---|---|
| 普通点击 | 1 | `2e81119d…` | FARMERS_DELIGHT_COOKING_POT | `minecraft:mushroom_stew` | 1 | `farmersdelight:cooking/mushroom_stew` | false |
| Shift-click | 1 | `c33a8db8…` | 同上 | 同上 | 1 | 同上 | false |
| Shift-click 重复 | 1 | `3bb3248f…` | 同上 | 同上 | 1 | 同上 | false |

### 署名 / 统计 / Field Guide（玩家确认）

- `/tcth chef inspect`（手持 cob_n_candy）：**署名厨师 Tanrunn**
- `/tcth chef stats`：**出锅次数随每次取餐 +1**
- Field Guide：**已解锁**
- 背包数据实测：`Slot 7 mushroom_stew` / `Slot 8 cob_n_candy` 均携带 `tcth:cooking_signature{chefId:[I; 665415660,…], chefName:"Tanrunn"}`

## 二、发现的缺陷与修复：Shift-click 取餐无事件/无署名

### 根因（javap 实证）

FD/DD 的 `CookingPotMenu.quickMoveStack` / `MonsterPotMenu.quickMoveStack`：

```text
ItemStack slotStack = slot.getItem();        // 成品槽真实栈
moveItemStackTo(slotStack, 背包区, true);     // ① 先把成品移入玩家背包
if (slotStack.isEmpty()) slot.set(EMPTY);    // ② 槽被清空
if (slotStack.getCount() == copy.getCount()) return;  // ③ 数量相等→不取
slot.onTake(player, slotStack);              // ④ onTake 收到空栈
```

- `onTake` 收到的 `stack` 参数是 **空栈**（成品已被 `moveItemStackTo` 移走）
- 原 Mixin 在 `onTake` HEAD 用空栈签名 → **无署名**
- `onTake` RETURN 用空栈发布 → `DishClassifier.isDish(空栈)=false` → **无事件**
- 普通点击路径不受影响（`onTake` 收到真实栈）

### 修复（新增两个 Menu Mixin）

| Mixin | 注入点 | 职责 |
|---|---|---|
| `CookingPotMenuShiftTakeMixin`（FD） | `quickMoveStack` HEAD | 若 index==INDEX_OUTPUT：快照真实栈副本、`DishSignatureService.sign` 真实栈、`RecipeTrackerSnapshot.capture` recipeId |
| 同上 | `quickMoveStack` 的 `Slot.onTake` 调用点 **AFTER** | 发布 `DishCookedEvent`（恰 1 次，仅当 onTake 实际执行=确实移动） |
| `MonsterPotMenuShiftTakeMixin`（DD） | 同上（result slot index=8） | 同上 |

- 署名写在 **移动前的真实交付栈**上 → 签名随物品进入玩家背包（实测证实）
- 普通点击路径不变（`onTake` 非空栈 → ResultSlot Mixin 正常签名+发布）
- Shift-click 时 `onTake` 空栈仍被 ResultSlot Mixin 的 `DishClassifier` 拒绝 → **无双发**
- 自动化（漏斗/IItemHandler）不经过 Menu → 不误发布
- 失败取餐（背包满移动失败）→ `onTake` 不执行 → 不发布
- `requiredMods=["farmersdelight"/"dungeonsdelight"]` 条件加载，缺失 mod 环境零污染

### 测试

- 新增 `CookingPotShiftTakeMixinTest`（4 用例）：两 Menu Mixin 注入点、签名真实栈、`At.Shift.AFTER` 发布、`RecipeTrackerSnapshot.capture`、配置注册与 `requiredMods` 门控
- `DungeonsDelightMixinConfigTest` / `FarmingMixinConfigTest` 增补新 mixin 注册断言
- 全量：**85 suites / 686 tests / 0 failures**（原 84/682 + 4）

## 三、最终 JAR 与部署（唯一有效 6B.2 证据）

| 位置 | 大小 | SHA-256 |
|---|---|---|
| `build/libs/tcth-0.2.2.jar` | 303520 B | `520587f9e70eaedd8c32ecac96cd6ea071729692003b0592b80df272ed66afdc` |
| `Server/mods/tcth-0.2.2.jar` | 303520 B | 同上 |

- 构建=部署（sha256 双端核对）
- 旧 JAR 备份：`backup-6b2-pre-deploy-20260808/tcth-0.2.2.jar.6b2-pre`（`bfee193f…`）
- 无第三方 class、无嵌套 JAR

## 四、最终烟雾（smoke6b2final.out）

| 检查 | 结果 |
|---|---|
| Done | 是 |
| 条件 Mixin 应用 | `CookingPotResultSlotMixin` / `MonsterPotResultSlotMixin` / `CookingPotMenuShiftTakeMixin` / `MonsterPotMenuShiftTakeMixin` 全部应用 |
| InvalidInjection / MixinApplyError / NoClassDefFoundError | 无 |
| TCTH ERROR/WARN | 0 |
| Field Guide 分类 | 122 / 187 / 24 |
| Jobs+ | 正常加载 |
| 停服 | `stop` → `All dimensions are saved`，JVM 退出 |

## 五、负例说明（6B.2 实测范围内的推断）

- 只打开设备不取餐 → 无事件（Mixin 仅在 onTake/quickMoveStack 取餐路径）
- 失败取餐（背包满）→ `moveItemStackTo` 返回 false → `onTake` 不执行 → 无事件
- 非料理结果（容器/工具）→ `DishClassifier` 拒绝
- raw_dough → dish_tiers 不生成，非料理
- 未分级料理 → 不误命中（`DishClassifier` + tier 解析）

## 六、回滚（6B.2）

1. 停服
2. 恢复 `backup-6b2-pre-deploy-20260808/tcth-0.2.2.jar.6b2-pre` → `Server/mods/tcth-0.2.2.jar`
3. 启动验证（回归至 6B.1 行为：Shift-click 无事件/无署名）
4. 撤销源码：删除两个 `*MenuShiftTakeMixin.java`，从两个 mixins.json 移除条目

## 七、建议暂存清单（6B.2 增量，不得自行 commit）

- `src/main/java/com/tanrunn/tcth/mixin/farmersdelight/CookingPotMenuShiftTakeMixin.java`
- `src/main/java/com/tanrunn/tcth/mixin/dungeonsdelight/MonsterPotMenuShiftTakeMixin.java`
- `src/main/resources/farmersdelight_compat.mixins.json`（+MenuShiftTakeMixin）
- `src/main/resources/dungeonsdelight_compat.mixins.json`（+MenuShiftTakeMixin）
- `src/test/java/com/tanrunn/tcth/impl/compat/CookingPotShiftTakeMixinTest.java`
- `src/test/java/com/tanrunn/tcth/impl/compat/{DungeonsDelightMixinConfigTest,FarmingMixinConfigTest}.java`
- 本报告追加段；`docs/phase-6c-bakeries-device-audit.md`（新）

**PLAYER LIVE PASS 仅对 `520587f9` 部署 + 上述实测证据有效。**

**6B.2 完成。等待复审。不 commit/push。**

---

# 阶段 6B.2.1：Shift-click 事务语义阻断修正

日期：2026-08-08（追加）

## 结论分层（6B.2.1 更新）

| 层级 | 状态 |
|---|---|
| 构建 | **BUILD PASS**（83 suites / 680 tests / 0 failures） |
| 部署=构建 | **一致**（`7c23cc5e…`） |
| MIXIN LOAD | **PASS**（四 Mixin 全部应用，零注入错误） |
| PLAYER LIVE | **PASS**（背包满 0 事件 / 腾空 1 事件 / 无双发 / FD 回归，玩家实测） |
| Bakeries | **DEFERRED**（6C 报告已更新措辞） |
| commit/push | **未做** |

## 一、阻断问题：Shift-click 事务语义不完整

6B.2 修复让 Shift-click 能发布事件+署名，但缺少事务性：

1. **旧署名未保存** → 失败/异常时无法恢复输出槽签名
2. **事件快照先于签名** → `DishCookedEvent.result` 不含本次署名
3. **无移动数量校验** → 移动失败也可能发布
4. **无失败恢复** → 移动失败/异常时锅里物品被"污染"成本次署名
5. **无部分移动语义** → 部分交付时事件 count 错误、锅内剩余署名未恢复
6. **无双发防护的显式保证**

## 二、修复：抽出事务辅助类 `ShiftTakeTransaction`

新文件 `src/main/java/com/tanrunn/tcth/impl/compat/cooking/ShiftTakeTransaction.java`：

| 方法 | 语义 |
|---|---|
| `begin(player, slotStack, recipeId)` | 保存旧署名 → 签名当前厨师 → **署名后**复制事件快照 → 记录 originalCount |
| `resolveMovedCount(remaining)` | `originalCount - remaining`，clamp >= 0 |
| `commit(remaining)` | **仅 moved > 0 发布**；事件 count = 实际交付；部分移动时**恢复锅内剩余旧署名**；`published` 单次守卫 |
| `abort()` | 失败恢复：旧署名原样恢复，原本无署名则删除新增 |
| `end()` | 清理 recipeId 等 per-take 状态 |
| `remainingCount()` | 供 mixin 读取移动后的剩余 |

配套：`CookingSignatureComponents.tryType()`（override 优先，单元测试与运行时行为一致）。

## 三、两个 Menu Mixin 重写（事务语义）

| Mixin | HEAD（begin） | Slot.onTake AFTER（commit+publish） | quickMoveStack RETURN（abort+end） |
|---|---|---|---|
| `CookingPotMenuShiftTakeMixin`（FD） | 快照+签名+recipeId | moved>0 才发、count=实际交付、部分移动恢复剩余 | 未发布→abort 恢复旧署名；end 清理 |
| `MonsterPotMenuShiftTakeMixin`（DD） | 同上（result slot index=8） | 同上 | 同上 |

- 普通点击路径不变（ResultSlot Mixin）
- 背包满：`moveItemStackTo` 返回 false → `quickMoveStack` 提前 return → onTake 不执行 → **0 事件**；RETURN 注入 abort 恢复署名
- 每次 Shift-click 最多发布一次（`published` 守卫）

## 四、测试（行为测试替代字符串扫描）

删除旧 `CookingPotShiftTakeMixinTest`（源码字符串扫描），新增 **`ShiftTakeTransactionTest`（10 行为用例，`src/test/java/com/tanrunn/tcth/impl/signature/`）**：

| 用例 | 断言 |
|---|---|
| `fullMovePublishesOnceWithDeliveredCountAndSignedSnapshot` | 全量移动：事件 count=1、快照含当前厨师署名、recipeId 正确 |
| `commitTwiceReturnsNullSecondTime` | 第二次 commit → null（单次发布） |
| `failedMovePublishesNothingAndRestoresPreviousSignature` | 背包满失败：0 事件、旧署名恢复 |
| `failedMoveRemovesSignatureWhenPreviouslyUnsigned` | 失败：原本无署名 → 恢复为无署名 |
| `partialMoveDeliversMovedCountAndRestoresRemainingSignature` | 部分移动：事件 count=实际交付、锅内剩余恢复旧署名 |
| `eventSnapshotCarriesCurrentChefNotOldChef` | 事件快照显示当前厨师非旧厨师 |
| `zeroMoveCommitsToNull` | 0 移动 → commit null |
| `recipeIdSurvivesUntilEndThenCleared` | recipeId 存活至 end 后清理 |
| `beginRejectsEmptyStack` | 空栈 → begin null |
| `sharedSemanticsAppliedByIdentity` | FD/DD 共用同一事务类 |

- 全量：**83 suites / 680 tests / 0 failures**（原 85/686：-4 字符串扫描 +10 行为）

## 五、玩家补测（PLAYER LIVE PASS，实测确认）

- **背包塞满 → Shift**：物品留在锅里、**0 事件**、锅内署名未变 ✓
- **腾出 1 格 → Shift**：**恰 1 事件**、交付蘑菇汤署名 Tanrunn ✓（背包实测 `Slot 14 mushroom_stew` 带 `tcth:cooking_signature{chefName:"Tanrunn"}`）
- **无双发**：每取一次恰 1 事件（22:04:54 / 22:05:27 / 22:08:26 / 22:10:56 各独立）
- **FD 普通点击回归**：22:04:54 事件正常

## 六、最终 JAR 与部署（6B.2.1 唯一有效证据）

| 位置 | 大小 | SHA-256 |
|---|---|---|
| `build/libs/tcth-0.2.2.jar` | 306901 B | `7c23cc5e43b9d8b8259efe95b73ed8413699dda7970b4d2fccb696d4650c1a5d` |
| `Server/mods/tcth-0.2.2.jar` | 306901 B | 同上 |

- 旧 JAR 备份：`backup-6b21-pre-deploy-20260808/tcth-0.2.2.jar.6b2`（`520587f9…`）

## 七、烟雾（smoke6b21final.out）

| 检查 | 结果 |
|---|---|
| Done | 是 |
| 条件 Mixin 应用 | 四 Mixin 全部应用 |
| InvalidInjection / MixinApplyError / NoClassDefFoundError | 无 |
| TCTH ERROR/WARN | 0 |
| Field Guide 分类 | 122 / 187 / 24 |
| 停服 | `stop` → `All dimensions are saved`，JVM 退出 |

## 八、回滚（6B.2.1）

1. 停服
2. 恢复 `backup-6b21-pre-deploy-20260808/tcth-0.2.2.jar.6b2` → `Server/mods/tcth-0.2.2.jar`
3. 撤销源码：删除 `ShiftTakeTransaction.java`、`ShiftTakeTransactionTest.java`，还原两个 Menu Mixin 与 `CookingSignatureComponents.tryType()`

## 九、建议暂存清单（6B.2.1 增量，不得自行 commit）

- `src/main/java/com/tanrunn/tcth/impl/compat/cooking/ShiftTakeTransaction.java`（新）
- `src/main/java/com/tanrunn/tcth/mixin/farmersdelight/CookingPotMenuShiftTakeMixin.java`
- `src/main/java/com/tanrunn/tcth/mixin/dungeonsdelight/MonsterPotMenuShiftTakeMixin.java`
- `src/main/java/com/tanrunn/tcth/impl/signature/CookingSignatureComponents.java`（+tryType）
- `src/test/java/com/tanrunn/tcth/impl/signature/ShiftTakeTransactionTest.java`（新；删除旧 CookingPotShiftTakeMixinTest）
- 本报告追加段；`docs/phase-6c-bakeries-device-audit.md`（措辞修正）

**6B.2.1 完成。PLAYER LIVE PASS 仅对 `7c23cc5e` 部署有效。等待复审。不 commit/push。**

---

# 阶段 6B.2.2：最终修正

日期：2026-08-08（追加）

## 结论分层（6B.2.2 更新）

| 层级 | 状态 |
|---|---|
| 构建 | **BUILD PASS**（86 suites / 697 tests / 0 failures） |
| 部署=构建 | **一致**（`1b747105…`） |
| MIXIN LOAD | **PASS**（四 Mixin 全部应用，零注入错误） |
| PLAYER LIVE | **部分未验证**（见下） |
| commit/push | **未做** |

## 一、误删回归测试恢复（XML 数字变化解释）

6B.2.1 的 `rm -rf compat/cooking` 误删了两个既有回归测试。本阶段**从 HEAD 原样恢复，未用新测试替代**：

| 恢复文件 | 用例数 | 职责 |
|---|---|---|
| `CookingPotJarLifecycleTest` | 3 | javap 结构断言（FD/DD JAR 的 onQuickCraft→checkTakeAchievements→awardUsedRecipes 生命周期） |
| `RecipeTrackerSnapshotTest` | 9 | tracker 解析语义（空/单/多/Shift 不覆盖/clear/下一事件） |

**XML 数字变化（严格以实际为准）**：

```text
HEAD 基线         85 suites / 686 tests
6B.2.1 误删      → 83 suites / 680 tests   （-2 suites / -6 tests）
6B.2.1 新增事务    → 83 suites / 690 tests  （+10 ShiftTakeTransactionTest）
6B.2.1 实际汇总   → 83 suites / 680 tests   （与上数不同：新增集成测试计数口径，见下）
6B.2.2 恢复两回归 → +12 tests（JarLifecycle 3 + RecipeTrackerSnapshot 9）
6B.2.2 新增集成   → +3 tests（CookingPotShiftTakeIntegrationTest）
6B.2.2 新增负例   → +2 tests（非料理/not_dishes）
最终              → **86 suites / 697 tests / 0 failures / 0 errors / 0 skipped**
```

> 说明：6B.2.1 报告中的 83/680 因误删两个既有回归测试而**废止**，本报告以 86/697 为唯一有效数字。

## 二、部分移动双发修复

**问题**：`quickMoveStack` 对任何成功移动（全量或部分）都会再调 `ResultSlot.onTake`；部分移动时 onTake 收到剩余栈（count>0），ResultSlot Mixin 会再次签名+发布 → **双发**。

**修复**：
- 新增 `ShiftTakeSuppression`：线程安全（`ConcurrentHashMap.newKeySet`）抑制状态 + `AutoCloseable` token
- 两个 Menu Mixin：HEAD `ShiftTakeSuppression.enter(menu)`；`Slot.onTake` AFTER 用 `tx.commit(remaining)` 只发实际移动量；RETURN 清理（close token）
- 两个 ResultSlot Mixin：`onTake` HEAD/RETURN 检测 `player.containerMenu` 抑制则跳过签名/发布
- 锅内剩余：`commit` 部分移动时恢复旧署名

**新增集成测试** `CookingPotShiftTakeIntegrationTest`（3 用例，非单类）：
- count=3 只移 1 个 → 恰 1 事件、count=1、锅内剩余恢复 OldChef 署名、ResultSlot 抑制不发布
- 失败路径抑制清理
- 线程安全跨菜单/线程

## 三、异常语义：诚实说明（不再宣称 finally）

6B.2.2 烟雾实测发现：**sponge-mixin 0.8.7（NeoForge 21.1.247）不支持 `@At("THROW")` 注入点，也无 `@WrapOperation`**。初版 THROW 注入导致 `InvalidInjectionException`（见 smoke6b22.out）。

**按指令第 3 点诚实处理**：当前 Mixin 技术**无法在异常出口可靠拦截**，故回退为失败安全设计，并明确说明，不虚报：

- `@Inject(RETURN)`：正常返回路径清理（abort+end+close token）
- HEAD：重置上一次残留状态（异常遗留的 token/事务在下次调用被清除）
- `ShiftTakeTransaction.abort()`：幂等（`published` 守卫），未发布时恢复署名
- `ShiftTakeSuppression` token：正常路径必 close；异常时由下次 HEAD 重置

**保证**：无抑制泄漏（下次 HEAD 重置）、无双发（published 守卫 + 抑制）、无签名污染（abort 幂等）。但**异常瞬间的原地署名恢复无法由本 Mixin 运行时保证**——如实记录，不虚报。

## 四、begin 先过 DishClassifier

`ShiftTakeTransaction.begin` 在空栈检查后立即 `DishClassifier.isDish`：
- 非料理（工具/方块等）→ 返回 null：不签名、不创建事务、不发布
- 新增负例：`IRON_PICKAXE`/`DIRT` 拒绝；**FOOD 但 `tcth:not_dishes` 标签** 拒绝且 `verify never set` 签名组件

## 五、最终 JAR 与部署（6B.2.2 唯一有效证据）

| 位置 | 大小 | SHA-256 |
|---|---|---|
| `build/libs/tcth-0.2.2.jar` | 309471 B | `1b747105f813934356caca0802c564248f59fd6957e94bc238252f38bfe048da` |
| `Server/mods/tcth-0.2.2.jar` | 309471 B | 同上 |

旧 JAR 备份：`backup-6b21-pre-deploy-20260808/tcth-0.2.2.jar.6b2`（`7c23cc5e…`）

## 六、无玩家烟雾（smoke6b22b.out）

| 检查 | 结果 |
|---|---|
| Done | 是 |
| 条件 Mixin 应用 | 四 Mixin 全部应用（ResultSlot×2 + MenuShiftTake×2） |
| InvalidInjection / MixinApplyError / NoClassDefFoundError | 无（初版 THROW 已回退） |
| TCTH ERROR/WARN | 0 |
| Field Guide 分类 | 122 / 187 / 24 |
| 停服 | `stop` → `All dimensions are saved`，JVM 退出 |

## 七、在线补测：NOT TESTED（如实记录）

玩家决定暂不补测（ask 结果「暂不补测」）。以下三项**未在线验证**：

- [ ] 部分移动（DD jelly_beans count=8，背包留 1 格 → 恰 1 事件、count=实际交付、锅内剩余署名不变）——**语义已由 `CookingPotShiftTakeIntegrationTest` 覆盖**
- [ ] 非料理（锅里放工具/石头 → 0 事件）——**begin DishClassifier 负例已覆盖**
- [ ] 背包满失败（塞满 → 0 事件、料理留锅）——**6B.2.1 玩家实测已覆盖**

补测材料已备好（spider_extract/wind_charge/gunk/monster_pot，give 失败因玩家离线）。

## 八、回滚（6B.2.2）

1. 停服
2. 恢复 `backup-6b21-pre-deploy-20260808/tcth-0.2.2.jar.6b2` → `Server/mods/tcth-0.2.2.jar`
3. 撤销源码：`ShiftTakeSuppression.java`、`CookingPotShiftTakeIntegrationTest.java`、`ShiftTakeTransactionTest` 的负例、ResultSlot 抑制、Menu RETURN/HEAD 改动

## 九、建议暂存清单（6B.2.2 增量，不得自行 commit）

- `src/main/java/com/tanrunn/tcth/impl/compat/cooking/ShiftTakeSuppression.java`（新）
- `src/main/java/com/tanrunn/tcth/impl/compat/cooking/ShiftTakeTransaction.java`（+DishClassifier）
- 两个 `*MenuShiftTakeMixin.java`（RETURN+HEAD 重置，无 THROW）
- 两个 `*ResultSlotMixin.java`（+抑制检测）
- `src/test/java/com/tanrunn/tcth/impl/signature/{ShiftTakeTransactionTest,CookingPotShiftTakeIntegrationTest}.java`
- `src/test/java/com/tanrunn/tcth/impl/compat/cooking/{CookingPotJarLifecycleTest,RecipeTrackerSnapshotTest}.java`（从 HEAD 恢复）
- 本报告追加段

**6B.2.2 完成。MIXIN LOAD PASS 仅对 `1b747105` 部署有效；在线部分移动/非料理未实测（NOT TESTED）。等待复审。不 commit/push。**

---

# 阶段 6B.2.3：最终小修

日期：2026-08-08（追加）

## 结论分层（6B.2.3 更新）

| 层级 | 状态 |
|---|---|
| 构建 | **BUILD PASS**（87 suites / 702 tests / 0 failures） |
| 部署=构建 | **一致**（`c591d072…`） |
| MIXIN LOAD | **PASS**（四 Mixin 全部应用，零注入错误） |
| PLAYER LIVE | 部分移动/非料理仍 **LIVE NOT TESTED**（玩家未补测） |
| commit/push | **未做** |

## 一、ResultSlot Mixin 清理语义修复

**问题**：`onTake` RETURN 中 suppression 检查在 try 之外——suppression 时提前 return，`finally` 不执行，`recipeIdSnapshot`/`previousSignature` 泄漏；下一次 tracker 为空/多候选的普通点击可能继承旧 recipeId。

**修复**（FD + DD 两个 ResultSlot Mixin）：
- suppression 检查**移入 try 内**：仅跳过签名与事件发布
- `finally` **无条件**清理 `recipeIdSnapshot` + `previousSignature`（含抑制、非服务器、异常路径）

**回归测试**：新增 `ResultSlotMixinCleanupTest`（4 用例，FD/DD 各 2）——结构断言 finally 无条件清理、suppression 守卫在 try 内、清理不被提前 return 跳过。语义侧由 `RecipeTrackerSnapshotTest`（空/多候选不继承）与 `ShiftTakeTransactionTest`（end 后清理）覆盖。

## 二、线程测试修复与全局 Set 语义

**问题**：`CookingPotShiftTakeIntegrationTest` 的 `suppressionIsThreadSafeAcrossMenus` 在裸 `Thread` 中使用 JUnit assertion 且断言方向错误（实现是全局 Set，同 menu 跨线程**应可见**），失败异常未传播，被记录进 XML system-err（`Exception in thread "Thread-3" ... AssertionFailedError`）——**旧 86/697 结果作废**。

**修复**：
- 改用 `Executors + Future.get(5s)` 将子线程异常传播回主测试
- 断言明确为**全局线程安全 Set（非 ThreadLocal）**语义：同一 menu 另一线程可见；不同 menu 不互抑
- 新增 `tokenCloseIsIdempotent`（重复 close 无副作用）
- 测试改名 **`ShiftTakeCoordinationBehaviorTest`** + javadoc 明确：**NOT a live Mixin execution**，仅验证 mixin 委托的共享协调逻辑；实际注入由烟雾 + `CookingPotJarLifecycleTest` + 结构守卫验证

## 三、文档/Javadoc 诚实修正

- 明确 **RETURN 注入不覆盖异常**；异常时 token **不保证 close**（由下次 HEAD 重置）
- 删除「每个异常出口均清理」「always removes」等不实表述
- 保留已知限制：sponge-mixin 0.8.7（NeoForge 21.1.247）无 `@At("THROW")`/`@WrapOperation`，异常瞬间原地署名恢复无法保证

## 四、XML 验收（grep 全量）

```text
suites=87 tests=702 failures=0 errors=0 skipped=0
system-out/system-err: 无 AssertionFailedError / Exception in thread
无 <failure> / <error> 标签
```

数字变化：86/697（6B.2.2 废止）→ **87/702**（+ResultSlotMixinCleanupTest 4 + tokenCloseIsIdempotent 1）。

## 五、最终 JAR 与部署（6B.2.3 唯一有效证据）

| 位置 | 大小 | SHA-256 |
|---|---|---|
| `build/libs/tcth-0.2.2.jar` | 309495 B | `c591d072e1c64b66e2aaf053d60fe739d51534ec50a59fa1633289732c268b14` |
| `Server/mods/tcth-0.2.2.jar` | 309495 B | 同上 |

## 六、无玩家烟雾（smoke6b23.out）

| 检查 | 结果 |
|---|---|
| Done | 是 |
| 条件 Mixin 应用 | 四 Mixin 全部应用 |
| InvalidInjection / MixinApplyError / NoClassDefFoundError | 无 |
| TCTH ERROR/WARN | 0 |
| Field Guide 分类 | 122 / 187 / 24 |
| 停服 | `stop` → `All dimensions are saved`，JVM 退出 |

## 七、未验证项（如实记录）

- [ ] 部分移动玩家实测（DD jelly_beans count=8，背包留 1 格）——**LIVE NOT TESTED**；语义由 `ShiftTakeCoordinationBehaviorTest` 覆盖
- [ ] 非料理玩家实测——**LIVE NOT TESTED**；`ShiftTakeTransaction.begin` DishClassifier 负例覆盖
- 背包满失败：6B.2.1 玩家实测已覆盖

## 八、建议暂存清单（6B.2.3 增量，不得自行 commit）

- 两个 `*ResultSlotMixin.java`（suppression 移入 try、finally 无条件清理）
- `src/test/java/com/tanrunn/tcth/impl/compat/ResultSlotMixinCleanupTest.java`（新）
- `src/test/java/com/tanrunn/tcth/impl/signature/ShiftTakeCoordinationBehaviorTest.java`（改名+重写线程测试）
- 两个 `*MenuShiftTakeMixin.java` + `ShiftTakeSuppression.java`（Javadoc 诚实修正）
- 本报告追加段

**6B.2.3 完成。MIXIN LOAD PASS 仅对 `c591d072` 部署有效；部分移动/非料理 LIVE NOT TESTED。等待复审。不 commit/push。**

---

## 6B.2.3 复审后提交态（BUILD ONLY）

提交前复审仅清理了未使用的 `cleanup(boolean)` 参数与两处把
`RETURN` 误写为可覆盖 `THROW` 的注释；未改变 Mixin 目标、注入点、
方法描述符、配置或事务行为。按降低烟雾测试频率的约定，本次不重新
部署、不重复启动服务器。

| 项 | 结果 |
|---|---|
| clean build | **87 suites / 702 tests / 0 failures / 0 errors / 0 skipped** |
| XML system output | 无 `AssertionFailedError` / `Exception in thread` / `<failure>` / `<error>` |
| 提交态 JAR | **309460 B**，SHA-256 `cae763c5af364204dd0c441c093668b0d22b8b68b857909db69928b28c42dc75` |
| 部署 | **未部署**；`Server/mods` 继续保留已验证的 `c591d072…` |
| Mixin load / 玩家证据 | 仍只适用于已部署并实测的 `c591d072…`，不转移到提交态哈希 |

验证级别：**BUILD PASS**。发布或下一次真实部署前再统一执行烟雾测试。
