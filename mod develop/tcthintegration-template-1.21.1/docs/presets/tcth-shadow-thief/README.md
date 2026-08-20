# TCTH 影窃者（Shadow Thief）预设（草稿，未部署）

> **状态：DRAFT — 不部署、不启用职业、不发放经验、不开启任何 Shadow 生产开关。**

职业定义：
- job id：`tcth:shadow_thief`
- zh_cn：影窃者
- en_us：Shadow Thief
- 与 chef/farmer/gunner/brewer 完全独立；`is_default: false`、`max_level: 100`；
  图标 `minecraft:echo_shard`（已注册原版物品）
- 名称与描述**不硬编码在职业 JSON**，使用语言键：
  - `jobsplus.job.tcth.shadow_thief.name` / `...description`（已加入 TCTH 主模组 `assets/tcth/lang/`，中英双语）

## 内容

- `pack.mcmeta` — 数据包元数据（pack_format 48）
- `data/tcth/jobsplus/jobs/shadow_thief.json` — Jobs+ 职业定义（未启用）
- `data/tcth/jobsplus/powerups/shadow_thief/<node>.json` — 四路线 12 节点
  （required_level / price / parent 精确匹配阶段契约）
- `data/tcth/arc/shadow_thief/<kind>.json` — 成功经验 Action
  （`tcth:on_shadow_theft_success`，holder = `jobsplus:job`，
  rewards = `jobsplus:job_exp`：ENTITY 1–2；PLAYER ITEM 3–5、HEALTH 2–4、
  HUNGER 2–4、EFFECT 4–6）
- `data/tcth/arc/shadow_thief/powerup/<node>.json` — 每节点 holder =
  `jobsplus:powerup` 的互斥声明（`jobsplus:powerup_not_active`：I 排除
  II/III、II 排除 III、III 无排除；空 rewards——节点效果由 Java 快照层
  驱动，仅取最高 ACTIVE 档，不叠加）

## 四路线（12 节点）

| 路线 | I | II | III |
|---|---|---|---|
| 妙手 Sleight | 顺手牵羊 +5% 成功率、冷却 180 | 探囊取物 +10%、冷却 160、高价值 -5% | 无影之手 +15%、冷却 140、高价值 0 |
| 夺生 Life Siphon | 偷生 生命1/饥饿2 | 噬息 生命2/饥饿3 | 夺命契约 生命4/饥饿4 |
| 窃法 Spell Theft | 盗取余韵 ≤10s | 移花接木 ≤20s | 窃法宗师 ≤30s |
| 潜影 Shadow Escape | 敛息 速度I 4s、失败暴露 ×0.8 | 遁影 速度I 6s+隐身2s、×0.6 | 无踪 速度II 8s+隐身4s、×0.4 |

- 节点 ID 为阶段契约固定值，后续不得改名。
- 每路线同一时间只按最高 ACTIVE 档生效（Java `highestActiveTier` 强制 +
  数据 `powerup_not_active` 声明双保险）。
- 能力条件组合：`Config.ENABLED && shadowThiefIntegrationEnabled &&
  shadowAbilitiesEnabled && <路线开关>`；任何读取异常 fail-closed。

## 经验

- 仅 `outcome=SUCCESS`、receipt 匹配 theftType、非 automated、真实
  ServerPlayer、审计已 FINAL、eventId 未结算时发送 Action。
- 玩家目标每日每对（thief+target+UTC 日期）最多 3 次成功影窃获得职业
  经验（`shadowMaxExperienceRewardsPerPairPerDay=3`）；实体目标由
  LOOTED 一次性状态限制。
- 全部 0 经验：COIN / FAILED_ROLL / NO_CANDIDATE / COOLDOWN / DUPLICATE /
  PROTECTED / TRANSFER_FAILED / FAILED_CLEAN / ROLLED_BACK /
  RECOVERY_REQUIRED / AUDIT_FAILED / FRAMEWORK_DISABLED / INVALID_CONTEXT /
  automated=true。
- 不发金币、不发物品、不复制掉落；不增加第二套经验。

## 边界

- `shadowThiefIntegrationEnabled` / `shadowPlayerTheftEnabled` /
  `shadowEntityTheftEnabled` / `shadowRealAssetTransfersEnabled` /
  `shadowRewardsEnabled` 默认全部 false；本阶段不修改服务器配置。
- COIN 仍 BLOCKED，不得接入。
- 本预设不进主 JAR；部署前需运营确认 + 在线验收。
