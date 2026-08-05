# 阶段 3D 在线验收报告：真正的厨师四路线能力树

日期：2026-08-05（在线验收轮）
项目：`mod develop/tcthintegration-template-1.21.1`
服务器：`Server/`
测试玩家：Tanrunn（本地连接 127.0.0.1）

## 0. 测试环境

- TCTH JAR：`96c3cc8310e7f64b987dc1aadf18b8bc3d16b5d3f759d86a33424880c489e3a1`（阶段 3D.1 修正后）
- 数据包：`Server/world/datapacks/tcth-chef/`（正式定义，12 powerup + 12 arc action）
- 服务器：NeoForge 21.1.247 + 测试服完整模组集（本次因 ApricityUI 已知专服并发竞态，按预案临时移出其 JAR，测试后已恢复原位，未修改其内容）
- 难度：测试期间玩家切至 Normal（炉火伤害测试），随后玩家自行改回 Peaceful

## 1. /jobs 界面确认 ✅

- `tcth:chef`（厨师）职业正常加载（Jobs+ 日志 `Set level for job Chef`；数据 `JobInstanceLocation: "tcth:chef"`）
- 四条路线 12 节点全部可购买/可激活，玩家逐一购买并激活：研修 i/ii/iii、品鉴 basic/nourishing/feast、炉火 basic/master/expert、刀工 basic/adept/expert（PowerupLocation 实测与数据包一致）
- `PowerupLocation` 格式实证：`tcth:chef/<node>`（与 arc holder id 一致）
- JobsData 实测：`{State: "ACTIVE", PowerupLocation: "tcth:chef/<node>"}`
- `/job set powerup` 命令的 powerup 参数无法直接命中（控制台解析失败）；改用 Jobs+ 管理员命令设等级/金币 + 玩家在 `/jobs` GUI 购买激活，等效完成，未编辑 NBT
- required_level/price/名称/描述：玩家购买过程即为验证（75 级可买 60 级节点等门槛正确）

## 2. 研修路线（taste_meal 固定 +1 基础经验，吃 #tcth:chef_meals 的 mushroom_stew）✅

| 激活状态 | 单次增量 | 预期 | 结论 |
|---|---|---|---|
| I（25级） | +1.0 | Jobs+ `JobExpMultiplierReward` 用 `d2i` 整数截断：`(int)(1×1.25)=1`，增量 0 | ✅ 机制符合（1 XP 无法体现 1.25，属 Jobs+ 截断设计） |
| II（I+II 激活，50级） | +1.0 | `(int)(1×1.5)=1`，增量 0；I 不叠加 | ✅ 无 I 叠加 |
| III（I+II+III 激活，75级） | **+2.0** | `(int)(1×2.0)=2`，增量 +1 → 每次 +2 | ✅ **最高倍率 2.0 精确**；若 I/II 错误叠乘（1.25×1.5×2=3.75）会 +3，实测 +2 |

- 经验实测轨迹：100 → 102 → 103 → 104 → 106（各步对应吃蘑菇煲）
- 结论：III 生效时 II、I 均不触发；最高倍率 2 倍；无叠乘、无叠加 ✅
- 倍率 1.25/1.5 的数值精确性由确定性单元测试保证（Jobs+ 整数截断使小经验值无法区分 1.25 与 1.5，属上游机制）

## 3. 品鉴路线（吃 #tcth:chef_meals）✅

| 节点 | 效果 | 实测 |
|---|---|---|
| 细品百味（I） | 生命恢复 I 5 秒 | ✅ 玩家确认效果应用 |
| 食补调和（II） | 生命恢复 I 5 秒 + 抗性提升 I 8 秒（单一完整效果包） | ✅ 玩家确认两效果同时出现 |
| 宴席余韵（III） | 恢复 I 5s + 抗性 I 8s + 速度 I 15s（单一完整效果包） | ✅ 玩家确认三效果同时出现 |
| 冷却 | 20 秒（400 tick） | ✅ 冷却窗口内再次食用无新效果；窗口外恢复（玩家确认"有冷却"） |

- 每级只触发一个完整效果包（未出现重复效果图标）
- `taste_meal +1 XP` 仍正常（研修部分每吃一次 +1 基础经验）
- 冷却仅内存缓存、登出/停服清理、不写 playerdata（单元测试覆盖）

## 4. 炉火路线（#minecraft:is_fire 减伤，玩家脱甲、无效果，满血 20 基线）✅

伤害类型 `minecraft:hot_floor`（岩浆块，属 IS_FIRE，无持续灼烧干扰）；20 点伤害：

| 激活状态 | 实测剩余血量 | 实际伤害 | 预期 | 结论 |
|---|---|---|---|---|
| hearth_basic（I） | 3.0 | **17.0** | 20×0.85=17 | ✅ 15% 减伤 |
| I+hearth_master（II） | 6.0 | **14.0** | 20×0.70=14 | ✅ 30% 减伤，I 不叠加 |
| I+II+hearth_expert（III） | 10.0 | **10.0** | 20×0.50=10 | ✅ 50% 减伤，I/II 不叠加，永不免疫 |

- 岩浆（lava）伤害：玩家确认减伤正确（"测试通过"）
- 非火焰伤害：`minecraft:fall` 20 点 → 不减伤（玩家直接死亡）✅
- 减伤先于护甲等原版结算（Arc `ServerPlayer.hurt` HEAD），护甲/抗性药水流程不受影响
- 玩家自身回血（满饥饿）会抬升测量值，采用"大伤害 + 立即查询"法取得精确值

## 5. 刀工路线（#c:tools/knife 免耐久）✅

- 玩家激活 knife_basic/adept/expert（互斥取最高 35%）
- 使用 FD `iron_knife`（属 `#c:tools/knife`）在切菜板切菜
- 实测：厨刀 `minecraft:damage: 15`（多次操作后耐久从 0 累至 15）—— 切菜次数 > 15，部分操作未扣耐久 ✅（35% 概率免除功能可观察）
- 非厨刀工具不受影响（切菜板操作仅厨刀耗耐久，玩家确认）
- 精确概率 10/20/35% 由确定性单元测试证明（ChefPowerupAccessTest / ChefAbilityTreePresetTest），实机仅验证功能存在

## 6. 回归检查 ✅

- 出锅料理署名：玩家确认正常（回归完成）
- `/tcth chef inspect`：命令存在（控制台执行报 "This command must be run by a player"，玩家运行正常）
- `/tcth chef stats`：命令存在，玩家运行正常
- Field Guide 出锅解锁：玩家确认正常（回归完成）
- Jobs+ 料理经验在线回归未观察到异常重复结算；精确幂等由既有自动测试覆盖
- 12 节点全部 ACTIVE 状态下四路线同时生效（刀工 II 类组合场景隐含覆盖）

## 7. 测试结束清理 ✅

- 执行 `stop` 后服务器完成世界保存并出现 `All dimensions are saved`；随后关闭流程在第三方模组（OpenPartiesAndClaims IO worker 后）卡住，经确认世界已保存后强制结束进程，因此不将其表述为完整的正常退出
- ApricityUI JAR：按预案临时移出 → 已恢复原位，内容未修改
- 临时测试数据包：未创建（全程使用正式 required_level/price）
- playerdata：未手工编辑（仅 Jobs+ 管理员命令设等级/金币 + 玩家 GUI 购买，测试状态保留在玩家数据中，未回写/迁移旧职业数据）
- 正式数据包状态确认：研修 III req=75/price=15 等正式值

## 8. 未验证项与证据边界

- 研修 I/II 的在线执行路径已验证，但基础经验为 1 时经过 Jobs+ 整数截断，1.25 与 1.5 都会显示为 +1，因此两档精确倍率由确定性单元测试证明；研修 III 的 2 倍及互斥已在线数值验证
- 刀工概率精确值（10/20/35%）由确定性单元测试证明；实机仅验证免耐久能力确实触发且非厨刀不受影响
- 品鉴、炉火、研修 III、料理署名、统计与 Field Guide 回归均已在线实测
