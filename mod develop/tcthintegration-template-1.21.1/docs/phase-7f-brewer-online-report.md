# 阶段 7F 在线验收报告 — tcth:brewer 魔酿师四路线能力树与 Keg 调饮

- **日期**: 2026-08-10
- **验收方式**: 真人玩家在线操作(Tanrunn / 27a96fec-9b28-4152-b433-0dd8f085333b,level 4 OP),服务器后台日志 + debug.log 双路核对
- **环境**: Minecraft 1.21.1 · NeoForge 21.1.247 · TCTH Integration 0.2.6 · Jobs+ 9.0.0 · Arc 9.0.0 · BrewinAndChewin 4.5.0 · Field Guide 1.13.4
- **结论**: **LOAD PASS + PLAYER LIVE PASS(核心全通过);DEFERRED 仅剩跨重启 stats 复核。**
  - 前置修复(7F J 键 GUI 序列化修复,`docs/phase-7f-jkey-gui-serialization-fix-report.md`)随 0.2.6 部署,本次 186 actions 全量加载无错位。
- 玩家要求保留测试数据(12 节点已购 + 临时提升的等级),未做恢复;正常 `/stop` 停服,世界保存确认。

---

## 一、LOAD PASS

| 项 | 证据 |
|---|---|
| TCTH 版本 | `TCTH Integration 0.2.6 (tcth)`(mods scan) |
| Actions | `Loaded 186 actions`(含 brew_common / brew_t2 / 6 个 arc powerup 文件,无错位) |
| Jobs | `Loaded 4 jobs`(chef / farmer / gunner / brewer) |
| Job powerups | `Loaded 149 job powerups`(含魔酿师 12 节点) |
| 饮品 tier | `Brewer tiers loaded: 64 items (18 COMMON / 46 T2)` |
| Arc brewer 注册 | `tcth:on_beverage_prepared` / `tcth:beverage_tier` / `tcth:brewer_rewards_enabled` 全部 present=true(0.2.5 修复版已验证,0.2.6 同源码) |
| Field Guide 分类 | `tcth:brew_common=18, tcth:brew_t2=46` |
| TCTH 错误计数 | 0(日志中无 TCTH ERROR / 异常;仅有无关第三方 RecipeManager 解析警告) |

## 二、PLAYER LIVE PASS

### 2.1 J 键职业 GUI ✅ PASS
- 0.2.6 客户端(AutoModpack 同步)按 J 打开 Jobs+ GUI 正常,无 `ResourceLocationException`、无断线。
- 魔酿师技能树 12 节点全部可见(名称:快手斟饮 / 调酒大师 / 完美酿造 / 浅酌回甘 / 细品醇香 / 余韵绵长 / 强健体魄等)。

### 2.2 COMMON/T2 Keg 调饮只结算一次 ✅ PASS
- **T2**:`[TCTH][debug] beverage event id=e1ea3322 tier=T2 result=brewinandchewin:beer count=1 automated=false`(20:07:02),玩家经验 **+4**(T2 档范围 3–5)。
- **COMMON**:`[TCTH][debug] beverage event id=db050de6 tier=COMMON result=minecraft:honey_bottle count=1 automated=false`(20:08:50),玩家经验 **+1**(COMMON 档范围 1–2)。
- 两次事件 id 唯一、玩家确认各只结算一次,无重复。

### 2.3 /tcth brewer stats ✅ PASS(含跨版本持久化)
```
[TCTH] 魔酿师档案
  调饮次数: 5 | 饮品份数: 5 | 不同饮品: 2
  最常用设备: KEG (5 次)
  档次分布: COMMON=2 T2=3
  最常调制: brewinandchewin:beer (3 份)
  最近调制: minecraft:honey_bottle (KEG, COMMON)
```
- 结构正确;5 次中 3 次为 7C.2/7D 时代历史数据(beer),2 次为本会话新增(honey + kombucha)→ **stats 存档跨版本、跨重启持久化有效**。
- 命令权限正确(玩家可查自身;查他人需 level ≥ 3)。

### 2.4 图鉴解锁:普通给予/拾取/饮用不解锁,Keg 交付才解锁 ✅ PASS
- **负例**:`/give brewinandchewin:kombucha` → 拾取 → 饮用 → 图鉴**未解锁**(玩家确认)。
- **正例**:真实酿造 kombucha 并经 Keg 灌装交付(`id=a711ae84 tier=T2 automated=false`,20:17:55),图鉴**解锁**(玩家确认)。
- 服务器证据(debug.log,同 tick):`[TCTH] Field Guide brewer entry 'item:brewinandchewin/kombucha' unlocked for player 'Tanrunn' (beverage prepared)`。
- 无 unlock 失败日志。

### 2.5 调饮路线(brewing)I/II/III ✅ PASS
- I 快手斟饮(5级):Keg 调饮后**速度 I 5 秒**。
- II 调酒大师(20级):**速度 I + 幸运 I 8 秒**,覆盖 I(非叠加)。
- III 完美酿造(45级):**速度 I + 幸运 I 12 秒**,覆盖 II(高档互斥成立)。
- 玩家逐档购买实测,效果与配置描述一致。

### 2.6 品鉴路线(tasting)I/II/III + 20 秒冷却 ✅ PASS
- I 浅酌回甘(15级):完成饮用后**生命恢复 I 5 秒**。
- II 细品醇香(35级):**生命恢复 I 5 秒 + 抗性提升 I 8 秒**。
- III 余韵绵长(55级):**+ 速度 I 15 秒**。
- 冷却:完成一次饮用后立即再次饮用,**无第二次效果**(共享 20 秒冷却生效)。

### 2.7 耐受路线(resistance)I/II/III + 负例 ✅ PASS
- I 强健体魄(10级)10% / II(30级)20% / III(60级)35%:魔法/间接魔法/凋零伤害显著减免。
- **负例**:火焰、摔落伤害**未被减免**(仅魔法类伤害受减免,与设计一致)。

### 2.8 研修路线(study)I/II/III + 不叠乘 ✅ PASS
- I(25级)×1.15 / II(50级)×1.35 / III(75级)×1.60 经验倍率生效。
- **不叠乘**:同时持有 I+II+III 时仅取最高档 ×1.60,无叠加。

## 三、DEFERRED

1. **跨重启 stats 二次复核**:本次停服前 `/tcth brewer stats` 已含 7C.2/7D 历史数据(证明持久化有效);玩家已保留本次测试数据,下次会话可复核新增调饮是否累计。此项为"继续复核",非缺陷。
2. **品鉴冷却精确时长秒表核对**:玩家验证"立即再喝无效果"成立,但未用秒表精确测 20.0s 边界;如需精确边界值可在后续会话复核。
3. 耐受 III(35%)减伤的**精确伤害数值对比**未做逐点记录(玩家定性确认"明显降低");如需精确数值可用女巫伤害前后对照记录。

> 两个 DEFERRED 项(跨重启 stats 复核、冷却精确边界)按 7F.1 收口要求保留,不作为本阶段缺陷。

## 四、7F.1 BUILD-only 收口(2026-08-10)

- `./gradlew clean build`:110 suites / 867 tests / **0 failures / 0 errors / 0 skipped**(XML 逐文件聚合,真实重跑)。
- 构建产物:`build/libs/tcth-0.2.6.jar`,415,740 字节,SHA-256 `6bcbf7c15a6aa78827c4fc5366a7a8381d284321152cf45f0909a1a2879cee9d`。
- **部署一致性**:`Server/mods/tcth-0.2.6.jar` 与构建产物 SHA-256 完全一致(同一哈希)。
- 版本历史:0.2.4(7D.1/7E 部署构建,未进 git)→ 0.2.5(7D.1/7E 正式提交 `2914dc13`)→ 0.2.6(7F 序列化修复,本报告所述验收版本),详见 `docs/phase-7f-jkey-gui-serialization-fix-report.md` 第八节。
- **数据保留(按玩家要求)**:魔酿师 12 节点已购、等级经 `/job set level` 临时提升后保留;测试金币消耗于购节点,均不恢复。
- **禁止提交**:playerdata(`Server/world/playerdata/`)、服务器配置(`Server/config/`、`server.properties` 等)、运行日志(`accept7f2.out`、`logs/`、`hs_err_*.log`)、备份目录一律不进提交。
- 不再启动服务器、不再重复在线验收(7F 在线验收已 PASS)。

## 五、测试数据保留说明

- 玩家要求保留测试期间数据:魔酿师 12 节点已全部购买、等级经 `/job set level` 临时提升后保留;`/job set coins` 发放的测试金币已消耗于购节点。
- 未手工编辑 playerdata;`tcth debug brewing on` 为内存开关,随正常停服自动失效,无配置残留。

## 六、停服确认

- 玩家执行 `/stop`:日志依次出现 `Stopping server` → `Saving players` → `Saving worlds` → `ThreadedAnvilChunkStorage: All dimensions are saved`(20:35:43),进程无残留。

## 七、阶段结论

**LOAD PASS(0.2.6,186 actions / 4 jobs / 149 powerups / 64 tiers)+ PLAYER LIVE PASS(调饮、品鉴、耐受、研修四路线三档、高档互斥、品鉴冷却、耐受负例、图鉴解锁正负例、Keg 单次结算、stats 命令);DEFERRED 仅跨重启 stats 复核与精确边界测量(非缺陷)。**

7F 在线验收通过;本次会话未 commit/push、未重复启动、无额外烟雾测试。
