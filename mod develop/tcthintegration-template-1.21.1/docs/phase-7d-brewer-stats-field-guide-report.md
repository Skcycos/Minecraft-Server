# 阶段 7D 交付报告 — 魔酿师统计档案 + Field Guide 饮品图鉴

- **日期**: 2026-08-09
- **基线**: 阶段 7C.2.1 已通过;TCTH Integration 0.2.3(`b725c93c…`,COMMON/T2 Keg 事件与经验均已在线验证)
- **验证方式**: 单元测试 + 一次 clean build;本阶段**不改 Mixin、不做烟雾、不部署、不在线玩家测试**
- **结论**: **BUILD PASS(103 suites / 818 tests / 0 failures);PLAYER LIVE NOT TESTED(统计与图鉴均为运行时行为,需后续在线玩家验证)**

---

## 〇、7D.1 BUILD-only 收口(复审意见落实)

- **开关组合**:`fieldGuideBrewerEnabled` 现在与框架总开关 + 新增 `fieldGuideEnabled`(Field Guide 总开关)组合;chef cookbook 与 brewer 两个分支均检查三级开关。所有配置读取 **fail-closed**(读取异常→视为关闭,不传播)。
- **统计幂等缓存**:补齐 **容量上限(4096,LRU)**、**40 tick 过期**(tick 定期清理)、**登出清理**(按玩家 UUID)、**停服清理**;eventId 仅在统计写入成功后才提交(失败可重试)。
- **异常日志节流**:Field Guide chef/brewer 解锁失败日志按 **60 秒节流**(与 GunnerStatsTracker 一致)。
- **最常饮品并列**:完整物品 ID 字典序升序(`getMostPreparedBeverage`),结果确定。
- **NBT 加载加固**:拒绝非法 ResourceLocation(显式 `ns:path`、拒绝路径穿越 `..`)、负计数(钳制 0 / 条目不恢复)、未知枚举跳过,计数保持饱和。
- **图鉴只响应 `BeveragePreparedEvent`**:普通拾取、指令给予、饮用均不解锁(由事件驱动 + gate prereq 双重保证)。
- **回归**:既有 7A–7C.2.1 全部测试保留并纳入汇总。

---

## 一、BUILD PASS

命令:`GRADLE_USER_HOME=<repo>/.gradle-home JAVA_HOME=<JDK21> ./gradlew clean build --no-daemon`,随后 `cleanTest test --no-daemon --no-build-cache` 真实重跑。

| 阶段 | suites | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| 7C.2.1(前) | 97 | 766 | 0 | 0 | 0 |
| 7D(初版) | 103 | 809 | 0 | 0 | 0 |
| 7D.1(收口后) | **103** | **818** | **0** | **0** | **0** |

新增 **6 套件 / 53 用例**(7D.1 在 7D 基础上 +9):

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `PlayerBrewingStatsTest` | 10 | 事件/份数/不同饮品/档次/设备/每饮品计数、最常饮品排序(同数按 id 升序)、空档案、整数饱和、物品上限(新物品不再加/已有继续累加)、防御复制、NBT 加载拒绝负计数/非法 RL(路径穿越/隐式命名空间)、饱和加载 |
| `BrewingStatsDataTest` | 8 | 玩家创建/上限1024/已有存活上限、NBT 往返、未知 UUID 跳过、未知 tier/device 枚举跳过 |
| `BrewingStatsTrackerTest` | 12 | 分级真实事件计数;automated/null player/UNKNOWN/T3/重复 eventId 不统计;开关与框架开关关闭不统计;配置读取异常 fail-closed;40 tick 过期可重试;停服清理;硬上限 4096 |
| `BrewingStatsCommandTest` | 2 | 档案格式化(次数/份数/不同饮品/设备/档次/最常/最近);空档案安全 |
| `BrewerFieldGuideDataTest` | 5 | 两分类 18/46;互斥+总数64;entry 全部 `item:` 前缀 + gate prereq + 严格 ResourceLocation;T3候选/原料/容器/排除项不进入;预设与服务器数据包逐字节一致 |
| `FieldGuideBrewerUnlockTest` | 15 | 真实分级事件解锁对应 entry;获得/食用/命令给予不解锁;重复调制不重复提示;重复 eventId 只解锁一次;automated/null/UNKNOWN/T3 不锁;总开关/子开关/框架关闭不锁;配置读取异常 fail-closed(子开关/总开关);缺失 entry 不锁且不提交;适配器无 Field Guide 环境防御返回 |

- 原有 7A–7C.2.1 全部测试保留并通过(含 `KegPouringHandlerParameterBindingTest`、`KegPouringMutationTest`、`CookingStats*`、`FieldGuideCompatModuleTest`、`FieldGuideApiReferenceTest` 等)。
- `FieldGuideApiReferenceTest` 继续验证:Field Guide 引用仅在 `impl.compat.fieldguide` 包,编译输出不含任何 Field Guide 类 → 缺 Field Guide 时实现类无法解析。
- 测试期间清除了 `.gradle-home` 内因上次推送/rebase 产生的多个损坏 transform 缓存目录(非代码问题)。

## 二、构建产物

| 项 | 值 |
|---|---|
| 构建产物 | `mod develop/tcthintegration-template-1.21.1/build/libs/tcth-0.2.3.jar` |
| 大小 | 387,177 字节 |
| SHA-256 | `139c52de15a28507985a5eb4c52c999aefc9959b56a7f4860cc4334f37661f76` |
| 7D 初版(未部署) | `f5e784955c761094209336604ccd0cf5e16984e4f89329a7ef82bef5dedfe4f3`(383,711 B) |
| 上一版(7C.2.1,仍部署于服务器) | `b725c93c51750e723c4a2be6cde7bd8850923bafdb117c5a4009394b84bde4ca`(367,301 B) |
| 第三方纯净性 | JAR 内无第三方 class、无嵌套第三方 JAR |
| 新类 | `PlayerBrewingStats`、`BrewingStatsData`、`BrewingStatsTracker`、`BrewingStatsCommand`(均在 `impl/stats`) |
| 新资源 | `assets/tcth/textures/gui/fieldguide/brew_common.png`、`brew_t2.png`(16×16 RGBA) |
| 既有保留 | 64 个 tier 文件、`brewer_drinks.json`、`KegPouringMixin`、Arc/奖励模块 |

> 本阶段**JAR 未部署**:`Server/mods/` 仍运行 7C.2.1 的 `tcth-0.2.3.jar`(`b725c93c…`);但 **tcth-brewer 数据包已同步**(`Server/global_packs/required_data/tcth-brewer/` 新增 `fieldguide/categories/`),服务器未启动加载。不做烟雾、不在线测试。

## 三、魔酿师统计(PlayerBrewingStats + BrewingStatsData)

### 3.1 数据结构(`PlayerBrewingStats`)

- 记录字段:
  - `totalBrewingEvents` 调饮事件数(每事件 +1)
  - `totalBeveragesPrepared` 饮品份数(累计栈数量)
  - `uniqueBeverages` 不同饮品数(上限 `MAX_TRACKED_ITEMS=4096`)
  - `tierCounts` COMMON/T2/T3 分布(按份数累计)
  - `deviceCounts` 各 `BeverageDevice` 事件次数
  - `itemCounts` 每种饮品累计数量(上限 4096;已有饮品超出上限仍继续累加,新饮品不再加)
  - `firstPreparedAt` / `lastPreparedAt`
  - `lastBeverage` / `lastDevice` / `lastTier` 最近调制
  - 最常制作饮品(`getMostPreparedBeverage`,同数按 id 字典序升序)
- 全部整数累加为**饱和加法**(`satAdd`,不会溢出)。
- NBT 序列化只存 ResourceLocation 字符串/数字;未知 tier/device 枚举加载时跳过,绝不导致世界加载失败。

### 3.2 SavedData(`BrewingStatsData`)

- 独立文件:`world/data/tcth_brewing_stats.dat`(`NAME="tcth_brewing_stats"`)
- **不写 playerdata**,不依赖 Jobs+/Arc。
- 固定 overworld `DataStorage`(`current(level)` → `level.getServer().overworld().getDataStorage().computeIfAbsent(...)`),跨维度合并。
- UUID 玩家键(改名不影响数据)。
- 玩家上限 `MAX_TRACKED_PLAYERS=1024`:已达上限时新玩家返回 null 跳过,已有玩家继续更新;加载时同样执行上限。
- `dataVersion=1`。
- 防御加载:非法 UUID 条目跳过,未知字段忽略。

### 3.3 统计入口(`BrewingStatsTracker`)

- 监听 `BeveragePreparedEvent`;过滤条件(全部满足才记录):
  1. 框架主开关 + `brewerStatsEnabled=true`;
  2. 玩家非空且 `automated=false`;
  3. 结果数量 `>0`;
  4. tier 为 COMMON 或 T2(`UNKNOWN`/`T3` 不统计);
  5. eventId 非重复(有界幂等缓存,上限 4096)。
- 成功记录后 `setDirty()`;eventId 仅在统计写入后提交(幂等)。
- 在 `TCTHIntegration` 构造中直接 `init`,与 Jobs+/Arc 无关。

### 3.4 命令(`/tcth brewer stats`)

- `/tcth brewer stats` — 查询自己。
- `/tcth brewer stats <player>` — 权限 ≥3 查他人。
- 显示:调饮次数 / 饮品份数 / 不同饮品 / 最常用设备 / 档次分布 / 最常调制 / 最近调制。
- 只读,不提供 reset。

## 四、Field Guide 饮品图鉴

### 4.1 模块接线

- 扩展现有 `FieldGuideCompatModule`(条件加载模块,仅在 Field Guide 安装时实例化),新增 `handleBeveragePrepared` 分支 + `onBeveragePrepared` 监听。
- 新增配置开关 `fieldGuideBrewerEnabled=true`(默认 true;缺 Field Guide 时实现类不加载)。
- 不影响现有厨师图鉴(chef_common/chef_t2/chef_t3 122/190/24 不受影响;`reportCategoryLoadOnce` 同时报告厨师与魔酿师分类计数)。

### 4.2 解锁门(全部满足才解锁)

1. 框架主开关 + `fieldGuideBrewerEnabled=true`;
2. 玩家非空且 `automated=false`;
3. 结果数量 `>0` 且 tier 为 COMMON 或 T2;
4. Field Guide 数据中存在对应 `item:<ns>/<path>` entry;
5. eventId 非重复;解锁成功后提交幂等缓存。
6. 只由真实玩家的 `BeveragePreparedEvent` 解锁;**拿取、饮用、命令给予饮品均不能解锁**。

### 4.3 阻止"获得即解锁"(OBTAIN trigger)

- Field Guide 1.13.4 对 item entry 有隐式 OBTAIN trigger(pickup/eat/scan 触发解锁),无法用数据关闭。
- 沿用厨师图鉴已验证方案:每个显式 entry 固定 `unlock.prerequisites=["tcth:brewer_cookbook_gate"]`,gate 指向**不存在的 entry** → `canUnlock()` 遍历 prerequisites 时 `isUnlocked(gate)` 永远 false → pickup/eat/scan 的 `tryUnlock()` 被拒。
- TCTH 直接调 `unlock()`(不检查 `canUnlock`)→ 事件解锁成功。
- 该机制已由 javap 实证(`PlayerFieldGuideProgress.canUnlock` 逻辑)并复用厨师已验证路径。

### 4.4 两个分类(64 条显式 entry)

| 分类 | 数量 | 内容 |
|---|---|---|
| `tcth:brew_common` | 18 | COMMON 饮品(bakeries/neapolitan/dungeonsdelight/create/minecraft honey_bottle) |
| `tcth:brew_t2` | 46 | T2 饮品(brewinandchewin 14 + kaleidoscope_tavern 32) |

- 每分类 `sort_index`(4/5)与 `icon`(brew_common.png / brew_t2.png)。
- 64 条全部为显式 `item:<ns>/<path>` entry + gate prereq。
- **T3候选(red_rum / saccharine_rum)、原料、容器、排除项不进入**(生成器只从正式 64 条 tier 映射读取)。

## 五、数据生成(`scripts/generate_brewer_field_guide.py`)

- 数据源:**正式 64 条 beverage tier 映射**(`docs/presets/tcth-brewer/data/tcth/beverage_tiers/items/**` 每文件 `{"tier": "COMMON"|"T2"}`),与运行时 `BeverageTierManager` 读取同一权威来源。
- 输出:
  - `docs/presets/tcth-brewer/data/tcth/fieldguide/categories/brew_common.json` + `brew_t2.json`
  - `Server/global_packs/required_data/tcth-brewer/data/tcth/fieldguide/categories/...`(服务器数据包)
- 规则:
  - 每分类计数与 `EXPECTED`(COMMON 18 / T2 46)严格校验;
  - COMMON/T2 互斥,拒绝重叠;
  - 显式拒绝 T3 进入;
  - 每个 entry id 严格 `ns:path` 校验(拒绝路径穿越);
  - 确定性:两次运行字节一致(实测 sha256 `f27053f9…` 两次一致);
  - 生成前清 stale(整个 categories 目录重写,仅管理这两个文件)。
- 服务器数据包与预设**逐字节一致**(`BrewerFieldGuideDataTest.presetMatchesServerGlobalPackCopy` 断言)。
- 生成器输出不加入 JAR 数据目录(与厨师图鉴一致:分类数据走数据包,JAR 仅含图标)。

## 六、配置

`Server/config/tcth-common.toml`(JAR 默认值;本阶段未改服务器实际配置):

```toml
brewerStatsEnabled = true          # 魔酿师统计档案(默认 true)
fieldGuideEnabled = true           # Field Guide 总开关(chef + brewer,默认 true)
fieldGuideBrewerEnabled = true     # Field Guide 饮品图鉴解锁(默认 true)
```

> 服务器当前配置仍为 7C.2.1 状态(brewer 相关开关已开);三个新开关的默认值 true 在下次构建部署后生效,无需手动改配置。

## 七、PLAYER LIVE NOT TESTED

- **统计档案**(`/tcth brewer stats` + `tcth_brewing_stats.dat` 落盘):为运行时行为,需真实玩家调饮验证,本阶段未在线测试。
- **Field Guide 图鉴解锁**(事件解锁 + 获得/饮用/给予不解锁 + 图鉴显示 18/46):需真实玩家验证,本阶段未在线测试。
- 两者逻辑均由单元测试覆盖;部署后需按后续阶段做在线玩家验收。

## 八、未验证项 / 后续

1. 在线验证 `/tcth brewer stats` 显示与 `tcth_brewing_stats.dat` 落盘。
2. 在线验证 Field Guide 两个分类在游戏内显示 18/46、事件解锁、获得/饮用/给予不解锁、重复调制不重复提示。
3. 服务器升级 0.2.3(`139c52de…`)后确认 brew 分类随 tcth-brewer 数据包加载(启动日志应有 `Field Guide brewer categories: tcth:brew_common=18, tcth:brew_t2=46`)。
4. 本阶段不改 Mixin、不改经验、不改其他职业、不改 UNITE、不编辑 playerdata。

## 九、文件清单(本次新增/修改)

- 修改:`Config.java`(3 新开关:brewerStatsEnabled / fieldGuideEnabled / fieldGuideBrewerEnabled)、`TCTHIntegration.java`(注册 BrewingStatsTracker)、`TcthCommands.java`(注册 BrewingStatsCommand)、`FieldGuideCompatModule.java`(brewer 分支 + 三级开关 + 日志节流)
- 新增(主):`impl/stats/PlayerBrewingStats.java`、`BrewingStatsData.java`、`BrewingStatsTracker.java`、`BrewingStatsCommand.java`
- 新增(资源):`assets/tcth/textures/gui/fieldguide/brew_common.png`、`brew_t2.png`
- 新增(脚本):`scripts/generate_brewer_field_guide.py`
- 新增(数据):`docs/presets/tcth-brewer/data/tcth/fieldguide/categories/{brew_common,brew_t2}.json`、`Server/global_packs/required_data/tcth-brewer/data/tcth/fieldguide/categories/{brew_common,brew_t2}.json`
- 新增(测试):`stats/PlayerBrewingStatsTest`、`BrewingStatsDataTest`、`BrewingStatsTrackerTest`、`BrewingStatsCommandTest`;`compat/brewer/BrewerFieldGuideDataTest`;`compat/fieldguide/FieldGuideBrewerUnlockTest`、`FieldGuideCompatModuleTest`(补充总开关注入)

## 十、阶段结论

**BUILD PASS(103 suites / 818 tests / 0 failures);PLAYER LIVE NOT TESTED。**

魔酿师统计档案与 Field Guide 饮品图鉴的代码/数据/测试已完成;7D.1 收口落实了开关组合与 fail-closed、统计幂等缓存(容量/过期/登出/停服清理)、异常日志节流、最常饮品确定排序、NBT 加载加固(非法 RL/负数/未知枚举),统计语义、持久化、上限、图鉴 18/46、解锁语义(仅真实事件解锁、获得/饮用/给予不解锁)均经单元测试验证。**JAR 未部署,但 tcth-brewer 数据包已同步未加载**;本阶段不启动服务器、不做烟雾、不在线测试,等待复审。
