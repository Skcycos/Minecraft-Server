# 阶段 6D：整盘料理分食与防双算

日期：2026-08-08

## 结论分层

| 层级 | 状态 |
|---|---|
| 审计 | **完成**（21 个 SERVING_DISH 逐项 javap 核验） |
| 构建 | **BUILD PASS**（88 suites / 708 tests / 0 failures） |
| 数据驱动 | **完成**（`tcth:serving_dish_containers` tag，Java 零硬编码） |
| MIXIN LOAD | **PASS**（`StuffedHoglinTakeServingMixin` 应用成功，零注入错误，`cab4bde5` 部署） |
| PLAYER LIVE | **NOT TESTED**（本阶段按指令不做在线验收） |
| 实现范围 | **1/21 可实现**，20/21 DEFERRED |
| commit/push | **未做** |

---

## 一、21 个 SERVING_DISH 覆盖矩阵

数据来源：6A 审计 `新增食物模组整盘料理清单.csv`（22 行含表头）+ `新增食物模组人工分类覆盖.csv`（6A.3）。

| # | 整盘物品 | 单份产物 | 分食机制 | 交付路径 | 结论 |
|---|---|---|---|---|---|
| 1 | `mynethersdelight:roast_stuffed_hoglin` | `mynethersdelight:plate_of_stuffed_hoglin`(+`_snout`/`_ham`) | `StuffedHoglinBlock` Feast：持盘/碗右键 `takeServing`，SERVINGS 递减 | **`Inventory.add` 进背包** ✅ | **可实现** |
| 2 | `bakeries:country_bread` | `bakeries:country_bread_slice` | `AKnifeCutBlock` 刀切 `cut()` | `spawnItemEntity` 掉地 | DEFERRED |
| 3 | `brewinandchewin:pizza` | 吃 | `PizzaBlock` SERVINGS+`useWithoutItem` | `FoodData.eat` 直接吃 | DEFERRED |
| 4 | `dungeonsdelight:monster_cake` | 吃 | `MonsterCakeBlock` | `FoodData.eat` 直接吃 | DEFERRED |
| 5 | `dungeonsdelight:polterghast_pizza` | 吃 | `PolterghastPizzaBlock extends EXPPieBlock extends FD PieBlock` | `FoodData.eat` 直接吃 | DEFERRED |
| 6 | `dungeonsdelight:spider_donut` | 吃 | `SpiderDonutBlock` DONUTS=4 `useWithoutItem→eat` | `FoodData.eat` 直接吃 | DEFERRED |
| 7 | `dungeonsdelight:spider_pie` | 吃 | `EXPPieBlock extends FD PieBlock` `consumeBite` | `FoodData.eat` 直接吃 | DEFERRED |
| 8 | `minecraft:cake` | 吃 | 原版 `CakeBlock` | `FoodData.eat` 直接吃 | DEFERRED |
| 9 | `mynethersdelight:magma_cake_block` | 吃 | `MagmaCakeBlock` | 直接吃 | DEFERRED |
| 10-15 | `neapolitan:*_cake` ×6 | 吃 | `FlavoredCakeBlock.eatSlice` | `FoodData.eat` 直接吃 | DEFERRED |
| 16-21 | `neapolitan:*_ice_cream_block` ×6 | 吃 | `FlavoredCandleCakeBlock.useWithoutItem→eatSlice` | `FoodData.eat` 直接吃 | DEFERRED |

**关键 javap 证据**：
- `StuffedHoglinBlock.useItemOn`：`isKnife→cutEar`（`Containers.drop` 掉地）；`BOWL/盘→takeServing`（`Inventory.add` 进背包）——**唯一可靠交付入口**
- `FD PieBlock`（被 dd/brewin 继承）：刀→`cutSlice`（掉地）；空手→`consumeBite`（`FoodData.eat`）
- `AKnifeCutBlock.cut(Player)`：`spawnItemEntity` 掉地
- `SpiderDonutBlock.eat` / `FlavoredCakeBlock.eatSlice`：`FoodData.eat`

---

## 二、设计规则（已实现）

- **制作/放置整盘料理**：不发厨师经验（整盘容器无 FOOD → `DishClassifier` 不识别 → 天然无事件）
- **玩家真实取出一份**：`takeServing` RETURN 发布 **恰 1 次** `DishCookedEvent`
- **result = 实际交付的单份料理**（`plate_of_stuffed_hoglin` 等，count=1）
- **count = 实际份数**（每取一份 = 1）
- **给真实交付份署名**（`DishSignatureService.sign`，交付栈带 `tcth:cooking_signature`）
- **Field Guide**：解锁单份料理（有 FOOD → 通过分类）；整盘容器不解锁
- **防双算**：整盘容器在 `tcth:serving_dish_containers` tag 标记；分食仅 `takeServing` 路径发布，`cutEar`（刀切）不发布
- **失败/仅查看/自动化/非玩家**：0 事件（`player==null` 直接返回；`takeServing` 仅在持盘/碗且 SERVINGS>0 时执行）

---

## 三、实现

### 设备枚举

`CookingDevice.PORTIONING`（新增，位于 `BAKERIES_BLENDER` 后、`OTHER` 前；不使用含糊的 `OTHER`）。

### 数据驱动

- `docs/presets/tcth-chef/data/tcth/tags/item/serving_dish_containers.json`：21 个整盘容器 ID（数据包 tag，**Java 零硬编码**）
- 已同步 `Server/global_packs/required_data/tcth-chef/data/tcth/tags/item/serving_dish_containers.json`（SHA 一致 `45838f3f…`）

### Mixin

`StuffedHoglinTakeServingMixin`（`mynethersdelight_farming_compat.mixins.json`，`requiredMods=["mynethersdelight"]`）：

| 注入点 | 职责 |
|---|---|
| `StuffedHoglinBlock.takeServing` RETURN | 取第 6 参 `Item`（单份产物）→ adapter 发布 |

### Adapter（可测，无第三方类型）

`MyNethersDelightPortioningAdapter.onServingTaken(player, servingItem, level, pos)`：
- `player==null` → false（自动化/非玩家 0 事件）
- `DishClassifier.isDish` 拒绝非料理
- 签名 → `DishCookedEventDispatcher.publish(PORTIONING, count=1, automated=false)`

复用现有：`DishClassifier`、`DishSignatureService`、`DishCookedEventDispatcher`、tier、统计、Jobs+、Field Guide——**无第二套奖励**。

---

## 四、测试

新增 `MyNethersDelightPortioningAdapterTest`（6 用例）：

| 用例 | 断言 |
|---|---|
| `servingTakenPublishesExactlyOneEventWithDeliveredItem` | PORTIONING 事件、result=交付项、count=1、automated=false、玩家/坐标正确 |
| `servingIsSignedByCurrentChef` | 交付栈带 Tanrunn 署名 |
| `nonDishServingPublishesNothing` | 非料理（DIRT）0 事件 |
| `nullPlayerPublishesNothing` | 自动化/非玩家 0 事件 |
| `nullItemPublishesNothing` | null 产物 0 事件 |
| `portioningEnumPresent` | `PORTIONING` ≠ `OTHER` |

更新 `FarmingMixinConfigTest`：mnd 配置含 `StuffedHoglinTakeServingMixin`。

**全量：88 suites / 708 tests / 0 failures / 0 errors / 0 skipped**（87/702 基线 +6D 新增 7）。

---

## 五、最终 JAR 与烟雾

| 位置 | 大小 | SHA-256 |
|---|---|---|
| `build/libs/tcth-0.2.2.jar` | 312406 B | `cab4bde5cc865d9abb65652411d920a69ce9e3c2a867aa784673502401b919a6` |
| `Server/mods/tcth-0.2.2.jar` | 312406 B | 同上 |

烟雾（smoke6d.out）：Done、`StuffedHoglinTakeServingMixin` 应用成功、无 InvalidInjection/MixinApplyError/NoClassDefFoundError、TCTH ERROR/WARN=0、FG 122/187/24、`serving_dish_containers` tag 加载、正常停服 `All dimensions are saved`。

---

## 六、DEFERRED 说明（20/21）

其余 20 个 SERVING_DISH 的分食路径**均无「玩家真实取出单份料理进背包」的可靠入口**（直接吃 `FoodData.eat` 或掉地 `spawnItemEntity`/`Containers.drop`），不满足「result 必须是实际交付的单份料理」门槛。**不猜注入点、不轮询方块实体**。待各模组引入独立 ResultSlot/取餐事件后重估。

---

## 七、未验证项

- [ ] 玩家实机分食 `roast_stuffed_hoglin`（持盘取一份 → 恰 1 事件 + 署名）——**PLAYER LIVE NOT TESTED**（本阶段指令明确不做在线验收）
- [ ] Field Guide 解锁单份料理的实机确认

---

## 八、回滚

1. 停服
2. 恢复 `backup-6b21-pre-deploy-20260808/tcth-0.2.2.jar.6b2` → `Server/mods/tcth-0.2.2.jar`（或 `git checkout` 还原源码后重建）
3. 删除 `StuffedHoglinTakeServingMixin`、`MyNethersDelightPortioningAdapter`、`serving_dish_containers.json`、`CookingDevice.PORTIONING`（还原枚举）
4. 移除 mnd mixins.json 中该 mixin 条目

---

## 九、建议暂存清单（不得自行 commit）

- `src/main/java/com/tanrunn/tcth/api/cooking/CookingDevice.java`（+PORTIONING）
- `src/main/java/com/tanrunn/tcth/impl/compat/mynethersdelight/MyNethersDelightPortioningAdapter.java`（新）
- `src/main/java/com/tanrunn/tcth/mixin/mynethersdelight/StuffedHoglinTakeServingMixin.java`（新）
- `src/main/resources/mynethersdelight_farming_compat.mixins.json`（+mixin）
- `docs/presets/tcth-chef/data/tcth/tags/item/serving_dish_containers.json`（新）
- `Server/global_packs/required_data/tcth-chef/data/tcth/tags/item/serving_dish_containers.json`（新）
- `src/test/java/com/tanrunn/tcth/impl/signature/MyNethersDelightPortioningAdapterTest.java`（新）
- `src/test/java/com/tanrunn/tcth/impl/compat/FarmingMixinConfigTest.java`（+断言）
- 本报告 `docs/phase-6d-serving-dish-integration-report.md`

**6D 完成。PLAYER LIVE NOT TESTED。等待复审。不 commit/push。**

---

# 阶段 6D.1：分食交付阻断修正

日期：2026-08-08（追加）

> 提交状态：HEAD `6272c8e3 fix(tcth): 修正料理锅快捷取餐事务` **为既有基线，已包含 6B.2.3**；当前仅 6D（含 6D.1/6D.2）未提交，本阶段仍不 commit/push。

## 阻断问题

6D 初版在 `takeServing` RETURN 后**新建假 `ItemStack`** 充当交付物并签名——这不是玩家实际拿到的栈，署名不会出现在真实交付物上。

## 修复（以 MND 1.10.4 javap 为权威）

`StuffedHoglinBlock.takeServing` 字节码（实际 JAR）：

```text
PASS_TO_DEFAULT_BLOCK_INTERACTION  ← isValidPair 失败 或 servings <= 0（0 事件路径）
Inventory.add(new ItemStack(item)) ← add 成功：真实交付
add=false → Player.drop(new ItemStack(item), false) ← 背包满：掉地
```

两个**独立** `new ItemStack(item)`（add 与 drop 各一个）。Mixin 重写：

| 注入 | 职责 |
|---|---|
| `@Inject` BEFORE `Inventory.add` | 记录 `tcth$servingPlayer`；发布**恰 1 次** PORTIONING 事件 |
| `@ModifyArg` `Inventory.add` arg0 | 对**真实 add 参数栈**原地签名（add 成功 → 背包里的栈带署名） |
| `@ModifyArg` `Player.drop` arg0 | 对**真实 drop 参数栈**原地签名（add=false → 掉落实体带署名）；**不重复发布** |

- **不再 RETURN 后新建假 ItemStack**：签名作用在 `new ItemStack(item)` 传入 add/drop 的**同一真实栈**上（@ModifyArg 在参数进入前拦截）
- 每次 `takeServing` 至多 1 个 PORTIONING 事件（仅 add 路径）
- PASS / 无份数路径（提前 return）→ 0 事件

## 三个真实分食产物（人工定档 T2，无新 T3）

| 产物 | dish_tiers | chef_t2 tag | Field Guide |
|---|---|---|---|
| `mynethersdelight:plate_of_stuffed_hoglin` | T2 | ✓ | ✓ |
| `mynethersdelight:plate_of_stuffed_hoglin_ham` | T2 | ✓ | ✓ |
| `mynethersdelight:plate_of_stuffed_hoglin_snout` | T2 | ✓ | ✓ |

服务器全局包与 `docs/presets` 同步（chef_t2 tag SHA 一致 `1cd6e235…`）。

## serving_dish_containers tag 定位（诚实声明）

`tcth:serving_dish_containers` 是**分类数据**（21 个整盘容器清单，文档/未来用途），**Java 未读取它**——发布逻辑由 Mixin 注入 `Inventory.add` 驱动，**不得声称该 tag 控制发布逻辑**（`PortioningServingDataTest.servingContainerTagIsClassificationDataOnlyNotJavaGated` 守护此声明）。

## 测试（新增/重写）

- `MyNethersDelightPortioningAdapterTest`（11 用例）：`signServingStack` 真实栈原地签名（add/drop 参数）、空 player 不签名、非料理透传；add 恰 1 事件；drop 只签名不重复发布（总 1 事件）；null player / null item / 非料理 → 0 事件；`PORTIONING` ≠ `OTHER`
- `PortioningServingDataTest`（4 用例）：三产物 tier=T2、chef_t2 tag、Field Guide、tag 非 Java 门控

## 最终 JAR 与烟雾

| 位置 | 大小 | SHA-256 |
|---|---|---|
| `build/libs/tcth-0.2.2.jar` | （6D.1 重建后） | （6D.1 重建后） |
| `Server/mods/tcth-0.2.2.jar` | 同上 | 同上 |

烟雾（6D.1 最终）：Done、`StuffedHoglinTakeServingMixin` 应用成功（@Inject + 2×@ModifyArg）、无 InvalidInjection/MixinApplyError/NoClassDefFoundError、TCTH ERROR/WARN=0、FG 122/187/24+3、正常停服。

## 未验证项

- [ ] 玩家实机分食 `roast_stuffed_hoglin`（add 成功带署名 / 背包满 drop 带署名）——**PLAYER LIVE NOT TESTED**（指令明确不做在线测试）

## 回滚（6D.1）

1. 停服
2. 还原源码（删除 6D/6D.1 改动）或恢复备份 JAR
3. 还原 `CookingDevice.PORTIONING`、删除 Mixin/Adapter、还原 dish_tiers/tag/FG

## 建议暂存清单（6D.1 增量，不得自行 commit）

- `StuffedHoglinTakeServingMixin.java`（@Inject+@ModifyArg 版）
- `MyNethersDelightPortioningAdapter.java`（signServingStack 带 player / onServingDelivered）
- `MyNethersDelightPortioningAdapterTest.java`（重写）、`PortioningServingDataTest.java`（新）
- dish_tiers/TAG/FG 三个产物条目（服务器全局包 + 预设）
- 本报告 6D.1 章节

**6D.1 完成。PLAYER LIVE NOT TESTED。等待复审。不 commit/push。**

---

# 阶段 6D.2：提交前收口（BUILD ONLY）

日期：2026-08-08（追加）

> 验证级别：**BUILD ONLY**——不部署、不烟雾测试、不 commit/push。

## 一、adapter 发布前写入署名

`MyNethersDelightPortioningAdapter.onServingDelivered` 发布前对 `reportStack` 调用 `DishSignatureService.sign(player, reportStack)`——`DishCookedEvent.result` 携带当前厨师署名。测试新增断言：result 含 `Tanrunn` 署名（`servingDeliveredPublishesExactlyOneEvent`）。

## 二、Mixin servingPlayer 生命周期

| 注入点 | 行为 |
|---|---|
| `takeServing` HEAD | 清空旧 `tcth$servingPlayer`（防残留） |
| `takeServing` RETURN | 无条件清空（RETURN 在方法所有指令后执行，`add=false` 时 drop 路径 `@ModifyArg` 已先完成签名） |
| 异常退出 | **已知限制**：该 Mixin 运行时无 `@THROW` 注入点（见 6B.2.3），RETURN 清理不运行，字段保留至下次 HEAD——**如实记录，不声称已清理** |

文档已修正：不再声称异常路径已清理。

## 三、权威分类表

`食物三档分类表.csv` 追加 3 行（596→599）：

| 产物 | 档位 | 来源证据 |
|---|---|---|
| `mynethersdelight:plate_of_stuffed_hoglin` | T2 | `StuffedHoglinBlock.takeServing` 实际分食产物（持盘取餐 `Inventory.add` 进背包） |
| `mynethersdelight:plate_of_stuffed_hoglin_ham` | T2 | 同上 |
| `mynethersdelight:plate_of_stuffed_hoglin_snout` | T2 | 同上 |

## 四、生成器重跑（确定性验证）

`food_recipe_outputs.csv` 追加三条目（可食用=是）后：

- **dish tier 生成器**：连续两次输出一致（非法行仅既有 `bad id: '{}'`）；575 文件；COMMON 353 / **T2 198** / T3 24（**新 T3=0**）；目录哈希 `c82d353b…` 两次一致；三条目未被 stale 清理
- **Field Guide 生成器**：确定性；chef_t2 **190**（122/190/24 共 336）；tag+FG 三条目均在；服务器全局包与预设同步（SHA 一致 `043e5044…`）

## 五、BUILD ONLY 记录

- clean build 后记录新 JAR SHA（**不部署**）
- **原 `6e351969` 烟雾证据只适用于旧部署哈希，不得转移到新 BUILD-only JAR**
- 待复审通过后再部署 + 烟雾 + 玩家验证

**6D.2 完成。BUILD ONLY。等待复审。不 commit/push。**

### 6D.2 提交前单点加固（复审说明）

`StuffedHoglinTakeServingMixin` 生命周期单点化：

- **HEAD**：直接 `tcth$servingPlayer = player`（捕获当前玩家），不再置 null
- **`Inventory.add` BEFORE**：只发布 PORTIONING 事件，不再写 servingPlayer
- **RETURN**：无条件 `servingPlayer = null`（PASS 路径也会清空）

这样两个 `@ModifyArg`（`Inventory.add` arg0 / `Player.drop` arg0）都读 **HEAD 捕获的玩家**，**不依赖同一 INVOKE 上 `@Inject` 与 `@ModifyArg` 的执行顺序**。

- 定向测试（adapter/数据/计数）+ clean build：**89 suites / 715 tests / 0 failures**，XML 全绿干净
- 新 BUILD-only JAR SHA：**`8b19d31aaea95f544b3d19d6a03b151072eac348aa9967f85a7ccbe74c4eacf2`**（313,170 B）——**仅记录未部署**，服务器保持旧部署 `6e351969`
- 无额外烟雾测试（按指令）

**6D.2 加固完成，可提交。等待复审。不 commit/push。**
