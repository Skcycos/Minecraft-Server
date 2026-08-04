# 配方表生成脚本

> 这些脚本用于从模组 jar 提取配方与食物属性,生成收纳目录 `配方与经济管理/` 下的三个配方表:
> `配方与经济管理/农夫乐事配方表/`、`配方与经济管理/万花筒烹饪配方表/`、`配方与经济管理/统一配方表/`。
> 脚本位于 `配方与经济管理/统一配方表/scripts/`,可在本目录直接运行(`python3 <脚本>.py`)。

## 依赖

- Python 3(jdk 自带 `javap` 命令,用于食物属性解析)
- 三个模组 jar 已解压到 `/tmp/fd_recipe/`(可通过环境变量 `FD_JAR_DIR` 覆盖):
  - `FD_JAR_DIR/data/farmersdelight/` ← `[农夫乐事]FarmersDelight-1.21.1-1.3.2.jar`
  - `FD_JAR_DIR/kc/` ← `[万花筒烹饪]kaleidoscopecookery-1.4.1-neoforge+mc1.21.1.jar`
  - `FD_JAR_DIR/kc_compat/` ← `kaleidoscope_compat-2.9.7-neoforge+mc1.21.1.jar`
  - 解压参考命令:
    ```bash
    mkdir -p /tmp/fd_recipe && cd /tmp/fd_recipe
    unzip -o "Server/mods/[农夫乐事]FarmersDelight-1.21.1-1.3.2.jar" "data/farmersdelight/*" "assets/farmersdelight/lang/zh_cn.json"
    unzip -o "Server/mods/[万花筒烹饪]kaleidoscopecookery-1.4.1-neoforge+mc1.21.1.jar" -d kc "data/kaleidoscope_cookery/*" "assets/kaleidoscope_cookery/lang/zh_cn.json" "com/github/ysbbbbbb/kaleidoscopecookery/init/*" "com/github/ysbbbbbb/kaleidoscopecookery/item/*" "com/github/ysbbbbbb/kaleidoscopecookery/effect/*"
    unzip -o "Server/mods/kaleidoscope_compat-2.9.7-neoforge+mc1.21.1.jar" -d kc_compat "packs/*" "com/bmt/kaleidoscope_compat/*"
    ```

## 脚本一览(按运行顺序)

| 脚本 | 作用 | 输出 |
|---|---|---|
| `parse.py` | 解析农夫乐事全部配方 → CSV | `农夫乐事配方表/farmersdelight_recipes.csv`、`_work/recipes_raw.json` |
| `gen_md.py` | 由配方数据生成 Markdown 分表 | `农夫乐事配方表/0{1,2,3}-*.md` |
| `kc_parse.py` | 解析万花筒烹饪全部配方 → CSV | `万花筒烹饪配方表/kaleidoscope_cookery_recipes.csv` |
| `parse_food.py` | 从字节码提取食物属性(饥饿/饱和/效果) | `_work/food_props.json` |
| `gen_unite_diff.py` | 对比 UNITE 覆盖 vs 原始配方 | `农夫乐事配方表/farmersdelight_unite_diff.csv` |
| `gen_unified_csv.py` | 合并两模组配方 + 应用 UNITE + 附食物属性 | `统一配方表/统一配方总表.csv` |
| `sync_bounty_economy.py` | 应用悬赏经济锚点、职业隔离与金币上限，同步 Tooltip 注册表并校验 | `Server/config/bountiful/`、`菜品悬赏定价表.csv`、`bounty_food_registry.js` |

## 说明

- `_work/` 为中间产物(food_props.json、recipes_raw.json),可删除,运行脚本会重新生成。
- 所有输出路径基于脚本目录向上两级 = `配方与经济管理/`(即三个配方表目录所在的收纳根);`JARROOT` 默认 `/tmp/fd_recipe`,可用 `FD_JAR_DIR` 环境变量指向其他解压位置。
- 若模组升级,重新解压 jar 后重跑对应脚本即可(先 `parse_food.py` 再 `gen_unified_csv.py`)。
- 食物属性提取依赖 `javap`(JDK 自带);效果中文名映射与物品中文映射在脚本内维护,新版本物品缺失时脚本会回退显示原始 ID,便于发现补录。
- 数据准确性说明见 `../README.md` 与 `农夫乐事配方表/00-UNITE统一分析.md`。
- 悬赏经济改价后运行 `python3 sync_bounty_economy.py`；它不会替代服务器完整重启和 `/bo sample` 游戏内抽样。
