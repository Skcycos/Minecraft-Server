# 阶段 7C.2 交付报告 — tcth:brewer 魔酿师 Keg 事件与职业经验在线验收

- **日期**: 2026-08-09
- **验收方式**: 真人玩家在线操作(Tanrunn / 27a96fec-9b28-4152-b433-0dd8f085333b),服务器后台日志 + 停服后 playerdata 双路核对
- **环境**: Minecraft 1.21.1 · NeoForge 21.1.247 · TCTH Integration 0.2.3 · Jobs+ 9.0.0 · Arc 9.0.0 · BrewinAndChewin 4.5.0
- **结论**: **BAC Keg COMMON/T2 事件与魔酿师经验结算 PLAYER LIVE PASS。**
  (7C.2.1 补验完成:撤回 7C.2 初版 "COMMON DEVICE NOT COVERED" 结论,蜂蜜瓶经 Keg 真实交付 COMMON 档验证通过)
- 在线验收中发现并修复了 Keg 交付分支的一个真实缺陷(`@Inject` 参数绑定导致背包满掉落/手中替换分支发布错误栈),修复后三个 T2 交付分支全部通过;7C.2.1 新增 4 个参数绑定回归测试(97 suites / 766 tests)。

---

## 一、BUILD PASS

命令:`GRADLE_USER_HOME=<repo>/.gradle-home JAVA_HOME=<JDK21> ./gradlew clean build --no-daemon`

| 轮次 | JAR | suites | tests | failures | errors | skipped | 用途 |
|---|---|---|---|---|---|---|---|
| 0.2.2 | `41417b96...` | 96 | 762 | 0 | 0 | 0 | 基线(7C.2 重跑确认) |
| 0.2.3 | `d4e3efa4...` | 96 | 762 | 0 | 0 | 0 | 升版部署版 |
| 0.2.3 修复版 | `e6d93a5f...` | 96 | 762 | 0 | 0 | 0 | 7C.2 交付分支修复版 |
| 0.2.3 7C.2.1 | `b725c93c...` | 97 | 766 | 0 | 0 | 0 | 参数绑定回归 +4 用例(本轮最终) |

- 测试为真实重跑(`cleanTest test --no-daemon --no-build-cache`),非缓存恢复。
- XML 汇总来自 `build/test-results/test/*.xml` 逐文件聚合。

## 二、构建产物与部署产物

| 项 | 值 |
|---|---|
| 最终构建/部署产物 | `mod develop/tcthintegration-template-1.21.1/build/libs/tcth-0.2.3.jar` = `Server/mods/tcth-0.2.3.jar` |
| 大小 | 367,284 字节(构建=部署一致) |
| SHA-256 | `e6d93a5fda9fc1461cc96710ce863c8ecbb2c61446446196c29b989e05bfc10e` |
| SHA-1 | `e6d93a5f...`(AutoModpack 同步哈希) |
| 第三方纯净性 | JAR 内无第三方 class、无嵌套第三方 JAR |
| 关键内容 | `KegPouringMixin.class`、`brewinandchewin_compat.mixins.json`、`brewer_drinks.json`(64 条标签)、`beverage_tiers/items/` 64 个 tier 文件(18 COMMON / 46 T2)、`BrewerArcRegistrar`/`BrewerRewardModule`/`BeverageActionDispatcher` 等最新 Arc/奖励模块 |
| 版本 | neoforge.mods.toml `version="0.2.3"` |

### 历史产物哈希
- 0.2.2 基线:`41417b9605c97d1da0bd4d8c6d87d4c77558fb2199fced841f651562f3a564e9`(367,283 B)
- 0.2.3 升版(初版,含临时探针):`3b3e6c3c2320c078c21168cab3796be7c3710ef58051060101c38b23e41913d2`(367,714 B)
- 0.2.3 修复版(最终):`e6d93a5fda9fc1461cc96710ce863c8ecbb2c61446446196c29b989e05bfc10e`(367,284 B)
- 服务器旧部署(阶段开始前,已备份):`e076123d3456aff9576623417b215990b08dcaaca883b42ade0cd50b4639094b`(0.2.2,364,923 B)

## 三、ARC LOAD PASS

- `Loaded 180 actions`(基线 178 → 本轮 180,新增 `tcth:on_beverage_prepared` 的 brew_common/brew_t2 两条)。
- `Loaded 4 jobs`(chef / farmer / gunner / brewer),`enable_default_jobs: false` 生效,无 Jobs+ 默认职业回归。
- `tcth:on_beverage_prepared` present=true
- `tcth:beverage_tier` present=true
- `tcth:brewer_rewards_enabled` present=true
  (三者均来自 `logs/debug.log` 的 `[TCTH] brewer arc ... present=true`)
- 六个共享 ActionDataType(result_item_id / count / recipe_id / device / tier / automated)复用 `TcthArcRegistrar` 同一实例(7C.1 修正,本阶段由单元测试 + 事件字段正确性交叉确认)。

## 四、MIXIN LOAD PASS

- `Found mixin config MixinConfig[config=brewinandchewin_compat.mixins.json, requiredMods=[brewinandchewin]]`
- `Mixing KegPouringMixin from brewinandchewin_compat.mixins.json into umpaz.brewinandchewin.common.block.KegBlock`(debug.log)
- 无 `InvalidInjectionException`、无 `MixinApplyError`、无 `NoClassDefFoundError`。
- 对照运行时字节码(`javap` KegBlock.class 4.5.0),三个注入点与 lambda 实际分支一一对应。

## 五、PLAYER LIVE PASS / PARTIAL / NOT TESTED

### 测试 A:职业 GUI ✅ PASS
- J 键打开 Jobs+ GUI(需客户端安装同版 TCTH + AutoModpack 同步 0.2.3)。
- `tcth:brewer` 显示「魔酿师 / Mystic Brewer」并成功加入。
- 加入前等级 0 经验 0;`job debug` 服务器端确认 `tcth:brewer actions: 2`。

### 测试 B:Keg 单容器交付 ✅ PASS(修复后复测)
- 玩家手持 1 空 tankard,从含啤酒的 Keg 灌装;啤酒替换手中。
- 事件:`id=b771efd4-908a-4753-97f5-2fd45e42643f device=KEG result=brewinandchewin:beer count=1 recipeId=null tier=T2 automated=false pos=null`
- 经验 +4(预期 3–5)。
- 注意:本阶段最早一次“B 通过”发生在修复前,当时走的是 `Inventory.add` 分支(背包有空间);修复后重新验证了真正的 `setItemInHand` 手中替换分支。

### 测试 C:Keg 多容器背包交付 ✅ PASS
- 玩家手持一叠空 tankard 灌装,啤酒进入背包。
- 事件:`id=82efab12-ed35-444e-a5b2-f67649c8d244 result=brewinandchewin:beer count=1`
- 验证 7B.1.1 `Inventory.add` 变异修正:发布快照的实际交付数量(count=1),非被 shrink 后的空栈。
- 经验 +4,无重复事件。

### 测试 D:Keg 背包满掉落分支 ✅ PASS(修复前 FAIL → 修复后 PASS)
- **修复前**:填满背包 + 手持多个 tankard 灌装,啤酒掉地但 **0 事件 0 经验**(实测复现缺陷)。
- **修复后**:同一操作出现恰 1 条事件 `id=d4746e60-f467-4a36-b973-31dd906f25d0 result=brewinandchewin:beer count=1`,经验 +3,不与背包分支双发。

### 测试 E:T3 候选负例 ✅ PASS
- 使用 `brewinandchewin:red_rum`(服务器发酵配方确实存在 `red_rum_from_bloody_mary`,Keg 可灌装)。
- 运行时 tier 映射中无 red_rum(0 个 tier 文件)→ `tierFor()=UNKNOWN`。
- 灌装后 **0 事件 0 经验**,事件总数保持 2 条不变。

### 测试 F:无效操作负例 ✅ PASS
- F1 空手打开 Keg 菜单:0 事件 0 经验。
- F2 错误容器(玻璃瓶,啤酒容器应为 tankard):0 事件 0 经验。
- F3 连续真实灌装 eventId 全唯一(B/C/D 各自独立 UUID)。

### 测试 G:COMMON 档说明 → 7C.2.1 补验后 PLAYER LIVE PASS

**7C.2 初版(已撤回)**:当时审计仅看 Keg 发酵配方产出,误判"Keg 液体槽无法获得 COMMON 液体",标记 `LIVE NOT TESTED / DEVICE NOT COVERED`。

**7C.2.1 修正理解**:
- `KegPouringRecipe` codec 中 `can_fill` 默认 **true**(源码 `Codec.BOOL.optionalFieldOf("can_fill", true)`);honey_bottle.json 无显式字段 → 默认 true。
- `data/brewinandchewin/recipe/pouring/honey_bottle.json`:`fluid=brewinandchewin:honey`、`output=minecraft:honey_bottle`。
- 因此支持:用 `minecraft:honey_bottle` 向空 Keg 注入 honey 液体(装填,`canFill` 分支),再用 `minecraft:glass_bottle` 从 Keg 灌出 `minecraft:honey_bottle`。
- **COMMON 蜂蜜瓶经 Keg 真实交付在线验证通过(见 §5.7C.2.1),撤回 "DEVICE NOT COVERED" 结论。**

## 六、经验权威证据(双路交叉确认)

1. **游戏内 `/job` GUI**:玩家逐次报告经验增加(B +4、C +4、D +3、E 0、F 0)。
2. **停服后只读解析 `world/playerdata/27a96fec-9b28-4152-b433-0dd8f085333b.dat`**(gzip NBT,未做任何写入):

```
JobsData: Coins=0.0
  tcth:gunner: Level=1 Experience=42.0   ← 本阶段未变化,无串线
  tcth:brewer: Level=1 Experience=15.0
```

- 经验流水核对:修复前 B+C 累计 8.0 + 修复后 B 复测 +4 + 修复后 D 复测 +3 = **15.0**,与 playerdata 完全一致。
- `tcth:gunner` 保持 42.0(5A.2 阶段历史值),证实 TCTH 饮品 Action **未**影响 chef/farmer/gunner 经验(无串线)。

### 各次记录明细

| 测试饮品 | tier | eventId 前8位 | 交付分支 | 操作前经验 | 操作后经验 | 实际增量 | 预期范围 | 重复事件 |
|---|---|---|---|---|---|---|---|---|
| beer | T2 | 672799b7 | 背包(早期,修复前) | 0 | +4 | 4 | 3–5 | 无 |
| beer | T2 | 82efab12 | 背包(多容器) | +4 | +8 | 4 | 3–5 | 无 |
| beer | T2 | d4746e60 | 背包满掉落(修复后) | — | +11 | 3 | 3–5 | 无 |
| beer | T2 | b771efd4 | 手中替换(修复后复测) | — | +15 | 4 | 3–5 | 无 |
| red_rum | UNKNOWN(T3) | — | 灌装 | — | 0 | 0 | 0 | 0 事件 |
| 空手/玻璃瓶 | — | — | 菜单/无效 | — | 0 | 0 | 0 | 0 事件 |

## 七、本阶段修复:drop 与手中替换分支发布错误栈

- **现象**:背包满掉落(测试 D)与手中替换(修复后验证)两个交付分支 **0 事件 0 经验**,而背包分支正常。
- **诊断**:临时探针日志(`[TCTH][probe]`)实证:
  - `drop branch result=brewinandchewin:tankardx2 held=brewinandchewin:beerx1`
  - `setItemInHand branch result=minecraft:airx0 held=brewinandchewin:beerx1`
- **根因**:`lambda$useItemOn$0` 的字节码参数为 `(stack=param0, player, hand, itm=param3)`;mixin `@Inject` 处理器按位置绑定,`result=param0=stack`(原手持容器/空栈)、`held=param3=itm`(真实交付饮品)。原代码 `publishAfterDelivery(player, result)` 把 stack(UNKNOWN-tier 或空)当作交付物发布,被适配器或空栈检查拦下。
- **修复**:`tcth$onReplacedInHand` 与 `tcth$onDropped` 改为 `publishAfterDelivery(player, held)`,并将 handler 参数重命名为 `originalHeldStack / player / hand / deliveredStack`(明确语义:发布 deliveredStack,绝不发布 originalHeldStack)。
- `Inventory.add` 的 `@Redirect` 处理器直接接收 `add()` 实参(=itm),本就正确,未改动。
- 修复后回归:96/762/0 全过,探针日志已移除,JAR 无 `[TCTH][probe]` 输出。
- 7C.2.1 新增 `KegPouringHandlerParameterBindingTest`(4 用例,反射调用生产 handler,断言事件 result=deliveredStack 而非 originalHeldStack),完整回归 97/766/0。

## 八、最终配置

`Server/config/tcth-common.toml`:

```toml
enabled = true
brewerIntegrationEnabled = true
brewerRewardsEnabled = true
maxBrewerRewardsPerTickPerPlayer = 20
```

`Server/config/jobsplus-common.yaml`:
```yaml
enable_default_jobs: false
```

> 全部通过,`brewerRewardsEnabled=true` 保留供测试服继续观察(符合任务规则)。

## 九、数据包实际路径

- 权威副本:`Server/global_packs/required_data/tcth-brewer/`(仅此一处,无世界 datapacks 重复,`/datapack list` 确认无 tcth-brewer 在 world/datapacks)
- 内容与 `docs/presets/tcth-brewer/data` 逐字节一致(仅 README.md 不复制)。
- 含 `jobsplus/jobs/brewer.json`、`arc/brewer/brew_common.json`、`brew_t2.json`、`brewer/tiers.json`(审计草案,运行时不读)、`beverage_tiers/items/` 64 文件、`tags/item/brewer_drinks.json`(64 条)。

## 十、运行记录与异常

- 测试期间发生 **1 次服务器崩溃**:`ServerHangWatchdog` 单 tick 超时(60s),堆栈在 `ServerLevel.tickPrecipitation → isAreaLoaded`(区块加载),**与 TCTH 无关**(崩溃前最后 TCTH 事件已正常;无 TCTH 异常帧;内存充足 2832/8192 MiB)。崩溃报告已备份至 `tmp/7c.2-crashes/crash-env-hang-20260809-185042.txt`。该次为环境第三方问题。
- 崩溃后重启继续测试,玩家进度(Keg、经验、职业)均保留(playerdata 正常落盘)。
- 测试期间 TCTH ERROR/WARN 计数 0;其他 ERROR 均为既有第三方配方/前置问题(corn_delight、pineapple_delight、GD656 Ping Wheel 等)。

## 十一、回滚步骤

1. 停服:控制台 `stop`,确认 `Saving players / Saving worlds / All dimensions are saved`。
2. 还原 JAR:`cp backup-7c.2-0.2.3-upgrade-20260809-181630/tcth-0.2.2.jar.pre-0.2.3-41417b96 Server/mods/tcth-0.2.2.jar`,删除 `Server/mods/tcth-0.2.3.jar`。
3. 还原配置:`cp backup-7c.2-pre-deploy-20260809-165500/tcth-common.toml.pre-7c.2 Server/config/tcth-common.toml`(该备份为 brewer 两开关 false 版)。
4. 可选:数据包无需回滚(未改动)。
5. 重启并让客户端通过 AutoModpack 重新同步(哈希变化会强制同步)。

## 十二、备份清单

- `backup-7c.2-pre-deploy-20260809-165500/`(旧 JAR `e076123d` + 原配置)
- `backup-7c.2-0.2.3-upgrade-20260809-181630/`(0.2.2 `41417b96` 升 0.2.3 前备份)
- `tmp/7c.2-crashes/`(环境崩溃报告)

## 十三、未验证项

1. ~~COMMON 在线经验~~ **已在 7C.2.1 补验通过**(蜂蜜瓶经 Keg 真实交付,见 §5.7C.2.1)。
2. 第三方设备(如 Create 水槽、Kaleidoscope Tavern 等)若未来接入,需按同类矩阵补验其他档位。
3. 环境 Watchdog 崩溃(第三方)未在本阶段深挖,属既有环境问题。
4. FakePlayer/自动化灌装路径未在线构造(单元测试覆盖 `automated=true` 拦截)。

## 十四、其他记录

- 玩家保留正常测试产生的职业进度(`tcth:brewer` Level 1 / 15.0 XP);未迁移、未回写、未手工编辑 playerdata。
- 未 `git add -A`,未清理无关文件,未 commit/push。
- AutoModpack 内容快照已确认包含 `tcth-0.2.3.jar` sha1=`e6d93a5f...`(最终修复版),客户端强制同步可获取。
- 任务原文提示的 `e076123d...` 旧版已由本阶段 0.2.3 修复版取代。

---

### 阶段结论

**BAC Keg COMMON/T2 事件与魔酿师经验结算 PLAYER LIVE PASS。**

- T2 三个交付分支全部通过(单容器 setItemInHand / 多容器背包 Inventory.add / 背包满掉落 Player.drop),含 7C.2 修复后验证。
- COMMON 蜂蜜瓶经 Keg 真实交付在线验证通过(7C.2.1),撤回初版 "DEVICE NOT COVERED" 结论。

---

## 十五、阶段 7C.2.1 补验记录(追加)

### 5.1. 7C.2.1 COMMON 蜂蜜瓶在线补验(PLAYER LIVE PASS)

- **修正**:7C.2 初版对 BAC pouring recipe 的理解有误。`KegPouringRecipe` 的 `can_fill` 默认 true(源码 codec),honey_bottle.json 无显式字段即默认支持装填;故蜂蜜瓶可向空 Keg 注入 honey fluid,再用玻璃瓶灌出 honey_bottle。撤回 "COMMON DEVICE NOT COVERED"。
- **B 装填**(蜂蜜瓶 → 空 Keg):0 BeveragePreparedEvent、0 经验。✅
- **C 灌出**(玻璃瓶 → Keg):恰 1 事件:
  `id=5a70dd5c-ef93-4391-acf1-84744cf2fbc6 device=KEG result=minecraft:honey_bottle count=1 recipeId=null tier=COMMON automated=false pos=null`,经验 +1(预期 1–2)。✅
- **D**:本轮唯一事件 eventId 无重复;`tcth:gunner` 保持 42.0 无串线。✅
- 权威核对:playerdata `tcth:brewer Experience=16.0`(15.0 + COMMON +1)。

### 5.2. 7C.2.1 Keg handler 参数绑定回归测试(新增 4 用例)

- **背景**:7C.2 在线探针实证 `@Inject` 处理器按位置绑定,`result=param0=originalHeldStack`、`held=param3=deliveredStack`;初版代码发布 `result` 导致手中替换/背包满掉落分支 0 事件。
- **修复**:两个 `@Inject` handler 参数重命名为 `originalHeldStack / player / hand / deliveredStack`,并改为 `publishAfterDelivery(player, deliveredStack)`。
- **回归测试**:`KegPouringHandlerParameterBindingTest`(com.tanrunn.tcth.impl.event),反射调用两个生产 handler,分别传空容器/空栈与正式饮品,断言事件 result 是 deliveredStack、绝非 originalHeldStack;非源码字符串扫描。Inventory.add 原有变异测试保留。
  - `setItemInHandPublishesDeliveredStackNotEmptyOriginal` PASS
  - `dropPublishesDeliveredStackNotRemainingContainer` PASS
  - `setItemInHandWithUnknownTierPublishesNothing` PASS
  - `dropWithUnknownTierPublishesNothing` PASS
- 完整构建:`97 suites / 766 tests / 0 failures / 0 errors / 0 skipped`(762 + 4)。

### 5.3. 7C.2.1 构建产物

| 项 | 值 |
|---|---|
| JAR | `build/libs/tcth-0.2.3.jar` = `Server/mods/tcth-0.2.3.jar` |
| 大小 | 367,301 字节 |
| SHA-256 | `b725c93c51750e723c4a2be6cde7bd8850923bafdb117c5a4009394b84bde4ca` |
| 上一版(7C.2 修复版,已备份) | `e6d93a5fda9fc1461cc96710ce863c8ecbb2c61446446196c29b989e05bfc10e` |
| 备份 | `backup-7c.2.1-predeploy-20260809-210403/` |
| 第三方纯净性 | 无第三方 class、无嵌套 JAR;含 Keg Mixin、brewer_drinks.json、64 tier 文件、Arc/奖励模块 |
| 部署后 AutoModpack | 快照 sha1 与部署 JAR 一致,客户端强制同步 |

### 5.4. 7C.2.1 启动验证(本轮唯一一次启动)

- `Done`、`Loaded 4 jobs`、`Brewer tiers loaded: 64 items (18 COMMON / 46 T2)`、`Loaded 180 actions`
- 无 `InvalidInjectionException` / `MixinApplyError` / `NoClassDefFoundError`;TCTH ERROR/WARN 计数 0
- `tcth debug brewing on` 开启,测试后关闭
- 正常停服:`Saving players / Saving worlds / All dimensions are saved`,无残留进程
- 未手工编辑 playerdata、未重复烟雾、未 commit/push
