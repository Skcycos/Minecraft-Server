# TCTH Integration

一个可配置、数据驱动的 **NeoForge 模组联动框架**，为烹饪、职业、悬赏、
料理订单和经济模组提供统一事件与兼容机制。

> **状态：** 预发布 / 开发中。公共 API 稳定性尚未承诺。参见
> [CHANGELOG.md](CHANGELOG.md)。

---

## 环境要求

| 依赖 | 版本 | 类型 |
|---|---|---|
| Minecraft | 1.21.1 | 必需 |
| NeoForge | 21.1.247 | 必需 |
| Java | 21 | 必需（toolchain） |

## 支持的模组

所有第三方联动都是**可选**的：缺少其中任何一个，TCTH 都能正常启动运行。
兼容模块仅在目标模组存在时才被加载。

| 模组 | Mod ID | 已验证版本 | 状态 |
|---|---|---|---|
| Jobs+ | `jobsplus` | 9.0.0 | 已实现：厨师职业、料理经验、四路线能力树 |
| Arc | `arc` | 9.0.0 | 已实现：料理 Action、条件和能力奖励 |
| Kaleidoscope Cookery | `kaleidoscope_cookery` | 1.4.1 | 已实现：炒锅/汤锅/蒸笼出锅、品质与署名 |
| Farmer's Delight | `farmersdelight` | 1.3.2 | 已实现：烹饪锅出锅、recipeId 与署名 |
| Field Guide | `fieldguide` | 1.13.4 | 已实现：166 道料理图鉴与出锅解锁 |
| Bountiful | `bountiful` | 8.0.0-beta.2 | 规划中 |
| Order to Cook | `ordertocook` | 1.3.5 | 规划中 |
| Lightman's Currency | `lightmanscurrency` | 2.3.0.5 | 规划中 |
| Kaleidoscope Compat | `kaleidoscope_compat` | 2.9.7 | 已实现：`#c:tools/knife` 厨刀标签纳入刀工路线（4 种厨刀） |

已实现功能一览：

- **Jobs+**：厨师职业（`tcth:chef`）、料理经验奖励、四路线能力树（刀工/炉火/品鉴/研修）。
- **Arc**：料理出锅 Action 与条件，以及能力树奖励（品鉴效果、火焰伤害倍率、耐久取消）。
- **Kaleidoscope Cookery**：炒锅/汤锅/蒸笼出锅识别、品质分级与主厨署名。
- **Farmer's Delight**：烹饪锅出锅识别、recipeId 映射与主厨署名。
- **Field Guide**：166 道料理厨师图鉴与出锅解锁。
- **Kaleidoscope Compat**：其 `#c:tools/knife` 标签为刀工路线提供厨刀集合。

上表列出的是联动目标及在测试服务器上核对过的精确版本。功能将逐模块落地，
并在文档中如实登记。

## 安装方法

1. 安装 Minecraft 1.21.1 与 NeoForge **21.1.247**（或更高兼容版本）。
2. 将 `tcth-0.2.0.jar`（或当前版本）放入 `mods/` 文件夹。
3. 启动一次服务器/游戏，生成默认配置文件
   `config/tcth-common.toml`。

发布包中**不会捆绑**任何第三方模组 JAR，也不要求额外复制第三方 JAR。

## 配置方法

首次启动后编辑 `config/tcth-common.toml`：

- `enabled` — 整个框架的总开关。自阶段 1A 起由统一发布入口**机械保证**：关闭时
  料理完成事件分发器不发布任何事件。兼容模块也应在执行业务逻辑前检查它
  （或其自身开关）。

后续阶段新增的每个联动功能都会拥有独立开关，可单独关闭任意集成。

**奖励开关语义**：`jobsPlusRewardsEnabled` 控制是否发送
`tcth:on_dish_cooked` 料理 Action。它**不**控制预设的 `taste_meal`
Action——那是独立的 `arc:on_eat` Action：只要 `tcth-chef` 数据包启用，
食用 `#tcth:chef_meals` 就会获得 1 XP，即使
`jobsPlusRewardsEnabled=false`。零奖励演练期间不得进食这些料理。

**农夫开关（阶段 4A.2）**：`farmerIntegrationEnabled` 控制统一农事收获
事件框架（`CropHarvestedEvent` 的检测与发布：破坏检测器 + 右键采摘
Mixin）。`farmerRewardsEnabled` **只**控制 `tcth:on_crop_harvested` 的
Jobs+/Arc 农夫奖励，与 `jobsPlusRewardsEnabled`（厨师）相互独立。默认值：
`farmerIntegrationEnabled=true`、`farmerRewardsEnabled=false`（联测通过后
再开启）。

**影窃者开关（阶段 8B，框架骨架）**：`shadowThiefIntegrationEnabled`、
`shadowPlayerTheftEnabled`、`shadowEntityTheftEnabled` 全部默认 **false**。
本阶段只是可执行但不产生任何真实转移的框架骨架：
- 不监听 `PlayerInteractEvent.EntityInteract`，没有 ITEM/COIN/HEALTH/HUNGER/EFFECT
  真实事务，不调用 Lightman's Currency，不引用领地模组，也没有职业数据包；
- 即使手动把开关改为 `true`，空候选 provider、no-op 转移执行器与全拒保护
  也会在协调器早期短路，**不可能转移任何玩家资产**；
- COIN 类型仍被硬阻断（Lightman's Currency 2.3.0.5 无原子转账 API，见
  docs/phase-8a-shadow-thief-authoritative-audit.md §5.2）；
- 审计日志默认开启（`shadowAuditEnabled=true`，写 `world/data/tcth_shadow_audit.dat`）；
  审计可用性在一切候选/随机/资产操作**之前**强制：审计禁用或不可用 → `AUDIT_FAILED`
  拒绝，绝不执行转移；转移走两阶段事务（prepare → commit → rollback），
  `SUCCESS` 只在「commit 成功 + 最终审计写入成功」后发布——最终审计失败触发
  一次回滚（`ROLLED_BACK`），回滚失败进入 `RECOVERY_REQUIRED` 严重状态（携带已
  提交收据供人工恢复），绝不伪报成功。
- **真实转移受独立总闸控制**：事务引擎已接入生产协调器，但必须同时满足
  `enabled` + `shadowThiefIntegrationEnabled` + `shadowPlayerTheftEnabled` +
  `shadowRealAssetTransfersEnabled`（最后一项默认 **false**）才会真正转移资产；
  闸关闭时在候选池/随机/审计/执行器之前即拒绝，资产绝无变化。审计日志是普通
  SavedData，**不是 fsync WAL**——预写与最终写入之间的崩溃会留下
  `RECOVERY_REQUIRED` 窗口；正式服启用真实转移前需运营确认，且尚未进行任何
  在线玩家验收。

## 源码构建

```bash
./gradlew clean build
```

构建产物位于 `build/libs/`。

### 仅开发用的第三方依赖

开发期间，第三方模组 JAR 可放入 `libs/`，以 `compileOnly` / `localRuntime`
方式接入，仅用于本地编译与测试，**绝不**进入发布 JAR。

公开 CI 环境下，编译期第三方依赖必须从其发布的 Maven 仓库获取（见下方
CI 依赖获取方案），保证在没有本地 `Minecraft-Server` 目录的机器上
`./gradlew clean build` 也能成功。

### CI 依赖获取方案

GitHub Actions 在干净的运行器上构建，**没有本地 `Server/mods/`，也没有
`libs/`**，因此每个编译期依赖都必须能从已发布、可重复的来源获取。规则如下：

- 源码直接引用的每个依赖以 `compileOnly` 声明（仅 API 表面），完整模组只加入
  `localRuntime` 供本地测试。**javac 仍然需要源码引用的每一个第三方类型**——
  反射 / `ModList` 守卫加载只解决运行时的可选依赖隔离，不能免除编译期依赖。
- 每个兼容模块开发之前，必须先固定并验证可重复的获取来源（官方 Maven 仓库如
  `https://maven.modrinth.com`、模组作者自建 Maven，或精确测试版本的 Maven
  发布物）。
- 若某模组没有可用的公开 Maven 构件，必须先提交具体方案：要么提供 CI 兼容的
  获取步骤（例如从固定 URL 下载精确 JAR 作为 flat-dir 依赖），要么采用严格的
  接口隔离设计，让第三方类型完全不进入被编译的源码。**"用反射即可解决"不能
  替代构建期依赖方案。**
- CI 中直接下载第三方 JAR 必须遵守对应项目的许可证与再分发规则。第三方模组
  JAR **绝不**提交到本仓库，也**绝不**打包进 TCTH 发布 JAR。

### CI 工作流模板状态

`.github/workflows/build.yml` 是**未来独立 TCTH 仓库使用的工作流模板**：从
固定的 Modrinth CDN 链接下载五个可选模组开发 JAR（Farmer's Delight、
Kaleidoscope Cookery、Arc、Jobs+、Field Guide），并逐一用服务器实际 JAR 的
SHA-256 校验后执行 `./gradlew clean build --no-daemon`。

> 当前源码位于 Minecraft-Server 仓库子目录
> `mod develop/tcthintegration-template-1.21.1/`。GitHub 只执行仓库根目录的
> workflow，因此该**嵌套工作流不会被当前 Minecraft-Server 仓库执行**，也
> **不得声称 GitHub Actions 已实际通过**。迁移到独立 TCTH 仓库根目录后再
> 依赖它。

## 链接

- 主页 / 源码仓库 / Issue Tracker：**待定** —— 公开 GitHub 仓库建立后再填写，
  不使用占位 URL。

## 许可证

本项目采用 [MIT License](LICENSE)。Copyright (c) 2026 Tanrunn。NeoForged MDK
模板原许可证保留在 [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt)。

> 许可证状态为**暂定**：公开发布前将由项目所有者最终确认。

---

English version: [README.md](README.md)。

## 料理署名（阶段 3C）

新出锅的料理会带上 `tcth:cooking_signature` 组件（`chefId` = 主厨 UUID，
`chefName` = 签名时的名称快照），并在客户端 Tooltip 显示一行
`主厨：<名称>`。`/tcth chef inspect` 可只读查看手持料理的署名。

- 开关：`dishSignaturesEnabled`（默认 `true`）。只控制**新产生**料理的署名；
  关闭不会删除已有署名，不影响料理统计、Field Guide 解锁与 Jobs+/Arc。
- 署名时机：玩家亲自出锅（工作台/熔炉/烟熏炉/FD 烹饪锅/KC 炒锅/汤锅/蒸笼）
  时对**真实交付栈**署名；无人生产（自动抽取）、失败取餐、碗/铲子/容器、
  原料与 `raw_dough` 均不署名。批量为整组署名，同主厨再次处理署名一致。
- 堆叠：同一主厨（同 UUID + 同名称）制作的同类料理可以堆叠；不同主厨的
  料理不堆叠；**玩家改名后**新料理与旧名称署名的料理分开堆叠——署名是
  历史快照，这是预期行为，不视为错误。
- 安全边界：署名是**作品展示与溯源信息，不是可信经济凭证**。创造模式、
  管理员命令或第三方模组都能构造带组件的物品；后续金币、经验、订单结算
  不得只信任 ItemStack 中的署名，经济奖励仍应基于服务端真实
  `DishCookedEvent`、订单状态与幂等记录。本阶段不依据署名发放任何奖励。
