# tcth:chef 厨师职业预设（**不自动启用**）

本目录是未来 `tcth:chef` 职业的完整数据包预设，**TCTH 发布 JAR 不包含、不启用**
这些文件。当前服务器仍使用 `shiyun:chef`，本预设只用于：
- 规划 `tcth:chef` 的职业、能力树、奖励与文本；
- 在玩家七项取餐实机验证通过、且所有者决定迁移后启用。

## 启用方式（复制整个目录）

把 `docs/presets/tcth-chef/` **整个目录**复制为数据包，两种做法均可：

1. 复制到 `Server/world/datapacks/tcth-chef/`（含 `pack.mcmeta` + `data/`），
   然后 `/datapack list` / `/reload` 启用；
2. 或只复制 `pack.mcmeta` 与 `data/` 到任意数据包目录。

`pack.mcmeta`（pack_format 48 = MC 1.21.1）是数据包必需的。

```
docs/presets/tcth-chef/
├── pack.mcmeta
└── data/tcth/
    ├── jobsplus/jobs/chef.json                   职业定义
    ├── jobsplus/powerups/chef/*.json             能力树骨架（UI 层）
    ├── arc/chef/powerup/*.json                   能力树实际效果（经验倍率）
    ├── arc/chef/dish_cooked_common.json          基础奖励 COMMON
    ├── arc/chef/dish_cooked_t2.json              基础奖励 T2
    ├── arc/chef/dish_cooked_t3.json              基础奖励 T3
    ├── arc/chef/dish_cooked_excellent.json       品质额外奖励
    ├── dish_tiers/recipes/...                    按配方分级
    └── dish_tiers/items/...                      按物品分级
    （语言资源由 TCTH Integration 模组 assets/tcth/lang 提供）
```

## 启用门槛（缺一不可）

1. 玩家七项取餐实机验证全部通过；
2. 服务器所有者确认执行从 `shiyun:chef` 到 `tcth:chef` 的迁移；
3. `config/tcth-common.toml` 中 `jobsPlusRewardsEnabled = true`。

达到门槛前：**不要**复制本预设进任何数据包，**不要**打开奖励开关，
**不要进食 `#tcth:chef_meals` 料理**（`taste_meal` 是独立 `arc:on_eat`
Action：只要本预设启用，食用即得 1 XP，与 `jobsPlusRewardsEnabled` 无关；
`jobsPlusRewardsEnabled` 只控制 `tcth:on_dish_cooked` 料理 Action 的发送）。

## 奖励规则（占位值，正式经济平衡后调整）

每道料理（非自动化）至多命中**一个**基础奖励：

| 等级 | 经验 | 条件 |
|---|---|---|
| COMMON | 1–2 | `tcth:dish_tier = COMMON` 且 `tcth:automated = false` |
| T2 | 3–5 | `tcth:dish_tier = T2` 且 `tcth:automated = false` |
| T3 | 6–10 | `tcth:dish_tier = T3` 且 `tcth:automated = false` |

EXCELLENT/SUPERB 品质可**额外**命中一次品质奖励：**+2–4 经验**（同样要求
非自动化）。自动化料理不发放任何经验；同一事件绝不会同时命中两个基础等级。

> **发布状态（品质额外奖励 = deferred）**：静态测试与 Arc 数据加载已通过，
> 但本轮实机 KC 料理均为 POOR/UNKNOWN 品质，玩家实际经验结算**尚未验证**。
> `dish_cooked_excellent.json` 保持启用（不删除、不关闭）。验证方式：制作
> 一份 EXCELLENT/SUPERB 料理，记录出锅前后经验，期望增量 = 对应 tier 基础
> 经验 + 2–4 XP。基础等级奖励不受影响。
>
> **当前发布状态：测试服可用，品质额外奖励待补充实机验证。**

## 能力树（Arc 实际效果）

- `culinary_experience_i`：`jobsplus:job_exp_multiplier` **1.5**，条件排除 II、III；
- `culinary_experience_ii`：倍率 **2.0**，条件排除 III；
- `culinary_experience_iii`：倍率 **2.5**，无排除条件。

倍率与 `jobsplus/powerups/chef/*.json` 的文字一致。

## 职责划分与职业翻译

- **预设数据包**（本目录）负责：职业定义、能力节点、Arc Action（奖励与条件）、
  recipe/item 等级映射。数据包的 `data` 目录**不提供**客户端语言资源。
- **名称与描述**由 TCTH Integration 模组提供：模组 JAR 的
  `assets/tcth/lang/en_us.json` / `zh_cn.json` 包含以下键：
  `jobsplus.job.tcth.chef.name/.description` 与
  `jobsplus.powerup.tcth.chef.culinary_experience_{i,ii,iii}.name/.description`
  （Jobs+ 的 `JobInstance`/powerup Serializer 不读取 JSON 内嵌的
  name/description；界面读取翻译键）。
- **客户端需要安装包含对应翻译资源的 TCTH Integration 匹配版本**，否则职业与
  能力节点的名称/描述会缺失。

## 切换到 tcth:chef（测试服直接切换方案）

**本测试服不做玩家职业数据迁移**：不保留 `shiyun:chef` 的等级、经验或能力
节点。启用 `tcth:chef` 后，玩家从**新职业的初始状态**开始（等级 0、无经验、
无已购能力节点）。

1. 备份 `Server/world/datapacks/shiyun_jobs`（可选，回退用）。
2. 启用本预设：复制 `tcth-chef/` 到 `Server/world/datapacks/tcth-chef/`，
   `/reload`。
3. 停用 `shiyun:chef` 职业数据（从 `shiyun_jobs` 移除
   `data/shiyun/jobsplus/jobs/chef.json`、`powerups/chef`、`arc/chef`，
   仅职业相关内容；或直接停用整个 shiyun_jobs 数据包，按服务器需要决定）。
4. 玩家在 Jobs+ 界面选择/启用 `tcth:chef`（新职业，初始状态）。
5. 旧 `shiyun:chef` 的等级、经验、能力节点**不作迁移**——若以后需要
   "继承旧进度"，那是单独的一次性策略（本预设不含）。

### 回滚：tcth:chef → shiyun:chef

1. 停止服务器。
2. 移除 `tcth-chef` 数据包；恢复 `shiyun_jobs`（若之前停用了）。
3. 启动验证 `shiyun:chef` 正常。
4. 若玩家已在 `tcth:chef` 下获得进度，该进度随 `tcth:chef` 停用不再可用
   （测试服可接受；正式服迁移另定策略）。

## 客户端要求（重要）

启用 Jobs+/Arc 联动或本 `tcth-chef` 预设时，**服务器与所有客户端都必须安装
匹配版本的 TCTH Integration**（TCTH 注册了自定义 Arc Action/Condition/DataType
并携带翻译资源；客户端缺失会导致 Jobs+ GUI 无法解析或职业文本缺失）。

## 约束

- 不修改、不删除 `Server/world/datapacks/shiyun_jobs`（除非执行上述停用步骤）。
- 不修改服务器职业/经济/悬赏/世界配置。
- 不修改真实 `world/playerdata`（无玩家数据迁移）。
- 预设中所有数值均为占位。
