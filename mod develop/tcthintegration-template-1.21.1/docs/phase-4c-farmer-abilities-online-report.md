# 阶段 4C 在线验收报告 — 农夫能力树部署与四路线实测（含刀工潜伏缺陷修复）

- **日期**: 2026-08-11
- **验收方式**: 真人玩家在线操作(Tanrunn,level 4 OP),服务器日志 + 玩家逐档实测 + 单元测试三路核对
- **环境**: Minecraft 1.21.1 · NeoForge 21.1.247 · TCTH Integration 0.2.7 · Jobs+ 9.0.0 · Arc 9.0.0
- **结论**: **LOAD PASS + PLAYER LIVE PASS(四路线全通过);DEFERRED 见第六节。**
  - 验收中发现并修复**潜伏缺陷**:`arc:on_hurt_item` 在 NeoForge 21.1.247 环境从不触发(见第二节根因),影响农夫耕作与厨师刀工两条数据驱动路线;改为 TCTH 自有 mixin Java 驱动,修复后实测生效。

---

## 一、LOAD PASS

| 项 | 证据 |
|---|---|
| 版本 | `TCTH Integration 0.2.7 (tcth)` |
| Actions | `Loaded 195 actions`(7F 基线 186 → 4B 新增 12 数据驱动 arc 文件;耕作 3 + 刀工 3 已转为 Java 驱动无 arc 文件) |
| Jobs | `Loaded 4 jobs` |
| Job powerups | `Loaded 161 job powerups`(基线 149 → +12 农夫节点) |
| 能力树注册 | `Farmer ability tree active (tilling / harvest / livestock / study routes)` |
| 新条件/reward | `hoe_durability_enabled` / `farmer_study_abilities_enabled` / `farmer_livestock_abilities_enabled` / `farmer_livestock_cooldown` / `farmer_livestock_effects` 全部 `present in ArcRegistry` |
| TCTH Mixin | `ItemStackDurabilityMixin` 注入成功(`Mixing ... into net.minecraft.world.item.ItemStack` + `does use it's CallbackInfo`) |
| 错误计数 | 无 TCTH/Arc/Jobs+/Mixin 错误(日志仅无关第三方 RecipeManager 配方警告) |

## 二、根因审计:arc:on_hurt_item 从不触发(javap 权威证据)

1. **NeoForge 21.1.247 的 ItemStack patch**(GitHub neoforged/NeoForge 1.21.1 `patches/.../ItemStack.java.patch`):
   - `hurtAndBreak(int, ServerLevel, **ServerPlayer**, Consumer)` 被改为**仅一行委托**:`this.hurtAndBreak(amount, level, (LivingEntity) player, onBreak)`;
   - 实际耐久逻辑移入**新增**的 `hurtAndBreak(int, ServerLevel, **LivingEntity**, Consumer)`;
   - `hurtAndBreak(int, LivingEntity, EquipmentSlot)` 入口同时改为**直接委托 LivingEntity 版**(不再转成 ServerPlayer)。
2. **Arc 9.0.0 的 `MixinItemStack`** 只注入 **ServerPlayer 版**(javap: `@Inject(method="hurtAndBreak(IL...ServerPlayer;...Consumer;)V", HEAD, cancellable)`);锄地、挖掘、切菜的全部调用链都走 **LivingEntity 版** → **`arc:on_hurt_item` 在此环境永不触发**。
3. **实测确认**(无条件 + `chance: 100` + `arc:command` 三种临时文件,`/reload` 后锄地/挖石):无任何触发、无任何副作用。
4. **影响范围**:农夫耕作(`arc:on_hurt_item` + `arc:cancel_action`)与厨师刀工(同机制)两条数据驱动路线**从未真正生效**(刀工此前的"切菜测试"未注意耐久,未暴露)。
5. kubeloader 的 `ItemStackMixin` 注入的正是 **LivingEntity 版**(佐证运行时真实重载)。

## 三、修复(Java 驱动 mixin)

- **新增** `mixin/ItemStackDurabilityMixin`(`@Inject` LivingEntity 版 `hurtAndBreak` HEAD,cancellable),按标签分派:
  - `#minecraft:hoes` → `FarmerAbilityModule.shouldSkipHoeDurability`(耕作,10/20/35);
  - `#c:tools/knife` → `ChefAbilityModule.shouldSkipKnifeDurability`(刀工,10/20/35);
- **新增** `tcth_farmer_abilities.mixins.json`(`requiredMods: [jobsplus]`),`neoforge.mods.toml` 注册;Jobs+/Arc 缺失时不解析实现类。
- **数据**:耕作 3 + 刀工 3 个 arc 文件删除(Java 驱动),presets 与服务器副本同步;`hoe_durability_enabled` / `knife_durability_enabled` 条件类型保留注册(数据不再引用)。
- 概率随机源与开关 supplier 可注入(测试);fail-closed(查询/配置异常绝不免损);非锄/非刀、创造模式、空手永不免损。

## 四、PLAYER LIVE PASS

### 4.1 GUI 结构 ✅
- `/jobs` 农夫四路线 12 节点显示正确;等级(5/20/45、10/30/60、15/35/55、25/50/75)与价格(8/12/18)与数据一致。

### 4.2 耕作路线(Java mixin)✅
| 档位 | 实测 | 结果 |
|---|---|---|
| I(10%) | 石锄 50 下剩 86/131(免损 5 次) | 10% ✓ |
| II(20%) | 石锄 50 下剩 94/131(免损 13 次) | ~26% ✓ |
| III(35%) | 石锄 50 下剩 98/131(免损 17 次) | ~34% ✓ |
- 高档覆盖:III 生效时按 35%,不叠加。
- 非锄不生效(石镐 30/30 全掉,对照)。

### 4.3 丰收路线 ✅
- I:成熟小麦收获 → 急迫 I 5 s。
- II(harvest_adept):急迫 I 8 s + 速度 I 8 s(覆盖 I)。
- III(harvest_expert):急迫 I 12 s + 速度 I 12 s。
- 10 s 冷却:收获后立即再收无第二次效果;窗口外恢复。
- 负例:未成熟作物收获无效果。

### 4.4 畜牧路线 ✅
- I:繁殖 → 生命恢复 I 5 s。
- II(livestock_adept):驯服 → 恢复 I 5 s + 抗性 I 8 s。
- III(livestock_expert):繁殖 → 恢复 I 5 s + 抗性 I 8 s + 速度 I 15 s(三件套覆盖)。
- 剪羊毛入口:剪刀剪羊同样触发。
- 20 s 共享冷却:触发后立刻再操作无第二次效果。
- 失败操作不触发(Arc 成功事件语义)。

### 4.5 研修路线 ✅(临时固定 XP 精确验证)
- 临时将 `crop_harvested.json` 奖励改为固定 10 XP(先备份),测后删除并 `/reload` 恢复正式数据;未手工改 NBT。
- I(study_i):10 × 1.15 = 11.5 → **11**(三次稳定)。
- II(study_ii):10 × 1.35 = 13.5 → **13**。
- III(study_iii):10 × 1.60 = **16**。
- **不叠乘**:三档全激活仍 **16**(只取最高档)。

### 4.6 刀工路线(Java mixin,顺带修复)✅
| 档位 | 实测 | 结果 |
|---|---|---|
| I(10%) | 铁刀切 40 次剩 214/250(免损 4 次) | 10% ✓ |
| II(20%) | 切 40 次剩 218/250(免损 8 次) | 20% ✓ |
| III(35%) | 切 40 次剩 220/250(免损 10 次) | ~25% ✓(波动内) |

## 五、数据保留与收尾

- 玩家要求保留测试数据(农夫/厨师等级、节点、金币状态均保留;未恢复)。
- 临时数据已清理:固定 XP 文件已恢复为正式 1-2 XP 并 `/reload`;`crop_harvested.json` 备份已删除。
- Arc debug(`is_debug`)已关闭。
- 正常 `/stop`:`Saving players / Saving worlds / All dimensions are saved`(02:19:48),无残留服务端进程。

## 六、DEFERRED

1. 耕作/刀工免损概率的**大样本统计显著性抽样**(在线各档 40-50 次样本,单位测试承担精确边界)。
2. 研修倍率**非固定 XP 场景**(玩家正常 1-2 XP 收获时的倍率取整表现)未逐次核对。
3. Arc `on_hurt_item` 上游缺陷已记录根因(NeoForge patch 引入 LivingEntity 重载),若未来 Arc 修复,可评估回退数据驱动;当前 Java 驱动不受影响。

## 七、阶段结论

**LOAD PASS(0.2.7,195 actions / 4 jobs / 161 powerups)+ PLAYER LIVE PASS(耕作/丰收/畜牧/研修四路线三档与高档互斥、双冷却、负例全部实测通过)+ 刀工潜伏缺陷随同修复并实测通过;DEFERRED 仅统计抽样与场景补测(非缺陷)。**

本阶段未 commit/push;未提交日志、配置、备份或 playerdata。
