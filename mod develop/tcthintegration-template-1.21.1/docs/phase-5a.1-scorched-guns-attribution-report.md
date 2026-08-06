# 阶段 5A.1 交付报告 — Scorched Guns 击杀归因重构与枪客模块加固

- **日期**: 2026-08-06
- **工作区**: `/Users/a1111/Desktop/Minecraft-Server`
- **项目**: `mod develop/tcthintegration-template-1.21.1/`
- **目标环境**: Minecraft 1.21.1 · NeoForge 21.1.247 · TCTH Integration 0.2.1 · Jobs+ 9.0.0 · Arc 9.0.0 · Scorched Guns 1.5 · GD656 1.1.0.020
- **结论**: 误归因缓存已删除；普通弹丸/爆炸/光束/Niami 箭矢四路径全部改为只在 `LivingDeathEvent` 强证据结算；自动测试 **526 项全绿**；无玩家烟雾测试通过（含 Niami Mixin 实机应用证据）；在线枪械实测未做，保持 `gunnerRewardsEnabled = false`。

---

## 1. SG 1.5 源码与本地 javap 证据

本地 javap（服务器实际 JAR `Server/mods/[灼热枪械]ScorchedGuns-Neoforge-1.5.jar`，最终权威）：

| 类 | 字节码结论 |
|---|---|
| `top.ribs.scguns.entity.projectile.ProjectileEntity` | `extends Entity implements IEntityWithComplexSpawn`；`getShooter()→LivingEntity`、`getWeapon()→ItemStack`、`getOwner()→Entity`、`getDamage()→float`；所有真实弹丸（BasicBullet/FireRound/Rocket/AdvancedRound/…）均为其子类 |
| `top.ribs.scguns.init.ModDamageTypes` | `BULLET = ResourceKey(Registries.DAMAGE_TYPE, scguns:bullet)`、`MELEE`；`Sources.projectile(RegistryAccess, ProjectileEntity, LivingEntity)` 用于普通弹丸 |
| `top.ribs.scguns.common.network.ServerPlayHandler` | `private static Arrow getArrow(ServerPlayer, Level, Gun$Projectile)`（javap -p 确认，Mixin 注入点）；`handleShoot`、`handleBeamWeapon`、`handleBeamEffects`、`fireProjectiles` |
| `top.ribs.scguns.common.FireMode` | record；`SEMI_AUTO/AUTOMATIC/PULSE/BEAM/SEMI_BEAM/BURST`（`BEAM=scguns:beam`、`SEMI_BEAM=scguns:semi_beam`） |
| `top.ribs.scguns.common.Gun` | `getGeneral().getFireMode()`；`Gun$Projectile.firesArrows()` |
| `top.ribs.scguns.item.GunItem` | `getModifiedGun(ItemStack)→Gun` 实例方法；所有 SG 枪械均为 `GunItem` 子类（含 `ScorchedWeapon`/`EnergyGunItem`/`AirGunItem`） |

GitHub 源码辅助（`https://github.com/sadeast69/ScorchedGunsNeoforge` main，mod_version=1.5，逐行核实）：

- **普通弹丸**：`ProjectileEntity.onHitEntity` 用 `ModDamageTypes.Sources.projectile(level().registryAccess(), this, this.shooter)` → DamageType `scguns:bullet`，direct=弹丸，causing=shooter。
- **爆炸**：`ProjectileEntity.createExplosion/createRocketExplosion` 用 `entity.damageSources().explosion(entity, projectile.getShooter())` → DamageType `minecraft:explosion`，direct=爆炸本体（ProjectileEntity 子类），causing=shooter。Rocket 的 `onHitEntity` 覆写为纯爆炸。
- **光束**：`ServerPlayHandler.handleBeamEffects` 用 `ModDamageTypes.Sources.projectile(player.server.registryAccess(), null, player)` → DamageType `scguns:bullet`，**direct=null**，causing=玩家；随后同步 `hitEntity.hurt(damageSource, damage)`。
- **Niami**：`data/scguns/guns/niami.json` → `"projectile": { "item": "minecraft:arrow", "firesArrows": true, "damage": 12.0, ... }`；`ServerPlayHandler.getArrow` 生成原版 `Arrow`。

---

## 2. 四路径结算矩阵

统一只在 `LivingDeathEvent`（LOWEST、不接收已取消事件）最终确认；`gunnerIntegrationEnabled=false` 时所有入口立即返回、不产生任何记录。

| 路径 | 判定条件（全部满足才结算） | 武器快照来源 |
|---|---|---|
| **A. SG ProjectileEntity**（普通弹/霰弹/特殊弹/穿透/等离子直击与溅射/火箭/榴弹/MicroJet/其他继承 ProjectileEntity 的玩家弹丸与爆炸） | ① `source.getDirectEntity()` 是 `ProjectileEntity`（含子类）② `source.getEntity()` 是真实 `ServerPlayer` ③ 非 `FakePlayer` ④ `projectile.getShooter() == source.getEntity()` 同一玩家 ⑤ `projectile.getWeapon()` 非空且 item 是 `GunItem` ⑥ 目标非玩家 ⑦ 分级有效 ⑧ 开关开启 | `projectile.getWeapon().copy()`（弹丸记录，不读死亡时手持） |
| **B. SG 光束**（laser_musket / shard_culler / waltz_conversion / cr4k_mining_laser / minksy / flayed_god 等 beam/semi_beam 枪） | ① DamageType 是 `scguns:bullet`（`source.is(ModDamageTypes.BULLET)`）② `source.getDirectEntity()==null` ③ `source.getEntity()` 是真实 `ServerPlayer` ④ 非 `FakePlayer` ⑤ 死亡时主手 `GunItem` ⑥ `getModifiedGun(stack)` 非空 ⑦ FireMode 为 `scguns:beam` 或 `scguns:semi_beam` ⑧ 分级有效 ⑨ 开关开启 | 死亡同步调用中读取的主手快照（`getMainHandItem().copy()`），立即复制 |
| **C. Niami 原版 Arrow** | ① direct 是原版 `Arrow` ② 该 Arrow UUID 在 `NiamiArrowRegistry`（出生时登记）③ `source.getEntity()` 是真实 `ServerPlayer` 且与登记 shooter 相同 ④ 非 FakePlayer ⑤ 登记未过期（TTL 1200 tick）⑥ 分级有效 ⑦ 开关开启；结算成功后立即删除登记 | 发射时主手冻结快照（`held.copy()`），发射后换物品不影响 |
| **非结算** | 坠落/火焰/岩浆/毒/凋/其他玩家补刀/命中未击杀/`GunProjectileHitEvent` 后取消/炮塔/机械/FakePlayer/PvP/枪托近战/其他模组同类 DamageSource → **0 事件 0 经验 0 统计** | — |

`GunProjectileHitEvent` 明确**不使用**：它在伤害调用前发布、可被取消、只能证明尝试命中。仅记录为调试用途。

---

## 3. 删除旧 victim 缓存（证据）

- 删除 `ScorchedGunsCompatModule` 中：`ATTRIBUTION_CACHE`、`AttributionRecord`、`LivingDamageEvent.Post` 监听、`tryConfirmCachedKill`、`onPlayerLogout` 的归因清理、相关测试钩子（`attributionCacheSizeForTesting` 等）。
- 删除测试：`delayedKillFromAttributionCachePublishes`、`expiredAttributionDoesNotPublish`、`attributionIsClearedWhenTargetDies`、`attributionCacheStopCleanup`、`attributionCacheCapacityIsBounded`、`logoutClearsShooterAttribution`。
- 模块不再注册 `LivingDamageEvent.Post` 监听；`git diff` 中上述符号全部为删除（-）。
- 旧报告 `docs/phase-5a-gunner-report.md` 中"延迟伤害路径（归因缓存）"一节已删除并加注"已由 5A.1 取代"；"规范许可"表述已删除。
- 替代：Niami 采用**箭矢出生归属**（arrow UUID → 发射快照），与旧"受害者最近命中归属"（victim UUID → 最近命中）有本质区别——前者只在最终死亡事件的 direct 就是那支箭时消费，坠落/火焰/补刀永远无法命中该记录。

## 4. 延迟燃烧明确不结算

- 燃烧/岩浆/毒/凋零等延迟死亡：最终 `DamageSource` 既不是 SG ProjectileEntity（direct 非弹丸）也不是 `scguns:bullet`+beam 也不是登记的 Arrow → 三条路径全部拒绝 → 0。
- 新增负例测试：`fallDeathAfterHitIsNotPublished`、`fireDeathAfterHitIsNotPublished`、`thirdPartyFinisherIsNotPublished`、`delayedPoisonOrWitherDeathIsNotPublished`、`hitWithoutKillPublishesNothing`。

## 5. 条件编译期签名检查

- `build.gradle`：新增 `compileOnly "blank:scguns:1.5"` + `testImplementation "blank:scguns:1.5"`（来源 `dev-mods/scguns-1.5.jar`，gitignored）——SG 签名变化在构建期失败。
- SG 类型引用**仅存在于** `impl.compat.scguns`（含其子包 `mixin`）；`TCTHIntegration` 主入口仍只通过字符串描述符 `CompatLoader.register("scguns", "...ScorchedGunsCompatModule")`；公共 API 无引用（`GunCombatApiReferenceTest` 守护）。
- `neoforge.mods.toml`：`scguns` 保持 optional（`[1.5,)`）；新增条件 mixin 注册 `[[mixins]] config="scguns_compat.mixins.json" requiredMods=["scguns"]`。
- 缺少 SG 时：条件 mixin 配置跳过、`CompatLoader` 不解析实现类、TCTH 正常启动（有测试断言 mixin 配置 conditional）。
- CI SHA-256 校验：本阶段在构建文档中记录 dev-mods 固定 JAR 的 SHA-256（`Server/mods/[灼热枪械]ScorchedGuns-Neoforge-1.5.jar` = `scguns-1.5.jar`，见 §8），CI 固定下载并校验的步骤建议在接入 CI 时落地（当前仓库无 CI 流水线文件）。

## 6. 开关语义与防刷（加固）

- `gunnerIntegrationEnabled=false`：`ScorchedGunsCompatModule.onLivingDeath`、`NiamiArrowRegistry.register` 立即返回；不检测、不产生箭矢记录、不发布事件；`GunnerStatsTracker` 不写统计（新增 `integrationEnabledSupplier` 检查）。配置读取异常 fail-closed（`RuntimeException | LinkageError` 捕获）。
- `gunnerRewardsEnabled=false`：事件与统计照常，仅阻止 Arc Action/经验。
- `gunnerStatsEnabled=false`：仅阻止统计写入。
- 日志：`GunnerRewardsEnabledCondition` 配置失败 WARN 节流（60s 窗口）；tracker 统计失败日志含 eventId/player UUID/weapon ID。
- 防刷保留：dispatcher eventId + victim-UUID 双幂等（有界/TTL/停服清理）；reward 每玩家每 tick 上限、BOSS 冷却、成功后提交。

## 7. GunnerStatsTracker 提交顺序（加固）

1. 开关检查 → 2. player/automated 检查 → 3. eventId 重复检查（**不写入**）→ 4. 取 SavedData → 5. 成功 `record()` + `setDirty()` → 6. **最后**提交 eventId。
- `RuntimeException | LinkageError` 单事件隔离（不传播到事件总线），失败事件不占用 eventId 可安全重试；缓存保留容量/TTL/停服清理。
- 测试：`failedStatsWriteDoesNotCommitEventId`（失败后重试成功）、`linkageErrorIsIsolated`、`integrationDisabledDoesNotRecord`。

## 8. 测试 XML 实际汇总

命令：`GRADLE_USER_HOME=/Users/a1111/Desktop/Minecraft-Server/.gradle-home ./gradlew clean build --no-daemon`

```
suites=63  tests=526  failures=0  errors=0  skipped=0
```

新增/重写套件：

| 套件 | 覆盖 | 用例 |
|---|---|---|
| `impl.compat.scguns.ScorchedGunsCompatModuleTest` | 四路径矩阵、shooter 不一致/空武器/非枪械/FakePlayer/PvP/未分级/非 SG 弹丸/近战、光束五负例、Niami 九场景（含冻结快照/过期/离开世界/登出/停服/容量/副手不登记）、负例五类、开关、真实 SG 类型加载断言 | 33 |
| `impl.compat.scguns.NiamiArrowRegistryTest` | 出生登记、validator 拒绝、一次性消费、TTL、remove、登出/停服清理、容量、开关关闭、null 参数 | 10 |
| `impl.stats.GunnerStatsTrackerTest` | 三开关、automated、幂等、TTL、停服、失败不提交+重试、LinkageError 隔离 | 12 |
| `impl.compat.jobsplus.arc.GunnerPresetTest` | 预设实读校验（pack/job/四奖励/互斥/条件/无外部依赖/不进主 JAR）、三开关条件、fail-closed、距离条件 finite、GunKillEvent distance finite | 12 |
| `api.guncombat.GunCombatApiReferenceTest` | 新增 scguns mixin 条件配置断言 | 6 |
| `impl.compat.CompatModuleConstructibilityTest` | 所有 CompatModule public 无参构造器 | 1 |

删除：旧 attribution 缓存测试 7 项；`GunTargetResolverTest`/`GunKillEventTest` 等既有套件保留通过。

## 9. JAR 哈希与纯净性

| 项 | 值 |
|---|---|
| 构建产物 | `mod develop/tcthintegration-template-1.21.1/build/libs/tcth-0.2.1.jar` |
| 部署位置 | `Server/mods/tcth-0.2.1.jar`（`Server/mods/tcth-0.2.0.jar` 已移入 `backup-5a-gunner-pre-deploy-20260806/tcth-0.2.0.jar.pre-5a.1`，未删除） |
| 大小 | 242,187 字节 |
| SHA-256 | `bed5d610b20715ff699daa133948be8dc2e2f8289ef7ece3f6d0120e09969b39` |
| dev-mods SG JAR（固定版本测试依赖） | `dev-mods/scguns-1.5.jar` SHA-256：`6a4237c518fa36a56d3bf2a269fad508e2f4053a51e9c43381709b09806c2001`（与服务器 JAR 同一文件拷贝） |

第三方纯净性：JAR 内 `top/ribs/scguns/`、`org/mods/gd656/`、`com/daqem/` 类计数 **0**，无嵌套 JAR；`scguns_compat.mixins.json` 与 `NiamiArrowSpawnMixin.class` 已随包。AutoModpack 只收录 `tcth-0.2.1.jar` 且含 `tcth-gunner` 数据包。

## 10. 无玩家烟雾测试（含复审修复前/后）

### 10.1 首次烟雾（`smoke5a1_4.out`）—— 发现阻断错误，结论已撤回

首次探针触发后 debug.log 的顺序是：

```text
[18:41:08] [Server thread/DEBUG] [mixin/]: Mixing NiamiArrowSpawnMixin from scguns_compat.mixins.json into top.ribs.scguns.common.network.ServerPlayHandler
[18:41:08] [Server thread/FATAL] [mixin/]: Mixin apply for mod tcth failed scguns_compat.mixins.json:NiamiArrowSpawnMixin ...
    InvalidInjectionException: Invalid descriptor for NiamiArrowSpawnMixin::tcth$registerNiamiArrow!
    Expected (ServerPlayer, Level, Gun$Projectile, CallbackInfoReturnable)V but found (ServerPlayer, Level, Gun$Projectile, Arrow, CallbackInfoReturnable)V
... MixinApplyError: Mixin ... FAILED during APPLY
```

**上一版报告的"注入成功"结论错误**：`Mixing ... into` 只表示"开始应用"，紧随其后即 FATAL。回调错误地多声明了一个 `Arrow arrow` 参数（RETURN 注入回调应只由目标参数 + `CallbackInfoReturnable` 组成，返回值经 `cir.getReturnValue()` 获取）。该次结论已撤回。

### 10.2 复审修复（见 §14）后烟雾（`smoke5a1_5.out`）—— 通过

- ✅ `Done`；`Loaded 3 jobs`（chef/farmer/gunner）；TCTH ERROR/WARN = 0
- ✅ 探针触发（`TCTH probe: forced ServerPlayHandler class load`）后：
  ```text
  [20:55:32] [Server thread/DEBUG] [mixin/]: Mixing NiamiArrowSpawnMixin from scguns_compat.mixins.json into top.ribs.scguns.common.network.ServerPlayHandler
  ```
  **且其后无** `InvalidInjectionException`、`MixinApplyError`、`Mixin apply ... failed`、tcth FATAL（对 debug.log 全量 grep 为空）。
- ✅ 30 项 Arc 注册（`tcth:on_gun_kill`、`gun_target_tier`、`gun_kill_distance`、`gunner_rewards_enabled`）`present`/`ok`
- ✅ Field Guide chef categories 84/58/24 无回归；数据包标签零错误；`scguns:defender_pistol` 图标存在
- ✅ 正常 `stop` → `Saving worlds` → `All dimensions are saved` → JVM 退出，无 java 残留；探针脚本已删除，`kubejs/server_scripts/` 恢复原样（仅用户既有 5 个脚本）

日志中的 ERROR 均为环境既有问题（bakeries/pineapple_delight 等第三方配方、GD656 Ping Wheel、KubeJS 用户脚本），与 TCTH 无关。

> ⚠ 验收标准（本阶段与后续通用）：Niami Mixin 的通过标准是 `Mixing ... into ...` **且其后没有任何** `InvalidInjectionException` / `MixinApplyError` / tcth FATAL——不能只看 `Mixing` 行。

## 11. 未完成的在线验证（需真人玩家，不得用单元测试冒充）

- [ ] 普通 SG 枪击杀、霰弹、穿透弹、火箭/榴弹爆炸、等离子溅射各 1 条事件
- [ ] BEAM / SEMI_BEAM 武器击杀各 1 条事件
- [ ] Niami 箭矢击杀 1 条；Niami 发射后切换武器再命中仍用发射快照
- [ ] 原版弓箭击杀 0
- [ ] 枪击后坠落死亡 0、燃烧死亡 0、其他玩家补刀 0、命中未击杀 0
- [ ] 炮塔 0、PvP 0
- [ ] `/tcth gunner stats` 数值正确；每次有效击杀恰好一个 eventId
- [ ] 奖励开启后 COMMON/ELITE/HEAVY/BOSS 经验区间与单次结算（**当前保持 `gunnerRewardsEnabled=false`**）

## 12. 配置最终状态

`Server/config/tcth-common.toml`（用户既有设置未动）：

```toml
gunnerIntegrationEnabled = true
gunnerRewardsEnabled = false      # 保持关闭，在线验收通过前不得开启
gunnerStatsEnabled = true
maxGunKillActionsPerTick = 10
gunnerBossCooldownTicks = 1200
```

## 13. 精确建议暂存清单（未执行 git add/commit/push）

**A. 模组源码（必含）**
```
mod develop/tcthintegration-template-1.21.1/build.gradle
mod develop/tcthintegration-template-1.21.1/src/main/templates/META-INF/neoforge.mods.toml
mod develop/tcthintegration-template-1.21.1/src/main/resources/scguns_compat.mixins.json
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/scguns/ScorchedGunsCompatModule.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/scguns/NiamiArrowRegistry.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/scguns/mixin/NiamiArrowSpawnMixin.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/stats/GunnerStatsTracker.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/GunnerRewardsEnabledCondition.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/condition/GunKillDistanceCondition.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/api/guncombat/GunKillEvent.java
mod develop/tcthintegration-template-1.21.1/docs/presets/tcth-gunner/data/tcth/arc/gunner/*.json
mod develop/tcthintegration-template-1.21.1/docs/presets/tcth-gunner/README.md
mod develop/tcthintegration-template-1.21.1/docs/phase-5a-gunner-report.md
mod develop/tcthintegration-template-1.21.1/docs/phase-5a.1-scorched-guns-attribution-report.md
```

**B. 测试（必含）**
```
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/compat/scguns/ScorchedGunsCompatModuleTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/compat/scguns/NiamiArrowRegistryTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/stats/GunnerStatsTrackerTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/GunnerPresetTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/api/guncombat/GunCombatApiReferenceTest.java
```

**C. 服务器部署产物（可选，视仓库约定）**
```
Server/mods/tcth-0.2.1.jar
Server/global_packs/required_data/tcth-gunner/
Server/config/tcth-common.toml
Server/automodpack/host-modpack/automodpack-content.json
backup-5a-gunner-pre-deploy-20260806/   （回滚备份，建议不入库）
```

**D. 不应入库**：`dev-mods/scguns-1.5.jar`（gitignored）、`build/`、`logs/smoke5a1_*.out`、`world/`、探针脚本（已删除）。

## 14. 回滚方法

1. 停服 `stop`，确认 `All dimensions are saved` + JVM 退出。
2. `cp backup-5a-gunner-pre-deploy-20260806/tcth-0.2.0.jar.pre-5a.1 Server/mods/tcth-0.2.0.jar`（若需回 5A 版）或还原上一版 `tcth-0.2.1.jar`（备份目录 `tcth-0.2.0.jar.pre-5a`）；删除 `Server/mods/tcth-0.2.1.jar`。
3. 还原配置 `backup-5a-gunner-pre-deploy-20260806/tcth-common.toml.pre-5a`（如需回退 5A.1 前状态）。
4. 移除 `Server/global_packs/required_data/tcth-gunner/`（chef/farmer 不动）。
5. 确认 `Loaded 2 jobs`、TCTH 无错误、无 mixin 相关失败。
6. 世界/玩家数据未做任何迁移或 NBT 修改，无需回滚；`tcth_gunner_stats.dat` 若存在可在停服后删除以清空统计（可选）。

---

## 附：交付边界确认

- 未修改 SG JAR、GD656、厨师/农夫逻辑、枪客经验数值；未开启 `gunnerRewardsEnabled`；未编辑 playerdata；无玩家数据迁移。
- 未执行 `git add -A` / commit / push；用户既有未提交文件全部保留。
- 服务器现处于**停服**状态，等待复审。

## 15. 复审修复记录（2026-08-06 二审）

| 问题 | 修复 | 验证 |
|---|---|---|
| **[P1 阻断] Niami Mixin 回调多声明 `Arrow arrow` 参数**（实机 `InvalidInjectionException`，Niami 归因不可用；首版报告把 `Mixing...` 当成功证据属误判，已撤回） | `tcth$registerNiamiArrow` 改为目标参数 + `CallbackInfoReturnable<Arrow>` 四参，返回值经 `cir.getReturnValue()` 获取 | `smoke5a1_5.out`：`Mixing NiamiArrowSpawnMixin ... into ServerPlayHandler` 且其后 **无** InvalidInjectionException / MixinApplyError / tcth FATAL |
| **[P2] `GunnerRewardsEnabledCondition` 配置异常时 inverted 未 fail-closed**（catch 置 `matches=false` 后再 `isInverted()!=matches`，inverted=true 会误放行） | catch 中记录节流日志后**直接 `return false`**，不再应用 inverted | 新增断言：`rewardsConditionFailsClosedOnConfigError` 覆盖 `inverted=true` 也返回 false；BUILD SUCCESSFUL |
| **[P2] `GunnerStatsTracker` 类注释称异常日志节流但每次直接 ERROR** | 实现 60s 时间窗节流（`errorThrottled`），`resetForTesting` 重置 | 构建全绿；实现与注释一致 |

ApricityUI 状态说明：`Server/mods/[AUI]ApricityUI-neoforge-1.21.1-1.1.9.3.jar.disabled` 当前**仍为停用**。`docs/phase-3d-online-report.md` 中"已恢复原位"的说法与实际文件状态不符（历史遗留）；本阶段未改动该文件，是否恢复由运营决策（3D 报告记载其存在"已知专服并发竞态"）。
- 已知边界：Niami Mixin 注入成功已在烟雾中证实（`Mixing NiamiArrowSpawnMixin ... into ServerPlayHandler`），但"Niami 实弹击杀计数"属于在线验收项（§11）。
