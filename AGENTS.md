# AGENTS.md

## 模组汉化工作流

汉化 Minecraft 模组时，按以下流程操作：

1. 找到目标模组 jar，可能位于以下任意一处（文件名常带 `[客户端]` 前缀）：
   - 客户端 mods：`Server/automodpack/host-modpack/main/mods/`
   - 服务器 mods：`Server/mods/`
   用 `find`/`ls` 在两边搜索 modid 或名称关键词。
2. 用 `unzip -l` 列出 jar 内容，找到 `assets/<modid>/lang/en_us.json`，用 `unzip -p` 提取原文词条。
3. 将翻译后的 `zh_cn.json` 写入必选资源包：
   `Server/global_packs/required_resources/食韵筑家专用材质包v1.21.1/assets/<modid>/lang/zh_cn.json`
   （目录不存在则新建）
4. 用 `python3 -m json.tool` 校验 JSON 合法性。
5. 核对键值：用 python 脚本对比 en_us.json 与 zh_cn.json 的 key 集合，确保无遗漏、无多余键。
6. 保留原文件中的 `%s` 占位符与 `\n` 换行；`desc` 后缀的 key 为选项说明。
7. 若该 mod 已存在于资源包 `assets/` 下，只需新增/修改 `lang/zh_cn.json`，无需改动 pack.mcmeta。

## 建筑商店（buildshop）增删条目工作流

建筑商店由服务器数据包驱动（`/reload` 热重载，无需重启），根目录为 `Server/global_packs/required_data/buildshop/`。结构：

- 分类：`data/buildshop/building_shop/categories/<id>.json`
- 商品：`data/buildshop/building_shop/products/<id>.json`
- 货币：`virtual_coins`（虚拟金币，玩家首登 1000）或 `items:<itemId>`（实物货币）

**新增商品**（在 `products/` 下新建 `<id>.json`）：

```json
{
  "id": "oak_log",
  "item": "minecraft:oak_log",
  "categories": ["wood"],
  "currency": "virtual_coins",
  "unitPrice": 8,
  "bulkSize": 64,
  "stock": { "mode": "infinite" },
  "displayName": "橡木原木",
  "description": "经典的基础木材",
  "enabled": true,
  "sort": 0
}
```

字段说明：`id`/`item` 需唯一；`categories` 指向分类 id（可多分类）；`unitPrice` 单价；`bulkSize` 一次购买数量；`stock.mode` 为 `infinite`（无限）或 `finite`（有限，配 `quantity`）；`sort` 控制分类内排序；`displayName` 在游戏中显示为物品名（即使带中文也无需 lang 文件）。

**新增分类**（在 `categories/` 下新建 `<id>.json`）：`{ "id", "name", "icon", "sort", "enabled" }`，其中 `icon` 为展示用物品 id（如 `minecraft:oak_log`）。

**增删流程**：
1. 在对应目录新建/编辑 JSON（无需改动 pack.mcmeta）。
2. 用 `python3 -m json.tool` 校验 JSON 合法性。
3. 服务器内 `/reload` 生效；商品 `id` 不得与现有商品重复。
4. 提交时按更新日志规则补写 EDITION。

## 更新日志同步规则

`Q群玩家文档/更新日志-20260806.html`（逐提交版）与根目录 `更新日志-20260806.md`（摘要版）是服务器更新日志，**必须在每次推拉远端时保持最新**：

- **push 前**：先检查自上次更新日志之后的新提交，把未记录的新提交追加进 HTML（按 `EDITION N` 编号递增、最新在上）并同步改写 md 摘要。
- **pull 后**：拉取远端后检查远端是否带了新的更新日志/提交，若更新日志落后于最新提交，同样补写；若远端已有他人更新的日志，则以远端为准，只补充本端缺失部分。
- HTML 版本格式：每个提交一个 `<article class="edition">`，编号最新最大（当前最新 101），含提交短哈希、时间戳、标题与要点；md 顶部为最新区间摘要，历史部分保留在下方。
- 提交时把更新日志与代码改动一起提交，并再确认一次日志已包含全部新提交。
