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
