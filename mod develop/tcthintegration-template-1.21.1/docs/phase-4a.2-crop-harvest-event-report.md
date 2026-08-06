# 阶段 4A.2 交付报告：统一农作物收获事件与 Jobs+/Arc 结算

日期：2026-08-06（会话 `20260805-131045`；阶段 4A.2.1 修正同会话）

> **4A.2.1 修正说明**：本轮修正不扩大功能范围——①KC 右键按动态分派边界拆分
> （水稻=Base Mixin、辣椒=专项 Mixin、生菜=仅 BREAK）；②右键 Mixin 改为无状态
> RETURN-only（删除 @Unique 快照字段与 HEAD）；③严格年龄下降判定（不再用
> `!equals` 作收获证据）；④Break 监听改 `EventPriority.LOWEST` 并如实描述
> 取消边界。未开启农夫奖励（farmerRewardsEnabled=false），未提交 Git。

## 一、目标与结论

为 `tcth:farmer` 建立统一真人收获事件 `CropHarvestedEvent`（破坏 + 右键采摘 +
FakePlayer 排除 + 统一 Jobs+/Arc 结算），删除农夫预设中的 `arc:on_harvest_crop`，
改由 `tcth:on_crop_harvested` 统一发送，避免双倍结算。本阶段不实现农夫统计、
Field Guide 农作图鉴、四路线能力树、金币或悬赏。

## 二、API/JAR 注入点实证（javap 字节码，非猜测）

| 作物 | 类（父类） | 收获方式 | 注入点（精确方法） | 服务端 | 成功返回值 | 收获前成熟 | 收获后状态 |
|---|---|---|---|---|---|---|---|
| 小麦/胡萝卜/马铃薯/甜菜 | `CropBlock` 系 | 破坏 | NeoForge `BlockEvent.BreakEvent` | 服务端 | — | `isMaxAge`/age 最大 | 方块被破坏 |
| 甜浆果 | `SweetBerryBushBlock`（`BushBlock`） | 右键 | `useWithoutItem`（双端执行，需过滤客户端） | 双端 | `sidedSuccess`=服务端 SUCCESS | age==3 | age 重置 1 |
| FD 番茄 | `TomatoBlock`/`TomatoVineBlock`/`HangingTomatoBlock`（`CropBlock`） | 右键 | `useWithoutItem`（`useItemOn` 仅骨粉） | 双端 | `SUCCESS` | age==max | age 归 0 |
| KC 水稻 | `RiceCropBlock`（`CropBlock`，未声明自身 `useItemOn`） | 右键 | `BaseCropBlock.useItemOn`（继承，非镰刀） | 双端 | `ItemInteractionResult.SUCCESS` | age==max | `onUseBreakCrop` 重置 age 7→5 |
| KC 番茄 | `BaseCropBlock` 直接实例（`ModBlocks.TOMATO_CROP` 注册 Supplier = `new BaseCropBlock(TOMATO, TOMATO_SEED)`，KC JAR 字节码实证） | 右键 | `BaseCropBlock.useItemOn`（继承） | 双端 | `ItemInteractionResult.SUCCESS` | age==max | `onUseBreakCrop` 重置 age 7→5（在线实测 RIGHT_CLICK ×1 + BREAK ×1） |
| KC 辣椒 | `ChiliCropBlock`（`CropBlock`，覆写 `useItemOn` 且不调用 Base） | 右键 | `ChiliCropBlock.useItemOn`（专项 Mixin） | 双端 | `ItemInteractionResult.SUCCESS` | age==max | `onUseBreakCrop` 重置 age 7→5 |
| KC 生菜 | `LettuceCropBlock`（`CropBlock`，覆写 `useItemOn` 直接返回 `PASS_TO_DEFAULT_BLOCK_INTERACTION`） | **仅破坏** | —（**不创建**无效右键 Mixin） | — | — | — | — |
| 可可豆 | `CocoaBlock` | 破坏 | BreakEvent | 服务端 | — | age==2 | 破坏 |
| 下界疣 | `NetherWartBlock` | 破坏 | BreakEvent | 服务端 | — | age==3 | 破坏 |
| 南瓜/西瓜 | `PumpkinBlock`/`Block`（恒成熟） | 破坏 | BreakEvent | 服务端 | — | 恒成熟 | 破坏 |
| 甘蔗/仙人掌 | `SugarCaneBlock`/`CactusBlock` | 破坏 | BreakEvent | 服务端 | — | 仅上层（下方同类） | 破坏 |
| FD 卷心菜/洋葱 | `CabbageBlock`/`OnionBlock`（`CropBlock`） | 破坏 | BreakEvent | 服务端 | — | `isMaxAge` | 破坏 |
| FD 水稻 | 穗 `RicePaniclesBlock`（`CropBlock`）；根 `RiceBlock`（`BushBlock`） | 破坏穗 | BreakEvent（根排除） | 服务端 | — | age==max | 破坏 |

「一次交互是否同时进入 useItemOn 和 useWithoutItem」：会按序调用（useItemOn
先，PASS 后 useWithoutItem）；但实际执行收获的只有一个（FD 番茄 `useItemOn`
非骨粉走父类 PASS → `useWithoutItem` 收获；KC 水稻/辣椒 `useItemOn` 收获、
`useWithoutItem` 不存在/返回 PASS）。右键 Mixin 为**无状态 RETURN-only**
（无 `@Unique` 快照字段、无 HEAD 注入）：RETURN handler 直接使用原方法参数
（state/level/pos/player）+ 返回值，并用**严格年龄下降**规则判定（见第六·A
节）；异常时 RETURN 不执行，不留下任何状态或玩家引用；同一次方法链只结算
一次，Dispatcher 幂等再兜底。

> **KC 动态分派边界（4A.2.1 字节码实证）**：`RiceCropBlock` 未声明自身
> `useItemOn`（继承 Base → Base Mixin 覆盖）；`ChiliCropBlock` 覆写
> `useItemOn` 且不调用 Base（Base Mixin 无法捕获 → 新增专项
> `KcChiliCropBlockMixin`）；`LettuceCropBlock` 覆写 `useItemOn` 为直接返回
> `PASS_TO_DEFAULT_BLOCK_INTERACTION`（**不是收获路径，仅 BREAK**，不为生菜
> 创建无效右键 Mixin）。不再以“Base Mixin 自动覆盖全部子类”作为断言。

## 二·A、右键结算：严格年龄下降（4A.2.1）

右键 Mixin 为**无状态 RETURN-only**（删除全部 `@Unique` Snapshot 字段与 HEAD
注入；异常时 RETURN 不执行，不留下状态或玩家引用）。RETURN handler 直接使用
原方法参数（`state` 调用前状态、`level`、`pos`、`player`、返回值），调用后
状态由 `level.getBlockState(pos)` 读取。发布必须**全部**满足：

1. 服务端；
2. 真人 `ServerPlayer`（非 FakePlayer/子类）；
3. 返回值表示成功；
4. 原状态存在名为 `age` 的 IntegerProperty；
5. 原 age 等于合法最大值（成熟）；
6. 当前位置仍是同一 crop block；
7. 当前状态存在同名 `age` 属性；
8. 当前 age **严格小于**原 age。

**不再以 `!current.equals(oldState)` 作为收获证据。** 已实证的收获实现均满足
年龄下降：甜浆果 3→1、FD 番茄 max→0、KC 水稻/辣椒 `BaseCropBlock.onUseBreakCrop`
7→5。若未来某作物收获会删除/替换方块，必须为其写字节码证明的专项规则。

## 三、作物支持矩阵与成熟规则（`CropHarvestRules`）

规则顺序（fail-closed）：排除标签/`StemBlock` → 甘蔗仙人掌仅上层
（`farmer_vertical_crops`）→ `CropBlock` 真实最大年龄 → 可可豆/下界疣 age
最大值 → `farmer_harvestables`（age 验证）→ 未识别 fail-closed。
**（4A.3 变更）南瓜/西瓜不再参与收获**：原"恒成熟"分支与
`FARMER_ALWAYS_MATURE` 常量、`farmer_always_mature` 标签均已**彻底删除**
（4A.3.1）——放置的南瓜/西瓜与自然生长无法从方块状态区分，放置-破坏可
刷经验，用户决策移除（见 4A.3 章节）。

预设方块标签（`data/tcth/tags/block/`）：`farmer_harvestables`（预留）、
`farmer_vertical_crops`（sugar_cane/cactus）、
`farmer_excluded`（pumpkin_stem/melon_stem/attached_*、sweet_berry_bush 破坏路径、
FD `rice` 下半部分）。可选模组条目 `required: false`；标签选择作物、成熟度由
代码验证。

## 四、FakePlayer 判定

- `player instanceof FakePlayer`（含子类如 Create `DeployerFakePlayer`）→
  `automated=true` → `CropHarvestedEventDispatcher` 返回 `AUTOMATED_REJECTED`，
  不发布真人农事奖励事件。
- 无玩家上下文（Create 收割机 `BreakEvent` 传 `null`）→ `instanceof ServerPlayer`
  失败被拒。
- 非 FakePlayer 的机器人玩家：已知边界，不伪称全部排除。
- 检测点：Dispatcher（集中）、CropBreakDetector、右键 Mixin（RETURN-only 无状态）、FarmerRewardModule
  （防御性再检查）。

## 五、幂等设计

- Dispatcher：有界幂等缓存，key=玩家 UUID+维度+BlockPos+gameTime+HarvestMethod；
  100 tick 过期、上限 4096（容量清理）、ServerTick 惰性清理、ServerStopping 清空。
- FarmerRewardModule：独立于厨师的 eventId 幂等缓存（40 tick 过期、4096 上限），
  **发送成功后才提交幂等与限速**；失败不消耗 eventId 可安全重试；单模块异常
  不崩 tick。
- BREAK 与 RIGHT_CLICK 因 method 不同不互相误判重复。

## 六、Arc Action 迁移

- 新增 ActionType `tcth:on_crop_harvested`（`CropHarvestedAction`）与 DataType
  `tcth:crop_id`、`tcth:harvest_method`（复用 `tcth:automated`）；`verifyRegistrations`
  增加对应校验。
- `CropActionDispatcher` 构建 ActionData：Arc 原生 `BLOCK_STATE`/`BLOCK_POSITION`/
  `WORLD` + `crop_id`/`harvest_method`/`automated`。
- `FarmerRewardModule` 监听 `CropHarvestedEvent`，受 `farmerRewardsEnabled`（默认
  false）控制，与厨师 `JobsPlusRewardModule`/`jobsPlusRewardsEnabled` 相互独立。
- 预设迁移：**删除** `docs/presets/tcth-farmer/data/tcth/arc/farmer/harvest_crop.json`
  （`arc:on_harvest_crop`）；**新增** `crop_harvested.json`（type
  `tcth:on_crop_harvested`、reward `jobsplus:job_exp` 1–2、条件 `tcth:automated=false`）。
  保留：繁殖 3–5、驯服 8–12、剪毛 1–2、种植 0 XP。

## 七、配置

`tcth-common.toml`：`farmerIntegrationEnabled = true`（事件检测/发布）、
`farmerRewardsEnabled = false`（仅 `tcth:on_crop_harvested` 奖励，独立于
`jobsPlusRewardsEnabled`）。中英文 README 与语言键同步。

## 八、测试 XML 汇总与构建

| 项 | 值 |
|---|---|
| 全量测试（`clean build`） | **tests=376 failures=0 errors=0 skipped=0**（阶段 4A.1 为 301 → 4A.2 为 367 → 4A.2.1 新增 9） |
| 新增测试（4A.2.1） | `FarmerMixinBoundaryTest`（无 @Unique Snapshot 字段、KC 动态分派边界 Rice/Chili/Lettuce、Lettuce 字节码 PASS 非收获、Break 监听 LOWEST）、`HarvestInteractionMixinSupportTest` 严格年龄矩阵（年龄下降发布/属性变化同年龄不发/同年龄不发/换成无关方块不发/未成熟不发/失败不发/年龄增长不发/FakePlayer 不发/无状态） |
| 新增测试（4A.2） | `CropHarvestedEventTest`（API）、`CropHarvestRulesTest`、`CropHarvestedEventDispatcherTest`、`HarvestInteractionMixinSupportTest`（原版）、`CropBreakDetectorTest`、`FarmerRewardModuleTest`、`CropActionDispatcherTest`、`FarmingMixinConfigTest`（requiredMods 隔离） |
| 更新测试 | `FarmerPresetTest`（无 arc:on_harvest_crop、tcth:on_crop_harvested、automated=false、标签存在、XP 1-2）、`CompatConfigTest`（主配置含原版 mixin）、`FarmerServerDeploymentTest`（crop_harvested 在位、harvest_crop 已删）、`FarmingMixinConfigTest`（KcChiliCropBlockMixin 注册） |
| JAR | `build/libs/tcth-0.1.0.jar`，180,670 B，**SHA-256 `e86932661bd9f461b8f9fa66394bea5e82212b3dc61ac1ec4d8560da46e1bc6a`** |
| JAR 静态检查 | 无 Arc/Jobs+/FD/KC 第三方类；无嵌套 JAR；3 个 mixins.json 完整；`neoforge.mods.toml` requiredMods 正确；无预设数据（jobsplus/arc/tags/… 均无）；中英 farmer 键与配置键完整 |

## 九、Mixin 加载结果（烟雾测试日志）

4 个右键收获 Mixin 的实际目标（阶段 4A.2.1）：

| Mixin | 目标 | 配置（requiredMods） |
|---|---|---|
| `SweetBerryBushBlockMixin` | 原版 `SweetBerryBushBlock.useWithoutItem` | `tcth.mixins.json`（required=true） |
| `TomatoBlockMixin` | FD `TomatoBlock.useWithoutItem` | `farmersdelight_compat.mixins.json`（[farmersdelight]） |
| `KcBaseCropBlockMixin` | KC `BaseCropBlock.useItemOn`（覆盖继承它的 `RiceCropBlock`） | `kaleidoscope_cookery_compat.mixins.json`（[kaleidoscope_cookery]） |
| `KcChiliCropBlockMixin` | KC `ChiliCropBlock.useItemOn`（覆写，专项） | 同上 |

- 主配置（required=true）`SweetBerryBushBlockMixin` 注入无错误（服务器正常
  Done，required=true 注入失败会抛异常）。
- FD/KC 配置注入无失败日志；可选模组缺失时配置整体跳过，不触碰第三方类。
- `CropBreakDetector` 注册为 **`EventPriority.LOWEST` + `receiveCanceled=false`**，
  handler 内仍防御性检查 `isCanceled()`。这是尽力而为的顺序保证（最大限度
  避免已被其他监听器取消的破坏误发），**不是绝对保证**——更低优先级的第三方
  监听器仍可在之后取消。NeoForge `BreakEvent` 属破坏前事件；在线测试需加入
  “受保护区域无法破坏作物时 0 事件”的负例。

## 九·A、安全审查结论（security-review）

verdict：**minor concerns — no blocking or exploitable issues**。FakePlayer 三层
拒绝、无状态 RETURN-only 右键结算（无残留引用）、破坏前状态 + receiveCanceled=false、
双开关强制、缓存有界+过期、权限 3 调试开关、结算异常不崩 tick 全部通过。记录三项：

- LOW：右键路径只检查 `atMaxAge`，`farmer_excluded` 标签仅作用于 BREAK 路径。
  **设计决定：保持现状**——右键路径作物由 Mixin 白名单限定（甜浆果/FD 番茄/
  KC 作物），若让右键路径也套用 excluded，会错误排除甜浆果右键采摘（excluded
  中含 sweet_berry_bush 是用于破坏路径）。管理员如需排除某作物右键奖励，属
  未来增强（可加 `farmer_right_click_excluded` 标签）。
- LOW：Dispatcher 幂等 key 含 tick 且 post 后插入，按 tick 去重、不能抑制重入
  发布；缓存 4096/100 tick 有界，玩家不可利用。
- INFO：FarmerRewardModule 在任意非 null ActionResult 时消耗幂等/限速（即使无
  holder 匹配 0 奖励）；符合"每次有效收获恰好一次结算"，且 farmerRewardsEnabled
  仅在联测通过后开启（holder 必然存在）。

## 十、服务器部署与烟雾测试（首次烟雾测试时 farmerRewardsEnabled=false）

部署前备份：`backup-4a2-crop-harvest-20260806/`（旧 JAR、`tcth-common.toml`、
`world/datapacks/tcth-farmer`）。4A.2.1 复验：新 JAR（e8693266…）+ 最新预设
已重新部署，烟雾日志 `logs/smoke4a2_1.out`（`Done (7.530s)`）。

| 验收点 | 结果 |
|---|---|
| `Loaded 2 jobs`（tcth:chef、tcth:farmer） | ✔ |
| `tcth:on_crop_harvested` 注册成功 | ✔（171 actions 零解析错误，含 crop_harvested.json） |
| 新 DataType（crop_id/harvest_method）注册 | ✔（无异常；数据包引用零错误） |
| Mixin 正常应用 | ✔（见第九节） |
| Arc 数据零解析错误 | ✔（无 unknown action/condition/reward/holder） |
| 不再加载农夫预设 `arc:on_harvest_crop` | ✔（harvest_crop.json 已删除；数据包仅 breed/crop_harvested/shear/tame） |
| Field Guide / 厨师奖励 / 料理署名 / 厨师能力树无回归 | ✔（模块 active 日志、125 powerups、chef 12 节点保留） |
| TCTH 错误为 0 | ✔ |
| 配置生成（首次烟雾测试时） | ✔ `farmerIntegrationEnabled=true`、`farmerRewardsEnabled=false`（最终状态为 true，见 4A.3） |
| 正常停止 | ✔ `All dimensions are saved`；**JVM 已退出**（两者分开记录） |
| playerdata | 未编辑（时间戳 19:41 未变） |

> 已知既有问题（与本阶段无关）：`kaleidoscope_cookery:sickle_breakable_*` 标签
> 解析错误来自 kaleidoscope_compat 自带数据包，4A.1 基线日志同样存在。

## 十一、在线玩家验收（未完成，需玩家在线）

烟雾测试通过后再将 `farmerRewardsEnabled=true` 并完整重启，逐项记录事件数与
职业经验：成熟小麦破坏 1 条 +1~2 XP、未成熟 0、FD 卷心菜/洋葱各 1、FD 番茄右键
1 条 RIGHT_CLICK、FD 番茄破坏 1 条 BREAK（不与右键重复）、FD 水稻正常收获 1 条
（不因上下段重复）、KC 生菜/辣椒/水稻右键各 1、甜浆果右键 1、可可豆成熟/未成熟
1/0、下界疣 1/0、甘蔗上层 1/基部 0、仙人掌上层 1/基部 0、南瓜西瓜各 1、梗 0、
Create 收割机 0、FakePlayer（若能构造）0、每次有效收获恰好一次、厨师出锅仍一次、
`/tcth debug farming` 字段正确。**未完成项目标记为未验证，不得用单元测试冒充
玩家实测。**

## 十二、回滚方式

1. 停服；恢复 `backup-4a2-crop-harvest-20260806/`：旧 JAR（`tcth-0.1.0.jar.pre-4a2`）、
   `tcth-common.toml.pre-4a2`、数据包目录（`datapacks-tcth-farmer.pre-4a2`）。
2. 或仅回退数据包：把 `crop_harvested.json` 换成 `harvest_crop.json`（恢复
   `arc:on_harvest_crop`）并保持 TCTH 新 JAR（新 JAR 兼容旧预设）。
3. `farmerRewardsEnabled=false` 即可关闭农夫奖励，不影响事件检测。

## 十三、建议 Git 暂存范围（未执行）

- 暂存（源码与文档）：`mod develop/tcthintegration-template-1.21.1/` 下
  `src/main/java`、`src/main/resources`、`src/test`、`docs/`、`README*`、`build.gradle`
  （如变更）、`CHANGELOG.md`（如更新）。
- **不暂存**：`Server/`（运行产物）、`backup-*`（备份目录）、`tmp/`、`.gradle-home/`、
  `build/`、`logs/`。保留用户已有未提交改动。
- 本阶段**未执行 git commit / push，未使用 git add -A**。

## 约束确认

未修改 playerdata；未修改厨师经验/署名/统计/图鉴语义；未修改悬赏、金币或收购价；
未开始农夫统计与能力树；未把服务器运行产物、备份目录和配置文件放入源码提交范围。

---

# 阶段 4A.3 在线验收（玩家 Tanrunn 实测）

日期：2026-08-06。服务器实例：`smoke4a3.out`（第一轮，farmerRewardsEnabled=false）、
`smoke4a3b.out`（CONSUME 修复后）、`smoke4a3c.out`（KC 稻米专项修复后）、
`smoke4a3r.out`（第二轮，farmerRewardsEnabled=true）。最终部署 JAR
`e59846eee7b3c4f886e76217c3ee46906f7a0bd048bbc07bec1bcafda34279b6`
（南瓜/西瓜移除版），数据包 tcth-farmer 同步。

## 一、事件检测结果（第一轮，farmerRewardsEnabled=false）

### 已通过（PASS）

| 项 | 期望 | 实测 |
|---|---|---|
| 成熟小麦/胡萝卜/马铃薯/甜菜破坏 | 各 BREAK ×1 | ✅ 各 1 条 |
| 未成熟小麦 | 0 | ✅ |
| 成熟甜浆果右键 | RIGHT_CLICK ×1 | ✅（**修复 1 后**）|
| 未成熟甜浆果右键 | 0 | ✅ |
| 成熟/未成熟可可豆 | 1 / 0 | ✅ |
| 成熟/未成熟下界疣 | 1 / 0 | ✅ |
| 甘蔗上层/基部 | 1 / 0 | ✅ |
| 仙人掌上层/基部 | 1 / 0 | ✅ |
| 南瓜/西瓜果实 | 各 1 | ⚠️ 当时恒成熟规则发事件；**4A.3 用户决策移除**（见三），不计 PASS |
| 南瓜梗/西瓜梗 | 0 | ✅ |
| FD 卷心菜/洋葱破坏 | 各 1 | ✅ |
| KC 辣椒右键 | 1 | ✅ |
| 生菜破坏 / 生菜右键 | 1 / 0 | ✅ |
| KC 番茄破坏/右键 | 1/1 | ✅ |
| KC 稻米右键 | 1 | ✅（**修复 2 后**）|
| KC/各作物未成熟 | 0 | ✅（全部） |
| 花/草/树叶/原木/石头 | 0 | ✅ |
| 经验单次结算（第二轮） | 恰一次 | ✅ |

### 延期（未验证，不计入 PASS 数量）

| 项 | 原因 |
|---|---|
| FD 番茄（右键/破坏/未成熟） | 服务器无 FD 番茄作物 |
| FD 水稻（穗/下半部分） | 服务器无 FD 水稻作物 |
| Create 收割机 | 服务器无收割机设备 |
| FakePlayer | 无法构造 |
| 保护区域取消破坏 | 本轮未配置保护区域 |

每次有效行为恰好 1 条事件、无重复（含修复后复测）。

## 二、经验结算结果（第二轮，farmerRewardsEnabled=true）

玩家确认全部通过：

| 行为 | 期望 | 实测 |
|---|---|---|
| 成熟小麦 | +1～2 XP | ✅ |
| 未成熟小麦 | 0 | ✅ |
| 甜浆果右键 | +1～2 | ✅ |
| KC 辣椒右键 | +1～2 | ✅ |
| KC 稻米右键 | +1～2 | ✅ |
| 生菜破坏 | +1～2 | ✅ |
| 甘蔗上层 | +1～2 | ✅ |
| 甘蔗基部 | 0 | ✅ |
| 南瓜/西瓜果实 | 各 +1～2 | ⚠️ 旧规则下发；已按决策移除 |
| 花/草/石头 | 0 | ✅ |

同一收获未出现两次经验；无旧 `arc:on_harvest_crop` 叠加（该 action 已删除）；
无额外倍率（研修未实现）；`/jobs` 仅显示 tcth:chef 与 tcth:farmer。

## 三、中途修正（真实 bug，均已修复+构建+部署+复测）

1. **甜浆果右键不发布**：`InteractionResult.sidedSuccess(false)`（服务端）返回
   `CONSUME` 而非 `SUCCESS`（字节码实证）→ `SweetBerryBushBlockMixin` 成功判定
   接受 `SUCCESS|CONSUME`；新增回归测试
   `sweetBerryServerSideSuccessValueIsConsume`。
2. **KC 稻米右键不发布**：`BaseCropBlock.onUseBreakCrop` 用
   `setBlock(getStateForAge(5))`，`getStateForAge` 从 `defaultBlockState()` 重建
   丢失 `LOCATION` 属性 → 支撑校验破坏整株（实测右键后上部变 AIR、下部消失）→
   收获后"同一 crop block"检查失败。为 `kaleidoscope_cookery:rice_crop` 增加
   字节码证明的**移除型收获专项规则**（`RIGHT_CLICK_REMOVE_CROPS` 集合），其余
   作物仍要求同一方块 + 年龄下降；新增 3 个专项测试。
3. **南瓜/西瓜放置-破坏可刷经验**：方块无法区分放置/生长来源。**用户决策：移除
   南瓜/西瓜收获事件**——初次修正时 `CropHarvestRules` 删除恒成熟分支、曾将
   `farmer_always_mature.json` 置空并把 `FARMER_ALWAYS_MATURE` 标记
   `@Deprecated`；**4A.3.1 最终清理已将该标签、常量及对应存在性测试彻底删除**；
   测试改为 `pumpkinAndMelonAreNotHarvestable`。

## 四、最终状态与收尾

- 配置：`farmerRewardsEnabled=true`（验收通过后保留）、`farmerIntegrationEnabled=true`、
  `enable_default_jobs=false`。
- 服务器：玩家验收完成后**全部实例已停止**（期间出现一次重复实例/端口冲突，
  经清理；smoke4a3r 实例最后保存由服务器 autosave 完成）。
- `playerdata`：由游戏正常运行写入（玩家加入 tcth:farmer 职业，Jobs+ 保存），
  时间戳 01:40；**未手工编辑**。
- 未开始农夫统计/能力树/图鉴；未修改金币、悬赏或收购价；未修改厨师经验/署名/
  统计/图鉴语义；未执行 git commit / push。
- 测试：`clean build` 后 **tests=380 failures=0 errors=0 skipped=0**（含 3 个
  KC 稻米专项与南瓜/西瓜移除断言）；最终 JAR SHA-256
  `e59846eee7b3c4f886e76217c3ee46906f7a0bd048bbc07bec1bcafda34279b6`。
