# TCTH Integration

一个可配置、数据驱动的 **NeoForge 模组联动框架**，为烹饪、职业、悬赏、
料理订单和经济模组提供统一事件与兼容机制。

> **状态：** 预发布 / 开发中。公共 API 稳定性尚未承诺。参见
> [CHANGELOG.md](CHANGELOG.md)。

---

## 环境要求

| 依赖 | 版本 | 类型 |
|---|---|---|
| Minecraft | 1.21.1 | 必需 |
| NeoForge | 21.1.247 | 必需 |
| Java | 21 | 必需（toolchain） |

## 支持的模组

所有第三方联动都是**可选**的：缺少其中任何一个，TCTH 都能正常启动运行。
兼容模块仅在目标模组存在时才被加载。

| 模组 | Mod ID | 已验证版本 | 状态 |
|---|---|---|---|
| Jobs+ | `jobsplus` | 9.0.0 | 规划中 |
| Arc | `arc` | 9.0.0 | 规划中 |
| Kaleidoscope Cookery | `kaleidoscope_cookery` | 1.4.1 | 规划中 |
| Farmer's Delight | `farmersdelight` | 1.3.2 | 规划中 |
| Bountiful | `bountiful` | 8.0.0-beta.2 | 规划中 |
| Order to Cook | `ordertocook` | 1.3.5 | 规划中 |
| Lightman's Currency | `lightmanscurrency` | 2.3.0.5 | 规划中 |
| Kaleidoscope Compat | `kaleidoscope_compat` | 2.9.7 | 规划中 |

目前尚未实现任何联动功能；上表列出的是联动目标及在测试服务器上核对过的
精确版本。功能将逐模块落地，并在文档中如实登记。

## 安装方法

1. 安装 Minecraft 1.21.1 与 NeoForge **21.1.247**（或更高兼容版本）。
2. 将 `tcth-0.1.0.jar`（或当前版本）放入 `mods/` 文件夹。
3. 启动一次服务器/游戏，生成默认配置文件
   `config/tcth-common.toml`。

发布包中**不会捆绑**任何第三方模组 JAR，也不要求额外复制第三方 JAR。

## 配置方法

首次启动后编辑 `config/tcth-common.toml`：

- `enabled` — 整个框架的总开关。自阶段 1A 起由统一发布入口**机械保证**：关闭时
  料理完成事件分发器不发布任何事件。兼容模块也应在执行业务逻辑前检查它
  （或其自身开关）。

后续阶段新增的每个联动功能都会拥有独立开关，可单独关闭任意集成。

**奖励开关语义**：`jobsPlusRewardsEnabled` 控制是否发送
`tcth:on_dish_cooked` 料理 Action。它**不**控制预设的 `taste_meal`
Action——那是独立的 `arc:on_eat` Action：只要 `tcth-chef` 数据包启用，
食用 `#tcth:chef_meals` 就会获得 1 XP，即使
`jobsPlusRewardsEnabled=false`。零奖励演练期间不得进食这些料理。

## 源码构建

```bash
./gradlew clean build
```

构建产物位于 `build/libs/`。

### 仅开发用的第三方依赖

开发期间，第三方模组 JAR 可放入 `libs/`，以 `compileOnly` / `localRuntime`
方式接入，仅用于本地编译与测试，**绝不**进入发布 JAR。

公开 CI 环境下，编译期第三方依赖必须从其发布的 Maven 仓库获取（见下方
CI 依赖获取方案），保证在没有本地 `Minecraft-Server` 目录的机器上
`./gradlew clean build` 也能成功。

### CI 依赖获取方案

GitHub Actions 在干净的运行器上构建，**没有本地 `Server/mods/`，也没有
`libs/`**，因此每个编译期依赖都必须能从已发布、可重复的来源获取。规则如下：

- 源码直接引用的每个依赖以 `compileOnly` 声明（仅 API 表面），完整模组只加入
  `localRuntime` 供本地测试。**javac 仍然需要源码引用的每一个第三方类型**——
  反射 / `ModList` 守卫加载只解决运行时的可选依赖隔离，不能免除编译期依赖。
- 每个兼容模块开发之前，必须先固定并验证可重复的获取来源（官方 Maven 仓库如
  `https://maven.modrinth.com`、模组作者自建 Maven，或精确测试版本的 Maven
  发布物）。
- 若某模组没有可用的公开 Maven 构件，必须先提交具体方案：要么提供 CI 兼容的
  获取步骤（例如从固定 URL 下载精确 JAR 作为 flat-dir 依赖），要么采用严格的
  接口隔离设计，让第三方类型完全不进入被编译的源码。**"用反射即可解决"不能
  替代构建期依赖方案。**
- CI 中直接下载第三方 JAR 必须遵守对应项目的许可证与再分发规则。第三方模组
  JAR **绝不**提交到本仓库，也**绝不**打包进 TCTH 发布 JAR。

## 链接

- 主页 / 源码仓库 / Issue Tracker：**待定** —— 公开 GitHub 仓库建立后再填写，
  不使用占位 URL。

## 许可证

本项目采用 [MIT License](LICENSE)。Copyright (c) 2026 Tanrunn。NeoForged MDK
模板原许可证保留在 [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt)。

> 许可证状态为**暂定**：公开发布前将由项目所有者最终确认。

---

English version: [README.md](README.md)。
