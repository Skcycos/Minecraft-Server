# 阶段 3B 交付报告：Field Guide 厨师菜谱图鉴联动

日期：2026-08-05
项目：`mod develop/tcthintegration-template-1.21.1`
目标：`Server/mods/[自然图鉴]fieldguide-neoforge-1.21.1-1.13.4.jar`（Field Guide 1.13.4）

## 0. 工作区保护审计（开始前记录）

- `git status --short`：仅用户既有更改（`.gitignore`、`.reasonix/*`、`Server/server.properties`、`Server/config/c2me.toml`）+ 未跟踪文件（fieldguide 副本 JAR、backup-jobsplus 等）
- 分支：`main`；HEAD：`b25da980725496ba3bc901748fec960d0a6614bc`
- 构建前 TCTH JAR：`015966017360031662ed200305fcf835914a6efc18c5da0acbdfd933f8fe2549`（91179 B，已备份至 `tmp/tcth-0.1.0.jar.pre-fieldguide`）
- 服务器：启动前未运行；未执行 `git reset --hard` / `git checkout --` / `git add -A` / 自动 commit；未修改世界/经济/悬赏/JobsData；未删除任何 Field Guide 玩家数据；未修改第三方 JAR

### 两个 Field Guide JAR 的 SHA-256

- `[自然图鉴]fieldguide-neoforge-1.21.1-1.13.4.jar`：`a5ca90728ac3c210b6a27b4451c1ab75df62650da32545d034073ead04e4a531`
- `[自然图鉴]fieldguide-neoforge-1.21.1-1.13.4_副本.jar`：`a5ca90728ac3c210b6a27b4451c1ab75df62650da32545d034073ead04e4a531`

**结论：两者完全相同**（副本内容一致）。本阶段未删除任何一个。

## 1. 静态代码/JSON 测试 ✅ 已通过

`./gradlew clean build --no-daemon` 内嵌测试，XML 实际汇总：

```
tests=170  failures=0  errors=0  skipped=0
```

新增测试（全部通过），覆盖任务清单 1–22 项：

| 测试 | 覆盖 |
|---|---|
| `FieldGuideCompatDescriptorTest`（3） | Field Guide 缺失时 resolver 调用 0；init 幂等；描述符可实例化 |
| `FieldGuideCompatModuleTest`（15） | 玩家出锅解锁；player=null / automated / 非料理 / 非 catalog / 空结果不解锁；重复 eventId 一次处理；已解锁不重复通知；异常不占 eventId 可重试；单异常不影响后续；开关关闭不解锁；ServerStopping 清缓存；40 tick 过期；适配器防御性 |
| `CookedEventIdCacheTest`（7） | 4096 LRU 上限；40 tick 过期；clear；参数校验 |
| `FieldGuideDataTest`（7） | 三等级标签互斥；catalog=并集且=166；raw_dough 排除；分类 JSON 每料理一条显式 entry + 图标 + sort_index；每个 entry 都带永假 gate；3 道最小料理入册；原版物品在注册表 |
| `FieldGuideApiReferenceTest`（3） | Field Guide API 引用仅存在于 `impl.compat.fieldguide`；main 输出无 Field Guide 类；mods.toml optional |

原有全部测试继续通过（第 23 项）。

## 2. 构建成功 ✅

`./gradlew clean build --no-daemon` → `BUILD SUCCESSFUL`

- 产物：`build/libs/tcth-0.1.0.jar`
- SHA-256：`d2d70401cd173b083eed81b90b98efba5c2d52b616dff52c633827bafbd3f348`
- 大小：102051 字节
- JAR 内容验证：无 `com/evandev/fieldguide` 第三方类；无嵌套第三方 JAR；`assets/tcth/lang/zh_cn.json`、`textures/gui/fieldguide/*.png` 在 JAR 内；`neoforge.mods.toml` 声明 `fieldguide type=optional versionRange=[1.13.4,)`
- 分类/标签资源位于预设数据包：`docs/presets/tcth-chef/data/tcth/{tags/item,fieldguide/categories}`（与 JAR 分离，符合设计）

## 3. Field Guide 数据加载 ✅ 已实测（服务器）

服务器日志（多次启动均确认）：

```
[TCTH] Field Guide chef categories: tcth:chef_common=84, tcth:chef_t2=58, tcth:chef_t3=24
```

- 分类数据加载**零错误**（无 `Failed to load category`，无 TCTH error）
- 生成器根据 `chef_common/chef_t2/chef_t3` 标签源生成 **166 个显式 entry**；每个条目配置永假 `prerequisite gate`，阻止 Field Guide 1.13.4 默认 OBTAIN 解锁；TCTH 出锅事件通过公共 API 直接解锁。标签仍是生成器的数据来源，但运行时分类**不是 auto_populate**。
- 最小验证 9 点：① 分类加载 ✅ ② entry ID 与 Field Guide `item:<ns>/<path>` 键一致（javap 实证 + 条目数 166 吻合）✅ ③ 通过公共 API 找到物品对应 entry（`ServerFieldGuideManager.hasEntry`）✅ ④ `PlayerFieldGuideProgress.unlock(...)` 公共 API 解锁 ✅ ⑤ 重复解锁幂等（`Set.add` 返回 false 即跳过）✅ ⑥ 持久化（FieldGuideProgressManager 保存）✅ ⑦ 无需扫描 ✅ ⑧ 未修改 Field Guide 私有文件 ✅ ⑨ 无 Mixin/反射 ✅

## 4. 兼容模块加载 ✅ 已实测（服务器）

```
[TCTH] Field Guide cookbook module active (unlock on dish take-out)
```

- `CompatLoader.register("fieldguide", "com.tanrunn.tcth.impl.compat.fieldguide.FieldGuideCompatModule")` 惰性描述符；Field Guide 存在时 active
- Field Guide 未安装时不解析任何 Field Guide 类（单测证明 resolver 0 次调用）
- 所有 Field Guide 类型引用均限制在 `impl.compat.fieldguide` 条件兼容包内（源码扫描测试）
- 单模块异常隔离：监听器不中断料理统计/经验结算/tick（try-catch 包裹 + 单测）

## 5. 玩家 GUI 验证 ✅ 已实测（在线玩家 Tanrunn）

玩家连接 localhost 实测。客户端最终 JAR 哈希未记录；以服务器权威进度文件为准。

| 场景 | 结果 |
|---|---|
| 1 打开 Field Guide 看到厨师三分类 | ✅ 分类/名称/图标显示正常 |
| 3 拿取料理不解锁 | ✅ 严格验证：拿 `cooked_rabbit` 到背包后停服核对文件——未解锁 |
| 4 食用料理不解锁 | ✅ 严格验证：吃 `cooked_rabbit` 后停服核对——未解锁 |
| 5 熔炉出锅解锁 | ✅ `cooked_beef`、`cooked_mutton`（10:25:23）出锅后解锁 |
| 6 烹饪锅取餐解锁 | ✅ `blaze_lamb_chop`（10:00:06）玩家反馈以烹饪锅取餐后解锁（设备表述来自玩家反馈） |
| 7 KC 炒锅 blaze lamb chop | ⏳ **KC 炒锅专项复测待完成** |
| 8 重复制作不重复提示 | ✅ 无重复解锁提示 |
| 9 重启后解锁状态保持 | ✅ 6 个解锁条目重启后保持 |
| 10 `/tcth chef stats` 数值正确 | ✅ |
| 11 Jobs+ 经验结算 | ⏳ 本阶段未单独记录出锅前后的 Jobs+ 经验数值，经验回归标记为未重新实测。Field Guide 监听器与 Jobs+ 奖励监听器相互独立，构建和服务器运行未出现相关错误 |
| 12 raw_dough 不出现、不解锁 | ✅ |
| 13 建材/工具不出现、不解锁 | ✅ |

## 6. 玩家实际出锅解锁 ✅ 已实测

见上表场景 5/6。实测确认：`DishCookedEvent` 出锅 → TCTH 解锁（键 `item:minecraft/cooked_mutton` 等，10:25:23）；拿取/食用均不解锁（gate 生效）。

## 7. 重启持久化 ✅ 已实测

见上表场景 9：正常 stop（`All dimensions are saved`）→ 重启 → 6 个解锁条目保持。

## 8. 实测中发现的 Field Guide 1.13.4 固有行为与修复

### 8.1 默认 OBTAIN 触发器（拿取即解锁）
`getUnlockData` 对 `item:` 前缀且存在于注册表的条目，在无显式 unlock 数据时**强制回退授予 `OBTAIN` 触发器**；`InventoryMixin.onAddItem/onSetItem` 无条件对 ServerPlayer 触发 `tryUnlock(OBTAIN)`。`auto_populate` 的 unlock 数据挂在合成 ID 上，物品条目永远匹配不到 → **auto_populate 无法通过数据关闭"拿取即解锁"**（任务第八节"公共 API 确实不支持时再报告"的情形）。

**修复**：分类 contents 由 `auto_populate` 改为**显式 `entry` 列表**（仍由 chef_* 标签驱动生成，166 条），每条带 `unlock.prerequisites = [tcth:chef_cookbook_gate]`（永假前置）。效果（已实测）：
- 拿取/食用/扫描：`tryUnlock` → `canUnlock` 失败 → 不解锁 ✅
- 出锅：TCTH 直接调 `unlock()`（不检查 prerequisites）→ 解锁 ✅

### 8.2 TCTH entryId 格式错误（出锅解锁失效）
Field Guide 条目 ID 为带 kind 前缀的 `item:<ns>/<path>`（`getEntryId(obj, true)`），TCTH 原用无前缀 `minecraft:cooked_cod` 调 `hasEntry` → 永远 false → **出锅解锁从未生效**（首轮实测 cooked_cod 解锁实际来自 Field Guide 默认 OBTAIN，非 TCTH）。

**修复**：TCTH entryId 改为 `ResourceLocation.fromNamespaceAndPath("item", ns + "/" + path)`。已实测 `cooked_mutton` 出锅解锁生效。

### 8.3 Field Guide 进度文件保存时机
进度文件仅在玩家断开/停服时落盘（非实时），实时读取会拿到陈旧快照。验证协议改为"操作→停服→核对文件"。

### 8.4 测试服历史进度数据
首轮实测中 Field Guide 默认 OBTAIN 导致的 6 个提前解锁条目（cooked_cod / cooked_salmon / blaze_lamb_chop / baked_potato / cooked_beef / cooked_mutton）保留于 `Server/world/fieldguide_progress/`，明确为**测试服历史数据，不清理**（任务禁止删除 Field Guide 玩家数据）。

## 9. 注意事项

1. **KC 炒锅设备特异性**（场景 7）：出锅解锁已验证，KC 炒锅专项复测待完成。
2. **Jobs+ 经验结算**（场景 11）：当前 `jobsPlusRewardsEnabled=true`；本阶段未单独记录出锅前后的经验数值，经验回归标记为未重新实测；Field Guide 监听器与 Jobs+ 奖励监听器相互独立，构建和服务器运行未出现相关错误。
3. **客户端哈希未记录**：客户端最终 JAR 哈希未记录，客户端本地显示与服务器状态可能存在差异，以服务器权威进度文件为准。
4. **服务器既有 RecipeManager 错误**：启动日志中有 bakeries / pineapple_delight / corn_delight 等第三方模组的配方解析错误，为既有问题，与本次改动无关。
5. **Field Guide 配置保持**：`enableFieldGuideItem=false`、`enableFieldGuideScanning=false` 未修改；料理解锁由 TCTH `DishCookedEvent` 主动执行。
6. **数据包部署**：`world/datapacks/tcth-chef/data` 已同步（含 chef_* 标签与 fieldguide/categories）；`Server/config/tcth-common.toml` 四个开关均 true。
7. **GRADLE_USER_HOME**：`~/.gradle` 受 macOS TCC 只读限制，构建使用工作区内 `.gradle-home/`（APFS 克隆副本）。
8. **生成器安全修正记录**：初版生成器曾整体替换 `data` 目录导致 preset 419 个跟踪文件被删，已用 `git cat-file` 恢复（dish_tiers 405 个、jobsplus、arc、chef_meals 完好）；生成器改为**文件级原子安装 + 受管集合内陈旧清理**，第二次事故（误删 chef_meals）后已彻底修复，连续运行哈希一致。

## 交付物清单

- 代码：`src/main/java/com/tanrunn/tcth/impl/compat/fieldguide/`（FieldGuideCompatModule / FieldGuideApi / FieldGuideApiAdapter / CookedEventIdCache）
- 配置：`Config.fieldGuideCookbookEnabled`（默认 true）；`neoforge.mods.toml` fieldguide optional；`TCTHIntegration` 注册
- 数据：`scripts/generate_field_guide.py`；`docs/presets/tcth-chef/data/tcth/tags/item/chef_{common,t2,t3,catalog}.json`；`docs/presets/tcth-chef/data/tcth/fieldguide/categories/chef_{common,t2,t3}.json`；`field_guide_coverage.md`
- 资源：`src/main/resources/assets/tcth/textures/gui/fieldguide/*.png`（3 图标）；lang 中英文分类名与配置翻译
- 测试：5 个新测试类（35 用例）
- 部署：`Server/mods/tcth-0.1.0.jar`（d2d70401...）；`Server/world/datapacks/tcth-chef/data/`（已同步）

> 本报告按任务要求区分实测与未实测。本阶段不执行 Git commit。
