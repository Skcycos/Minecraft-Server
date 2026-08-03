# Bountiful 悬赏配置 AI 编写指南

> 用途：将本文作为 AI 创建、修改或审查 Bountiful 悬赏配置时的上下文与硬性约束。
>
> 当前项目基线：`Server/mods/[丰收]bountiful-neoforge-8.0.0-beta.2.jar`，模组清单标明 Bountiful `8.0.0-beta.2`、Minecraft `1.21`。本文以该 JAR 内置数据为第一依据，以 Bountiful 最新官网文档为第二依据。
>
> 当前启用状态：`Server/config/bountiful/bountiful.json` 已通过 `dataPathsToExclude` 屏蔽全部 JAR/数据包内置悬赏池与法令。运行时只使用本项目配置包中的完整定义，目前仅启用农夫法令。AI 不得把已屏蔽的内置池当作运行时存在的数据，也不得再创建只覆盖少数字段的依赖式补丁。

## 1. AI 必须遵守的规则

1. **先查实际环境，再写配置。** 必须确认 Bountiful 版本、目标物品/实体/标签的注册名、已有池与法令；不得凭显示名称猜测注册名。
2. **只使用新版对象映射结构。** 当前池文件的 `content` 是“条目 ID → 条目对象”的 JSON 对象。不得套用旧版 `bounties.json`、`rewards.json`，不得把 `content` 写成旧版数组。
3. **默认使用配置包。** 本项目优先写入：
   - `Server/config/bountiful/bounty_pools/<池名>.json`
   - `Server/config/bountiful/bounty_decrees/<法令名>.json`
4. **默认追加或补丁修改，不覆盖整池。** 只有用户明确要求清空、重做或完全替换时，才使用顶层 `"replace": true`。
5. **保持修改最小化。** 修改现有条目时只写需要覆盖的字段；删除单条时用 `null`，不要复制整个原始池。
6. **所有数值都要能解释。** `unitWorth` 是单个单位的价值，不是整组价值。项目固定 `1 unitWorth = 1 莱特曼铜币`，价格必须是正整数，不允许半枚铜币。AI 必须检查目标价值区间能否覆盖奖励价值区间。
7. **不得制造套利闭环。** 奖励与目标若能直接互换、分解或低成本合成，必须用 `forbids`、调价或移除其中一端来阻断套利。
8. **JSON 必须严格合法。** 不得有注释、尾逗号、漏冒号、重复键；保存后至少通过 `jq empty`。
9. **高级字段必须有版本证据。** `components`、`requires`、`biomes` 已在当前 JAR 内置数据中出现；其他未验证字段不得擅自发明。
10. **修改后必须验证。** 至少进行 JSON 语法检查、注册名核对、游戏内重载和 Bountiful 测试；不能完成的验证要明确写出“未验证”，不得声称可用。
11. **本项目定价必须先读取 `BOUNTIFUL_BASE_PURCHASE_PRICES.md`。** 新增基础物资时沿用其中的铜币基准和防通胀规则；若要偏离，必须说明配方、产能或经济数据依据。
12. **禁止常驻系统收购建材。** 不得把泥土、石材、沙砾、沙子、黏土、原木、木板等建筑材料加入常驻目标池；建材需求只能设计为独立的限时社区工程，并需用户明确授权。

## 2. 核心概念

### 2.1 悬赏池 Pool

池是一组可供随机选择的目标或奖励。常见命名约定：

- `farmer_objs`：农夫目标池；`objs` 表示 objectives。
- `farmer_rews`：农夫奖励池；`rews` 表示 rewards。
- `_all_objs`、`_all_rews`：多个法令都会使用的通用池。

一个池文件并不主动出现于告示板。它必须被某个法令的 `objectives` 或 `rewards` 引用。

### 2.2 法令 Decree

法令决定生成悬赏时使用哪些目标池和奖励池。一个告示板若混用多个法令，且配置允许混合法令，则各法令的池会合并参与选择。

### 2.3 价值匹配

当前项目配置 `reverseMatchingAlgorithm` 为 `false`，通常先选奖励，再寻找总价值接近的目标：

```text
单条总价值 = 数量 × unitWorth
整张悬赏价值 = 所有条目的单条总价值之和
```

例如 3 个苹果、每个价值 250，则奖励价值为 `3 × 250 = 750`。目标不必与奖励绝对相等，但目标池应有足够的价值跨度供算法匹配。若悬赏经常生成 3 个以上目标，通常说明目标池缺少高价值条目。

稀有度和告示板声望会影响条目出现概率；声望还会给目标要求提供折扣。因此不要只按最低声望下的单张结果判断经济平衡。

## 3. 悬赏池文件结构

### 3.1 新增一个普通物品目标

文件：`Server/config/bountiful/bounty_pools/_all_objs.json`

```json
{
  "content": {
    "custom_torch_delivery": {
      "type": "item",
      "content": "minecraft:torch",
      "amount": {
        "min": 4,
        "max": 16
      },
      "unitWorth": 100
    }
  }
}
```

注意两层 `content` 含义不同：

- 顶层 `content`：池内所有条目的映射。
- 条目内 `content`：物品、标签、实体、触发器或命令的实际注册内容。

`custom_torch_delivery` 是条目 ID。它应当：

- 在同一个池内唯一；
- 使用稳定、可读的英文小写 `snake_case`；
- 一旦用于补丁或删除，不要随意改名。

### 3.2 完整替换一个池

仅在用户明确要求重做整个池时使用：

```json
{
  "replace": true,
  "content": {
    "custom_wheat_delivery": {
      "type": "item",
      "content": "minecraft:wheat",
      "amount": {
        "min": 8,
        "max": 32
      },
      "unitWorth": 50
    }
  }
}
```

清空整个池的合法写法是：

```json
{
  "replace": true,
  "content": {}
}
```

### 3.3 补丁修改已有条目

文件名必须与原池名相同，条目 ID 也必须精确匹配。只写要覆盖的字段：

```json
{
  "content": {
    "oak_sapling": {
      "unitWorth": 100
    }
  }
}
```

### 3.4 删除已有条目

```json
{
  "content": {
    "oak_sapling": null
  }
}
```

删除前必须从当前版本的原始池中确认真实条目 ID。物品注册名是 `minecraft:oak_sapling`，但条目 ID 可能是 `oak_sapling`，两者不能混淆。

## 4. 条目字段

### 4.1 基础字段

| 字段 | 类型 | 要求 |
| --- | --- | --- |
| `type` | 字符串 | 推荐使用 `item`、`item_tag`、`entity`、`criteria`、`command` 中适用的类型。当前 JAR 也存在 `minecraft:item`，但新配置优先遵循官网的简写 `item`。 |
| `content` | 字符串 | 内容注册名、标签、Criteria 触发器或命令。不得使用玩家看到的中文名称代替注册名。 |
| `amount.min` | 正整数 | 最小数量，必须小于或等于 `max`。通常不写 0。 |
| `amount.max` | 正整数 | 最大数量；物品数量还应考虑堆叠上限和玩家实际交付体验。 |
| `unitWorth` | 正数 | 单个单位的价值，负责目标与奖励的匹配。 |

### 4.2 可选字段

| 字段 | 用途与约束 |
| --- | --- |
| `rarity` | `COMMON`、`UNCOMMON`、`RARE`、`EPIC`、`LEGENDARY`。越稀有，低声望时通常越少出现。 |
| `weightMult` | 在稀有度之外微调权重。优先调 `rarity`，只在有明确理由时使用。正数；小于 1 降低出现率，大于 1 提高出现率。 |
| `timeMult` | 调整该条目带来的完成时间。击杀、探索或等待类目标通常需要更高值。 |
| `name` | 直接显示的名称。它不具备完整本地化能力；公开整合包优先使用翻译键/资源包方案。 |
| `icon` | 为难以自动显示的条目指定物品图标，尤其是 `criteria`。使用有效物品注册名。 |
| `repRequired` | 告示板声望硬门槛；官网说明仅对奖励生效。 |
| `forbids` | 禁止与指定条目出现在悬赏的对立两侧。数组元素只能包含 `type` 和 `content`。 |
| `conditions` | 仅供 `criteria` 使用，结构必须符合相应 Minecraft Criteria 触发器。 |
| `components` | Minecraft 1.21 物品组件。当前 JAR 已用于药水内容、附魔等精确物品。优先于旧 NBT 猜写。 |
| `nbt` | 旧式或兼容内容的字符串 NBT。当前 JAR 的兼容数据仍有使用，但 1.21 新物品优先检查 `components`。 |
| `biomes` | 当前 JAR 的兼容池已使用，可按生物群系 ID 或 `#标签` 限制条目。官网当前页未完整说明，使用后必须游戏内验证。 |

池顶层还可能出现：

| 字段 | 用途与约束 |
| --- | --- |
| `replace` | `true` 时完全替换同名池；默认不要使用。 |
| `requires` | 当前 JAR 的跨模组兼容池用于声明所需模组 ID，例如 `"requires": ["farmersdelight"]`。 |
| `currency` | 当前 JAR 的 `currency_example.json` 使用该布尔字段标记货币池；属于高级用途，不得在普通目标/奖励池中随意添加。 |

## 5. 条目类型

### 5.1 `item`：精确物品

可作为目标，也可作为奖励。

```json
"bread_reward": {
  "type": "item",
  "content": "minecraft:bread",
  "amount": {
    "min": 2,
    "max": 8
  },
  "unitWorth": 165
}
```

若需要精确药水、附魔书或其他带组件物品，不要凭空手写组件。优先在游戏中手持目标物品执行 `/bo hand`，再核对复制出的 JSON。当前 1.21 内置药水示例：

```json
"water_potion": {
  "type": "item",
  "content": "minecraft:potion",
  "amount": {
    "min": 1,
    "max": 4
  },
  "unitWorth": 100,
  "components": {
    "minecraft:potion_contents": {
      "potion": "minecraft:water"
    }
  }
}
```

官网还允许在 `item` 的 `content` 中使用带 `#` 的物品标签，使生成结果随机替换成标签内某个具体物品，例如 `#minecraft:beds`。如果任务目标是“任意组合均可累计”，应使用下一节的 `item_tag`。

### 5.2 `item_tag`：任意标签成员累计

主要用于目标。`content` 写标签 ID，但**不要**加 `#`：

```json
"any_wool": {
  "type": "item_tag",
  "content": "minecraft:wool",
  "amount": {
    "min": 4,
    "max": 24
  },
  "unitWorth": 70
}
```

它可以接收该标签下不同物品的组合。当前 JAR 的内置 `item_tag` 条目都位于目标池；若计划把它用于奖励，必须先单独验证生成行为。

### 5.3 `entity`：击杀实体

只能作为目标，`content` 是实体类型注册名：

```json
"kill_zombies": {
  "type": "entity",
  "content": "minecraft:zombie",
  "amount": {
    "min": 1,
    "max": 4
  },
  "unitWorth": 250,
  "timeMult": 6.0
}
```

不要把 `entity` 放进奖励池。官网 latest 页面此处写成“只能作为奖励”，但它同时说明这是击杀任务，且当前 JAR 的实体条目实际位于 `_all_objs.json`；应按“只能作为目标”处理。

### 5.4 `criteria`：事件触发目标

只能作为目标。`content` 是 Minecraft Criteria 触发器，`conditions` 是该触发器条件：

```json
"catch_enchanted_book": {
  "type": "criteria",
  "content": "minecraft:fishing_rod_hooked",
  "conditions": {
    "item": {
      "items": [
        "minecraft:enchanted_book"
      ]
    }
  },
  "amount": {
    "min": 1,
    "max": 3
  },
  "unitWorth": 350,
  "timeMult": 2,
  "name": "钓起附魔书",
  "icon": "minecraft:fishing_rod"
}
```

规则：

- 自定义 Criteria 通常必须补充 `name` 和 `icon`，否则客户端可能不知道如何命名或展示。
- 禁止使用 `minecraft:enter_block` 和 `minecraft:tick`。
- Criteria 没有像进度系统那样的长期记忆；依赖跨多次触发累计唯一对象的条件可能不正确。
- 条件结构随 Minecraft 版本变化，必须依据当前版本数据格式验证。

### 5.5 `command`：服务器命令奖励

只能作为奖励。命令由服务器执行，因此属于高风险条目。官网列出的替换符：

- `%PLAYER_NAME%`：提交悬赏的玩家名。
- `%PLAYER_NAME_RANDOM%`：随机在线玩家名。
- `%PLAYER_POSITION%`：提交玩家的 `x y z` 坐标。
- `%BOUNTY_AMOUNT%`：该奖励生成的数量。

当前 JAR 默认池没有可供核对的 `command` 示例。AI 未经实际测试不得生成涉及 OP、权限、封禁、停服、任意 NBT 注入或可被玩家输入劫持的命令奖励。若用户确实需要，应先制作最小无害测试条目并确认 8.0.0-beta.2 的命令格式与替换符仍有效。

## 6. `forbids` 防套利示例

例如奖励铜制胸甲时，禁止悬赏另一侧要求铜锭：

```json
"copper_chestplate_reward": {
  "type": "item",
  "content": "example:copper_chestplate",
  "amount": {
    "min": 1,
    "max": 1
  },
  "unitWorth": 6400,
  "forbids": [
    {
      "type": "item",
      "content": "minecraft:copper_ingot"
    }
  ]
}
```

`forbids` 中的对象只能有 `type` 与 `content`，不得加入 `amount`、`unitWorth` 等字段。

AI 还必须主动检查：

- 原材料目标 ↔ 成品奖励；
- 成品目标 ↔ 可拆解回原材料的奖励；
- 商店固定低价购入物 ↔ 高价值悬赏奖励；
- 自动化大量生产物 ↔ 稀有资源奖励；
- 同一物品同时存在“低估目标”和“高估奖励”的价差。

## 7. 法令文件

文件：`Server/config/bountiful/bounty_decrees/farmer.json`

当前 JAR 的农夫法令结构如下：

```json
{
  "linkedProfessions": [
    "farmer"
  ],
  "objectives": [
    "farmer_objs",
    "_all_objs"
  ],
  "rewards": [
    "farmer_rews",
    "_all_rews",
    "_gardening_rews"
  ]
}
```

规则：

- `objectives` 只能引用目标池，`rewards` 只能引用奖励池。
- 池名不带 `.json`。
- 新建池后若没有被任何法令引用，它不会参与该法令的悬赏生成。
- 当前 JAR 使用 `linkedProfessions` 关联村民职业。
- 官网还列出法令可选字段 `name`、`canSpawn`、`canReveal`、`canWanderBuy`、`replace`。使用前应在当前 8.0.0-beta.2 中验证；客户端要显示自定义名称时通常也需要同步资源或配置。

## 8. 价值设计方法

本项目已经建立第一版价格锚点，详见 [`BOUNTIFUL_BASE_PURCHASE_PRICES.md`](BOUNTIFUL_BASE_PURCHASE_PRICES.md)。其中固定 `1 unitWorth = 1 莱特曼铜币`，铜币、铁币、金币分别为 `1 / 10 / 100`，所有商品价格必须是正整数。AI 不得继续采用悬赏附属包原始的 `100 / 200 / 400 / 600 / 800 / 1000` 硬币估值，也不得生成小数铜币价格。

### 8.1 先建立基准，再定价值

AI 不应看到“钻石”就随意写 `10000`。应先收集：

1. 当前内置池中相近物品的 `unitWorth`；
2. 服务器商店买入价、卖出价或玩家货币体系；
3. 合成原料成本与可逆分解关系；
4. 生产时间、自动化程度、运输难度和风险；
5. 服务器希望鼓励的活动，而不只是物品稀有度。

### 8.2 检查价值覆盖

对每个条目计算：

```text
最小总价值 = amount.min × unitWorth
最大总价值 = amount.max × unitWorth
```

同一个法令的目标池应能用 1～2 个目标覆盖主要奖励区间。高价值奖励必须有相应的高价值目标，否则算法会不断追加低价值目标或无法形成合理悬赏。

### 8.3 长线生活服建议

- 常见农产品、建材适合较宽数量区间和低到中等单位价值。
- 稀有收藏品、强力装备和不可再生资源应降低数量、提高稀有度，并谨慎限制声望。
- 奖励优先鼓励烹饪、建筑、探索和社区交易，避免持续抬高战斗数值。
- 自动化产物的价值不能只按手工采集时间计算。
- 不要让通用池中的高价值条目污染所有职业主题；主题奖励尽量放入专用池。

## 9. AI 标准工作流

### 第一步：发现

在修改前回答并记录：

- 当前 Bountiful JAR 的精确版本是什么？
- 用户要新增、补丁修改、删除还是完全替换？
- 目标池、奖励池和法令分别叫什么？
- 每个物品、实体、标签、触发器的注册名来自哪里？
- 是否涉及其他模组，服务器是否确实安装了它及依赖？
- 是否已有同 ID 条目或同内容的重复条目？

可从当前 JAR 查看内置数据：

```bash
jar tf 'Server/mods/[丰收]bountiful-neoforge-8.0.0-beta.2.jar' \
  | rg 'data/bountiful/(bounty_pools|bounty_decrees)/.*\.json$'
```

查看某个内置文件：

```bash
unzip -p 'Server/mods/[丰收]bountiful-neoforge-8.0.0-beta.2.jar' \
  'data/bountiful/bounty_pools/bountiful/farmer_objs.json' | jq .
```

如果 JAR 版本变化，命令中的文件名必须随实际文件调整，不能继续照抄本文路径。

### 第二步：设计

先输出简短设计表，至少包含：

| 池 | 条目 ID | 类型 | 内容 | 数量 | 单价 | 价值区间 | 稀有度 | 理由 |
| --- | --- | --- | --- | --- | ---: | ---: | --- | --- |
| `farmer_objs` | `custom_tomato` | `item` | `example:tomato` | 8～32 | 60 | 480～1920 | `COMMON` | 可再生农作物 |

若没有经济数据，应明确标记为“初始估值，需试玩校准”，不要伪装成精确平衡结果。

### 第三步：实现

- 优先创建配置包补丁，而不是改 JAR。
- 不改动用户未授权的池或法令。
- 不使用 `replace: true`，除非用户明确要覆盖全部原数据。
- JSON 中不写解释性注释；设计理由放在单独 Markdown 或交付说明中。

### 第四步：静态验证

对每个 JSON 文件执行：

```bash
jq empty Server/config/bountiful/bounty_pools/*.json
jq empty Server/config/bountiful/bounty_decrees/*.json
```

并逐项检查：

- 顶层是对象，池文件含对象类型的 `content`；
- 条目 ID 没有重复或误拼；
- `amount.min`、`amount.max` 是正整数且 `min <= max`；
- `unitWorth > 0`，`weightMult > 0`，`timeMult > 0`；
- 稀有度只使用规定的大写枚举；
- 物品、实体、标签和组件均存在；
- 法令引用的池确实存在或来自内置数据；
- 目标类型没有放进奖励池，奖励类型没有放进目标池；
- 没有明显合成、分解、商店或自动化套利。

### 第五步：游戏内验证

1. 备份当前可用配置。
2. 执行 `/reload`；若当前版本注册了 Bountiful 专用重载，也可用 `/bo reload`。
3. 执行 `/bo test` 检查非法物品名、数量等简单错误。
4. 使用 `/bo sample <法令ID> 2` 检查奖励能否匹配到足够价值的目标。官网建议法令至少能通过 level 2。
5. 使用 `/bo decree <法令ID>` 获取法令，并实际生成多张悬赏观察内容、数量、时间和稀有度。
6. 带组件物品先用 `/bo hand` 获取当前版本生成的结构，再对比配置。
7. 检查服务器日志中的 Bountiful 报错；不能只看聊天栏“重载完成”。

如果某命令在 8.0.0-beta.2 中不存在或参数改变，记录实际报错并以游戏内 `/bo` 帮助为准。

## 10. 常见错误与禁止事项

- 不要使用 1.12 的 `config/bountiful/bounties.json`、`rewards.json` 写法。
- 不要把新版顶层 `content` 写成数组。
- 不要使用旧字段 `amountRange`、`weight`、`nbt_data`，除非目标版本的真实文件证明需要它们。
- 不要把 `entity` 放进奖励池；它是击杀目标。
- 不要把 `command` 放进目标池；它是服务器命令奖励。
- `item_tag` 的 `content` 不加 `#`；`item` 随机抽取标签成员的写法才使用 `#标签`。
- 不要把显示名、翻译键和注册名混为一谈。
- 不要假设文件名等于条目 ID，也不要假设条目 ID 等于注册名。
- 不要复制官网 Criteria 示例中的尾逗号；严格 JSON 不允许尾逗号。
- 不要复制官网“清空池”示例里的 `"replace" true`；正确语法是 `"replace": true`。
- 不要仅因 `jq` 通过就宣称配置可用；`jq` 只验证 JSON 语法，不验证注册名、字段语义或经济平衡。
- 不要直接编辑模组 JAR；更新模组会覆盖改动，也不利于审计。

## 11. 可直接交给 AI 的指令模板

```text
你正在为本项目编写 Bountiful 悬赏配置。严格遵守 BOUNTIFUL_AI_GUIDE.md。

任务：<描述新增、修改、删除或替换内容>
目标法令：<法令 ID>
目标池：<目标池 ID>
奖励池：<奖励池 ID>
经济基准：<商店价格、已有 unitWorth 或待调试说明>
额外限制：<允许/禁止的模组、物品、命令、稀有度等>

执行要求：
1. 先读取当前 Bountiful JAR 版本和相关内置池/法令，不使用旧版格式。
2. 核验所有注册名，不得猜测。
3. 先给出条目设计与价值区间，再进行最小范围修改。
4. 默认使用配置包补丁，不使用 replace，除非任务明确要求完全替换。
5. 执行 JSON、字段、池引用、价值覆盖和套利检查。
6. 能运行服务器时执行 /reload、/bo test、/bo sample <法令ID> 2；不能运行时明确列出未完成验证。
7. 最终报告修改文件、每条价值依据、验证结果和仍需试玩观察的风险。
```

## 12. AI 交付报告格式

AI 完成修改后应按以下格式报告：

```text
修改结果
- 文件：...
- 动作：新增 / 补丁 / 删除 / 完全替换
- 影响法令：...

平衡摘要
- 目标价值范围：...
- 奖励价值范围：...
- 稀有度与声望考虑：...
- 防套利处理：...

验证
- JSON 语法：通过 / 未执行 / 失败
- 注册名：通过 / 待确认
- /reload：通过 / 未执行 / 失败
- /bo test：通过 / 未执行 / 失败
- /bo sample ... 2：通过 / 未执行 / 失败
- 实际生成测试：通过 / 未执行 / 失败

待观察
- ...
```

## 13. 资料来源与版本注意

- [Bountiful 最新版：Customizing Bounties](https://kambrik.ejekta.io/mods/bountiful/latest/CustomizingBounties)
- [Bountiful 最新版：File Structure](https://kambrik.ejekta.io/mods/bountiful/latest/FileStructure)
- [Bountiful：Bounty Generation](https://kambrik.ejekta.io/mods/bountiful/advanced/generation)
- 当前服务器本地 JAR：`Server/mods/[丰收]bountiful-neoforge-8.0.0-beta.2.jar`

官网 `latest` 页面与当前 Beta JAR 可能存在更新不同步或文字错误。发生冲突时采用以下优先级：

1. 当前服务器 JAR 内置数据与实际游戏测试；
2. 与当前版本对应的官方源码；
3. 官方 `latest` 文档；
4. 旧版本文档仅用于理解历史，不可直接复制语法。
