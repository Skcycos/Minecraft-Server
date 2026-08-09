# 阶段 7C：魔酿师 Arc 接入与奖励

日期：2026-08-09

## 结论分层

| 层级 | 状态 |
|---|---|
| **BUILD PASS** | 95 suites / 756 tests / 0 failures（`e076123d`） |
| **ARC LOAD PASS** | `tcth:on_beverage_prepared` Action + `tcth:beverage_tier`/`tcth:brewer_rewards_enabled` Condition + 6 数据类注册，verify 全 present（烟雾 9 项） |
| **MIXIN LOAD PASS** | `KegPouringMixin` 应用成功，零注入错误（`e076123d` 唯一一次最终烟雾） |
| **PLAYER LIVE** | **NOT TESTED**（不做在线经验验收） |
| commit/push | **未做** |

---

## 一、Arc 接入

### ActionType `tcth:on_beverage_prepared`（`BrewerArcRegistrar`）

| 数据 | 类型 | 说明 |
|---|---|---|
| ITEM_STACK | ItemStack | 交付栈防御复制 |
| ITEM | Item | 交付物品 |
| `result_item_id` | String | 物品 id |
| `count` | Integer | 实际交付份数 |
| `recipe_id` | String(可 null) | Keg 恒 null |
| `device` | String | BeverageDevice.name() |
| `tier` | String | BeverageTier.name() |
| `automated` | Boolean | 自动化标记 |

### ConditionType

- **`tcth:beverage_tier`**：匹配 `UNKNOWN/COMMON/T2/T3`（大小写归一，未知 tier 数据加载报错）；支持 inverted
- **`tcth:brewer_rewards_enabled`**：三开关组合（`Config.ENABLED && BREWER_INTEGRATION_ENABLED && BREWER_REWARDS_ENABLED`）；配置读取异常 fail-closed，**inverted 不得把异常翻转为放行**

### 隔离

Arc/Jobs+ 类型只存在于 brewer compat 包；`JobsPlusCompatModule` 的 `arcAvailable` 分支注册（Arc 缺失不加载）。

## 二、奖励模块（`BrewerRewardModule`）

- **player=null / automated=true → 不结算**
- **UNKNOWN / T3 → 不结算**（T3 无 Action 无奖励；UNKNOWN 未分级）
- **eventId 有界幂等缓存**（40 tick 过期 / 4096 上限）——**发送成功后才提交**
- **每玩家每 tick 独立限速**（`maxBrewerRewardsPerTickPerPlayer=20`）
- **单事件异常隔离**：失败不占缓存/限速，可重试
- **停服清理缓存**（ServerStopping）
- **不发金币**，不改料理/农夫/枪客奖励

## 三、配置（中英文注释）

- `brewerRewardsEnabled=false`（默认关）
- `maxBrewerRewardsPerTickPerPlayer=20`（范围 1-1000）

## 四、tcth-brewer 预设正式化

| 文件 | 奖励 | 条件 |
|---|---|---|
| `arc/brewer/brew_common.json` | COMMON 基础经验 1-2 | brewer_rewards_enabled + tier=COMMON + automated=false |
| `arc/brewer/brew_t2.json` | T2 基础经验 3-5 | brewer_rewards_enabled + tier=T2 + automated=false |

- 两档条件**互斥**（tier COMMON vs T2）；均要求 **automated=false**
- **T3 无 Action、无奖励**；不增加"饮用经验"（只奖励真实 Keg 灌装完成）
- 不发金币
- `brewer.json` 保留 `max_level=100`、`is_default=false`
- 客户端职业翻译键已补齐（zh_cn/en_us 各 2 键）

## 五、数据（主 JAR 标签 + 数据包逐物品）

- 主 JAR：`data/tcth/tags/item/brewer_drinks.json`（64）
- tcth-brewer 数据包：`data/tcth/beverage_tiers/items/<ns>/<path>.json`（64 逐物品，每文件 COMMON/T2）
- `BeverageTierManager` 原子加载（FOLDER=`beverage_tiers`，SimpleJsonResourceReloadListener）；**修复**：该 listener 的 key 不含 FOLDER 前缀，ITEMS_PREFIX 用 `items/` 后 tiers 正确加载 64（烟雾验证 `Brewer tiers loaded: 64 items (18 COMMON / 46 T2)`）

## 六、测试

| 测试 | 用例 | 覆盖 |
|---|---|---|
| `BrewerRewardModuleTest` | 8 | COMMON/T2 各恰 1 次；UNKNOWN/T3/automated/null 0；三开关 fail-closed；幂等成功后提交；失败不占缓存/限速；限速 2/tick；异常隔离 |
| `BrewerPresetDataTest` | 5 | COMMON 1-2/T2 3-5；互斥+门控；job 标志；翻译键 |
| 既有全部保留 | — | — |

全量：**95 suites / 756 tests / 0 failures**（92/741 基线 + 13 新增）

## 七、最终证据

| 项 | 值 |
|---|---|
| JAR SHA-256 | `e076123d3456aff9576623417b215990b08dcaaca883b42ade0cd50b4639094b` |
| 部署 | 覆盖旧缺陷 JAR `215b4971` |
| 烟雾 | Done=1、Loaded 4 jobs、Brewer Arc 注册 9 项、Brewer tiers 64、Keg Mixin 应用、零注入/加载错误、TCTH ERROR/WARN=0、brewerRewardsEnabled=false、数据重载零错误、正常停服 |
| tcth-brewer 数据包 | 已部署 `Server/global_packs/required_data/tcth-brewer/` |

## 八、边界遵守

- 未修改 playerdata、UNITE、其他职业数据和能力树
- 未 commit/push

## 九、建议暂存清单（不得自行 commit）

- `src/main/java/com/tanrunn/tcth/impl/compat/brewer/**`（BrewerRewardModule + arc 包 5 类）
- `src/main/java/com/tanrunn/tcth/impl/compat/jobsplus/JobsPlusCompatModule.java`（+brewer 注册）
- `Config.java`（+brewerRewardsEnabled/maxBrewerRewardsPerTickPerPlayer）
- `src/main/java/com/tanrunn/tcth/impl/brewing/BeverageTierManager.java`（FOLDER 修复）
- `src/main/resources/data/tcth/tags/item/brewer_drinks.json`
- `docs/presets/tcth-brewer/data/tcth/arc/brewer/**`（2 新）+ `beverage_tiers/items/**`（64）
- `Server/global_packs/required_data/tcth-brewer/**`（部署）
- 测试 `BrewerRewardModuleTest`/`BrewerPresetDataTest`/`BrewerDataConsistencyTest`
- 本报告 `docs/phase-7c-mystic-brewer-arc-rewards.md`

**7C 完成。BUILD PASS / ARC LOAD PASS / MIXIN LOAD PASS / PLAYER LIVE NOT TESTED。等待复审。不 commit/push。**

---

# 阶段 7C.1：阻断修正

日期：2026-08-09（追加）

> **撤回 7C 初版"奖励可结算"的结论**：7C 初版 `BrewerArcRegistrar` 重复 register 了 6 个 ActionDataType（result_item_id/count/recipe_id/device/tier/automated），与 `TcthArcRegistrar` 的对象**不是同一实例**——条件读取的数据键与写入方不一致，奖励不可靠结算。7C.1 已修正，初版结论作废。

## 一、删除重复 register（核心）

- `BrewerArcRegistrar` 删除 6 个 ActionDataType 的重复 `ActionDataType.register`
- 只保留 3 个新注册：`tcth:on_beverage_prepared` ActionType、`tcth:beverage_tier`、`tcth:brewer_rewards_enabled` ConditionType
- `BeverageActionDispatcher` / `BeverageTierCondition` 改用 **`TcthArcRegistrar` 共享对象**（RESULT_ITEM_ID/COUNT/RECIPE_ID/DEVICE/TIER/AUTOMATED）
- **Arc 表述修正**：烟雾的"Arc 9 项"应理解为 **3 个新注册 + 6 个共享数据类型检查**（verifyRegistrations 只查 3 个新项；6 个共享数据由 TcthArcRegistrar 验证）

## 二、身份与真实条件测试（`BrewerArcIdentityTest`）

- `assertSame(brewer 数据类型, TcthArcRegistrar 对应对象)` ×6——**不是 location 相同或 registry present**
- 构建 Beverage ActionData 后**直接执行** `AutomatedCondition(false, false)`：automated=false 匹配、automated=true 不匹配
- COMMON/T2 两份完整条件组合（brewer_rewards_enabled + tier + automated=false）各命中一次 + 互斥

## 三、eventId 缓存修复

- `pruneExpiredLocked`：**先清过期** → `while(size > 4096)` **驱逐最旧**（LinkedHashMap 插入序）
- 压力测试：5000 事件后缓存**绝不超过 4096**
- 值类型改 `EventRecord(playerUuid, committedTick)`

## 四、失败重试修正

- `sendFailureThenRetrySameEventSettlesOnce`：**同一 BeveragePreparedEvent** 首次失败 → 重发成功恰 1 次 → 重复被抑制
- `exceptionIsIsolatedAndSameEventRetryable`：同一事件异常后可重试

## 五、WARN 节流

`BrewerRewardsEnabledCondition` 配置异常 WARN **60 秒节流**（`warnThrottled` + `lastWarnAt` + `resetThrottleForTesting`）——不再每次 warn。

## 六、登出清理

- RECENT_EVENT_IDS 关联 player UUID；`PlayerLoggedOutEvent` 清该玩家 eventId + 本 tick 计数
- `ServerStoppingEvent` 全量清理；生命周期注册幂等
- 测试：A 登出后 A 可重结、B 幂等保留

## 七、历史版本

旧 **`e076123d`** 记为**自动化条件对象不一致的缺陷版本**（brewer 侧数据类型与 TcthArcRegistrar 非同一对象）。

## 八、验证（仅定向测试 + clean build）

- 不修改 Mixin、不重复烟雾、不在线测试、不部署、不 commit/push
- clean build 输出新测试数与 JAR SHA（见下）

**7C.1 完成。停止等待复审。**
