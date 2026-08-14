# 8D.3 实体影窃受控部署与在线验收

> 状态：**LOAD PASS + ENTITY PLAYER LIVE PASS（部分项玩家决定跳过）**。
> 不进入 8E、不 commit/push。服务器保持停服，配置已恢复关闭。

## 部署与哈希

| 项 | 值 |
|---|---|
| 构建 JAR | `tcth-0.2.7.jar`，SHA-256 `f6f6d3ecbf1c709cd6e2c7d6725426ab4241a85f577c08a9ce9279b9a3015fcd` |
| 部署 | `Server/mods/` + `Server/automodpack/host-modpack/main/mods/`（三处哈希一致） |
| 数据预设 | `docs/presets/tcth-shadow-entity-loot` → `Server/global_packs/required_data/tcth-shadow-entity-loot/`（3 实体 JSON + pack.mcmeta pack_format 48） |
| 备份 | `backup-8d.3-pre-deploy-20260814-1020/`（JAR、tcth-common.toml、playerdata） |

## 启动记录（三次）

1. **RUN1（LOAD 验证）**：Done (7.610s)、0 FATAL、0 TCTH ERROR；数据包
   `tcth-shadow-entity-loot` 自动加载；四开关全 false；`/tcth shadow audit`
   已注册；正常 stop（Saving players/worlds/All dimensions）。
   - 注：日志有 `Missing data pack file/tcth-shadow-8c3-test` WARN——为
     8C.3 遗留引用（临时数据包已删），非本阶段问题。
2. **RUN2（在线验收）**：Done、0 FATAL；配置临时开启（entity 四开关 true、
   playerTheft 保持 false）；`/tcth debug shadow on` 仅验收期间。
3. **RUN3（重启持久化复核）**：Done、0 FATAL；配置仍开启；复核后正常 stop。

## 在线验收结果

| 项 | 结果 | 证据 |
|---|---|---|
| chicken→egg | **PASS** | audit `b7d133a1`：ENTITY/ITEM/**egg×1**/SUCCESS，pos -148,85,-122 |
| pig→porkchop | **PASS** | audit `8889a1f9`：**porkchop×1**/SUCCESS |
| rabbit→rabbit | **PASS** | audit `201f53eb`：**rabbit×1**/SUCCESS，shown 3 of 3 |
| 成功后只能取得一次 | **PASS** | 同实体再次右键 → DUPLICATE（debug 日志）、0 提示、审计不新增 |
| 失败不交付 + 失败冷却 | **PASS** | FAILED_ROLL 0 交付；冷却期连续右键 ×3 → COOLDOWN；冷却后新尝试正常 |
| 成功 count=1、eventId 一致 | **PASS** | 三条审计均为 x1；审计 eventId 与 debug 日志事件一致 |
| 重启后已搜刮实体不可再搜刮 | **PASS** | RUN3 重启后右键 t1（UUID d93c1426）→ 0 提示、审计仍 4 条（attachment 随实体 NBT 持久化） |
| 无定义实体 0 产出 | **PASS** | cow、sheep → NO_CANDIDATE，审计无记录 |
| L3 实体 0 产出 | **PASS** | zombie → NO_CANDIDATE |
| 硬排除实体 0 产出 | **PASS** | Wither → NO_CANDIDATE（NoAI 召唤验证后清除） |
| 玩家目标路径关闭 | **PASS（配置级）** | `shadowPlayerTheftEnabled=false` 全程（无第二玩家，未做在线右键玩家验证） |
| 满背包 0 交付且可重试 | **NOT APPLICABLE（8D.3.1）** | 当前入口结构不可达：主手空意味着选中的主背包槽为空，天然存在接收槽；底层拒绝语义仍由单元测试覆盖（SlotItemTransaction prepare→null → inventory_full） |
| 职业经验/金币/第二份死亡掉落 | **PASS（框架级）** | 审计无经验/金币字段；搜刮不注入 loot table（独立 shadow_loot 数据）、不击杀实体 |

## 发现的问题（8D.3.1 根因修正）

1. **根因（已修复）**：首次 reload 时 `ShadowLootLoader.apply()` 通过
   `ServerLifecycleHooks.getCurrentServer()` 获取注册表——初始资源 reload
   时 current server 可能为 null → 发布空 definitions → 实体显示"无物可
   窃"；玩家手动 `/reload`（此时 current server 已可用）后定义加载成功。
   **撤回 8D.3 报告中的"global_packs 尚未进入资源栈"未证根因**。
   8D.3.1 改为：`AddReloadListenerEvent.getRegistryAccess()`（本次 reload
   冻结的 RegistryAccess）绑定到每次 reload 独立 listener；初始启动与
   `/reload` 走完全相同的代码路径；null/异常仍 fail-closed 清空。
2. **0 TCTH WARN 证据修正**：8D.3 报告的"0 TCTH WARN"受 `ShadowLogThrottle`
   溢出缺陷影响（首条 WARN 被永久抑制，不可作为证据）；8D.3.1 修复后重新
   验证（见 8D.3.1 验证节）。
3. 8C.3 遗留的 `tcth-shadow-8c3-test` 数据包引用 WARN（非阻塞）。

## 配置最终状态

- **恢复关闭**（与 8C.3 收尾一致，正式启用需运营决策）：
  `enabled=true`（既有）、`shadowThiefIntegrationEnabled=false`、
  `shadowEntityTheftEnabled=false`、`shadowRealAssetTransfersEnabled=false`、
  `shadowPlayerTheftEnabled=false`、`shadowAuditEnabled=true`（既有默认）。
- 服务器**保持停服**；备份与三份启动日志保留
  （`Server/logs/8d3-run1/2/3-console.out`）。

## 遗留与声明

- 数据包注入时序问题待修复（见上）；满背包项由玩家决定跳过；
- **PLAYER 目标路径 LIVE 未验证**（playerTheftEnabled=false + 无第二玩家）；
- 不进入 8E、不 commit/push；正式启用实体影窃需运营决策。


## 8D.3.1 验证分层（版本 0.2.8）

| 分层 | 结果 |
|---|---|
| BUILD PASS | `./gradlew clean build --no-daemon` BUILD SUCCESSFUL；XML **suites=145 tests=1436 failures=0 errors=0 skipped=0**；JAR `tcth-0.2.8.jar`（SHA `2d843575…`，635,300 B）无第三方 class、无嵌套 JAR、主资源无 shadow_loot JSON |
| INITIAL RELOAD PASS | 无玩家启动（RUN4）：初次 reload 日志直接出现 `[TCTH] Shadow loot definitions loaded: 3 entities`（**未执行 /reload**）；Done；0 FATAL、0 TCTH/SHADOW ERROR、**0 TCTH/SHADOW WARN（节流修复后证据重新有效）**；数据包自动启用；正常 stop（Saving players/worlds/All dimensions）、无残留进程 |
| LOAD PASS | 同 RUN4（首个无玩家启动即同时完成 INITIAL RELOAD 与 LOAD 验证） |
| PLAYER LIVE NOT REPEATED | 沿用 8D.3 已有证据（chicken/pig/rabbit SUCCESS、DUPLICATE、FAILED_ROLL、COOLDOWN、硬排除 0 产出、attachment 重启持久化） |
| 8E NOT STARTED | 未进入 |
| commit/push NOT DONE | 未执行 |

- 部署：0.2.7 已移除，`tcth-0.2.8.jar` 部署至 Server/mods 与 AutoModpack（哈希一致），无并存。
- 配置：四 Shadow 开关全 false（`shadowAuditEnabled=true` 既有默认）；服务器停服。
- 根因修正回顾：首次 reload 定义为空源于 reload 阶段错误依赖 lifecycle current server（已修复为 event RegistryAccess + 每 reload 独立 bound listener）；"global_packs 尚未进入资源栈"未证根因已撤回。


## 8D.3.2 提交前收口（BUILD-only，版本保持 0.2.8）

- **ShadowLogThrottle 原子节流**：`ConcurrentHashMap.compute` 内原子判定，
  `logger.warn` 在原子判定成功后执行——同模板并发调用恰一次输出；同值
  负时间（如连续 `Long.MIN_VALUE`）因差值为 0 被节流；`Math.subtractExact`
  防溢出（溢出按满窗放行），时钟回拨放行并重置；新增多线程同步起跑测试
  与负时间重复测试，保留全部既有边界测试。
- **listener.prepare 委托统一**：`ShadowLootReloadListener.prepare` 直接
  委托 `ShadowLootLoader.prepare(manager)`，删除重复解析实现——生产 listener
  与最高优先级/坏文件不回退测试共用同一实现。
- **加固测试**（经真实 listener）：最高优先级覆盖、坏文件不回退、两个非
  null 行为不同的 RegistryAccess 连续 reload 不串用、null 注册表继续清空。
- **PLAYER LIVE 结论不变**（沿用 8D.3 证据）；新构建仅为 BUILD PASS，服务器
  仍部署已通过 INITIAL RELOAD PASS 的 `2d843575…` 版本，旧运行证据不转移。
- 验证：仅一次 `clean build`（BUILD SUCCESSFUL）；XML **suites=145
  tests=1443 failures=0 errors=0 skipped=0**（8D.3.1 为 1436，净 +7）；
  新构建 JAR `tcth-0.2.8.jar` SHA **`3275f527…`**（633,705 B，无第三方
  class、无嵌套 JAR、主资源无 shadow_loot）——**仅 BUILD PASS**；
  未部署、未启动、未烟雾、未在线测试、未进入 8E、未 commit/push。

—— 8D.3 / 8D.3.1 / 8D.3.2 验收完 ——
