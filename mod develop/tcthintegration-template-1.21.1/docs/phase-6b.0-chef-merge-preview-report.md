# 阶段 6B.0：厨师档次与 Field Guide 合并前差异审计

日期：见仓库阶段 6A.3/6B.0 复审日（文档确定性导出）

## 结论分层（请勿混淆）

| 层级 | 状态 |
|---|---|
| 数据审计通过 | **是**（167 DISH 全量唯一处理） |
| 合并预览生成 | **是**（仅预览 CSV/摘要） |
| 正式厨师数据已修改 | **否** |
| Field Guide 已正式更新 | **否** |
| 设备兼容已实现 | **否** |
| 玩家实测 | **未执行** |
| 进入 6B.1 | **否** |

## 1. 实际输入文件

| 文件 | 用途 |
|---|---|
| `配方与经济管理/统一配方表/新增食物模组普通手持料理清单.csv` | 6A.3 普通手持 DISH（权威输入） |
| `…/新增食物模组产物待分级表.csv` | 配方 ID、模组、证据 |
| `…/新增食物模组人工分类覆盖.csv` | 覆盖依据（只读） |
| `…/食物三档分类表.csv` | 旧权威档次（**只读，未改**） |
| `docs/presets/tcth-chef/data/tcth/dish_tiers/` | 当前 item/recipe tier（**只读**） |
| `docs/presets/tcth-chef/data/tcth/fieldguide/` | 当前 FG categories（**只读**） |
| `docs/presets/tcth-chef/data/tcth/tags/item/` | chef_* 标签（**只读**） |
| `src/main/resources/data/tcth/tags/item/not_dishes.json` | 排除项 |

权威语义：`phase-6a-new-food-mod-audit.md` **§10 6A.3**（DISH 167 / COMMON 38 / T2 129 / T3候选 0 / REVIEW 0）。

## 2. 当前基线（工作区重新统计，非历史报告硬编码）

| 指标 | 数量 |
|---|---|
| 旧权威 CSV 数据行 | **428** |
| 旧权威 unique ID | **428** |
| 旧权威 COMMON/T2/T3（等级码 1/2/3） | **317/66/24** |
| 当前 item tier JSON | **405**（315/66/24） |
| 当前 recipe tier JSON | **1** |
| FG 显式 entry | **166** |
| chef_common/t2/t3 直列 | **84/58/24** |
| FG 跨分类重复 | **0** |

## 3. 五类合并状态

| 状态 | 数量 | 含义 |
|---|---|---|
| NEW | **167** | 旧表/item/recipe 均无 → 理论可新增 |
| SAME_TIER | **0** | 已存在且与 6A.3 一致 → 不重复生成 |
| TIER_CONFLICT | **0** | 旧/item 与 6A.3 不一致 → 人工复审 |
| EXISTING_UNMAPPED | **0** | 旧表有、item JSON 无 → 禁止直接回流 |
| EXCLUDED_OR_INVALID | **0** | 排除/非法/新 T3 等 → 阻断 |

合计必须等于输入 DISH 数：**167** / 输入 **167**。

## 4. Field Guide 四类状态

| 状态 | 数量 |
|---|---|
| FG_NEW | **167** |
| FG_ALREADY_PRESENT | **0** |
| FG_ID_CONFLICT | **0** |
| FG_BLOCKED | **0** |

entry_id 语义：`item:<namespace>/<path>`（与现有 category contents 一致）。  
解锁：`tcth:chef_cookbook_gate（DishCookedEvent 严格解锁；拿取/食用不自动解锁）`。

## 5. 冲突与异常清单

详见：`配方与经济管理/统一配方表/新增食物模组厨师档次冲突复审表.csv`（0 条）。

（无）

## 6. 理论合并规模（仅 NEW 自动安全）

| 指标 | 当前 → 理论 |
|---|---|
| item tier | 405 → **572** |
| FG entry | 166 → **333** |
| COMMON/T2/T3 item | 315/66/24 → **353/195/24** |

## 7. 测试

运行：

```bash
node Server/tools/export_phase6a_audit.test.mjs
node Server/tools/export_phase6b0_merge_preview.test.mjs
```

（本报告生成时以实际终端输出为准；期望 6A 测试 47 passed，6B.0 测试全通过。）

## 8. 连续导出确定性

下列预览文件在连续两次运行中应保持相同 SHA-256（本轮）：

| 文件 | SHA-256 |
|---|---|
| 新增食物模组厨师合并预览.csv | `92e3f5c5191d1ec7bb94bdd10f18728e092e45be27f2766c08f6cd0b53470916` |
| 新增食物模组厨师档次冲突复审表.csv | `470c8e44c74b2f3b0230173e1d9f283a329801c68039a241687e2e5ec29e6f2f` |
| 新增食物模组FieldGuide合并预览.csv | `5fd8a0adb2892212f65066d9e3234d80cc8314418867dcfbf7d1a9207fcfd7c0` |
| 新增食物模组6B0合并摘要.md | `bc0c3bf339ab7aa9e89e96c57a63efc288d477def1255582f0a14b7924f7f69f` |

补充：167 DISH 与旧权威表重叠 **0**；NEW 建议档次 COMMON **38** / T2 **129**；冲突行 **0**。

## 9. 正式文件未变证据

| 项 | 值 |
|---|---|
| 保护文件数 | 418 |
| 运行前 manifest | `c01c854eca3ce2d7862129cd9807acd491a57107fcf82d2d76f397bfa503e8b6` |
| 运行后 manifest | `c01c854eca3ce2d7862129cd9807acd491a57107fcf82d2d76f397bfa503e8b6` |
| 一致 | **是** |

涵盖：`食物三档分类表.csv`、`generate_dish_tiers.py`、`generate_field_guide.py`、`dish_tiers/**`、`fieldguide/**`、`tags/item/**`。

## 10. 下一阶段建议（6B.1，未开始）

1. 人工关闭全部 `TIER_CONFLICT` / `EXISTING_UNMAPPED`。  
2. 仅合并 `NEW` 的 COMMON/T2。  
3. 同步生成 item JSON 与 FG category entry（`item:ns/path` + gate）。  
4. 再跑生成器干跑与互斥校验；**不**改奖励数值。  
5. 设备事件仍属后续阶段。

## 11. 建议暂存清单（不得自行 commit）

若复审通过后由用户提交，建议路径仅限预览与报告：

- `Server/tools/export_phase6b0_merge_preview.mjs`
- `Server/tools/export_phase6b0_merge_preview.test.mjs`
- `Server/tools/phase6a_lib.mjs`（CSV/合并纯函数扩展）
- `配方与经济管理/统一配方表/新增食物模组厨师合并预览.csv`
- `配方与经济管理/统一配方表/新增食物模组厨师档次冲突复审表.csv`
- `配方与经济管理/统一配方表/新增食物模组FieldGuide合并预览.csv`
- `配方与经济管理/统一配方表/新增食物模组6B0合并摘要.md`
- `mod develop/tcthintegration-template-1.21.1/docs/phase-6b.0-chef-merge-preview-report.md`

**禁止**将 `食物三档分类表.csv` 或 `docs/presets/tcth-chef/` 纳入本阶段提交。

---

**6B.0 停止。等待复审。不进入 6B.1。**
