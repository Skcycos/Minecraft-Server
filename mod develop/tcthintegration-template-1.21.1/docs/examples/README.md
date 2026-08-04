# TCTH Jobs+ / Arc 示例数据（examples — 不自动启用）

这些文件是**示例**，TCTH 发布包**不包含**启用状态的奖励数据。
要启用它们，请把 JSON 复制到你的数据包中对应的路径，并且：

- 服务器上已经存在对应的职业（本服务器当前是 `shiyun:chef`；`tcth:chef`
  的迁移与正式数值在后续阶段单独完成，**不要**在本阶段启用第二个 chef）；
- 已经通过玩家七项取餐实机验证；
- `config/tcth-common.toml` 中 `jobsPlusRewardsEnabled = true`。

在满足以上条件前，请保持奖励默认关闭，不要部署会自动发经验的 Arc 数据。

---

## 1) 料理分级（DishTierManager）

路径规则：

```
data/<namespace>/dish_tiers/recipes/<recipe-namespace>/<recipe-path>.json
data/<namespace>/dish_tiers/items/<item-namespace>/<item-path>.json
```

### 按配方（Farmer's Delight 等有 recipeId 时优先）
`data/tcth/dish_tiers/recipes/farmersdelight/cooking/cooked_rice.json`:

```json
{ "tier": "T3" }
```

### 按物品（原版 / Kaleidoscope Cookery recipeId=null 时回退）
`data/tcth/dish_tiers/items/minecraft/cooked_beef.json`:

```json
{ "tier": "T2" }
```

优先级：recipe 映射 > item 映射；两者都没有时不发送奖励 Action。
同一道料理最终只会命中一个 tier（recipe 覆盖 item）。

## 2) Arc 奖励 Action（示例，启用前必须满足上文条件）

路径：`data/tcth/arc/chef/dish_cooked_<tier>.json`
（Arc 的 reload 目录是 `data/<ns>/arc/<...>`，注意**不是**
`data/tcth/arc/tcth/chef/...`）

奖励按 tier 分开，便于分级控制：

### COMMON
`data/tcth/arc/chef/dish_cooked_common.json`:

```json
{
  "holder": { "type": "jobsplus:job", "id": "<job-id>" },
  "type": "tcth:on_dish_cooked",
  "rewards": [ { "type": "jobsplus:job_exp", "chance": 100, "min": 1, "max": 2 } ],
  "conditions": [ { "type": "tcth:dish_tier", "tier": "COMMON" } ]
}
```

### T2
`data/tcth/arc/chef/dish_cooked_t2.json`:

```json
{
  "holder": { "type": "jobsplus:job", "id": "<job-id>" },
  "type": "tcth:on_dish_cooked",
  "rewards": [ { "type": "jobsplus:job_exp", "chance": 100, "min": 3, "max": 5 } ],
  "conditions": [ { "type": "tcth:dish_tier", "tier": "T2" } ]
}
```

### T3
`data/tcth/arc/chef/dish_cooked_t3.json`:

```json
{
  "holder": { "type": "jobsplus:job", "id": "<job-id>" },
  "type": "tcth:on_dish_cooked",
  "rewards": [ { "type": "jobsplus:job_exp", "chance": 100, "min": 6, "max": 10 } ],
  "conditions": [ { "type": "tcth:dish_tier", "tier": "T3" } ]
}
```

### 非自动化（防止自动化刷经验）
`data/tcth/arc/chef/dish_cooked_manual.json`:

```json
{
  "holder": { "type": "jobsplus:job", "id": "<job-id>" },
  "type": "tcth:on_dish_cooked",
  "rewards": [ { "type": "jobsplus:job_exp", "chance": 100, "min": 1, "max": 2 } ],
  "conditions": [ { "type": "tcth:automated", "value": false } ]
}
```

### 品质额外奖励（Kaleidoscope Cookery 品质）
`data/tcth/arc/chef/dish_cooked_excellent.json`:

```json
{
  "holder": { "type": "jobsplus:job", "id": "<job-id>" },
  "type": "tcth:on_dish_cooked",
  "rewards": [ { "type": "jobsplus:job_exp", "chance": 100, "min": 2, "max": 4 } ],
  "conditions": [ { "type": "tcth:dish_quality", "quality": ["EXCELLENT", "SUPERB"] } ]
}
```

### 使用 Arc 原生 `arc:items` 条件（按料理物品）
`data/tcth/arc/chef/dish_cooked_special.json`:

```json
{
  "holder": { "type": "jobsplus:job", "id": "<job-id>" },
  "type": "tcth:on_dish_cooked",
  "rewards": [ { "type": "jobsplus:job_exp", "chance": 100, "min": 5, "max": 8 } ],
  "conditions": [
    { "type": "arc:items", "items": ["minecraft:cooked_beef"] }
  ]
}
```

`arc:items` 通过 `ActionDataType.ITEM_STACK` / `ActionDataType.ITEM` 读取料理
结果，可匹配物品或标签（如 `#tcth:chef_cooked_foods`）。

### `<job-id>` 替换位置

- 当前服务器已有 **`shiyun:chef`**：把上面所有 `<job-id>` 替换为
  `shiyun:chef` 即可对接现有职业（本阶段推荐做法，不新建职业）。
- 未来 **`tcth:chef`** 预设（见 `docs/presets/tcth-chef/`）：在正式迁移完成、
  且旧职业数据停用后，再把 `<job-id>` 替换为 `tcth:chef`。
  同一时刻只应存在一个 chef 职业，避免重复。

## 3) TCTH 条件类型（已注册，可在 rewards/conditions 中使用）

| 条件 | JSON 示例 |
|---|---|
| `tcth:dish_tier` | `{ "type": "tcth:dish_tier", "tier": "T3" }` |
| `tcth:dish_quality` | `{ "type": "tcth:dish_quality", "quality": ["EXCELLENT", "SUPERB"] }` |
| `tcth:cooking_device` | `{ "type": "tcth:cooking_device", "devices": ["FARMERS_DELIGHT_COOKING_POT", "KALEIDOSCOPE_STEAMER"] }` |
| `tcth:automated` | `{ "type": "tcth:automated", "value": false }` |

全部支持 `"inverted": true`；名称大小写不敏感；未知 tier/quality/device
会在数据加载时报清晰错误。

## 3) 调试

```text
/tcth debug cooking on
/tcth debug cooking off
/tcth debug cooking status
```

开启后每次料理事件会在日志打印
`[TCTH][debug] dish event id=... device=... result=... count=... player=...
recipeId=... quality=... automated=... pos=...`。
