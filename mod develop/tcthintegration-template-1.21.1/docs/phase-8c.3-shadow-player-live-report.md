# 8C.3 影窃者玩家真实资产转移 — 部署与在线验收报告

> 状态:**LOAD PASS** / **PLAYER LIVE DEFERRED**(用户主动跳过在线部分)。
> 不得声称任何资产转移在线通过。

## 结论分层

| 层级 | 结果 |
|---|---|
| LOAD | **PASS**(修复 Mixin FATAL 后服务器成功加载至 Done) |
| ITEM PLAYER LIVE | **NOT TESTED**(在线验收被用户主动跳过) |
| HEALTH PLAYER LIVE | **NOT TESTED** |
| HUNGER PLAYER LIVE | **NOT TESTED** |
| EFFECT PLAYER LIVE | **NOT TESTED** |
| FAILURE/PROTECTION | **NOT TESTED** |
| AUDIT PERSISTENCE | **NOT TESTED** |
| DAILY LIMIT PERSISTENCE | **NOT TESTED** |
| COIN BLOCKED | 未在线验证(代码层恒定关闭) |
| ENTITY THEFT NOT IMPLEMENTED | 未实现,未测试 |
| JOB/XP/ABILITY TREE NOT IMPLEMENTED | 未实现,未测试 |

## 事件时间线

1. **部署前**(2026-08-13):服务器停服确认、git status 记录、备份
   (`backup-8c.3-pre-deploy-20260813-0035/`:旧 JAR、tcth-common.toml、
   两名玩家 playerdata;Shadow SavedData 不存在,跳过)。
2. **第一次启动尝试 → Mixin FATAL**:8C.2.6 构建的 `tcth-0.2.7.jar`
   (SHA-256 `bdbe2aa7…`)在服务器上加载失败:
   `tcth_farmer_abilities.mixins.json:ItemStackDurabilityMixin … contains
   non-private static method shouldSkipDurability`.
   根因:Sponge Mixin 禁止 mixin 类中的 non-private static 方法(会被合并
   进目标类产生命名冲突);该 helper 此前为 `public static`。
3. **修复**:`shouldSkipDurability` 改为 `private static`(类内私有逻辑,
   零外部引用,零行为影响);增量构建(非 clean build)得到
   `tcth-0.2.7.jar`(SHA-256 `2fa2143c…`),部署到 `Server/mods/` 与
   AutoModpack host(哈希一致)。
4. **第二次启动(即正式第一次启动)→ LOAD PASS**:
   `Done (8.299s)`、0 FATAL、`tcth-shadow-8c3-test` 临时数据包自动加载、
   `/tcth debug shadow status` 默认关闭、`/tcth shadow audit recent` 已注册、
   `/tcth debug shadow on` 生效、无 TCTH ERROR/WARN。
5. **中止**:用户主动跳过在线玩家部分(两名真实玩家未参与)。
6. **收尾**:正常 `stop`(Saving players / Saving worlds / All dimensions are
   saved);删除临时数据包;恢复全部临时配置;强制
   `shadowRealAssetTransfersEnabled=false`、`shadowEntityTheftEnabled=false`;
   fifo 清理,服务器保持停服;备份与日志保留
   (`Server/logs/8c3-run1-console.out`)。

## JAR 三版状态

| 版本 | SHA-256 | 大小 | 状态 |
|---|---|---|---|
| 8C.2.6 构建版 | `bdbe2aa73ffd0af3fcfa20eedd5792ee613f9b50221aedfcafb8faa1b0fd733b` | — | **作废**:实测服务器 Mixin FATAL |
| 当前部署版 | `2fa2143cfcd1e5ede667818720fb0cce92cb7b78dbf96d3b481d20f83b791756` | 593,326 B | **服务器 LOAD PASS**(`shouldSkipDurability` 改 `private static`) |
| 8C.3.1 clean-build 产物 | `3149987acb6a04ead70c815bf7adcfada59cb12a582310fc03b0d3b317272f77` | 593,968 B | **BUILD PASS**:未部署、未做运行时验证,**不声称 LOAD PASS** |

> 注:pre-8C.3 的其他旧版(如 `73370897…`,不含 shadow 框架的旧 0.2.7)未被本阶段验证,
> 不笼统断言其加载行为。

## 临时配置记录(已全部恢复)

- 测试期:integration/playerTheft/realTransfers = true;冷却
  20/20/20/20/20;`shadowNewPlayerProtectionTicks=0`;成功率保持正式值
  (0.35/0.05/0.85)、`shadowDailyItemLossLimit=3`。
- 恢复后:integration/playerTheft/realTransfers = false;冷却
  200/40/400/1200/100;`shadowNewPlayerProtectionTicks=72000`;
  `shadowEntityTheftEnabled=false` 保持。

## 遗留说明

- 服务器 `Server/mods/tcth-0.2.7.jar` 现为当前部署版 2fa2143c,
  也是唯一完成服务器 LOAD PASS 的版本。
- 8C.3 未进行任何在线资产转移验证;PLAYER LIVE 各分层均未通过,
  不得声称在线通过。
- 8D 未进入;未 commit/push。

—— 8C.3 报告完 ——
