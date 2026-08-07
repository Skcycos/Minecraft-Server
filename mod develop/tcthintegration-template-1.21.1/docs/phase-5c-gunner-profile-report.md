# Phase 5C / 5C.1 — 枪客战地档案与勋章系统

- 日期:2026-08-07
- 模块:`mod develop/tcthintegration-template-1.21.1`(版本号仍为 0.2.1)
- 范围:复用 5A 统计实现战地档案视图 + 五枚基础勋章 + 解锁提示;不发金币/额外经验/物品;不改 GD656;不改能力树数值;未 commit/push。
- **5C.1 复审修正**:Top-N 真正不可变快照;profile/勋章/提示全面 `Component.translatable`;阈值常量单一来源。

---

## 1. 审计结论(实施前)

| 项 | 结论 |
|---|---|
| 总击杀 | 复用 `totalGunKills` |
| 武器 Map | 复用 `weaponKills: Map<String,Integer>`(物品 id 字符串 → 次数) |
| tier 分布 | 复用 `common/elite/heavy/bossKills` |
| 最远距离 | **复用 `maxDistance`**,不新增 `longestKillDistance` |
| 主武器 | 复用 `getMostUsedWeapon()`,修正平手规则 |
| SavedData | `world/data/tcth_gunner_stats.dat`,`dataVersion` **1 → 2** |
| eventId 幂等 | `GunnerStatsTracker`(成功 `record` 后才 commit) |
| `/tcth gunner stats` | 保留兼容;权限 ≥3 查他人 |
| 安全 | 武器 4096 上限、玩家 1024 上限、饱和加法 |

**不重复建设第二套统计。**

---

## 2. 实现摘要

### 2.1 统计加固

- `record`:仅 `finite && distance >= 0` 才更新 `maxDistance`。
- NBT 加载:`NaN` / 无穷 / 负数 → `0.0`。
- 主武器 / Top-N:击杀数降序,完整 id 字典序升序;不依赖 HashMap/NBT 顺序。
- Top-3 用于 profile;`getTopWeapons(n)` 返回 **不可变 List + `Map.entry` 不可变快照**(5C.1:`setValue` 抛异常,内部击杀不变)。

### 2.2 五枚勋章(代码常量,不可配置)

| ID | 中文 | 条件 |
|---|---|---|
| `first_blood` | 初次交锋 | `totalGunKills >= 1` |
| `centurion` | 百战之证 | `totalGunKills >= 100` |
| `long_shot` | 远射手 | `maxDistance >= 50.0` |
| `elite_hunter` | 精英猎手 | `eliteKills >= 25`(不含 HEAVY) |
| `boss_finisher` | 强敌终结者 | `bossKills >= 1` |

- 枚举顺序固定:`FIRST_BLOOD → CENTURION → LONG_SHOT → ELITE_HUNTER → BOSS_FINISHER`。
- 持久化:`unlockedMedals: medalId → unlockedAtEpochMillis`;上限 128;未知 id 跳过;永久不撤销。
- 新解锁:`unlockedAt = System.currentTimeMillis()`。
- 迁移/补记:`unlockedAt = 0`,**永不提示**。

### 2.3 dataVersion 2 与旧档

- 加载 v1 或缺失勋章字段:按现有计数静默补记已满足勋章。
- v2 缺失但已满足:同样静默补全。
- 不改击杀/武器/tier/`maxDistance`;不写 playerdata。
- 需写回时 `setDirty()`,保存写出 `dataVersion=2`。

### 2.4 实时解锁与提示

- 流程:`record` → `GunnerMedalEvaluator.unlockNewlyMet` → `setDirty` → commit eventId → 可选提示。
- 配置:`gunnerMedalAnnouncementsEnabled`(默认 `true`)。
- 关闭时仍解锁持久化,不提示;再开不补发。
- 配置异常 fail-closed(不提示),解锁与统计不受影响。
- 同事件多勋章合并一条 **`Component.translatable("tcth.gunner.medal.unlocked", …)`** 系统消息(非 ActionBar、不全服);服务端**不**读取 `getLanguage()`。
- 勋章名 / 分隔符均为翻译键(`tcth.gunner.medal.<id>`、`list_separator`)。
- 提示失败不回滚统计/勋章。

### 2.5 命令

| 命令 | 行为 |
|---|---|
| `/tcth gunner stats [player]` | 5A 兼容简洁输出(不变语义) |
| `/tcth gunner profile [player]` | 完整战地档案(Top-3 武器 + 勋章列表) |

- 本人任意玩家;他人权限 ≥3;控制台无参拒绝。
- 只读;无 reset;距离一位小数;无主武器/无勋章显示「暂无」。

### 2.6 新增类(包内,无公共 API/网络包)

- `GunnerMedal`
- `GunnerMedalEvaluator`
- Tracker / Command / Config / `PlayerGunnerStats` / `GunnerStatsData` 扩展

### 2.7 GD656

完全独立:不 `addScore`/`addKill`/`BonusEngine`、不读写 `gd656killicon`、不声明依赖。

---

## 3. 测试

```
suites=77
tests=630
failures=0
errors=0
skipped=0
```

新增/扩展覆盖:

- 阈值边界(0/1、99/100、49.9/50.0、24/25 ELITE、0/1 BOSS);常量单一来源
- v1 迁移静默补记 + `unlockedAt=0` + `dataVersion=2`
- v2 缺失补全、未知 medal id 跳过
- 主武器平手字典序、Top-3、List 不可变 + **Entry.setValue 抛异常且内部不变**
- 提示合并/配置关/再开不补发/异常 fail-closed/重载不重复提示
- profile/勋章/提示组件树使用翻译键;en_us+zh_cn 键齐全
- 距离 NaN 防御

单元测试 ≠ 玩家实测。

---

## 4. JAR 与部署

| 项 | 值 |
|---|---|
| 大小 | **281,766 B** |
| SHA-256 | **`21deb294f8049cca8514f6da8bf1659ae6d6423f5c7795cbf5379aea34559c44`** |
| 构建 = 部署 | 一致 |
| 备份 | `backup-5c.1-pre-deploy-20260807/tcth-0.2.1.jar.pre5c1`(`d0dfb152…`,5C) |
| 纯净性 | 无第三方 class、无嵌套 JAR |

---

## 5. 烟雾(完整环境,无玩家) — LIVE PASS

`Server/logs/smoke5c_1.out`:

- `Done` ✓ · `Loaded 3 jobs` / `Loaded 137 job powerups` ✓
- `Gunner ability module active` ✓
- 无 `InvalidInjectionException` / `MixinApplyError` / `NoClassDefFoundError`
- TCTH ERROR/WARN = **0**
- 正常 `stop` + `All dimensions are saved`
- 无残留服务端 Java

---

## 6. 在线未验证 — LIVE NOT TESTED

- `/tcth gunner profile` 玩家端显示
- `/tcth gunner stats` 兼容显示
- 勋章解锁聊天提示(中/英)
- 配置关闭后解锁无提示
- 旧存档进服后勋章静默补记(需有 v1 数据的世界观察)

不得把单元测试写成玩家实测 PASS。

---

## 7. 建议暂存清单(不自动执行)

```
git add CHANGELOG.md
git add docs/phase-5c-gunner-profile-report.md
git add src/main/java/com/tanrunn/tcth/Config.java
git add src/main/java/com/tanrunn/tcth/impl/stats/GunnerMedal.java
git add src/main/java/com/tanrunn/tcth/impl/stats/GunnerMedalEvaluator.java
git add src/main/java/com/tanrunn/tcth/impl/stats/PlayerGunnerStats.java
git add src/main/java/com/tanrunn/tcth/impl/stats/GunnerStatsData.java
git add src/main/java/com/tanrunn/tcth/impl/stats/GunnerStatsTracker.java
git add src/main/java/com/tanrunn/tcth/impl/stats/GunnerStatsCommand.java
git add src/main/resources/assets/tcth/lang/en_us.json
git add src/main/resources/assets/tcth/lang/zh_cn.json
git add src/test/java/com/tanrunn/tcth/impl/stats/GunnerMedalEvaluatorTest.java
git add src/test/java/com/tanrunn/tcth/impl/stats/GunnerMedalMigrationTest.java
git add src/test/java/com/tanrunn/tcth/impl/stats/GunnerWeaponRankingTest.java
git add src/test/java/com/tanrunn/tcth/impl/stats/GunnerMedalAnnounceTest.java
git add src/test/java/com/tanrunn/tcth/impl/stats/PlayerGunnerStatsTest.java
git add src/test/java/com/tanrunn/tcth/impl/stats/GunnerStatsCommandTest.java
```

勿 `git add -A` / commit / push。

---

## 8. 回滚

1. 停服。
2. 用 `backup-5c-pre-deploy-20260807/tcth-0.2.1.jar.pre5c` 覆盖 `Server/mods/tcth-0.2.1.jar`。
3. 若已写出 `dataVersion=2` 的 `tcth_gunner_stats.dat`,旧 JAR 仍可加载玩家计数(忽略未知勋章字段的兼容取决于旧代码);推荐回滚前备份 `world/data/tcth_gunner_stats.dat`。
4. 启动验证;AutoModpack 客户端同步。
