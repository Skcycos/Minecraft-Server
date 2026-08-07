# Phase 5B.1 / 5B.1.1 — 枪客能力树阻断问题修正报告

- 日期:2026-08-07
- 模块:mod develop/tcthintegration-template-1.21.1(版本号仍为 0.2.1,阶段由 commit 区分)
- 本轮范围:仅修复 5B / 5B.1 复审遗留;未调整能力数值、未修改 `tcth:gunner` ID、未编辑 playerdata、未修改枪客经验奖励档次、未修改 SG/GD656 JAR、未 commit/push。

> **5B.1.1(本轮)**:修正光束 `consumeAmmo` HEAD 在创造/`IgnoreAmmo`/空弹时仍抽奖的问题;以服务器真实 JAR 重校控制流矩阵;将四种依赖组合明确区分为结构验证 vs 实机启动。

---

## 1. 权威控制流(服务器真实 SG JAR)

`Server/mods/[灼热枪械]ScorchedGuns-Neoforge-1.5.jar`、`javap -p -c top.ribs.scguns.common.network.ServerPlayHandler`(1924 行):

```text
handleShoot
 ├─ BEAM / SEMI_BEAM → handleBeamWeapon
 ├─ 普通弹丸 / Niami → 创建并发射弹丸 (fireProjectiles / getArrow+addFreshEntity)
 └─ 所有成功 handleShoot 分支汇合到公共内联扣弹块 (~offset 408+)
      !creative ∧ !IgnoreAmmo ∧ Reclaimed miss
      → AmmoCount = Math.max(0, AmmoCount - 1)
```

此外:

- `FireMode.BEAM` 达到消费延迟时,`handleBeamWeapon` **额外**调用 `consumeAmmo`(唯一 call site,且仅在 `FireMode.BEAM` 分支)。
- `FireMode.SEMI_BEAM` **不进入**周期性 `consumeAmmo` 分支。
- `handleShoot` 的公共内联扣弹**也覆盖** BEAM 与 SEMI_BEAM。
- 弹丸生成 / 光束处理发生在公共内联扣弹**之前**(不得写“弹丸生成晚于扣弹”)。

`consumeAmmo` 本体(简化):

```text
if (player.isCreative()) return;           // no-op, no write
tag = getOrCreateCustomData(stack)
if (tag.getBoolean("IgnoreAmmo")) return; // no-op
ammo = tag.getInt("AmmoCount")
if (ammo <= 0) return;
tag.putInt("AmmoCount", ammo - 1); setCustomData(...)
```

因此 HEAD inject 若在进入方法时立刻抽奖,会在创造/`IgnoreAmmo`/空弹 no-op 路径上浪费 Jobs+ 查询与随机判定 —— **5B.1.1 已修**。

---

## 2. 最终注入矩阵

| 路径 | 实际扣弹入口 | TCTH 注入 |
|---|---|---|
| 普通枪械、霰弹、火箭、榴弹、Niami | `handleShoot` 公共内联 `Math.max` | `@Redirect` |
| BEAM 启动/成功 `handleShoot` | `handleShoot` 公共内联 `Math.max` | `@Redirect` |
| SEMI_BEAM 成功 `handleShoot` | `handleShoot` 公共内联 `Math.max` | `@Redirect` |
| BEAM 达到周期消费延迟 | `handleBeamWeapon → consumeAmmo` | `@Inject HEAD` + 前置门控 |
| SEMI_BEAM 周期消费 | **不存在此路径** | 不得声称存在 |

- **每次实际扣弹入口**最多一次概率判定;若同一次 BEAM 处理同时触发公共 `Math.max` 与周期 `consumeAmmo`,则**各判定一次**。不得写成“同一调用绝对只判定一次”或把“每次射击”与“每次实际扣弹”混为一谈。
- 普通射击 Redirect 成功时返回 `oldCount`,剩余 1 发成功节省**不会**清除已装弹种类(`clearLoadedProjectileItem` 被跳过)。
- 光束命中时 HEAD `ci.cancel()`,不复制、不写 NBT。
- 普通路径上创造/`IgnoreAmmo`/Reclaimed 命中跳过 `Math.max`,Redirect 根本不会执行。
- 光束路径上创造/`IgnoreAmmo`/空弹由 `AmmoSaverBeamGate` 在抽奖前短路。

---

## 3. 5B.1.1 光束前置条件修正

新增只读门控(无 Jobs+ 依赖,可单测):

| 类 | 职责 |
|---|---|
| `AmmoSaverBeamGate` | 纯逻辑:仅当会进入真实扣弹时才调用概率源;异常 fail-closed |
| `AmmoSaverStackRead` | 只读 `DataComponents.CUSTOM_DATA`(`ItemStack.get` + `CustomData.copyTag`);**不** `getOrCreate`、不写 NBT、不改 stack |

API 以 Minecraft 1.21.1 编译 classpath `javap` 为准(`CustomData.copyTag` / `isEmpty` / `DataComponents.CUSTOM_DATA`)。

行为矩阵:

| 条件 | 概率源 | cancel |
|---|---|---|
| player/stack null 或 empty | 不调用 | 否 |
| creative=true | 不调用 | 否 |
| IgnoreAmmo=true | 不调用 | 否 |
| AmmoCount≤0 / CustomData 缺失(视为 ammo=0) | 不调用 | 否 |
| 生存 + IgnoreAmmo=false + ammo>0 + 抽奖成功 | 恰好 1 次 | 是 |
| 同上 + 抽奖失败 | 恰好 1 次 | 否(SG 原扣弹) |
| CustomData/概率异常 | fail-closed | 否 |

普通射击 `handleShoot Math.max` Redirect **未改动**语义(仅注释/文档对齐)。

---

## 4. Mixin 配置拆分(可选依赖隔离)

| 配置 | 内容 | requiredMods |
|---|---|---|
| `scguns_compat.mixins.json` | `NiamiArrowSpawnMixin` | `["scguns"]` |
| `scguns_ammo_compat.mixins.json` | `AmmoSaverMixin` | `["scguns", "jobsplus"]` |

- `neoforge.mods.toml` 两个 `[[mixins]]` 块 requiredMods 与上表一致(构建 JAR 内已展开审计)。
- `JobsPlusCompatModule` 在 `ModList.isLoaded("scguns")` 真分支调用 `GunnerAbilityModule.init`;字节码常量池含 `scguns` 与 `GunnerAbilityModule`/`init`(结构测试加固)。

---

## 5. 依赖组合:结构 vs 实机

`GunnerDependencyMatrixTest` 等验证的是 Mixin 配置、class 常量池、mods.toml、源码/字节码守卫、SG JAR 注入点结构 —— **不是**四种模组组合的真实启动。

| 组合 | 结构验证 | 实机启动 |
|---|---|---|
| 仅 TCTH | STRUCTURAL PASS | LIVE NOT TESTED |
| TCTH + SG、无 Jobs+ | STRUCTURAL PASS | LIVE NOT TESTED |
| TCTH + Jobs+/Arc、无 SG | STRUCTURAL PASS | LIVE NOT TESTED |
| TCTH + SG + Jobs+/Arc | STRUCTURAL PASS | **LIVE PASS**(完整环境烟雾) |

表述:**结构测试验证四种依赖组合的静态结构边界;其中只有完整模组环境完成实机启动。**
不得再写“四种组合均 PASS”或“静态结构测试等于实机组合启动”。

结构测试加固点(5B.1.1):

1. `JobsPlusCompatModule` 编译字节码含 `scguns` 门控与 `GunnerAbilityModule`/`init`(源码字符串搜索仅作辅助)。
2. Niami Mixin 常量池无 Jobs+/Arc 类型。
3. AmmoSaver 配置同时要求 `scguns`+`jobsplus`。
4. 研修 Arc 条件常量池无 SG 类型。
5. 构建 JAR 存在时审计展开的 mods.toml 与两个 mixin JSON。
6. javap:公共 `Math.max` 唯一; `consumeAmmo` 仅 `handleBeamWeapon`;BEAM 字段与周期路径同在;SEMI_BEAM 仅出现在 `handleShoot` 分发。

---

## 6. 真实扣弹语义测试

| 套件 | 内容 |
|---|---|
| `AmmoSaverLogicTest` | 公共 `Math.max` 语义:失败 N→N-1;成功 N→N;剩余 1 发成功不 clear |
| `AmmoSaverBeamGateTest` | 计数型 seam 证明创造/IgnoreAmmo/空栈/空弹/读失败**不调用概率源**;成功 cancel / 失败不 cancel;CustomData 缺失与 adapter 只读 |
| `GunnerAbilityModuleTest` 等 | 概率档位/开关/fail-closed(既有) |

---

## 7. 测试与构建(5B.1.1)

- `./gradlew clean build --no-daemon`:成功。
- **实际 XML 汇总:`72 suites / 591 tests / 0 failures / 0 errors / 0 skipped`**。
- 枪客相关:光束前置条件新测 + AmmoSaverLogic + DependencyMatrix + AbilityModule/Damage/Reference/TreePreset/Condition 全绿。

---

## 8. JAR 审计

- 构建 JAR:`build/libs/tcth-0.2.1.jar` = **269,194 B**。
- 完整 SHA-256:**`4e33242b998f94b3239ac147b01dc8eb0aa29d3da3641ffa1b7fd5139b998676`**。
- 部署 JAR `Server/mods/tcth-0.2.1.jar` = 同一 SHA(**一致**)。
- 两个 SG mixin 配置均在 JAR 内;展开 mods.toml requiredMods 正确。
- 无第三方 class(无 `top/ribs/`、`com/daqem/` 根)、无嵌套第三方 JAR。
- 部署前备份:`backup-5b.1.1-pre-deploy-20260807/tcth-0.2.1.jar.5b1`(SHA `c09a23a6...`,5B.1 版)。

---

## 9. 服务器烟雾(完整模组环境,无玩家) — LIVE PASS

日志:`Server/logs/smoke5b11_2.out`(+ `debug.log` Mixin DEBUG 行)

| 检查项 | 结果 |
|---|---|
| `Done` | ✓ |
| `Loaded 3 jobs` / `Loaded 137 job powerups` | ✓ |
| 探针强制加载 `ServerPlayHandler` | ✓ |
| `Mixing NiamiArrowSpawnMixin from scguns_compat.mixins.json` | ✓ (debug.log) |
| `Mixing AmmoSaverMixin from scguns_ammo_compat.mixins.json` | ✓ (debug.log) |
| `InvalidInjectionException` / `MixinApplyError` / `NoClassDefFoundError` | 无 |
| `Gunner ability module active` | ✓ |
| TCTH ERROR/WARN | **0** |
| 正常 `stop` + `All dimensions are saved` | ✓ |
| 残留服务端 Java | 无 |

普通射击 Redirect 在 5B.1 已实机应用成功;本轮 AmmoSaverMixin 继续应用成功(含前置门控新代码)。

---

## 10. 玩家在线未验证项(弹药路线不标记 PASS)

> **延期决定（2026-08-07）**：服主决定暂不执行弹药路线在线验收。
> 阶段 5B.1.1 的静态测试、真实 SG 字节码审计、完整模组环境 Mixin
> 加载与服务器烟雾测试均已通过；下列玩家操作仍记为 **DEFERRED / LIVE
> NOT TESTED**，不得写成 PASS。本决定不关闭已部署能力，也不阻塞后续开发；
> 正式公开发布或需要确认玩家体感前再集中补验。

后续在线验收仍需:

- 普通手枪、霰弹枪、火箭/榴弹、Niami
- BEAM、SEMI_BEAM
- 剩余 1 发成功节省
- 创造模式不抽奖、不复制
- IgnoreAmmo 不抽奖
- 每个真实扣弹入口最多一次概率判定(区分“每次射击”与“每次实际扣弹”)

枪术/防护/研修在线数值验证沿用 5B 报告清单。

当前发布状态：**测试服可继续观察，弹药路线在线效果待补验**。

---

## 11. 建议暂存清单(等待授权,不自动执行)

```
git add CHANGELOG.md
git add "docs/phase-5b-gunner-abilities-report.md"
git add "docs/phase-5b.1-gunner-fix-report.md"
git add src/main/java/com/tanrunn/tcth/impl/compat/scguns/AmmoSaverLogic.java
git add src/main/java/com/tanrunn/tcth/impl/compat/scguns/AmmoSaverBeamGate.java
git add src/main/java/com/tanrunn/tcth/impl/compat/scguns/AmmoSaverStackRead.java
git add src/main/java/com/tanrunn/tcth/impl/compat/scguns/mixin/AmmoSaverMixin.java
git add src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/JobsPlusCompatModule.java
git add src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/powerup/GunnerAbilityModule.java
git add src/main/resources/scguns_compat.mixins.json
git add src/main/resources/scguns_ammo_compat.mixins.json
git add src/main/templates/META-INF/neoforge.mods.toml
git add src/test/java/com/tanrunn/tcth/impl/compat/scguns/AmmoSaverLogicTest.java
git add src/test/java/com/tanrunn/tcth/impl/compat/scguns/AmmoSaverBeamGateTest.java
git add src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/powerup/GunnerDependencyMatrixTest.java
git add src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/powerup/GunnerAbilityReferenceTest.java
```

(若 5B.1 中其他已改文件尚未提交,可一并按复审清单加入;勿 `git add -A`。)

---

## 12. 回滚方法

1. 停服。
2. 从 `backup-5b.1.1-pre-deploy-20260807/tcth-0.2.1.jar.5b1` 恢复 `Server/mods/tcth-0.2.1.jar`(5B.1 SHA `c09a23a6...`)。
3. 若需回到更早 5B:`backup-5b.1-pre-deploy-20260807/tcth-0.2.1.jar.5b`(SHA `24dae54a...`)。
4. 启动验证;客户端 AutoModpack 需同步回退(服务器重新生成包)。
