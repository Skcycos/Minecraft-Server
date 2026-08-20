# 阶段 6E.0 / 6E.0.1 —— 新增食物模组悬赏定价审计与生成器修正

## 1. 阶段边界

- 本阶段只生成新增料理的 Bountiful 定价预览、价格缺口表和套利风险复审表。
- 未修改 `Server/config/bountiful/bounty_pools/*.json`、decree JSON、KubeJS
  正式悬赏注册/提示、playerdata 或 Shadow 配置。
- 未进入正式悬赏池合并；6E.0.1 静态复审完成后继续停在预览阶段。
- 当前工作区既有 8A–8E 影窃者改动完整保留，未执行 add、commit、push、reset
  或 checkout。

## 2. 范围与分类表修复

权威输入为：

- `食物三档分类表.csv`
- `新增食物模组普通手持料理清单.csv`
- `原料单价参考表.csv`
- `菜品悬赏定价表.csv`
- 当前 Bountiful food pools 和 KubeJS food recipe export

最终目标范围核对：

| 档次 | 数量 |
|---|---:|
| COMMON | 38 |
| T2 | 132 |
| T3 | 0 |
| 合计 | 170 |

170 个 ID 唯一，且本阶段目标 ID 均为合法 ResourceLocation。范围由 6A/6B
普通手持料理 167 个加 6D 分食产物 3 个组成；没有把 DRINK、INGREDIENT、
RAW_FOOD、SERVING_DISH、NON_FOOD 或 T3 候选混入预览。

### 6D 三行结构证据

修复前：

- 表头为 24 列。
- 三个 `mynethersdelight:plate_of_stuffed_hoglin*` 行各为 25 列。
- 多出的空字段位于饱和度/效果后的空字段组，导致代表机器及后续字段整体右移。

修复后：

- 只删除三行各自多出的一个空字段，未重写其他权威表行。
- 全表每一行均为 24 列。
- 三个分食产物字段已归位：`T2`、`mynethersdelight:oven_roasting`、
  `mynethersdelight:roast_stuffed_hoglin`、配方条数 `1`、难度系数 `1.2`、
  pool `food_t2_objs`、原占位 unitWorth `35`。
- 三个 ID 唯一且合法。

表中另有一条历史占位行使用 `{}` 作为产物 ID。该行不属于本阶段 170 项，按
范围限制未顺手修改，并在本阶段报告中作为既有权威表数据问题保留。

## 3. 运行时配方证据

旧导出文件：

- `Server/kubejs/config/food_recipe_export.json`
- 修改时间：2026-08-10 20:51:22
- SHA-256：`27d1f3be60df2f83d9fd0ed6b25e534e634e977e2a14b587118b2b2ce636c4c1`
- recipe rows：1025 条有效数据行
- 目标覆盖：0/170

该导出已判定过期，不能作为当前定价权威。按阶段允许范围执行过一次无玩家
服务端启动尝试，意图执行 `/syexport food_recipes` 后正常停止；控制台输入未
被可靠消费，未形成新的可引用导出证据。该次服务端 Java 进程已终止并确认无
残留 TCTH NeoForge 服务端进程。

6E.0 初版使用 `JAR_STATIC_ONLY` / `RUNTIME_EXPORT_STALE`；6E.0.1 已拆分为
`runtimeStatus=STALE`、`runtimeVerified=false`、`staticEvidence=JAR`。两种表述都不
声称当前运行时配方导出已刷新，也不把历史 1025 条导出当作权威结果。

## 4. 6E.0 初版定价方法（历史，已被 6E.0.1 复审取代）

生成器：`Server/tools/export_phase6e0_bounty_pricing_preview.mjs`

- 从目标模组 JAR 的实际 recipe JSON 读取静态候选路径。
- 递归展开可识别的中间产物。
- 多产出按实际 output count 分摊。
- 容器字段不直接作为永久消耗；动态组件、标签无法定价时进入 REVIEW。
- 选择可闭合候选中的最低成本路径。
- 计算：

  `原料成本 / 输出数量 × 难度系数 × buff 系数 × COMMON 0.95 / T2 1.0`

- 6E.0 初版错误地优先读取分类表中的 `1.0/1.2`，而不是固定使用悬赏系数
  `COMMON=1.15 / T2=1.65 / T3=2.45`；该行为已在 6E.0.1 修正。
- 旧表中的 COMMON=15、T2=35 只作为历史占位证据，不直接作为新正式价格。
- 缺少原料价格、配方循环、零成本、动态组件、无静态配方和自动化风险均不
  静默猜价。

### 4.1 6E.0.1 已完成的修正

- 悬赏难度系数已与分类表的旧建议系数解耦，固定为
  `COMMON=1.15 / T2=1.65 / T3=2.45`。
- `fire_resistance` 不再被归入负面系数；未知效果进入 `BUFF_REVIEW`。
- shaped 配方按 pattern 中的实际符号次数计数，同一原料槽的备选项不再直接
  全部相加。
- 每条静态配方先形成候选对象，再绑定 recipeId、设备、产量、成本和证据链。
- 输出证据拆分为 `runtimeStatus=STALE`、`runtimeVerified=false`、
  `staticEvidence=JAR`，不再把 JAR 静态配方称作当前运行时有效配方。
- 原料缺口表不再使用“递归原料依赖”占位，改为输出直接依赖和依赖链。
- 有价格锚点的 recipe `container` 当前计入成本；缺乏价格/返还证据的容器进入
  `CONTAINER_SEMANTICS_REVIEW`。

### 4.2 6E.0.1 交接复审发现的剩余阻断

6E.0.1 的现有自动化测试通过，但静态代码复审确认定价引擎尚未完全收口：

1. `resolveCost()` 的直接价格结果使用 `value` 字段，而递归候选返回
   `rawCost/unitCost`，没有返回 `value`。因此中间产物虽可能计算成功，调用方
   仍会把它视为不可用；当前“递归展开中间产物”结论不成立。
2. 候选已经计算 `unitCost = rawCost / outputCount`，但最终 `unitWorth` 仍调用
   `calculateWorth(selected.rawCost, ...)`。多产出配方会按整份成本计价，尚未
   实现单件分摊。
3. buff 仍以正则和“负面优先”判定，复合正负效果的单一规则尚未由行为测试
   收口；当前只能认定已修正 `fire_resistance` 单点错误。
4. 有价格的容器被直接假定为“消耗且不返还”，尚未逐设备取得返还/载体语义
   证据；因此容器相关价格仍属预览。
5. 缺口表已比 6E.0 可操作，但仍未包含原计划要求的 target tier、slot/alternative
   序号、source JAR 和 source recipe path；风险表也未覆盖完整套利矩阵。
6. 当前测试对五个备选原料料理只断言目标存在，对 apple jelly 只断言证据文本
   含玻璃瓶；尚未验证实际选项成本、容器金额、多产出分摊和递归中间产物。
7. 自动生成的 `新增食物模组悬赏定价摘要.md` 仍写有 `PRICING ENGINE FIXED`；
   在上述阻断修复前，该摘要只能视为生成器输出快照，当前报告才是复审结论。

因此本阶段不能标记 `PRICING ENGINE FIXED`，也不能进入正式池合并。

## 5. 交付物

- `配方与经济管理/统一配方表/新增食物模组悬赏定价预览.csv`
- `配方与经济管理/统一配方表/新增食物模组悬赏原料价格缺口表.csv`
- `配方与经济管理/统一配方表/新增食物模组悬赏套利风险复审表.csv`
- `配方与经济管理/统一配方表/新增食物模组悬赏定价摘要.md`
- `Server/tools/export_phase6e0_bounty_pricing_preview.mjs`
- `Server/tools/export_phase6e0_bounty_pricing_preview.test.mjs`
- 本报告

生成结果分层：

- 6E.0 初版（历史）：预览 170 行、原料缺口 148 条、风险 162 条；其 8 个价格
  因系数/容器/备选项等问题作废。
- 6E.0.1 当前：预览 170 行、实际生成价格 6 项、原料缺口 511 条、风险 164 条。
- 当前预览为 16 列；缺口表为 8 列；风险表为 6 列；所有 CSV 均为矩形 schema。
- 当前 6 个价格仍是静态候选预览，不得写入正式悬赏池。

## 6. 6E.0.1 最终静态检查

- 自动化测试：`phase6e0.1 pricing preview tests: PASS`
- 范围：170 = 38 COMMON + 132 T2
- T3：0
- ID 唯一且目标 ID ResourceLocation 合法
- 预览未混入饮品、原料、生食、整盘容器或非食物
- 预览 170 行/16 列；缺口 511 行/8 列；风险 164 行/6 列
- 缺口表中“递归原料依赖”占位残留：0
- 两次内存生成与磁盘输出逐字节一致：
  - 预览：`ab0bc828254f99ab75bbf54e17dd36fd0d5c9f26aff669618f2d81608e3ae86b`
  - 缺口：`9b0aa2fa0f5ba8d8afe3cc5595041c23afedc0266cda759e72b50cd2d0962b03`
  - 风险：`849a4758b774f40a316a90655f698bc94d7de85da77a7a733bbbc5b319ab4fbf`
  - 摘要：`e8b841bb952ea5210b4d0fc0902d629438e5ebf34e0fde3c593b079045ef3e41`
- Bountiful pool 顶层结构仍为 `content`
- `food_common_objs.json`、`food_t2_objs.json`、`food_t3_objs.json` 未修改
- 既有正式定价表 `菜品悬赏定价表.csv` 未修改
- `git diff --check`：通过
- 当前工作区 `/Users/a1111/Desktop/Minecraft-Server/Server` 无 NeoForge 服务端进程；
  6E.0.1 未启动服务器、未重新导出配方。

保护文件当前 SHA-256（用于本轮前后核对）：

- `food_common_objs.json`：`b731c6e4e306327aca7456ea603ce6da02b52840cb296f73525f442180117d2c`
- `food_t2_objs.json`：`ebffa9d1c7473406f93715e24d55fbea8c5bc3fc3e0910e9e7b9d2603282c0c6`
- `food_t3_objs.json`：`6ce40d402ed1380a6c227089d3eaaaa09d4215d641d8bdb115fb97c372e3b728`
- `bounty_food_registry.js`：`0085c1fdbc27241254fc0f73f6759f21cf0b2de69060e07aeae238f1ec18862b`
- `菜品悬赏定价表.csv`：`5cb28603242f5c935833bff4368eba3e8d72f0f73d94276d189e90f9eee272b0`

## 7. 6E.0.1 阶段结论

- **SCOPE PASS**
- **CSV STRUCTURE PASS**
- **PRICING ENGINE PARTIAL / BLOCKED**
- **PREVIEW GENERATED（6 个静态候选价格，不可正式写入）**
- **RUNTIME RECIPES NOT VERIFIED**
- **RUNTIME EXPORT STALE**
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED（6E.0.1）**
- **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

6E.0.1 已修正初版最明显的档次系数、`fire_resistance`、备选槽和证据命名问题，
但递归成本返回协议、多产出单件分摊、容器权威语义及其行为测试仍是正式定价
前的阻断。下一步应先完成 6E.0.2 计算引擎收口，再补齐原料价格和运行时配方
证据；当前禁止进入 6E.1 正式悬赏池合并。

## 8. 6E.0.2 计算引擎最终收口

本轮只修改静态 Node 生成器、行为测试、预览输出和本报告；未修改正式定价表、
Bountiful pools/decree、KubeJS 正式配置、playerdata、TCTH Java 代码或 8E 工作区
文件，未启动服务器、未重新执行 `/syexport food_recipes`、未部署、未在线测试，未
commit/push。

- 递归协议统一为候选对象：`unitCost` 始终为单件成本，`rawCost` 为整份成本；直接
  价格也转换为同一协议。中间产物链可继续展开，证据链保留 `C -> B -> A`。
- 多产出最终使用 `unitCost` 计算 `unitWorth`，预览同时输出整份成本和单件成本。
- 每条候选绑定 recipeId、recipe type/device、outputCount、原料选择、容器决策、JAR
  和 recipe JSON 路径、证据链与风险；最低合法候选整体选取。失败候选只进入 alternatives
  风险，不污染已选路径。
- buff 使用显式 token 分类：NONE 1.00、LIGHT 1.15、STRONG 1.35、纯 NEGATIVE 0.85；
  `fire_resistance` 属强正面；正负混合、已知加未知、全未知均为 REVIEW，不猜测。
- 容器决策类型为 CONSUMED、RETURNED、REUSABLE、PART_OF_RESULT、REVIEW；无静态权威
  证据默认为 REVIEW，不因为价格表有锚点就判定消耗。`apple_jelly` 的 glass bottle
  保留为 `REVIEW`，不静默计价。
- 缺口表扩展为 14 列，包含目标档次、直接缺失 ID/tag、依赖类型、完整链、槽位、备选
  序号、出现次数、JAR、recipe path、其他合法路径、建议动作和状态。风险表扩展为 9
  列，区分具体 recipe/JAR/path、风险对象、证据、建议动作和状态。

### 8.1 6E.0.2 测试与生成结果

- Node 行为测试：`phase6e0.2 pricing preview tests: PASS`。
- 范围：170 = COMMON 38 + T2 132，T3 0；ID 唯一且 ResourceLocation 合法。
- 系数行为：`ceil(22*1.65)=37`；`ceil(12*1.15*0.95)=14`；覆盖递归、中间产物、多
  产出、shaped 重复、单槽备选不叠加、五个 My Nether's Delight 料理、容器、buff
  复合、循环隔离和同源候选证据。
- 预览：170 行 × 21 列；实际生成 unitWorth 0 项（静态缺口/REVIEW 未被静默定价）。
- 缺口：659 行 × 14 列；风险：170 行 × 9 列；“递归原料依赖”泛化占位为 0。
- 两次内存生成、磁盘输出逐字节一致：
  - 预览：`ddcbb643bd25acf94a9031d5eaca821147ec986fd4b155aa5b363087fe8c0dbf`
  - 缺口：`8e4706a00c3337a3af20fe3c0e605a23a73fafaf255d5e869a82131fe4b1f9a1`
  - 风险：`cc31ba6d601ebfddda59f099785c9188854b01bf77a7e13533d07cecac248172`
  - 摘要：`2712ee8515e02e2d1677b57aed5c183cea8ac326fda4d0c662f475aa422f19fb`
- 保护 manifest 前后不变：三个 food pool、`bounty_food_registry.js` 和正式菜品定价表
  SHA-256 均保持 6E.0.1 记录值。

### 8.2 6E.0.2 分层结论

- **SCOPE PASS**
- **CSV STRUCTURE PASS**
- **PRICING ENGINE FIXED**（仅指 Node/static 计算引擎与行为测试）
- **PREVIEW GENERATED**
- **PREVIEW ONLY**
- **RUNTIME EXPORT STALE**
- **RUNTIME RECIPES NOT VERIFIED**：JAR 仅为静态候选配方，未声称当前 RecipeManager
  有效或 UNITE 后仍有效。
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED**
- **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

本阶段完成后停止，等待复审；不进入 6E.1，不进行人工补价或正式池合并。

## 9. 6E.0.2.1 悬赏定价预览阻断修正

本轮只修改静态 Node 生成器、行为测试、预览输出和本报告；未修改正式定价表、
Bountiful pools/decree、KubeJS 正式配置、playerdata、TCTH Java 代码或 8E 工作区
文件，未启动服务器、未执行 `/reload`/`/syexport`、未部署、未在线测试，未
commit/push。

### 9.1 真实格式 Buff 解析

- 只从 `effect.<namespace>.<path>` 提取完整效果 ID（如 `minecraft:fire_resistance`、
  `farmersdelight:nourishment`），tick、持续时间、等级、概率及 `effect` 前缀不再
  作为未知 token。
- 显式分类：强正面（STRONG 1.35）、普通正面（LIGHT 1.15）、负面（NEGATIVE 0.85）；
  正负混合 MIXED REVIEW、已知加未知 REVIEW、全未知 REVIEW。
- 旧中文描述（如 `温暖 1分20秒`、`抗火 2分`）无法解析为效果 ID，不再静默当作无
  效果（NONE），统一进入 REVIEW。
- 测试使用分类表真实原文覆盖 fire_resistance、speed、regeneration、nourishment、
  poison、正负复合（`effect.minecraft.regeneration…；effect.minecraft.poison…`）、
  未知模组效果（bakeries/dungeonsdelight/neapolitan/mynethersdelight）与旧中文文本。

### 9.2 根上下文缺口

- 递归成本求值全程携带不可变根上下文 `rootTargetItemId/rootTargetTier`，缺口行的
  targetItemId 始终为 170 项目标料理，targetTier 恒为 COMMON/T2。
- fullDependencyChain 从根目标开始并以 directMissingDependency 结尾；中间产物只
  出现在链中，不再被写成 targetItemId。
- 自动断言：blank targetTier = 0；超出 170 范围的 target = 0；链起点/终点错误 = 0。

### 9.3 测试与生产实现同源

- 删除独立的 `resolveCostGraph` 测试替身；`resolveCost`/`evaluateCandidate`/
  `evaluateTargetCandidates` 为 buildPreview 与测试共同调用的纯求值器。
- 同一求值器覆盖直接价格、两层/三层递归、多产出单件成本、多候选选择、合法与循环
  候选隔离、循环且无合法候选、容器 CONSUMED/RETURNED/REUSABLE/REVIEW。
- 测试断言 buildPreview 预览行（recipeId、证据链、原料选择）与共享求值器对真实
  `mynethersdelight:blue_tenderloin_steak` 求值结果一致。
- 容器决策：CONSUMED/PART_OF_RESULT 计入成本；RETURNED/REUSABLE 不计价且无风险；
  无静态权威证据保持 REVIEW。

### 9.4 6E.0.2.1 生成结果

- Node 行为测试：`phase6e0.2.1 pricing preview tests: PASS`。
- 范围：170 = COMMON 38 + T2 132，T3 0。
- 实际定价：2 项（`dungeonsdelight:amethyst_rock_candy` T2 = 63、
  `mynethersdelight:tear_popsicle` COMMON = 120），由真实效果 ID 正确归类且配方
  原料链可闭合，非 Buff 误判或容器静默计价。
- UNKNOWN_BUFF = 77；MIXED_BUFF = 1（`mynethersdelight:blue_tenderloin_steak`，
  nausea + nourishment 正负混合）；CONTAINER_REVIEW = 16。
- 预览：170 行 × 21 列；缺口：1009 行 × 14 列；风险：168 行 × 9 列。
- 6E.0.2 记录中的缺口表 SHA `8e4706a00c3337a3af20fe3c0e605a23a73fafaf255d5e869a82131fe4b1f9a1`
  已被 6E.0.2.1 重生成结果取代。
- 两次内存生成、磁盘输出逐字节一致：
  - 预览：`ff65b7ac89544a5175866f8bbedbb254bf12fec1c2f9356294e5dbd112f4f6d2`
  - 缺口：`e2a8058aeea3b3fd36ae7b3bc82b1eb2bd60ed7810efbd3c343c6d1c1075941a`
  - 风险：`e9fe38a4c41ad41eafb2e516ac46faa8f90778da3aae1c43d80c8525144388c0`
  - 摘要：`96ad2dd274e9a4c80135d766361ead888b5cd410350e3f85979b408046cffa36`
- 保护 manifest 前后不变：三个 food pool、`bounty_food_registry.js` 和正式菜品定价表
  SHA-256 均保持 6E.0.1 记录值。
- `git diff --check`：通过；当前工作区无服务端进程。

### 9.5 6E.0.2.1 分层结论

- **SCOPE PASS**
- **CSV STRUCTURE PASS**
- **PRODUCTION-PATH TEST PASS**（buildPreview 与共享求值器同源）
- **BUFF PARSING PASS**（真实 `effect.<ns>.<path>` 格式）
- **DEPENDENCY CONTEXT PASS**（根上下文缺口）
- **PREVIEW GENERATED**（2 项静态候选价格，PREVIEW ONLY）
- **RUNTIME EXPORT STALE**
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED**
- **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

本阶段完成后停止，等待复审；不进入 6E.1，不进行人工补价或正式池合并。

## 10. 6E.0.3 悬赏定价证据收口

本轮只修改静态 Node 生成器、行为测试、预览输出（含两份新复审表）和本报告；未修改
正式定价表、Bountiful pools/decree、KubeJS 正式配置、playerdata、TCTH Java 代码或
8E 工作区文件，未启动服务器、未执行 `/reload`/`/syexport`、未部署、未在线测试，未
commit/push。

### 10.1 严格效果 ID 分类

- 已提取到结构化 `effect.<namespace>.<path>` 时只按 `BUFF_BY_FULL_ID` 查询，禁止按
  path 短名回退；`effect.unknown.strength/speed/poison` 均须 UNKNOWN REVIEW。
- `BUFF_BY_SHORT` 仅用于没有结构化 effect ID 的历史英文短文本。
- ResourceLocation 路径支持合法 `/`、`.`、`-`、`_`，拒绝非法或截断 ID
  （`effect.minecraft`、`effect.minecraft.` 均 REVIEW）。
- 明确分类：`minecraft:slow_falling` 按原版正面效果为 LIGHT 1.15；
  `minecraft:levitation` 保持 REVIEW（无运营决定）。
- 模组效果（bakeries/dungeonsdelight/neapolitan/mynethersdelight）未取得服务器实际
  JAR/匹配版本源码或 javap 证据前一律 REVIEW，不按名称猜测。

### 10.2 价格锚点证据等级

- 原料单价参考表“来源”列分级：`已定义`/`已定义/…`/`配方派生`/`派生`/`最便宜锚点`/
  `番茄/卷心菜锚点`/`对齐番茄` → DEFINED；`猜想`/`缺省猜想` → PROVISIONAL；空值或
  未知 → REVIEW。
- `directResult` 保留 `priceValue/priceSource/priceConfidence`；递归候选跨层合并全部
  直接与间接锚点来源，`provisionalAnchors` 列出实际使用到的全部猜想锚点。
- advisory（`PROVISIONAL_PRICE_ANCHOR`）与 blocking（`MISSING_PRICE`、
  `RECIPE_CYCLE`、`CONTAINER_SEMANTICS_REVIEW`、`ZERO_COST`）风险分离：advisory 允许
  计算暂定 unitWorth，blocking 一律禁止生成价格。
- 两项可计算候选改为“暂定候选价格”：`amethyst_rock_candy` 仍为 63（猜想锚点
  `minecraft:amethyst_shard=18`）；`tear_popsicle` 仍为 120（猜想锚点
  `minecraft:ice=8`、`minecraft:ghast_tear=40`、`minecraft:stick=1`），均标记
  PROVISIONAL_PRICE_ANCHOR，不再描述为“无风险实际定价”。

### 10.3 新增复审表

- 新增 `新增食物模组悬赏定价锚点复审表.csv`（14 列）：由 1009 条缺口记录按直接依赖
  汇总为 107 个唯一锚点（按 dependencyId+dependencyKind），occurrenceRows 与
  affectedTargetCount 分开统计，affectedTargetIds 确定性排序；CONTAINER 仅因价格存在
  不得判定消耗，一律 BLOCKED；无证据时 suggestedPrice 保持空值并 REVIEW。
- 新增 `新增食物模组悬赏效果复审表.csv`（10 列）：34 个唯一 effect ID，21 个未确认
  进入 REVIEW，正负复合仍 REVIEW；正式因子仍限 NONE 1.00 / LIGHT 1.15 / STRONG 1.35 /
  NEGATIVE 0.85，未新增系数。

### 10.4 6E.0.3 生成结果

- Node 行为测试：`phase6e0.3 pricing preview tests: PASS`。
- 范围：170 = COMMON 38 + T2 132，T3 0。
- 可计算暂定价格：2 项（均为 PROVISIONAL 锚点）；DEFINED-only 价格 0；blocking 168 项。
- UNKNOWN_BUFF 84；MIXED_BUFF 1（blue_tenderloin_steak）；CONTAINER_REVIEW 16。
- 预览 170 行 × 21 列；缺口 1009 行 × 14 列；风险 170 行 × 9 列；锚点 107 行 × 14 列；
  效果 34 行 × 10 列。
- 两次内存生成、磁盘输出逐字节一致：
  - 预览：`2c3c03741a426e72ea3224e0ed8fa685147015de3cef1974f135526d18dea165`
  - 缺口：`e2a8058aeea3b3fd36ae7b3bc82b1eb2bd60ed7810efbd3c343c6d1c1075941a`
  - 风险：`39ec249c336f35516a1116898e3459f72d2028cb351b8c9efb12313dcaa47b5c`
  - 锚点：`95d8218e77858a5692e201c9d90e1e4d89bc1e7e4f3e27983a276b994339d3c4`
  - 效果：`a0f2442376ebf17defae656adb619884cd60c713dda0728e8908b5675b3723ec`
  - 摘要：`d170df1c1a628ca7d23d2f07093448055a0ec8c306f945cfc87bdee8f75de29b`
- 保护 manifest 前后不变：三个 food pool、`bounty_food_registry.js` 和正式菜品定价表
  SHA-256 均保持 6E.0.1 记录值。
- `git diff --check`：通过；当前工作区无服务端进程。

### 10.5 6E.0.3 分层结论

- **SCOPE PASS**
- **CALCULATION ENGINE PASS**
- **PRICE PROVENANCE PASS**
- **EFFECT AUDIT PASS**
- **ANCHOR ROLLUP PASS**
- **PROVISIONAL PRICES ONLY**（2 项暂定候选价格，PREVIEW ONLY）
- **RUNTIME EXPORT STALE**
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED**
- **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

本阶段完成后停止，等待复审；不进入 6E.1，不进行人工补价或正式池合并。

## 11. 6E.0.3.1 定价计算阻断修正

本轮只修改静态 Node 生成器、行为测试、预览输出和本报告；未修改正式定价表、
Bountiful pools/decree、KubeJS 正式配置、playerdata、TCTH Java 代码或 8E 工作区
文件，未启动服务器、未执行 `/reload`/`/syexport`、未部署、未在线测试，未
commit/push。

### 11.1 ItemStack 解析与输出计数

- 支持 `{id,count}`、`{item:"id",count}`、`{item:{id,count}}` 三种 schema；id 与 count
  必须来自同一个栈对象。
- 非有限、≤0、非整数数量整条候选 fail-closed（`results` 返回 null，配方不注册）。
- 服务器真实 JAR 配方输出计数回归全部通过：
  - `mynethersdelight:cutting/hoglin_sausage` = 2
  - `mynethersdelight:cutting/magma_cake`（magma_cake_slice）= 7
  - `mynethersdelight:cutting/slices_of_bread` = 5
  - `brewinandchewin:cutting/pizza`（pizza_slice）= 4
  - `brewinandchewin:cutting/quiche`（quiche_slice）= 4
  - `dungeonsdelight:cutting/monster_cake`（monster_cake_slice）= 7
  - `dungeonsdelight:cutting/slime_bar`（slime_noodles）= 2
  - `dungeonsdelight:cutting/spider_pie`（spider_pie_slice）= 4
- 以上配方均为 `farmersdelight:cutting` 类型且使用嵌套 `{item:{id,count}}` 结果 schema，
  修复前 count 会退化为 1。

### 11.2 多产物与成本分配

- `dungeonsdelight:cutting/slime_bar` 同时产出 `slime_noodles`×2 与 `farmersdelight:canvas`×1，
  属多产物（co-product）。没有明确成本分配规则时添加 `CO_PRODUCT_ALLOCATION_REVIEW`
  阻断风险并阻止正式价格，完整原料成本不会重复算给每个产物。

### 11.3 resolveCost 证据优先级

- DEFINED 直接锚点作为权威锚点直接返回。
- PROVISIONAL 直接价同时计算可用配方路径；优先 DEFINED-only 路径，再在 PROVISIONAL
  层级（直接价 + 配方路径）内比较最低 unitCost。
- REVIEW 来源 fail-closed：`directResult` 对 REVIEW 置信度返回 null，不得产生空风险
  可用成本。
- 真实回归：`minecraft:stick` 猜想直接价=1、`minecraft:bamboo` 已定义=2；`stick` 经
  `mynethersdelight:cutting/stick_bamboo`（1 竹 → 1 棍）选择 DEFINED 路径成本 2，
  不再使用猜想直接价 1。
- `tear_popsicle` 重算为 121（raw 82 = 冰40 + 恶魂之泪40 + 棍2，DEFINED 棍路径）；
  `amethyst_rock_candy` 仍为 63（amethyst_shard 无 DEFINED 配方路径，保持猜想锚点 18）。

### 11.4 来源分级修正

- `探索锚点` 由 DEFINED 改为 PROVISIONAL（`minecraft:dragon_breath` 备注“暂不进常驻
  悬赏，待玩家市场形成后再校准”，与分级一致）。
- 备注核对：`最便宜锚点`/`番茄/卷心菜锚点`/`对齐番茄`/`派生` 系列均指向可核验经济
  锚点，保持 DEFINED，无冲突。

### 11.5 6E.0.3.1 生成结果

- Node 行为测试：`phase6e0.3 pricing preview tests: PASS`。
- 范围：170 = COMMON 38 + T2 132，T3 0。
- 可计算暂定价格：2 项（tear_popsicle=121、amethyst_rock_candy=63，均为 PROVISIONAL
  锚点）；DEFINED-only 0；blocking 168。
- 预览 170 行 × 21 列；缺口 1247 行 × 14 列（因 PROVISIONAL 直接价同时展开配方路径，
  缺口记录更完整）；风险 170 行 × 9 列；锚点 121 行 × 14 列；效果 34 行 × 10 列
  （与 6E.0.3 一致）。
- 两次内存生成、磁盘输出逐字节一致：
  - 预览：`da1b75c3ccd2765f2c56dc08af15204a7701a802c298ee5744e18796140038c4`
  - 缺口：`2555d7773055301e1ec548db03711c176199dcacbd7dc017579d392c29fa296b`
  - 风险：`9c5591da0798d66860d448c9109b850b887542dba2da2ad283de1b11f9666e8d`
  - 锚点：`09a3389205f841261338826f097250ca2c63a9f8476d0cd54154d155dacdf28a`
  - 效果：`a0f2442376ebf17defae656adb619884cd60c713dda0728e8908b5675b3723ec`
  - 摘要：`7f792f6994aa98948c06f6c0018c43b5fc0ec59a99f2ce4843ee86a418a924f3`
- 保护 manifest 前后不变：三个 food pool、`bounty_food_registry.js` 和正式菜品定价表
  SHA-256 均保持 6E.0.1 记录值。
- `git diff --check`：通过；当前工作区无服务端进程。

### 11.6 6E.0.3.1 分层结论

- **SCOPE PASS**
- **CALCULATION ENGINE PASS**（ItemStack 解析、多产物分配、证据优先级）
- **PRICE PROVENANCE PASS**
- **EFFECT AUDIT PASS**
- **ANCHOR ROLLUP PASS**
- **PROVISIONAL PRICES ONLY**（2 项暂定候选价格，PREVIEW ONLY）
- **RUNTIME EXPORT STALE**
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED**
- **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

本阶段完成后停止，等待复审；不进入 6E.1，不进行人工补价或正式池合并。

## 12. 6E.0.3.2 配方数量与概率输出最终收口

本轮只修改静态 Node 生成器、行为测试、预览输出和本报告；未修改正式定价表、
Bountiful pools/decree、KubeJS 正式配置、playerdata、TCTH Java 代码或 8E 工作区
文件，未启动服务器、未执行 `/reload`/`/syexport`、未部署、未在线测试，未
commit/push。

### 12.1 原料槽严格 fail-closed

- 原料槽区分 VALID / INVALID / EMPTY；真正不存在的可选字段不产生槽（不计数），
  声明过但无法解析、ID 非法、count 非有限/≤0/非整数的原料整条候选添加
  `INVALID_INGREDIENT_STACK` 并禁止 unitCost，不再过滤后继续计算。
- 支持 `neoforge:compound` 原料展开为备选槽（powdery_furniture 的木制品族、
  sculk 切割配方等）；`neoforge:difference` 与 create `fluid_tag` 等未支持声明类型
  一律 INVALID fail-closed。
- 真实 JAR 统计：14 个配方含非法原料槽（create 流体/差集组合配方），16 个配方为
  概率输出，26 个配方为多产物；既有 8 个输出计数回归全部保持。

### 12.2 概率输出

- `parseStack`/`results` 保留 `chance`；缺失视为 guaranteed（chance=null）；
  chance 必须 finite 且 0<chance≤1，否则整条候选 fail-closed。
- 任一 chance<1 输出默认添加 `PROBABILISTIC_OUTPUT_REVIEW` 并阻止正式定价，不擅自
  用期望值折算。
- 同 ID 的 guaranteed + probabilistic 输出不合并为保证数量（如 blaze_powder 3 保底
  + 1 个 25% 额外，分别注册、均被阻断）。
- 真实 JAR 回归：`balze_rod`（blaze_powder 3 保底 + 25% 额外）、`bullet_pepper`
  （pepper_powder 1 保底 + 25% 额外）、`powdery_furniture`（仅 75% 产出）、`slime_bar`
  多产物；同时存在共产品与概率输出的配方两种风险均保留（如 brined_flesh、
  pumpkin_slice_alt、strider_egg 等）。

### 12.3 备选槽总成本同标同算

- 每个备选项计算 `totalSlotCost = resolved.unitCost × choice.count × slot.occurrence`；
  按证据等级（DEFINED 先于 PROVISIONAL）→ totalSlotCost → dependencyId 确定性排序。
- 选择与结算使用同一个 totalSlotCost：测试 A×10 单价 1 与 B×1 单价 2，必须选 B 且
  成本 2；DEFINED 备选即使更贵也优先于 PROVISIONAL 备选。

### 12.4 6E.0.3.2 生成结果

- Node 行为测试：`phase6e0.3 pricing preview tests: PASS`。
- 范围：170 = COMMON 38 + T2 132，T3 0。
- 可计算暂定价格：3 项（tear_popsicle=121、amethyst_rock_candy=63、
  sculk_apple=123；均为 PROVISIONAL 锚点）；DEFINED-only 0；blocking 167。
  `sculk_apple` 由 compound 原料展开修复解锁（sculk 切割配方经 sculk=28 缺省猜想
  锚点闭合，buff 为空取 NONE 1.0），非 Buff 误判。
- 预览 170 行 × 21 列；缺口 1282 行 × 14 列；风险 170 行 × 9 列；锚点 121 行 × 14 列；
  效果 34 行 × 10 列（不变）。
- 两次内存生成、磁盘输出逐字节一致：
  - 预览：`d0017be7bd6c1c83ce71c01573635d47bed0e988c30c06dc3e944ff954fd2004`
  - 缺口：`6de162c093d6a6e901d5432a5485c9c40561a3184f6bd1ca3362f94fb3363aa0`
  - 风险：`124303b7a68d38eba8e44063fcd9b472d3c340184199cb55bb7fa7b8fe492812`
  - 锚点：`82a5c01279ef0c9d349ff0f03dfbf9043f8325bb31c2620a9e497ef53ae81ffe`
  - 效果：`a0f2442376ebf17defae656adb619884cd60c713dda0728e8908b5675b3723ec`
  - 摘要：`60f038f781f97705cc964bdba7201750f0af02980b48da3aa6893e7c402c93ea`
- 保护 manifest 前后不变：三个 food pool、`bounty_food_registry.js` 和正式菜品定价表
  SHA-256 均保持 6E.0.1 记录值。
- `git diff --check`：通过；当前工作区无服务端进程。

### 12.5 6E.0.3.2 分层结论

- **SCOPE PASS**
- **CALCULATION ENGINE PASS**（fail-closed 槽、概率输出、同标同算）
- **PRICE PROVENANCE PASS**
- **EFFECT AUDIT PASS**
- **ANCHOR ROLLUP PASS**
- **PROVISIONAL PRICES ONLY**（3 项暂定候选价格，PREVIEW ONLY）
- **RUNTIME EXPORT STALE**
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED**
- **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

本阶段完成后停止，等待复审；不进入 6E.1。本阶段复审通过后，下一步进入 6E.0.4
原料锚点人工定价，仍不直接进入正式池合并。

## 13. 6E.0.4 原料锚点人工定价

本轮新增人工决策源并接入生成器，仅修改生成器、测试、预览输出和本报告；未修改
正式定价表、原料单价参考表、Bountiful pools/decree、KubeJS 正式配置、playerdata、
TCTH Java 代码或 8E 工作区文件，未启动服务器、未执行 `/reload`/`/syexport`、未部署、
未在线测试，未 commit/push。

### 13.1 人工决策源

- 新增 `新增食物模组悬赏原料锚点人工定价表.csv`：121 行，每个 6E.0.3.2 锚点恰好
  一行（dependencyId+dependencyKind 唯一），15 列含决策、建议价、证据、可再生性、
  获取难度、种植风险、经济风险、理由与状态。生成器只读加载，永不覆盖。
- 决策分布：DEFINED_CANDIDATE 1 / PROVISIONAL 4 / REVIEW 109 / EXCLUDED 7。
- DEFINED_CANDIDATE：`minecraft:blaze_powder=25`（证据：原料单价参考表 ender_eye=45
  配方派生=末影珍珠20+烈焰粉25，反解烈焰粉=25，派生价内部一致）。
- PROVISIONAL（明确推导 + 风险）：`bone_block=72`（9×骨粉8 缺省猜想）、
  `magma_cream=33`（粘液球8+烈焰粉25）、`rotten_flesh=2`（既有猜想，僵尸可再生）、
  `glass_bottle=2`（既有猜想，仅 ITEM 原料；CONTAINER 行仍阻断）。
- EXCLUDED（运营排除，不补价）：echo_shard、wind_charge、sniffer_egg、
  calibrated_sculk_sensor、dungeonsdelight:ancient_egg、hoglin_trophy、zoglin_trophy
  （探索/头目/机制专属，无稳定市场价格）。
- TAG 成员无法从静态 JAR 核验（`c:` 标签为运行时约定），38 个 TAG 全部 REVIEW；
  CONTAINER 未确认消耗/返还语义，9 个全部 REVIEW（继续 BLOCKED）。未用最终料理售价
  反推原料价，未强行补价。

### 13.2 生成器接入

- 只读加载人工表，DEFINED_CANDIDATE→DEFINED、PROVISIONAL→PROVISIONAL 覆盖旧表；
  REVIEW/EXCLUDED 不产生价格。人工表通过 `validateManualTable` fail-fast 校验
  （空/非法/重复 ID、路径穿越、非整数/非正价格、REVIEW 携带价格、非法决策）。
- 优先级：人工 DEFINED > 旧表 DEFINED > 人工 PROVISIONAL > 旧表 PROVISIONAL >
  DEFINED 配方路径 > PROVISIONAL 配方路径 > 缺价。人工锚点进入完整 evidenceChain，
  标注 `人工定价(<决策>):<证据类型>`。
- 既有阻断规则不变：INVALID_INGREDIENT_STACK、PROBABILISTIC_OUTPUT_REVIEW、
  CO_PRODUCT_ALLOCATION_REVIEW、CONTAINER_SEMANTICS_REVIEW；runtimeStatus 保持 STALE。

### 13.3 自动验证

- `phase6e0.4 pricing preview tests: PASS`：121 行唯一、决策枚举、proposedPrice 正整数、
  校验器 fail-fast、REVIEW/EXCLUDED 不入计算、DEFINED>PROVISIONAL、人工表不被覆盖
  （哈希前后一致）、连续两次逐字节一致。
- 三个既有暂定价格无漂移：tear_popsicle=121、amethyst_rock_candy=63、sculk_apple=123
  （其原料锚点不在人工表内，证据链完整）。

### 13.4 6E.0.4 生成结果

- 范围：170 = COMMON 38 + T2 132，T3 0。
- 可计算暂定价格：3 项（全 PROVISIONAL）；DEFINED-only 0；blocking 167。
- 因人工锚点新可计算料理：0（人工锚点已闭合 blaze_powder/bone_block/magma_cream 等
  缺口，但相关料理仍被标签/容器/概率/共产品/效果或循环阻断）。
- 仍阻断（同一料理可多类）：缺价/缺标签 158、容器 21、概率 13、共产品 24、
  效果 85、循环 65、非法原料 8、零成本 112。
- 预览 170×21；缺口 1267×14；风险 170×9；锚点 118×14（3 个已人工定价锚点离开缺口
  集合）；效果 34×10；未决项 116×6（REVIEW 109 + EXCLUDED 7）。
- 两次内存生成、磁盘输出逐字节一致：
  - 预览：`8311555a175f1f631d2fcd5c567c2f86aa93ac6b510668938b208c39701f6b42`
  - 缺口：`b771ebaa1db83577b2252f2abe03b39b890e4370069955ce0cf805371f73f410`
  - 风险：`080b507747f997cae6340036f131b53e9eac6b1bae2d3a2b48e9b00daced0703`
  - 锚点：`22a60e44bc1fde62e4d87766a94898244f1a6c43521d8ebf7995362ac06f0963`
  - 效果：`a0f2442376ebf17defae656adb619884cd60c713dda0728e8908b5675b3723ec`
  - 未决项：`fa51956ea66c3df05e8197b925478225f1fcef52ffd1c026bf14e6219e2beb61`
  - 覆盖摘要：`4ca0eb8e07a1dc2f8d2a36a3432bb81f579db4d0e26bf64db49a2c7e6c8abfb0`
  - 摘要：`6c260f6389a52bbf571d07b157c077017417f3edce1bf1d0ca6552d881fdd8a1`
- 人工定价表 SHA-256：`f4ce7790eec9946a82b1b38d91f4ba9aa79eda62a4b695eb7bcc9033ec8d8e8c`。
- 保护 manifest 前后不变：三个 food pool、`bounty_food_registry.js`、正式菜品定价表
  及原料单价参考表 SHA-256 均保持原值。
- `git diff --check`：通过；当前工作区无服务端进程。

### 13.5 6E.0.4 分层结论

- **SCOPE PASS**
- **MANUAL ANCHOR INPUT ESTABLISHED**（121 行只读人工决策源）
- **CALCULATION ENGINE PASS**（人工优先级与阻断规则不变）
- **PRICE PROVENANCE PASS**
- **EFFECT AUDIT PASS**
- **ANCHOR ROLLUP PASS**
- **PROVISIONAL PRICES ONLY**（3 项暂定候选价格，PREVIEW ONLY；因人工锚点新可计算 0）
- **RUNTIME EXPORT STALE**
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED**
- **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

> 本节为 6E.0.4 初版结论，其中 **CALCULATION ENGINE PASS** 已被 6E.0.4.1
> 修订取代（blaze_powder 循环反解不能升级为 DEFINED；人工价格合并存在旧 DEFINED 被
> 人工 PROVISIONAL 降级覆盖的缺陷；NEW_COMPUTABLE 曾用 evidenceChain 字符串判断而非
> 真实集合差分）。当前结论以 §13.6 为准，不再保留本节的 CALCULATION ENGINE PASS 为
> 有效结论。

## 13.6 6E.0.4.1 修订结论

本轮只修改生成器、测试、人工定价表（blaze_powder 决策修订）、预览输出和本报告；
未修改正式定价表、原料单价参考表、Bountiful pools/decree、KubeJS 正式配置、
playerdata、TCTH Java 代码或 8E 工作区文件，未启动服务器、未执行 `/reload`/
`/syexport`、未部署、未在线测试，未 commit/push。

### 13.6.1 阻断修正

- 生产路径强制 `loadManualTableStrict`：精确表头与 15 列结构、dependencyKind 仅
  ITEM/TAG/CONTAINER、DEFINED_CANDIDATE/PROVISIONAL 必须有价格/证据/理由/风险/状态、
  REVIEW/EXCLUDED 禁止携带价格；任一错误立即抛出，禁止生成部分输出。
- 人工价格合并规则显式化（`mergeManualPrices`）：
  - 人工 DEFINED_CANDIDATE 可覆盖旧价（含旧 DEFINED）；
  - 旧表 DEFINED 不被人工 PROVISIONAL 降级覆盖；
  - 人工 PROVISIONAL 覆盖旧表 PROVISIONAL；
  - DEFINED 配方路径优先于一切 PROVISIONAL 路径（resolveCost 既有行为，测试固化）。
- `minecraft:blaze_powder` 由 DEFINED_CANDIDATE 改为 PROVISIONAL：ender_eye=45 由
  ender_pearl=20(猜想)+blaze_powder=25(猜想) 派生，反解属循环证明，不能升级为
  DEFINED；`magma_cream=33` 继续保持 PROVISIONAL。人工分布更新为
  DEFINED_CANDIDATE 0 / PROVISIONAL 5 / REVIEW 109 / EXCLUDED 7。
- 真实双基线：basePrices（仅原料单价参考表）与 mergedPrices（+合法人工决策）共用
  配方/目标/Buff/阻断规则，分别求值 170 项；
  NEW_COMPUTABLE = mergedPriced − basePriced（集合差分），不再用 evidenceChain 字符串。
- 人工表键集与基线锚点集（121 个 dependencyId+dependencyKind）强制完全相等；缺失、
  多余或替换成任意合法 ID 均 fail-fast（`checkManualCoverage`），非仅断言 121 行。

### 13.6.2 自动验证

- `phase6e0.4 pricing preview tests: PASS`：生产入口非法人工表直接失败、
  人工 PROVISIONAL 不覆盖旧 DEFINED、人工 DEFINED_CANDIDATE 可覆盖、
  DEFINED 配方路径优先于人工 PROVISIONAL、blaze_powder 保持 PROVISIONAL 风险、
  NEW_COMPUTABLE 集合差分一致、人工表缺/多/替换一行均失败、人工表不被覆盖、
  连续两次逐字节一致。
- 基线可计算 3 项 = 合并后可计算 3 项 → 真实 NEW_COMPUTABLE = 0。

### 13.6.3 6E.0.4.1 生成结果

- 范围：170 = COMMON 38 + T2 132，T3 0。
- 可计算暂定价格：3 项（全 PROVISIONAL，tear_popsicle=121、amethyst_rock_candy=63、
  sculk_apple=123，无漂移）；DEFINED-only 0；blocking 167。
- 真实 NEW_COMPUTABLE：0（基线 3 项，合并后仍 3 项）。
- 预览 170×21；缺口 1268×14；风险 170×9；锚点 119×14；效果 34×10；未决项 116×6。
- 两次内存生成、磁盘输出逐字节一致：
  - 预览：`13245857b5284f0d936f2f23350c6c8dd004c60192f3e02d7c593b6907887149`
  - 缺口：`2a6cb2f30a3212b6832b016b85ff191f5b9a1e688c0e48dadf28b77619f64ab1`
  - 风险：`41b09f81506342b117aa9ad707f0b1c23dec603f53d49b6a974e267991bbf6b1`
  - 锚点：`765aaa995e387cd32fc14dcb39a0621240a7ca92119da6f6c79ea2d76acd9ded`
  - 效果：`a0f2442376ebf17defae656adb619884cd60c713dda0728e8908b5675b3723ec`
  - 未决项：`fa51956ea66c3df05e8197b925478225f1fcef52ffd1c026bf14e6219e2beb61`
  - 覆盖摘要：`39a39bb27ee01ac99bc856679f354c2432b5044d9ea80a17b28b82b6aea8d361`
  - 摘要：`d77c8cb0b84ff33628666458c747e2d53aaf9f7aa80d43c158ebeb3a3e2da1a9`
- 人工定价表 SHA-256：`3b789bb58fc6e67fada1f43adc5ad0a979e1f4d972a58779c128c0a20f82570e`。
- 保护 manifest 前后不变：三个 food pool、`bounty_food_registry.js`、正式菜品定价表
  及原料单价参考表 SHA-256 均保持原值。
- `git diff --check`：通过；当前工作区无服务端进程。

### 13.6.4 分层结论（当前有效）

- **SCOPE PASS**
- **MANUAL ANCHOR INPUT ESTABLISHED**（121 行只读人工决策源，与基线锚点集完全一致）
- **CALCULATION ENGINE PASS**（经 6E.0.4.1 修订后有效）
- **PRICE MERGE PASS**（人工/旧价/配方路径优先级显式化）
- **PRICE PROVENANCE PASS**
- **EFFECT AUDIT PASS**
- **ANCHOR ROLLUP PASS**
- **PROVISIONAL PRICES ONLY**（3 项暂定候选价格，PREVIEW ONLY；真实 NEW_COMPUTABLE 0）
- **RUNTIME EXPORT STALE**
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED**
- **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

本阶段完成后停止，等待复审；不进入 6E.1，不把人工价格写入正式经济配置。

## 14. 6E.0.5 运行时证据与定价阻断收口

本轮只修改静态 Node 生成器、测试、人工定价表（容器行证据更新）、预览输出和本报告；
未修改正式定价表、原料单价参考表、Bountiful pools/decree、KubeJS 正式配置、
playerdata、TCTH Java 代码或 8E 工作区文件，未部署、未在线测试，未 commit/push。

### 14.1 环境保护与 RUNTIME EXPORT

- 进程核查：另一生产服务端 `/Users/a1111/Desktop/Mc_Server_0.1/Minecraft-Server/Server`
  （PID 96113/96118，run.sh nogui + neoforge 21.1.247）正在运行，端口 19764；当前工作区
  端口 25565 空闲、世界目录独立，无端口/世界锁冲突。
- **决策：未启动当前服务端**。同机正运行另一生产服务端，再启动一个完整 NeoForge
  服务端存在资源争用风险；运行时导出脚本未经线上验证。按"先完成静态审计并将运行时
  部分标为 BLOCKED"执行。
- **RUNTIME EXPORT BLOCKED**：RecipeManager 当前有效配方、TAG 实际成员、效果注册表/
  MobEffectCategory 运行时确认、启用数据包清单、UNITE 覆盖后最终状态均未能运行时
  导出；`food_recipe_export.json` 维持 STALE。本轮未修改 `/syexport` 脚本（无启动即
  无法验证，留待服务端停机窗口由运营执行）。

### 14.2 容器语义（静态 recipe JSON 证据）

- 对 9 个 CONTAINER 锚点逐项核验真实 recipe JSON 的 `container` 字段与设备类型：
  均为烹饪/发酵/搅拌设备的交付容器（如 apple_jelly 玻璃瓶、碗装炖菜、面包包裹、
  hotdog 面包胚等），容器随交付物一并给出、不单独返还 → **PART_OF_RESULT**。
- 新增《新增食物模组悬赏容器语义决策表.csv》（9 行）：设备、recipeId、输入容器、
  交付物、返还路径、证据、决定。9 个全部判定 PART_OF_RESULT（计入成本）。
- 结果：CONTAINER_REVIEW 阻断 21 → 0；有价格的容器（glass_bottle 2、bowl 2 等）成本
  现被正确计入。

### 14.3 效果分类（javap MobEffectCategory 证据）

- 对 21 个未知效果逐项 javap 安装 JAR 的注册类与效果类：
  - BENEFICIAL→LIGHT：bakeries:enjoy/cheese_power/cocoa_mania、dungeonsdelight:ravenous_rush、
    mynethersdelight:g_pungent、neapolitan:vanilla_scent/sugar_rush/berserking/harmony
  - HARMFUL→NEGATIVE：dungeonsdelight:putrid_scent、mynethersdelight:b_pungent
  - NEUTRAL→REVIEW：dungeonsdelight:exudation/pouncing/swift_step/rotgut/decisive/voracity/
    tenacity/burrow_gut、neapolitan:agility、minecraft:levitation（共 10，正负未定禁猜价）
- 证据为安装 JAR 字节码（MobEffectCategory 构造参数），非名称猜测；未新增系数。
- 效果复审表更新：evidenceSource 记录 `javap(MobEffectCategory.X)`；UNIQUE_UNKNOWN_EFFECTS
  21 → 10（仅 NEUTRAL 保持 REVIEW）。

### 14.4 TAG 审计

- 新增《新增食物模组悬赏Tag成员审计表.csv》（38 行）：runtimeMemberCount=UNKNOWN、
  成员/替换来源/数据包来源=UNKNOWN（RUNTIME_BLOCKED），38 个 TAG 全部建议 REVIEW，
  不凭标签名称猜价。`c:` 标签成员为运行时约定，静态 JAR 无定义文件可核验。

### 14.5 第二轮人工定价

- 人工定价表保持 121 行（0/5/109/7）；9 个 CONTAINER 行证据更新为"recipe JSON
  container 字段 + 设备行为 → PART_OF_RESULT，成本按旧价表计入"，决策不变
  （容器不设人工价）。
- 基线/合并双基线仍隔离；NEW_COMPUTABLE 使用真实集合差分。

### 14.6 6E.0.5 生成结果

- Node 行为测试：`phase6e0.4 pricing preview tests: PASS`。
- 范围：170 = COMMON 38 + T2 132，T3 0。
- 可计算暂定价格：7 项（全 PROVISIONAL）：apple_jelly=40、glow_berry_marmalade=55、
  sweet_berry_jam=40、amethyst_rock_candy=63、sculk_apple=123、burnt_roll=37、
  tear_popsicle=121。DEFINED-only 0；blocking 163。
- 基线（basePrices、无容器语义）可计算 3；合并后可计算 7；真实 NEW_COMPUTABLE = 4
  （apple_jelly、glow_berry_marmalade、sweet_berry_jam、burnt_roll）。
- 容器：已确认 9/9 PART_OF_RESULT，仍 REVIEW 0；CONTAINER_REVIEW 阻断 0。
- 效果：已确认（javap）21，仍 REVIEW 10（NEUTRAL）；MIXED_BUFF 9、UNKNOWN_BUFF 36。
- TAG：已核验 0，仍 REVIEW 38。
- 预览 170×21；缺口 1241×14；风险 170×9；锚点 113×14；效果 34×10；容器 9×7；
  TAG 38×11；未决项 116×6。
- 两次内存生成、磁盘输出逐字节一致：
  - 预览：`e66165af89a06c80d28eca9118f6658ec40a5c0241d3ef68d2170a3f4b6c9cd2`
  - 缺口：`867b32b5e3410d6c86c486eafb57083cf328dd907208a84a5875de29145eb807`
  - 风险：`bbf54c75b74630bf5286b1732c91398ae3975e8264a11b9a4fe5987881f6ab75`
  - 锚点：`66fae432ff7a138a4cf866274fcd0d20ccf660cef59c69ab586fe1c5106a6b9d`
  - 效果：`c473495bf5da2f835e54d085bc0ae63ec1620c7bfc4351c46bfff1d4c0ba5057`
  - 容器语义：`848890fc599c9cf7220e8b3e9e835ead4dff745d1e894e319b862128e95ede37`
  - TAG 审计：`8d6a7913a51ddeb14061ad36be15bf35dcb0a8fff2b3fe1efb6135c257ac1d55`
  - 未决项：`3baba3f772bbb9da205dc6930f25c0f346797302a8bca8783bf2e31909315c40`
  - 覆盖摘要：`165c3da1d374f628d91990ae84cb41bbbf3b39f54b5bf7431cc6bd941e99c4bf`
  - 摘要：`f6234f0eb6c736273450133f657b2baaa439d0e728c7b55f696fea0d3c447e0e`
- 人工定价表 SHA-256：`5883871db51c14e0f0204cb64da3ed457bb2503a088c218081e16f795c70272b`。
- 保护 manifest 前后不变：三个 food pool、`bounty_food_registry.js`、正式菜品定价表
  及原料单价参考表 SHA-256 均保持原值。
- `git diff --check`：通过；当前工作区无服务端进程（未启动）。

### 14.7 6E.0.5 分层结论

- **SCOPE PASS**
- **RUNTIME EXPORT BLOCKED**（同机另一生产服务端运行中，未启动当前服务端）
- **TAG AUDIT**：已核验 0 / 仍 REVIEW 38（成员需运行时）
- **CONTAINER SEMANTICS**：已确认 9 / 仍 REVIEW 0（recipe JSON 证据）
- **EFFECT AUDIT**：已确认 21 / 仍 REVIEW 10（NEUTRAL，javap 证据）
- **MANUAL ANCHORS**：DEFINED_CANDIDATE 0 / PROVISIONAL 5 / REVIEW 109 / EXCLUDED 7
- **CALCULATION ENGINE PASS**（容器/效果证据接入，阻断规则不变）
- **PRICE PROVENANCE PASS**（baseline 3、merged 7、NEW_COMPUTABLE 4，集合差分）
- **PROVISIONAL PRICES ONLY**（7 项暂定候选价格，PREVIEW ONLY）
- **RUNTIME EXPORT STALE**
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED**
- **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

本阶段完成后停止，等待复审；不进入 6E.1。运行时导出需在服务端停机窗口由运营执行，
TAG 成员与 UNITE 最终状态仍为 UNKNOWN。

## 15. 6E.0.5.1 静态阻断修正

> 本节修订取代 §14（6E.0.5）。6E.0.5 的容器覆盖统计存在魔法索引错误、效果
> "BENEFICIAL→LIGHT" 自动规则不严谨（未结合行为证据、berserking 全 ID/短名矛盾）、
> 容器语义按 container ID 作用域过宽等缺陷，均在本节修正。当前结论以 §15 为准。

本轮只修改静态 Node 生成器、测试、预览输出和本报告；未启动任何服务端，未修改正式
定价表、原料单价参考表、Bountiful pools/decree、KubeJS 正式配置、playerdata、TCTH
Java 代码或 8E 工作区文件，未部署、未在线测试，未 commit/push。

### 15.1 容器覆盖摘要统计修复

- 原 `buildContainerSemanticsTable` 决定字段位于索引 6，但覆盖摘要错误使用 `r[3]`
  （交付物列），为魔法索引缺陷。已改为命名对象 `{device, recipeId, containerId,
  resultItem, returnPath, evidence, decision}`，覆盖摘要按 `decision` 字段统计。
- 测试断言覆盖摘要明确包含"已确认 9 个"且不出现"已确认 0 个"。

### 15.2 撤回 BENEFICIAL→LIGHT 自动规则（行为证据定档）

- MobEffectCategory 仅作方向证据（positive/negative/neutral）；定价强度由安装 JAR
  javap 行为证据单独定档。单一权威映射 BUFF_BY_FULL_ID，与旧短名一致：
  - STRONG：bakeries:enjoy（回血+移除负面）、bakeries:cheese_power（ATTACK_DAMAGE）、
    dungeonsdelight:ravenous_rush（MOVEMENT_SPEED+0.3 / ATTACK_SPEED+0.1）、
    neapolitan:sugar_rush（MOVEMENT_SPEED / BLOCK_BREAK_SPEED）、neapolitan:berserking
    （ARMOR / ATTACK_DAMAGE，全 ID 与短名一致为 STRONG）
  - LIGHT：bakeries:cocoa_mania（ATTACK_SPEED，类急迫）
  - NEGATIVE：dungeonsdelight:putrid_scent、mynethersdelight:b_pungent
  - REVIEW（行为证据不足，BENEFICIAL 仅方向）：mynethersdelight:g_pungent（复杂火防
    transformEffect 强度不明）、neapolitan:vanilla_scent、neapolitan:harmony（无属性/
    tick 证据）
- BUFF_BY_SHORT 移除 `agility`（全 ID neapolitan:agility=NEUTRAL REVIEW，避免矛盾）；
  保留 `berserking: STRONG` 与全 ID 一致。未新增系数。

### 15.3 容器语义加固

- 语义按 recipeId 作用域（`evidence.get(recipeId)`），不再按 container item ID。
- 枚举全部容器配方（37 条，dedupe by recipeId+containerId），记录设备、recipeId、
  输入容器、交付物、返还路径、证据、决定；烹饪/发酵/搅拌设备 container 字段 →
  PART_OF_RESULT，其余 REVIEW。
- `containerDecision` 仅 DEFINED/PROVISIONAL 置信度价格计入；REVIEW/非法置信度价格
  阻断（新增 `PRICE_SOURCE_REVIEW` 阻断风险）。
- 保留 RUNTIME EXPORT BLOCKED 声明，静态 JAR 配方不称 RecipeManager 权威。

### 15.4 6E.0.5.1 生成结果

- Node 行为测试：`phase6e0.5.1 pricing preview tests: PASS`。
- 范围：170 = COMMON 38 + T2 132，T3 0。
- 可计算暂定价格：7 项（全 PROVISIONAL）：apple_jelly=40、glow_berry_marmalade=55、
  sweet_berry_jam=40、amethyst_rock_candy=63、sculk_apple=123、burnt_roll=37、
  tear_popsicle=121。DEFINED-only 0；blocking 163。
- 基线 3 / 合并 7 / NEW_COMPUTABLE 4（集合差分）。
- 容器锚点：已确认 9/9（PART_OF_RESULT），仍 REVIEW 0。
- 效果：已确认 21 / 仍 REVIEW 13（NEUTRAL 10 + g_pungent/vanilla_scent/harmony）。
- TAG：38 个全部 REVIEW（RUNTIME EXPORT BLOCKED）。
- 预览 170×21；缺口 1241×14；风险 170×9；锚点 113×14；效果 34×10；容器 37×7；
  TAG 38×11；未决项 116×6。
- 两次内存生成、磁盘输出逐字节一致：
  - 预览：`47584dab2a82fe2fa45d0934a27c7fe165844117e744ac08cd52c78670e38e94`
  - 缺口：`867b32b5e3410d6c86c486eafb57083cf328dd907208a84a5875de29145eb807`
  - 风险：`dbbd2ab208da30e9b8b94de1dd84aaae53c5149bc3d74350d98640a798ef14cd`
  - 锚点：`66fae432ff7a138a4cf866274fcd0d20ccf660cef59c69ab586fe1c5106a6b9d`
  - 效果：`02380a1a08b9ff762c55077300528c211654e6297c7766bc986b6326dca01cca`
  - 容器语义：`28a79ea586e9c26062fd6536a0168c2795d5fbcb80d753e2400b008bd5182939`
  - TAG 审计：`8d6a7913a51ddeb14061ad36be15bf35dcb0a8fff2b3fe1efb6135c257ac1d55`
  - 未决项：`3baba3f772bbb9da205dc6930f25c0f346797302a8bca8783bf2e31909315c40`
  - 覆盖摘要：`f62b2c243eab907efa6d18a06be17d5b8275cd8ef3b602f9ae527ed13eebc397`
  - 摘要：`bc6ed50530834afc29b5639795a9d0cdd2842350c38b81dc388f73e7beb0aa6d`
- 人工定价表 SHA-256：`5883871db51c14e0f0204cb64da3ed457bb2503a088c218081e16f795c70272b`。
- 保护 manifest 前后不变：三个 food pool、`bounty_food_registry.js`、正式菜品定价表
  及原料单价参考表 SHA-256 均保持原值。
- `git diff --check`：通过；当前工作区无服务端进程（未启动）。

### 15.5 6E.0.5.1 分层结论

- **SCOPE PASS**
- **RUNTIME EXPORT BLOCKED**（同机另一生产服务端运行中，未启动当前服务端）
- **CONTAINER SUMMARY FIXED**（命名对象，摘要断言"已确认 9 个"）
- **CONTAINER SEMANTICS**：已确认 9/9（PART_OF_RESULT），recipeId 作用域 + 置信度硬阻断
- **EFFECT AUDIT**：撤回 BENEFICIAL→LIGHT；行为证据定档，已确认 21 / 仍 REVIEW 13
- **TAG AUDIT**：已核验 0 / 仍 REVIEW 38（成员需运行时）
- **MANUAL ANCHORS**：DEFINED_CANDIDATE 0 / PROVISIONAL 5 / REVIEW 109 / EXCLUDED 7
- **CALCULATION ENGINE PASS**
- **PRICE PROVENANCE PASS**（baseline 3、merged 7、NEW_COMPUTABLE 4，集合差分）
- **PROVISIONAL PRICES ONLY**（7 项暂定候选价格，PREVIEW ONLY）
- **RUNTIME EXPORT STALE** / **RUNTIME RECIPES NOT VERIFIED**
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED** / **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

本阶段完成后停止，等待复审；不进入 6E.1。如实说明：runtime export、TAG 实际成员、
数据包清单、UNITE 覆盖后最终状态仍未验证（RUNTIME EXPORT BLOCKED）。

## 16. 6E.0.6 运行时权威导出（RUNTIME BLOCKED）

以 §15（6E.0.5.1）为静态基线。本轮只修改静态 Node 生成器、测试、预览输出和本报告；
未启动任何服务端、未执行 `/reload`/`/syexport`，未修改正式定价表、原料单价参考表、
Bountiful pools/decree、KubeJS 正式配置、playerdata、TCTH Java 代码或 8E 工作区文件，
未部署、未在线测试，未 commit/push。

### 16.1 启动前检查与决策

- 进程核查：另一生产服务端 `/Users/a1111/Desktop/Mc_Server_0.1/Minecraft-Server/Server`
  正在运行（run.sh PID 2061 → java PID 2066，RSS 1.9GB，端口 19764 监听，持有 world/
  session.lock）；当前工作区端口 25565 空闲、世界锁无占用、无本工作区 Java 进程。
- 资源：总内存 32GB，system-wide 空闲约 50%（约 16GB），另一服务端仅占 1.9GB——资源
  本身充足，不存在"资源不足"。
- **决策：仍不启动当前服务端**。另一生产服务端确在运行且玩家可能在线，同机再启动
  完整 NeoForge 服务端在其加载期会造成 CPU/内存争用，不属于"安全停机窗口"。按任务
  守则"仅在安全停机窗口启动"与"不得触碰其他副本"执行，运行时部分标记 BLOCKED，
  待运营在真实停机窗口执行导出。
- 未备份/刷新 `food_recipe_export.json`（维持 STALE）；未修改 `/syexport`。

### 16.2 静态收口（不依赖运行时）

- 新增 `computeRecipeClosure`：计算 170 项目标料理的递归配方依赖闭包。
- 容器语义表按闭包标记 scopeStatus：IN_SCOPE 20 / OUT_OF_SCOPE 17（共 37 条）；
  9 个容器锚点全部 IN_SCOPE。已确认无返还路径写 `returnPath=NONE`（37/37）。
- 所有静态配方 `runtimeStatus=UNKNOWN`：运行时无效或被高优先级覆盖的静态配方无法
  静态确认，不得标记 INACTIVE（需运行时覆盖证据）。
- 运行时配方/TAG 成员/数据包清单/UNITE 最终状态：UNKNOWN，未能导出。

### 16.3 6E.0.6 生成结果（静态基线不变）

- Node 行为测试：`phase6e0.5.1 pricing preview tests: PASS`。
- 范围：170 = COMMON 38 + T2 132，T3 0。
- 可计算暂定价格：7 项（全 PROVISIONAL）：apple_jelly=40、glow_berry_marmalade=55、
  sweet_berry_jam=40、amethyst_rock_candy=63、sculk_apple=123、burnt_roll=37、
  tear_popsicle=121。DEFINED-only 0；blocking 163。
- 基线 3 / 合并 7 / NEW_COMPUTABLE 4（集合差分）。
- 容器锚点：已确认 9/9（PART_OF_RESULT）；效果已确认 21 / 仍 REVIEW 13；
  TAG 38 全部 REVIEW。
- 预览 170×21；缺口 1241×14；风险 170×9；锚点 113×14；效果 34×10；容器 37×9；
  TAG 38×11；未决项 116×6。
- 两次内存生成、磁盘输出逐字节一致：
  - 预览：`47584dab2a82fe2fa45d0934a27c7fe165844117e744ac08cd52c78670e38e94`
  - 缺口：`867b32b5e3410d6c86c486eafb57083cf328dd907208a84a5875de29145eb807`
  - 风险：`dbbd2ab208da30e9b8b94de1dd84aaae53c5149bc3d74350d98640a798ef14cd`
  - 锚点：`66fae432ff7a138a4cf866274fcd0d20ccf660cef59c69ab586fe1c5106a6b9d`
  - 效果：`02380a1a08b9ff762c55077300528c211654e6297c7766bc986b6326dca01cca`
  - 容器语义：`064c6ca0afaec52913d6ec64a73eea6ed4c33eeec99b2d693089e322508a86b5`
  - TAG 审计：`8d6a7913a51ddeb14061ad36be15bf35dcb0a8fff2b3fe1efb6135c257ac1d55`
  - 未决项：`3baba3f772bbb9da205dc6930f25c0f346797302a8bca8783bf2e31909315c40`
  - 覆盖摘要：`a7b2cb8ba4ce560cce2319a0087198fdfa3a63061906ded36373109e847e2d23`
  - 摘要：`9ed5f00c9af38ec9f15782535d1d94032ebd090d8e1750e09e5e1d78bc2c97a7`
- 人工定价表 SHA-256：`5883871db51c14e0f0204cb64da3ed457bb2503a088c218081e16f795c70272b`。
- 保护 manifest 前后不变：三个 food pool、`bounty_food_registry.js`、正式菜品定价表
  及原料单价参考表 SHA-256 均保持原值。
- `git diff --check`：通过；当前工作区无服务端进程（未启动，其他副本未触碰）。

### 16.4 6E.0.6 分层结论

- **SCOPE PASS**
- **RUNTIME EXPORT BLOCKED**（另一生产服务端运行中，非安全停机窗口，未启动当前服务端）
- **RECIPE CLOSURE**：170 项目标递归依赖闭包已计算
- **CONTAINER SCOPE**：IN_SCOPE 20 / OUT_OF_SCOPE 17，9 个容器锚点全部 IN_SCOPE，
  returnPath=NONE，静态配方 runtimeStatus=UNKNOWN
- **TAG AUDIT**：已核验 0 / 仍 REVIEW 38（成员需运行时）
- **CONTAINER SEMANTICS**：已确认 9/9（PART_OF_RESULT）
- **EFFECT AUDIT**：已确认 21 / 仍 REVIEW 13
- **MANUAL ANCHORS**：DEFINED_CANDIDATE 0 / PROVISIONAL 5 / REVIEW 109 / EXCLUDED 7
- **CALCULATION ENGINE PASS** / **PRICE PROVENANCE PASS**（baseline 3、merged 7、
  NEW_COMPUTABLE 4）
- **PROVISIONAL PRICES ONLY**（7 项暂定候选价格，PREVIEW ONLY）
- **RUNTIME EXPORT STALE** / **RUNTIME RECIPES NOT VERIFIED**
- **FORMAL BOUNTIFUL POOLS NOT MODIFIED**
- **SERVER NOT STARTED** / **PLAYER LIVE NOT TESTED**
- **8E WORKTREE PRESERVED**
- **commit/push NOT DONE**

本阶段完成后停止，等待复审；不进入 6E.1。RUNTIME PASS 前置条件：在另一生产服务端
停机窗口执行一次无玩家启动完成全部导出（RecipeManager 有效配方、38 TAG 实际成员、
数据包清单、UNITE 覆盖后最终状态、170 项覆盖清单），并确认世界正常保存、无残留 Java
进程；此后才可规划 6E.1 正式池合并。

## 17. 6E.0.7 P0 快速修复

仅修复影响静态审计结论的三项逻辑，无重构、无扩展、无 Java 改动、不进入 6E.1；未启动
服务端、未触碰其他副本、未改正式池/KubeJS 正式逻辑/playerdata/人工定价表/8E，未
commit/push。仅修改生成器、测试、生成输出与本报告。

- **P0-1 TAG 闭包**：`computeRecipeClosure` 现从 5 个相关 mod JAR 的
  `data/<ns>/tags/item(s)/**/*.json` 读取静态 tag 成员并递归展开 tag 引用；tag 可达的
  配方纳入闭包。`#dungeonsdelight:monster_foods` 静态成员中的 poi_cup、rubaboo_cup、
  salt_soaked_stew_cup、spider_bubble_tea、spider_salmagundi_cup、tower_boreito 对应容器
  配方均改为 IN_SCOPE（真实回归断言）。runtimeStatus 保持 UNKNOWN，静态成员不冒充
  最终运行时成员；无法静态解析的 tag 保持 fail-closed。
- **P0-2 NEW_COMPUTABLE 基线去污染**：baseline 价格求值现与 merged 使用完全相同
  recipes/targets/buff/containerEvidence/阻断规则，唯一差异为是否合并人工价格；不再传
  `new Map()` 作为 baseline containerEvidence。NEW_COMPUTABLE 用集合差
  mergedPricedIds − basePricedIds：base 6 / merged 7 / NEW_COMPUTABLE 1（仅
  mynethersdelight:burnt_roll，由人工岩浆膏锚点驱动）。容器语义驱动的 apple_jelly 等
  同时进入 base 与 merged，不再计入人工 NEW_COMPUTABLE。
- **P0-3 hasOtherValidRoute**：缺口槽全部备选失败时输出"否"（hasAlt=false），仅当存在
  其他可闭合且有效的备选才为"是"；新增两个候选均缺价 → "否"测试。

静态基线输出（10 份，两次生成逐字节一致）：

- 预览 `47584dab…38e94`；缺口 `867b32b5…45eb807`；风险 `dbbd2ab2…ef14cd`；
  锚点 `66fae432…a6a6b9d`；效果 `02380a1a…01cca`；容器语义
  `2a7dfa35128bc3d791e0e50dcea47cfb4be6dd25e4c968d1e736e6ea58f11a3f`；
  TAG `8d6a7913…c1d55`；未决项 `3baba3f7…93c15c40`；覆盖摘要 `e1b6132e…cb355d`；
  摘要 `c54999ac…3562acfb`
- 容器作用域：IN_SCOPE 26 / OUT_OF_SCOPE 11（9 个容器锚点全 IN_SCOPE）
- baseline 6 / merged 7 / NEW_COMPUTABLE 1；blocking 163；范围 170 = 38 + 132 + 0

保护文件 SHA 前后不变；`git diff --check` 通过；当前工作区无服务端进程。

当前结论维持 §16（RUNTIME BLOCKED）不变，仅上列 P0 项生效；P1 项（runtime snapshot
参数化、版本命名、文档历史整理）记为后续，不处理。
