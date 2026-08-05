# 阶段 3C 交付报告：料理署名与主厨身份组件

日期：2026-08-05
项目：`mod develop/tcthintegration-template-1.21.1`
服务器：`Server/`

## 0. 工作区保护审计（开始前记录）

- `git status --short`：用户既有更改（`.gitignore`、`.reasonix/*`、`Server/server.properties`、`Server/config/*`）+ 未跟踪文件全部保留
- 分支：`main`；HEAD：`1f313d29e51d3c043d3a4a9957a404dcceb872b9`
- 构建前 TCTH JAR：`d2d70401cd173b083eed81b90b98efba5c2d52b616dff52c633827bafbd3f348`（102051 B，阶段 3B 最终）
- 服务器：开始前未运行；未执行 `git reset --hard` / `git checkout --` / `git add -A` / 自动 commit；未修改玩家 JobsData、经济、悬赏、世界内容；未清理 Field Guide 进度
- 完成时服务器已正常 stop（`All dimensions are saved`）

## 1. 本地 JAR/API 审计 ✅

以当前源码与服务器实际模组 JAR（KC 1.4.1、FD 1.3.2）为权威，用 `javap -p/-c` 核验 7 种设备"最终交给玩家的真实 ItemStack"：

| 设备 | 真实交付栈 | 署名注入点 |
|---|---|---|
| 工作台 | `PlayerEvent.ItemCraftedEvent.getCrafting()`（事件内真实栈） | 检测器事件处理中先署名再发布 |
| 熔炉/烟熏炉 | `PlayerEvent.ItemSmeltedEvent.getSmelting()`（真实栈） | 同上 |
| FD 烹饪锅 | `CookingPotResultSlot.onTake(player, stack)` 的 `stack`（super 调用前交付） | mixin HEAD 署名 `stack` |
| KC 炒锅 | 内部 `result` 字段（`getResult()` 返回活引用）→ `getItemToLivingEntity` 交付 | mixin HEAD 署名 `getResult()` 引用 |
| KC 汤锅 | `result.copyWithCount(takeoutCount)`（复制组件） | mixin HEAD 署名 `getResult()` 引用（copyWithCount 保留组件） |
| KC 蒸笼 | 槽位 `items.get(i)` 引用 → `getItemToLivingEntity` 交付全部非空槽 | mixin HEAD 对每个熟食槽署名；失败取餐回滚 |

- `DishCookedEvent.getResult()` 为防御性副本（构造时 copy）；署名写在真实栈，事件副本带署名但不影响真实栈（单测验证）
- 顺序：取得真实栈 → 写入署名 → 发布事件（所有设备均满足）
- 容器/碗/铲子/载体与料理结果严格区分（只对料理栈签名）
- FD/KC 失败取餐路径不签名（FD onTake 不被调用；KC RETURN=false 不发事件；蒸笼失败回滚）

## 2. Data Component 静态测试 ✅

- `tcth:cooking_signature`：`DataComponentType.builder().persistent(CookingSignature.CODEC).networkSynchronized(CookingSignature.STREAM_CODEC).build()`，经 `DeferredRegister.createDataComponents` 注册（NeoForge 21.1.247 实测 API）
- `CookingSignature(chefId: UUID, chefName: String)`：不可变 record；名称 sanitize（去 `§` 格式码与格式字符、去控制字符、截断 32 字符）；UUID codec 用容错 flatXmap、StreamCodec 用 STRING_UTF8.map
- 不保存时间/eventId/设备/品质/次数/坐标
- 堆叠语义：同 UUID+同名称可堆叠；不同主厨分开；改名后新旧署名分开堆叠（历史快照，README 已记录）

## 3. 各设备真实栈测试 ✅（单元测试，真实 ItemStack 模拟 mixin 注入点序列）

`DishSignatureDeliveryTest`（真实服务 + 真实栈，镜像各设备交付路径）：
- 炒锅：`getResult()` 活引用签名 → 事件快照 copy 带署名、交付引用带署名、count 不变
- 汤锅：签名 result → `copyWithCount(1)` 交付份保留署名、锅 result count 不变
- 蒸笼：多槽独立签名、原料槽不签、失败回滚移除
- FD：onTake stack 交付带署名
- 堆叠：同主厨堆叠 / 不同主厨不堆叠 / 署名与未署名不堆叠

`DishSignatureServiceTest`（13 项）：配置关、框架关、player null、空栈、非料理、覆盖旧署名、count 不变、其他组件保留、事件副本带署名且独立、失败不抛。

## 4. clean build ✅

```
./gradlew clean build --no-daemon
tests=225  failures=0  errors=0  skipped=0
```

- JAR SHA-256：`908e3f6c9e55e5cad30afc01afb602823530023ab0b9c02a0e39301f4b0cf6cc`（3C.1 修正后最终构建）
- JAR 大小：113633 字节
- 无 `com/evandev` 第三方类、无嵌套 JAR；`client/TooltipEvents` 仅在 `com.tanrunn.tcth.client` 包（Dist.CLIENT）
- lang：中英各 7 个新键（tooltip + inspect 命令 + 配置）
- 原有 Field Guide optional 兼容不受影响；`tcth.mixins.json` 保持空

## 5. Dedicated Server 烟雾测试 ✅（无玩家）

- `Done (6.171s)` ✅
- Data Component 注册成功（无 codec/registry/network 错误；日志中第三方模组既有错误与 TCTH 无关）✅
- Field Guide 三分类仍为 84/58/24 ✅
- Jobs+ 模块正常（`Jobs+ dish reward module active`）✅
- TCTH 错误 0 ✅
- 正常 `stop` → `All dimensions are saved` ✅

## 5b. 阶段 3C.1 复审修正（2026-08-05）✅

本次复审发现并修复三项初版问题（此前安全审查未覆盖这些顺序/上限问题）：

1. **初版事件快照顺序错误**：KC 炒锅/汤锅/蒸笼原先"先复制无署名快照、再签名真实栈"，导致 `DishCookedEvent#getResult()` 不含署名。已改为 **保存旧署名 → 签名真实栈 → 再复制带署名快照 → 取餐 → 成功发布带署名快照 / 失败恢复旧署名**。事件防御性副本语义不变。
2. **初版失败回滚会删除旧署名**：原先失败取餐直接 `remove(签名)`，会误删料理在本次取餐前已有的其他主厨署名。已改为每栈/每槽独立保存 `previousSignature + hadPreviousSignature`，失败时"有则恢复原署名、无则删除本次新增"；蒸笼逐槽独立恢复，部分交付时未交付槽也恢复到取餐前状态。
3. **初版网络字符串上限过大**：UUID 原用 `STRING_UTF8.map(UUID::fromString)`，名称原用无界 `STRING_UTF8`（最大 32767）。已改用 `UUIDUtil.STREAM_CODEC`（固定 16 字节）与 `ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH)`（有界），并补充解码测试（16 字节往返、32 字符通过、超长网络输入拒绝、NBT 解码仍 sanitize、空白名称不渲染 Tooltip）。

回归测试新增 13 项（共 225 项），全部通过；测试模拟顺序与真实 Mixin 注入点顺序一致。

**玩家实际 Tooltip 与设备出锅仍未在线验证**（无在线玩家）。

## 6–9. 玩家实测 ❌ 未实测（无在线玩家）

以下项**不得虚报为已实测**：
- 玩家 Tooltip 实测（工作台/熔炉/烟熏炉/FD/KC 各设备 Tooltip 显示"主厨：xxx"）
- 各设备实际署名（shift-click 保留、品质保留、raw_dough/建材工具不署名等实机行为）
- 堆叠行为实机验证（不同玩家同类料理不能堆叠、同玩家可以堆叠）
- 丢弃/存箱/交易/重启持久化

上述行为已由单元测试（真实 ItemStack + 真实注入点语义）覆盖，但玩家实机验收待在线玩家执行。

## 10. 未验证项

1. 玩家实机逐项测试（任务十三 20 项）
2. KC 品质组件在实机炒锅/汤锅上的保留（单测用"其他组件保留"模拟）
3. `/tcth chef inspect` 实机命令展示

## 安全边界（已写入 javadoc 与 README）

署名是作品展示与溯源信息，不是可信经济凭证；创造模式/管理员命令/第三方模组可构造带组件物品；后续金币、经验、订单结算不得只信任署名，仍基于服务端真实 `DishCookedEvent`、订单状态与幂等记录；本阶段不依据署名发放任何奖励。

## 交付物清单

- `impl/signature/`：CookingSignature（值对象+Codec+StreamCodec+sanitize）、CookingSignatureComponents（注册）、DishSignatureService（署名服务）
- `client/TooltipEvents`：客户端 Tooltip（Dist.CLIENT）
- 设备接入：VanillaCookingDetector、CookingPotResultSlotMixin（FD）、PotBlockEntityMixin/StockpotBlockEntityMixin/SteamerBlockEntityMixin（KC）
- `TcthCommands`：`/tcth chef inspect`（只读）
- `Config.dishSignaturesEnabled`（默认 true）；lang 中英键；README 中英署名与安全边界说明
- 测试：5 个新测试类（42 用例）

> 本报告区分实测与未实测。本阶段不执行 Git commit。
