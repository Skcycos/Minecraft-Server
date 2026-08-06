# 阶段 4A.1 审计报告：tcth:farmer 作物覆盖矩阵与自动化边界

日期：2026-08-06（会话 `20260805-131045`，阶段 4A.1）

来源：全部为 **JAR 字节码 / javap 实证**（非物品标签推断）。Minecraft+NeoForge
类来自 `build/moddev/artifacts/neoforge-21.1.247-merged.jar`；模组类来自
`Server/mods/` 与 `dev-mods/` 对应 JAR。

## 一、结论摘要

1. **Arc 原生 `on_harvest_crop` 只对 `Block instanceof CropBlock` 的方块在
   “玩家破坏（BREAK）”时触发**；且经 Architectury 桥转发时要求
   `BreakEvent.getPlayer() instanceof ServerPlayer`（字节码实证），因此
   **无 `ServerPlayer` 上下文的机械收割不会触发**。
2. **FakePlayer 未被 Arc 排除**：`net.neoforged.neoforge.common.util.FakePlayer
   extends ServerPlayer`；`ArcServerPlayer` 是接口（ServerPlayer 经 Arc
   MixinServerPlayer 实现）；Arc 全部类中**零处**引用 FakePlayer。若某设备以
   FakePlayer 破坏作物，可绕过防刷。**当前服务器收割设备无 FakePlayer 路径**
   （见第四节），故状态为 NOT_COVERED / NEEDS_LIVE_TEST。
3. 覆盖矩阵显示 **6 类作物/收获方式不在原生判定内**（甜浆果右键、可可豆、
   下界疣、甘蔗、仙人掌、南瓜/西瓜及梗、FD 水稻下半部分、FD/KC 番茄与 KC
   水稻的右键采摘路径）。**是否在阶段 4B 前实现 `CropHarvestedEvent`：见第七节决策**。

## 二、FakePlayer 审计（字节码实证）

| 项 | 结论 | 证据 |
|---|---|---|
| FakePlayer 类 | `net.neoforged.neoforge.common.util.FakePlayer extends net.minecraft.server.level.ServerPlayer` | `javap net.neoforged.neoforge.common.util.FakePlayer` |
| ArcServerPlayer | `public interface ArcServerPlayer extends ArcPlayer`（接口） | `javap com.daqem.arc.api.player.ArcServerPlayer` |
| ServerPlayer 实现 ArcServerPlayer | 由 Arc `MixinServerPlayer`（arc-common.mixins.json 含 `MixinServerPlayer`）注入 → FakePlayer 继承后满足 `instanceof ArcServerPlayer` | Arc mixins.json + FakePlayer 继承链 |
| Arc 是否有 FakePlayer 过滤 | **无**：Arc JAR 全部类中 0 处引用 FakePlayer | 全类 `javap -c` 扫描 |
| Architectury 桥 | `EventHandlerImplCommon.event(BlockEvent$BreakEvent)` 要求 `getPlayer() instanceof ServerPlayer` 才转发 `BlockEvent.BREAK` → FakePlayer（ServerPlayer 子类）会通过 | `javap -c dev/architectury/event/forge/EventHandlerImplCommon` |
| **当前服务器 FakePlayer 收割路径** | **无**：Create 收割机 `visitNewPosition` 调 `BlockHelper.destroyBlockAs(..., aconst_null, ...)`（Player=null）→ 不触发；Create `DeployerFakePlayer` 用于部署器放置/交互，不收割作物 | `javap -c HarvesterMovementBehaviour`（offset 124 `aconst_null`） |
| FakePlayer 状态 | **NOT_COVERED / NEEDS_LIVE_TEST**：Arc 无排除机制；若未来安装假人/机器人收割模组（Carpet、自定义 bot）可绕过“自动化不发经验”。文档不得再声称 FakePlayer 已排除 | — |

## 三、作物覆盖矩阵（真实类层次）

图例：CropBlock 判定 = `Block instanceof CropBlock`（Arc 触发条件）；age 属性 =
存在名为 `age` 的 IntegerProperty（`crop_fully_grown` 匹配条件）；实测 = 玩家在线
实测状态（本阶段无玩家在线，全部未实测）。

### 原版作物（minecraft:）

| 作物 | 方块 ID | Java 类 | 父类 | CropBlock | age 属性 | Arc on_harvest_crop 静态判定 | 玩家实测 | 需 TCTH 兼容 |
|---|---|---|---|---:|---:|---|---|---|
| 小麦 | `minecraft:wheat` | `CropBlock`（直接实例） | `BushBlock` | ✔ | ✔（`AGE`） | 破坏成熟 → 触发 | 未实测 | 否（破坏路径） |
| 胡萝卜 | `minecraft:carrots` | `CarrotBlock` | `CropBlock` | ✔ | ✔ | 破坏成熟 → 触发 | 未实测 | 否 |
| 马铃薯 | `minecraft:potatoes` | `PotatoBlock` | `CropBlock` | ✔ | ✔ | 破坏成熟 → 触发 | 未实测 | 否 |
| 甜菜 | `minecraft:beetroots` | `BeetrootBlock` | `CropBlock` | ✔ | ✔（重定义 `AGE`） | 破坏成熟 → 触发 | 未实测 | 否 |
| 甜浆果 | `minecraft:sweet_berry_bush` | `SweetBerryBushBlock` | `BushBlock` | ✗ | ✔（`AGE`） | 不触发（非 CropBlock；**右键采摘** `useWithoutItem` 不走 BREAK） | 未实测 | **是（右键采摘）** |
| 可可豆 | `minecraft:cocoa` | `CocoaBlock` | `HorizontalDirectionalBlock` | ✗ | ✔（`AGE`） | 不触发（非 CropBlock，破坏收获） | 未实测 | **是** |
| 下界疣 | `minecraft:nether_wart` | `NetherWartBlock` | `BushBlock` | ✗ | ✔（`AGE`） | 不触发 | 未实测 | **是** |
| 甘蔗 | `minecraft:sugar_cane` | `SugarCaneBlock` | `Block` | ✗ | ✔（`AGE` 0–15） | 不触发 | 未实测 | **是** |
| 仙人掌 | `minecraft:cactus` | `CactusBlock` | `Block` | ✗ | ✔（`AGE` 0–15） | 不触发 | 未实测 | **是** |
| 南瓜 | `minecraft:pumpkin` | `PumpkinBlock` | `Block` | ✗ | ✗ | 不触发（破坏收获） | 未实测 | **是** |
| 南瓜梗 | `minecraft:pumpkin_stem` | `StemBlock`（实例） | `BushBlock` | ✗ | ✔（`AGE`） | 不触发（非 CropBlock） | 未实测 | **是** |
| 西瓜 | `minecraft:melon` | `Block`（实例，无 MelonBlock 子类） | `Block` | ✗ | ✗ | 不触发 | 未实测 | **是** |
| 西瓜梗 | `minecraft:melon_stem` | `StemBlock`（实例） | `BushBlock` | ✗ | ✔（`AGE`） | 不触发 | 未实测 | **是** |

### Farmers Delight 作物（farmersdelight:）

| 作物 | 方块 ID | Java 类 | 父类 | CropBlock | age 属性 | Arc on_harvest_crop 静态判定 | 玩家实测 | 需 TCTH 兼容 |
|---|---|---|---|---:|---:|---|---|---|
| 番茄（藤蔓） | `farmersdelight:tomatoes` | `TomatoVineBlock` | `TomatoBlock → CropBlock` | ✔ | ✔（`AGE_3`，名 `age`） | 破坏成熟 → 触发；**右键采摘**（`TomatoBlock.useItemOn/useWithoutItem`）不走 BREAK | 未实测 | **部分（右键采摘路径）** |
| 番茄（绳上） | `farmersdelight:tomatoes_on_rope` | `HangingTomatoBlock` | `TomatoBlock → CropBlock` | ✔ | ✔（`age`） | 同上 | 未实测 | **部分（右键采摘路径）** |
| 卷心菜 | `farmersdelight:cabbages` | `CabbageBlock` | `CropBlock` | ✔ | ✔（继承 `AGE`） | 破坏成熟 → 触发 | 未实测 | 否 |
| 洋葱 | `farmersdelight:onions` | `OnionBlock` | `CropBlock` | ✔ | ✔（继承 `AGE`） | 破坏成熟 → 触发 | 未实测 | 否 |
| 水稻（下半部分） | `farmersdelight:rice` | `RiceBlock` | `BushBlock`（`SimpleWaterloggedBlock`） | ✗ | ✔（`AGE`） | 不触发（非 CropBlock） | 未实测 | **是** |
| 水稻穗 | `farmersdelight:rice_panicles` | `RicePaniclesBlock` | `CropBlock` | ✔ | ✔（`RICE_AGE = AGE_3`，名 `age`） | 破坏成熟 → 触发（继承 CropBlock 无右键交互，仅破坏） | 未实测 | 否 |

> **RiceBlock 与 RicePaniclesBlock 是不同类、不同父类，不得混为一类。**
> 水稻下半部分（`rice`，BushBlock）Arc 不触发；水稻穗（`rice_panicles`，
> CropBlock）破坏时 Arc 触发。

### Kaleidoscope Cookery 作物（kaleidoscope_cookery:）

| 作物 | 方块 ID | Java 类 | 父类 | CropBlock | age 属性 | Arc on_harvest_crop 静态判定 | 右键采摘路径（TCTH Mixin） | 玩家实测 | 需 TCTH 兼容 |
|---|---|---|---|---:|---:|---|---|---|---|
| 生菜 | `kaleidoscope_cookery:lettuce_crop` | `LettuceCropBlock` | `BaseCropBlock → CropBlock` | ✔ | ✔（`age`，blockstate 实证） | 破坏成熟 → 触发（TCTH `tcth:on_crop_harvested` BREAK） | **无**（`LettuceCropBlock.useItemOn` 覆写为直接返回 `PASS_TO_DEFAULT_BLOCK_INTERACTION`，不是收获路径；**仅 BREAK**） | 未实测 | **否（右键）／破坏路径覆盖** |
| 辣椒 | `kaleidoscope_cookery:chili_crop` | `ChiliCropBlock` | `BaseCropBlock → CropBlock` | ✔ | ✔ | 破坏成熟 → 触发 | **专项 Mixin**（`ChiliCropBlock` 覆写 `useItemOn` 且不调用 Base，Base Mixin 无法捕获；`KcChiliCropBlockMixin` 注入其 `useItemOn`，收获后 `onUseBreakCrop` 重置 age 7→5） | 未实测 | **部分（专项右键）** |
| 水稻 | `kaleidoscope_cookery:rice_crop` | `RiceCropBlock` | `BaseCropBlock → CropBlock`（`SimpleWaterloggedBlock`） | ✔ | ✔ | 破坏成熟 → 触发 | **Base Mixin 覆盖**（`RiceCropBlock` 未声明自己的 `useItemOn`，继承 `BaseCropBlock.useItemOn`；收获后 `onUseBreakCrop` 重置 age 7→5；`useWithoutItem` 返回 PASS 非收获路径） | 未实测 | **部分（右键）** |
| 番茄 | `kaleidoscope_cookery:tomato_crop` | `BaseCropBlock`（**直接实例，无子类**；`ModBlocks.TOMATO_CROP` 注册 Supplier = `new BaseCropBlock(TOMATO, TOMATO_SEED)`，KC JAR 字节码实证） | `CropBlock` | ✔ | ✔ | 破坏成熟 → 触发 | **Base Mixin 覆盖**（继承 `BaseCropBlock.useItemOn`；在线实测 RIGHT_CLICK ×1 + BREAK ×1） | 未实测 | **部分（右键）** |

> **动态分派边界（4A.2.1 实证）**：Base Mixin 只覆盖实际继承 Base 收获实现的
> 作物（如 KC 水稻）；覆写 `useItemOn` 的辣椒需专项 Mixin；覆写为非收获路径的
> 生菜不得宣称右键支持（仅 BREAK）。不得再用“Base Mixin 自动覆盖全部子类”
> 作为断言。

### 服务器其他模组植物（非可收获作物，仅记录）

| 方块 | 类 | 父类 | 说明 |
|---|---|---|---|
| spawn: 向日葵植株 / 红藻 | `SunflowerPlantBlock` / `RedAlgaePlantBlock` | `BushBlock` | 生态/装饰植物，非收获作物 |
| kubejs 作物 | 无 | — | 服务器 kubejs `startup_scripts` 无自定义作物注册 |

## 四、收割设备与自动化路径

| 设备 | 玩家上下文 | 是否触发 Arc harvest | 证据 |
|---|---|---|---|
| Create 收割机（Harvester） | **无**（Player=null） | 不触发（`null instanceof ServerPlayer` 失败） | `HarvesterMovementBehaviour.visitNewPosition` offset 124 `aconst_null` + `destroyBlockAs` 手动 post `BreakEvent` |
| Create 部署器（Deployer） | `DeployerFakePlayer extends FakePlayer` | 用于放置/交互，不收割作物；放置触发 `PLACE`（plant 无 action，无影响） | `javap DeployerFakePlayer` |
| 假人/机器人收割模组 | FakePlayer | **可绕过防刷**（Arc 零过滤） | 见第二节 |

## 五、方案 A / B 技术决策（本阶段只决策，不实现）

### 方案 A：继续使用 Arc 原生 `on_harvest_crop`（默认）

适用：CropBlock + 真人玩家破坏（小麦/胡萝卜/马铃薯/甜菜、FD 卷心菜/洋葱/
水稻穗、KC 生菜/辣椒/水稻的**破坏路径**）。

- 优点：零新代码、零 Mixin、与 `tcth:chef` 架构一致、不双算。
- 局限（矩阵中的“需 TCTH 兼容”项）：
  1. 非 CropBlock 作物全部漏检（可可豆/下界疣/甘蔗/仙人掌/南瓜/西瓜/梗、
     甜浆果、FD 水稻下半部分）。
  2. 右键采摘路径漏检（甜浆果、FD 番茄、KC 生菜/辣椒/水稻的右键收获）。
  3. FakePlayer 收割无排除（当前无设备，未来风险）。

### 方案 B：TCTH 统一农事事件 `CropHarvestedEvent`

**采用条件**：仅在确认存在实际漏检后采用（在线实测证实至少一类作物在
预期收获方式下无经验）。**决策（本阶段）**：

- 不盲目新增 Mixin；先完成在线专项验证（第六节），用实测证据确定方案 B 是否
  需要、需要覆盖哪些收获方法。
- 若采用，公共事件最小设计（**公共 API 不引用第三方模组类**）：

```java
public record CropHarvestedEvent(
        UUID eventId,          // 有界幂等：同一方块同一收获行为只结算一次
        ServerPlayer player,   // 真实玩家（FakePlayer 需显式判定，见下）
        BlockState harvestedState,
        BlockPos position,
        ResourceLocation cropId,
        int harvestedCount,    // 事件结算，不按掉落数量重复发送
        boolean fullyGrown,
        boolean automated,
        HarvestMethod method   // BREAK / RIGHT_CLICK / SPECIAL_BLOCK / OTHER
) {}

public enum HarvestMethod { BREAK, RIGHT_CLICK, SPECIAL_BLOCK, OTHER }
```

- 真实玩家与 FakePlayer 明确区分：事件消费方必须用
  `!(player instanceof FakePlayer)`（对 NeoForge `net.neoforged.neoforge.common.util.FakePlayer`
  **与** 第三方 FakePlayer 子类）或 TCTH 提供 `player.isRealPlayer()` 辅助判定。
- 不与 Arc 原生 `on_harvest_crop` 双重结算：两选一
  （a）TCTH 只补漏（非 CropBlock / 右键路径），CropBlock 破坏路径仍由 Arc
  处理；（b）全部统一由 `CropHarvestedEvent` 结算并停用原生 harvest action。
  倾向（a）补漏模式：改动最小、风险最低、Arc 行为不变；需在 Arc action
  条件上排除 TCTH 已接管路径以避免双算。
- 事件发送点（Mixin 候选）：`Block.playerDestroy`（破坏路径）、各作物
  `use`/`useItemOn`（右键路径）、模组特有收获（如 FD 番茄、KC 水稻）。每个
  Mixin 均需先有实测漏检证据，逐项批准后实施。

## 六、在线专项验证清单（有玩家时执行，本阶段未执行）

每项记录经验变化与 Arc/TCTH 日志，不得凭类继承关系冒充在线实测：

1. 成熟/未成熟小麦（破坏）
2. FD 番茄（右键采摘 + 破坏成熟藤蔓）
3. FD 卷心菜（破坏）
4. FD 洋葱（破坏）
5. FD 水稻：分别破坏下半部分（`rice`）与水稻穗（`rice_panicles`）
6. 甜浆果右键采摘
7. 可可豆（破坏）
8. 下界疣（破坏）
9. 甘蔗（破坏）
10. 南瓜/西瓜（破坏果实与梗）
11. 若服务器存在 FakePlayer 收割设备，执行一次自动收割并核对是否漏发经验

## 七、结论

- **是否需要在阶段 4B 前做 `CropHarvestedEvent`：待定（取决于在线实测）**。
  静态审计确认存在 6+ 类预期漏检路径（非 CropBlock 作物 7 项、右键采摘路径
  4 项、FakePlayer 无排除机制）；若玩家实测证实其中任一在正常收获方式下无
  经验，则在阶段 4B 前按方案 B 补漏实现（先做批准的最小 Mixin 集）。
- 若不采用方案 B，阶段 4A 基础经验仅覆盖“CropBlock + 破坏”路径，其余作物
  收获不发经验——属已知范围，需在 README 中如实说明。
- 本阶段未修改经验数值、未改职业 ID、未改服务器启用状态、未开发能力树、
  未修改 playerdata、未提交 Git。

## 八、测试执行与交付（阶段 4A.1）

| 项 | 值 |
|---|---|
| 全量测试（`./gradlew clean build`） | **301/301 通过，0 失败，0 跳过**（测试 XML 汇总：`build/test-results/test/TEST-*.xml`，`tests=301 failures=0 skipped=0`） |
| 新增测试 | `FarmerCropCoverageTest`（6：覆盖矩阵完整性 / 逐项类层次+age 属性+判定一致性 / RiceBlock≠RicePanicles / FakePlayer 状态不得声称 VERIFIED / FakePlayer extends ServerPlayer / Arc 无 FakePlayer 过滤）、`FarmerServerDeploymentTest`（3：enable_default_jobs=false / tcth-chef 数据包在位 / tcth-farmer 数据包在位；依赖 `../../Server/`，无 Server 目录时 assumeTrue 跳过且不计作普适保证） |
| 修正测试 | `FarmerPresetTest` 移除 Server 配置断言（移入 `FarmerServerDeploymentTest`），类注释明确“crop_fully_grown 条件存在 ≠ 全部作物成熟检测成功；FakePlayer 排除不在断言范围” |
| JAR | `build/libs/tcth-0.1.0.jar`，147,493 B，SHA-256 `53a5b79a10afab76245485337c31b030a396422eedc0838b062447fe10339d9a`（与阶段 4A 相同——本阶段未改任何主模组代码/资源） |
| 未实测项目 | 见第六节在线专项验证清单（全部 11 项；本阶段无玩家在线） |
| 未改动 | 经验数值、职业 ID、服务器启用状态、playerdata、能力树；未提交 Git |

**自动化风险（汇总）**：当前服务器唯一机械收割设备 Create 收割机传 `null`
Player（字节码 `aconst_null`）→ 不触发 Arc；FakePlayer 无 Arc 排除机制属
未来风险（NOT_COVERED）；右键采摘路径（甜浆果/FD 番茄/KC 生菜辣椒水稻）与
非 CropBlock 作物为静态确认的漏检路径，待在线实测确认后决定是否在阶段 4B
前实现 `CropHarvestedEvent`（方案 B 补漏模式，见第五节）。
