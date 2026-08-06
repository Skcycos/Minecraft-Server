# 阶段 4A（方案修订）交付报告：停用 Jobs+ 默认职业 + 创建 tcth:farmer

日期：2026-08-06（会话 `20260805-131045`）

## 一、结论摘要

- **Jobs+ 自带 10 个默认职业已通过官方配置停用**（`enable_default_jobs: false`），
  不是删除 JAR 内容、空 JSON 覆盖、Mixin 或手工删 playerdata。
- **不迁移** `jobsplus:farmer` 的等级、经验、能力节点与玩家数据；
  `world/playerdata/*.dat` 未编辑（玩家存档时间戳 19:41，本轮启动 21:22 未变化）。
- **`jobsplus:farmer` 仅作实现参考**（JAR 内置 Job JSON / Arc Action / Powerup
  结构、双倍掉落、经验倍率、互斥写法）。
- **正式职业为 `tcth:farmer`**（另有既有 `tcth:chef`）。
- 服务器最终加载职业 ID 清单：**`tcth:chef`、`tcth:farmer`**（日志 `Loaded 2 jobs`）。

## 二、停用默认职业（官方配置）

| 项 | 值 |
|---|---|
| 配置 | `Server/config/jobsplus-common.yaml` → `jobs: enable_default_jobs: false` |
| 备份 | `backup-4a-default-jobs-20260806/jobsplus-common.yaml.orig` |
| 数据包列表备份 | `backup-4a-default-jobs-20260806/datapacks-启用列表.txt` |
| 停用前服务器状态 | 未运行（部署前已停服） |
| 停用前启用数据包 | `world/datapacks/tcth-chef`（`datapacks-disabled/shiyun_jobs` 保持停用） |

JobManager 启动日志：`Loaded 2 jobs`（此前 `enable_default_jobs: true` 时为
`Loaded 11 jobs`＝10 默认 + tcth:chef），证明 10 个默认职业不再加载。
YamlConfig 启动重写配置后 `enable_default_jobs` 仍为 `false`。

配置自带警告：关闭默认职业会清除对应职业统计，用户已明确接受，不迁移、不恢复。

## 三、tcth:farmer 预设

独立预设目录：`mod develop/tcthintegration-template-1.21.1/docs/presets/tcth-farmer/`
（已整目录复制为 `Server/world/datapacks/tcth-farmer/`，启动时自动加载）。

- 职业定义 `data/tcth/jobsplus/jobs/farmer.json`：
  `is_default: false`、`max_level: 100`、主题图标 `minecraft:stone_hoe`、
  背景 `minecraft:textures/block/hay_block_side.png`；**无内嵌 name/description**
  （Jobs+ Serializer 不读取，界面读语言键）。
- 基础经验（4 个 Arc Action，全部 holder = `tcth:farmer`，全部 reward =
  `jobsplus:job_exp`，无金币）：

| 行为 | 经验 | Action | 条件 |
|---|---:|---|---|
| 种植作物 | 0 XP（不创建奖励 Action） | — | — |
| 收获成熟普通作物 | 1–2 | `arc:on_harvest_crop` | `arc:crop_fully_grown` |
| 繁殖动物成功 | 3–5 | `arc:on_breed_animal` | — |
| 驯服动物成功 | 8–12 | `arc:on_tame_animal` | — |
| 剪取已长毛绵羊 | 1–2 | `arc:on_interact_entity` | 羊 + `arc:ready_for_shearing` + 手持剪刀（主/副手） |

- 不创建 `jobsplus/powerups/farmer/` 与 `arc/farmer/powerup/`；阶段 4C 四路线
  （耕作 / 丰收 / 畜牧 / 研修）仅在设计说明中预留（README），不复用旧节点
  ID（`double_drops_i/ii/iii`、`job_exp_i/ii/iii`，不再制作覆盖；旧节点数据
  随默认 Job 停用而不产生结算，见 4A.1 修正）。
- 无 Bountiful 修改、无悬赏/收购价修改、不修改 `tcth:chef`。

## 四、防刷语义与 Arc 原生能力核验（字节码证据，如实结论）

结论：**Arc 9.0.0 原生 Action 覆盖部分防刷要求，但存在明确边界；是否需要
TCTH 统一农事事件（`CropHarvestedEvent`）由阶段 4A.1 覆盖矩阵与 FakePlayer
审计决定，尚未下定论**。本阶段不新增 Mixin。

| 要求 | 原生机制（Arc 9.0.0 JAR 字节码实证）与边界 |
|---|---|
| 自动化（无玩家上下文） | `BlockEvents.registerEvents` 绑定 Architectury `BlockEvent.BREAK/PLACE`；回调 `lambda$registerEvents$0/1` 首行 `instanceof ArcServerPlayer`。**无 `ServerPlayer` 上下文的机械收割不会触发**；但 **FakePlayer 是 `ServerPlayer` 子类，`instanceof ArcServerPlayer` 不能证明其被排除**（待专项验证） |
| 未成熟不发经验 | `arc:crop_fully_grown`：取方块 `age` 属性并比对最大值（`CropFullyGrownCondition`）。**仅对 `Block instanceof CropBlock` 的方块调用**，非 CropBlock 作物不在判定内 |
| 失败繁殖/驯服/剪毛不发经验 | 繁殖：`MixinAnimal.onSpawnChildFromBreeding`（幼崽生成回调）+ `getLoveCause()`；驯服：`EntityEvent.ANIMAL_TAME`；剪毛：`arc:ready_for_shearing`（`Shearable.readyForShearing`，排除未长毛/幼崽羊） |
| 同一行为只结算一次 | 每事件单次触发；收获用 `jobsplus:job_exp`（事件结算），不用 `arc:block_drop_multiplier`（不按掉落倍增） |

### 4A.1 修正：之前结论的偏差

此前结论“Arc 原生 Action 已满足全部防刷要求 / 机械与假人都不进入结算 /
默认职业 Action/Powerup 资源完全消失”不准确。修正为：

- 无 `ServerPlayer` 上下文的机械收割不会触发；**FakePlayer 是否被排除待验证**。
- `on_harvest_crop` 只对 `Block instanceof CropBlock` 调用。
- `enable_default_jobs=false` 停用默认 **Job 实例**；内置 Arc Action 与
  Powerup **数据仍可能被数据加载器解析**，但对应 Job 不可用、不产生结算。
- 覆盖矩阵、FakePlayer、模组特殊作物（FD 番茄/卷心菜/洋葱/水稻等）与
  **在线实测**均在阶段 4A.1 建立与验证，详见 `docs/phase-4a.1-farmer-audit.md`。

## 五、语言资源（TCTH 主模组）

`src/main/resources/assets/tcth/lang/`（不在数据包 `data/`）：

| 键 | zh_cn | en_us |
|---|---|---|
| `jobsplus.job.tcth.farmer.name` | 农夫 | Farmer |
| `jobsplus.job.tcth.farmer.description` | 耕种四时作物，照料田地与牲畜，在一次次播种与收获中积累经验。 | Cultivate seasonal crops, tend fields and livestock, and grow through planting and harvest. |

客户端需安装匹配版本 TCTH Integration（本轮新 JAR
SHA-256 `53a5b79a10afab76245485337c31b030a396422eedc0838b062447fe10339d9a`）。

## 六、测试结果

| 测试 | 结果 |
|---|---|
| 静态测试（JUnit） | **完成**：全量 **301/301 通过，0 失败，0 跳过**（阶段 4A.1 新增 `FarmerCropCoverageTest` 6 项类层次/矩阵实证、`FarmerServerDeploymentTest` 3 项部署态检查；`FarmerPresetTest` 14 项预设/语言断言）。覆盖：职业定义存在 / `is_default=false` / 无第二份 `jobsplus:farmer` 定义 / 所有新 Action holder 为 `tcth:farmer` / 无 `jobsplus:farmer(/)` 引用 / 收获含成熟条件 / 种植无奖励 / 基础经验数值 / 无金币与双倍掉落 / 剪羊毛条件 / 无 Bountiful / 中英翻译完整 / 预设无 lang 目录 / chef 预设未改 / 发布 JAR 不含预设；另有真实作物覆盖矩阵与 FakePlayer 静态审计（见 `docs/phase-4a.1-farmer-audit.md`） |
| 发布 JAR 检查 | JAR 内含 farmer 翻译键；无 `data/tcth/{jobsplus,arc,dish_tiers,fieldguide}` 与 `docs/presets` |
| 启动测试（服务器实机） | **完成**：`Done (6.794s)`；`Loaded 2 jobs`（tcth:chef、tcth:farmer）；`Loaded 171 actions`（167+4，零 holder/action/condition/reward 错误）；`Loaded 125 job powerups`（chef 12 节点保留）；数据包 `tcth-farmer` 自动加载；正常停服 `All dimensions are saved` |
| GUI 测试（/jobs 界面） | **未完成**：需玩家客户端登录查看职业列表与职业图标；本环境无在线客户端，仅以日志 `Loaded 2 jobs` + 数据包来源佐证 |
| 玩家行为实测（农夫经验结算） | **未完成**：无玩家在线；本轮**无玩家烟雾测试不构成农夫经验实测成功**。实测方法（供后续）：玩家加入 → 启用农夫 → 收获成熟小麦/收土豆、繁殖牛羊、驯服狼猫、剪已长毛羊，记录前后经验增量（期望 1–2 / 3–5 / 8–12 / 1–2），并验证未成熟收获、机械收割、失败行为均不涨经验 |

## 七、文件改动清单（未提交 Git）

- 改：`Server/config/jobsplus-common.yaml`（`enable_default_jobs: false`）
- 增：`mod develop/tcthintegration-template-1.21.1/docs/presets/tcth-farmer/`（预设）
- 改：`…/src/main/resources/assets/tcth/lang/zh_cn.json`、`en_us.json`（farmer 键）
- 增：`…/src/test/java/com/tanrunn/tcth/impl/compat/jobsplus/arc/FarmerPresetTest.java`
- 改（部署）：`Server/world/datapacks/tcth-farmer/`、`Server/mods/tcth-0.1.0.jar`
- 增（备份）：`backup-4a-default-jobs-20260806/`

**未执行 git commit / push，等待复审。**
