# 00 · UNITE 统一兼容对农夫乐事配方的影响分析

> 分析对象:`kaleidoscope_compat-2.9.7-neoforge+mc1.21.1.jar`
> 本服配置:`Server/config/kaleidoscope_compat.jsonc` → `datapack_mode = "UNITE"`、`soup_datapack_enabled = true`、FD 厨锅/砧板配方未整体禁用(`false`)
> 分析方式:解压 jar 反编译 `DatapackLoader`/`DatapackMode`/mixin + 对比数据包 `packs/` 与 FD 原始配方 + 核对 NeoForge 21.1.242 配方条件机制
> 结论可信度:**高**(数据包覆盖与加载顺序均已从字节码与源码确认)

---

## 一、UNITE 是怎么工作的

`DatapackLoader`(反编译字节码确认)在服务端数据包加载时(`AddPackFindersEvent`)按模式注入内置数据包:

| 注入的包 | 条件 | 作用 |
|---|---|---|
| `always` | 非 NONE 模式 | 基础标签 + 跨模组配方桥接(81 文件) |
| `unite` | UNITE | 通用物品标签统一(12 文件) |
| `soup` | `soup_datapack_enabled`(本服开) | 汤类配方数据包 |
| `unite_farmersdelight` | **已装 FD**(本服开) | FD 配方覆盖/禁用(54 文件) |
| `unite_create` | 已装 Create(本服开) | Create 的面包/面团/史莱姆配方适配 |
| `disable_farmersdelight_cooking_pot` 等 | 对应配置开关(本服 false) | 整体禁用 FD 厨锅/砧板配方 |

包优先级(后注入者覆盖先注入者):`always` < `unite` < `unite_farmersdelight`。

**核心机制是「物品统一 + 标签接管」**:
1. `c:` 通用标签用 `"replace": true` **接管**(如 `c:crops/tomato` 只含 KC 番茄)
2. FD 配方中引用 `c:` 标签的地方自动匹配到 KC 物品
3. FD 中被统一的物品(`farmersdelight:tomato`、`fried_egg`、`rice`、`wheat_dough`、`mutton_chops`、`cooked_rice` 等 9 项,见 `kaleidoscope_cookery:united` 标签)在配方里**整体替换为 KC 对应物**

> 附注:运行时 mixin(`FDRecipesMixin`/`CookingPotRecipeMixin`/`CuttingBoardRecipeMixin`)仅在 `cooking_pot_recipes_disabled`/`cutting_board_recipes_disabled` 配置开启时隐藏/拦截 FD 厨锅砧板配方,本服两者均为 `false`,**mixin 不生效**。

---

## 二、对 FD 配方的具体影响(共 49 处)

### 2.1 被禁用(7 个)→ 游戏内消失

| 配方 | 原因 |
|---|---|
| `cooked_mutton_chops`(熔炉)/ `_from_campfire_cooking` / `_from_smoking` | 羊肉排统一由 KC 体系承接 |
| `cutting/mutton`、`cutting/cooked_mutton` | 砧板切羊肉统一由 KC 承接 |
| `rice`(稻穗→稻米) | 稻米统一为 KC 稻米 |
| `wheat_dough_from_water` | 面团统一为 KC 面团 |

> 禁用实现:覆盖配方带 `forge:false` 条件 + `minecraft:barrier` 产物。已验证 `forge:false` 不在 NeoForge 条件注册表中,但无论「条件解析失败被跳过」还是「条件为 false」,`RecipeManager` 都会让该覆盖配方不加载,而数据包覆盖已按路径替换原配方 → **禁用必然生效**(NeoForge patched `RecipeManager` 单配方解析失败仅记 error 并跳过,不会崩服)。

### 2.2 被修改(23 个)→ 原料/产物换成 KC 统一物品

| 修改模式 | 代表配方 |
|---|---|
| 产物 `farmersdelight:tomato` → `kaleidoscope_cookery:tomato` | `tomato`、`tomato_crate`、`cutting/wild_tomatoes` |
| 产物 `farmersdelight:fried_egg` → `kaleidoscope_cookery:fried_egg`,原料 `egg` → `c:eggs` | `fried_egg` 及 2 个变体 |
| 原料/产物 `rice`/`cooked_rice` → `kaleidoscope_cookery:*` | `cod_roll`、`kelp_roll`、`salmon_roll`、`steak_and_potatoes`、`rice_bag`、`rice_bale`、`rice_from_bag`、`rice_panicle`、`honey_glazed_ham_block`、`cutting/rice_panicle`、`cutting/wild_rice`、`integration/create/milling/*` |
| 面团体系:小麦 → KC 面粉,产物 → `kaleidoscope_cookery:raw_dough` | `wheat_dough_from_egg`、`cutting/tag_dough` |
| 其余适配 | `roasted_mutton_chops`、`tomato_seeds`、`cooking/dumplings`(原料标签化)等 |

### 2.3 ⚠️ 发现作者 bug:2 个面包配方失效(但有替代途径)

`unite_farmersdelight` 的 **`bread_from_smelting.json` 和 `bread_from_smoking.json`** 把原料写成了:

```json
"ingredient": { "item": "c:dough" }   ← 错误!item 字段不能引用标签
```

`item` 必须指向具体物品 ID,`c:dough` 是标签。结果是这两个覆盖配方**解析失败被跳过**,而数据包覆盖已按路径顶掉 FD 原配方 → **FD 的熔炉/烟熏炉烤面包配方在 UNITE 模式下消失**(`always` 包里正确写法的 `"tag": "c:dough"` 版本因优先级低被覆盖,不生效)。

**但面包并未绝版**:`unite_create` 数据包(本服 Create 已装)提供了**替代配方** —— 命名空间 `create`、类型 `minecraft:smelting` 的 `create/recipe/smelting/bread.json`:

```json
"ingredient": { "item": "kaleidoscope_cookery:raw_dough" }  →  minecraft:bread
```

该配方在**普通熔炉**中生效(只是借 create 命名空间注册),经验 0.0。即:**用 KC 面团(不是 FD 面团)在普通熔炉里仍可烤面包**。真正的变化是原料从 FD 面团换成了 KC 面团,而 FD 面团配方已被统一为 KC 面团,所以实际影响很小 —— 但 FD 的 `bread_from_smelting`/`bread_from_smoking` 两个原始配方条目本身是坏了(从 JEI/数据层面消失),若追求配方完整可向作者反馈或数据包修正。

### 2.4 新增:KC 机器可以做 FD 的菜(15 个)

`always` 包给 Kaleidoscope Cookery 的机器添加了 FD 配方(命名空间用 `farmersdelight`,KC 配方加载器按类型扫描):

| KC 机器 | 新增配方 |
|---|---|
| 炒锅(`pot/add/*`) | 熟培根、牛肉饼、熟鳕鱼片、熟鸡肉块、培根蛋、熟三文鱼片 |
| 菜板(`chopping_board/add/*`) | 苹果派片、卷心菜叶、蛋糕片、巧克力派片、海带卷片、南瓜片、甜浆果芝士蛋糕片 |
| 汤锅(`stockpot/modify/pumpkin_soup`) | 南瓜汤(汤底=奶) |
| 炒锅(`pot/modify/fried_rice`) | 炒饭(碗载体) |

---

## 三、标签统一清单

`unite` + `unite_farmersdelight` 用 `replace:true` 接管(只含统一物品):

- 食材:`c:crops/tomato`、`c:crops/rice`、`c:crops/cabbage`、`c:crops/onion`、`c:foods/raw_mutton`、`c:foods/cooked_mutton`、`c:foods/cooked_rice`、`c:foods/raw_bacon`、`c:foods/raw_chicken`、`c:foods/cooked_egg`(+复数 `cooked_eggs`)、`c:foods/tomato`
- 加工品:`c:dough`、`c:flour`、`c:pasta`(FD 生意面)

统一物品表(`kaleidoscope_cookery:united` 标签,FD 侧 9 项):

| FD 物品 | 统一为(KC) |
|---|---|
| `tomato` / `tomato_seeds` | `kaleidoscope_cookery:tomato` / `tomato_seed` |
| `fried_egg` | `kaleidoscope_cookery:fried_egg` |
| `rice` / `rice_panicle` | `kaleidoscope_cookery:rice` / `rice_panicle` |
| `cooked_rice` | `kaleidoscope_cookery:cooked_rice` |
| `wheat_dough` | `kaleidoscope_cookery:raw_dough` |
| `mutton_chops` / `cooked_mutton_chops` | (由 KC 体系承接,FD 侧禁用) |

---

## 四、对配方数据表的使用建议

1. `farmersdelight_recipes.csv`(原始配方表)中,标注为「禁用/修改/失效」的 32 个配方与游戏内实际不一致,请以 `farmersdelight_unite_diff.csv` 对照查看。
2. **修复建议**(如认为面包消失是问题):把 `unite_farmersdelight` 两个面包配方的 `"item": "c:dough"` 改为 `"tag": "c:dough"`。可用数据包覆盖修正(在服务端 `Server/kubejs/` 或 `config` 加同名配方,优先级更高),或向模组作者反馈。
3. 若要关闭统一:将 `datapack_mode` 改为 `COMPAT`(仍会加载 `always` 包的部分跨模组桥接)或 `NONE`。

---

## 五、方法说明

- 加载逻辑:`javap -c` 反编译 `com/bmt/kaleidoscope_compat/datapack/DatapackLoader`、`DatapackMode`
- 条件机制:NeoForge 21.1.242 `NeoForgeMod` 源码确认条件注册于 `neoforge` 命名空间;patched `RecipeManager` 确认单配方解析失败仅跳过
- 差异表:`配方与经济管理/农夫乐事配方表/farmersdelight_unite_diff.csv`(49 行,由脚本自动对比生成,脚本在 `配方与经济管理/统一配方表/scripts/gen_unite_diff.py`)
