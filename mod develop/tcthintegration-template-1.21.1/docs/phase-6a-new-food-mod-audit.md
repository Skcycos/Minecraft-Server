# 阶段 6A / 6A.1 / 6A.2 / 6A.3：新增食物模组配方导出与厨师适配审计

日期：2026-08-08  
**当前有效语义结论以 §10（6A.3）为准（有效表 REVIEW=0）。**  
6A 初版、**6A.1 的 203 DISH（语义未拆分）**、以及 **6A.2 仍残留 16 REVIEW 的“未完全定稿”状态**均已废止，不得再作为 6B 输入。

约束：未改 TCTH Java 业务、未实现 Mixin、未写旧权威 CSV、未部署厨师数据包、未改 UNITE/playerdata/奖励；未 commit/push；**禁止进入 6B**。

---

## 当前有效摘要（唯一权威数字来源：§10 6A.3）

| 指标 | 当前值 |
|---|---|
| 有效产物 | **633** |
| DISH | **167** |
| SERVING_DISH | **21** |
| INGREDIENT | **87** |
| DRINK | **86** |
| RAW_FOOD | **7** |
| NON_FOOD | **265** |
| REVIEW | **0** |
| COMMON / T2 / T3候选 | **38 / 129 / 0** |
| 人工覆盖 | **84** |
| **当前唯一权威章节** | **§10 6A.3** |

> §0–§9 为过程与历史快照（含 6A.1/6A.2 已废止数字）。任何与上表冲突的旧数字一律以 §10 为准。

---

## 0. 6A 初版 vs 6A.1（历史/废止声明）

| 项 | 6A 初版（废止） | 6A.1 历史快照（已废止，不得作 6B 输入） |
|---|---|---|
| UNITE pack 解析 | 错误：`packs/` 被 `data/` 正则吞掉 → unite_add/disable≈0 | **已修**：先解析 `packs/<pack>/data/...` |
| 多提供者 | 单 Map 静默覆盖 | **多 provider** + `UNITE覆盖`/`UNITE添加` |
| 待分级表 | 679 产物（含 46 无运行时） | 633 有效产物；46 进未加载附表（结构保留至 6A.3） |
| 内容类型 | cream 等泛化误伤 ice cream | 优先级重构 + 证据/置信度（**DISH 语义仍未拆分**） |
| T3 | 52 正式 T3（过高） | **历史：2×T3候选**（6A.2 已驳回 → 当前 **0**，见 §10） |
| 营火 | 误写“已覆盖” | **未覆盖**（仅枚举，无检测；6B 设计项） |

---

## 1. JAR 版本与 SHA-256（服务器权威）

| 模组 | JAR | 版本 | SHA-256 |
|---|---|---|---|
| Neapolitan | `neapolitan-1.21.1-6.0.1.jar` | 6.0.1 | `82e104af…071d9` |
| Dungeon's Delight | `neoforge-dungeonsdelight-1.21.1-1.5.0.jar` | 1.5.0 | `90342848…1c5cd` |
| My Nether's Delight | `MyNethersDelight-1.21.1-1.10.4.jar` | 1.10.4 | `fe246d08…43432` |
| Brewin' and Chewin' | `BrewinAndChewin-neoforge-4.5.0+1.21.1.jar` | 4.5.0 | `9f658182…43971` |
| Bakeries | `bakeries-1.21.1-NeoForge-1.0.1.jar` | 1.0.1 | `a36e3460…76947` |
| Kaleidoscope Tavern | `kaleidoscopetavern-1.2.0-neoforge+mc1.21.1.jar` | 1.2.0 | `03f35e1e…edeff` |
| Fowl Play | `fowlplay-1.2.3+1.21.1-neoforge.jar` | 1.2.3 | `41974c76…01198` |
| Kaleidoscope Compat | `kaleidoscope_compat-2.9.7-neoforge+mc1.21.1.jar` | 2.9.7 | `a9cb2a91…d3974` |

`datapack_mode` = **UNITE**（未修改配置）。

源码参考仍不匹配处（只读）：Neapolitan 工作树≈6.1.0；MND=1.20.1；bakery=1.20.1 Forge；Compat 本地≈2.10.0。权威=JAR。

---

## 2. 工具与测试（含 6A.1 历史测试快照）

| 文件 | 作用 |
|---|---|
| `Server/kubejs/server_scripts/export_food_recipes.js` | 运行时 RecipeManager 导出（6A 已扩展） |
| `Server/tools/phase6a_lib.mjs` | 纯逻辑（pack/分类/档次/覆盖；经 6A.2/6A.3 扩展） |
| `Server/tools/export_phase6a_audit.mjs` | 生成审计 CSV |
| `Server/tools/export_phase6a_audit.test.mjs` | **当前：47 passed, 0 failed**（见 §10.5） |

运行时导出（6A 已完成，后续阶段复用）：**1809** 条 / `food_recipe_export.json`；服务器 **All dimensions are saved**。

### 测试覆盖（6A.1 历史：25 项）

> 以下为 6A.1 当时的 25 项清单。**当前测试数为 47**（6A.1+6A.2+6A.3），见 §10。

1. pack 路径 `unite_bakeries`  
2. 普通 `data/` pack 为空  
3. recipe 路径 path 段  
4. 同 ID 基础+UNITE 双提供者 + `UNITE覆盖`  
5. 仅 UNITE → `UNITE添加`  
6. disable 覆盖  
7. classifyPackKind  
8–12. ice cream / creamy soup / breeze cone → DISH  
13–15. cream/dough → INGREDIENT  
16–18. scarecrow / blazier / pressing tub  
19. 多路径最低档  
20–21. T3 不可仅靠 monster_cooking；T3候选多证据  
22. CSV 矩形  
23–24. 营火未覆盖；熔炉/工作台已覆盖  
25. jsonHash 稳定  

### 确定性（6A.1 历史 SHA — 已废止，不得当作当前 CSV）

> 下表为 **6A.1 当时** 的哈希。**当前有效 CSV 哈希只认 §10.5。**

| 文件 | SHA-256（6A.1 历史，废止） |
|---|---|
| 新增食物模组配方审计表.csv | `51d3f701…fe2f99`（历史） |
| 新增食物模组产物待分级表.csv | `00b1d736…52a722`（历史） |
| 新增食物模组未加载产物附表.csv | `077844dd…ba1698`（历史） |
| 新增食物模组T3候选复审表.csv | `bfd5aca2…40826da`（历史；当时含 **2** 候选，现已为 0） |

---

## 3. 6A.1 历史快照（已废止，不得作为 6B 输入）

> 本节仅保留 6A.1 历史审计过程。当前有效数据只能读取 §10 的 6A.3 结论。

### 3.1 配方审计表（6A.1 历史）

| 指标 | 值（历史） |
|---|---|
| 审计配方行 | 856 |
| 运行时有效 | 784 |
| 仅 JAR/未加载 | 72（行级） |

### 3.2 产物表（6A.1 历史）

| 指标 | 值（历史） |
|---|---|
| 有效唯一产物 | 633（结构延续至 6A.3） |
| inactive-only 产物（附表） | 46 |
| 可食用有效产物 | 见当时 summary（非当前） |

### 3.3 内容类型（配方行 · 6A.1 历史 · 语义未拆分）

| DISH | DRINK | INGREDIENT | RAW_FOOD | NON_FOOD | REVIEW | ANIMAL_FOOD |
|---|---|---|---|---|---|---|
| 257（废止语义） | 110 | 127 | 6 | 342 | 14 | 0 |

### 3.4 建议厨师档次（有效产物表 · 6A.1 历史）

| COMMON | T2 | T3候选 | 不进入厨师 | 待复审 |
|---|---|---|---|---|
| 35 | 165 | **2（历史；6A.2 已驳回）** | 420 | 11 |

其中 DISH 产物档次约（**历史**）：COMMON 35 / T2 165 / **T3候选 2** / 待复审 1。  
当时无正式 T3 写入。**当前 T3候选 = 0**（§10）。

旧基线 COMMON 315 / T2 66 / T3 24 仅供对照，非当前。

### 3.5 UNITE（pack 解析修正后 · 结构仍有效）

Compat JAR 内 **pack 配方计数**（解析成功；计数结构仍适用）：

| pack | 配方数 |
|---|---|
| always | 78 |
| compat | 15 |
| unite_farmersdelight | 32 |
| unite_farm_and_charm | 25 |
| unite_create | 9 |
| unite_bakeries | 1 |
| disable_farmersdelight_cooking_pot | 3 |
| disable_youkaisfeasts_steamer_pot | 3 |
| disable_vinery_fermentation_barrel | 2 |
| disable_farmersdelight_cutting_board | 1 |
| disable_farm_and_charm_cooking_pot | 1 |

审计焦点集合上的状态命中（多 provider 标注）：

| UNITE添加 | UNITE覆盖 | disable |  
|---|---|---|
| 0 | 3 | 1 |

说明：多数 `unite_farmersdelight` 等作用于已在旧 FD/KC 权威表覆盖的 ID；**pack 计数非 0** 证明 6A.1 已修复 6A 初版 pack 解析错误。

---

## 4. 6A.1 历史边界清单（已由 6A.2/6A.3 覆盖）

> **历史清单。** 表中出现的 `REVIEW` **不代表当前状态**。  
> **当前有效产物表 `REVIEW=0`**（见 §10）。下列 29 项已在 6A.2/6A.3 写入人工覆盖并关闭。

当时须人工复审的可食用 INGREDIENT/REVIEW（**历史共 29**）：

| 产物ID | 6A.1 历史类型 | 证据（历史） |
|---|---|---|
| bakeries:cheese_cream | INGREDIENT | ingredient_name |
| bakeries:foamed_cream | INGREDIENT | ingredient_name |
| bakeries:fresh_cheese_cube | INGREDIENT | ingredient_name |
| bakeries:mashed_taro | INGREDIENT | ingredient_name |
| bakeries:meat_floss | INGREDIENT | ingredient_name |
| bakeries:pineapple_oil | INGREDIENT | ingredient_name |
| brewinandchewin:flaxen_cheese_wedge | INGREDIENT | cutting |
| brewinandchewin:scarlet_cheese_wedge | INGREDIENT | cutting |
| dungeonsdelight:cleaved_ancient_egg | INGREDIENT | cutting |
| dungeonsdelight:creeperilla_squib | INGREDIENT | cutting |
| dungeonsdelight:ghast_calamari | INGREDIENT | cutting |
| dungeonsdelight:rotgourd_slice | INGREDIENT | cutting |
| dungeonsdelight:rotten_tripe | INGREDIENT | cutting |
| dungeonsdelight:wardenzola_crumbles | INGREDIENT | cutting |
| farmersdelight:pumpkin_slice | INGREDIENT | cutting |
| mynethersdelight:ghast_dough | INGREDIENT | ingredient_name |
| mynethersdelight:hot_cream | INGREDIENT | ingredient_name |
| mynethersdelight:minced_strider | INGREDIENT | ingredient_name |
| mynethersdelight:strider_egg | INGREDIENT | cutting |
| dungeonsdelight:bubblegunk | REVIEW（历史） | ambiguous → **6A.2 已改 DISH COMMON** |
| dungeonsdelight:slicorice | REVIEW（历史） | ambiguous → **6A.2 已改 DISH COMMON** |
| dungeonsdelight:slime_bar | REVIEW（历史） | ambiguous → **6A.2 已改 DISH COMMON** |
| mynethersdelight:bullet_pepper | REVIEW（历史） | ambiguous → **6A.2 已改 RAW_FOOD** |
| mynethersdelight:dried_ghast_with_milk | REVIEW（历史） | ambiguous → **6A.2 已改 DISH COMMON** |
| mynethersdelight:spicy_cotton | REVIEW（历史） | ambiguous → **6A.2 已改 DISH COMMON** |
| neapolitan:banana | REVIEW（历史） | raw-ish → **6A.2 已改 RAW_FOOD** |
| neapolitan:chocolate_spider_eye | REVIEW（历史） | ambiguous → **6A.2 已改 DISH COMMON** |
| neapolitan:mint_chops | REVIEW（历史） | ambiguous → **6A.2 已改 DISH COMMON** |
| neapolitan:mint_leaves | REVIEW（历史） | raw-ish → **6A.2 已改 RAW_FOOD** |

（历史来源：当时 `phase6a_audit_summary.json` → `edible_but_ingredient_or_review`。）

---

## 5. 设备覆盖矩阵（6A.1 修正；6B 设计输入，非语义数字）

| 设备/工序 | DishCookedEvent | 说明 |
|---|---|---|
| 工作台 crafting | **已覆盖** | `ItemCraftedEvent` |
| 熔炉 smelting | **已覆盖** | `ItemSmeltedEvent` |
| 烟熏炉 smoking | **已覆盖** | 同上 + 菜单启发 SMOKER |
| **营火 campfire** | **未覆盖** | 枚举有 `CAMPFIRE`，**无检测实现**；Neapolitan 等 campfire 配方**不会**因此发事件（**6B 设计项**） |
| 高炉 blasting | 可能误触 ItemSmelted | **不宜**作料理设备 |
| FD Cooking Pot | **已覆盖** | ResultSlot Mixin |
| FD Cutting | **未覆盖（非出锅）** | 不应当厨师出锅 |
| KC Pot/Stockpot/Steamer | **已覆盖** | 既有 Mixin |
| DD Monster Pot | **需适配** | `MonsterPotResultSlot#onTake`（建议；**6B**） |
| Bakeries Oven/Blender/… | **需适配** | 仅建议注入点，**未实机**（**6B**） |
| BnC Keg | **需适配** | 发酵/倾倒；酒保另议（**6B**） |
| Tavern Barrel/Shaker | **需适配** | 建议点，未实机（**6B**） |
| Create 机器 | **不接入玩家** | 自动化不得记厨技 |

---

## 6. 6A.1 历史交付状态（已废止数字，不得当作当前结果）

> 下表描述 **6A.1 当时** 交付物状态。其中 **T3候选 2、REVIEW 非零、旧 SHA** 均 **不是当前结果**。  
> **当前交付与哈希见 §10.5–10.6。**

| 路径 | 6A.1 历史说明（废止） |
|---|---|
| `…/新增食物模组配方审计表.csv` | 当时 856 行数据 |
| `…/新增食物模组产物待分级表.csv` | 当时 633 有效产物 only |
| `…/新增食物模组未加载产物附表.csv` | 当时 46 inactive-only |
| `…/新增食物模组T3候选复审表.csv` | **历史：2 候选**（6A.2 已驳回；**当前 0，表仅表头**） |
| `Server/kubejs/config/phase6a_audit_summary.json` | 当时 phase=6A.1（**当前 phase=6A.3**） |
| `Server/kubejs/config/food_recipe_export.json` | 运行时权威（1809；结构仍复用） |

**未修改（全程约束，仍成立）：** `食物三档分类表.csv`、`统一配方总表.csv`。

断言（结构仍成立）：`dungeonsdelight:blazing_blood_sausage` **不在**有效待分级表；有效配方数可由审计表复算。

---

## 7. 历史门槛记录（6A.1→6A.3；已完成项已关闭）

> 本节是过程门槛台账，**不是**“当前还欠 16 条 REVIEW”之类的未完成清单。  
> 语义分类门槛已在 **6A.3 关闭**。**当前仍未解决的只有 6B 设计项**（见下）。

### 7.1 语义/分类门槛（已完成）

1. ~~人工勾选 DISH 名单与 29 条 edible INGREDIENT/REVIEW~~ → **6A.2 已覆盖 29 边界**  
2. ~~另余 16 条 REVIEW 仍待后续人工~~ → **6A.3 已清零，当前 REVIEW=0**  
3. ~~人工确认 2 条 T3候选~~ → **6A.2 已驳回，T3候选=0**（不得为凑数重挑）  
4. ~~普通手持料理与整盘料理拆分~~ → **6A.2 完成；6A.3 收口**  
5. ~~substring 误判 / 覆盖优先启发式~~ → **6A.2 完成**  

### 7.2 当前仍未解决 — 仅 6B 设计项（禁止在本报告假装已实现）

- **SERVING_DISH 防双算**（整盘 vs 单份经验/统计）  
- **新设备完成事件**（Monster Pot / Bakeries / BnC / Tavern 等 Mixin 与注入点）  
- **Field Guide 整盘展示方式**（展示整盘还是单份、分食解锁、双解锁）  
- **营火是否纳入** DishCookedEvent  
- **饮品职业是否独立**（DRINK 暂不进普通厨师）  

**进入 6B 前：** 不得把上列设计项写成已实现；不得合并进旧权威表，除非另开 6B 任务并终审通过。

---

## 8. 约束核对（6A.1 历史节点）

| 项 | 6A.1 当时状态 |
|---|---|
| 不进 6B | ✓（全程） |
| 不改旧权威 CSV / 厨师预设 / Mixin / 奖励 / UNITE / playerdata | ✓（全程） |
| 不 commit/push | ✓ |
| 测试 25/0 | ✓（**6A.1 历史**；当前 47，见 §10） |
| 运行时 RecipeManager 非纯静态冒充 | ✓（复用 6A 导出） |

**6A.1 技术管线复审通过；语义分类未通过 → 进入 6A.2（历史节点，非当前）。**
---

## 9. 阶段 6A.2：厨师料理语义拆分（历史过程；数字以 §10 为准）

日期：2026-08-08  
> **历史章节。** 6A.2 完成 DISH/SERVING 拆分与 T3 驳回，但当时仍残留 **16 条 REVIEW**（**已由 6A.3 清零**）。  
> 下列 6A.2 统计（如 DISH 158、REVIEW 16、覆盖 68）为**中间快照**，**不是当前结论**。当前只认 §10。

**本阶段只处理分类与人工决策。** 未修改 `食物三档分类表.csv`、未运行 `generate_dish_tiers.py`、未改 tcth-chef、未改 Java/Mixin、未部署服务器、未 commit/push；**仍禁止进入 6B**。

### 9.1 为何废止 6A.1 的「203 DISH」

6A.1 将下列语义混在同一 `DISH` 桶：

1. 带 FOOD 组件、手持食用的最终料理；  
2. 无 FOOD、可放置后分食的蛋糕/披萨/整盘；  
3. 部分生坯/中间产物误伤。  

因此 **6A.1「203 DISH」为语义未拆分值，正式废止**，不得写入厨师档次表或 6B。

### 9.2 内容类型（扩展）

| 类型 | 含义 | 厨师档次 |
|---|---|---|
| **DISH** | 有 FOOD 组件的最终手持料理 | 可进 COMMON/T2/T3候选 |
| **SERVING_DISH** | 无 FOOD；JAR/Block 证明可放置分食 | **不进入**普通厨师（防双算待设计） |
| **INGREDIENT** | 中间产物/生坯/酱料/面团 | 不进入；不发锅经验 |
| **DRINK** | 饮品 | 暂不进入 |
| **RAW_FOOD** | 可食用原料 | 不进入 |
| **NON_FOOD** | 装饰/箱装/工具/非食物 | 永不进入厨师 |
| **REVIEW** | 仍模糊 | 待复审 |

规则硬约束：

- `DISH` 默认要求运行时 `是否可食用=是`；无 FOOD 不得仅凭 cake/pizza/roast/tart 名称成为 `DISH`；  
- 无 FOOD 仅当有分食证据才可 `SERVING_DISH`；  
- 名称规则一律 **完整 token / 前后边界 / 明确后缀 / 显式 ID**，禁止任意子串（修复 `tart`→`tartaric_acid_painting`、`roast`→`roasted_adzuki_crate`）。

### 9.3 人工覆盖管线

文件：`配方与经济管理/统一配方表/新增食物模组人工分类覆盖.csv`  
列：产物ID、最终内容类型、最终建议档次、决策证据、是否允许厨师经验、是否允许 Field Guide、是否属于整盘料理、备注。

执行顺序：

1. 自动启发式（`classifyContentType` + `suggestTier`）  
2. **人工覆盖 CSV（优先）**  
3. 一致性验证（DISH+FOOD、档次与类型、SERVING 不进厨师）  
4. 输出待分级表与分清单  

测试覆盖：重复 ID / 非法 RL / 非法类型 / DISH 无 FOOD / INGREDIENT 等不得有厨师档 / SERVING 不进档 / 覆盖确定性。

**人工覆盖条目数：68（6A.2 历史；当前 84，见 §10）。**

### 9.4 T3 候选驳回（不得补位）

| 产物 | 原 6A.1 | 6A.2 最终 | 理由摘要 |
|---|---|---|---|
| `dungeonsdelight:sculk_mayo` | T3候选 | **INGREDIENT** | 饥 1 / 饱和 0.4 / 虚弱副作用；酱料；被 devilish_eggs 等使用 |
| `dungeonsdelight:wardenzola` | T3候选 | **INGREDIENT** | sculk_polyp×2+milk；奶酪中间产物；被 polterghast_pizza 使用 |

**修正后 T3候选 = 0。** 不为保留 T3 数量重新挑选替代品。

### 9.5 29 边界产物（可食用但仍曾为 INGREDIENT/REVIEW）

保持 **INGREDIENT**（含驳回的 mayo/wardenzola）：cheese_cream、foamed_cream、fresh_cheese_cube、mashed_taro、meat_floss、pineapple_oil、flaxen/scarlet_cheese_wedge、cleaved_ancient_egg、creeperilla_squib、ghast_calamari、rotgourd_slice、rotten_tripe、wardenzola_crumbles、pumpkin_slice、ghast_dough、hot_cream、minced_strider、strider_egg、**sculk_mayo**、**wardenzola**。

人工改判：

| 产物 | 最终 | 档次 |
|---|---|---|
| banana / bullet_pepper / mint_leaves | RAW_FOOD | 不进入厨师 |
| bubblegunk / slicorice / slime_bar / spicy_cotton | DISH | COMMON |
| mint_chops / dried_ghast_with_milk / chocolate_spider_eye | DISH | COMMON |

均写入覆盖 CSV，不再依赖模糊名称正则。

### 9.6 6A.2 历史统计快照（已废止为“当前”；有效产物 = 633）

> **中间态。** 其中 `REVIEW=16` 已在 6A.3 清零；DISH/SERVING/档次等以 §10 为准。

| 指标 | 6A.2 历史数量（非当前） |
|---|---|
| FOOD=true 的 DISH | 158（→ 当前 167） |
| SERVING_DISH | 18（→ 当前 21） |
| INGREDIENT | 85（→ 当前 87） |
| DRINK | 86 |
| RAW_FOOD | 5（→ 当前 7） |
| NON_FOOD | 265 |
| REVIEW | **16（历史；当前 0）** |
| ANIMAL_FOOD | 0 |
| 普通料理 COMMON | 37（→ 当前 38） |
| 普通料理 T2 | 121（→ 当前 129） |
| 普通料理 T3候选 | 0 |
| 整盘料理（清单） | 18（→ 当前 21） |
| 人工覆盖条目 | 68（→ 当前 84） |
| 运行时有效配方行 | 784 |
| 审计配方行 | 856 |
| 未加载产物 | 46 |

档次规则：仅 **DISH** 可进 COMMON/T2/T3候选；SERVING_DISH / DRINK / INGREDIENT / RAW_FOOD / NON_FOOD **不进入**。

整盘料理清单（18，均「不进入厨师」）：

- `brewinandchewin:pizza`
- `dungeonsdelight:monster_cake`、`polterghast_pizza`
- `minecraft:cake`
- `mynethersdelight:magma_cake_block`、`roast_stuffed_hoglin`（javap：`StuffedHoglinBlock.SERVINGS`）
- Neapolitan `*_cake` ×6、`*_ice_cream_block` ×6  

手持 `*_ice_cream` ItemStack 仍为 **DISH**。  
Tavern `*_sandwich_board` → **NON_FOOD**（javap：装饰/花变换，无 SERVINGS 分食）。

### 9.7 回归与测试（6A.2 历史）

- 单元测试（**6A.2 当时**）：**45 passed, 0 failed**（6A.1 的 25 + 6A.2 补充）  
- **当前测试：47**（见 §10.5）  
- 补充断言：non-FOOD dishStrong ≠ DISH；tartaric painting；roasted adzuki crate；raw egg tart；raw stuffed hoglin；cake/pizza/ice cream block → SERVING_DISH；sculk_mayo/wardenzola → INGREDIENT；覆盖解析/验证；SERVING 无厨师档；CSV 矩形；连续两次输出 SHA-256 一致  

确定性哈希（**6A.2 历史 SHA — 已废止为当前**；完整值仅作审计过程留存）：

| 文件 | SHA-256（6A.2 历史） |
|---|---|
| 审计/待分级/清单等 | 见原 6A.2 导出记录（**勿作当前**；当前哈希 → §10.5） |

### 9.8 交付路径（6A.2 新增/更新 · 历史）

| 路径 | 说明（6A.2 当时） |
|---|---|
| `配方与经济管理/统一配方表/新增食物模组人工分类覆盖.csv` | 新增人工权威（当时 68 条） |
| `…/新增食物模组普通手持料理清单.csv` | DISH 清单 |
| `…/新增食物模组整盘料理清单.csv` | SERVING_DISH 清单 |
| `…/新增食物模组饮品清单.csv` | DRINK |
| `…/新增食物模组中间产物清单.csv` | INGREDIENT |
| `…/新增食物模组待复审清单.csv` | 当时含 REVIEW 数据行（**当前仅表头**） |
| 审计表 / 待分级表 / 未加载附表 / T3 复审表 | 6A.2 重生成（T3 表仅表头） |
| 工具与 summary | 当时 `phase: "6A.2"`（**当前 `"6A.3"`**） |

**未修改：** `食物三档分类表.csv`、`统一配方总表.csv`、tcth-chef、Java/Mixin、UNITE、playerdata、奖励。

### 9.9 6A.2 结论与停止点（历史）

| 项 | 状态 |
|---|---|
| 普通手持 vs 整盘已分离 | ✓（已完成） |
| 6A.1 的 203 DISH 废止 | ✓ |
| 两枚 T3 候选驳回 → 0 | ✓ |
| substring 误判修复 | ✓ |
| 人工覆盖优先于启发式 | ✓ |
| 当时仍有 16 条 REVIEW | **历史 → 6A.3 已清零** |
| 进入 6B | **禁止** |
| commit/push | **未做** |

**6A.2 完成技术拆分与 T3 驳回；历史残留 16 条 REVIEW 已由 6A.3 清零。当前权威 → §10。不得进入 6B。**

---

## 10. 阶段 6A.3：最终语义收口（REVIEW=0）— **当前唯一权威**

日期：2026-08-08  
**本阶段处理 6A.2 历史遗留的 16 条 REVIEW 并清零。**  
未修改 `食物三档分类表.csv`、未运行 `generate_dish_tiers.py`、未改 tcth-chef、未写 Java/Mixin、未部署服务器、未改 UNITE/playerdata/奖励/悬赏、未 commit/push；**仍禁止进入 6B**。

> **读本报告时：分类数量、档次、覆盖条数、CSV 哈希只认本节。**

### 10.1 目标与结果

| 目标 | 结果 |
|---|---|
| 16 条 REVIEW 逐项取证并人工覆盖 | ✓ |
| 最终有效产物表 `REVIEW=0` | ✓ |
| `T3候选=0` | ✓ |
| DISH 全部 FOOD=true | ✓（167） |
| 连续两次导出 SHA-256 一致 | ✓ |
| 自动测试 | **47 passed, 0 failed** |
| 进入 6B / 改旧权威表 | **否** |

### 10.2 16 条原 REVIEW 逐项结论

#### A. DISH（手持可食最终料理）

| 产物ID | 最终类型 | 档次 | 证据摘要 |
|---|---|---|---|
| `bakeries:bagel_filled_sauce` | DISH | **T2** | FOOD n=12 s=9.6；`recipe id=bakeries:bagel_filled_sauce`（bagel+bearnaise） |
| `bakeries:baguette_with_filling` | DISH | **T2** | FOOD n=13 s=10.4；`recipe id=bakeries:baguette_with_filling`（baguette+tomato+cooked_pork） |
| `bakeries:meat_floss_bread_roll` | DISH | **T2** | FOOD n=7 s=10.5；`recipe id=bakeries:meat_floss_bread_roll`（sliced_toast×4+meat_floss×4+bearnaise） |
| `dungeonsdelight:sculk_apple` | DISH | **T2** | FOOD n=5 s=5；`recipe id=dungeonsdelight:sculk_apple`（apple+sculk_polyp×2+honey） |
| `mynethersdelight:bleeding_tartar` | DISH | **T2** | FOOD n=6 s=9 + fire_resistance/nourishment；`recipe id=mynethersdelight:crafting/bleeding_tartar` |
| `mynethersdelight:hot_cream_cone` | DISH | **T2** | FOOD n=4 s=8 + g_pungent/fire_resistance；`recipe id=mynethersdelight:crafting/hotcream_cone`；`HotCreamConeItem` |
| `mynethersdelight:stuffed_pepper` | DISH | **T2** | FOOD n=8 s=12.8 + b_pungent；`recipe id=mynethersdelight:crafting/stuffed_pepper` |
| `neapolitan:chocolate_strawberries` | DISH | **COMMON** | FOOD n=4 s=0.8 + sugar_rush；`recipe id=neapolitan:chocolate_strawberries`（简单 2 料） |
| `neapolitan:strawberry_scones` | DISH | **T2** | FOOD n=5 s=1；`recipe id=neapolitan:strawberry_scones`（wheat×2+strawberry+sugar） |

未产生 T3。档次互斥。

#### B. RAW_FOOD

| 产物ID | 最终类型 | 证据摘要 |
|---|---|---|
| `neapolitan:strawberries` | RAW_FOOD | FOOD n=3；`recipe id=neapolitan:strawberries` 拆 `strawberry_basket`→×9；原始水果 |
| `neapolitan:white_strawberries` | RAW_FOOD | FOOD n=5；拆 `white_strawberry_basket`→×9；原始水果 |

二者 **不进入** 厨师档次 / 出锅经验 / 普通料理图鉴。

#### C. 五无 FOOD 边界（javap / 配方链）

| 产物ID | 最终类型 | 档次 | 证据 |
|---|---|---|---|
| `bakeries:country_bread` | **SERVING_DISH** | 不进入厨师 | `javap class=CountryBreadBlock extends AKnifeCutBlock`；`getSliceItem=COUNTRY_BREAD_SLICE`；`getMaxSlice=4`；`useItemOn` 刀具切割；`recipe id=bakeries:oven/country_bread`；`bread_knife`→`country_bread_slice`×6。整盘无 Item FOOD，切片后才是手持份。 |
| `bakeries:mould_toast` | **INGREDIENT** | 不进入厨师 | `javap class=MouldToastBlock implements IMouldBlock`；`take()` 经 `demouldItem` 脱模；`recipe id=bakeries:oven/toast` 产出 mould_toast；`bread_knife` 切片输入为 **`bakeries:toast`** 非本物品。模具中间态，非分食整盘。 |
| `bakeries:mould_cheese_cocoa_toast` | **INGREDIENT** | 不进入厨师 | 同上 `MouldToastBlock`；`recipe id=bakeries:oven/cheese_cocoa_toast`；`bread_knife` 输入为 **`bakeries:cheese_cocoa_toast`**。模具中间态。 |
| `dungeonsdelight:spider_donut` | **SERVING_DISH** | 不进入厨师 | `javap class=SpiderDonutBlock`；`DONUTS` property；`getMaxServings=4`；`useWithoutItem`→`eat()` 直接 `FoodData.eat(3,0.3f)`+POUNCING；`DDBlocks` 注册 `SpiderDonutBlock`；`recipe id=dungeonsdelight:monster_cooking/spider_donut`。Item 无 FOOD，放置后分食。 |
| `dungeonsdelight:spider_pie` | **SERVING_DISH** | 不进入厨师 | `javap class=EXPPieBlock extends vectorwing.farmersdelight.common.block.PieBlock`；`consumeBite`+经验；`DDBlocks.SPIDER_PIE`；`recipe id=dungeonsdelight:spider_pie`；`cutting`→`spider_pie_slice`×4。可分食 PieBlock。 |

判定原则落实：

- 明确分食/切片方块交互且整盘本身非手持料理 → SERVING_DISH  
- 仅模具/脱模中间态 → INGREDIENT（不进入普通厨师档）  
- **禁止**仅因 bread/toast/pie/donut 名称判 DISH  

### 10.3 Field Guide 边界（6A.3）

对 SERVING_DISH 覆盖中 `是否允许Field Guide=是` **仅表示未来图鉴展示意图**，**不声称**当前 Field Guide 自动生成器已支持无 FOOD 整盘条目。

6B 再决定：整盘是否单独展示、展示整盘还是单份、分食是否解锁、如何避免整盘与单份双解锁/双统计。

### 10.4 最终分类数量（有效产物 = 633）

| 指标 | 6A.2 | **6A.3** |
|---|---|---|
| DISH（FOOD=true） | 158 | **167** |
| SERVING_DISH | 18 | **21** |
| INGREDIENT | 85 | **87** |
| DRINK | 86 | 86 |
| RAW_FOOD | 5 | **7** |
| NON_FOOD | 265 | 265 |
| REVIEW | **16** | **0** |
| COMMON | 37 | **38** |
| T2 | 121 | **129** |
| T3候选 | 0 | **0** |
| 人工覆盖条目 | 68 | **84** |
| 待复审清单行 | 16 数据 | **仅表头** |

### 10.5 校验与测试

硬校验（生成器）：

- 有效表 REVIEW == 0  
- DISH 必须 FOOD=true  
- RAW_FOOD / SERVING_DISH / INGREDIENT / NON_FOOD / DRINK 不得有 COMMON/T2/T3  
- T3候选 == 0  
- 每 item_id 至多一行最终分类  
- 原 16 条均存在人工覆盖且已非 REVIEW  
- 连续两次输出 SHA-256 一致  

自动测试：**47 passed, 0 failed**。

确定性哈希（CSV 正文）：

| 文件 | SHA-256 |
|---|---|
| 新增食物模组配方审计表.csv | `b733845adf6133d4ba0aaf707f79657c3ff9d1f787b928897d45eb541db75b75` |
| 新增食物模组产物待分级表.csv | `5f479935f7f686fd02ed5bd2a6028eb4616e3980c1d49739c1c91e0ab1221c51` |
| 新增食物模组未加载产物附表.csv | `a3632f7456836bb3aa76376e18602bbf2f627c2ca4f82eea21815af6d10fa11e` |
| 新增食物模组T3候选复审表.csv | `31821da872bf7b10afcf9e41bb6fd4235931a2724fcbd503cf707ad6af099003` |
| 新增食物模组普通手持料理清单.csv | `aee33287a734b7ee7312826496a4ed024deb4cae160236171c625df4e8c93506` |
| 新增食物模组整盘料理清单.csv | `16e1696ced794b49a16df08d73847ffe8c105e5346962fb4632c1da01e16de6a` |
| 新增食物模组饮品清单.csv | `3d224be0ffa099e3973533949c99a047f8bb5c453e812379401acfa4c362c38d` |
| 新增食物模组中间产物清单.csv | `5aa9df4c217f5ccf2160444b3cd9ac1753f7ebe6d9d3858031f8c6eba367b6a1` |
| 新增食物模组待复审清单.csv | `0326ee6a90ca4d29f30d63d7968bc70d80a45d1e58c5a5894d968e04cc4283dc` |
| 新增食物模组人工分类覆盖.csv | `ca8ad4411e8b2a0ae501049c5da708e0bb25c4151ade64f112246ad57b18e4ea` |

### 10.6 交付与约束核对

| 项 | 状态 |
|---|---|
| 更新人工覆盖 CSV（+16） | ✓ |
| 重生成全部 6A CSV / 清单 / summary（phase=6A.3） | ✓ |
| 仍未修改 `食物三档分类表.csv` | ✓ |
| 仍未改 tcth-chef / Java / Mixin / UNITE / 奖励 | ✓ |
| 仍未 commit/push | ✓ |
| 进入 6B | **禁止** |

**6A.3 最终语义收口完成（有效表 REVIEW=0），停止，等待复审。不得进入 6B。**
