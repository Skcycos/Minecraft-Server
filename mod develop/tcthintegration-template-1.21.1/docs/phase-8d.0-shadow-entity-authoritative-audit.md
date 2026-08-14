# 8D.0 生物影窃权威审计与数据方案

> 状态：**仅审计与设计**。不改代码、配置、数据包或 playerdata；
> 不构建、不启动服务器、不部署、不 commit/push。8D.1 未进入。

## 1. 审计方法与权威源

以服务器实际运行的 JAR `javap` 为权威，源码参考仅辅助。审计对象：

| 权威源 | 路径 | 说明 |
|---|---|---|
| NeoForge 事件 | `Server/libraries/net/neoforged/neoforge/21.1.247/neoforge-21.1.247-universal.jar` | `PlayerInteractEvent$EntityInteract`、`LivingChangeTargetEvent` |
| Minecraft 服务端 | `Server/libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar` | 实体/Mob/行为/Boss/持久化 API |
| 部署版 TCTH | `Server/mods/tcth-0.2.7.jar`（SHA-256 `2fa2143c…`，8C.3.1 部署版） | 玩家影窃入口与分流方式（权威） |
| NeoForge userdev patch | `~/.gradle/caches/…/neoforge-21.1.247-userdev.jar` → `patches/net/minecraft/world/entity/Entity.java.patch` | Entity attachment 序列化链（权威） |
| compiledWithNeoForge | `build/moddev/artifacts/neoforge-21.1.247.jar` 的 `net.minecraft.world.entity.Entity` | 打补丁后 Entity 字节码（权威，8D.0.2） |
| OPAC | `Server/mods/[队伍与领地]open-parties-and-claims-neoforge-1.21.1-0.29.3.jar` | 领地保护 API |
| 服务器配置 | `Server/config/incontrol/spawn.json`、`Server/kubejs/server_scripts/entity_health_scale.js`、`Server/config/does_it_tick-common.toml` | 精英/Boss 判定来源、实体 tick 边界 |

## 2. 交互入口与 PLAYER/ENTITY 分流（javap 证据）

- 入口：`PlayerInteractEvent.EntityInteract`（NeoForge，`ICancellableEvent`，
  `getTarget()` 返回交互目标实体）。该事件**可能经过双端事件链**（客户端触发
  与服务端处理均会发布）；**生产实现必须显式要求
  `event.getSide() == LogicalSide.SERVER`**，杜绝客户端侧误处理。
- 部署版 TCTH：`PlayerInteractHandler.onEntityInteract` 订阅该事件；`attempt` 中
  `getTarget()` 后**恒以 `ShadowTargetKind.PLAYER` 构造 `ShadowAttemptContext`**
  （字节码：`getstatic ShadowTargetKind.PLAYER`），随后 `resolveVictim` 要求目标为
  `ServerPlayer`。
- **公共入口检查（PLAYER 与 ENTITY 分流之前，顺序固定）**：
  1. `event.getSide() == LogicalSide.SERVER`；
  2. 交互手必须为 `MAIN_HAND`；
  3. 影窃者必须潜行（`isShiftKeyDown` / `isDiscrete`）；
  4. 双手（主手+副手）必须为空；
  5. 影窃者必须是**真实 `ServerPlayer`**（拒绝 FakePlayer）；
  6. 距离、目标存活、同维度等既有入口条件保持不变。
- **分流发生在公共入口检查全部通过之后**：
  `target instanceof ServerPlayer → 现有 PLAYER 路径（原样）`；
  `否则 → ENTITY 路径（ShadowTargetKind.ENTITY，targetId = entity UUID，
  targetType = entity type ResourceLocation）`。`ShadowAttemptContext` 记录已含
  `targetType` 字段，实体影窃无需扩展 context 结构。
- 候选提供者：部署版仅 `PlayerReadonlyCandidateProvider`。8D 需要新的实体候选
  提供者（只读探测，不写实体状态）。
## 3. 失败反应能力矩阵（javap 证据）

> 修正：**“实体行为 API 完整可用”是错误结论，已删除**。以下逐项核实
> 可支持性，未验证项一律 DEFERRED/BLOCKED，不得写成已支持。

| 反应 | 权威 API | 本阶段支持度 | 说明与验证要求 |
|---|---|---|---|
| 敌对 Mob 锁定影窃者 | `Mob.setTarget(LivingEntity)`（public）；`LivingChangeTargetEvent.setNewAboutToBeSetTarget(...)`（可取消） | **可实现** | ① 事件取消：`LivingChangeTargetEvent` 设置新目标为空以阻止切换；② 目标写入失败：`setTarget` 返回 void，无失败信号——需验证调用后 `getTarget()` 是否仍为 null；③ **非 Mob 实体分支**：目标可能是非 Mob（物品展示框等），`setTarget` 不存在于非 Mob 实体，该分支必须显式处理（不调用、不崩溃） |
| 动物逃跑 | `PanicGoal.canUse()`（存在） | **DEFERRED/BLOCKED** | `PanicGoal` 存在**不等于可触发**：原版无可靠通用触发 API（恐慌由伤害/事件内部驱动）；本阶段**不得**把「添加临时 Goal、强制导航、击退」写成已支持 |
| Boss/高风险实体强化 | — | **不实现** | 本阶段不实现任何强化（Boss 见 §5 术语） |

- 所有随机与副作用均在服务端执行；近距离交互时实体 AI 正常 tick
  （does_it_tick 仅在距离 >64 停止 tick，交互点不受影响）。
## 4. Boss/高风险术语与服务器精英判定来源（权威）

### 4.1 术语（修正）

- **API boss**：`ServerBossEvent` 持有者可证实的原版 Boss——
  `WitherBoss.bossEvent`、`EnderDragon.getDragonFight()`。**仅此二者为
  “API 可证实的原版 Boss”**。
- **策略性高风险硬排除实体**（无 Boss API，但高风险）：`ElderGuardian`、
  `Warden`（`net.minecraft.world.entity.monster.warden.Warden`，Monster 子类，
  无 Boss 事件）——按服务器策略进入高风险硬排除名单，**不称为有 Boss API 的
  核心 Boss**。
- **incontrol 统一血量放大**：`spawn.json` 规则（hostile ×2.5 + 无条件 ×6.5）
  对所有 spawn 实体统一放大血量，**不等于独立精英身份**，不能作为稳定精英
  判定 API。kubejs `entity_health_scale.js` 当前 `ENABLE=false` 不生效。

### 4.2 结论

- 服务器实际 Boss 集 = API boss（Wither、末影龙）+ 策略性高风险硬排除
  （远古守卫者、Warden）；"精英化"仅为 incontrol 血量放大，无独立判定 API。
- 8D 数据方案以 **entity type 白名单 + API boss / 策略性高风险硬排除恒排除**
  为基准。
## 5. 一次性搜刮权威状态（8D.0.2 修正：实体 Attachment）

### 5.1 权威依据（8D.0.2，撤回 8D.0.1 的错误结论）

以 NeoForge 21.1.247 userdev `patches/net/minecraft/world/entity/Entity.java.patch`
与 compiledWithNeoForge 的 `Entity.class`（`build/moddev/artifacts/neoforge-21.1.247.jar`）
为权威（javap + patch 双核实）：

1. **`Entity extends AttachmentHolder`**（class 声明，compiledWithNeoForge javap）；
2. **`serializeAttachments` 位于 `Entity.saveWithoutId` 内**（随后才调用
   `addAdditionalSaveData`）：patch 在 `saveWithoutId` 尾部追加
   `CompoundTag attachments = serializeAttachments(registryAccess());`
   `if (attachments != null) p_20241_.put(ATTACHMENTS_NBT_KEY, attachments);`；
   compiledWithNeoForge 字节码中 `saveWithoutId` 内确有
   `serializeAttachments(HolderLookup)CompoundTag` 调用（其后调用
   `addAdditionalSaveData`）；
3. **`load` 调用 `deserializeAttachments`**：patch 在 `load` 内追加
   `if (p_20259_.contains(ATTACHMENTS_NBT_KEY, TAG_COMPOUND))
   deserializeAttachments(registryAccess(), ...)`；字节码同证；
4. **`restoreFrom` 使用 `saveWithoutId → load`**：字节码
   `restoreFrom` 内 `saveWithoutId`（→ `load`）——跨维度创建的新实体同样复制
   可序列化 attachment；
5. **`AttachmentType.Builder.serialize(IAttachmentSerializer)` / `serialize(Codec)`**
   存在（NeoForge universal jar），明确表示该 attachment 类型**持久化到磁盘**。

> 结论修正：实体 attachment 随实体 NBT 保存链（chunk 卸载保存、重启、维度迁移
> 经 restoreFrom 复制）**可靠持久**；8D.0.1 基于未打补丁 srg jar 的
> “不随实体保存”结论**撤回**。

### 5.2 权威状态设计

- **注册可序列化的实体 `AttachmentType`**（如 `tcth:shadow_looted`，
  `AttachmentType.Builder.serialize(...)` 声明磁盘持久化），attachment 是
  **同一实体“已搜刮”状态的权威来源**；
- **不使用 `copyOnDeath`**：死亡后新实体**不得继承**已搜刮标记；
- 新 UUID 实体（召唤/复制/繁殖）自然视为新个体（未搜刮）；
- `ShadowAuditStore` **仍只负责历史审计，不兼任 marker**。

### 5.3 生命周期边界（attachment 语义）

| 场景 | 行为 |
|---|---|
| 实体 chunk 卸载（UNLOADED_TO_CHUNK / UNLOADED_WITH_PLAYER） | 实体 NBT 保存时 `serializeAttachments` 落盘，重载时 `load → deserializeAttachments` 恢复——标记保留 |
| 服务器重启 | 同上（实体 NBT 持久） |
| 维度迁移（CHANGED_DIMENSION，`restoreFrom`） | `saveWithoutId → load` 复制可序列化 attachment——同一实体跨维度仍为“已搜刮” |
| 复制/召唤（/summon、`MobSpawnType.*`、蛋/刷怪笼） | 新 UUID → 新实体，无标记 |
| KILLED / DISCARDED | attachment 随实体消亡；审计历史仍在 `ShadowAuditStore`；无残留 marker 数据生命周期问题 |

### 5.4 SavedData 降级（可重建管理索引，非权威）

- **删除**“`getEntity(UUID) == null` 可判断实体终结”的表述（实体可能长期卸载
  但未终结）；
- **删除** 7 天 TTL（会重新开放仍存活但长期卸载的实体）；
- **删除** `MAX_LOOTED = 8192` 作为权威 marker 上限；
- 如保留 SavedData，**只能作为可重建的管理索引**（例如按 entity type 的统计
  或审计关联索引），**不得决定实体是否已搜刮**。

### 5.5 事务限制（8D.1 前置）

- attachment marker、物品交付与审计**不是数据库原子事务**；
- 8D.1 必须设计 **PENDING / LOOTED 状态与失败恢复策略**，不得只写 boolean 后
  声称崩溃级 exactly-once；
- 崩溃窗口（marker 已写 / 物品未交付；物品已交付 / marker 未写）**必须如实
  记录**并给出恢复方向（如审计记录驱动重放/人工介入）。
## 6. `data/tcth/shadow_loot/<namespace>/<entity>.json` schema（收口，无歧义算法）

```jsonc
{
  "pools": [
    {
      "weight": 100,                 // 池权重（1..1,000,000）
      "entries": [                   // 每池条目（1..32）
        { "id": "minecraft:cobblestone", "weight": 50, "min_count": 1, "max_count": 2 }
      ]
    }
  ]
}
```

**抽取算法（每层恰好一次，全服务端随机）**：

1. 每次成功只按 weight 选择 **1 个 pool**；
2. 再从该 pool 按 weight 选择 **1 个 item entry**；
3. 最后从该 entry 的 `min_count..max_count` 抽取**一次**数量；
4. **池级 min_count/max_count 已删除**（首版不引入 `rolls`）；
5. 一次搜刮**最多交付一种物品**。

**数值约束（越界 → 该实体定义整体 fail-closed）**：

- `pools` 1..8；每池 `entries` 1..32；
- `weight` 1..1,000,000；**权重和使用 long 且防溢出**（逐层累加前检查
  `sum <= Long.MAX_VALUE - weight`）；
- `min_count` 1..4、`max_count` 1..4 且 `min <= max`（首版不使用 64 的高风险
  上限）；
- 任一非法字段、未知物品 id（Registry 未注册）、空池（pools 为空或某池 entries
  为空）、权重溢出 → 该实体定义整体 fail-closed，日志节流告警；
- **不回退死亡掉落表或默认池**。

**产出定位（8D.0.2）**：**“不复制死亡掉落表”仅表示不会自动复制核心死亡
战利品**；独立 shadow_loot 仍是**额外产出**，正式数据必须经过**经济审计**
后才可启用——本阶段仍不生成正式掉落数据。

**命名空间**：原版实体 `minecraft:cow` → `data/tcth/shadow_loot/minecraft/cow.json`；
模组实体同理（namespace 为实体所属 modid）。

## 7. 开关组合（8D 设计）

```
enabled && shadowThiefIntegrationEnabled
&& shadowEntityTheftEnabled && shadowRealAssetTransfersEnabled
```

- **`shadowEntityTheftEnabled` 不依赖 `shadowPlayerTheftEnabled`**：8C.2.2 的
  总闸把 playerTheftEnabled 作为公共闸的一部分，8D 需按 targetKind 分支重构：
  公共三开关 + `PLAYER 目标 → playerTheftEnabled` / `ENTITY 目标 →
  entityTheftEnabled`（与 8C.2.3-5 预告一致）。
- 读取失败均 fail-closed false；`ShadowAuditStore` 健康检查保持 8C.2.4 语义
  （不健康 → AUDIT_FAILED / 实体搜刮全拒）；实体“已搜刮”状态由实体 attachment
  承担，不参与存储健康检查。
## 8. 首批原版实体建议矩阵与风险分级（建议，不生成正式数据）

风险分级：**L1 无风险**（状态只读、无 AI 反制）→ **L2 低风险**（有逃跑/反击
但可控）→ **L3 中风险**（强力反击/特殊机制）→ **HX 策略性高风险硬排除** →
**BX API boss 恒排除**。

| 实体 | 类别 | 风险 | 建议 | 理由（服务器权威） |
|---|---|---|---|---|
| `minecraft:cow` / `sheep` / `pig` / `chicken` | 动物 | L2 | 可开放（低权重池） | 逃跑=PanicGoal（触发不可控，见 §3），血量 ×6.5（incontrol），交互近距离可完成；无反击 |
| `minecraft:rabbit` | 动物 | L1 | 可开放 | 逃跑型；不复制死亡掉落表，但 shadow_loot 仍是额外产出，需经济审计 |
| `minecraft:wolf` / `cat` | 驯养动物 | L2 | 可开放（低权重） | 有 owner；`NeutralMob` 愤怒仅对攻击者 |
| `minecraft:zombie` / `skeleton` / `spider` / `creeper` | 普通怪物 | L3 | 谨慎（小池、低 min/max） | 血量 ×6.5、有近战/远程/爆炸反击；影窃后实体存活，无掉落翻倍 |
| `minecraft:enderman` | 特殊怪物 | L3 | 谨慎/暂缓 | 传送+高伤反击 |
| `minecraft:blaze` / `ghast` | 远程怪 | L2 | 可开放（低权重） | 远程反击但可远离；`does_it_tick` 白名单含 ghast |
| `minecraft:iron_golem` | 中立守卫 | L2 | 可开放（低权重） | 反击强但无 AI 逃跑 |
| `minecraft:elder_guardian` | 策略性高风险 | **HX** | **硬排除** | 无 Boss API，服务器策略硬排除 |
| `minecraft:warden` | 策略性高风险 | **HX** | **硬排除** | 无 Boss API（Monster 子类），高伤+特殊机制 |
| `minecraft:wither` | API boss | **BX** | **恒排除** | `ServerBossEvent` 持有者；禁掉落翻倍 |
| `minecraft:ender_dragon` | API boss | **BX** | **恒排除** | 同上；`does_it_tick` 白名单 |

- 首批发版建议仅开放 **L1/L2**；L3 待 8D.1 数据评审；HX/BX 恒排除。
  **不生成正式掉落数据。**
## 9. 审计结论

1. 交互入口：`PlayerInteractEvent.EntityInteract` 可能经双端事件链，生产实现
   必须显式要求 `LogicalSide.SERVER`；公共入口检查（主手/潜行/双手空手/真实
   ServerPlayer/距离/存活/同维度）完成后才做 PLAYER/ENTITY 分流。
2. 失败反应能力：敌对 Mob 锁定（`Mob.setTarget` + 事件取消）可实现，需验证
   取消/写入失败/非 Mob 分支；动物逃跑 DEFERRED/BLOCKED（无可靠通用触发
   API）；Boss 强化不实现。
3. 一次性搜刮权威状态（8D.0.2 修正）：**实体 `AttachmentType`**（可序列化，
   随实体 NBT 保存链持久、维度迁移经 `restoreFrom` 复制），attachment 为同一
   实体“已搜刮”的权威来源；不使用 `copyOnDeath`；新 UUID 实体为新个体；
   `ShadowAuditStore` 只负责历史审计。SavedData 仅可作可重建管理索引。
4. `shadow_loot` schema：无歧义三层抽取（1 pool → 1 entry → 1 count），
   池级 count 已删除，数值约束与 fail-closed 见 §6；独立产出，正式数据须经
   经济审计。
5. Boss 术语：API boss 仅 Wither/末影龙；远古守卫者与 Warden 为策略性高风险
   硬排除；incontrol 血量放大≠精英身份。
6. 开关组合：`enabled && integrationEnabled && entityTheftEnabled &&
   realAssetTransfersEnabled`，`shadowEntityTheftEnabled` **不依赖**
   `shadowPlayerTheftEnabled`。
7. **范围**：COIN、玩家影窃、职业经验和能力树均不在 8D 范围。

### 结论状态

**8D.0.2 AUDIT CORRECTED** / 允许进入 8D.1 的前提是先设计 attachment 的
**PENDING / LOOTED 事务状态与失败恢复策略**（见 §5.5）；未实现、未构建、
未部署、未在线验证。


## 10. 8D.1 落地确认（BUILD-only）

- `tcth:shadow_loot_state` attachment 已实现（四状态、自定义严格
  serializer 不抛异常、blocksTheft 全拒、不用 copyOnDeath）；marker 为唯一
  权威，SavedData 不参与判定。
- `shadow_loot` 加载器已实现（严格 schema、原子替换、硬排除四实体、
  三层随机各恰一次、未知物品 fail-closed）。
- PLAYER/ENTITY 分流已实现；实体开关组合不含 `shadowPlayerTheftEnabled`。
- 实体物品事务（EntityLootTransferPlan + 10 步顺序 + 回滚/恢复规则）
  已实现；PENDING 阻塞重试、崩溃窗口如实记录、不声称崩溃级 exactly-once。
- 失败反应：`Mob.setTarget` + 回读；非 Mob 不调用；动物逃跑 DEFERRED；
  Boss 强化未实现。
- 详见 `docs/phase-8d.1-shadow-entity-framework-report.md`。


## 11. 8D.1.1 修订注记

- **8D.1 初版 BUILD PASS 已被 8D.1.1 修订取代**：物品交付改为明确槽位事务
  （`SlotItemTransaction`，禁 `Inventory.add`/扫描 shrink）；审计状态前置
  `auditEnabled` 检查、PENDING 后干净恢复写 FINAL FAILED_CLEAN、RECOVERY
  携带 receipt；数据包加载改为最高优先级覆盖 + reload 期 registry 验证
  （`containsKey`，杜绝默认条目误判）；attachment schema 加每状态字段白名单
  与 write/read 对称；实体路径接入冷却与幂等、真实类型重验与硬排除、失败
  反应仅限敌对 Enemy。详见 `docs/phase-8d.1-shadow-entity-framework-report.md`
  §10。

—— 8D.0 / 8D.1.1 收口完 ——
