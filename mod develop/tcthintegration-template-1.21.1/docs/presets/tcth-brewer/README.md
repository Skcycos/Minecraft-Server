# TCTH Brewer 预设（草稿，未部署）

> **状态：DRAFT — 不部署、不启用职业、不发放经验。**

职业定义：
- job id：`tcth:brewer`
- zh_cn：魔酿师
- en_us：Mystic Brewer
- 与 `tcth:chef` 完全独立；不迁移玩家数据；不恢复 Jobs+ 默认 alchemist；不发金币、不改悬赏

## 内容

- `pack.mcmeta` — 数据包元数据（pack_format 48）
- `data/tcth/jobsplus/jobs/brewer.json` — Jobs+ 职业定义骨架（未启用，`is_default: false`）
- `data/tcth/tags/item/brewer_drinks.json` — **已填充 64 个正式饮品**（DRINK_COMMON 18 + DRINK_T2 46，7A 审计定稿）
- `data/tcth/brewer/tiers.json` — **非运行时审计草案**（含 T3_CANDIDATE 6 / INGREDIENT 2 供人工决策；运行时 BeverageTier 仅 UNKNOWN/COMMON/T2/T3）

语言键（已加入 TCTH 主模组 `assets/tcth/lang/`）：
- zh_cn：`jobsplus.job.tcth.brewer.name` = 魔酿师；`...description` = 调制饮品、掌握发酵工艺，并在一次次斟饮中探索奇妙风味。
- en_us：Mystic Brewer / Mix drinks, master fermentation, and uncover strange flavors one pour at a time.

## 边界

本阶段（7A）**不创建**真实经验 Action、不启用职业、不部署数据包、不写 Mixin、不发放经验/金币、不把饮品重新加入厨师、不改 UNITE/playerdata。

**T3 候选不会自动启用**：`tiers.json` 中 `DRINK_T3_CANDIDATE` 仅供人工决策，运行时 BeverageTier 无 T3_CANDIDATE，当前 T3 不启用。

## 后续（7B+）

设备注入点候选：BAC Keg（`useItemOn → extractInWorld → fluidExtract → getPouringRecipe`，玩家灌装交付）。
