# 阶段 7F 交付报告 — J 键职业 GUI 崩溃修复(Arc condition 网络序列化错位)

- **日期**: 2026-08-10
- **基线**: 阶段 7E 已通过(BUILD PASS,110 suites / 867 tests);TCTH Integration 0.2.5
- **环境**: Minecraft 1.21.1 · NeoForge 21.1.247 · TCTH Integration 0.2.5→0.2.6 · Jobs+ 9.0.0 · Arc 9.0.0 · BrewinAndChewin 4.5.0
- **验证方式**: 版本隔离客户端(HMCL)实测 + 服务器/AutoModpack 同步 + 单元测试 + javap 字节码证据
- **结论**: **修复 PASS(110 suites / 867 tests / 0 failures);客户端 J 键职业 GUI 实测打开成功(全量 186 actions)。**

---

## 一、问题现象

1. 玩家在版本隔离客户端(1.21.1-NeoForge-食韵筑家-beta)进入服务器后按 J 键,Jobs+ 职业 GUI 无法打开。
2. 客户端 debug.log 连续三包失败并断开连接:
   - `Failed to process a synchronized task of the payload: arc:clientbound_update_actions`
   - `Failed to process a synchronized task of the payload: arc:clientbound_update_action_holders`
   - `jobsplus:clientbound_open_jobs_screen`
3. 底层异常:`net.minecraft.ResourceLocationException: Non [a-z0-9/._-] character in path of location: cth:beverage_tier\u0017tcth:brewer/brew_common\u0006COMMON\u000Etcth:automated\u0017tcth:brewer/brew_common\u0000\u0000\u0019tcth:on_beverage_prepared\u0013`
4. 该问题为**间歇性历史问题**(玩家此前多次遇到"有时候能开 GUI 有时候不行"),本次在版本隔离 + 全量数据包下稳定复现。

## 二、定位过程(二分实验)

| 服务器 actions 组合 | actions 数 | J 键 GUI | 结论 |
|---|---|---|---|
| 全量(含 brew_common/brew_t2 + 6 powerup) | 186 | 崩 | 复现 |
| 移除 6 powerup(仅 brew_common/brew_t2) | 180 | 崩 | powerup 无关 |
| 仅保留 study_i(仍含 brew_common/brew_t2) | 181 | 崩 | powerup 无关 |
| 移除 brew_common/brew_t2(仅 powerup) | 179 | 开 | **brew action 为触发源** |

> 客户端版本隔离排除了"多 automodpack 实例/旧缓存 jar"的环境干扰;服务器 JAR(0.2.3/0.2.4/0.2.5)均可复现,与 JAR 版本无关,定位到数据。

## 三、根因分析(javap 权威证据)

### 3.1 Arc 网络读端格式(每 condition 固定)

`IConditionSerializer.fromNetwork(buf)` → 读 2 个 ResourceLocation(type + action location)→ `default fromNetwork(location, buf)` → **`readBoolean()`(inverted)** → `fromNetwork(location, buf, inverted)`。

即读端对每个 condition 的预期字节流为:

```
[type location][action location][inverted: boolean][condition 数据]
```

### 3.2 TCTH 写端缺陷

| Serializer | toNetwork 实现 | inverted 写入 |
|---|---|---|
| `AutomatedCondition` | `super.toNetwork` + `writeBoolean(value)` | ✅ |
| `DishTierCondition` | `super.toNetwork` + `writeUtf(tier)` | ✅ |
| `GunTargetTierCondition` 等其余 10 个 | `super.toNetwork`(+ 数据) | ✅ |
| **`BrewerRewardsEnabledCondition`** | **空方法** | ❌ 缺 1 字节 |
| **`BeverageTierCondition`** | **仅 `writeUtf(tier)`** | ❌ 缺 1 字节 |

### 3.3 错位机制

`brew_common.json` 的 conditions 依次为 `tcth:brewer_rewards_enabled`(无数据)、`tcth:beverage_tier`(COMMON)、`tcth:automated`(false)。写端少写 inverted 后,读端把**下一个字段的长度前缀当 inverted 布尔消费**,再按错误偏移读字符串——恰好命中错误串的每个字符:

```
读到 inverted ← 0x10(="tcth:beverage_tier" 长度 16 的低字节)
读到 type ← 't'(0x74=116)→ 吃掉 "cth:beverage_tier" + "tcth:brewer/brew_common" + "COMMON" + ...
```

错误串 `cth:beverage_tier\u0017tcth:brewer/brew_common\u0006COMMON\u000Etcth:automated\u0017tcth:brewer/brew_common\u0000\u0000\u0019tcth:on_beverage_prepared\u0013` 与上述逐字节预测**完全吻合**(含 `tcth:automated` 的 inverted=false + value=false 两个 `\u0000`)。

### 3.4 为什么只有 brew action 崩

- chef/farmer/gunner/酿酒能力树 action 只引用写端正确的 condition(均调用 `IConditionSerializer.super.toNetwork` 写 inverted)。
- brew_common/brew_t2 是唯一引用 `tcth:brewer_rewards_enabled` / `tcth:beverage_tier` 的 action。
- 服务端加载 186 条 action 后整体打包 `clientbound_update_actions` 同步,任一字节错位即整包失败 → 客户端所有 arc 数据不可用 → J 键 GUI 打不开。

### 3.5 7C.2 在线报告"测试 A PASS"勘误

7C.2(2026-08-09)曾记录 J 键 GUI PASS,当时 brew_common/brew_t2 已含同样的两个缺陷 condition。该 PASS 属间歇性误判(玩家历史亦多次随机复现),根因缺陷在 7C.2 阶段即已存在。本次修复后对同结构全量数据复测为稳定 PASS,详见本报告第五节。

## 四、修复

两处 Serializer 补写 inverted(与其余 12 个 condition 一致):

- `BrewerRewardsEnabledCondition.Serializer.toNetwork`:空方法 → `IConditionSerializer.super.toNetwork(buf, condition);`
- `BeverageTierCondition.Serializer.toNetwork`:写入 `IConditionSerializer.super.toNetwork(buf, condition);` 后再 `writeUtf(tier)`

修复后字节码核验(javap):

```
public void toNetwork(RegistryFriendlyByteBuf, BrewerRewardsEnabledCondition):
   0: aload_0 / aload_1 / aload_2
   3: invokespecial IConditionSerializer.toNetwork:(RegistryFriendlyByteBuf;ICondition;)V   ← 写 inverted
   6: return

public void toNetwork(RegistryFriendlyByteBuf, BeverageTierCondition):
   0: aload_0 / aload_1 / aload_2
   3: invokespecial IConditionSerializer.toNetwork:(...)V   ← 写 inverted
   6: aload_1 / aload_2
   8: getfield BeverageTierCondition.tier
  11: writeUtf(tier)
```

写端与读端恢复严格对称:`[type][action location][inverted][数据]`。

## 五、验证

### 5.1 BUILD PASS

`./gradlew clean build`:110 suites / 867 tests / 0 failures / 0 errors / 0 skipped。`neoforge.mods.toml version="0.2.6"`。

### 5.2 服务器实测

- 服务器 `Server/mods/tcth-0.2.6.jar` 部署,`Done (4.449s)`,`Loaded` 全量数据(186 actions 无报错)。
- AutoModpack 内容生成正常,客户端自动同步新哈希。

### 5.3 客户端实测(版本隔离)

- HMCL 版本隔离实例加入服务器,按 J 键**职业 GUI 正常打开**,无 `ResourceLocationException`、无断线。
- 与修复前(同客户端、全量数据包)稳定复现崩溃形成对照。

## 六、审计教训(防回归)

1. Arc 数据类型的网络序列化必须**读端/写端逐字段对称**;重写 `toNetwork` 时如无数据字段也必须调用 `IConditionSerializer.super.toNetwork` 写 inverted,或明确不写(读端同时改)且以字节流测试兜底。
2. 本阶段对全部 14 个 TCTH condition 的 `toNetwork` 做了一致性复核(12 个正确 + 2 个本次修复),全部达成对称。
3. 同类风险面:全部 TCTH reward 的 `toNetwork`(`IRewardSerializer` 同样先写 2 个 location 再调实例方法)——本次一并抽查一致,未发现同型缺陷。

## 七、文件清单(本次新增/修改)

- 修改:`arc/condition/BrewerRewardsEnabledCondition.java`(toNetwork 补 inverted)、`arc/condition/BeverageTierCondition.java`(toNetwork 补 inverted)、`gradle.properties`(0.2.5→0.2.6)、`CHANGELOG.md`(新增 7F)、`docs/phase-7c.2-mystic-brewer-online-report.md`(测试 A 勘误)
- 新增:`docs/phase-7f-jkey-gui-serialization-fix-report.md`(本报告)
- 部署:`Server/mods/tcth-0.2.6.jar`(构建=部署一致,415,740 字节)

## 八、版本历史补记(0.2.3 → 0.2.4 → 0.2.5 → 0.2.6)

| 版本 | git 提交 | 内容 | 部署状态 |
|---|---|---|---|
| 0.2.3 | `d8af56ca`(2026-08-09) | 魔酿师 Keg 事件与职业经验结算(7A–7C.2.1);7C.2 在线验收 PASS | 正式部署(b725c93c 修复版) |
| 0.2.4 | 未进 git | 7D.1/7E 代码(brewer stats、饮品图鉴、四路线能力树)的部署构建;应要求升级版本号,用于版本隔离排查与多版本复现测试(0.2.3/0.2.4/0.2.5 均可复现序列化缺陷) | 测试部署 |
| 0.2.5 | `2914dc13`(2026-08-10) | 7D.1/7E 正式提交:魔酿师统计、饮品图鉴(brew_common=18 / brew_t2=46)、四路线能力树(12 节点);J 键 GUI 间歇崩溃同版本复现 | 正式部署(11:28) |
| 0.2.6 | 本次(未提交) | **7F 序列化缺陷修复**:`BrewerRewardsEnabledCondition` / `BeverageTierCondition` 的 `toNetwork` 补写 inverted(漏写导致 `arc:clientbound_update_actions` 整包字节错位,J 键 GUI 打不开);修复后 186 actions 全量加载正常,在线验收全部 PASS | 正式部署(与构建 SHA 一致) |

**序列化缺陷与修复方式**(详见第三节):Arc 网络读端对每个 condition 固定读取 `[type][action location][inverted bool][数据]`;两个 brewer condition 的 `toNetwork` 漏写 inverted(一个为空、一个只写 tier),导致含 brew_common/brew_t2 的 action 整包错位 1 字节。修复为在 `toNetwork` 内调用 `IConditionSerializer.super.toNetwork(buf, condition)` 补写 inverted,与其余 12 个 TCTH condition 保持一致。

**回归测试**:`clean build` 110 suites / 867 tests / 0 failures(全部重跑);另有 7F 在线验收对 J 键 GUI、Keg 调饮、图鉴解锁、四路线效果的全量回归(见 `docs/phase-7f-brewer-online-report.md`)。部署产物与构建 SHA-256 一致:`6bcbf7c15a6aa78827c4fc5366a7a8381d284321152cf45f0909a1a2879cee9d`(415,740 字节)。

## 九、阶段结论

**BUILD PASS(110 suites / 867 tests / 0 failures);客户端实测 PASS(186 actions 全量, J 键职业 GUI 打开正常)。**

根因为 Arc condition 网络读端固定读取 inverted 布尔,而 `tcth:brewer_rewards_enabled` / `tcth:beverage_tier` 两个 Serializer 的 `toNetwork` 漏写,导致含 brew_common/brew_t2 的 `clientbound_update_actions` 整包字节错位;修复后写读对称,全量数据稳定通过。
