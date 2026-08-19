// 食韵筑家III：烟火长歌
// ============================================================
// 禁用合成清单（唯一维护入口）
// ------------------------------------------------------------
// 以后要禁用/放行某个物品：只改本文件，不要改 server/client 脚本。
// 本清单通过 global 同步到：
//   - server_scripts/disabled_craft_recipes.js  → 删除合成配方
//   - client_scripts/disabled_craft_tooltips.js → 物品上显示禁用说明
// 客户端整合包需同步整个 kubejs/ 目录，tooltip 才会显示。
// ============================================================

/**
 * @typedef {{ id: string, reason?: string }} DisabledCraftEntry
 * @type {DisabledCraftEntry[]}
 */
const DISABLED_CRAFT_ENTRIES = [
  // —— Create：自动建造、采矿、农业与高阶生产 ——
  { id: 'create:schematicannon', reason: '自动建造，替代建筑职业' },
  { id: 'create:crushing_wheel', reason: '大规模粉碎/资源处理' },
  { id: 'create:mechanical_drill', reason: '自动采矿' },
  { id: 'create:mechanical_harvester', reason: '自动收割' },
  { id: 'create:mechanical_plough', reason: '自动耕地' },
  { id: 'create:mechanical_crafter', reason: '自动合成，替代手工与作坊' },
  { id: 'create:steam_engine', reason: '高阶动力，偏重科技' },
  { id: 'create:hose_pulley', reason: '大规模流体搬运' },

  // —— Create：移动结构、自动交互与大规模作业 ——
  { id: 'create:mechanical_saw', reason: '自动伐木/切割' },
  { id: 'create:deployer', reason: '自动交互/摆放，可绕过操作限制' },
  { id: 'create:mechanical_arm', reason: '自动取放与产线全能手' },
  { id: 'create:mechanical_piston', reason: '移动结构核心' },
  { id: 'create:sticky_mechanical_piston', reason: '移动结构核心' },
  { id: 'create:rope_pulley', reason: '移动结构/升降' },
  { id: 'create:mechanical_bearing', reason: '旋转移动结构' },
  { id: 'create:clockwork_bearing', reason: '旋转移动结构' },
  { id: 'create:linear_chassis', reason: '移动结构底盘' },
  { id: 'create:secondary_linear_chassis', reason: '移动结构底盘' },
  { id: 'create:radial_chassis', reason: '移动结构底盘' },
  { id: 'create:gantry_shaft', reason: '龙门架移动结构' },
  { id: 'create:gantry_carriage', reason: '龙门架移动结构' },

  // —— Create：组装结构残余（P1，防止车载/移动仓库） ——
  { id: 'create:cart_assembler', reason: '矿车组装结构，易做流动仓储与移动产线' },
  { id: 'create:contraption_controls', reason: '移动结构控制' },
  { id: 'create:portable_storage_interface', reason: '移动结构/列车装卸物品接口' },
  { id: 'create:portable_fluid_interface', reason: '移动结构/列车装卸流体接口' },
  { id: 'create:mechanical_roller', reason: '大范围铺路/铲平，易改地形' },
  { id: 'create:minecart_coupling', reason: '矿车编组，配合组装结构扩大移动实体负担' },
  { id: 'create:controller_rail', reason: '可控动力轨道，偏移动结构物流' },

  // —— Create：列车整套（实体多、区块加载与计算开销大，养老服性能） ——
  { id: 'create:track', reason: '列车轨道，列车系统整体禁用' },
  { id: 'create:railway_casing', reason: '列车机壳' },
  { id: 'create:track_station', reason: '列车车站' },
  { id: 'create:track_signal', reason: '列车信号' },
  { id: 'create:track_observer', reason: '列车观测器' },
  { id: 'create:controls', reason: '列车控制台' },
  { id: 'create:schedule', reason: '列车时刻表' },
  // 注意：small_bogey / large_bogey / fake_track 没有物品形态（机壳点在轨道上生成），不能写进清单
  { id: 'create:train_door', reason: '列车门' },
  { id: 'create:train_trapdoor', reason: '列车活板门' },
  { id: 'create:incomplete_track', reason: '未完成轨道中间件' },
  { id: 'create:content_observer', reason: '智能侦测器，容器/状态检测，偏自动机' },

  // —— 节气 Ecliptic Seasons：本服仅保留视觉季节，禁用湿度/温室/红石玩法道具 ——
  // 保留装饰：风铃、纸风铃、竹风铃、风车、日历（纯展示/氛围）
  { id: 'eclipticseasons:spring_greenhouse_core', reason: '温室核心，作物季节系统已关' },
  { id: 'eclipticseasons:summer_greenhouse_core', reason: '温室核心，作物季节系统已关' },
  { id: 'eclipticseasons:autumn_greenhouse_core', reason: '温室核心，作物季节系统已关' },
  { id: 'eclipticseasons:winter_greenhouse_core', reason: '温室核心，作物季节系统已关' },
  { id: 'eclipticseasons:greenhouse_core_container', reason: '温室核心容器，作物季节系统已关' },
  { id: 'eclipticseasons:block_in_wooden_grate_block', reason: '湿度相关加湿器' },
  { id: 'eclipticseasons:dehumidifier', reason: '湿度通风/除湿' },
  { id: 'eclipticseasons:humidity_tank', reason: '湿度罐' },
  { id: 'eclipticseasons:hygrometer', reason: '湿度计（湿度玩法已关）' },
  { id: 'eclipticseasons:hyetometer', reason: '雨量计（本地化天气已关）' },
  { id: 'eclipticseasons:thermometer', reason: '温度计（温度玩法已关）' },
  { id: 'eclipticseasons:growth_detector', reason: '生长检测，作物季节系统已关' },
  { id: 'eclipticseasons:season_sensor', reason: '季节红石传感器，易做自动机' },
  { id: 'eclipticseasons:salt_wand', reason: '盐之杖，季节/天气交互道具' },
  { id: 'eclipticseasons:seasonal_prayer_scroll', reason: '季节祈愿卷轴' },
  { id: 'eclipticseasons:broom', reason: '扫帚，节气功能道具' },

  // —— 原版：限制自动合成与状态侦测红石 ——
  { id: 'minecraft:crafter', reason: '原版自动合成器，替代手工与作坊' },
  { id: 'minecraft:observer', reason: '侦测器，高频自动机核心' },
  { id: 'minecraft:sculk_sensor', reason: '幽匿感测体，振动/状态检测' },
  { id: 'minecraft:calibrated_sculk_sensor', reason: '校频幽匿感测体，精准状态检测' },

  // —— 原版补充：养老服易出问题的自动化/破坏 ——
  { id: 'minecraft:sticky_piston', reason: '粘性活塞，飞行器与高频机器核心' },
  { id: 'minecraft:tnt', reason: '炸药，破坏建筑' },
  { id: 'minecraft:tnt_minecart', reason: 'TNT 矿车，破坏建筑' },
  { id: 'minecraft:end_crystal', reason: '末地水晶，高爆破坏' },
  { id: 'minecraft:respawn_anchor', reason: '重生锚，主世界误爆风险' },

  // —— 潜影盒：全部颜色禁用（放入清单以同步 tooltip，替代原先的正则删除） ——
  { id: 'minecraft:shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:white_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:orange_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:magenta_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:light_blue_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:yellow_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:lime_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:pink_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:gray_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:light_gray_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:cyan_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:purple_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:blue_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:brown_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:green_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:red_shulker_box', reason: '潜影盒全部颜色禁用' },
  { id: 'minecraft:black_shulker_box', reason: '潜影盒全部颜色禁用' },

  // —— 正则 pattern 条目（匹配删除 + tooltip 同步生效） ——
  { pattern: /caverns_and_chasms:.*_potion/, reason: '不合适' },
  { pattern: /slashblade:.*/, reason: '内部测试' },
  { id: 'caverns_and_chasms:halt_rail', reason: '自动化内容禁用' },
  { id: 'caverns_and_chasms:spiked_rail', reason: '自动化内容禁用' },
  { id: 'caverns_and_chasms:slaughter_rail', reason: '自动化内容禁用' },
]

// 挂到 global，供 server / client 脚本读取
global.SYDisabledCraft = {
  /** 完整条目（含 reason） */
  entries: DISABLED_CRAFT_ENTRIES,
  /** 仅 id 列表（精确匹配），方便 forEach */
  ids: DISABLED_CRAFT_ENTRIES.filter(e => e.id).map(e => e.id),
  /** 正则 pattern 列表（匹配删除 + tooltip），如 /caverns_and_chasms:.*_potion/ */
  patterns: DISABLED_CRAFT_ENTRIES.filter(e => e.pattern).map(e => e.pattern),
  /** id 或 pattern -> reason */
  reasons: Object.fromEntries(
    DISABLED_CRAFT_ENTRIES.map(e => [e.id || String(e.pattern), e.reason || '服务器已禁用该物品的合成'])
  ),
  /** 默认 tooltip 标题与颜色说明（客户端用） */
  tooltipTitle: '§c✖ 禁止合成',
  tooltipHint: '§7食韵筑家 · 本周目已禁用此配方'
}

console.info(
  `[食韵筑家] 已注册禁用合成 ${global.SYDisabledCraft.ids.length} 项 + 正则 ${global.SYDisabledCraft.patterns.length} 条（startup）`
)
