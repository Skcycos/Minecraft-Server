# 阶段 5A 交付报告 — tcth:gunner 枪客职业与 Scorched Guns 击杀结算

- **日期**: 2026-08-06
- **工作区**: `/Users/a1111/Desktop/Minecraft-Server`
- **项目**: `mod develop/tcthintegration-template-1.21.1/`
- **目标环境**: Minecraft 1.21.1 · NeoForge 21.1.247 · TCTH Integration 0.2.1 · Jobs+ 9.0.0 · Arc 9.0.0 · Scorched Guns 1.5 · GD656 Kill Icon 1.1.0.020 · TACZ 已停用
- **结论**: 实现完成，单元测试 485 项全绿，服务器无玩家冒烟测试通过；枪械经验在线实测未做（无在线玩家），按规范保持 `gunnerRewardsEnabled = false`。

---

## 1. Scorched Guns 事件 / API 字节码证据

以服务器实际 JAR `Server/mods/[灼热枪械]ScorchedGuns-Neoforge-1.5.jar` 执行 `javap` 审计：

| 类 | javap 结论 |
|---|---|
| `top.ribs.scguns.entity.projectile.ProjectileEntity` | `extends net.minecraft.world.entity.Entity implements IEntityWithComplexSpawn`；公开方法 `getShooter() → LivingEntity`、`getWeapon() → ItemStack`、`getOwner() → Entity`、`getDamage() → float`、`getShooterId() → int` |
| `BasicBulletProjectileEntity` / `AdvancedRoundProjectileEntity` / `FireRoundEntity` / `RocketEntity` 等 | **全部 `extends ProjectileEntity`** —— 真实弹丸均为子类，判定必须用 `isAssignableFrom`，不能精确类名匹配 |
| `top.ribs.scguns.item.GunItem` | `extends net.minecraft.world.item.Item implements IColored, IMeta`；`ScorchedWeapon extends GunItem`、`EnergyGunItem / AirGunItem / DiamondSteelGunItem extends GunItem`、`DualWieldGunItem extends ScorchedWeapon` —— 向上遍历父类链找 `GunItem` 可覆盖全部枪械，弹药（`AmmoItem extends Item`）不会误判 |
| `top.ribs.scguns.event.GunFireEvent` / `GunReloadEvent` | `extends PlayerEvent`，构造 `(Player, ItemStack)`，`getStack()` / `isClient()` —— 实证存在；本阶段不监听（不发经验），仅记录证据 |
| `top.ribs.scguns.event.GunProjectileHitEvent` | `extends Event implements ICancellableEvent`，`getRayTrace()` / `getProjectile()` —— 实证存在；未使用（命中不结算） |

关键修正（相对上一版 AI 实现）：原代码 `entity.getClass().getName().equals("top.ribs.scguns.entity.projectile.ProjectileEntity")` 对任何真实弹丸（全是子类）恒为 `false`，导致永远无法检测击杀。现改为缓存 `ProjectileEntity` Class 后 `isAssignableFrom(entity.getClass())`。

---

## 2. GD656 数据与计分审计

以 `Server/mods/[击杀效果]gd656killicon-1.1.0.020-1.21.1-neoforge.jar` 执行 `javap`：

- `ServerData`：`getScore(UUID)` / `addScore(ServerPlayer, float)` / `getKill(UUID)` / `addKill(ServerPlayer, int)` 及 SCOREBOARD/KILLBOARD/DEATHBOARD/ASSISTBOARD 计分板目标；数据文件 `world/gd656killicon/playerdata/<uuid>.json`。
- `BonusEngine`：`add(ServerPlayer, int, float, String, ...)` 连杀/距离/爆炸/暴击 Bonus 追加接口。
- `ServerEventHandler`：监听 `LivingDamageEvent.Post` 与 `LivingDeathEvent` —— 统计**所有**伤害与击杀，不限枪械。

**本阶段 GD656 完全独立，绝不交互**：
- 不把 GD656 score/kill 转成枪客经验；不调用 `ServerData.addScore` / `BonusEngine.add`（防一次击杀重复计分）；不修改 GD656 配置/数据文件/网络包；不设为依赖（mods.toml 中刻意**不**声明 gd656，有测试守护）。
- GD656 只负责击杀图标、音效、通用得分与排行榜；枪客经验走 `GunKillEvent → Jobs+/Arc → GunnerStats` 独立管线。
- 未来如需深度兼容（读取武器/目标统计做成就等），只能作为 optional 条件模块，兼容失败不得影响枪客经验与服务器运行；本阶段不做。

---

## 3. 枪械击杀归因设计

模块：`impl/compat/scguns/ScorchedGunsCompatModule`（全代码库唯一允许出现 `top.ribs.scguns` 引用的包；主类只通过 `CompatLoader.register("scguns", "...ScorchedGunsCompatModule")` 字符串描述符注册，缺失时类不解析）。

**主路径（直接命中致死）** — 监听 `LivingDeathEvent`（LOWEST、不接收已取消事件）：
1. `DamageSource.getDirectEntity()` 是 `ProjectileEntity`（含子类）；
2. `projectile.getShooter()` 是真实 `ServerPlayer` 且**非** `FakePlayer`（炮塔/机械 shooter 是其他实体 → 拒绝）；
3. `projectile.getWeapon()` 非空且为 SG 枪械（父类链含 `GunItem`）；
4. 目标不是 `Player`（PvP 纵深防御，另有 excluded 标签）；
5. 目标经 `GunTargetResolver` 分级非空 → 发布 `GunKillEvent`。

> **⚠ 已由阶段 5A.1 取代**：原"延迟伤害路径（victim 归因缓存）"已删除——中枪未死随后坠落/燃烧/他人补刀/毒凋等延迟死亡一律 0 结算。5A.1 改为仅从最终 `LivingDeathEvent` 强证据结算（ProjectileEntity 直接路径 + Niami 箭矢出生归属 + 光束路径），详见 `docs/phase-5a.1-scorched-guns-attribution-report.md`。

**明确不发经验**：开枪、换弹、命中未击杀、制作枪械/弹药、对空开枪、炮塔/机械、FakePlayer、PvP、村民/动物/驯服/友军、自杀、无法证明来自 SG 枪械的击杀 —— 全部被上列检查或分级规则拒绝。

---

## 4. 目标分级

数据驱动（`data/tcth/tags/entity_type/gunner_targets/*.json`），Java 不硬编码实体 ID。判定顺序 `excluded > boss > heavy > elite > common`，各档**严格互斥**，未命中任何规则 → 不结算（fail-closed）。标签内容经 SG 实际标签审计后策展（SG `#scguns:gunner` 含僵尸/骷髅等普通怪，`#scguns:heavy` 含龟/雪傀儡等友军，故不整体引用，改手工列实体 + `required:false`）：

| 档 | 内容 | 依据 |
|---|---|---|
| COMMON | 僵尸/骷髅/蜘蛛/苦力怕/史莱姆/溺尸/幻翼等 | 普通敌对生物 |
| ELITE | 掠夺者/卫道士/唤魔者/女巫/幻术师/**劫掠兽** + `scguns:blunderer/adjudicator/subjugator/finforcer` | 袭击者、持械敌人（SG illager/gunner 敌人） |
| HEAVY | `scguns:dissident/cog_minion/supply_scamp/sky_carrier/cog_knight/trauma_unit/viventrum/scamp_tank/scampler/signal_beacon/praetor` | SG `#scguns:heavy`/`#scguns:bot` 中非友军成员 |
| BOSS | 远古守卫者/末影龙/循声守卫/凋灵 + `scguns:mother_ghast` | 明确 BOSS |
| excluded | 村民/流浪商人/玩家/盔甲架/悦灵/**铁傀儡**/雪傀儡 + 全部动物/驯服生物 + 猪灵/疣猪兽等（不与上档重叠） | 村民、动物、驯服、友军 |

修正（相对上一版）：`ravager` 原同时出现在 elite/heavy/excluded → 现仅 elite；`iron_golem` 原同时出现在 heavy/excluded → 现仅 excluded；excluded 内重复条目已去重。

---

## 5. 防刷与幂等

**`GunKillEventDispatcher.publish`（集中发布入口）**：
- 主开关 `Config.ENABLED` + `Config.GUNNER_INTEGRATION_ENABLED`；客户端/空 level 拒绝；
- **eventId 幂等缓存**：有界（≤4096）+ TTL（100 tick）+ 停服清理；
- **victim-UUID 去重**：同一 target 一次死亡只结算一次（即使死亡事件重复触发或不同模块用不同 eventId 上报）—— 同容量/TTL/清理；
- 先校验后发布，发布成功才入缓存；异常不崩 tick。

**`GunnerRewardModule`（Jobs+ 结算）**：
- `gunnerRewardsEnabled=false`（默认）时整个结算链路不动作；事件与统计照常；
- 事件级幂等（eventId，TTL 40 tick，容量 4096）+ 每玩家每 tick Action 上限（`maxGunKillActionsPerTick=10`）+ BOSS 级每玩家冷却（`gunnerBossCooldownTicks=1200`）；
- **只有 Action 成功发送后才提交幂等、限速计数与 BOSS 冷却**，失败可重试；
- 异常被捕获记录，绝不中断服务器 tick。

**统计侧 `GunnerStatsTracker`**：eventId 去重（容量 4096 + TTL 100 tick + 停服清理）；automated/无玩家事件不统计。

---

## 6. Jobs+/Arc 数据结构

注册（`TcthArcRegistrar`，仅 Jobs+ 且 Arc 存在时加载；`verifyRegistrations()` 逐项核对 ArcRegistry）：

- **ActionType**：`tcth:on_gun_kill`（`GunKillAction`，JSON/网络序列化，无自有逻辑，只转发 rewards/conditions）。
- **ActionData（`GunKillActionDispatcher.buildActionData`）**：Arc 原生 `ITEM_STACK`（防御性副本）、Arc 原生 `ITEM`，加 `weapon_id`、`target_id`、`target_tier`（枚举 `name()` 稳定大写）、`gun_kill_distance`（Float）、`automated`（Boolean）。
- **ConditionType**：
  - `tcth:gun_target_tier` — JSON `{"tier":"COMMON|ELITE|HEAVY|BOSS"}`，未知 tier 数据加载时报清晰错误；
  - `tcth:gun_kill_distance` — JSON `{"min":..,"max":..}`，min<0 / max<min 报错，非法距离 fail-closed；
  - `tcth:gunner_rewards_enabled` — 读配置开关，配置读取异常 fail-closed；
  - 均支持 JSON 与网络序列化、`inverted`；日志均 DEBUG（不刷 WARN）。
- **数据包奖励（`docs/presets/tcth-gunner/data/tcth/arc/gunner/*.json`）**：每档一条，`jobsplus:job_exp`，互斥条件 `tcth:gun_target_tier` + `tcth:automated=false`：

| 档 | 经验 |
|---|---|
| COMMON | 1～2 |
| ELITE | 3～5 |
| HEAVY | 6～10 |
| BOSS | 12～20 |

- Jobs+/Arc/Scorched Guns 缺失时对应类不加载（CompatLoader 懒描述符 + mods.toml optional 声明）。

---

## 7. 枪客统计存储结构

`GunnerStatsData extends SavedData` → `world/data/tcth_gunner_stats.dat`（`dataVersion=1`），固定绑定 **overworld** DataStorage（`level.getServer().overworld().getDataStorage()`）→ 跨维度合并；不写入玩家 NBT。

- 键：玩家 UUID（改名不丢数据）；玩家上限 1024（已达上限的玩家继续累计，拒绝**新**玩家）；
- 武器统计上限 4096：**已达上限后新武器不再记账，已有武器继续累计**（新增测试覆盖）；所有整数饱和加法；
- 字段：`totalGunKills`、四档击杀数、`weaponKills`（item id → 次数）、`uniqueWeapons`、`maxDistance`、`lastWeapon/lastTarget/lastTier`、`firstGunKillAt/lastGunKillAt`；
- 只存 ResourceLocation 字符串、数字、必要文本，**不存完整 ItemStack/NBT**；未知 tier 加载跳过不崩服；`dataVersion<0` 安全返回。
- 查询：`/tcth gunner stats`（本人）、`/tcth gunner stats <player>`（权限 ≥3）；只读，**无 reset 命令**；显示总击杀/四档分布/最常用枪械/最大距离/最近击杀。

---

## 8. 测试 XML 实际汇总

命令：`GRADLE_USER_HOME=/Users/a1111/Desktop/Minecraft-Server/.gradle-home ./gradlew clean build --no-daemon`

```
suites=61  tests=485  failures=0  errors=0  skipped=0
```

枪客相关套件（全部通过）：

| 套件 | 用例数 |
|---|---|
| `api.guncombat.GunCombatApiReferenceTest`（公共 API 零第三方引用/输出无第三方 class/mods.toml 声明） | 4 |
| `api.guncombat.GunKillEventTest`（非空校验、防御性复制、eventId 稳定） | 19 |
| `impl.event.GunKillEventDispatcherTest`（开关/上下文/幂等/重复/容量/TTL/停服） | 9 |
| `impl.compat.scguns.ScorchedGunsCompatModuleTest`（真实 SG JAR 场景：真实玩家弹丸发布一次、非 SG 弹丸/近战/PvP/FakePlayer/炮塔/未分级/非枪械不发布、重复死亡只一次、延迟归因/过期/清理/容量/登出） | 22 |
| `impl.compat.scguns.GunTargetResolverTest`（null/未分级 fail-closed） | 2 |
| `impl.compat.jobsplus.GunnerRewardModuleTest`（开关/幂等/限速/重置/BOSS 冷却/停服） | 11 |
| `impl.compat.jobsplus.arc.condition.GunTargetTierConditionTest` / `GunKillDistanceConditionTest` | 3 + 5 |
| `impl.stats.GunnerStatsDataTest` / `GunnerStatsTrackerTest` / `GunnerStatsCommandTest` / `PlayerGunnerStatsTest`（含武器上限、饱和加法） | 7 + 8 + 3 + 11 |
| `impl.compat.CompatModuleConstructibilityTest`（所有 CompatModule 必须有 public 无参构造器 —— 冒烟测试抓到的坑的回归守护） | 1 |

说明：Mockito/ByteBuddy 无法在 Graal JVM 上插桩真实 SG `ProjectileEntity`，场景测试以契约一致的测试局部 `TestProjectile`（暴露相同 `getShooter()/getWeapon()`，经 javap 核对）注入模块的 class-name 判定；`realScorchedGunsClassResolves` 断言生产默认能从真实 SG JAR 解析出 `ProjectileEntity` 并接受其子类。

---

## 9. JAR 路径、大小、SHA-256

| 项 | 值 |
|---|---|
| 构建产物 | `mod develop/tcthintegration-template-1.21.1/build/libs/tcth-0.2.1.jar` |
| 部署位置 | `Server/mods/tcth-0.2.1.jar`（旧版 `Server/mods/tcth-0.2.0.jar` 保留于备份） |
| 大小 | 232,318 字节 |
| SHA-256 | `a65bebabafb5c618862f76c8df9a2a749637ca51bdb25b31c651a9a0b0a57b7b` |

（构建与部署产物逐一校验一致。）

---

## 10. 第三方内容审计

- JAR 内第三方 class 计数为 **0**（`top/ribs/scguns/`、`org/mods/gd656/`、`com/daqem/` 均不存在）；**无嵌套 JAR**。
- `mods.toml`：新增 `scguns` optional（`[1.5,)`）；jobsplus/arc/fieldguide optional 保留；**gd656 刻意不声明**。
- 依赖：`build.gradle` 中 scguns 仅为 `testImplementation`（来自 `dev-mods/scguns-1.5.jar`，gitignored）；主代码**无任何** SG 编译期引用（全部 class-name 反射，有源码边界测试 `scgunsReferencesOnlyInsideCompatPackage` 守护）。

---

## 11. 无玩家冒烟测试结果

服务器停服后完成：备份 → 部署 `tcth-0.2.1.jar` + `global_packs/required_data/tcth-gunner/` → Java 21（sdkman `21.0.2-graal`）启动 → 验证 → 正常 stop。

日志 `Server/logs/smoke5a_2.out`（第二次，含构造器修复）：

- ✅ `Done (…)` — 服务器启动完成；
- ✅ `[JobManager/INFO]: Loaded 3 jobs` — `tcth:chef` + `tcth:farmer` + `tcth:gunner`；
- ✅ `[TCTH] Scorched Guns compat module registered` / `... active`（第一次冒烟暴露 `IllegalAccessException: ... with modifiers "private"`，即兼容模块构造器为 private；已改为 public 并加回归测试）；
- ✅ Arc 30 项注册全部 `present in ArcRegistry` / `ok (location ...)`（含 `tcth:on_gun_kill`、`weapon_id/target_id/target_tier/gun_kill_distance`、`gun_target_tier/gun_kill_distance/gunner_rewards_enabled`）；
- ✅ TCTH ERROR/WARN = 0；
- ✅ 厨师/农夫模块 active、Field Guide chef categories `84/58/24` 与 0.2.0 完全一致（无回归）；
- ✅ 数据包标签加载零错误；`scguns:defender_pistol` 图标资源存在于 SG assets；
- ✅ `stop` → `Saving worlds` → `ThreadedAnvilChunkStorage: All dimensions are saved` → JVM 退出。

日志中的 ERROR 均为环境既有问题（SG 自身 `blunderer` 战利品表、spawn 模组考古表、KubeJS recipe component 警告、GD656 Ping Wheel 注册失败），与 TCTH/枪客无关。

**无在线玩家，未声称枪械经验实测通过**（见下节）。

---

## 12. 在线未验证项目（需真人玩家验收）

保持 `gunnerRewardsEnabled = false` 时先验证事件：

- [ ] SG 普通枪械击杀普通怪物 → 1 条事件；
- [ ] 自动武器多发命中最终击杀 → 仅 1 条事件；
- [ ] 近战击杀 0、弓箭击杀 0、开枪未命中 0、命中未击杀 0；
- [ ] 动物/村民/玩家击杀 0；炮塔击杀 0；FakePlayer（如可构造）0；
- [ ] COMMON/ELITE/HEAVY/BOSS 分类正确；
- [ ] `/tcth gunner stats` 数值正确；
- [ ] GD656 击杀图标正常且无重复分数。

事件验证通过后开启 `gunnerRewardsEnabled = true` 再验证：

- [ ] COMMON +1～2 / ELITE +3～5 / HEAVY +6～10 / BOSS +12～20；
- [ ] 每次击杀只结算一次；
- [ ] GD656 分数不会再次转成 Jobs+ 经验；
- [ ] `/jobs` 只显示厨师、农夫、枪客三个职业（`Loaded 3 jobs` 已服务端确认，GUI 需客户端复核）。

---

## 13. 配置最终状态

`Server/config/tcth-common.toml`（首次启动自动补齐，其余键保持用户既有设置）：

```toml
gunnerIntegrationEnabled = true   # 检测与发布
gunnerRewardsEnabled = false      # 奖励默认关闭，待在线验收
gunnerStatsEnabled = true         # 统计开启
maxGunKillActionsPerTick = 10
gunnerBossCooldownTicks = 1200
# 既有: jobsPlusRewardsEnabled = true / farmerRewardsEnabled = true（用户设置，未改动）
```

---

## 14. 回滚步骤

1. 停服：服务器控制台执行 `stop`，确认 `All dimensions are saved` 与 JVM 退出。
2. 还原 JAR：`cp backup-5a-gunner-pre-deploy-20260806/tcth-0.2.0.jar.pre-5a Server/mods/tcth-0.2.0.jar`，删除 `Server/mods/tcth-0.2.1.jar`。
3. 还原配置：`cp backup-5a-gunner-pre-deploy-20260806/tcth-common.toml.pre-5a Server/config/tcth-common.toml`（若只想回滚枪客键，删除该文件中的 `gunner*` 段即可，重启会按默认补回）。
4. 移除预设：删除 `Server/global_packs/required_data/tcth-gunner/`（chef/farmer 预设不动）。
5. 重启验证回到 `Loaded 2 jobs`、TCTH 无错误。
6. 说明：备份目录 `backup-5a-gunner-pre-deploy-20260806/` 保留原 `tcth-0.2.0.jar.pre-5a` 与 `tcth-common.toml.pre-5a`；世界/玩家数据未做任何迁移或 NBT 修改，无需回滚。

---

## 15. 建议的精确 Git 暂存清单（未执行 `git add`/`commit`/`push`）

**A. TCTH 模组源码（必含）**
```
mod develop/tcthintegration-template-1.21.1/build.gradle
mod develop/tcthintegration-template-1.21.1/gradle.properties
mod develop/tcthintegration-template-1.21.1/src/main/templates/META-INF/neoforge.mods.toml
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/Config.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/TCTHIntegration.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/command/TcthCommands.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/JobsPlusCompatModule.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/GunnerRewardModule.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/TcthArcRegistrar.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/GunKillAction.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/GunKillActionDispatcher.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/GunKillDistanceCondition.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/GunTargetTierCondition.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/GunnerRewardsEnabledCondition.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/scguns/  （整个目录）
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/event/GunKillEventDispatcher.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/stats/GunnerStatsData.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/stats/GunnerStatsTracker.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/stats/GunnerStatsCommand.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/stats/PlayerGunnerStats.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/api/guncombat/  （整个目录）
mod develop/tcthintegration-template-1.21.1/src/main/resources/assets/tcth/lang/en_us.json
mod develop/tcthintegration-template-1.21.1/src/main/resources/assets/tcth/lang/zh_cn.json
mod develop/tcthintegration-template-1.21.1/docs/presets/tcth-gunner/  （整个目录）
mod develop/tcthintegration-template-1.21.1/docs/phase-5a-gunner-report.md
```

**B. TCTH 测试（必含）**
```
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/api/guncombat/  （整个目录）
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/compat/CompatModuleConstructibilityTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/GunnerRewardModuleTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/GunKillDistanceConditionTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/GunTargetTierConditionTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/compat/scguns/  （整个目录）
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/event/GunKillEventDispatcherTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/stats/GunnerStats*.java  （GunnerStatsCommand/Data/Tracker + PlayerGunnerStats 测试）
```

**C. 服务器部署产物（可选，视仓库约定）**
```
Server/mods/tcth-0.2.1.jar
Server/global_packs/required_data/tcth-gunner/  （整个目录）
Server/config/tcth-common.toml                  （含自动补齐的 gunner 键；farmerRewardsEnabled=true 为用户既有修改）
Server/automodpack/host-modpack/automodpack-content.json  （服务器启动自动重生成，含新模组/数据包条目）
Server/server.properties                        （仅时间戳注释变化，服务器启动重写）
backup-5a-gunner-pre-deploy-20260806/           （回滚备份，建议保留不入库或单独提交）
```

**D. 不应入库**
```
mod develop/tcthintegration-template-1.21.1/dev-mods/scguns-1.5.jar   （gitignored 本地测试依赖副本）
mod develop/tcthintegration-template-1.21.1/build/ 、logs/            （构建与运行产物）
Server/logs/smoke5a_*.out 、world/ 运行时数据
```

---

## 附：交付范围说明

- 未执行 `git add -A` / `commit` / `push`；用户既有未提交文件全部保留。
- 未修改 `tcth:chef`、`tcth:farmer` 行为与数值；未重新启用 Jobs+ 默认职业；未改经济/悬赏/世界/玩家 NBT。
- 未删除/修改 `Server/global_packs/required_data/tcth-chef/` 与 `tcth-farmer/`。
- 本阶段不实现能力树，四路线（枪术/游击/兵备/研修）设计记录于 `docs/presets/tcth-gunner/README.md`。
- 服务器现处于**停服**状态，等待复审。
