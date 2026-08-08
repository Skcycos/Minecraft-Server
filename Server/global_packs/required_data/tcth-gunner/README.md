# tcth:gunner 枪客职业预设（**不自动启用**）

本目录是 `tcth:gunner` 职业的完整数据包预设，**TCTH 发布 JAR 不包含、不启用**
这些文件。用于：规划 `tcth:gunner` 的职业、奖励与文本。

## 启用方式（复制整个目录）

把 `docs/presets/tcth-gunner/` **整个目录**复制为数据包，两种做法均可：

1. 复制到 `Server/world/datapacks/tcth-gunner/`（含 `pack.mcmeta` + `data/`），
   然后 `/datapack list` / `/reload` 启用；
2. 或只复制 `pack.mcmeta` 与 `data/` 到任意数据包目录。

`pack.mcmeta`（pack_format 48 = MC 1.21.1）是数据包必需的。

```
docs/presets/tcth-gunner/
├── pack.mcmeta
└── data/tcth/
    ├── jobsplus/jobs/gunner.json                    职业定义
    ├── arc/gunner/gun_kill_common.json              基础奖励 COMMON
    ├── arc/gunner/gun_kill_elite.json               基础奖励 ELITE
    ├── arc/gunner/gun_kill_heavy.json               基础奖励 HEAVY
    ├── arc/gunner/gun_kill_boss.json                基础奖励 BOSS
    └── tags/entity_type/gunner_targets/
        ├── common.json                              普通敌对生物
        ├── elite.json                               袭击者/#scguns:gunner
        ├── heavy.json                               #scguns:heavy/very_heavy
        ├── boss.json                                明确 BOSS
        └── excluded.json                            明确排除目标
    （语言资源由 TCTH Integration 模组 assets/tcth/lang 提供）
```

## 未来能力树（四路线，本阶段不实现）

职业保留 `tcth:gunner`。四条路线**互不冲突，可同时发展**；每条路线内部
**只允许最高已激活节点生效**，低级与高级效果**不叠加、不叠乘**。

```
枪术路线              游击路线              兵备路线              研修路线
枪术入门 (5级)        游走射击 (10级)       弹药管理 (15级)       枪械研修 I (25级)
└ 精准射击 (20级)     └ 疾行射击 (30级)    └ 整备有方 (35级)     └ 枪械研修 II (50级)
  └ 百步穿杨 (45级)     └ 且战且走 (60级)     └ 兵贵神速 (55级)     └ 枪械研修 III (75级)
```

## 奖励规则

每次枪械击杀（非自动化）至多命中**一个**基础奖励：

| 等级 | 经验 | 条件 |
|---|---|---|
| COMMON | 1～2 | `tcth:gun_target_tier = COMMON` 且 `tcth:automated = false` |
| ELITE | 3～5 | `tcth:gun_target_tier = ELITE` 且 `tcth:automated = false` |
| HEAVY | 6～10 | `tcth:gun_target_tier = HEAVY` 且 `tcth:automated = false` |
| BOSS | 12～20 | `tcth:gun_target_tier = BOSS` 且 `tcth:automated = false` |

## 职责划分与职业翻译

- **预设数据包**（本目录）负责：职业定义、Arc Action（奖励与条件）、
  目标分级标签。数据包的 `data` 目录**不提供**客户端语言资源。
- **名称与描述**由 TCTH Integration 模组提供：模组 JAR 的
  `assets/tcth/lang/en_us.json` / `zh_cn.json` 包含
  `jobsplus.job.tcth.gunner.name/.description`。
- **客户端需要安装包含对应翻译资源的 TCTH Integration 匹配版本**。

## 约束

- 不修改、不删除 `Server/world/datapacks/shiyun_jobs`。
- 不修改服务器职业/经济/悬赏/世界配置。
- 不修改真实 `world/playerdata`（无玩家数据迁移、不手工编辑 NBT）。
- 本阶段不实现能力树，只在 README 记录未来四路线设计。
- 所有数值均为阶段 5A 正式设计值。
