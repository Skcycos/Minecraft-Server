# 阶段 8A —— tcth:shadow_thief（影窃者）权威审计与可实施设计定稿

- 阶段：8A（只审计与设计；不实现事件、Mixin、职业数据包、奖励或转移逻辑）
- 目标：为「可实施、可审计、不会复制玩家资产」的影窃者建立技术基线
- 权威：服务器实际 JAR、配置、javap 字节码；本地源码参考仅辅助，版本必须对齐
- 验收结论：**AUDIT PASS / DESIGN PASS / BUILD NOT REQUIRED / SERVER NOT STARTED / PLAYER LIVE NOT TESTED**

---

## 1. 当前 Git / 版本基线

| 项 | 值 | 证据 |
|---|---|---|
| 仓库分支 | `main` | `git branch --show-current` |
| 最近提交 | `2c0ae4f6` merge: 合入农夫四路线与工具耐久修复 | `git log --oneline -5` |
| 工作区 | 有未提交改动与未跟踪文件（`Server/config/*.toml`、`Server/server.properties`、`.gradle-home/`、`Server/*.out/*.pid`、tcth-chef dish_tiers 等）——**8A 未触碰，全部保留** | `git status --short` |
| NeoForge | **21.1.247**（server + universal） | `Server/libraries/net/neoforged/neoforge/21.1.247/` + `unix_args.txt`：`--fml.neoForgeVersion 21.1.247`、`--fml.mcVersion 1.21.1`、`--fml.neoFormVersion 20240808.144430`、`--fml.fmlVersion 4.0.43` |
| Minecraft | 1.21.1 (neoForm 20240808.144430) | 同上 |
| 运行时字节码命名 | **官方（Mojang）名**：`net/minecraft/server/level/ServerPlayer`、`hurt(DamageSource, float)` | javap `server-1.21.1-20240808.144430-srg.jar` |
| TCTH | `mod_version=0.2.7`，产物 `tcth-0.2.7.jar`（SHA-256 见 §2） | `gradle.properties`；`build/libs/` |
| TCTH 测试基线 | **117 suites / 934 tests / 0 failures** | 解析 `build/test-results/test/*.xml` |
| TCTH 编译映射 | Parchment 1.21.1 / 2024.11.17，NeoForge 21.1.247 | `gradle.properties` |
| 服务器世界 | `Server/world/`（`level-name=world`） | `server.properties` |

运行时 MC 类与官方名对照证据：`-srg.jar` 内 `ServerPlayer.class` 方法名为官方名（`public boolean hurt(DamageSource, float)`），证实 NeoForge 1.21.1 运行时类即为官方映射名；TCTH 开发环境（moddev + Parchment）同名编译，下述 javap 签名可直接用于 8B 编码。

---

## 2. 服务器实际相关 JAR 清单、版本、SHA-256

权威 JAR = `Server/mods/`、`Server/libraries/` 下实际文件（文件名即版本来源，另用 javap 复核）。

### 2.1 核心 / 框架

| JAR | 版本 | SHA-256 |
|---|---|---|
| `Server/libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar` | 1.21.1 | `26ca9c40d7e1681190b428583c38816852218e78df3f8bdb60a59a78503aec71` |
| `Server/libraries/net/neoforged/neoforge/21.1.247/neoforge-21.1.247-server.jar` | 21.1.247 | （清单见 unix_args.txt；server jar 含补丁后的 `ServerGamePacketListenerImpl` 等） |
| `Server/libraries/net/neoforged/neoforge/21.1.247/neoforge-21.1.247-universal.jar` | 21.1.247 | `b55d3551e9b0d9b68fa4c47a2122deaf4019b549d8df3c74d9a27b2e43c9718a` |
| `Server/server.jar` | NeoForge 服务器启动器（非完整服务端） | 25,819 B |

### 2.2 直接相关 mod（本阶段审计对象）

| JAR | 版本 | SHA-256 | 用途 |
|---|---|---|---|
| `Server/mods/tcth-0.2.7.jar` | 0.2.7 | `73370897dbd6a54b4ac62d1907b02739896d20e9faba684bc66dd7238cef005e` | TCTH 本体（四职业） |
| `Server/mods/[莱特曼货币]lightmanscurrency-1.21-2.3.0.5.jar` | 2.3.0.5 | `5d610dd9ca42b58039f54c92d3b05cc700e15d40b3d817ecb3ccb661d36dc761` | 货币（COIN） |
| `Server/mods/[职业+]jobsplus-9.0.0-neoforge.jar` | 9.0.0 | `5f06f40317ea727afd79ddd02790d6b840702999115d439fb09e8c6c77e5b5be` | 职业（经验/能力树） |
| `Server/mods/[Arc库]arc-9.0.0-neoforge.jar` | 9.0.0 | `bd165916f75a9c7c5d8ccd9aaf8ca4a151eaffa68280a562da7084428343cd50` | 动作/奖励结算 |
| `Server/mods/[队伍与领地]open-parties-and-claims-neoforge-1.21.1-0.29.3.jar` | 0.29.3 | `a49d18f92dcde9489a1938012bd5a3e4ba13ed7ea4ba1ec25dc0b1a28f886028` | 领地/声明（安全区） |
| `Server/mods/[随身挎包]vercte-satchels-1.2.0.jar` | 1.2.0 | `20f2b8e65b6453357519da7abbb49fbbd8099b47d82a573e5324e0c1777c2685` | 挎包（实体 Attachment 存储内容） |
| `Server/mods/[饰品API]curios-neoforge-9.5.1+1.21.1.jar` | 9.5.1 | `a45df2125c26219974aba7507ffc9afe7b83acc941a386af3faacb1cc0056fde` | 饰品栏（背包之外） |

### 2.3 交互冲突候选（审计「不误拦截」用）

| JAR | 版本 | SHA-256 | 结论 |
|---|---|---|---|
| `Server/mods/[请坐]takeaseat-1.0.1.jar` | 1.0.1 | `f899ee9bc154aee54169178c65d1296740baad5f702a13e92701cf4db1b3179d` | 仅监听 `PlayerLoggedInEvent`/`ServerTickEvent`，**不消费** `EntityInteract` |
| `Server/mods/[传送石碑]waystones-neoforge-1.21.1-21.1.38.jar` | 21.1.38 | `db83e6a22db2ab703f840a4d2cc94143d66a4ff9f955ae4d9b8c2238af731682` | 方块右键（RightClickBlock），不走实体交互路径 |
| `Server/mods/[战斗系统]bettercombat-neoforge-2.4.0+1.21.1.jar` | 2.4.0 | （按需补算） | 仅攻击/挥动 mixin（`PlayerEntityRangeMixin` 等），服务端不消费实体右键 |
| `Server/mods/[生物控制]incontrol-1.21-10.2.6.jar` | 10.2.6 | `f7ef3ac3c1e8faf8cf2764a63858e2658d81c2bb1174e625ce304318ac079add` | 生成/掉落控制；`areas.json` 为空，无禁用区域 |
| `Server/mods/[脚本支持]kubejs-neoforge-2101.7.2-build.368.jar` | 2101.7.2-build.368 | `01767bb677a9c4a8f318717c4c21bca7e7ef80995603403a551068a0e064e740` | 脚本层（未发现影窃干预点） |

### 2.4 服务器关键配置（保护/经济相关）

| 配置 | 值 | 来源 |
|---|---|---|
| `pvp` | `true` | `Server/server.properties` |
| `spawn-protection` | `16` | `Server/server.properties` |
| `gamemode`（默认） | `creative` | `Server/server.properties` |
| `difficulty` | `easy` | `Server/server.properties` |
| ops | Tanrunn × 2，level 4 | `Server/ops.json` |
| OPAC 领地数据 | 存在玩家 claim 文件 | `Server/world/data/openpartiesandclaims/player-claims/` |
| LC 银行数据 | 存在 `lightmanscurrency_bank_data.dat` | `Server/world/data/` |
| LC 银行账户 | `enabled=true`，`interest=0.0` | `Server/config/lightmanscurrency-server.txt` |
| LC 钱币链 | `main`（coin_copper 基准）与 `emeralds` 双链 | `Server/config/lightmanscurrency/MasterCoinList.json` |
| LC 钱包 | 7 种；bankAbility 仅 netherite/nether_star/ender_dragon | `Server/config/lightmanscurrency-server.txt` |

---

## 3. 本地源码参考分支 / tag / commit 匹配情况

| 仓库 | checkout 分支 | HEAD | 与服务器 JAR 匹配度 | 结论 |
|---|---|---|---|---|
| `mod develop/源码参考/ArcLib` | `1.21` | `051b008` (Merge PR #89) | arc 9.0.0 ↔ 分支 `1.21`（`mod_version=9.0.0`） | **匹配**，可辅助 |
| `mod develop/源码参考/JobsPlus` | `1.21` | `0a15e53` (Merge PR #90) | jobsplus 9.0.0 ↔ 分支 `1.21`（`mod_version=9.0.0`） | **匹配**，可辅助 |
| `mod develop/源码参考/LightmansCurrency` | `LC-1.21.1` | `888f8df2e` 「2.3.0.5 update」 | LC 2.3.0.5 ↔ 该 commit | **匹配**，可辅助 |
| Open Parties and Claims | 无本地源码 | — | JAR 0.29.3 | 仅 JAR 审计 |
| Satchels / Curios / takeaseat / waystones / bettercombat / incontrol | 无本地源码 | — | — | 仅 JAR 审计 |

规则遵循：**以服务器 JAR 的 javap 字节码为唯一权威**；源码只用于理解调用意图（如 LC `BankTransfer` 的两步实现），与 JAR 不符的源码结论一律丢弃。不切换源码仓库分支，全程只读。

---

## 4. 玩家交互入口矩阵（NeoForge 21.1.247）

### 4.1 权威调用链（javap 字节码证据）

**服务端**（`neoforge-21.1.247-server.jar`）：

1. 客户端发送 `ServerboundInteractPacket`（`createInteractionPacket(entity, player.isShiftKeyDown(), hand)`，见客户端 `MultiPlayerGameMode.interact`）。
2. `net.minecraft.server.network.ServerGamePacketListenerImpl.handleInteract(ServerboundInteractPacket)`：
   - `serverLevel` 解析目标实体（`ServerboundInteractPacket.getTarget`）；
   - `player.resetLastActionTime()`；
   - **`player.setShiftKeyDown(packet.isUsingSecondaryAction())`** —— 潜行状态由包携带，服务端以此为准；
   - 距离校验 `player.canInteractWithEntity(aabb, entityInteractionRange)`；
   - `packet.dispatch(handler)`。
3. `ServerGamePacketListenerImpl$1`（`ServerboundInteractPacket.Handler` 实现）：
   - `onInteraction(hand)`（无坐标版）→ `performInteraction` → lambda 直接 **`Player.interactOn(Entity, InteractionHand)`**（BootstrapMethods 证据）；
   - `onInteraction(hand, vec3)`（带坐标版）→ `CommonHooks.onInteractEntityAt(player, entity, vec3, hand)`（= `EntityInteractSpecific` 事件）→ `entity.interactAt(player, vec3, hand)`。
4. **`Player.interactOn(entity, hand)`（NeoForge 补丁版，`Player.class` 字节码）**：
   - spectator 分支；
   - **`CommonHooks.onInteractEntity(player, entity, hand)`** → 发布 `PlayerInteractEvent$EntityInteract`；返回值非空则立即返回（**取消原版交互**）；
   - 否则 `entity.interact(player, hand)`；
   - 若未消费且主手有物品且目标是 `LivingEntity` → `stack.interactLivingEntity(player, entity, hand)`。

**客户端**（`neoforge-21.1.247.jar` 合并 jar 的 `MultiPlayerGameMode`）：

- `interact(player, entity, hand)`：`ensureHasSentCarriedItem()` → 发包 → `player.interactOn(entity, hand)`（本地预测，同样触发事件，仅影响预测）。
- `interactAt(...)`：发包 → **`CommonHooks.onInteractEntityAt(player, entity, hitResult, hand)`**（客户端也触发 `EntityInteractSpecific`）→ `entity.interactAt`。
- 手的选择：`Minecraft.startUseItem` → `player.getUsedItemHand()`（`LivingEntity.getUsedItemHand`：仅当 `DATA_LIVING_ENTITY_FLAGS` bit2 置位——正在使用副手物品——才返回 `OFF_HAND`，否则 `MAIN_HAND`）。

**结论：`PlayerInteractEvent$EntityInteract` 双端都会触发**（客户端预测 + 服务端权威），服务端判定必须过滤 `event.getSide()` / `player.level().isClientSide()`。

### 4.2 事件签名（javap 原文）

```
net.neoforged.neoforge.event.entity.player.PlayerInteractEvent$EntityInteract
  implements net.neoforged.bus.api.ICancellableEvent
  ctor(Player, InteractionHand, Entity)
  Entity getTarget()
  InteractionResult getCancellationResult()
  void setCancellationResult(InteractionResult)
  // 继承: Player getEntity(); InteractionHand getHand(); ItemStack getItemStack();
  //       Level getLevel(); LogicalSide getSide(); boolean isCanceled(); setCanceled(boolean)
```

### 4.3 设计规则（对应任务要求）

| 要求 | 设计 | 依据 |
|---|---|---|
| 只响应服务端真实 ServerPlayer | `getSide()==LogicalSide.SERVER && getEntity() instanceof ServerPlayer && !(instanceof FakePlayer)` | §4.4 |
| 仅 MAIN_HAND | `getHand()==InteractionHand.MAIN_HAND`；包内 hand 由客户端 `getUsedItemHand()` 决定 | §4.1 |
| 仅潜行 | `player.isDiscrete()`/`isShiftKeyDown()`（服务端已在 `handleInteract` 用包内标志设置） | §4.1 |
| 仅空手 | `player.getMainHandItem().isEmpty()`；副手有物时客户端可能用副手交互 → hand≠MAIN_HAND 自然排除 | §4.1 |
| 每次物理交互最多一个操作 | 事件是包级单发；影窃命中即 `setCanceled(true)+setCancellationResult(SUCCESS)`；未命中不取消 | §4.1/§4.5 |
| 不误拦截正常交互 | 仅在「潜行+空手+MAIN_HAND+目标是可窃目标」时取消；其余放行 | §4.5 |
| FakePlayer/机械排除 | 主手与受害者都 `!(instanceof FakePlayer)` | §4.4 |
| 距离 | 服务端 `canInteractWithEntity` 已在 `handleInteract` 强制（`entityInteractionRange()` 默认 3.0）；影窃处理器再自查一次做纵深防御 | §4.1 |

### 4.4 FakePlayer（javap）

```
net.neoforged.neoforge.common.util.FakePlayer extends net.minecraft.server.level.ServerPlayer
net.neoforged.neoforge.common.util.FakePlayerFactory
  public static FakePlayer getMinecraft(ServerLevel)
  public static FakePlayer get(ServerLevel, GameProfile)
```
判定：`instanceof net.neoforged.neoforge.common.util.FakePlayer`（机械/模拟玩家唯一基类）。

### 4.5 与既有交互的冲突矩阵

| 原版/模组交互 | 路径 | 影窃是否干预 |
|---|---|---|
| 村民交易 | `Entity.interact`（MenuProvider） | 不干预：非潜行/非空手/非目标放行；村民默认不在可窃目标内 |
| 乘骑（马/船/猪） | `Entity.interact` | 放行 |
| 拴绳/喂食/驯服 | `stack.interactLivingEntity` | 主手有物即不满足空手条件，天然放行 |
| 打开 GUI（箱子/方块） | `RightClickBlock` | 不涉及 EntityInteract |
| 传送石碑 / 坐下 | `RightClickBlock` / 无关事件 | 无影响 |
| 领地实体交互拦截 | OPAC `CommonEventsNeoForge.onEntityInteract` 消费同一事件并 `setCanceled(true)` | **依赖顺序不确定**：TCTH 处理器必须内置领地检查（§6），不得假设 OPAC 先后 |
| 潜行右键玩家（原版） | `Entity.interact` 默认 PASS，无效果 | 影窃在此路径接管 |

---

## 5. ITEM / COIN / HEALTH / HUNGER / EFFECT API 矩阵

### 5.1 ITEM —— 物品真实转移

**Inventory（javap，`-srg.jar`）：**
```
public class net.minecraft.world.entity.player.Inventory implements Container {
  public static final int INVENTORY_SIZE = 36;   // 0..35 主背包（hotbar 0..8）
  public static final int SLOT_OFFHAND = 40;
  public static final int[] ALL_ARMOR_SLOTS;     // 36..39
  public final NonNullList<ItemStack> items;     // 0..35
  public final NonNullList<ItemStack> armor;     // 36..39
  public final NonNullList<ItemStack> offhand;   // 40
  public boolean add(ItemStack);                 // true=全部放入
  public ItemStack removeItem(int slot, int amount);   // 返回移除栈，扣减 count
  public void removeItem(ItemStack);
  public void setItem(int, ItemStack);
  public ItemStack getItem(int);
  public int getFreeSlot();
  public int getSlotWithRemainingSpace(ItemStack);
  public void placeItemBackInInventory(ItemStack);
  public int getContainerSize();                 // 41
}
```

**`add` 语义（字节码）：** 依次尝试 ①同 item+同 components（`isSameItemSameComponents`）且可堆叠且 count<max 的槽位合并；②空槽（热键优先）；输入栈被消费到实际放入的量，返回是否全部放入。**无吞物**。

**ItemStack（javap）：**
```
public final class net.minecraft.world.item.ItemStack implements DataComponentHolder {
  public boolean isEmpty();
  public int getCount(); public void setCount(int); public void grow(int); public void shrink(int);
  public ItemStack copy(); public ItemStack copyWithCount(int); public ItemStack split(int);
  public boolean is(TagKey<Item>);
  public DataComponentMap getComponents(); public DataComponentPatch getComponentsPatch();
  public <T> T set(DataComponentType<? super T>, T); public <T> T remove(DataComponentType<? extends T>);
  public int getMaxStackSize();
}
```

**组件键（javap）：**
```
net.minecraft.core.component.DataComponents:
  public static final DataComponentType<CustomData> CUSTOM_DATA;             // 绑定/任务模组常用
  public static final DataComponentType<CustomData> BLOCK_ENTITY_DATA;
  public static final DataComponentType<ItemContainerContents> CONTAINER;    // 潜影盒等容器内容
  public static final DataComponentType<SeededContainerLoot> CONTAINER_LOOT;
  public static final DataComponentType<ItemLore> LORE;
  public static final DataComponentType<ItemEnchantments> ENCHANTMENTS;
```

**边界与硬拒规则（设计）：**
- 窃取范围：**受害者主背包槽 0..35**（`Inventory.items`）；装备栏 36..39 与副手 40 不参与（「穿着物不可窃」）。
- 潜影盒/任何带 `CONTAINER` 组件的物品：`stack.has(DataComponents.CONTAINER)` **代码层硬拒**（不依赖名称字符串）。
- 带 `CONTAINER_LOOT`（未填充的容器）同样硬拒。
- Vercte Satchel：`SatchelItem`（`net.vercte.satchels.content.satchel.SatchelItem`），内容存于**实体 Attachment**（`ModAttachmentTypes.SATCHEL_SLOT` → `ItemStackHandler`），窃走物品会留下内容孤儿 → **按类硬拒 + `tcth:unstealable_items` 标签双保险**。
- Curios 饰品栏：独立于 vanilla Inventory，`Inventory.add` 不可达 → 天然不是来源与接收目标（文档化，无需特判）。
- LC 钱币物品：`CoinAPI.getApi().IsCoin(ItemStack, boolean)`（javap 确认）→ ITEM 候选剔除钱币，避免与 COIN 型双重取钱。
- **标签设计（8B 新增，本阶段不创建）**：
  - `tcth:unstealable_items`（item tag）：服务器管理员数据驱动黑名单（绑定/任务/稀有物品）；**必须同时有代码层组件硬拒**，标签只作补充，不得唯一依赖。
  - `tcth:high_value_stealable_items`（item tag）：高价值物品，用于成功率修正层（§10）；默认空。
  - `tcth:unstealable_item_components`（component tag）：**结论：不新增（DEFERRED）**。NeoForge 组件标签需 mod 侧注册组件类型；代码判定 `CONTAINER`/`CONTAINER_LOOT`/`CUSTOM_DATA` 已覆盖可枚举风险面，组件标签收益低于成本。
- **只转移 1 个**：候选快照为 `(slot, ItemStack copy)`，实际执行 `removeItem(slot, 1)` 取出 1 个，`copyWithCount(1)` 转移。
- **先确认可接收再从受害者扣除（防凭空/防吞）**：
  1. 快照候选（槽位+栈）→ 执行前重验受害者槽位同栈同 count；
  2. 在影窃者背包找接收点：`getSlotWithRemainingSpace(stack)`（同 item+components 且未满），否则 `getFreeSlot()`；都没有 → **移除 ITEM 候选**（状态机步骤 4 候选净化），不扣受害方；
  3. 执行：`victim.removeItem(slot,1)` → `thief.items.set(目标槽, 合并)` 或 `thief.add(stack)`；
  4. 异常回滚：`try/catch`，若已扣未得 → `victim.placeItemBackInInventory(stack)`。
- **快照时机**：事件处理器内、服务端主线程上、成功判定**之前**生成；服务端单线程无并发，快照与执行之间槽位不会被其他代码改动。
- **创造/死亡/退出**：`player.isCreative()`/`isSpectator()`；目标 `isDeadOrDying()`/`isRemoved()`/`hasDisconnected()` → 拒绝 ITEM/COIN。

### 5.2 COIN —— 货币（Lightman's Currency 2.3.0.5）

**审计结论：PARTIAL（可查/可扣/可加，但无单次原子转账 API）—— 8C 的 COIN 默认保持关闭（§13）。**

**权威 API（javap，`lightmanscurrency-1.21-2.3.0.5.jar`）：**
```
// 查询玩家总货币（钱包+银行，在线玩家）
io.github.lightman314.lightmanscurrency.api.money.MoneyAPI
  public static MoneyAPI getApi()
  public abstract IMoneyHolder GetPlayersMoneyHandler(Player)          // safe（缓存，随 player 更新）
  public abstract IMoneyHolder GetPlayersMoneyHandlerUnsafe(Player)    // unsafe 变体

io.github.lightman314.lightmanscurrency.api.money.value.holder.IMoneyViewer
  public abstract MoneyView getStoredMoney();

io.github.lightman314.lightmanscurrency.api.capability.money.IMoneyHandler
  public abstract MoneyValue insertMoney(MoneyValue, boolean simulate);
  public abstract MoneyValue extractMoney(MoneyValue, boolean simulate);
  public abstract boolean isMoneyTypeValid(MoneyValue);
```
```
// 银行账户（离线可用，数据在 SavedData）
io.github.lightman314.lightmanscurrency.api.money.bank.BankAPI
  public static BankAPI getApi()
  public abstract boolean BankDepositFromServer(IBankAccount, MoneyValue, boolean notifyPlayers);
  public abstract Pair<Boolean,MoneyValue> BankWithdrawFromServer(IBankAccount, MoneyValue, boolean notifyPlayers);
  public abstract MutableComponent BankTransfer(Player, BankReference from, MoneyValue, IBankAccount to); // 两步实现！

io.github.lightman314.lightmanscurrency.api.money.bank.reference.builtin.PlayerBankReference
  public static BankReference of(UUID); public static BankReference of(PlayerReference); public static BankReference of(Player);
  public IBankAccount get();

io.github.lightman314.lightmanscurrency.api.money.bank.IBankAccount
  public abstract MoneyStorage getMoneyStorage();
  public abstract void depositMoney(MoneyValue);
  public abstract MoneyValue withdrawMoney(MoneyValue);   // capValue 封顶，余额不足返回 empty
  public abstract void markDirty();

io.github.lightman314.lightmanscurrency.api.money.value.MoneyStorage
  public MoneyValue valueOf(String type); public boolean containsValue(MoneyValue);
  public MoneyValue capValue(MoneyValue); public boolean isEmpty();
  public void addValue(MoneyValue); public void removeValue(MoneyValue);
```
```
// 金额构造与比例
io.github.lightman314.lightmanscurrency.api.money.value.MoneyValue
  public long getCoreValue();
  public MoneyValue multiplyValue(double);
  public MoneyValue percentageOfValue(int, boolean);
  public MoneyValue addValue(MoneyValue); public MoneyValue subtractValue(MoneyValue);

io.github.lightman314.lightmanscurrency.api.money.value.builtin.CoinValue
  public static MoneyValue fromNumber(String chain, long core);

io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI
  public static CoinAPI getApi();
  public abstract boolean IsCoin(ItemStack, boolean);
```

**失败语义/线程/日志：**
- `withdrawMoney`：`coinStorage.capValue(amount)` 封顶实际余额，空余额返回 `MoneyValue.empty()`；**不抛异常、不为负**。
- `depositMoney`：纯加法，无失败路径（银行账户无容量上限）。
- 线程：银行数据在 `BankDataCache extends CustomData`（服务器 SavedData，`lightmanscurrency_bank_data.dat` 已存在），**必须在服务端主线程**读写；事件处理器天然满足。
- 离线 vs 在线：银行账户是服务端 SavedData → **离线可读可扣可加**（`PlayerBankReference.of(uuid).get()`）；钱包/钱币在 `WalletHandler`（玩家实体 Attachment）→ 仅在线。**设计：COIN 只走银行账户**。
- 交易日志：LC 有 `NotificationData`（银行账户日志）与 `BankTransferNotification`；`BankDepositFromServer/Withdraw` 的 `notifyPlayers` 参数可控制通知。

**原子性判定（证据链）：** `BankAPI.BankTransfer`（javap + LC-1.21.1 源码 `BankAPIImpl.java:165`）实现为 `fromAccount.withdrawMoney(amount)` → `destination.depositMoney(withdrawnAmount)` → 记录日志，**两步、无单点原子封装**；中途异常无自动回滚。结论：**不存在保证原子转账的公共 API** → PARTIAL。

**可在 8C 复审时采用的补偿模式（仅记录，不实现）：**
1. 服务端主线程；
2. `victimAccount.getMoneyStorage()` 取各币种余额 → `multiplyValue(pct)` 计算偷窃额（1%～3%）；
3. `withdrawMoney` 预检（余额不足 → 失败退出，零改动）；
4. `withdrawMoney(amount)` → `thiefAccount.depositMoney(withdrawn)`；
5. `try/finally`：deposit 抛异常 → `victimAccount.depositMoney(withdrawn)` 补偿返还；
6. 写审计记录（§11）。
> 该模式未经实机验证，须在 8C 复审确认后才可开启 COIN。

**COIN 数据结构（本阶段只列字段，不落盘）：**
- 日额度/单次上限：`SavedData` `ShadowThiefLimitsData`：`Map<UUID victim, Map<Long day, long stolenCoreValue>>`、`Map<UUID victim, Map<Long day, Integer attempts>>`；
- 日期边界：**不采用现实时区**，采用主世界日序号 `overworld.getDayTime()/24000`（服务端确定性、无时区问题）；
- 单次上限与 1%～3% 数值：本阶段不写死，留给 8C 平衡配置。

### 5.3 HEALTH —— 生命转移

**API（javap）：**
```
net.minecraft.world.entity.LivingEntity
  public float getHealth(); public void setHealth(float);
  public void heal(float);                       // 不能超过 maxHealth（属性）
  public float getMaxHealth();                   // 由 Attributes.MAX_HEALTH 派生
  public float getAbsorptionAmount(); public void setAbsorptionAmount(float);
  public boolean isDeadOrDying();
  public boolean hurt(DamageSource, float);      // 含护甲/无敌帧/伤害统计/事件全链路
  public AttributeInstance getAttribute(Holder<Attribute>);  // Attributes.MAX_HEALTH
```

**设计结论：**
- **推荐 `setHealth` 直接扣减**（生命转移是「抽走」，不是「造成伤害」）：
  - `setHealth` 不触发护甲吸收、无敌帧（`invulnerableTime`）、伤害统计（`DamageSource` 相关 stat/advancement）、`LivingDamageEvent`/死亡音效；
  - `heal` 会封顶到 maxHealth 且触发事件；`setHealth` 无上限保护，必须手动 `Math.max(2.0f, ...)`。
- 若走 `hurt(DamageSource, amount)`：会走护甲/附魔/无敌帧/其他 mod 伤害监听（bettercombat、SC 等），**不可预测、可能致死、产生死亡统计** → 否定。
- 最低保留 2 点生命：目标 `newHealth = max(2f, health - stealAmount)`；实际扣除 `= health - newHealth`（0 则 HEALTH 候选移除）。
- 治疗只回实际扣除量：影窃者 `heal(stealAmount)`（`heal` 自带 maxHealth 封顶，**不会过量**）。
- 边界：`isDeadOrDying` 拒绝；`Abilities.invulnerable`（旁观/创造）、`isInvulnerable()` → 拒绝。
- **吸收值（absorption）不参与转移**：absorption 是临时护甲，转移仅基于 `getHealth()`；文档化限制。

### 5.4 HUNGER —— 饥饿与饱和度

**API（javap）：**
```
net.minecraft.world.entity.player.Player
  public FoodData getFoodData();
  public boolean canEat(boolean alwaysCanEat);
net.minecraft.world.food.FoodData
  public int getFoodLevel(); public void setFoodLevel(int);
  public float getSaturationLevel(); public void setSaturation(float);
  public float getExhaustionLevel(); public void setExhaustion(float);
  public boolean needsFood();
```

**设计结论：**
- 转移语义：先扣**饱和度**（按比例），再扣**饥饿值**；影窃者按同一比例补回（`setFoodLevel`/`setSaturation`），**实际扣多少补多少**。
- 比例换算（草案，8C 定数值）：`stealFood = max(1, round(hunger * pct))`，`stealSaturation = saturation * (stealFood/hunger)` 同比例。
- 保护线：目标饥饿最低保留（草案 4 点，本阶段不定死）；影窃者接收空间 = `20 - getFoodLevel()`（饥饿封顶 20）、`20 - getSaturationLevel()`（饱和度上限 20）封顶，**无溢出**。
- 边界：`isDeadOrDying`/旁观/创造 → 拒绝；目标 `needsFood()` 仅为提示，不作为判定依据。

### 5.5 EFFECT —— 正面效果转移

**API（javap）：**
```
net.minecraft.world.effect.MobEffectInstance
  public static final int INFINITE_DURATION;         // -1
  public boolean isInfiniteDuration(); public boolean endsWithin(int);
  public Holder<MobEffect> getEffect(); public int getDuration(); public int getAmplifier();
  public boolean isAmbient(); public boolean isVisible(); public boolean showIcon();
  public boolean update(MobEffectInstance);          // 合并语义：更强/更长才生效
net.minecraft.world.effect.MobEffect
  public boolean isBeneficial();                     // category==BENEFICIAL
  public boolean isInstantenous();
  public MobEffectCategory getCategory();            // BENEFICIAL/HARMFUL/NEUTRAL
net.minecraft.world.entity.LivingEntity
  public Collection<MobEffectInstance> getActiveEffects();
  public MobEffectInstance getEffect(Holder<MobEffect>);
  public boolean hasEffect(Holder<MobEffect>);
  public final boolean addEffect(MobEffectInstance);
  public boolean addEffect(MobEffectInstance, Entity source);
  public void forceAddEffect(MobEffectInstance, Entity source);
  public MobEffectInstance removeEffectNoUpdate(Holder<MobEffect>);
  public boolean removeEffect(Holder<MobEffect>);
```

**分类：** `MobEffect.isBeneficial()`（category==BENEFICIAL）为正面效果候选；HARMFUL/NEUTRAL 排除。

**设计结论：**
- **标签设计（8B 新增，本阶段不创建）**：
  - `tcth:stealable_effects`（effect tag）：白名单；**默认只有标签内效果可窃**（fail-closed）。
  - `tcth:unstealable_effects`（effect tag）：黑名单覆盖层；两表冲突时黑名单优先。
- **排除规则**：`isInfiniteDuration()` 排除；`isAmbient()`（信标/潮涌）**排除**——但必须如实记录限制：**MobEffectInstance 不含来源字段**，`isAmbient` 是唯一启发式，无法可靠区分「信标刚刷新的 5 秒 ambient」与「非 ambient 的真转移」，因此对 ambient 一律排除是最稳妥策略，代价是信标流也完全不可窃（记录：这是有意的 fail-closed 取舍，不声称已精确排除信标）。
- **关键模组状态**：影响数值的状态（如其他模组的属性效果）本质仍是 MobEffectInstance；除非列入 `unstealable_effects`，否则默认允许——由 `stealable_effects` 白名单做最终闸门。
- **转移量**：最多 10/20/30 秒（能力树分档，§12）；实际取 `min(上限, 目标剩余)`；**从目标真实剩余时间扣除**：`removeEffect` 后 `forceAddEffect(新实例, thief)`（同 amplifier、duration-转移量）；不提高 amplifier。
- **合并规则**：影窃者已有同效果 → 预检：若已有 `amplifier > 转移值` 或 `duration >= 转移后时长`（`update()` 语义：更强/更长才生效，否则 addEffect 返回 false 无效果）→ **从候选池移除 EFFECT**（步骤 4 净化），避免「偷了但没生效」。
- **无法保证等量转移**（如目标剩余时间少于最小可转移量 1 秒）：从候选池移除，不做事务失败。
- **信标限制如实记录**：无法区分信标来源，仅能以 `isAmbient` 启发式排除；不声称已完全排除信标。

---

## 6. 安全区与玩家保护矩阵

### 6.1 实际存在的保护机制（JAR + 配置证据）

| 保护 | 存在性 | 证据 |
|---|---|---|
| 领地/声明 | **有**：Open Parties and Claims 0.29.3，服务端已启用（玩家 claim 数据存在） | `Server/world/data/openpartiesandclaims/player-claims/*.nbt` |
| 出生点保护 | 有：`spawn-protection=16`（原版，仅保护 16 格内方块破坏，非实体交互） | `Server/server.properties` |
| 主城/商店区域 | **无独立配置**；未见相关数据包/配置（incontrol `areas.json` 空） | `Server/config/incontrol/areas.json` |
| 新玩家保护 | **无现成机制**（无模组提供，未见配置） | 全目录扫描 |
| PvP/交互保护 | `pvp=true`（原版 PvP 开）；OPAC 对声明区块做实体交互拦截 | `server.properties`；OPAC `CommonEventsNeoForge.onEntityInteract` |
| 黑名单维度/区域 | 无（incontrol areas 空） | 同上 |

### 6.2 OPAC 权威 API（javap，`open-parties-and-claims-neoforge-1.21.1-0.29.3.jar`）

```
// 入口
xaero.pac.common.server.api.OpenPACServerAPI
  public static OpenPACServerAPI get(MinecraftServer)
  public IChunkProtectionAPI getChunkProtection();
  public IServerClaimsManagerAPI getServerClaimsManager();

// 领地查询（只读，fail-closed 依据）
xaero.pac.common.server.claims.api.IServerClaimsManagerAPI extends IClaimsManagerAPI
  public IPlayerChunkClaimAPI get(ResourceLocation dim, BlockPos);   // null=未声明
  public IPlayerChunkClaimAPI get(ResourceLocation dim, ChunkPos);
  public IPlayerClaimInfoAPI getPlayerInfo(UUID);

xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI
  public UUID getPlayerId();
  public boolean isForceloadable();
xaero.pac.common.claims.player.api.IPlayerClaimInfoAPI
  public boolean isPartyOwned();
  public UUID getPlayerId();

// 交互拦截查询（OPAC 自身在 EntityInteract 事件中调用的同款判定）
xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI
  public boolean onEntityInteraction(Entity interactor, Entity interactor2, Entity target,
                                     ItemStack, InteractionHand, boolean, boolean, boolean); // true=阻止
  public IPlayerConfigAPI getConfig(IPlayerChunkClaimAPI);
  public boolean hasChunkAccess(IPlayerConfigAPI, Entity);
```

OPAC 自身调用证据（`CommonEvents.onEntityInteract` 字节码）：`chunkProtection.onEntityInteraction(serverData, interactor, interactor, target, null, hand, false, false, true)`，返回 true 时 `CommonEventsNeoForge.onEntityInteract` 对事件 `setCanceled(true)`。

**结论：存在稳定公共 API 可询问「影窃是否允许」，标记 SUPPORTED（只读查询层）。**

### 6.3 fail-closed 设计

| 场景 | 规则 |
|---|---|
| 无法判断区域权限 | **拒绝影窃**（`getClaimsManager().get(dim,pos)` 返回 null 且无法判定安全 → 策略为「无 claim 即放行」或「无 claim 即拒绝」二选一；**默认：无 claim（野外）放行，claim 内按所有权规则**；管理员可在配置切换为全拒绝） |
| 目标或影窃者处于保护状态 | 被 OPAC 阻止的交互：TCTH 不发布事件、不转移、不打日志（零副作用）；提示走 OPAC 自带消息 |
| claim 所有权规则 | claim 主 = 目标本人或同 party：**放行**（玩家自己的领地内偷自己的不算「领地保护」）；claim 主 = 第三方：**拒绝**（无论第三方是否在线） |
| 管理员/创造/旁观 | 创造/旁观玩家作为目标 → 拒绝（§5.1）；作为影窃者 → 允许（无资产风险）但**仍受领地/冷却约束**；管理员 `bypassesPlayerLimit` 不影响影窃规则 |
| 新玩家保护 | **无现成来源** → 需要 TCTH 自有配置（保护天数/在线时长阈值），读取自己的配置不读第三方。标记 DEFERRED（8B 仅预留字段，8C 配数值） |
| 出生点保护 | 不硬编码主城坐标；`spawn-protection=16` 不扩展至实体交互；如需主城保护 → **要求服主用 OPAC 声明主城区块**（走 6.2 已有 API），TCTH 不做坐标特判 |
| TCTH 自有回调 | 设计：`ShadowThiefSafety` 抽象（`isTheftAllowed(thief, target, pos)`）默认走 OPAC 查询，供服主注入自定义实现；**本阶段只列接口草案，不实现** |

---

## 7. Jobs+ / Arc 接入矩阵（JAR 9.0.0 权威）

### 7.1 既有模式（TCTH 四职业已验证，8B 直接复用）

- 职业 JSON：`data/tcth/jobsplus/jobs/<job>.json`（`price`、`max_level`、`icon` 等）；现有 `gunner.json` 即模板。
- Powerup JSON：`data/tcth/jobsplus/powerups/<job>/<name>.json`（`job`、`icon`、`price`、`required_level`）。
- Arc 动作 JSON：`data/tcth/arc/<job>/<event>.json`：`holder.type = jobsplus:job`、`type = tcth:on_xxx`（自定义 ActionType）、`rewards = [{type: jobsplus:job_exp, min, max}]`、`conditions`（含 `tcth:xxx_enabled` 总开关、`jobsplus:powerup_not_active` 互斥——见 `study_i.json` 的 study_ii/iii 互斥条件）。
- 能力读取（`BrewerAbilityModule.java` 等四份同构实现）：`ServerPlayer instanceof JobsServerPlayer` → `jobsplus$getJob(JobInstance.of(ID))` → `job.getPowerupManager()` → `getPowerup(instance)` → `PowerupState.ACTIVE`。
- Arc 触发（`GunKillActionDispatcher.java`）：`(ArcServerPlayer) player` → `new ActionDataBuilder(player, ActionType).withData(...).build().sendToAction()`。

### 7.2 JAR javap 佐证

```
// jobsplus-9.0.0-neoforge.jar
com.daqem.jobsplus.player.job.powerup.PowerupState          // ACTIVE / INACTIVE / NOT_OWNED / LOCKED
com.daqem.jobsplus.player.job.powerup.JobPowerupManager
  public Optional<Powerup> getPowerup(PowerupInstance);
com.daqem.jobsplus.player.job.Job
  public JobPowerupManager getPowerupManager();
  public void addExperience(double); public void setExperience(double, boolean);
com.daqem.jobsplus.player.JobsPlayer                        // 接口，ServerPlayer 实现（JobsServerPlayer）
  public Job jobsplus$getJob(JobInstance);

// arc-9.0.0-neoforge.jar
com.daqem.arc.api.action.type.ActionType
  public static <T> ActionType<T> register(ResourceLocation, ISerializer<T>);   // TCTH 现有代码在用
com.daqem.arc.api.action.data.ActionDataBuilder
  ctor(ArcPlayer, ActionType<?>); <T> ActionDataBuilder withData(IActionDataType<T>, T); ActionData build();
com.daqem.arc.api.action.data.ActionData
  public ActionResult sendToAction();
com.daqem.arc.api.player.ArcServerPlayer                    // ServerPlayer 实现
```

### 7.3 接入设计（顺序即财产安全关键）

1. 真实转移完成（§9 状态机步骤 10-11，含审计落盘）；
2. **之后**才发布成功事件（`tcth:on_shadow_theft` ActionData，含 theftType/result/quality 数据）；
3. Arc 结算职业经验（`jobsplus:job_exp` reward）；
4. **任何转移失败/无候选/重复事件 → 不发布成功事件，0 经验**（状态机在步骤 5/11/13 短路，天然 0 经验）。

失败语义：`sendToAction()` 异常 → 记录日志，不影响已完成的财产转移（单向顺序保证「先财产后经验」，绝无「先经验后财产」）。

四路线最高档互斥：沿用 `jobsplus:powerup_not_active` 数据驱动互斥（最高档 active 时低档条件不满足），**不叠加触发**。

---

## 8. 生物一次性搜刮持久化方案

### 8.1 候选存储（评估表）

| 方案 | 生命周期 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| NeoForge Entity Attachment（`AttachmentType<T>`） | 随实体 NBT 保存/加载；区块卸载重载保留；实体死亡/移除即消失 | 原子随实体保存；无全局清理；官方推荐 | 死亡即失（一次性语义对「活着的实体」成立） | **首选**（对「每实体一次」的存续期恰好符合：实体还在就有记录） |
| `Entity.getPersistentData()` | 同上 | 简单 NBT | 同上 + 字符串键无类型安全 | 备选（弃） |
| SavedData（UUID 键） | 跨实体死亡保留 | 可追踪「曾被搜刮后死亡重生」 | 需清理孤儿记录；防刷依赖 UUID 唯一性 | 仅用于 boss/elite 防刷（配合 §8.2） |
| 实体 UUID 本身 | — | — | 重生实体 UUID 会变 | 不可单独用 |

**JAR 证据：**
```
net.minecraft.world.entity.Entity extends net.neoforged.neoforge.attachment.AttachmentHolder
  public CompoundTag getPersistentData();
  public final <T> T setData(AttachmentType<T>, T);
  // AttachmentHolder: getData/setData/hasData/removeData/serializeAttachments(registryAccess)
```
实体死亡：`dropAllDeathLoot`/`remove(RemovalReason.KILLED)` 后实体数据不再保存——附件随实体销毁。

### 8.2 数据文件设计（只设计，不创建）

`data/tcth/shadow_loot/<namespace>/<entity>.json` —— 实体搜刮表（**不是死亡掉落表**，互不替代）：

```jsonc
{
  "entries": [                       // 至少 1 条
    { "type": "minecraft:item", "id": "minecraft:diamond", "weight": 10, "min": 1, "max": 3 },
    { "type": "minecraft:effect", "id": "minecraft:regeneration", "weight": 5, "seconds": 15 }
  ],
  "required_tags": ["tcth:shadow_loot/tier_common"],   // 实体须具备全部标签
  "forbidden_tags": ["tcth:shadow_loot/excluded"],
  "elite": false, "boss": false,     // 标记（供一次性/冷却/防刷策略）
  "cooldown_seconds": 600,           // 0=仅一次
  "once": true
}
```

语义规则：
- `once:true`（或 `boss:true`）→ 实体 Attachment `shadow_loot_used` 标记；再次交互 → 无候选、0 经验、不重复搜刮。
- `cooldown_seconds > 0` → Attachment 记录时间戳，冷却内不可再搜刮。
- 数据错误隔离：单条 entry 解析失败 → 跳过该 entry（记 warn），整个文件失败 → 该实体无候选（fail-closed），**不崩溃、不影响其他实体**；与 `GunnerStatsData` 的防御式加载（`GunnerStatsData.load` 未知字段回退默认）同思想。
- **禁止直接复制实体死亡掉落表**：entries 独立设计，与 `minecraft:loot_table` 无继承关系。
- 实体类型解析用 `ResourceLocation` + 标签判定（`EntityType.is` 或 level tag），全部运行时验证。

---

## 9. 原子事务状态机（必须收口）

```
S0  事件入口：PlayerInteractEvent$EntityInteract
    1) getSide()==SERVER；目标 != 自己；否则 return（放行）
    2) 非 FakePlayer x2；MAIN_HAND；isDiscrete()（潜行）；主手空
    3) 幂等 key 命中 -> return（重复调用返回「已处理，无操作」）
    失败 -> return（不取消事件，原版交互继续）
S1  上下文验证：目标存活、非旁观/创造（按类型）、距离重验
    领地检查（§6.3 fail-closed）-> 失败 return（零副作用）
    冷却检查（影窃者/目标/区域）-> 失败 return（短冷却提示）
S2  生成候选类型（5 型全在池：ITEM/COIN/HEALTH/HUNGER/EFFECT）
S3  移除不可用类型（净化，只读不扣任何东西）：
    ITEM   -> 目标背包无候选槽 / 无可窃物品 / 影窃者无处接收
    COIN   -> 8C 默认关闭；或银行余额为 0 / 目标无账户
    HEALTH -> 扣除量=0（目标已低于 2+最小步长）
    HUNGER -> 折算后=0 或目标已低于保护线
    EFFECT -> 目标无可窃正面效果 / 剩余不足 / 影窃者已有更强（§5.5）
S4  候选为空 -> 短冷却、0 经验、无转移、暴露身份（对目标播报），结束
S5  类型权重随机（一次）：30/20/20/15/15 归一，抽出一个类型（随机调用 #1）
S6  计算成功率（§10 公式，含高价值物品修正、能力树修正）clamp [5%,85%]
S7  成功判定（一次）：random < successRate（随机调用 #2，与 #1 用途不同）
    失败 -> 0 转移、0 经验、暴露身份、冷却，结束
S8  执行对应原子事务（§5.1-5.5，均为「预检 -> 执行 -> 补偿」三段）
    事务期间目标状态变化（死亡/下线/换维/槽位变化）-> 立即中止并回滚
    任一类型事务失败 -> 走失败出口（不重抽类型！不转移一半！）
    成功后：写审计记录（§11）
S9  发布统一事件 tcth:on_shadow_theft（成功才发，经 Arc §7.3）
S10 提交冷却、每日额度、幂等记录（SavedData + 实体 Attachment）
S11 结束
```

硬性约束：
- **类型随机（S5）与成功判定（S7）是两次不同用途的随机调用**：S5 抽类型后 S7 只判定「该类型是否成功」，绝不允许失败后换类型重抽。
- 不允许「转移一半后继续结算」：每型事务要么全部完成，要么全部回滚。
- **eventId**：S8 成功确定后、S9 前生成；S9 事件与审计记录共用同一 id。失败路径也生成负结果记录 id（支持「暴露」审计），记 failure 标记。
- 幂等 key：`(thiefUUID, targetUUID, 交互时 serverTick)` 与 `(targetUUID, 世界日序号)` 双维度；S0 命中即 return。
- 服务端主线程要求：整个状态机只在事件线程（主线程）执行，禁止异步。
- 每日额度边界：主世界日序号 `overworld.getDayTime()/24000`（§5.2，无时区问题）。
- 断线/死亡/维度切换：S8 任意阶段检测到 `hasDisconnected()/isRemoved()/isDeadOrDying()/level()!=事件时 level` -> 中止并回滚，不结算经验。

---

## 10. 随机与成功率公式（草案，数值 8C 再定）

**成功率：**
```
base        = 0.35
+ behind    = +0.25   若「背后」
- lookingAt = -0.25   若「目标正在看影窃者」
- alert     = -0.20   若目标近 5 秒内受到伤害/发现（警觉状态）
- distance  = -0.02 x max(0, dist_blocks - 1.5)     （>1.5 格递减）
- highValue = -0.10   若 ITEM 型且命中 tcth:high_value_stealable_items（负修正）
+ skillTree = 能力树修正（§12，最高 +0.10）
clamp       = [0.05, 0.85]    永不 100%
```

**「背后」/「正在看」向量计算（数值化）：**
- `thiefLook = normalize(thief.getLookAngle())`（`Entity.getLookAngle()` javap 已确认）；
- `toThief   = normalize(thief.position() - target.position())`；
- 「目标正在看影窃者」：`dot(toThief, target.getLookAngle()) > cos(70deg)`，即目标视线与「指向影窃者方向」夹角 < 70°；`cos(70°)=0.342`；
- 「背后」：`dot(normalize(target.position() - thief.position()), thiefLook) > cos(60deg)`，即影窃者面朝与「指向目标方向」夹角 < 60°；`cos(60°)=0.500`；
- 边界：夹角恰等于阈值计入「正在看」（fail-safe 偏向目标）；用 `Vec3.dot` + `Mth` 计算。
- 高价值修正所在层：**S6 成功率层**（不改类型抽取权重）；仅影响 ITEM 型且仅当候选物品在 `high_value_stealable_items` 标签内。

---

## 11. 审计日志与统计设计

### 11.1 审计日志（独立持久化边界）

- 存储：**独立 SavedData**（`ShadowThiefAuditData`），**不写入 vanilla playerdata**；文件 `Server/world/data/shadow_thief_audit.dat`（与 `tcth_brewing_stats.dat`、`lightmanscurrency_bank_data.dat` 同风格）。
- 字段（每记录）：`eventId, timestamp(世界日序号+相对tick), dimension, posX/Y/Z, thiefUUID, victimUUID|entityUUID, entityType, theftType, itemId, itemCount|amountCoreValue, success, failureReason, distance, behindFlag, lookingAtFlag, baseSuccessRate, finalSuccessRate`。
- 访问控制：普通玩家仅能查询自己的记录（UUID 匹配）；管理员权限 >=3 查询全部（`op-level` 检查）；命令 `tcth shadowthief audit`（仿 `GunnerStatsCommand`/`BrewingStatsCommand` 既有命令模式）。
- 容量保护：条数上限（如 50,000）+ 保留时间（如 90 世界日）滚动清理；单文件增长监控。
- **写入失败处理（关键）**：审计写入失败（IO 异常）时财产操作已完成——策略：记录 pending 到内存缓冲，下次 tick 重试；持续失败 -> error 日志并保持 pending，**绝不回滚已完成的财产转移**（回滚已转移财产比丢审计更危险）。
- 敏感数据：**不记录 ItemStack 完整 NBT**，只记 `itemId + count`；金额只记 `coreValue` 数字，不记卡号/钱包结构。
- 普通 debug 日志只输出 `eventId + 结果`，禁止输出 UUID/物品/金额明细。

### 11.2 统计档案（复用 TCTH stats 设计思想，本阶段只列字段与数据版本方案）

- `ShadowThiefStatsData extends SavedData`（仿 `GunnerStatsData`：防御式加载、未知字段回退默认、`save/load` 带 `HolderLookup.Provider`）。
- 玩家统计字段：`attempts, successes, failures, byType{ITEM/COIN/HEALTH/HUNGER/EFFECT}, itemsStolen, coinsStolen, healthStolen, hungerStolen, effectSecondsStolen, victimsHit, daysActive, lastActivityDay`。
- 数据版本：`int version` + 迁移开关；version 0 = 初始字段集，只增不改。

---

## 12. 四路线 12 节点建议（数值建议，8B 不写死）

| 路线 | 节点 | 价格 | 每级具体增强 |
|---|---|---|---|
| 妙手 Sleight | 妙手·初 | 5 | ITEM 候选权重 +50%（不新增类型）；ITEM 成功率 +0.03 |
| 妙手 | 妙手·精 | 20 | ITEM 成功率 +0.04（累计 +0.07）；可窃槽位含副手 40（不含装备栏） |
| 妙手 | 妙手·绝 | 45 | ITEM 成功率 +0.03（累计 +0.10）；非 high_value 标签物品成功率 +0.05 |
| 夺生 Vigor | 夺生·初 | 10 | HEALTH 候选权重 +50%；生命扣除下限 2→1.5（保护线永不为 0） |
| 夺生 | 夺生·精 | 30 | HEALTH 转移量 x1.5；影窃者治疗溢出转为吸收盾（不突破 maxHealth） |
| 夺生 | 夺生·绝 | 60 | HUNGER 候选权重 +50%；饥饿保护线 4→2（仍 >0） |
| 窃法 Arcane | 窃法·初 | 15 | EFFECT 候选权重 +50%；效果上限 10→15 秒 |
| 窃法 | 窃法·精 | 35 | EFFECT 上限 15→20 秒；可窃全部非 ambient 正面效果 |
| 窃法 | 窃法·绝 | 55 | EFFECT 上限 20→30 秒；同效果 amplifier 不更高也可叠加（限 stealable_effects 白名单） |
| 潜影 Veil | 潜影·初 | 25 | 冷却 -20%；成功率 +0.02 |
| 潜影 | 潜影·精 | 50 | 冷却 -40%（累计 -60%）；「背后」判定角 60°→75° |
| 潜影 | 潜影·绝 | 75 | 成功率 +0.05（累计 +0.07，见 §10 skillTree 上限）；潜行影窃时获得短暂隐身（攻击或再次影窃立即解除） |

硬性约束（与任务一致）：
- **不能选择随机类型**：所有节点只增强「已抽中类型」的数值/权重，不提供类型选择。
- 不能突破生命/饥饿保护线（1.5/2 为最低线，永不为 0）。
- 不能让成功率达到 100%：上限恒 0.85（§10 clamp）。
- 潜行隐身在攻击或再次影窃时立即解除（事件监听移除效果）。
- **不增加金币或物品生成**：所有收益来自真实转移，无凭空产出。
- 最高档覆盖低档：数据驱动 `jobsplus:powerup_not_active` 互斥（§7.3），不叠加触发。

---

## 13. SUPPORTED / PARTIAL / BLOCKED / DEFERRED 清单

| 项 | 结论 | 说明 |
|---|---|---|
| 服务端交互入口（EntityInteract） | **SUPPORTED** | 事件类/签名/双端/取消语义全部 javap 实证；推荐入口 `PlayerInteractEvent$EntityInteract`，服务端过滤 `getSide()` |
| 物品转移（Inventory/ItemStack） | **SUPPORTED** | `removeItem/setItem/add` 语义实证；先验可接收再扣受害方 + 补偿回滚可达原子性 |
| 危险物品判定（容器/挎包/钱币） | **SUPPORTED** | `DataComponents.CONTAINER`/`CONTAINER_LOOT` 组件硬拒 + `SatchelItem` 类判定 + `CoinAPI.IsCoin` |
| 货币转账原子性 | **PARTIAL** | 可查/可扣/可加（公共 API 齐全），但无单次原子转账 API（LC `BankTransfer` 两步实现实证）→ **8C COIN 默认关闭** |
| 领地/安全区查询 | **SUPPORTED（查询层）** | `OpenPACServerAPI.get(server)` → `getServerClaimsManager().get(dim,pos)` / `getChunkProtection().onEntityInteraction(...)` 公共 API；「是否允许影窃」无现成事件，需 TCTH 组合查询（§6.3） |
| 新玩家保护来源 | **BLOCKED** | 服务器无现成模组/配置提供「新玩家保护时间」→ TCTH 自有配置（8C 配数值）；8B 只预留字段 |
| 主城/商店坐标保护 | **BLOCKED（无坐标来源）** | 无配置、无数据包；方案：要求服主用 OPAC 声明主城区块，走既有 API，不硬编码坐标 |
| Jobs+/Arc 职业结算 | **SUPPORTED** | PowerupState 链路与 ActionDataBuilder.sendToAction 均 javap 实证 + 四职业既有实现同构 |
| 生物一次性搜刮持久化 | **SUPPORTED** | Entity Attachment（`AttachmentHolder`）随实体 NBT 持久化实证；boss 防刷可加 SavedData |
| 审计日志/统计 | **SUPPORTED** | 独立 SavedData + 防御式加载（`GunnerStatsData` 既有模式） |
| `tcth:unstealable_item_components` 组件标签 | **DEFERRED** | 收益 < 成本；代码层组件判定已覆盖风险面 |
| 信标效果来源区分 | **DEFERRED（如实记录）** | MobEffectInstance 无来源字段；以 `isAmbient` 启发式排除，不声称已完全排除信标 |
| 能力树数值 | **DEFERRED** | 本阶段只列建议值；8B 只建数据框架，不写死 |

---

## 14. 8B 最小安全实现范围

8B 只允许实现以下（按 §9 状态机的安全骨架，不含完整业务）：

1. **事件骨架**：`EntityInteract` 服务端入口 + 前置过滤（SERVER/非 FakePlayer/MAIN_HAND/潜行/空手）+ 幂等 key 检查 + 短冷却占位。
2. **候选净化框架**：5 型候选枚举 + 净化管道（可空实现），`S2-S4` 路径完整但各型只读预检。
3. **领地 fail-closed 检查**：接 `OpenPACServerAPI` 只读查询（§6.3 规则），无 OPAC 时默认拒绝并打 warn。
4. **标签与数据目录占位**：`tcth:unstealable_items`、`tcth:high_value_stealable_items`、`tcth:stealable_effects`、`tcth:unstealable_effects` 空标签创建（数据包，无逻辑）。
5. **审计 SavedData 骨架**：`ShadowThiefAuditData` 字段/容量/清理（§11.1），写入路径带 pending 缓冲。
6. **统计 SavedData 骨架**：`ShadowThiefStatsData` 字段 + version 0。
7. **能力树数据框架**：12 节点 powerup JSON + `jobsplus:powerup_not_active` 互斥（纯数据），能力效果空实现。
8. **单测**：状态机/净化/成功率公式/向量判定/审计序列化（不依赖服务器运行，沿用既有 JUnit 结构）。

## 15. 明确禁止在 8B 实现的阻断项

| 阻断项 | 原因 |
|---|---|
| COIN 真实转账（含补偿模式） | PARTIAL，未复审通过；禁止直接改 NBT/SavedData/数据库 |
| 任何类型的真实财产转移（ITEM/HEALTH/HUNGER/EFFECT 执行段） | 8B 只做骨架与预检；执行段属 8C |
| 反射/NBT 硬改绕过 API 缺口 | 审计规则 #9 |
| 事件发布（`tcth:on_shadow_theft`）与 Arc 奖励 | 成功事件必须建立在真实转移之后（§7.3） |
| 生物搜刮表 `data/tcth/shadow_loot/...` 与实体 Attachment 写入 | 8D 范围（生物型） |
| 能力树数值生效 | 只建框架，不接数值 |
| 新玩家保护/主城坐标硬编码 | BLOCKED/DEFERRED |
| 修改服务器配置、playerdata、世界、职业数据包 | 审计规则 #5 |

## 16. 精确的下一阶段文件规划（8B 建议清单，实际以 8B 设计为准）

```
mod develop/tcthintegration-template-1.21.1/
  src/main/java/com/tanrunn/tcth/impl/shadowthief/
    ShadowThiefModule.java                 # 事件入口 + 状态机骨架（S0-S4、S11）
    ShadowThiefContext.java                # 上下文/幂等 key
    ShadowThiefCooldowns.java              # 冷却占位（SavedData）
    ShadowThiefSafety.java                 # OPAC fail-closed 查询（§6.3 接口草案）
    audit/ShadowThiefAuditData.java        # 审计 SavedData（§11.1）
    stats/ShadowThiefStatsData.java        # 统计 SavedData（§11.2）
    effect/... tags 加载                   # 标签读取（不实现转移）
  src/main/resources/data/tcth/tags/item/{unstealable_items,high_value_stealable_items}.json
  src/main/resources/data/tcth/tags/mob_effect/{stealable_effects,unstealable_effects}.json
  src/main/resources/data/tcth/jobsplus/{jobs,powerups}/shadow_thief/*.json   # 12 节点纯数据
  src/main/resources/data/tcth/arc/shadow_thief/powerup/*.json                # 互斥条件
  src/test/java/com/tanrunn/tcth/test/shadowthief/...                          # 状态机/公式/向量单测
```

## 17. 本阶段实际修改文件清单

| 文件 | 操作 |
|---|---|
| `mod develop/tcthintegration-template-1.21.1/docs/phase-8a-shadow-thief-authoritative-audit.md` | **新增**（本报告） |

未修改任何其他文件；未 commit、未 push；工作区既有改动原样保留。

---

### 验收声明

- AUDIT PASS（权威证据：JAR javap 字节码 + 服务器配置 + 版本对齐源码辅助）
- DESIGN PASS（§4-§12 设计已收口；随机/事务模型见 §9-§10）
- BUILD NOT REQUIRED（本阶段无代码）
- SERVER NOT STARTED
- PLAYER LIVE NOT TESTED

**本阶段不声称：** 影窃功能已实现；钱币转账可用（PARTIAL，未复审）；领地保护已兼容（仅有查询层 API，完整保护策略 §6.3 需 8B 实现并验证）；玩家财产转移已安全（仅设计）；生物影窃已完成。

—— 阶段 8A 报告完 ——
