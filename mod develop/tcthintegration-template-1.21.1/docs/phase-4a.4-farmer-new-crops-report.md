# 阶段 4A.4 交付报告：tcth:farmer 新增作物兼容

日期：2026-08-07
范围：Neapolitan 6.0.1、Dungeons Delight 1.5.0、My Nether’s Delight 1.10.4
约束：保持 Kaleidoscope Compat `datapack_mode = UNITE`；不改农夫 XP；不发金币；不改厨师/枪客；不 commit/push。

---

## 1. 服务器 JAR 版本（唯一权威）

| 模组 | 服务器文件 | modId / version |
|---|---|---|
| Neapolitan | `[那不勒斯风味]neapolitan-1.21.1-6.0.1.jar` | `neapolitan` **6.0.1** |
| Dungeons Delight | `[农夫乐事-地牢乐事]neoforge-dungeonsdelight-1.21.1-1.5.0.jar` | `dungeonsdelight` **1.5.0** |
| My Nether’s Delight | `[农夫乐事-下界乐事]MyNethersDelight-1.21.1-1.10.4.jar` | `mynethersdelight` **1.10.4** |
| Kaleidoscope Compat | `[森罗物语-兼容]kaleidoscope_compat-2.9.7-neoforge+mc1.21.1.jar` | `kaleidoscope_compat` **2.9.7** |
| Farmer’s Delight | `FarmersDelight-1.21.1-1.3.2.jar` | `farmersdelight` **1.3.2**（菌落基类） |

服务器 JAR SHA-256：

| JAR | SHA-256 |
|---|---|
| neapolitan-1.21.1-6.0.1 | `82e104afc49887134fb0f74e6b3d3bc827fe1f88ea3d14836c2701218a1071d9` |
| dungeonsdelight-1.5.0 | `90342848adb6b98569a53a1d34fabbd7042f80ee530b23b7e378e329c5a1c5cd` |
| MyNethersDelight-1.10.4 | `fe246d086dcb87c7d2163fbbff09fd7e164f206238e52700fe0b3e34fb343432` |
| kaleidoscope_compat-2.9.7 | `a9cb2a9176c42bd15ad29b283eecdd408889f7faa0be8651544d6ad2e8cd3974` |

---

## 2–4. 源码 ref / 为何不能直接用当前检出 / JAR 优先

| 模组 | 服务器 JAR | 源码 ref（只读 `git show`） | 本地当前检出为何不能用 | 匹配状态 |
|---|---|---|---|---|
| Neapolitan | 6.0.1 | `521ab1eabac9f10af52f99204ef8b9f7154f1dc4`（`mod_version=6.0.1`） | 当前 `1.21.x` HEAD 为 **6.1.0** | **匹配 6.0.1**（以指定 commit + JAR javap 为准） |
| Dungeons Delight | 1.5.0 | `e1a994d53fd0622c6ca72fefa46b996a46d1a851` / `origin/1.21.1-v1.5`（`mod_version=1.5.0`） | 本地已在该 commit | **匹配** |
| My Nether’s Delight | 1.10.4 | `c98e86b6bf336810e165aea21eb839c0130ce475` / `origin/Neo-1.21+`（MC 1.21.1, mod 1.10.4） | 本地检出 **1.20.1** 分支 | **匹配**（只读 show 该 commit；未 checkout） |
| Kaleidoscope Compat | 2.9.7 | 本地源码 **2.10.0** | 无精确 2.9.7 源码树 | **源码不匹配** → 仅以服务器 JAR + 配置为据 |

**未对任何源码参考仓库执行 checkout / switch / pull / reset / 写文件。**
**JAR 与源码冲突时一律以服务器 JAR 字节码为准。**

---

## 5. 作物支持矩阵

| cropId（世界方块 ID） | 类 | 方式 | 成熟/成功条件 | 收获后 | 备注 |
|---|---|---|---|---|---|
| `neapolitan:strawberry_bush` | `StrawberryBushBlock` extends BushBlock | RIGHT_CLICK + BREAK | AGE max=6 | 右键 AGE→1 | 红/白同一方块（WHITE）；仅一套检测 |
| `neapolitan:mint` | `MintBlock` extends BushBlock | RIGHT_CLICK + BREAK | AGE max=4 | 右键 AGE→1 | — |
| `neapolitan:adzuki_sprouts` | `AdzukiSproutsBlock` extends BushBlock | BREAK only | AGE max=6 | 破坏 | 无右键收获路径 |
| `dungeonsdelight:rotbulb_crop` | `RotbulbCropBlock` extends PitcherCropBlock | BREAK | AGE max=4 | 破坏 | 双格；位置规范化到下半格；怪物生成副作用不另发事件 |
| `mynethersdelight:powdery_cane` | `PowderyCaneBlock` | RIGHT_CLICK（专项） | age>1 且 LIT，厨刀/剪刀 | LIT=false, AGE=0, PRESSURE=0 | **非** max-age 通用规则；空手爆炸=0 |
| `mynethersdelight:powdery_cannon` | `PowderyCannonBlock` | RIGHT_CLICK（专项） | LIT + 厨刀/剪刀 | LIT=false | AGE 非成熟判据；不进 harvestables/vertical |
| `mynethersdelight:warped_fungus_colony` | FD `MushroomColonyBlock` | RIGHT_CLICK（标签过滤） | COLONY_AGE>0 + 剪/刀 | 剪 AGE−1 / 刀 AGE→0 | tag `tcth:farmer_colony_harvestables` |
| `mynethersdelight:crimson_fungus_colony` | 同上 | 同上 | 同上 | 同上 | 同上 |

### 明确排除（放置—破坏刷经验 / 无可靠成熟）

| 排除 cropId | 原因 |
|---|---|
| `neapolitan:vanilla_vine`, `vanilla_vine_plant` | 无可靠自然成熟 vs 放置区分 |
| `neapolitan:banana_bundle`, `banana_frond`, `banana_stalk` | 可放置；放置—破坏风险 |
| `neapolitan:beanstalk`, `beanstalk_thorns`, `magic_beans` | 结构/可放置；无可靠收获语义 |
| `dungeonsdelight:rotten_crop`, `rotten_potatoes`, `rotten_tomatoes` | 腐烂态，非成熟收获 |
| `dungeonsdelight:rotbulb_plant` | 植物体，非 crop 成熟收获 |
| `dungeonsdelight:wormroot_stalk`, `wormroot_tendrils`, `wormroots_block` | 无可靠成熟 |
| `dungeonsdelight:rotgourd`, `carved_rotgourd` | 可放置果实类 |
| `mynethersdelight:powdery_chubby_sapling` | sapling（非收获） |
| `mynethersdelight:bullet_pepper` | 可放置花/顶部块 |
| `mynethersdelight:powdery_cane`, `powdery_cannon`（BREAK 路径） | 仅专项右键工具采收；排除通用 BREAK 刷经验 |

标签：`docs/presets/tcth-farmer/data/tcth/tags/block/`
- `farmer_harvestables`：草莓/薄荷/红豆芽/rotbulb（`required:false`）
- `farmer_colony_harvestables`：两种 MND 菌落
- `farmer_excluded`：上表排除项 + 既有项

---

## 6. 右键方法 javap 签名与控制流（服务器 JAR）

### Neapolitan `StrawberryBushBlock` / `MintBlock`

```
protected InteractionResult useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)
```

- 成熟：`setBlock(AGE, 1)` → `InteractionResult.sidedSuccess(level.isClientSide)`
- 服务端成功 = **CONSUME**（不是仅 SUCCESS）
- 未成熟：`super.useWithoutItem` → PASS
- Mixin：`RETURN-only` → `HarvestInteractionMixinSupport.handleReturn`（严格 max-age + 年龄下降）

### My Nether’s Delight `PowderyCaneBlock`

```
protected ItemInteractionResult useItemOn(ItemStack, BlockState, Level, BlockPos, Player, InteractionHand, BlockHitResult)
```

- 条件：`age > 1 && lit` 且 `ItemUtils.isKnife || TOOLS_SHEAR`
- 成功：`setBlock(lit=false, age=0, pressure=0)` → `ItemInteractionResult.sidedSuccess(...)` → 服务端 **CONSUME**
- 空手/错误工具：走爆炸或 PASS，不发事件

### My Nether’s Delight `PowderyCannonBlock`

```
protected ItemInteractionResult useItemOn(...)
```

- 条件：`lit` 且厨刀/剪刀
- 成功：`lit=false` → sidedSuccess → 服务端 **CONSUME**
- `useWithoutItem` 空手爆炸路径：不结算

### FD `MushroomColonyBlock`（菌落）

```
public ItemInteractionResult useItemOn(...)
```

- `COLONY_AGE > 0`；剪刀 AGE−1；厨刀 AGE→0
- 返回 sidedSuccess → 服务端 **CONSUME**
- TCTH 用 `tcth:farmer_colony_harvestables` 过滤，不接管 FD 自有菌落

成功判定统一：`SUCCESS || CONSUME`（吸取甜浆果旧 bug 教训）。

---

## 7. BREAK / RIGHT_CLICK / 排除划分

| 路径 | 入口 | 幂等 |
|---|---|---|
| BREAK | `CropBreakDetector` @ LOWEST | Dispatcher：UUID+维度+**规范化 pos**+tick+BREAK |
| RIGHT_CLICK | 各 Mixin RETURN-only | Dispatcher：method=RIGHT_CLICK |
| 排除 | `farmer_excluded` + fail-closed 规则 | 0 事件 |

双格 Rotbulb：`CropBreakDetector.normalizeDoublePlantPosition` 将 `half=upper` 规范到下半格，避免上下两格坐标不同导致双发（不单靠 position 幂等“碰巧”）。

---

## 8. UNITE 模式边界

| 层 | 行为 |
|---|---|
| 作物方块检测 | `cropId` **始终**取世界 `Block` 注册 ID（`BuiltInRegistries.BLOCK.getKey`）；不用掉落物/统一物品 ID |
| 统一物品/配方 | Kaleidoscope Compat UNITE 可能统一 FD 部分物品；**不改变**三模组作物方块身份 |
| Arc 职业经验 | 仅 `tcth:on_crop_harvested` → 1–2 XP；`automated=false`；无 `arc:on_harvest_crop` |

- **未修改** `Server/config/kaleidoscope_compat.jsonc`（仍为 `UNITE`）
- 烟雾启动自动加载 `mod/kaleidoscope_compat:packs/unite_bakeries`
- 同一次收获：一个 eventId + 一次 Arc Action（Dispatcher + FarmerRewardModule 双重幂等）

---

## 9. 防重复 / FakePlayer / 放置刷经验

- FakePlayer：`instanceof FakePlayer` 在 Dispatcher / Break / 右键 support 全路径拒绝
- 幂等：eventId 成功发送后才提交；同 tick 同位置同 method 去重
- 双格规范化防双发
- 排除可放置无成熟语义方块
- 粉末作物不进通用 max-age harvestables；菌落仅 tag 白名单
- 空手爆炸/错误工具：无成功返回或证据矩阵失败 → 0

---

## 10. 测试 XML 实际汇总

```
suites=78 tests=657 failures=0 errors=0 skipped=0
```

（`build/test-results/test/TEST-*.xml` 机器汇总，非手工相加。4A.4 初版为 655；4A.4.1 增 2 条版本范围断言。）

新增/扩展覆盖：`Phase4a4NewCropCompatTest`、`FarmingMixinConfigTest`、`FarmerPresetTest` 标签与隔离断言；保留全部既有农夫/厨师/枪客测试。

---

## 11. 发布 JAR

| 项 | 值 |
|---|---|
| 路径 | `build/libs/tcth-0.2.1.jar`（4A.4 已部署服务器；4A.4.1 产物见 §16.5） |
| 大小（4A.4.1） | **291125** bytes |
| SHA-256（4A.4.1） | `ad8ee8d3df0a8712c9ecc207c46452342f1d9ddf1f6ace572dd88df9f60473b0` |
| 第三方 class | **无**（无 teamabnormals/soytutta/yirmiri/vectorwing 包） |
| 嵌套 JAR | **无** |
| Mixin configs | `tcth` / `farmersdelight_compat`（+MushroomColony）/ `neapolitan_farming_compat` / `mynethersdelight_farming_compat` / … |
| requiredMods | neapolitan / mynethersdelight / farmersdelight 等正确门控 |
| 预设数据包 | **不在主 JAR**（仍为 world datapack） |

---

## 12. 四层验证状态

| 层 | 状态 |
|---|---|
| 静态测试 | **PASS** 655/0/0/0 |
| Mixin 加载 | **PASS** debug.log：Strawberry/Mint/PowderyCane/PowderyCannon/MushroomColony 全部 Mixing 成功；无 InvalidInjection / MixinApplyError / TCTH NCDFE |
| 服务器启动 | **PASS** Done (4.339s)/(4.802s)；三模组识别；UNITE 保持；无 TCTH ERROR/WARN；`stop` → **All dimensions are saved** |
| 玩家实测 | **LIVE NOT TESTED**（无在线玩家；单元测试不替代） |

烟雾日志：`Server/smoke4a4.out`、`Server/smoke4a4b.out`；Mixin 证据：`Server/logs/debug.log`。

---

## 13. DEFERRED

| 项 | 原因 |
|---|---|
| 全部在线正例/负例（§十一清单） | 无在线玩家；标 **LIVE NOT TESTED** |
| Kaleidoscope Compat 2.9.7 精确源码 diff | 本地仅 2.10.0；不阻塞（本阶段不依赖其 Java API） |
| 错误工具在线路径 | 见 §16.3：JAR 字节码证明；**非**单测直接传工具栈 |

### CI 可复现性（4A.4.1 已闭合）

见下方 **§16 阶段 4A.4.1 最终交付修正**。原 §13「CI 缺口」已由固定 Modrinth CDN URL + 服务器 SHA-256 校验解决。

---

## 14. 回滚步骤

```bash
# 从备份恢复
cp backup-4a4-farmer-crops-20260807-164624/tcth-0.2.1.jar.pre-4a4 Server/mods/tcth-0.2.1.jar
rm -rf Server/world/datapacks/tcth-farmer
cp -a backup-4a4-farmer-crops-20260807-164624/tcth-farmer Server/world/datapacks/tcth-farmer
# 确认 kaleidoscope_compat.jsonc 仍为 UNITE（本阶段未改）
# 重启服务器
```

---

## 15. 精确 Git 暂存清单（建议，**未执行** add/commit）

**应纳入本阶段 + 4A.4.1（TCTH 工程）：**

```
mod develop/tcthintegration-template-1.21.1/build.gradle
mod develop/tcthintegration-template-1.21.1/.github/workflows/build.yml
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/detector/farming/CropBreakDetector.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/detector/farming/CropHarvestRules.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/impl/detector/farming/HarvestInteractionMixinSupport.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/mixin/neapolitan/StrawberryBushBlockMixin.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/mixin/neapolitan/MintBlockMixin.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/mixin/mynethersdelight/PowderyCaneBlockMixin.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/mixin/mynethersdelight/PowderyCannonBlockMixin.java
mod develop/tcthintegration-template-1.21.1/src/main/java/com/tanrunn/tcth/mixin/farmersdelight/MushroomColonyBlockMixin.java
mod develop/tcthintegration-template-1.21.1/src/main/resources/neapolitan_farming_compat.mixins.json
mod develop/tcthintegration-template-1.21.1/src/main/resources/mynethersdelight_farming_compat.mixins.json
mod develop/tcthintegration-template-1.21.1/src/main/resources/farmersdelight_compat.mixins.json
mod develop/tcthintegration-template-1.21.1/src/main/templates/META-INF/neoforge.mods.toml
mod develop/tcthintegration-template-1.21.1/docs/presets/tcth-farmer/data/tcth/tags/block/farmer_harvestables.json
mod develop/tcthintegration-template-1.21.1/docs/presets/tcth-farmer/data/tcth/tags/block/farmer_excluded.json
mod develop/tcthintegration-template-1.21.1/docs/presets/tcth-farmer/data/tcth/tags/block/farmer_colony_harvestables.json
mod develop/tcthintegration-template-1.21.1/docs/phase-4a.4-farmer-new-crops-report.md
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/detector/farming/Phase4a4NewCropCompatTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/compat/FarmingMixinConfigTest.java
mod develop/tcthintegration-template-1.21.1/src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/FarmerPresetTest.java
```

**不要：** `git add -A`；不要提交 `dev-mods/`、备份目录、`Server/playerdata`、源码参考仓库、`kaleidoscope_compat.jsonc`。

---

## 实现要点摘要

1. **复用**既有 `CropHarvestedEvent` 全家桶；无第二套事件/职业/XP。
2. 可选依赖：`compileOnly` 精确版本；mixin `requiredMods`；公共 API **零**第三方 import。
3. DD **无** mixin（标签 + PitcherCrop 双格规范化即可）。
4. `farmerRewardsEnabled` 部署前后均为 **true**（未改数值、未关闭）。
5. 等待复审；在线清单需玩家上线后执行 `/tcth debug farming on` 实测。

---

## 16. 阶段 4A.4.1 最终交付修正

日期：2026-08-07。**不改农业业务逻辑**；不重复在线玩家验收；不改 UNITE / playerdata / 奖励数值 / 服务器配置。

### 16.1 可复现构建（CI workflow）

更新 `.github/workflows/build.yml`，为下列 JAR 增加**固定** Modrinth CDN URL + **服务器实测 SHA-256**（`sha256sum -c`），禁止 latest、禁止不校验：

| flatDir 名 | 固定 URL（version id） | 服务器 SHA-256 |
|---|---|---|
| `neapolitan-6.0.1.jar` | `cdn.modrinth.com/.../InYMuiQt/versions/RQ5qgaUC/neapolitan-1.21.1-6.0.1.jar` | `82e104af…071d9` |
| `dungeonsdelight-1.5.0.jar` | `…/qPfNr476/versions/aFluEQDH/neoforge-dungeonsdelight-1.21.1-1.5.0.jar` | `90342848…1c5cd` |
| `mynethersdelight-1.10.4.jar` | `…/O53VhQoZ/versions/OdH19ieD/MyNethersDelight-1.21.1-1.10.4.jar` | `fe246d08…43432` |
| `scguns-1.5.jar`（此前 build.gradle 有、workflow 缺） | `…/GwtIopV4/versions/8pMPZhvI/ScorchedGuns-1.5.jar` | `6a4237c5…c2001` |

既有 pin 保持不变：farmersdelight / kaleidoscopecookery / arc / jobsplus / fieldguide。

**空 dev-mods 模拟**：`rm -rf dev-mods && mkdir dev-mods` 后按 workflow 全量 curl + sha256 校验全部 **OK**，再 `./gradlew clean build --no-daemon --rerun-tasks` **BUILD SUCCESSFUL** —— 证明不依赖本机残留。

不提交第三方 JAR；`dev-mods/` 仍在 `.gitignore`。

### 16.2 可选依赖版本范围收紧

`neoforge.mods.toml`：

| modId | versionRange（4A.4.1） |
|---|---|
| `neapolitan` | `[6.0.1,6.1.0)` |
| `dungeonsdelight` | `[1.5.0,1.6.0)` |
| `mynethersdelight` | `[1.10.4,1.11.0)` |

测试：`optionalDependencyVersionRangesArePinnedToValidatedMajors`、`phase4a41OptionalCropVersionRangesAreBounded`。

### 16.3 测试与报告措辞修正

| 修正 | 说明 |
|---|---|
| `colonyTagDeniedAgeZeroNoAgeDropOrFailedReturnIsZero` | 原名 `colonyAgeZeroOrWrongToolOrNoTagIsZero` 误导；本单测覆盖：tag 拒绝 / AGE=0 / 年龄不下降 / `interactionSucceeded=false` |
| **错误工具** | **非**单测传入厨刀/剪刀失败栈；由 FD / MND **JAR 字节码**在返回 SUCCESS/CONSUME 前拒绝错误工具；玩家实测仍为 **LIVE NOT TESTED** |
| `optionalModJarsHaveExpectedVersions` | 解析 JAR 内 `META-INF/neoforge.mods.toml`（或 `mods.toml`），断言真实 `modId` + `version`，不只查文件名 |

### 16.4 文档清理

`CropHarvestRules` javadoc 规则列表删除重复的 `CropBlock` 条目（原先第 3 与第 6 项重复）。

### 16.5 4A.4.1 重新验证

| 项 | 值 |
|---|---|
| 命令 | `./gradlew clean build --no-daemon --rerun-tasks` |
| 测试 XML | **suites=78 tests=657 failures=0 errors=0 skipped=0** |
| JAR 路径 | `build/libs/tcth-0.2.1.jar` |
| 大小 | **291125** B |
| SHA-256 | `ad8ee8d3df0a8712c9ecc207c46452342f1d9ddf1f6ace572dd88df9f60473b0` |
| 第三方 class | **无** |
| 嵌套 JAR | **无** |
| 服务器配置 / UNITE / playerdata / XP | **未修改** |
| 在线验收 | **未重复**（仍 LIVE NOT TESTED） |

（相对 4A.4 构建，JAR 仅因 `versionRange` 元数据字符串变化而微变；业务 class 行为不变。）

### 16.6 4A.4.1 未执行项

- 不 commit / push
- 不重新部署服务器 JAR（本轮为交付修正；业务逻辑无变；需要时可再部署 §16.5 的 SHA）
- 不在线实测错误工具 / 正例负例

---

**状态：4A.4.1 完成，等待复审。**

---

## 17. 0.2.2 发布与服务器部署

日期：2026-08-07。

### 17.1 版本与构建

- `gradle.properties`：`mod_version=0.2.2`。
- `GunnerDependencyMatrixTest` 不再硬编码旧版 JAR 文件名，改为读取 `gradle.properties` 中的当前版本，避免后续正常升级导致测试误报。
- 构建命令：`./gradlew clean build --no-daemon --rerun-tasks`。
- 测试 XML：**suites=78 tests=657 failures=0 errors=0 skipped=0**。
- 发布 JAR：`build/libs/tcth-0.2.2.jar`，**291125 B**。
- SHA-256：`ad29f66d590ed713decfe9f11be42d21efb6277322cb505f799e60a70385c237`。
- JAR 内 `neoforge.mods.toml` 确认为 `modId=tcth`、`version=0.2.2`，三个新增作物模组的可选依赖版本范围保持 §16.2 的已验证值。
- 发布 JAR 不含第三方 class 或嵌套 JAR。

### 17.2 部署与回滚

- 部署前服务器处于停服状态。
- 旧版 `Server/mods/tcth-0.2.1.jar` 已移动到 `backup-tcth-0.2.2-predeploy-20260807/`，未删除。
- 新版已部署为 `Server/mods/tcth-0.2.2.jar`，部署文件 SHA-256 与构建产物一致。
- `tcth-farmer` 源预设与服务器已部署数据包逐文件一致，因此未重复覆盖数据包。

### 17.3 无玩家烟雾测试

- 服务器识别 `TCTH Integration 0.2.2 (tcth)`，启动至 `Done (4.640s)`。
- 新增的 Neapolitan、My Nether's Delight 与 Farmer's Delight 菌落 Mixin 均完成应用；无 `InvalidInjectionException`、`MixinApplyError` 或 TCTH ERROR/WARN。
- AutoModpack 生成内容已识别 `/mods/tcth-0.2.2.jar`。
- 控制台执行 `stop` 后完成世界与全部维度保存，日志出现 `All dimensions are saved`，服务端进程已退出。
- 本次服务器运行时为 Java 25.0.3；项目构建 toolchain 仍为 Java 21。该运行环境可正常启动，但正式环境建议继续统一到 Java 21。
- 其他模组既有的配方、标签和可选类警告仍存在，不属于本次 TCTH 改动。

### 17.4 验证边界

本次完成的是构建、部署与无玩家烟雾测试；4A.4 新增作物的在线玩家收获验收仍为 **LIVE NOT TESTED**，不得将 Mixin 加载成功等同于实际收获经验通过。

**状态：TCTH 0.2.2 已构建并部署，服务器正常保存停服；源码等待提交。**
