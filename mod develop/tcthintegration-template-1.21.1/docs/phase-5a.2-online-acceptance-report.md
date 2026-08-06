# 阶段 5A.2 交付报告 — tcth:gunner 在线玩家验收

- **日期**: 2026-08-06
- **验收方式**: 真人玩家在线操作(Tanrunn / 27a96fec-9b28-4152-b433-0dd8f085333b),服务器后台日志 + playerdata 双路核对
- **环境**: Minecraft 1.21.1 · NeoForge 21.1.247 · TCTH Integration 0.2.1 · Jobs+ 9.0.0 · Arc 9.0.0 · Scorched Guns 1.5 · GD656 1.1.0.020
- **结论**: **核心正例、强归因路径和四档经验结算通过**;完整负例验收尚未完成(E2–E5 延期)。测试服可保持 `gunnerRewardsEnabled=true` 观察,正式发布仍需补验延期项。
- 5A.2 验收期临时 DEBUG 日志已按复审意见清理:改为内存开关 `/tcth debug gunner on|off|status`(默认关闭,仅记录确认后的枪械事件,不再对无关死亡写日志)。

---

## 0. 开始前前提核对

| 项 | 结果 |
|---|---|
| 阶段 5A.1 已 commit | ✅ 两个 commit:`cd5da284`(5A)、`bcfb8cb7`(5A.1);本地 `ahead 2`,**未 push**(按用户决策) |
| 停服后部署 | ✅ 服务器停服后部署 `Server/mods/tcth-0.2.1.jar` |
| 5A.1 交付版 SHA(前提 3) | ✅ 曾核 `bed5d610b20715ff699daa133948be8dc2e2f8289ef7ece3f6d0120e09969b39`;已备份至 `backup-5a-gunner-pre-deploy-20260806/tcth-0.2.1.jar.pre-5a.2` |
| 配置开关 | ✅ 第一轮 `gunnerIntegrationEnabled=true / gunnerStatsEnabled=true / gunnerRewardsEnabled=false`;第二轮改为 `true` 并完整重启 |
| 不编辑 playerdata / 不迁移职业 / 不动厨师农夫 | ✅ 全程未编辑 playerdata(仅读取核对)、未迁移、未改厨师/农夫配置 |

> 说明:为满足"开启枪客调试输出"(第一轮验收要求),在 5A.1 交付版(bed5d610)基础上**仅新增 DEBUG 级日志**(`[TCTH][GUN]` 事件/确认路径/统计记录,零行为变更),构建为**验收版** `1e6f5ab5…`(见 §5)。行为逻辑与 5A.1 完全一致。

---

## 1. 在线验收逐项 PASS/FAIL 表

### 第一轮:击杀归因与统计(经验关闭)

**A. 普通 SG 弹丸** ✅

| 时间 | 武器 | 弹丸类 | 目标 | tier | dist | eventId | 事件数 |
|---|---|---|---|---|---|---|---|
| 21:54:21 | `scguns:flintlock_pistol` | ProjectileEntity | zombie | COMMON | 2.20 | `9a9313de-d151-4f0e-abb6-2998be3a86f3` | 1 |
| 21:54:33 | `scguns:kiln_gun` | BearPackShellProjectileEntity | zombie | COMMON | 6.27 | `9be3f358-584c-4eed-8d1d-5da91b74e35f` | 1 |
| 21:54:39 / 21:55:07 | `scguns:plasmabuss` | PlasmaProjectileEntity | zombie | COMMON | 2.60 / 2.21 | `63ca3886…` / `905676d3…` | 各 1 |
| 21:56:20 | `scguns:earths_corpse` | AdvancedRoundProjectileEntity | zombie | COMMON | 2.36 | `ac116ef9-74d8-4d3f-ba83-0f154a2503bb` | 1 |

weaponId / targetId / targetTier / distance / player / automated=false 全部正确;每次死亡恰好 1 条事件。**PASS**

**B. 爆炸弹丸** ✅

| 时间 | 武器 | 弹丸类 | 目标 | tier | eventId | 事件数 |
|---|---|---|---|---|---|---|
| 21:54:43 | `scguns:terra_incognita` | RocketEntity | spider | COMMON | `ff5ad37a…` | 1 |
| 21:54:49 | `scguns:terra_incognita` | RocketEntity | zombie | COMMON | `65f02516…` | 1 |
| 21:55:28 | `scguns:blooper` | HeGrenadeRoundEntity | zombie | COMMON | `24dc28df…` | 1 |

每个目标死亡恰好 1 条、eventId 唯一、不重复。**PASS**(注:"一次爆炸波及多目标"未实测,列入延期 §3)

**C. 光束武器** ✅

| 时间 | 武器 | 模式 | direct | tier | dist | eventId | 事件数 |
|---|---|---|---|---|---|---|---|
| 22:02:14 | `scguns:laser_musket` | semi_beam | null | COMMON | 1.89 | `7b527cc6-8654-4976-afb7-2970e55e7e0d` | 1 |
| 22:02:32 | `scguns:flayed_god` | beam | null | COMMON | 1.60 | `7ded09d7-f4a3-4b2a-86de-2c255d517159` | 1 |

`directEntity=null` 时正确归因真实射手与当前枪械;第一轮全部普通弹丸击杀均为 `path=projectile`(无误入 beam)。**PASS**

**D. Niami 原版 Arrow** ✅

| 时间 | weaponId | direct | tier | dist | eventId | 事件数 |
|---|---|---|---|---|---|---|
| 22:02:57 | `scguns:niami` | Arrow | COMMON | 2.31 | `6198c06c-52e6-4073-8bc5-08a0591bc7a0` | 1 |
| 22:03:30 | `scguns:niami` | Arrow | COMMON | 9.50 | `eb0f8fdd-285c-4da9-a1a1-0f1e5e7b1a2d` | 1 |

第二笔(22:03:30)为"发射后切换物品再命中",eventId 的 `weapon` 仍为发射时的 `scguns:niami` → 出生快照正确。原版弓/弩未在第二轮击杀测试(第一轮曾确认普通箭矢不登记的逻辑)。**PASS**

**E. 严格负例**

| 项 | 结果 |
|---|---|
| E1 子弹打伤后目标坠落/环境死亡 | ✅ 0 事件 0 统计(22:05:36 zombie `direct=null causing=null`) |
| E2 打伤后燃烧/岩浆/中毒/凋零死亡 | ⏭ 延期跳过 |
| E3 手持枪械近战(枪托)击杀 | ⏭ 延期跳过 |
| E4 命中但未死亡 | ⏭ 延期跳过 |
| E5 同一死亡重复统计 | ✅ 20 笔事件全部 eventId 唯一(隐式验证) |
| 非玩家/FakePlayer/炮塔击杀 | ⏭ 延期跳过(机制上被 shooter 校验拒绝) |

**重启持久化** ✅:`/tcth gunner stats` 重启前后一致(total=14、COMMON=14、最大距离 9.5、最常用枪 earths_corpse、最近击杀 flintlock_pistol)。

### 第二轮:经验结算(开启后)

| 档 | 目标(事件时间) | 击杀前→后 | 实际加 | 预设 | 通过 |
|---|---|---|---|---|---|
| COMMON | zombie(22:18:06,whizzbanger) | 0→1 | +1 | 1–2 | ✅ |
| ELITE | ravager(22:21:29,big_bore) | —→+3 | +3 | 3–5 | ✅ |
| HEAVY | cog_minion(22:21:08) | — | +6 | 6–10 | ✅ |
| HEAVY | cog_knight(22:22:01,big_bore) | —→+7 | +7 | 6–10 | ✅ |
| HEAVY | sky_carrier(22:22:19) | — | +7 | 6–10 | ✅ |
| BOSS | warden(22:23:00,big_bore) | —→+17 | +17 | 12–20 | ✅ |

- **权威核对**(停服后解析 playerdata `Jobs → tcth:gunner → Experience` double)= **41.0**,与 6 笔事件经验总和吻合(1+3+6+7+7+17=41) ✅
- 每次击杀恰好 1 条 eventId、只命中一个档位奖励(四档互斥,无重复 Arc Action) ✅
- 负例机制延续:第二轮所有 `direct=null` 死亡(glow_squid/cavefish 等)0 事件 0 经验 ✅
- `/jobs`:`Loaded 3 jobs`(chef/farmer/gunner),无 Jobs+ 默认职业回归(服务端日志确认;游戏内 `/jobs` GUI 由玩家目视确认三个 TCTH 职业) ✅

---

## 2. 事件与统计全量

- 第一轮成功事件 14 笔(弹丸/爆炸 9 + 光束 2 + Niami 2 + 手枪 1),第二轮 6 笔,合计 **20 笔**,eventId 全唯一,统计 `total=20`。
- 每笔事件日志格式:`[TCTH][GUN] event=… weapon=… target=… tier=… dist=… player=… auto=false result=POSTED`,并伴随 `confirm path=projectile|beam|arrow` 与 `stats … total=N` 记录。

## 3. 延期项目(不计入 PASS,需后续补验)

1. E2:子弹打伤后燃烧/岩浆/中毒/凋零死亡 → 0 事件
2. E3:手持枪械近战(枪托)击杀 → 0 事件
3. E4:命中但未死亡 → 0 事件
4. B 组补充:一次爆炸范围击杀多个目标 → 每个死亡目标各 1 条且不重复
5. 原版弓/弩击杀 → 0 条枪客事件(逻辑已由单元测试覆盖,在线未实测)
6. FakePlayer / 炮塔 / 机械击杀 → 0(单元测试覆盖,在线未构造)

## 4. 最终配置状态

`Server/config/tcth-common.toml`:

```toml
gunnerIntegrationEnabled = true
gunnerStatsEnabled = true
gunnerRewardsEnabled = true   # 测试服观察用;正式发布前需补验延期项(见 §3)
maxGunKillActionsPerTick = 10
gunnerBossCooldownTicks = 1200
```

> 复审修正(5A.2 review):上一版"经验正式对玩家生效"表述不准确——当前为**测试服观察状态**,正式发布仍需补验延期负例。备份:`backup-5a-gunner-pre-deploy-20260806/tcth-common.toml.pre-5a.2-rewards-off`(rewards=false 版)。

## 5. 构建产物与部署产物哈希比对

| 项 | 值 |
|---|---|
| 构建产物 | `mod develop/tcthintegration-template-1.21.1/build/libs/tcth-0.2.1.jar` |
| 部署产物 | `Server/mods/tcth-0.2.1.jar` |
| 大小 | 243,956 字节(构建与部署一致) |
| SHA-256(最终验收版) | `f9efbbacf31ebfd6cd446d6105215b7a870447716a9c04aec6c4b2a4d61534d5`(**构建=部署 ✅**) |
| 5A.2 初次验收版 SHA(已弃,含无条件 death 日志) | `1e6f5ab52e7101159ed121f0dc04ea3940d705c059fcaee2df790789a562009a` |
| 5A.1 交付版 SHA(备份) | `bed5d610b20715ff699daa133948be8dc2e2f8289ef7ece3f6d0120e09969b39` |
| 第三方纯净性 | JAR 内 `top/ribs/scguns/`、`org/mods/gd656/`、`com/daqem/` 类计数 0,无嵌套 JAR |

> 复审修正:上一版报告的 SHA `1e6f5ab5…` 属于含无条件 per-death 日志的初次验收版,已按复审意见替换为开关版 `f9efbbac…`(唯一差异:新增 `/tcth debug gunner` 内存开关,日志默认关闭,行为零变更)。

## 6. XML 实际测试汇总

命令:`GRADLE_USER_HOME=/Users/a1111/Desktop/Minecraft-Server/.gradle-home ./gradlew clean build --no-daemon`

```
suites=64  tests=528  failures=0  errors=0  skipped=0
```

(含新增 `GunDebugTest` 2 用例:默认关闭、可切换)

## 7. TCTH 错误日志核查

三轮验收日志 `accept5a2_1.out / accept5a2_2.out / accept5a2_3.out`:`[TCTH]` ERROR/WARN 计数均为 **0**。日志中其他 ERROR 均为环境既有第三方问题(bakeries 配方、GD656 Ping Wheel、DisplayDelight 缺失注册项等),与 TCTH 无关。

## 8. 停服与保存

- 三次停服(第一轮结束、持久化验证重启、第二轮结束)均正常:`stop` → `Saving worlds` → `ThreadedAnvilChunkStorage: All dimensions are saved` → JVM 退出;无 java 残留进程。
- `world/data/tcth_gunner_stats.dat` 正常落盘(22:07 与 22:23 各更新一次,与两次停服对应)。

## 9. 其他记录

- AutoModpack 服务器证书指纹(本次运行):`7d3d1d2e0c3391656bfd4057a2300b816ef996859b553be125257bd34297d939`
- 玩家数据核对方式:停服后解析 `world/playerdata/27a96fec-….dat`(gzip NBT)中 `Jobs → JobInstanceLocation=tcth:gunner → Experience(double)`;**未做任何写入**。

## 10. 复审修正与暂存/提交状态

- **调试日志清理**(5A.2 review 必改项):删除 `ScorchedGunsCompatModule.onLivingDeath` 对每次非玩家死亡的无条件 DEBUG(刷怪塔会膨胀 debug.log);三处枪客日志(confirm / event / stats)全部改为内存开关 `/tcth debug gunner on|off|status` 控制,**默认关闭**,且只记录确认后的枪械事件。新增 `GunDebug` 开关类与 `GunDebugTest`。
- 开关命令冒烟验证:`status→disabled`、`on→enabled`、`off→disabled`(accept5a2_4.out),启动期 `[TCTH][GUN]` 日志为 0。
- 已提交:`cd5da284`(5A)、`bcfb8cb7`(5A.1);未 push。
- 本轮 5A.2 改动(调试开关 + 报告)按复审指示**提交**(见提交记录),未 push。
- 本阶段未执行 `git add -A`。

## 11. 建议下一步

1. 补验延期项(§3)后可正式放行经验。
2. 决定 `gunnerRewardsEnabled=true` 是否随正式开服保留。
3. 复审通过后 push 两个 commit,并将本轮调试日志改动 + 本报告提交。
