# tcth:farmer 农夫职业预设（**不自动启用**）

本目录是 `tcth:farmer` 职业的完整数据包预设，**TCTH 发布 JAR 不包含、不启用**
这些文件。测试服启用方式为复制整个目录到
`Server/world/datapacks/tcth-farmer/`。本目录用于规划 `tcth:farmer` 的职业、
基础经验、能力树设计与文本。

## 启用方式（复制整个目录）

把 `docs/presets/tcth-farmer/` **整个目录**复制为数据包（含 `pack.mcmeta` +
`data/`）到 `Server/world/datapacks/tcth-farmer/`，然后 `/datapack list` /
`/reload` 启用。`pack.mcmeta`（pack_format 48 = MC 1.21.1）是数据包必需的。

```
docs/presets/tcth-farmer/
├── pack.mcmeta
└── data/tcth/
    ├── jobsplus/jobs/farmer.json      职业定义（is_default: false, max_level: 100）
    ├── arc/farmer/*.json              基础经验（阶段 4A.2：4 个 Arc Action）
    └── tags/block/farmer_*.json       农事方块标签（harvestables/vertical/excluded）
    （语言资源由 TCTH Integration 模组 assets/tcth/lang 提供）
```

## 阶段 4A.2：基础经验（统一收获事件）

| 行为 | 经验 | Action 类型 | 条件 / 说明 |
|---|---:|---|---|
| 种植作物 | **0 XP（不创建奖励 Action）** | — | 无 `on_plant_crop` action |
| 收获成熟作物（破坏/右键） | 1–2 | `tcth:on_crop_harvested` | `tcth:automated=false` |
| 繁殖动物成功 | 3–5 | `arc:on_breed_animal` | 幼崽生成时按繁殖发起者结算 |
| 驯服动物成功 | 8–12 | `arc:on_tame_animal` | 成功驯服事件 |
| 剪取已长毛绵羊 | 1–2 | `arc:on_interact_entity` | 绵羊 + `arc:ready_for_shearing` + 手持剪刀 |

阶段 4A.2 迁移：收获经验不再使用 `arc:on_harvest_crop`（该 Action 已删除），
改由 TCTH 统一事件 `CropHarvestedEvent`（`tcth:on_crop_harvested`）结算：
破坏检测器（NeoForge `BlockEvent.BreakEvent`，LOWEST 优先级）+ 右键采摘
Mixin（原版甜浆果、FD 番茄、KC 水稻[Base] / KC 辣椒[专项]）。**不得同时
保留两种收获 Action，否则双倍经验。** KC 生菜右键覆写为非收获路径（返回
PASS），**仅 BREAK 收获**，不为其创建无效右键 Mixin。

规则：

- 种植不发经验；未成熟作物不发事件。
- **自动化边界（如实描述）**：统一事件由 TCTH 发布——无 `ServerPlayer`
  上下文的机械收割（如 Create 收割机传 `null` Player）不会触发；`FakePlayer`
  及子类被 TCTH 显式判定为 automated 并拒绝发布（`CropHarvestedEventDispatcher`
  返回 `AUTOMATED_REJECTED`）。非 FakePlayer 的机器人玩家属已知边界，
  不伪称全部排除。
- `CropHarvestRules` 成熟判定：排除标签优先 → 甘蔗/仙人掌仅上层 →
  `CropBlock` 真实最大年龄 → 可可豆/下界疣 age 最大值 → `farmer_harvestables`
  标签（age 验证）→ 未识别方块 fail-closed。**南瓜/西瓜不再发收获事件**
  （放置-破坏可刷经验，用户决策移除，见 4A.3 报告）。
- 其余事件防刷边界：繁殖/驯服/剪毛同前（成功才触发、失败不发）。
- **覆盖范围**：成熟破坏（原版谷物/甜菜、FD 卷心菜/洋葱/水稻穗/番茄、
  KC 水稻/辣椒）与右键采摘（甜浆果、FD 番茄、KC 水稻[Base] / 辣椒[专项]）均发事件；
  作物梗、甜浆果破坏、FD 水稻下半部分不发事件；南瓜/西瓜不发事件。覆盖矩阵与 FakePlayer 审计详见
  `docs/phase-4a.1-farmer-audit.md`；统一事件设计见
  `docs/phase-4a.2-crop-harvest-event-report.md`。
- **同一行为只结算一次**：每次事件只结算一次，不按掉落数量倍增
  （不使用 `arc:block_drop_multiplier`，只用 `jobsplus:job_exp`）。
- **不发金币**：所有 reward 均为 `jobsplus:job_exp`，无 coin reward。
- **不修改悬赏（Bountiful）和收购价**：本预设不含 Bountiful 数据。
- **不修改 `tcth:chef`**：厨师职业与其已有进度不受影响。

## 阶段 4C 设计说明（**本阶段只留设计，不实现能力效果**）

关闭 Jobs+ 默认职业后，不再为 `jobsplus:farmer` 的旧节点
（`double_drops_i/ii/iii`、`job_exp_i/ii/iii`）制作覆盖。
`enable_default_jobs=false` 停用的是默认 **Job 实例**（对应 Job 不可用、
统计被清除）；Jobs+ JAR 内置的 Arc Action 与 Powerup **数据文件仍可能被
数据加载器解析**，但因为没有可用 Job 不会产生实际结算。阶段 4C 为
`tcth:farmer` 重新设计完整四路线能力树，
**不复用旧节点 ID**：

| 路线 | 主题 |
|---|---|
| 耕作路线 | 种植、土壤与农具效率 |
| 丰收路线 | 收获、双倍掉落与果实加成 |
| 畜牧路线 | 繁殖、驯服与剪毛效率 |
| 研修路线 | 职业经验倍率（互斥，最高一档生效） |

具体节点、等级、价格与效果在阶段 4C 设计；阶段 4A 不创建
`jobsplus/powerups/farmer/` 与 `arc/farmer/powerup/` 文件。

## 职业翻译与职责划分

- **预设数据包**（本目录）负责：职业定义、基础经验 Arc Action。数据包
  `data` 目录**不提供**客户端语言资源，也不内嵌 name/description。
- **名称与描述**由 TCTH Integration 模组提供：模组 JAR 的
  `assets/tcth/lang/en_us.json` / `zh_cn.json` 包含
  `jobsplus.job.tcth.farmer.name` / `.description`
  （Jobs+ 的 JobInstance.Serializer 不读取 JSON 内嵌 name/description；
  界面读取翻译键）。
- **客户端需要安装包含对应翻译资源的匹配版本 TCTH Integration**，否则
  农夫职业名称/描述会缺失。

## 约束

- 不修改、不删除 `Server/world/datapacks/tcth-chef`。
- 不修改服务器经济/悬赏/世界配置（Jobs+ 默认职业停用除外）。
- 不修改真实 `world/playerdata`（无玩家数据迁移、不手工编辑 NBT）。
- 不迁移 `jobsplus:farmer` 的等级、经验、能力节点或玩家数据。
