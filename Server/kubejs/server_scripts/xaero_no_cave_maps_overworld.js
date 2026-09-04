// 主世界自动给予 Xaero 世界地图的禁止洞穴地图效果。
// 每 200 tick（约 10 秒）检查一次：
// - 主世界：保持无限时长的 xaeroworldmap:no_cave_maps
// - 其他维度：移除该效果

var XAERO_NO_CAVE_MAPS_EFFECTS = [
  'xaeroworldmap:no_cave_maps',
  'xaerominimap:no_cave_maps'
]
var CHECK_INTERVAL_TICKS = 200
var INFINITE_DURATION = -1

PlayerEvents.tick(event => {
  var player = event.player

  // 使用玩家 tickCount 定时检查，避免每个 tick 都操作药水效果。
  if (player.tickCount % CHECK_INTERVAL_TICKS !== 0) return

  try {
    if (player.level.isOverworld()) {
      // 同时限制世界地图和小地图的洞穴地图；重复添加用于恢复被移除的效果。
      XAERO_NO_CAVE_MAPS_EFFECTS.forEach(effectId => {
        player.potionEffects.add(effectId, INFINITE_DURATION, 0, false, false)
      })
    } else {
      XAERO_NO_CAVE_MAPS_EFFECTS.forEach(effectId => {
        if (player.hasEffect(effectId)) {
          player.removeEffect(effectId)
        }
      })
    }
  } catch (error) {
    console.error('[XaeroNoCaveMaps] 处理玩家效果失败: ' + error)
  }
})
