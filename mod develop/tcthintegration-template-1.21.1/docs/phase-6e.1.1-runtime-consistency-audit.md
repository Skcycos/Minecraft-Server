# 阶段 6E.1.1 —— 工作区运行时证据与入池后一致性审计

## 1. 阶段边界

- 仅对当前开发工作区 `/Users/a1111/Desktop/Minecraft-Server/Server` 做运行时验证与
  一致性审计；不部署生产端、不修复发现问题、不扩展为新的定价工程。
- 生产副本 `/Users/a1111/Desktop/Mc_Server_0.1/Minecraft-Server/Server`（端口 19764）
  全程未触碰（用户已明确授权在开发工作区 25565 启动测试服）。
- 未修改：三个正式 Bountiful food pool、价格/amount/weightMult/等级、
  bounty_food_registry.js、decree、playerdata、KubeJS 正式业务逻辑、8E Java 工作区。
- 未运行完整 sync_bounty_economy.py；未 commit/push。
- 未因目标无运行时配方而从 bounty pool 删除任何条目。

## 2. 启动前检查

- 端口 25565：空闲。
- 当前工作区残留 Java 进程：无。
- 当前工作区 `world/session.lock`：存在但无进程占用（可安全启动）。
- 生产副本状态（仅记录）：端口 19764 由 java PID 62521 监听并持有其世界锁，未对其
  执行任何操作。
- 旧导出备份：
  `/tmp/Minecraft-Server-phase6e1.1-backup-20260820/food_recipe_export.json`
  - SHA-256：`27d1f3be60df2f83d9fd0ed6b25e534e634e977e2a14b587118b2b2ce636c4c1`
  - 大小：1,638,051 字节
  - mtime：2026-08-10 20:51:22
  - export_meta：无（旧文件缺失该字段）
  - 条目：recipe_rows 1026、ingredient_rows 5115、output_rows 1024、mod_summary_rows 5

## 3. 服务端启动

- 启动目录：`/Users/a1111/Desktop/Minecraft-Server/Server`
- 启动时间：约 11:31:04（bootstrap），`Done (4.777s)!` 于 11:31:34，监听 `*:25565`。
- Bountiful 成功读取三个 food pool（无解析错误）：
  `food_common_objs.json`、`food_t2_objs.json`、`food_t3_objs.json`。
- `bounty_food_registry.js` 启动日志：
  `[食韵筑家] 已注册悬赏食物 157 项（startup）`。
- 执行控制台命令：`syexport food_recipes`
- 成功回执：`[食韵筑家] 食品配方导出完成：1807 条（skip=7316），目录：
  kubejs/config/food_recipe_export.json`（11:31:50）。
- 执行 `stop`（11:40:36 处理）：世界已保存（"All dimensions are saved"）、配置卸载完成
  （"Finished unloading server configs"）、服务端完整退出。
- 收尾确认：当前工作区 Java 进程退出、25565 端口释放、`world/session.lock` 释放。
- 生产副本未停止、未操作。

## 4. fresh export 校验

- 路径：`Server/kubejs/config/food_recipe_export.json`
- SHA-256：`a0ede0420bcc07500ce4cbde529b15675a402e32c469d30e00b950d6195aa3dc`
- 大小：3,338,795 字节；mtime：2026-08-20 11:31（较旧文件已刷新）。
- JSON 语法：通过。
- 条目：recipe_rows 1807、ingredient_rows 8090、output_rows 1803、mod_summary_rows 11、
  extended_rows 1807。
- export_meta：`{phase: "6A", source_mods: [12], product_namespaces_always_include: [7],
  exported: 1807, skipped: 7316}`。注：export_meta 无时间戳字段，导出时间以日志
  11:31:50 与文件 mtime 11:31 佐证；此项为既有脚本限制，记录不改造。

## 5. 170 目标运行时配方覆盖

- 目标集合：食物三档分类表 6B 手持 + 6D 分食，共 170 唯一（COMMON 38 / T2 132 / T3 0）。
- 有运行时有效配方：**167 / 170**。
- 无运行时配方：**3**（全部为 6D 分食单份产物）：
  - `mynethersdelight:plate_of_stuffed_hoglin`
  - `mynethersdelight:plate_of_stuffed_hoglin_ham`
  - `mynethersdelight:plate_of_stuffed_hoglin_snout`
  这些分食产物由整盘（roast_stuffed_hoglin）切割/盛盘机制运行时生成，无独立
  RecipeManager 配方；其物品本身已注册且已入池，Bountiful 可正常作为提交目标。
  按任务要求，不因无运行时配方而移出 pool。
- 多配方目标：21 个（如 cooked_taro×3、cooked_loin×3、boiled_egg×4、burnt_roll×2 等）。
- 每目标运行时配方明细与"输出 ID 非法 RL"检查见 `/tmp/rt_coverage.json`；非法 RL 输出
  ID：无。

## 6. 静态 JAR 配方 vs 运行时有效配方差异

对 170 目标按 recipe ID 比较（静态扫描来源：5 个目标 mod JAR 的 recipe JSON）：

- 交集：204 个 recipe。
- 仅静态存在（fresh 导出未含）：1 个 — `mynethersdelight:chilidog` 的
  `farmersrespite:chilidog_alt`。原因：该 recipe 的命名空间 `farmersrespite` 不在
  `/syexport` 的 sourceMods 过滤名单内，属导出范围过滤，非运行时缺失。
- 仅运行时存在：0。
- 相同 recipe ID 输出数量不同：0。
- 相同 recipe ID serializer/type 不同：0。
- 可能被 datapack/KubeJS 覆盖或移除：无证据（交集数量/类型一致，未发现覆盖）。
- TAG 原料运行时成员证据：本阶段未单独导出 TAG 成员；依赖运行时导出脚本扩展，记录为
  未验证项。
- 限制说明：`export_phase6e0_bounty_pricing_preview.mjs` 虽读取该导出文件，但现有逻辑
  并未真正使用 runtime recipe rows 计算价格；本阶段未修改该生成器，因此
  runtimeStatus 不会被其伪造为 ACTIVE/INACTIVE。本阶段独立完成上述 fresh export 比较。

## 7. 正式入池一致性（只读）

- 三个 pool 条目数：food_common_objs 107 / food_t2_objs 190 / food_t3_objs 30，总计 327。
- content 全部唯一（327/327 无重复）。
- 本次新增 170 个 content：每个恰好出现一次；COMMON 38 全部位于 food_common_objs，
  T2 132 全部位于 food_t2_objs，T3 无新增。
- 新增字段校验：
  - COMMON：type=item、unitWorth=15、amount 3..16、weightMult=0.85
  - T2：type=item、unitWorth=35、amount 2..8、weightMult=0.8
  - 无 rarity/repRequired/forbids 等额外字段。
- 旧条目未因运行服务端而改变（与 6E.1.0 备份逐 key/value 一致，顺序保留）。
- 三个 pool JSON 校验：通过（`python3 -m json.tool`）。
- 三个 pool 修改后 SHA 与 6E.1.0 一致（服务端运行未改写）：
  - common `9a8fc661…4dbed3f`；t2 `d2cae43d…413b8`；t3 `6ce40d40…3e3b728`
- `git diff --check`：通过。

## 8. Tooltip registry 一致性（只读）

- pools 唯一 content：327。
- registry（bounty_food_registry.js）唯一 item：157。
- registry 缺失：170 —— 经比对，缺失集合**恰好等于本次新增的 170 个目标**。
- registry 多余：0。
- registry 内部重复：无；pools 内部重复：无。
- 结论：Tooltip registry 尚未同步本次 170 项。按任务要求：不修改 registry、不运行完整
  sync_bounty_economy.py、不顺手同步；此问题仅报告，不影响 Bountiful 正式入池。

## 9. Decree 引用检查（只读）

- `Server/config/bountiful/bounty_decrees/cook.json` 仍引用：
  `food_common_objs`、`food_t2_objs`、`food_t3_objs`（第 5-7 行）。
- 无拼写/路径错误；三个 food pool 均被 decree 使用。未修改 cook.json。

## 10. 日志判定

本次相关错误：无。
- food_common/t2/t3_objs 解析失败：无。
- duplicate entry / unknown item / invalid ResourceLocation：无。
- Bountiful pool/decree 加载失败：无。
- syexport food_recipes 执行失败：无。
- 服务端异常崩溃：无（正常 Done 与正常 stop）。

无关既有日志问题（与本 food pool 无关，不判定为本阶段失败）：
- `DataMapLoader: Object with ID modid:example specified in data map for registry
  minecraft:dimension doesn't exist`（既有示例数据错误）。
- mixin 对 client 类（Options/LevelRenderer/HierarchicalModel 等）在 DEDICATED_SERVER
  端的既有告警。
- gd656killicon / Ping Wheel 相关加载行为属既有，未出现监听器报错影响本阶段。

## 11. 修改文件清单（本阶段）

- 实际修改（工作区）：
  - `Server/kubejs/config/food_recipe_export.json`（由 syexport 刷新）
  - 本报告 `mod develop/tcthintegration-template-1.21.1/docs/phase-6e.1.1-runtime-consistency-audit.md`
- 运行时运行记录（临时、非仓库）：`/tmp/mc_stdout.log`、`/tmp/rt_coverage.json`、
  `/tmp/diff_rows.json`、`/tmp/static_recipes.json`、审计脚本均在 /tmp。
- 未修改任何正式 Bountiful pool、registry、decree、KubeJS 业务逻辑、8E、生成器或测试。

## 12. 最终结论（分项）

- **Bountiful pool JSON consistency：PASS**（327 唯一、新 170 字段正确、旧条目未变、
  JSON 合法）
- **Bountiful runtime pool load：PASS**（三个 food pool 运行时读取无错误，decree 引用
  正常）
- **Runtime recipe export：PASS**（1807 条，fresh 文件 SHA/mtime 刷新，成功回执）
- **Runtime recipe coverage：167 / 170**（3 个 6D 分食产物无独立配方，如实记录）
- **Tooltip registry coverage：FAIL**（registry 缺 170 项，恰为本次新增目标；不影响
  正式入池，未同步）
- **Decree pool references：PASS**
- **Player submission：NOT TESTED**（未部署、未在线玩家验证）
- **Production deployment：NOT PERFORMED**
- **Production server：NOT TOUCHED**（19764 运行中，全程未操作）

发现的问题（仅报告，不修复）：
1. 3 个 6D 分食产物无运行时 RecipeManager 配方（由整盘切割/盛盘生成），但物品已注册
   且已入池。
2. Tooltip registry 缺 170 项（未同步）。
3. `/syexport` 的 sourceMods 名单不含 farmersrespite 等命名空间，导致
   `farmersrespite:chilidog_alt` 这类配方被导出范围过滤。
4. export_meta 无时间戳字段（既有脚本限制）。
5. TAG 原料运行时成员证据未单独导出（需扩展导出脚本）。