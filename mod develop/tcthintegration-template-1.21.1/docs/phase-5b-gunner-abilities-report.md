# Phase 5B — tcth:gunner 四路线能力树交付报告

> **5B.1 / 5B.1.1 修订(以 `docs/phase-5b.1-gunner-fix-report.md` 为准)**:本报告下列原始结论已废止——
> "consumeAmmo 是所有枪械唯一扣弹点"、"BEAM/SEMI_BEAM 都只通过 consumeAmmo 扣弹"、
> "SEMI_BEAM 存在周期 consumeAmmo"、"创造模式/IgnoreAmmo 不到达光束 Mixin"、
> "弹丸生成晚于扣弹"、"四种依赖组合均 PASS / 静态结构=实机启动"。
>
> 实情(SG 1.5 `javap`):`handleShoot` 先处理 BEAM/SEMI_BEAM 或生成弹丸,**再**汇合公共内联
> `Math.max` 扣弹;`consumeAmmo` **仅** `FireMode.BEAM` 周期路径调用;SEMI_BEAM 无周期
> `consumeAmmo`;光束 HEAD 注入必须在真实扣弹前置条件通过后才抽奖。

- 日期:2026-08-06
- 模块:mod develop/tcthintegration-template-1.21.1(版本号仍为 0.2.1,阶段由 commit 区分)
- 交付产物:build/libs/tcth-0.2.1.jar(SHA-256 `24dae54ad9e3371ddd9aaed5cd0b0ad39d02bd71dc9ec6042b4e8fb088fe032a`,262,322 B)
- 部署状态:Server/mods/tcth-0.2.1.jar 与构建产物哈希一致;数据包部署于 Server/global_packs/required_data/tcth-gunner/(27 个 JSON)

---

## 1. 阶段状态

1. **5A.3 在线负例补验已标记 DEFERRED**(CHANGELOG.md 与 phase-5a.2-online-acceptance-report.md §3):
   - 燃烧/岩浆/中毒/凋零延迟死亡、枪托近战、原版弓弩、一次爆炸多目标、FakePlayer/炮塔。
   - 测试服现有枪客奖励(`gunnerRewardsEnabled=true`)保持开启观察,未关闭。
   - 延期项目未写成 PASS。
2. 已通过的枪械击杀归因框架(5A.1)**未重复修改**:新增代码全部是独立类;
   `NiamiArrowRegistry` 仅增加一个只读查询 `isRegistered(UUID)`(不改变归因行为、不消费记录)。

## 2. 实施前 API 审计(以服务器实际 JAR + javap 为权威)

### 2.1 Scorched Guns 1.5(Server/mods/[灼热枪械]ScorchedGuns-Neoforge-1.5.jar)

| 项目 | javap 结论 |
|---|---|
| 弹药扣除入口 | **已由 5B.1/5B.1.1 废止“单一 consumeAmmo”说法**。真实路径见 5B.1 报告:公共 `handleShoot` 内联 `Math.max` + BEAM 周期 `consumeAmmo`;SEMI_BEAM 无周期 `consumeAmmo` |
| 伤害来源构造 | 字节码确认 `ModDamageTypes$Sources.projectile(RegistryAccess, null, player)`(scguns:bullet,直接实体=null,源头=玩家)用于普通弹丸与光束;爆炸用 `damageSources().explosion(projectile, shooter)`(minecraft:explosion,直接实体=爆炸本体) |
| 弹丸继承 | `ProjectileEntity` 为全部 SG 弹丸/火箭/榴弹/爆炸本体的公共父类(`getShooter()LivingEntity`/`getWeapon()ItemStack`);光束 `FireMode.BEAM/SEMI_BEAM`,`Gun$General.getFireMode()` |
| Niami | 原版 `Arrow` 由 `ServerPlayHandler.getArrow(ServerPlayer, Level, Gun$Projectile)Arrow`(private static)创建(5A.1 已注入) |
| 伤害入口客户端/服务端 | 发射(handleShoot)、光束(handleBeamEffects)、命中伤害均在服务端 `ServerPlayHandler`/`ProjectileEntity` 内执行;`LivingDamageEvent.Pre` 在服务端结算 |
| 可安全注入点 | `consumeAmmo` HEAD(private static,可条件 Mixin)|

### 2.2 Arc 9.0.0 / Jobs+ 9.0.0

- Arc 已有 `DamageMultiplierReward`(com.daqem.arc.data.reward.entity)、`DamageSourceCondition`、`HURT_ENTITY`/`GET_HURT`/`SHOOT_PROJECTILE` action;**审计结论:伤害 multiplier/cancel reward 可复用,但 `DamageSourceCondition` 只能按 DamageType 匹配**,无法区分"SG 爆炸(minecraft:explosion)"与普通爆炸、"Niami 箭(minecraft:arrow)"与原版箭 → 枪术/防护采用强证据代码判定(与 5A.1 同源规则),而非纯数据驱动。
- Jobs+ powerup 状态查询复用 5A/3D 已实证公共路径:`JobsServerPlayer.jobsplus$getJob(JobInstance.of(tcth:gunner))` → `Job.getPowerupManager()` → `getPowerup(PowerupInstance)` → `PowerupState.ACTIVE`。
- Jobs+ powerup JSON 支持 `parent` 字段(chef 同款,高等级前置)。

## 3. 能力树(12 个 powerup,data/tcth/jobsplus/powerups/gunner/)

| 路线 | 节点 | required_level | price | parent |
|---|---|---|---|---|
| A 枪术 | marksmanship_basic | 5 | 5 | — |
| A 枪术 | marksmanship_adept | 25 | 10 | marksmanship_basic |
| A 枪术 | marksmanship_expert | 50 | 15 | marksmanship_adept |
| B 弹药 | ammo_saver_basic | 10 | 5 | — |
| B 弹药 | ammo_saver_adept | 30 | 10 | ammo_saver_basic |
| B 弹药 | ammo_saver_expert | 60 | 15 | ammo_saver_adept |
| C 防护 | battlefield_defense_basic | 15 | 8 | — |
| C 防护 | battlefield_defense_adept | 40 | 12 | battlefield_defense_basic |
| C 防护 | battlefield_defense_expert | 70 | 18 | battlefield_defense_adept |
| D 研修 | gunner_experience_i | 25 | 5 | — |
| D 研修 | gunner_experience_ii | 50 | 10 | gunner_experience_i |
| D 研修 | gunner_experience_iii | 75 | 15 | gunner_experience_ii |

- 全部 `job: "tcth:gunner"`、`icon: scguns:defender_pistol`、每路线三级严格父子(parent)关系。
- 研修 3 个 Arc action(data/tcth/arc/gunner/powerup/):`jobsplus:on_job_exp` + `jobsplus:job_exp_multiplier`(job=tcth:gunner)+ 条件 `tcth:gunner_experience_abilities_enabled` + `jobsplus:powerup_not_active` 互斥(I 排除 II、III;II 排除 III;III 无排除)。
- 语言资源:en_us.json / zh_cn.json 各 12 组 `jobsplus.powerup.tcth.gunner.<node>.name/.description`,数值精确(5%/10%/15%、10%/20%/30%、×1.15/×1.35/×1.60、×1.05/×1.10/×1.15)。

## 4. 实现

### 4.1 统一能力查询(impl/compat/jobsplus/powerup/)

- `GunnerPowerupTier` — NONE/I/II/III,纯值类型,`highestActive(boolean,boolean,boolean)` 保证"任意时刻每路线最多一个等级生效"。
- `GunnerAbilityRoute` — 四路线枚举,每路线三节点定位。
- `GunnerPowerupAccess` — 抽象适配层(仅 Minecraft 类型,无 Jobs+/Arc/SG 依赖,缺失时可安全加载):只查询 Jobs+ 公共 API、不读写 playerdata/NBT、**不长期缓存**(每次实时查询)、查询异常/缺失职业/缺失节点/LinkageError 全部 fail-closed 返回 NONE。
- `GunnerAbilityModule` — Jobs+ 条件兼容实现(仅 Jobs+ 存在时解析;`GunnerAbilityReferenceTest` 保证其不引用任何 SG 类型,证据判定委托给 scguns 包的 `SgDamageEvidence`)。含:
  - 倍率常量(精确):marksmanship 1.05/1.10/1.15;defense 0.90/0.80/0.70;ammo 0.05/0.10/0.15;experience 1.15/1.35/1.60。
  - `LivingDamageEvent.Pre` 监听:枪术(玩家对**非玩家**目标 SG 伤害 ×mult)与防护(玩家受 SG 伤害 ×mult),每事件仅应用最高档一次;PvP 永不触发枪术。
  - `ammoSaverShouldSave(player)` — 概率判定(可注入随机源 `ChanceSource`,测试确定性)。

### 4.2 强证据伤害判定(impl/compat/scguns/SgDamageEvidence.java)

- `isSgFirearmDamage(DamageSource, Entity)`:① 直接实体是 `ProjectileEntity` 且 shooter 为真实玩家(覆盖弹丸/霰弹/特殊/等离子/火箭/榴弹/爆炸本体);② `scguns:bullet` + direct=null + 源头玩家主手持 beam/semi-beam 枪(光束);③ 直接实体是原版 `Arrow` 且 `NiamiArrowRegistry.isRegistered`(Niami)。近战/原版弓弩/环境伤害/FakePlayer/炮塔一律 false。
- 证据规则与 5A.1 击杀确认同源,但不修改原框架。

### 4.3 弹药节省条件 Mixin(AmmoSaverMixin) — 见 5B.1/5B.1.1 报告

- 配置已拆分:`scguns_compat`(Niami,仅 scguns)+`scguns_ammo_compat`(AmmoSaver,scguns+jobsplus)。
- 普通/公共扣弹:`handleShoot` 唯一 `Math.max` `@Redirect`。
- 光束周期:`consumeAmmo` HEAD + `AmmoSaverBeamGate` 前置(创造/IgnoreAmmo/空弹不抽奖)。
- 不得再写“创造/IgnoreAmmo 不到达光束 Mixin”——它们会进入 `consumeAmmo`,由前置门控短路。

### 4.4 配置开关(Config.java,默认 true)

- `gunnerAbilitiesEnabled`(总开关)、`gunDamageAbilitiesEnabled`、`gunAmmoAbilitiesEnabled`、`gunDefenseAbilitiesEnabled`、`gunExperienceAbilitiesEnabled`。
- 每条路线实际生效组合 = `Config.ENABLED && Config.GUNNER_INTEGRATION_ENABLED && gunnerAbilitiesEnabled && 路线开关`;任何读取异常直接返回 false,**不会被 inverted 翻转成通过**(`GunnerExperienceAbilitiesEnabledCondition` 的 catch 直接 `return false`;`GunnerAbilityModule` 的开关全部 try-catch fail-closed)。

### 4.5 互斥与结算安全

1. 每路线任意时刻最多一个等级生效(`highestActive`)。
2. 同一射击最多一个弹药节省节点(唯一 consumeAmmo 注入点)。
3. 同一伤害最多一个枪术 multiplier(Pre 事件应用一次)。
4. 防护不重复减伤(最高档单一 multiplier)。
5. 能力不改变 GunKillEvent 的 eventId/目标档次/归因(不触碰 5A 框架)。
6. 能力不绕过 `gunnerRewardsEnabled`(研修仅乘经验,不代发击杀奖励;开关组合独立)。
7. 枪术伤害加成与经验奖励完全分离(不同路线、不同事件)。
8. 全部服务端结算,客户端数据不作权限依据。

## 5. 测试(确定性)

- **实际 XML 汇总:`69 suites / 562 tests / 0 failures / 0 errors / 0 skipped`**(含此前全部测试,无回归)。
- 新增测试类 5 个(34 个用例):
  1. `GunnerAbilityModuleTest` — tier 查询(无职业→NONE、ACTIVE 组合、INACTIVE 不计、查询异常→NONE)、四路线倍率精确、ammo 概率边界(注入 ChanceSource:0.05/0.15 含边界)、主开关关闭四路线全失效、单路线开关只影响对应路线、配置异常/LinkageError fail-closed。
  2. `GunnerAbilityModuleDamageTest` — 枪术 ×1.10 应用、无 tier 不变、**PvP 永不触发枪术**、近战/环境/未注册原版箭不触发、防护 ×0.90、高级覆盖低级(×0.70 非叠乘)、原版爆炸/火焰不减伤。
  3. `GunnerAbilityTreePresetTest` — 12 节点 JSON 合法、required_level/price/parent 正确、研修 action 互斥条件、multiplier 数值精确、中英文翻译齐全。
  4. `GunnerExperienceAbilitiesEnabledConditionTest` — 四开关合取、异常 fail-closed、**inverted 不能把失败翻成通过**。
  5. `GunnerAbilityReferenceTest` — GunnerAbilityModule 无 SG 引用;SgDamageEvidence 在 scguns 包;mixin config 声明 requiredMods 且包含两个 SG mixin。
- 既有 `GunnerPresetTest.fourRewardFilesExistAndMatchContract` 已更新为只统计 arc/gunner 根目录的 4 个基础奖励(排除 5B 新增的 powerup/ 子目录)。

## 6. 服务器验证(无玩家启动烟雾,smoke5b_1~4)

| 验证项 | 结果 |
|---|---|
| clean build | 全绿(562 测试) |
| JAR 纯净性 | 无第三方 class(无 top/ribs、com/daqem、org/mods/gd656 根)、无嵌套 JAR;新类确认在 JAR 内 |
| 服务器启动 | `Done` ✓;`Loaded 3 jobs` ✓;`Loaded 137 job powerups`(含 gunner 12 个)✓ |
| TCTH 启动日志 | `Gunner ability module active` / `Gunner ability tree active` ✓;TCTH ERROR/WARN = 0 |
| Mixin 加载(探针强制类加载) | `Mixing NiamiArrowSpawnMixin ... into ServerPlayHandler` + `Mixing AmmoSaverMixin ... into ServerPlayHandler`,其后**无 InvalidInjection/MixinApplyError/FATAL** ✓ |
| Arc 注册 | `tcth:gunner_experience_abilities_enabled present in ArcRegistry` ✓;全部 tcth action/condition/reward 零解析错误 |
| powerup/arc 解析错误 | 空 ✓ |
| 正常 stop | `All dimensions are saved` ✓,无残留进程 |
| 环境修复 | 启动曾因 `cloth_config` 缺失(betterf3/bettercombat 强制依赖)崩溃 → 已从 AutoModpack 客户端包复制 cloth-config-15.0.140 到 Server/mods(环境遗留问题,与本阶段改动无关) |

**在线效果未测试前不声称玩家实测通过。** 枪术/弹药/防护/研修的实际数值效果、`/jobs` 在线不崩溃、购买与激活流程,需在线玩家补验(见 §7)。

## 7. 玩家在线实测(延期项)

> **延期决定（2026-08-07）**：服主决定暂不进行 5B 弹药路线在线验收。
> 5B.1.1 已完成扣弹路径修正、静态测试与完整环境烟雾验证；真实玩家枪型、
> 剩余一发、创造模式及 BEAM/SEMI_BEAM 操作仍为 **DEFERRED / LIVE NOT
> TESTED**，不计入 PASS，但不阻塞后续阶段开发。

以下必须在有玩家在线时补验,补验前不写 PASS:

1. 购买 marksmanship_basic/adept/expert 后对僵尸/怪物伤害实测 5%/10%/15% 增幅;PvP 与近战/弓弩不增幅。
2. ammo_saver 概率实测(5%/10%/15%,连续射击观察不耗弹次数);霰弹一次射击只判定一次;创造模式不复制弹药;光束按周期判定。
3. battlefield_defense 对 SG 弹丸与 SG 爆炸减伤(10%/20%/30%);普通 TNT 爆炸/火焰/摔落不减伤。
4. 研修经验倍率实测(×1.15/×1.35/×1.60,击杀获得经验取整后可对照);I/II/III 互斥不叠乘。
5. `/jobs` GUI 在线打开不崩溃;12 个节点名称/描述正常显示;主开关与单路线开关在 config 修改后即时生效(无需重启验证)。
6. 修改能力(购买/切换)后无需重启即可生效(实时查询)。

## 8. 交付约束遵守

- 未修改厨师、农夫能力树;未修改已有枪客经验档次;未编辑 playerdata;未修改 SG/GD656 JAR/数据;未实现金币奖励;未自动 commit/push。
- 所有 SG 引用限定在 impl/compat/scguns/ 包(测试守护);Jobs+ 引用限定在 impl/compat/jobsplus/ 条件兼容包。

## 9. 建议 git 暂存清单(等待用户授权,不自动执行)

```
git add CHANGELOG.md
git add "docs/phase-5a.2-online-acceptance-report.md"
git add "docs/phase-5b-gunner-abilities-report.md"
git add "docs/presets/tcth-gunner/data/tcth/jobsplus/powerups/"
git add "docs/presets/tcth-gunner/data/tcth/arc/gunner/powerup/"
git add src/main/java/com/tanrunn/tcth/Config.java
git add src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/powerup/
git add src/main/java/com/tanrunn/tcth/impl/compat/scguns/SgDamageEvidence.java
git add src/main/java/com/tanrunn/tcth/impl/compat/scguns/mixin/AmmoSaverMixin.java
git add src/main/java/com/tanrunn/tcth/impl/compat/scguns/NiamiArrowRegistry.java
git add src/main/resources/scguns_compat.mixins.json
git add src/main/resources/assets/tcth/lang/
git add src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/TcthArcRegistrar.java
git add src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/JobsPlusCompatModule.java
git add src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/GunnerExperienceAbilitiesEnabledCondition.java
git add src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/powerup/
git add src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/GunnerAbilityTreePresetTest.java
git add src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/GunnerPresetTest.java
git add src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/GunnerExperienceAbilitiesEnabledConditionTest.java
```
