// 食韵筑家III：烟火长歌
// Create 首发限制：保留食品预加工、厨房物流、展示与公共交通，
// 禁用会替代职业分工、大规模获取资源或自动完成建筑的设备合成。

ServerEvents.recipes(event => {
  const disabledCreateItems = [
    // 自动建造、采矿、农业与高阶生产
    'create:schematicannon',
    'create:crushing_wheel',
    'create:mechanical_drill',
    'create:mechanical_harvester',
    'create:mechanical_plough',
    'create:mechanical_crafter',
    'create:steam_engine',
    'create:hose_pulley',

    // 移动结构、自动交互与大规模作业
    'create:mechanical_saw',
    'create:deployer',
    'create:mechanical_arm',
    'create:mechanical_piston',
    'create:sticky_mechanical_piston',
    'create:rope_pulley',
    'create:mechanical_bearing',
    'create:clockwork_bearing',
    'create:linear_chassis',
    'create:secondary_linear_chassis',
    'create:radial_chassis',
    'create:gantry_shaft',
    'create:gantry_carriage'
  ]

  disabledCreateItems.forEach(item => {
    event.remove({ output: item })
  })
})
