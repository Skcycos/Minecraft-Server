# 禁用合成清单（怎么用）

## 你只需要改一个文件

编辑：

`kubejs/startup_scripts/disabled_craft_registry.js`

在 `DISABLED_CRAFT_ENTRIES` 数组里**增加或删除**条目即可。

```js
{ id: 'create:deployer', reason: '自动交互/摆放，可绕过操作限制' },
```

- `id`：物品 ID（必填）
- `reason`：鼠标悬停时显示的原因（可选）

### 正则匹配条目

想用正则批量禁用（例如带 NBT 的药水变体、某模组一类物品），用 `pattern` 字段：

```js
{ pattern: /caverns_and_chasms:.*_potion/, reason: '禁用全部 potion（含 NBT 变体）' },
{ id: 'caverns_and_chasms:tether_potion[potion_contents={potion:"minecraft:night_vision"}]', reason: '只禁用夜视 tether' },
```

- `pattern`：JS 正则，删除配方与 tooltip 都会按此匹配（`/regex/` 对象）
- `id`：仍支持带 NBT 的完整字符串，只精确匹配该变体
- 同一个数组里 `id` 和 `pattern` 可以混用；正则条目无法用 `Item.of` 校验物品是否存在，会直接作为 Ingredient 添加

### 按配方 ID 禁用（DISABLED_RECIPE_IDS）

同一物品靠 NBT 区分多个变体（如 Easy Mob Farm 的 T0~T3 农场是同一物品）、只想禁用某个变体的合成时，把**配方 ID**（不是物品 ID）加到同文件里的 `DISABLED_RECIPE_IDS` 数组：

```js
{ id: 'easy_mob_farm:mob_farm/ocean_farm/tier0_ocean_farm', reason: '其他方式获取' },
```

- 仅在服务端删除该配方（JEI 也搜不到），**不会**添加 tooltip（物品共享 id，无法只标注某个变体）
- 配方 ID 可在 mod jar 的 `data/<modid>/recipe/` 路径里查到

## 会自动同步什么？

| 脚本 | 作用 |
|---|---|
| `startup_scripts/disabled_craft_registry.js` | 唯一清单 → 写入 `global.SYDisabledCraft` |
| `server_scripts/disabled_craft_recipes.js` | 服务端删除这些物品的合成输出配方 |
| `client_scripts/disabled_craft_tooltips.js` | 客户端物品上显示「禁止合成」描述 |

**不需要再手写两份列表**，也不需要额外“同步脚本”——改完重启 / `/reload` 即可。

## 生效方式

- **服务端配方**：重启服，或游戏内 `/reload`（KubeJS 服务端脚本重载）
- **客户端描述**：客户端需有相同 `kubejs/`；改完后重启客户端，或 `F3+T` / KubeJS 客户端重载

## 重要：客户端整合包

本仓库是服务端目录。玩家要看到物品上的禁用说明，必须把下面目录同步进客户端整合包：

```
kubejs/startup_scripts/disabled_craft_registry.js
kubejs/client_scripts/disabled_craft_tooltips.js
```

（整份 `kubejs/` 一起拷最省事。）

JEI 在配方被删后也会搜不到合成，但 **tooltip 依赖客户端脚本**。

## 旧文件

`server_scripts/create_disabled_recipes.js` 已弃用，请勿再往里加内容。
