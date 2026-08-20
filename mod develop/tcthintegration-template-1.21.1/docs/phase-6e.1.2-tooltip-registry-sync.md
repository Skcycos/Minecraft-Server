# 阶段 6E.1.2 —— Tooltip registry-only 同步

## 1. 目标与边界

- 只将当前正式 Bountiful food pool 的 327 个料理同步到
  `Server/kubejs/startup_scripts/bounty_food_registry.js`，修复客户端 tooltip registry
  缺失问题。
- 未触碰生产副本 `/Users/a1111/Desktop/Mc_Server_0.1/Minecraft-Server/Server`。
- 未启动/停止任何 Minecraft 服务端。
- 未修改 food_common/t2/t3_objs.json、cook.json、decree、playerdata、价格表、原料参考表、
  分类表、8E Java 工作区、生成器、RecipeManager 导出逻辑、配方文件。
- 未运行完整 sync_bounty_economy.py；未人工改价（unitWorth/amount/weightMult 均取自 pool）。
- 未 commit/push。

## 2. 备份

- 备份路径：`/tmp/Minecraft-Server-phase6e1.2-backup-20260820/bounty_food_registry.js`
- 备份 SHA-256：`0085c1fdbc27241254fc0f73f6759f21cf0b2de69060e07aeae238f1ec18862b`
- 大小：20,527 字节；mtime：2026-08-04 14:59:03

## 3. 同步方法

- 读取三个正式 pool（common/t2/t3），按各自插入顺序展开 content。
- 保留 registry 现有文件结构、头注释、字段命名（id/tier/tierName/unitWorth/amountMin/
  amountMax）与尾部 global.SYBountyFood 结构。
- 池→tier 映射：common→T1（通用/基础）、t2→T2（家常菜）、t3→T3（名菜），tierName 沿用
  原 registry 既有映射，不新增。
- 每条目值直接取自 pool 的 unitWorth、amount.min/max，不人工改价。

## 4. 结果

- 修改前 registry 数量：157（T1 69 / T2 58 / T3 30）
- 修改后 registry 数量：327（T1 107 / T2 190 / T3 30）
- 新增数量：170
- registry 唯一 item：327；pool 唯一 content：327
- pool→registry 缺失：0；registry→pool 多余：0
- registry 内部重复：0；pool 内部重复：0
- 值一致性：全部 registry 条目 unitWorth/amount 与对应 pool 一致（不一致条目 0）
- 170 个本次新增目标：全部出现在 registry
- 原 157 个条目：全部保留（含原值）
- 非法 namespace/ResourceLocation：0；重复 key：0
- JavaScript 语法检查（node --check）：通过

## 5. 校验

- `node --check` 语法 OK。
- pool/registry 双向比对、唯一性、重复、值一致性、170 覆盖、157 保留均通过。
- `git diff --check`：通过。
- 修改前后 SHA-256：
  - 前：`0085c1fdbc27241254fc0f73f6759f21cf0b2de69060e07aeae238f1ec18862b`
  - 后：`33bf41e34ebcf5d51f974b4729a7164107ff59fc37683530b7ccde96771f7c9a`

## 6. 修改文件

- `Server/kubejs/startup_scripts/bounty_food_registry.js`（+170 行）
- 本报告 `mod develop/tcthintegration-template-1.21.1/docs/phase-6e.1.2-tooltip-registry-sync.md`

注：`git diff --stat` 显示的其他文件（pool、food_recipe_export、8E、CHANGELOG 等）为
本工作区此前的累计未提交改动，本阶段未新增/修改它们。

## 7. 结论

- registry 已从 157 同步到 327，缺失/多余/重复均为 0。
- 生产副本未触碰；未启动服务器；未运行完整 sync_bounty_economy.py。
- 未进入新审计阶段，未自动启动游戏验证，未部署生产端。
