# 8D.2 生物影窃掉落经济审计与首批正式数据

> 状态：**BUILD-only**。基于 8D.1.3 已通过框架制作首批保守 shadow_loot 数据。
> 不部署、不启动、不烟雾、不在线测试、不改配置/playerdata、不进入 8E、
> 不 commit/push。

## 1. 经济审计权威源

| 权威源 | 路径 | 用途 |
|---|---|---|
| 服务器实际注册表 | `Server/libraries/…/server-1.21.1-srg.jar` + 部署版 mods | entity/item 存在性 |
| 原料单价参考表 | `配方与经济管理/统一配方表/原料单价参考表.csv` | 铜币单价锚点（raw_beef=10 / raw_pork=15 / raw_mutton=15 / raw_rabbit=12 / eggs=5 / bones=8 / raw_meats 锚点 7-12） |
| Bountiful 悬赏池 | `Server/config/bountiful/bounty_pools/food_common_objs.json` 等 | 需求侧：悬赏为熟食加工品（beef_patty、cooked_bacon 等），**非生肉/原材直接需求** |
| Lightman's Currency | `Server/config/lightmanscurrency/` | 货币面值；本批数据**不涉及货币物品** |

## 2. 审计结论（详见 `docs/影窃者生物掉落经济审计表.csv`；8D.2.1 修正）

- **价格证据分级（8D.2.1 §2，不得混写）**：
  - 已定义：c:eggs=5、c:foods/raw_pork=15（原料单价参考表标注"已定义"）；
  - 猜想：c:foods/raw_rabbit=12、leather=8（标注"猜想"，**不是已定义权威
    价格**）；
  - 未定价：white_wool 等（单价表无条目）。
- **APPROVED（3）**：chicken→egg、pig→porkchop、rabbit→rabbit。
  - 全部 8D.0 L1/L2、可再生；价格已定义或猜想且低量级；
  - **pig/rabbit/egg 均可经烹饪（熟食/煎蛋）间接进入 Bountiful 需求链**——
    存在间接刷取闭环，但需养殖+烹饪成本，评估为低风险（**不写"无闭环"**）；
  - 鸡蛋非死亡掉落（下蛋产物）；
  - **rabbit=12 仍是猜想价**：保留兔肉属**运营接受的低风险试行决定**，
    **不冒充权威定价**（8D.2.2 明确）。
- **REJECTED（7）**：cow→leather（leather=8 为**猜想价**、证据不足，后续
  评审）；sheep→white_wool（**未定价**且当前 schema 不区分羊毛颜色——
  任意颜色羊都会产出白羊毛，语义不符，后续评审）；wolf/cat（驯养动物
  无自然掉落候选，**死亡不掉落物品**，不凑数量）；iron_golem（铁锭核心
  材料+必掉重复）；blaze（烈焰棒酿造核心材料）；ghast（恶魂之泪高价值且
  获取低效——ghast 刷怪塔**可再生**但缓慢，仍属稀有材料禁止）。
- **禁止进入首批**：L3（僵尸/骷髅/蜘蛛/苦力怕/末影人）、Warden、远古守卫者、
  Wither、末影龙（8D.0 HX/BX 硬排除，代码级 + 数据双重保证）。

## 3. 首批数据（`docs/presets/tcth-shadow-entity-loot/`；8D.2.1 更新）

- 独立数据预设（不进主 JAR、不建 shadow_thief 职业/经验/能力树）；
  **pack_format=48**（8D.2.1 §1）：
  ```
  docs/presets/tcth-shadow-entity-loot/
    pack.mcmeta（pack_format 48）
    data/tcth/shadow_loot/minecraft/{chicken,pig,rabbit}.json
  ```
- 每文件严格 schema：单 pool、单 entry、weight=100、
  **min_count=max_count=1**；每次最多一种物品；
- 保守规则全部满足：无货币/容器/潜影盒/装备/附魔/动态组件、无稀有/Boss/
  核心进度材料、不自动复制死亡掉落表、不为凑数量强行批准实体（首批
  cow/sheep 因证据不足/未定价被 REJECTED 而非凑数保留）；
- 首批全部为 `minecraft:` 命名空间（如实说明）。

## 4. 确定性生成器（`ShadowLootPresetGenerator`，test-only；8D.2.1 加固）

- 从审计 CSV 读取 APPROVED 行 → 按固定顺序/固定模板生成 JSON + pack.mcmeta
  （pack_format 48）；无时间戳、无随机 → **连续两次生成字节级一致**；
- **fail-fast（8D.2.1 §3）**：严格校验表头、10 列列数、decision 非空、
  ResourceLocation 格式、count 必须为 1、**重复 entityId 拒绝**；
  APPROVED 行缺字段/非法 ID/count≠1/重复 → **抛异常令测试失败**（禁止
  静默 continue）；生成 JSON 数量必须精确等于 APPROVED 行数；`..`/绝对
  路径等**路径穿越拒绝**；
- **清理边界（8D.2.1 §4）**：stale 清理仅作用于
  `data/tcth/shadow_loot/`；pack.mcmeta 可重写；**README、其他 data 目录
  （如未来 `data/tcth/jobsplus/`）保留**；
- 测试（`ShadowLootPresetTest`，18 用例；8D.2.2 再加固）：
  - `generatorIsDeterministic`（两次 SHA 一致）
  - `generatorCleansStaleFilesOnlyInsideShadowLoot`
  - `generatorPreservesNonShadowLootFiles`（README + jobsplus 保留）
  - `checkedInPresetMatchesTheGeneratorOutput`
  - `allPresetFilesPassTheProductionSchema`
  - `presetEntitiesAndItemsExistInTheVanillaBootstrapRegistries`
    （BuiltInRegistries 仅证明 **vanilla bootstrap 注册表**存在性，**不是**
    完整服务器模组注册表）
  - `noHardExcludedOrL3EntitiesInThePreset` / `everyPresetEntryHasCountOne` /
    `mainResourcesContainNoShadowLootJson`
  - `packFormatIsExactly48`（**精确断言 48**，不只比较一致）
  - `generatorRejectsMalformedApprovedRows` / `generatorRejectsDuplicateEntityIds` /
    `generatorRejectsPathTraversal`
  - 8D.2.2：`generatorRejectsInvalidDecisionValues`（不 trim，大小写/前后空格
    拒绝）、`generatorRejectsRejectedAndApprovedForTheSameEntity`（全表唯一）、
    `generatorRejectsInvalidResourceLocations`（tryParse 权威 + 小写 + 穿越）、
    `generatorRejectsMalformedCsvQuotes`（未闭合引号）、
    `rejectedRowsMayLeaveItemAndCountEmpty`

## 5. 测试与验证

- 合计 **suites=144 tests=1423 failures=0 errors=0 skipped=0**
  （8D.2.1 为 1418，净 +5；8D.2.2 收口）；
- 仅一次 `./gradlew clean build --no-daemon`：**BUILD SUCCESSFUL**；
- JAR 审计：无第三方 class、无嵌套 JAR、**主资源无 shadow_loot JSON**
  （预设仅在 docs/presets/）；
- `git diff --check -- src docs CHANGELOG.md` 通过；
- 未部署、未启动、未烟雾、PLAYER LIVE NOT TESTED、未进入 8E、
  未 commit/push。

## 6. 修改文件清单

**新增**
```
docs/影窃者生物掉落经济审计表.csv
docs/presets/tcth-shadow-entity-loot/（pack.mcmeta + 3 实体 JSON）
docs/phase-8d.2-shadow-loot-economy-report.md（本报告）
src/test/java/com/tanrunn/tcth/tools/ShadowLootPresetGenerator.java
src/test/java/com/tanrunn/tcth/impl/shadow/ShadowLootPresetTest.java
```

**文档**
```
docs/phase-8d.1-shadow-entity-framework-report.md（§13 8D.2 落地）
CHANGELOG.md
```

## 7. 遗留限制

- 首批为 3 个 L1/L2 实体（chicken/pig/rabbit）、count=1；cow/sheep 因
  证据不足/未定价转 REJECTED 等待后续经济评审；L3/高风险实体恒禁；
  **8D.2 的 BUILD PASS 已被 8D.2.1 修订取代，8D.2.1 已被 8D.2.2 收口
  修订**；
- 正式启用前需数据包部署与在线验收（PLAYER LIVE NOT TESTED）；
- 8E 未进入；职业经验、能力树、COIN 不在范围内。

—— 8D.2 报告完 ——
