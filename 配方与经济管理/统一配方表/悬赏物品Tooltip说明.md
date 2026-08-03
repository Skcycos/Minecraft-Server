# 悬赏食物 Tooltip

悬停物品时显示：

- **★ 悬赏收购**
- 档位（T1 通用 / T2 家常 / T3 名菜）
- **收购价：N 铜币/个**（= Bountiful `unitWorth`）
- 常见数量范围
- T3 高价项会显示告示板声望要求

## 脚本

| 文件 | 作用 |
|---|---|
| `Server/kubejs/startup_scripts/bounty_food_registry.js` | 清单（从三个 food_* 池生成） |
| `Server/kubejs/client_scripts/bounty_food_tooltips.js` | 客户端描述 |

## 改价后如何更新 tip

1. 改 `config/bountiful/bounty_pools/food_*.json`
2. 重新生成 `bounty_food_registry.js`（或让 AI/脚本同步）
3. **客户端同步 kubejs/** 并重启或 F3+T

与禁用合成 tip 相同：仅服务端改脚本，客户端看不到描述。
