# 阶段 6C：Bakeries 设备审计报告

日期：2026-08-08

## 结论

**全部 Bakeries 设备 DEFERRED。** 无一台满足「玩家真实领取成品 + 普通/Shift-click 全覆盖 + 排除失败 + 区分自动化 + 真实料理栈 + 每次一次 + 注入点存在 + 条件加载 + 零污染」的实现门槛，**不写 Mixin、不猜注入点、不轮询方块实体**。保留 `BAKERIES_OVEN` / `BAKERIES_BLENDER` 枚举（不发射事件）。

---

## 一、权威信息

### 服务器实际 JAR（最高权威）

| 项 | 值 |
|---|---|
| 文件名 | `[烘焙坊]bakeries-1.21.1-NeoForge-1.0.1.jar` |
| modId | `bakeries` |
| 版本 | `1.21.1-NeoForge-1.0.1` |
| 大小 | 2653025 B |
| SHA-256 | `a36e34607c79d33217575524c0caf9405a28aaf74ed8c51a65f656cdd7e76947` |
| 依赖 | `rcg_lib`（required）、`ponder`（optional client） |
| 前置 | `neoforge`、`minecraft 1.21.1` |

### 源码辅助（远端仓库，非权威）

> **权威声明**：本报告的注入点判断**只以服务器实际 JAR（`javap` 字节码）为准**。下方源码信息来自 Bakeries 上游仓库，仅作理解用，**不作为注入点或版本匹配的核验依据**；若本地源码目录缺失或与服务器版本不符，不影响本报告任何结论。

| 项 | 值 |
|---|---|
| 来源 | 上游仓库（分支 `NeoForge-1.21.1`） |
| 参考 HEAD | `4fb8864e`（2026/08/07，声明 `1.21.1-NeoForge-1.0.2`，**已移除 rcg_lib**、authors 4 人） |
| 服务器 JAR 声明版本 | `1.21.1-NeoForge-1.0.1`、`authors="Renyigesai,1-weibai-1"`、依赖 `rcg_lib`（**与参考 HEAD 不一致**） |

说明：参考 HEAD 与服务器版本不匹配（1.0.2 vs 1.0.1），本次仅以 `javap` 字节码核对服务器 JAR 的结构（类名/方法签名/字段），**未依赖、未修改任何源码**；所有结论以服务器 JAR 为唯一权威。

---

## 二、必须审计设备清单与调用链矩阵

### 1. Bakeries Oven（烤箱）

| 问题 | 证据 |
|---|---|
| BlockEntity | `OvenBlockEntity extends BlockEntity implements Container, MenuProvider`（6 槽 `ItemStackHandler`，每槽限 1） |
| Menu | `OvenMenu`（6 个 `OvenSlot` + 玩家槽） |
| Slot | `OvenSlot extends Slot`（vanilla 普通 Slot，仅重写 `getMaxStackSize`；**无 `onTake` override**） |
| 成品槽索引 | **无独立 ResultSlot**；`craftItem` 原地 `itemHandler.setStackInSlot(slot, takeItem)` —— 6 槽既是原料槽也是成品槽（原位替换） |
| 配方完成时机 | `serverTick → recipeItem → craftItem(slot, perfect)`（`getCurrentRecipe(slot)` 实时查表） |
| 玩家普通点击 | vanilla `AbstractContainerMenu.doClick → Slot.onTake`（继承自 `Slot`，OvenSlot 未覆盖） |
| Shift-click | `OvenMenu.quickMoveStack`：槽限 1 个，成品**完全移走**后 `slotStack.isEmpty()` → `setByPlayer(EMPTY)` → 数量相等 → **`slot.onTake` 不被调用**（javap：`if_icmpne` 不跳转） |
| 自动输出/漏斗 | `getOptionalIItemHandler()` = 原始 `itemHandler`；漏斗走 `Container.removeItem` / `IItemHandler.extractItem`，**不经过 Menu/onTake** |
| 公开事件/API | 无料理事件（api/event 仅 `AnvilLandingEvent` / `PlayerLookBlockEvent` / `SnifferDropSeedEvent`） |
| 真实玩家 | `Slot.onTake(Player, ItemStack)` 参数可得 |
| 真实交付栈 | 普通点击可得；**Shift-click 丢失** |
| recipeId | `getCurrentRecipe(slot)` 实时计算，**无 recipe tracker**；取餐时无法恢复（成品槽与原料槽同槽） |
| 失败取餐 | 无失败概念；取走**原料**也会命中普通 `onTake`（无法区分成品/原料/失败） |
| 重复触发 | 普通点击每次 1 次 |
| 最小稳定注入点 | 无（shift-click 不走 onTake；无法区分取走物） |

### 2. Bakeries Blender（搅拌机）

| 问题 | 证据 |
|---|---|
| BlockEntity | `BlenderBlockEntity extends BaseContainerBlockEntity`（`inventory` + `filtrationinventory`） |
| Menu | `BlenderMenu`（输入 0-8 + 容器 9 + 输出 10 + 过滤槽 11-20） |
| Slot | 输出槽 10 = **`SlotItemHandler`（NeoForge 泛类，非 Bakeries 类）**；`FiltrationSlot extends SlotItemHandler` |
| 成品槽索引 | `OUTPUT_SLOT=10`（独立成品槽） |
| 配方完成时机 | `craftTick → craftItem`（`cookingTotalTime>=100` 写入 slot 10） |
| 玩家普通点击 | `doClick → SlotItemHandler.onTake`（继承 vanilla）→ `extractItem` |
| Shift-click | `BlenderMenu.quickMoveStack`：**无 `slot.onTake` 调用**（javap 计数 0，仅 `Slot.set`/`Slot.setChanged`）→ 事件必然丢失 |
| 自动输出/漏斗 | `OutputItemHandler.extractItem` 仅允许 slot 10；玩家取餐走 `SlotItemHandler`（绑定 `getInventory()` 原始 handler），漏斗走 `capabilitieHandler`（`OutputItemHandler`）——同一 slot 两种 handler，**难以区分玩家与自动化** |
| 公开事件/API | 无料理事件 |
| 真实玩家 | 可得 |
| 真实交付栈 | 普通点击可得；shift-click 丢失 |
| recipeId | `getMatchingRecipe` 返回 `RecipeHolder`（含 id），craftItem 时可捕获；**取餐时无持久化** |
| 失败取餐 | 无失败概念 |
| 重复触发 | 普通点击每次 1 次 |
| 最小稳定注入点 | 无（输出槽是 NeoForge 泛类，mixin 会污染所有 `SlotItemHandler`；shift-click 不走 onTake） |

### 3. Bakeries Toaster（烤面包机）

- **无 GUI / 无 Menu**：`ToasterBlockEntity` 仅 `addItem(ItemStack, int)` / `getItem(Player)`，玩家交互走 `ToasterBlock.useItemOn`（空手+辅助键直接领取）
- 无 Slot / onTake / ResultSlot 路径；配方为 `CampfireCookingRecipe`（`CachedCheck<SingleRecipeInput, CampfireCookingRecipe>`）
- **无稳定取餐注入点** → DEFERRED

### 4. Bakeries FermentationBox（发酵箱）

- `FermentationBoxMenu$FermentationBoxSlot extends SlotItemHandler`（仅重写 `getMaxStackSize`，**无 onTake override**）
- 与 Oven 同款：6 槽原位替换成品（`fermentation.setItem(i, resultItem.copy())`），无独立 ResultSlot
- **无法区分取走成品/原料/失败** → DEFERRED

### 5. Bakeries DoughCraftingTable（面团台）

- `DoughCraftingTableMenu`（石匠桌式）：`INPUT_SLOT=0` + `RESULT_SLOT=1`，resultSlot 为**匿名类 `DoughCraftingTableMenu$2`**，override 了 `onTake(Player, ItemStack)`
- **产物是面团**（`DoughCraftingRecipe extends SingleItemRecipe`，输出 `*_dough`；`dish_tiers/items/bakeries/` 中**无 dough 条目** → 非料理）
- 匿名类 mixin 脆弱（`$2` 无稳定类名）；即使实现也无料理事件
- → DEFERRED（无价值）

### 6. 其他设备

- BreadRack / BreadBasket / Cupboard / 等：纯存储容器，无配方产出
- MouldToast / TanPie 等：非玩家取餐设备
- 未纳入注入点审计

---

## 三、实现门槛逐条核对（全部设备）

| 门槛 | Oven | Blender | Toaster | FermentationBox | DoughCraftingTable |
|---|---|---|---|---|---|
| 玩家真实领取成品 | ✗（成品/原料同槽） | 部分（输出槽明确） | ✗（无 GUI） | ✗ | ✗（面团非料理） |
| 覆盖普通点击+Shift-click | ✗（shift 不走 onTake） | ✗（shift 不走 onTake） | ✗ | ✗ | ✗（shift 语义未验证+非料理） |
| 排除失败领取 | ✗ | ✗ | ✗ | ✗ | ✗ |
| 区分玩家与自动化 | 可（漏斗不走 onTake） | ✗（两 handler 同槽） | ✗ | 可（漏斗不走 onTake） | 可 |
| 取得真实料理栈 | 普通可 / shift 否 | 普通可 / shift 否 | ✗ | ✗ | 可（但非料理） |
| 每次只发一次 | ✓（普通） | ✓（普通） | — | ✓（普通） | ✓ |
| 注入点存在于服务器 JAR | 部分 | ✗（NeoForge 泛类） | ✗ | 部分 | 部分（匿名类） |
| requiredMods 条件加载 | ✓（可做） | ✓（可做） | ✓（可做） | ✓（可做） | ✓（可做） |
| 无 Bakeries 环境零污染 | ✓（可做） | ✗（SlotItemHandler 污染） | ✓（可做） | ✓（可做） | ✓（可做） |

**结论：全部不满足 → DEFERRED。**

---

## 四、javap 关键证据摘要

```text
# OvenMenu.quickMoveStack（Shift-click 丢失 onTake）
 149: ItemStack.isEmpty()
 160: Slot.setByPlayer(EMPTY)
 168: Slot.setChanged()
 180: if_icmpne 187          ← 数量相等时不跳转
 192: Slot.onTake(...)        ← 完全移走时此分支不可达

# BlenderMenu.quickMoveStack（无 onTake）
 97: Slot.set(EMPTY)
 105: Slot.setChanged()
 （无 Slot.onTake 调用，grep 计数 0）

# Oven craftItem（原位替换，无独立 ResultSlot）
 itemHandler.setStackInSlot(slot, takeItem);

# Blender 输出槽
 new SlotItemHandler(blockEntity.getInventory(), 10, ...)   ← NeoForge 泛类
```

完整输出：`tmp/bakeries-javap-evidence.txt`

---

## 五、后续可行性（不阻塞本阶段）

- Oven：若后续版本引入独立 ResultSlot / 配方持久化字段，可重新评估（需同时解决 shift-click onTake 丢失与成品/原料区分）
- Blender：若引入 Bakeries 专属 ResultSlot 子类替代 `SlotItemHandler`，或提供取餐事件，可重新评估
- DoughCraftingTable：若面团被认定为料理且注入点稳定化（非匿名类），可重新评估

**DEFERRED。不写 Mixin。等待复审。不 commit/push。**
