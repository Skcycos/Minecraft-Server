# 食韵筑家III：烟火长歌｜配置变更记录

> 服务器：食韵筑家  
> 当前周目：食韵筑家III：烟火长歌  
> 用途：记录人工确认过的配置调整，方便排查、迁移、回滚和后续封测。  
> 最后更新：2026-07-24

## 使用约定

- 本文只记录**人工决定或人工修改**的配置；模组首次启动自动生成的默认文件会单独标注为“待审”。
- 修改前先完整备份 `Server/world`、`Server/config` 和 `Server/server.properties`。
- 任何涉及世界生成、区块数据、领地、经济和权限的配置，都应先在副本服务器验证。
- 每次调整后，在本文件补充“文件、改动、目的、验证方式和回滚方式”。

## 已人工调整

### 服务端启动

| 文件 | 当前设置 | 目的 | 回滚方式 |
|---|---|---|---|
| `Server/run.sh` | `-Xms4G -Xmx8G`，并加入 `nogui` | 最小分配 4GB、最大限制 8GB 内存；无图形界面启动服务端。 | 删除这三个参数，或按机器实际内存重新设置。 |

### Paradigm：聊天、公告与玩家列表

| 文件 | 当前设置 | 目的 | 回滚方式 |
|---|---|---|---|
| `Server/config/paradigm/chat.json` | 管理聊天改为“食韵筑家·管理”；加入、离开和首次加入消息使用完整周目名；玩家聊天名使用金色。 | 统一服务器对外称呼，营造“品烟火、筑家园”的生活服氛围。 | 恢复上一版 JSON，或仅修改各条 `value` 字段。 |
| `Server/config/paradigm/lang/en.json` | 队伍聊天和提及提示均翻译为中文；保留 `{player_name}`、`{group_name}`、`{seconds}`、`%s` 等占位符。 | 让服务端提示对中文玩家可读。 | 恢复英文语言文件；不得改动键名和占位符。 |
| `Server/config/paradigm/motd.json` | MOTD、服务器列表标题和悬停说明改为“食韵筑家III：烟火长歌”；展示长期生活世界、美食、建筑、交易、聚落与交流群 `903730159`。 | 使登录欢迎页和服务器列表符合项目定位。 | 恢复上一版 JSON；保留 `{player}`、`{player_level}`、`{player_health}`、`{max_player_health}` 等变量。 |
| `Server/config/paradigm/tablist.json` | TabList 标题使用完整周目名，底部说明为美食、建筑、交易和聚落共建。 | 统一在线时的视觉信息。 | 恢复上一版 JSON；保留 `{online_players}`、`{max_players}` 和玩家前后缀变量。 |
| `Server/config/paradigm/commands.json` | 启用 `hologram` 命令。 | 支持全息展示内容。 | 将 `hologram` 改回 `false`。 |

### Ecliptic Seasons：仅保留视觉季节

| 文件 | 当前设置 | 目的 | 回滚方式 |
|---|---|---|---|
| `Server/config/eclipticseasons-common.toml` | 保留节气通知、冬季视觉覆雪、树木覆雪和地图雪景；关闭作物季节、湿度、骨粉限制、温室交易、中暑、动态昼夜、本地化天气、动物/蜜蜂/钓鱼季节限制，以及湿度区块数据写入。 | 只提供四季氛围，不让低频玩家因种植、旅行或养殖受到惩罚。 | 将对应选项恢复为模组默认值；若改动涉及物理雪或湿度数据，先在副本世界测试。 |

当前关键值：

```toml
DynamicDaylightDuration = false
UseSolarWeather = false
HeatStroke = false
EnableSeasonalCrop = false
EnableCropHumidityControl = false
RestrictBoneMeal = false
SaveChunkEnvironmentalHumidity = false
SnowyWinter = true
SnowyTree = true
```

### Git 忽略规则

| 文件 | 当前设置 | 目的 |
|---|---|---|
| `.gitignore` | 忽略世界存档、日志、缓存、调试文件、玩家缓存、Paradigm 运行时数据库/权限扫描结果和 Spark 临时文件。 | 只提交可复用的服务端配置，不提交每次运行都会变化的数据。 |

## 新增配置：待封测确认

以下文件因新增模组或首次启动而出现，目前未记录额外的人工数值调整；封测后应逐项补充用途和结论。

- `Server/config/arc-common.yaml`
- `Server/config/curios-common.toml`
- `Server/config/curios-server.toml`
- `Server/config/grieflogger/grieflogger.toml`
- `Server/config/gun_scaling/main.toml`
- `Server/config/item-restrictions-common.yaml`
- `Server/config/jobsplus-common.yaml`
- `Server/config/scguns-common.toml`
- `Server/config/scguns-server.toml`
- `Server/config/spawn-server.toml`
- `Server/config/structure_crafter-common.toml`
- `Server/config/structure_crafter-server.toml`
- `Server/config/fieldguide-client.json`
- `Server/config/fieldguide-server.json`
- `Server/config/item_descriptions.toml`
- `Server/config/lightmanscurrency-common.txt`
- `Server/config/lightmanscurrency-server.txt`
- `Server/config/lightmanscurrency/`
- `Server/config/ordertocook/`
- `Server/config/trades/`

## 当前需确认项

| 文件 | 当前值 | 说明 |
|---|---|---|
| `Server/server.properties` | `white-list=false` | 项目策划建议首发开启白名单；该值会允许任何知道服务器地址的玩家尝试进入。正式公开前请明确决定是否改为 `true`。 |

