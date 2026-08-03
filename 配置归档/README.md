# 配置归档 · 食韵筑家III:烟火长歌

> 本目录汇总 `Server/config/` 下各模组配置文件中**有实际业务含义**的设置,便于阅读与维护。
> 原始配置文件仍以 `Server/config/` 为准,本目录为人工整理的解读文档。

## 目录结构

| 文件 | 内容 |
|---|---|
| `00-索引.md` | 全部模组配置一览表(模组 → 配置文件 → 所在文档) |
| `01-美食玩法.md` | Farmer's Delight、Kaleidoscope、Create、订单/商店等生活玩法 |
| `02-经济交易.md` | Lightman's Currency、物品限制、JEI |
| `03-世界生成.md` | Terralith、Tectonic、Lithostitched、RoadWeaver |
| `04-社区运营.md` | 领地、刷怪控制、Paradigm、季节、枪械平衡、破坏日志 |
| `05-性能优化.md` | ModernFix、FerriteCore、Lithium、ServerCore、spark、C2ME、NeoForge |

## 阅读约定

- 「当前值」直接取自配置文件实际内容;标注 `默认` 的表示未做修改。
- 每份文档按模组分节,包含:配置文件路径 → 关键配置表格 → 值得注意的点。
- 更新配置后,请同步更新对应文档;如配置被还原为默认,删除对应条目。

## 已知需要注意的项(重点阅读)

1. **InControl 生物血量**:`incontrol/spawn.json` 两条 `healthmultiply` 规则若叠加生效,敌对生物血量可达基础 ×16.25,需确认是否本意。
2. **Scorched Guns 枪械削弱**:全局伤害 ×0.2、爆炸半径减半、炮塔伤害约 1/5,与「不做战力」定位一致。
3. **Lightman's Currency 经济收紧**:铸币机配方禁用(`canMint=false`)、实体/箱子不再掉落硬币、猪灵不收金币、银行家/收银员职业交易关闭。
4. **Paradigm 占位项**:公告中的 `https://example.com` 链接、申诉 URL(`example.invalid`)、全局出生点坐标均为占位,开服前需配置。
5. **季节系统为纯氛围**:eclipticseasons 不限制作物、繁殖、钓鱼,只保留节气日历与冬季视觉积雪。
6. **ServerCore 未激进调优**:激活范围(`activation-range`)与动态性能调节(`dynamic`)两大功能当前关闭。
7. **resourceful-config-web.json 含明文密码**:Web 配置界面已禁用,但文件内有 UUID 形密码,启用前请更换。
8. **模组数量**:`SERVER_PLAN.md` 记录的基线(29 个)已过时,`Server/mods/` 现有 66 个文件(含前置库)。
