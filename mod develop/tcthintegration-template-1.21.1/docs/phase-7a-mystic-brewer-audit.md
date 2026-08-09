# 阶段 7A：tcth:brewer 魔酿师职业审计与数据基础

日期：2026-08-09

## 结论分层

| 层级 | 状态 |
|---|---|
| 饮品数据审计 | **完成**（86 个 DRINK，互斥分类，REVIEW=0） |
| 设备审计 | **完成**（javap 权威：Keg IMPLEMENTABLE，Shaker/Barrel/Blender DEFERRED） |
| 职业预设骨架 | **完成**（docs/presets/tcth-brewer/，未部署） |
| 事件设计草案 | **完成**（仅设计不实现） |
| 验证级别 | **DATA/AUDIT ONLY**（不部署 JAR、不启动服务器、不烟雾） |
| commit/push | **未做** |

---

## 一、饮品分类统计（魔酿师饮品分类表.csv）

来源：6A 审计 `新增食物模组饮品清单.csv`（86 个 DRINK）+ 服务器实际 JAR 核对。

| 分类 | 数量 | 说明 |
|---|---|---|
| DRINK_COMMON | 18 | 普通饮品（bakeries 拿铁/咖啡 6、neapolitan 奶昔/冰沙 7、dd 茶饮 3、create builders_tea、honey_bottle） |
| DRINK_T2 | 46 | 复合调饮/发酵酒（BAC beer/mead/vodka/rum/ale/stout/kombucha 等 + tavern 鸡尾酒/酒类） |
| DRINK_T3_CANDIDATE | 6 | 仅候选（saccharine_rum/red_rum/sherry/champagne/ice_wine/madame_shexiang）——**禁止自动启用** |
| BREWING_INGREDIENT | 2 | 酿造原料（coffee_bean/ground_coffee） |
| EMPTY_CONTAINER | 2 | 空容器（drink_cup/coffee_table） |
| EXCLUDED | 12 | 非职业饮品（potion/water_bucket/milk_bucket/牛奶瓶/奶酪轮/发酵食品 jerky 等） |
| REVIEW | **0** | 无需人工复审 |

- 总数 86，唯一 id 86，13 列（item id/中英名/来源模组/配方设备/FOOD/效果/最终饮品/中间产物/适合魔酿师/档次/证据/复审）
- 两次生成 SHA 一致：`e339d20d06e62a33`

### 关键核对（服务器 JAR 权威）

- BAC `BEER` 等有 `BnCFoods` FOOD 组件；`fermenting`（24 配方）+ `pouring`（27）+ `keg` 配方，饮品以**流体**（millibuckets）发酵，`pouring` 灌装成物品
- tavern 饮品为 `DrinkBlockItem(Block)`（无 FOOD，自定义 `use` 饮用逻辑）
- bakeries `drink` 配方（moka pot 调饮）产物为最终饮品

---

## 二、设备支持矩阵

| 设备 | BlockEntity/Menu | 玩家交付路径 | ServerPlayer | 真实交付栈 | recipeId | 结论 |
|---|---|---|---|---|---|---|
| **BAC Keg** | `KegBlockEntity` + `KegMenu`（普通 Slot） | `useItemOn → extractInWorld(stack,1,false) → fluidExtract → getPouringRecipe` 返回 `List<ItemStack>`，玩家持容器灌装 | ✓ | ✓ | ✗（见下） | **IMPLEMENTABLE** |
| Tavern Shaker | `ShakerBlockEntity`（addIngredient+result）+ `ShakerItem`（pourResult） | 双形态：放置空手右键 getDrops 掉落 / 手持 pourResult——交付进背包待确认 | ✓ | 部分 | ✗ | DEFERRED |
| Tavern Barrel | `BarrelBlockEntity`（ingredient+output+recipeId 字段） | `useItemOn` 仅 openLid/addFluid/removeFluid/addIngredient/removeIngredient/tipBrewInfo——无明确玩家取成品入口 | ✓ | 复杂 | ✓（字段） | DEFERRED |
| Bakeries Blender | `BlenderBlockEntity` + `BlenderMenu` | 输出槽=NeoForge 泛类 SlotItemHandler；shift-click 不走 onTake（6C） | ✓ | 部分 | ✗ | DEFERRED |

**下一阶段最优先设备**：BAC Keg（唯一可靠「玩家真实获得最终饮品」入口，注入点 `KegBlock.useItemOn` / `KegBlockEntity.extractInWorld`）。

**Keg recipeId 限制（7A.1 修正，javap 实证）**：
- `getPouringRecipe(ItemStack)` 返回 `Optional<KegPouringRecipe>`（裸 Recipe，非 `RecipeHolder`）
- `KegPouringRecipe` **无 `id()`/`getId()`** 方法（javap 已确认）——无法直接取得 recipeId
- 7B 实现时 **默认允许 recipeId=null**，通过**结果物品映射档次**（`BeverageTier` 从 result item 的 tiers.json 查）
- **不准把 Barrel 的 `lastRecipeID` 字段当作 pouring recipe ID**（那是 barrel 发酵配方，与 keg pouring 无关）

---

## 三、职业预设骨架（docs/presets/tcth-brewer/）

```
tcth-brewer/
├── pack.mcmeta                          # pack_format 48
├── README.md                            # 职业定义/边界/后续
└── data/tcth/
    ├── jobsplus/jobs/brewer.json        # is_default:false，图标 brewinandchewin:keg
    ├── tags/item/brewer_drinks.json     # common+T2 共 64 项（草案）
    └── brewer/tiers.json                # COMMON 18 / T2 46 / T3_CANDIDATE 6 / INGREDIENT 2
```

语言键（已加入 TCTH 主模组 `assets/tcth/lang/`）：
- zh_cn：`jobsplus.job.tcth.brewer.name` = **魔酿师**；`...description` = **调制饮品、掌握发酵工艺，并在一次次斟饮中探索奇妙风味。**
- en_us：**Mystic Brewer** / *Mix drinks, master fermentation, and uncover strange flavors one pour at a time.*

**未部署、不启用职业、不创建经验 Action。**

---

## 四、事件设计草案（仅设计）

### BeveragePreparedEvent（extends `net.neoforged.bus.api.Event`，发布到 `NeoForge.EVENT_BUS`）

| 字段 | 类型 | 说明 |
|---|---|---|
| eventId | UUID | 唯一，幂等跟踪 |
| player | ServerPlayer（可 null） | 自动化无参与者 |
| recipeId | ResourceLocation（可 null） | 设备可提供时 |
| result | ItemStack | 防御性复制 |
| device | BeverageDevice | 枚举 |
| tier | BeverageTier | 枚举 |
| automated | boolean | 玩家/自动化 |
| level | ServerLevel | 服务端上下文 |
| position | BlockPos（可 null） | 设备位置 |

### BeverageDevice 枚举

`KEG`（BAC）、`SHAKER`（tavern）、`BARREL`（tavern）、`BLENDER`（bakeries）、`OTHER`

### BeverageTier 枚举

`UNKNOWN`、`COMMON`、`T2`、`T3`

**7A.1 修订**：`T3_CANDIDATE` 与 `INGREDIENT` **只属于审计数据**（魔酿师饮品分类表），**不进入运行时事件**。运行时 BeverageTier 仅 `UNKNOWN/COMMON/T2/T3`；当前 T3 不启用（T3 候选不自动启用）。

**要求**：公共 API 零第三方引用；经验/金币/settled 状态**不放进事件**（消费方自行记账，镜像 DishCookedEvent 设计）。

---

## 五、边界遵守

- 未编写任何 Mixin
- 未启用魔酿师职业、未发放经验/金币、未做食用饮品得经验
- 未把饮品重新加入厨师、未修改 tcth:chef/UNITE/playerdata
- 未 commit/push

---

## 六、验证记录（DATA/AUDIT ONLY）

- CSV/JSON 校验：魔酿师饮品分类表 86 项、tcth-brewer 预设 3 JSON、lang 2 JSON 全部合法
- 两次生成 SHA 一致：`e339d20d06e62a33`
- 定向测试：`ChefPresetTest` / `FieldGuideDataTest` BUILD SUCCESSFUL（lang 键改动无破坏）
- 未部署 JAR、未启动服务器、未做烟雾测试

---

## 七、精确建议暂存清单（不得自行 commit）

- `配方与经济管理/统一配方表/魔酿师饮品分类表.csv`（新）
- `mod develop/.../docs/presets/tcth-brewer/**`（pack.mcmeta/README/brewer.json/brewer_drinks/tiers.json）
- `mod develop/.../src/main/resources/assets/tcth/lang/zh_cn.json`（+brewer 键）
- `mod develop/.../src/main/resources/assets/tcth/lang/en_us.json`（+brewer 键）
- `mod develop/.../docs/phase-7a-mystic-brewer-audit.md`（本报告）

**7A 完成。DATA/AUDIT ONLY。等待复审。不 commit/push。**

---

# 阶段 7A.1：数据修正与自动校验

日期：2026-08-09（追加）

## 修正内容

1. **魔酿师饮品分类表.csv**：「是否需人工复审」修正为**仅 6 个 DRINK_T3_CANDIDATE=是**，其余 80 项=否。分类 `REVIEW` 槽位=0（无 REVIEW 分类）与「待决策候选=6」**分开统计**。
2. **Keg recipeId 修正**（javap 实证）：`getPouringRecipe(ItemStack)` 返回 `Optional<KegPouringRecipe>`（裸 Recipe 非 RecipeHolder），`KegPouringRecipe` **无 `id()`/`getId()`**——7B 默认 `recipeId=null`，通过结果物品映射档次；**不准把 Barrel.lastRecipeID 当 pouring recipe ID**。
3. **事件草案修订**：BeverageTier 仅 `UNKNOWN/COMMON/T2/T3`；`T3_CANDIDATE`/`INGREDIENT` 只属审计数据不进入运行时；当前 T3 不启用。
4. **预设 README 更新**：brewer_drinks 已填充 64 个正式饮品（COMMON 18 + T2 46）；tiers.json 非运行时审计草案；T3 候选不自动启用。

## 自动校验（scripts/validate_brewer_7a1.py，exit=0）

| 校验项 | 结果 |
|---|---|
| 唯一 ID | 86 / 86 |
| 分类计数 | COMMON 18 / T2 46 / T3_CANDIDATE 6 / INGREDIENT 2 / EMPTY_CONTAINER 2 / EXCLUDED 12 |
| REVIEW 槽位 | 0（无 REVIEW 分类） |
| 人工复审=是（待决策候选） | 6（恰为 T3_CANDIDATE 集合） |
| brewer_drinks tag | 64，且与 COMMON∪T2 完全一致 |
| T3候选/原料/容器/排除在 tag | 无（不在） |
| tiers.json 与 CSV 一致 | 是（COMMON/T2/T3/INGREDIENT 四分类全等） |
| 测试 | `ChefPresetTest`/`FieldGuideDataTest` BUILD SUCCESSFUL（lang 改动无破坏） |

**CSV SHA-256**：`1cfad6786d916e4a0ed87cf9aec8b375e90a2900756eda31f7c0552653ed43de`

## 验证级别

仅数据/文档测试——未启动服务器、未烟雾、未部署、未 commit/push。

**7A.1 完成，停在 7A.1 等待复审。**
