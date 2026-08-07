# TCTH Integration 第三方源码兼容审查报告

| 项 | 内容 |
|---|---|
| 日期 | 2026-08-07 |
| 审查范围 | `mod develop/tcthintegration-template-1.21.1`（只读） |
| 服务器 | `Server/`、`Server/mods/` 实际 JAR（权威） |
| 参考源码 | `源码参考/*`（**只读**；**禁止把当前 checkout 当服务器版本**） |
| 约束 | 未改代码/配置/世界/数据包/第三方源码；未 git add/commit/push |
| 本轮结论 | **仅审查与报告**；修复等待确认 |

---

## 0. 权威顺序与源码分支错位（本轮最重要前提）

### 0.1 判断运行行为的权威顺序（严格执行）

1. `Server/mods/` 中实际安装的 JAR
2. 对实际 JAR 执行的 `javap -p -c`
3. 与 JAR 版本匹配的源码 **tag / branch / commit**（通过 `git show <ref>:...` 只读查阅，**未切换工作树**）
4. 本地源码 **当前 checkout**（仅作“仓库里现在检出了什么”的记录）
5. README / Wiki / 推测

**源码主分支 / 当前 checkout 不等于服务器版本。** 与 JAR 不一致时，**一律以 JAR 为准**，匹配 ref 仅作辅助理解。

### 0.2 版本映射表（JAR vs 当前 checkout vs 应对照 ref）

| 模组 | 服务器 JAR | 当前 checkout | 当前 checkout 实际目标 | **应对照的匹配 ref** | 匹配质量 |
|---|---|---|---|---|---|
| Arc | `arc-9.0.0-neoforge.jar` → **9.0.0** / MC 1.21.x | `ArcLib` **branch `26.2`** `69daa11` | **MC 26.2 / version 21.2.7** | **`origin/1.21`** `051b008`，`mod_version=9.0.0` | **严重错位** — 禁止用 26.2 结论替代 9.0.0 |
| Jobs+ | `jobsplus-9.0.0-neoforge.jar` → **9.0.0** | `JobsPlus` **branch `26.2`** `8ebe9b7` | **MC 26.2 / version 21.2.3** | **`origin/1.21`** `0a15e53`，`mod_version=9.0.0` | **严重错位** |
| Field Guide | `fieldguide-…-1.13.4.jar` → **1.13.4** | `Field-Guide` branch `1.21.1` `602e5ff` | gradle **1.13.6** | commit **`d709491`（1.13.4）** | 分支对、**略超前** |
| Scorched Guns | `ScorchedGuns-Neoforge-1.5.jar` → **1.5** | `main` `a93b541` | `mod_version=1.5`（describe 仍写 1.2.5-11） | 当前 main + **JAR javap** | 可用；关键点以 JAR 为准 |
| Farmer's Delight | `FarmersDelight-1.21.1-1.3.2.jar` → **1.3.2** | branch `1.21` `57fd50f2` | **1.3.2** | 当前 HEAD | **对齐** |
| Kaleidoscope Cookery | `kaleidoscopecookery-1.4.1-neoforge+mc1.21.1.jar` | **branch `main`** `1d935a2c` | **Forge 1.20.1 / 1.4.1-forge** | **`origin/1.21.1-neoforge`** `8eaa0d2d`，`1.4.1-neoforge+mc1.21.1` | **严重错位** — 禁止用 1.20.1 main |
| Kaleidoscope Compat | `kaleidoscope_compat-2.9.7-…` | `1.21.1-NeoForge` `3f4115a` | gradle **2.10.0** | 当前分支略超前 | 部分可用 |
| Bountiful | `bountiful-…-8.0.0-beta.2` | `dev` `11a7cb7b` | **8.0.0-beta.1** | 接近 beta.2 | 部分可用 |
| Lightman's Currency | `lightmanscurrency-1.21-2.3.0.5` | **branch `main`** `2f882edc` | **MC 26.1 / 26.1-1.0.0.0** | **`origin/LC-1.21.1`** `888f8df2`，`1.21-2.3.0.5` | **严重错位** |
| GD656 | `gd656killicon-1.1.0.020-…` | **无本地源码** | — | **仅 JAR** | 仅 JAR |

### 0.3 源码仓库快照（当前 checkout，只读记录）

```text
ArcLib          origin=DAQEM/ArcLib.git          branch=26.2              HEAD=69daa112…  describe=20.2.10   dirty=0
JobsPlus        origin=DAQEM/JobsPlus.git        branch=26.2              HEAD=8ebe9b70…  describe=20.2.6    dirty=0
Field-Guide     origin=evanbones/Field-Guide.git branch=1.21.1            HEAD=602e5ff0…  describe=602e5ff   dirty=0
ScorchedGuns    origin=sadeast69/…               branch=main              HEAD=a93b5417…  describe=1.2.5-11… dirty=0
Kaleidoscope-C  origin=BmtUltra/…                branch=1.21.1-NeoForge   HEAD=3f4115aa…  describe=3f4115a   dirty=0
KaleidoscopeCookery origin=KaleidoscopeMods/…    branch=main              HEAD=1d935a2c…  describe=snapshot-2026-06-25… dirty=0
Bountiful       origin=ejektaflex/Bountiful.git  branch=dev               HEAD=11a7cb7b…  describe=7.0.3-…   dirty=0
LightmansCurrency origin=Lightman314/…           branch=main              HEAD=2f882edc…  describe=Build-477… dirty=0
FarmersDelight  origin=vectorwing/…              branch=1.21              HEAD=57fd50f2…  describe=57fd50f2  dirty=0
```

### 0.4 本轮实际采用的对照策略

| 模组 | 本轮 API/调用流证据来源 |
|---|---|
| **Arc 9.0.0 / Jobs+ 9.0.0** | **服务器 JAR `javap`** + `git show origin/1.21:…`；**忽略** checkout 的 26.2 源码语义 |
| **KC 1.4.1 NeoForge** | **服务器 JAR `javap`** + `git show origin/1.21.1-neoforge:…`；**忽略** checkout 的 1.20.1 Forge main |
| **LC 2.3.0.5** | **服务器 JAR** + `origin/LC-1.21.1`；**忽略** main(26.1) |
| **Field Guide 1.13.4** | **服务器 JAR** + commit `d709491`；当前 HEAD 仅作 diff 参考 |
| **SG 1.5 / FD 1.3.2** | **服务器 JAR javap** 为主；本地源码仅辅助 |
| **GD656** | **仅服务器 JAR** |

> **维护建议（非本轮修改）**：审查前应将 `源码参考` 检出到匹配 ref，或在文档中固定“只读对照 ref 列表”，避免后续审查再次读到 26.2 / 1.20.1 Forge 而误判 API。

---

## 1. 总览

### 1.1 生产代码真实反射

| 类型 | 数量 | 位置 | 评价 |
|---|---:|---|---|
| `Class.forName`（经 `ClassResolver`，默认 `Class::forName`） | **1 处设计点** | `CompatLoader.loadModule` | **合理保留**：可选依赖延迟解析 |
| `getDeclaredConstructor().newInstance()` | **1** | `CompatLoader.loadModule` | 与延迟加载配套；无 `setAccessible` |
| `getDeclaredMethod` / `getDeclaredField` / `MethodHandle` / `setAccessible` | **0** | — | 生产路径无“运行时挖私有成员” |

**结论：生产侧几乎没有“业务反射”。唯一反射链路是 `CompatLoader` 可选模块延迟加载。**

### 1.2 测试反射

| 项 | 数量 |
|---|---:|
| 含反射模式的测试文件 | **8** |
| 匹配行（`Class.forName` / `getDeclaredField` / `setAccessible` 等） | **约 22** |

测试反射用于：CompatLoader 隔离证明、预设/边界静态检查、Graal/JUnit 下无法构造真实 SG/Jobs+ 实例时的类存在性断言。
**不得把测试反射计入“服务器运行反射”**，也**不得把静态/单元测试写成实机 PASS**。

### 1.3 Mixin / Accessor

| 注解 | 数量 |
|---|---:|
| `@Mixin` 类 | **11** |
| `@Inject` | **14** |
| `@Redirect` | **1**（SG `Math.max` 弹药节省） |
| `@Accessor` | **1**（FD `usedRecipeTracker`） |
| `@Shadow` | **0** |

**Mixin 配置文件（5）：**

| 配置 | `requiredMods` / required | 内容 |
|---|---|---|
| `tcth.mixins.json` | 无（原版） | `SweetBerryBushBlockMixin` |
| `farmersdelight_compat.mixins.json` | toml: `farmersdelight`；json `required:false` | 出锅 + 番茄 + Accessor |
| `kaleidoscope_cookery_compat.mixins.json` | toml: `kaleidoscope_cookery`；json `required:false` | 锅/汤锅/蒸笼 + 作物 |
| `scguns_compat.mixins.json` | `scguns` | Niami `getArrow` |
| `scguns_ammo_compat.mixins.json` | **`scguns` + `jobsplus`** | 弹药节省 |

### 1.4 条件兼容模块

| 模块 | 加载方式 | 目标 modId |
|---|---|---|
| Jobs+ / Arc | `CompatLoader.register("jobsplus", …JobsPlusCompatModule)` | `jobsplus`（构造时再验 `arc`） |
| Field Guide | `CompatLoader.register("fieldguide", …)` | `fieldguide` |
| Scorched Guns | `CompatLoader.register("scguns", …)` | `scguns` |
| FD / KC | **无 CompatModule**；仅条件 Mixin + 适配器类 | `farmersdelight` / `kaleidoscope_cookery` |
| Bountiful / LC / GD656 | **无集成实现** | — |

`build.gradle`：上述第三方均为 **`compileOnly` / `testImplementation`**，不打入发布 JAR。`neoforge.mods.toml` 中 jobsplus/arc/fieldguide/scguns 为 `optional`。

### 1.5 总体评价

| 维度 | 评价 |
|---|---|
| 可选依赖隔离 | **良好**：CompatLoader 延迟加载 + mixin `requiredMods` + Jobs+ 内对 `scguns` 再分支 |
| 反射滥用 | **低**：业务路径几乎无反射 |
| Mixin 必要性 | FD/KC 出锅、SG Niami/弹药：公开完成事件不足，**当前 Mixin 基本合理** |
| 脆弱点 | **SG `@Redirect Math.max`**、私有 `getArrow`/`consumeAmmo` 描述符；依赖 **SG 1.5 字节码形状** |
| 源码仓库可用性 | **差（当前 checkout）**：Arc/Jobs/KC/LC **严重错位**；后续审查必须先对齐 ref |
| 实机验证 | 本轮 **NOT TESTED**；静态/单元 ≠ LIVE STARTUP / PLAYER ACTION |

**总体：架构方向正确（公开 API 优先、fail-closed、条件加载清晰）。当前最大外部风险是「参考源码 checkout 与服务器 JAR 错位」导致误审；代码内最大技术风险是 SG 弹药 `@Redirect` 的注入点脆弱性。未发现已证实的 P0 数据损坏/必崩点（静态范围）。**

---

## 2. 问题清单

严重度：

- **P0**：数据损坏、安全、必崩
- **P1**：核心功能错误、重复结算、缺依赖崩溃
- **P2**：兼容脆弱、运维/日志/测试或文档误导
- **P3**：维护性改进

### P0

**无。**（本轮静态 + JAR 对照未证实必崩或存档损坏路径。）

---

### P1

#### P1-1 服务器同时存在两份 Field Guide JAR（同 modId）

| 项 | 内容 |
|---|---|
| 位置 | `Server/mods/[自然图鉴]fieldguide-neoforge-1.21.1-1.13.4.jar` 与 `…1.13.4_副本.jar` |
| 当前行为 | MD5 **相同**（`e49f9e67…`）；NeoForge 对同 modId 双 JAR 通常导致加载冲突或不可预期 |
| 证据 | `ls Server/mods`；`md5` 一致 |
| 风险 | **LIVE STARTUP** 失败或模组加载异常；与 TCTH 代码无关，但阻断兼容验收 |
| 推荐 | 删除/移走 `_副本.jar`，只保留一份 1.13.4 |
| 是否现在处理 | **是（运维）**；非 TCTH 代码改动 |

#### P1-2（条件性）参考源码错位导致后续“按源码改 TCTH”的决策风险

| 项 | 内容 |
|---|---|
| 位置 | `源码参考/ArcLib@26.2`、`JobsPlus@26.2`、`KaleidoscopeCookery@main(1.20.1)`、`LightmansCurrency@main(26.1)` |
| 当前行为 | 人读/IDE 跳转/自动 diff 极易对 **错误 MC 代际** 的 API 下结论 |
| 证据 | 见 §0.2；`origin/1.21` 才是 Arc/Jobs **9.0.0**；KC 应对 `1.21.1-neoforge` |
| 风险 | 把 26.2 / 1.20.1 的类名、事件、注册方式“移植”到 1.21.1 服务器 → **编译或运行时崩溃** |
| 推荐 | 审查/开发前固定对照 ref；文档写明「禁止用 26.2 指导 9.0.0」 |
| 是否现在处理 | **是（流程）**；本轮不改仓库 |

> 说明：P1-2 不是 TCTH 运行时已触发的 bug，而是 **兼容工作流的高危错误源**，按用户强调的分支问题单列。

---

### P2

#### P2-1 SG 弹药节省：`@Redirect` 绑定 `Math.max(II)I`（高脆弱注入点）

| 项 | 内容 |
|---|---|
| 文件/方法 | `AmmoSaverMixin.tcth$ordinaryShotSave` → `@Redirect` `handleShoot` 内 `Ljava/lang/Math;max(II)I` |
| 当前行为 | 成功 roll 时返回 `oldCount`，阻止 `AmmoCount-1` 与空匣清理 |
| JAR/javap 证据（**SG 1.5 JAR**） | `handleShoot` 在 IgnoreAmmo/AmmoCount 之后 **唯一** 一处 `Math.max`（约 offset 485），随后 `putInt AmmoCount` / `clearLoadedProjectileItem`。`handleBeamWeapon` 另有 `Math.max`（`getBeamDamageDelay`，**非扣弹**），不在 `handleShoot` redirect 范围内 |
| 匹配源码 | SG `main` `mod_version=1.5` 与 JAR 大体一致；仍以 JAR 为准 |
| 风险 | SG 在 `handleShoot` 增加第二个 `Math.max` → Mixin 歧义（`defaultRequire:1`）→ **MIXIN LOAD / STARTUP 失败或错误改写** |
| 推荐 | 保持版本锁定 `scguns [1.5,)`；升级 SG 时强制 `javap` 复核；中长期可向上游要扣弹事件/钩子 |
| 是否现在处理 | **否（代码）**；升级 SG 前必须复核 |

#### P2-2 SG 私有方法描述符硬编码（`getArrow` / `consumeAmmo`）

| 项 | 内容 |
|---|---|
| 文件 | `NiamiArrowSpawnMixin`、`AmmoSaverMixin` |
| 当前行为 | 注入 `private static getArrow(...)` RETURN；`consumeAmmo` HEAD 可取消 |
| JAR 证据 | 两方法均存在且签名与 mixin 描述符一致（SG 1.5） |
| 风险 | 重命名/签名变更 → mixin 应用失败 |
| 推荐 | 保留；changelog 跟踪 SG；可选 `@Mixin` 附加 `require` 注释与集成冒烟 |
| 是否现在处理 | 否 |

#### P2-3 BEAM 可能对「同一次持续射击」命中两条扣弹入口（设计已知，文档需防误读）

| 项 | 内容 |
|---|---|
| 行为 | `handleShoot` 公共 `Math.max` 扣弹 + `handleBeamWeapon` 周期 `consumeAmmo`；SEMI_BEAM 不进周期 `consumeAmmo` |
| 证据 | JAR 控制流 + 现有 5B.1 报告；`AmmoSaverMixin` 注释已说明 |
| 风险 | 产品误以为“每枪只 roll 一次”→ 体感偏差；**非重复奖励结算** |
| 推荐 | 保留实现；验收文案明确「按真实扣弹入口，非按枪次」 |
| 是否现在处理 | 否 |

#### P2-4 FD / KC 条件 mixin json 为 `required: false`

| 项 | 内容 |
|---|---|
| 位置 | `farmersdelight_compat.mixins.json`、`kaleidoscope_cookery_compat.mixins.json` |
| 对比 | SG 两份为 `required: true` + `requiredMods` |
| 风险 | 目标方法漂移时可能 **静默不注入**（功能丢失）而非启动失败，难以及早发现 |
| 推荐 | 评估改为与 SG 一致的 fail-loud；或 CI 做 mixin 应用日志检查 |
| 是否现在处理 | 否（R2） |

#### P2-5 单元测试大量注入 seam，易被误读为实机 PASS

| 项 | 内容 |
|---|---|
| 位置 | `ScorchedGunsCompatModule` / `GunnerAbilityModule` / `FieldGuideCompatModule` 等 test hooks |
| 风险 | 文档/口头把 BUILD/TEST 写成 LIVE STARTUP / PLAYER ACTION |
| 推荐 | 验收清单强制标注验证层级（见 §7） |
| 是否现在处理 | 文档约定即可 |

#### P2-6 Field Guide 本地源码 1.13.6 vs 服务器 1.13.4

| 项 | 内容 |
|---|---|
| 证据 | checkout HEAD `version=1.13.6`；服务器 1.13.4；匹配 commit `d709491` |
| JAR 证据 | `PlayerFieldGuideProgress.unlock(ServerPlayer, ResourceLocation, String, boolean)` 等与 `FieldGuideApiAdapter` 一致 |
| 风险 | 用 1.13.6 新 API 改 TCTH 可能不兼容 1.13.4 |
| 推荐 | 对照 `d709491` 或服务器 JAR |
| 是否现在处理 | 否 |

#### P2-7 KC 存在公开事件，但**不能**替代出锅 Mixin

| 项 | 内容 |
|---|---|
| JAR API | `SickleHarvestEvent`、`StockpotMatchRecipeEvent`、`RecipeItemEvent` 等 |
| 缺口 | **无**“玩家成功取走成品（含最终 ItemStack）”的通用完成事件 |
| 当前 TCTH | `takeOutProduct` / `takeFood` HEAD+RETURN 快照 + 失败回滚签名 |
| 匹配源码 | `origin/1.21.1-neoforge` 中 `PotBlockEntity.takeOutProduct` 与 JAR 一致方向 |
| 风险 | 误用 1.20.1 main 的 API 文档会错；误以为可用 Sickle 事件替代取餐 |
| 推荐 | **保留 Mixin**；R4 可向上游申请 `DishTakenEvent` |
| 是否现在处理 | 否 |

#### P2-8 FD 无公开出锅事件；`usedRecipeTracker` Accessor 合理但脆弱

| 项 | 内容 |
|---|---|
| JAR | `CookingPotResultSlot.onTake`；`CookingPotBlockEntity.usedRecipeTracker` 私有字段存在 |
| 公开事件 | 无可替代的“取餐完成 + recipeId + 交付栈”事件 |
| 风险 | 字段改名 → Accessor 失败 |
| 推荐 | 保留；版本钉死 FD 1.3.2 |
| 是否现在处理 | 否 |

---

### P3

#### P3-1 参考源码目录未文档化“强制匹配 ref”

| 推荐 | 在 `源码参考/README` 或本报告旁维护一表：mod → 服务器版本 → `git checkout`/`git show` ref |
| 是否现在处理 | R5 |

#### P3-2 CompatLoader 仅 3 个模块；FD/KC 无模块描述符

| 说明 | 设计可接受（Mixin 已条件化）；若需统一开关/日志，可后续补 thin CompatModule |
| 是否现在处理 | 否 |

#### P3-3 Bountiful / LC 尚未接入（预期）

| 说明 | 见 §6 后续 API 勘察；本轮不实现 |
| 是否现在处理 | 否 |

#### P3-4 GD656 与 TCTH 枪客管线刻意独立

| JAR | `PlayerDataManager` 有 `getScore/addKill` 等，但是 **内部存档/事件逻辑**，无稳定跨模组事件总线契约 |
| 推荐 | **继续不依赖 GD656**；勿读其 playerdata |
| 是否现在处理 | 否（保持） |

---

## 3. 替换建议矩阵

| 当前实现 | 源码/JAR 发现 | 建议方案 | 收益 | 风险 |
|---|---|---|---|---|
| `CompatLoader` + `Class.forName` | 可选模块延迟加载 | **保留** | 缺依赖可启动 | 无（合理反射） |
| Jobs+ `JobsServerPlayer` / `JobPowerupManager` / `PowerupState.ACTIVE` | **JAR 9.0.0 + origin/1.21** 公开 API 齐全 | **保留公开 API 查询**；禁止读 NBT | 稳定、可测 | 勿对照 26.2 API |
| Arc `ActionType/ConditionType/RewardType.register` + `ActionDataBuilder` | **JAR 9.0.0 + origin/1.21** | **保留** 注册与数据驱动 | 数据驱动职业 | 类加载须在 jobsplus 模块内 |
| SG 击杀：`LivingDeathEvent` + 强证据 | JAR：`ProjectileEntity`/`ModDamageTypes.BULLET`/`getArrow` | **保留**；不用 `GunProjectileHitEvent` 作击杀 | 无假击杀 | 光束依赖主手快照 |
| Niami：`getArrow` Mixin | 私有方法，无公开出生事件 | **保留 Mixin** | 唯一可靠出生点 | 签名漂移 |
| 弹药：`Math.max` Redirect + `consumeAmmo` cancel | JAR 双扣弹入口 | **保留**；版本锁定 | 贴合真实扣弹 | **Redirect 脆弱** |
| 枪术/防护：`LivingDamageEvent.Pre` + `SgDamageEvidence` | Arc `DamageSourceCondition` 无法区分 Niami/爆炸来源 | **保留代码证据** | 防误伤/误判 | 须与 5A 规则同步 |
| 枪客研修：`jobsplus:on_job_exp` + `job_exp_multiplier` + `powerup_not_active` | 数据包互斥（I 排除 II/III） | **保留数据驱动** | 无代码倍率叠乘 | 数据包部署遗漏 |
| FG：`PlayerFieldGuideProgress.unlock` | **1.13.4 JAR** 公开 API | **保留**；显式 entry + gate 仍必要（目录/标签） | 无私有字段 | 对照勿用 1.13.6 新 API |
| FD 出锅 Mixin + Accessor | 无完成事件 | **保留**；R4 申请事件 | 正确 recipeId/栈 | 字段名漂移 |
| KC 取餐 Mixin | 匹配分支有 `takeOutProduct`；**无**成品取出事件 | **保留 Mixin**；勿用 Sickle 事件替代 | 失败回滚签名 | 错用 1.20.1 源码 |
| KC/FD 右键作物 Mixin | 无统一收获完成 API | **保留**；KC 镰刀可另接 `SickleHarvestEvent`（可选增强） | 覆盖右键 | 镰刀路径现状可能未进 TCTH 事件 |
| Bountiful | `BountifulSharedApi` 偏桥接/原版钩子 | 后续：读 datapack bounty 类型 + 自建完成检测；慎用内部 BE | 可做悬赏职业 | API 面窄 |
| LC | **2.3.0.5 JAR**：`MoneyAPI` / `IMoneyHandler` / `BankAPI` / `TradeEvent` | 后续金币结算用 **MoneyAPI**；对照 **LC-1.21.1** | 正式经济 API | 禁止用 main(26.1) |
| GD656 | 无公开击杀事件契约 | **不接入**；TCTH 自有 `GunKillEvent`/统计 | 解耦 | 若强行读存档则违规 |

---

## 4. 应保留内容（防误删）

下列设计经本轮复核为 **合理且应保留**：

1. **`CompatLoader` 延迟 `Class.forName` + 描述符注册**
   - 目标模组缺失时不解析实现类；单模块失败不拖垮其它模块。

2. **第三方 `compileOnly`，不进发布 JAR**
   - `build.gradle` 已约束 arc/jobsplus/fieldguide/scguns/fd/kc。

3. **Mixin `requiredMods` 条件加载**（尤其 `scguns_ammo` 同时要求 jobsplus）
   - 避免无 Jobs+ 时解析 `GunnerAbilityModule`。

4. **Jobs+ 模块内 `ModList.isLoaded("scguns")` 再注册枪客伤害监听**
   - `TCTH + Jobs+/Arc` 不解析 `SgDamageEvidence` 运行路径。

5. **Jobs+/Arc 公开 API：职业、Powerup、`PowerupState.ACTIVE`、Action/Condition/Reward 注册**
   - 以 **9.0.0 JAR / origin/1.21** 为准；**不要**用 26.2 重写。

6. **击杀强证据三路径 + FakePlayer 排除 + 无 victim 近期命中缓存**

7. **Niami 出生注册表（容量/TTL/登出清理/消费即删）**

8. **弹药节省纯逻辑拆分**（`AmmoSaverLogic` / `AmmoSaverBeamGate` / `AmmoSaverStackRead` 只读 CustomData）

9. **FD/KC 取餐 HEAD 快照 + RETURN 成功才结算 + KC 失败签名回滚**

10. **Field Guide 仅调公开 unlock API；显式 catalog/gate；事件 id 缓存防重**

11. **fail-closed 配置读取**（开关异常 → 不生效）

12. **不依赖 GD656**

---

## 5. 逐模块审查摘要

### 5.1 Scorched Guns（权威：`ScorchedGuns-Neoforge-1.5.jar`）

| 检查项 | 结论 | 验证层级 |
|---|---|---|
| 普通射击扣弹 | `handleShoot` 公共 `Math.max` 块；Redirect 目标与 JAR 一致 | STRUCTURAL + javap |
| BEAM / SEMI_BEAM | BEAM 另有周期 `consumeAmmo`；SEMI_BEAM 无周期 `consumeAmmo`；两者仍走公共扣弹 | javap |
| Niami | `getArrow` 私有；Mixin RETURN 注册；击杀用 `Arrow` + 注册表 | STRUCTURAL |
| 伤害来源 | 弹丸 `ProjectileEntity`；光束 `scguns:bullet` + 主手 BEAM/SEMI_BEAM；爆炸体亦为弹丸子类 | STRUCTURAL |
| 击杀归因 | 仅 `LivingDeathEvent`；无 hit 缓存 | STRUCTURAL |
| 枪术/防护/弹药 | `LivingDamageEvent.Pre` + Mixin；最高档不叠加 | STRUCTURAL + 单测 |
| FakePlayer/炮塔 | 击杀与证据均排除 FakePlayer | STRUCTURAL |
| 双 Mixin 配置 | `scguns` / `scguns+jobsplus` | STRUCTURAL |
| 实机射击/扣弹体感 | **NOT TESTED** | — |

### 5.2 Jobs+ / Arc（权威：`*-9.0.0-neoforge.jar` + **`origin/1.21`**，**非 26.2**）

| 检查项 | 结论 |
|---|---|
| 职业/能力查询 | `JobsPlayer.jobsplus$getJob`、`Job.getPowerupManager`、`getPowerup` → `PowerupState.ACTIVE`（JAR 确认） |
| Action/Condition/Reward | `TcthArcRegistrar` 使用 9.0.0 风格 `ActionType.register` 等；与 origin/1.21 一致方向 |
| 经验倍率互斥 | 枪客研修 JSON：`job_exp_multiplier` + `powerup_not_active` 排除更高档（已部署 `global_packs/.../tcth-gunner`） |
| fail-closed | 查询异常 → `NONE`；条件/开关失败不生效 |
| 缺 Arc | Jobs+ 模块检测后禁用奖励并打日志 |
| 缺 SG | 不 `init` `GunnerAbilityModule` |
| **禁止** | 用 **26.2** 的 version 21.x API 评估当前实现 |

### 5.3 Field Guide（权威：1.13.4 JAR；匹配 commit `d709491`）

| 检查项 | 结论 |
|---|---|
| 解锁 API | `unlock` / `isUnlocked` / `hasEntry` / `getProgress` 公开且被适配器使用 |
| 默认拿取解锁 | FG 自有 `checkDefaultUnlocks`；TCTH 额外做厨师图鉴 **显式 entry + catalog gate** 仍有必要（业务目录 ≠ FG 默认规则） |
| 出锅解锁 | 监听 TCTH `DishCookedEvent`，非 FG 内部私有 |

### 5.4 Farmer's Delight / Kaleidoscope Cookery

| 检查项 | FD 1.3.2 | KC 1.4.1 NeoForge（**非 1.20.1 main**） |
|---|---|---|
| 完成点 | `CookingPotResultSlot.onTake` | `takeOutProduct` / `takeFood` |
| 交付栈 | onTake 的 `ItemStack` + 署名 | HEAD `getResult()` 副本；参数 `ItemStack` 是锅铲非菜 |
| recipeId | Accessor `usedRecipeTracker` 单条目 | 适配器侧 recipe/品质 |
| 失败回滚 | recipe 快照 finally 清空 | 签名恢复 |
| 公开事件替代 | **无**合适出锅事件 | **无**取餐完成事件；镰刀事件不可替代 |
| 作物右键 | `TomatoBlock.useWithoutItem` | `useItemOn` Mixin |

### 5.5 Bountiful / Lightman's Currency（只审查不实现）

| 模组 | 正式 API/事件（JAR） | 后续接入建议 |
|---|---|---|
| Bountiful 8.0.0-beta.2 | `BountifulSharedApi`、bounty 类型注册、board BE；**缺少**清晰的“职业订单完成”公共事件 | 以 datapack 目标 + 自有检测为主；慎挖内部 |
| LC 1.21-2.3.0.5 | **`api.money.*`、`MoneyAPI`、`IMoneyHandler`、`BankAPI`、`TradeEvent` 等** | 金币结算优先 MoneyAPI；源码只用 **`LC-1.21.1`** |

### 5.6 GD656（仅 JAR）

- 有服务端 `PlayerDataManager` 分数/击杀读写与内部 `ServerEventLogic`。
- **无**面向第三方的稳定“击杀显示/积分”事件 API 文档契约。
- TCTH **不应**读取/修改其私有存档；当前独立设计正确。

---

## 6. 可选依赖矩阵

| 环境 | 期望 | 静态结构结论 | 实机 |
|---|---|---|---|
| 仅 TCTH | 正常启动 | **STRUCTURAL PASS**（无 optional 解析） | NOT TESTED |
| TCTH + SG | 仅 SG 基础兼容（击杀事件/统计） | **STRUCTURAL PASS**（scguns 模块 + Niami mixin；无 ammo mixin） | NOT TESTED |
| TCTH + Jobs+/Arc | 不解析 SG 类型 | **STRUCTURAL PASS**（无 scguns → 不 init GunnerAbility；ammo mixin 不注册） | NOT TESTED |
| TCTH + SG + Jobs+/Arc | 枪客完整 | **STRUCTURAL PASS** | NOT TESTED |
| TCTH + Field Guide | 仅图鉴兼容 | **STRUCTURAL PASS** | NOT TESTED |
| TCTH + FD | 仅 FD 兼容 | **STRUCTURAL PASS** | NOT TESTED |
| TCTH + KC | 仅 KC 兼容 | **STRUCTURAL PASS** | NOT TESTED |
| 全部安装 | 全模块 | **STRUCTURAL PASS**（注意 P1-1 双 FG JAR） | NOT TESTED |

**严格区分：**

| 层级 | 本轮 |
|---|---|
| STRUCTURAL PASS | 有（类边界、toml、mixin requiredMods、import 隔离） |
| BUILD PASS | **NOT TESTED**（本轮未跑 Gradle） |
| MIXIN LOAD PASS | **NOT TESTED**（未起服务端看 mixin 应用） |
| LIVE STARTUP PASS | **NOT TESTED** |
| PLAYER ACTION PASS | **NOT TESTED** |

---

## 7. 后续阶段建议

### R1：P0/P1 处理

| 范围 | 验收 |
|---|---|
| 移除重复 Field Guide JAR | 启动日志仅一个 `fieldguide`；**LIVE STARTUP PASS** |
| 固定 `源码参考` 对照 ref（文档或脚本，**可选** checkout） | 审查清单写明 Arc/Jobs=`origin/1.21`，KC=`1.21.1-neoforge`，LC=`LC-1.21.1`，FG=`d709491` |
| 文件 | 仅 `Server/mods` 运维；可选 `源码参考` 说明文档 |

### R2：高风险实现加固 / 可替换项

| 范围 | 验收 |
|---|---|
| SG 升级前 `javap` 回归脚本（`handleShoot` Math.max 计数、`getArrow`/`consumeAmmo` 签名） | 升级门禁 |
| 评估 FD/KC mixin `required:true` + 失败即可见 | MIXIN LOAD 可观测 |
| 文件 | `AmmoSaverMixin`、mixin json、CI 脚本 |

### R3：依赖组合测试

| 矩阵 | 每组合至少 |
|---|---|
| 仅 TCTH / +SG / +Jobs+Arc / +三者 / +FG / +FD / +KC / 全量 | LIVE STARTUP + 关键 PLAYER ACTION 冒烟 |
| 禁止 | 用单元测试勾选 LIVE PASS |

### R4：向上游 API

| 目标 | 诉求 |
|---|---|
| Scorched Guns | 扣弹前后事件；弹/箭出生事件（含 weapon 快照） |
| Farmer's Delight | 烹饪锅 take 完成事件（player, stack, recipeId） |
| Kaleidoscope Cookery | 取餐成功事件（player, stack, device, quality） |
| Bountiful | 悬赏完成公共事件 |

### R5：文档维护

| 项 |
|---|
| 本报告与 phase-5x 报告交叉链接 |
| 源码分支错位警示置顶 |
| 验收层级术语统一（STRUCTURAL / BUILD / MIXIN / LIVE / PLAYER） |

---

## 8. 附录：本轮关键命令与证据索引

```bash
# 仓库状态（当前 checkout — 不等于服务器版本）
git -C "源码参考/ArcLib" branch --show-current   # 26.2  ← 错位
git -C "源码参考/JobsPlus" branch --show-current # 26.2  ← 错位
git -C "源码参考/KaleidoscopeCookery" branch --show-current # main(1.20.1) ← 错位

# 匹配 ref（只读，未切换工作树）
git -C "源码参考/ArcLib" show origin/1.21:gradle.properties          # mod_version=9.0.0
git -C "源码参考/JobsPlus" show origin/1.21:gradle.properties        # mod_version=9.0.0
git -C "源码参考/KaleidoscopeCookery" show origin/1.21.1-neoforge:gradle.properties
git -C "源码参考/LightmansCurrency" show origin/LC-1.21.1:gradle.properties
git -C "源码参考/Field-Guide" show d709491:gradle.properties         # 1.13.4

# JAR 权威
javap -p -c -classpath "Server/mods/[灼热枪械]ScorchedGuns-Neoforge-1.5.jar" \
  top.ribs.scguns.common.network.ServerPlayHandler
javap -p -classpath "Server/mods/[职业+]jobsplus-9.0.0-neoforge.jar" \
  com.daqem.jobsplus.player.job.powerup.PowerupState
```

---

## 9. 结束语

本轮为 **只读兼容审查**。核心发现：

1. **参考源码当前 checkout 与服务器 JAR 严重错位**（Arc/Jobs → 26.2；KC → 1.20.1 Forge；LC → 26.1 main）。所有行为结论以 **JAR + 匹配 ref** 为准。
2. TCTH 生产路径 **几乎无业务反射**；`CompatLoader` 延迟加载应保留。
3. Mixin 主要覆盖 **无公开完成事件** 的出锅/Niami/扣弹；Jobs+/Arc/FG 已走公开 API。
4. 最大代码脆弱点：**SG `Math.max` @Redirect**。
5. 运维：**重复 Field Guide JAR** 应处理后再做 LIVE 验收。
6. 本轮 **未** BUILD / 起服 / 玩家操作验证。

**报告完成，停止修改；等待确认后再进入 R1 修复。**
