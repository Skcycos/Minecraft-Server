// 食韵筑家III：烟火长歌
// ============================================================
// 禁止玩家持有时间冻结（runiclib:chronos）
// ------------------------------------------------------------
// 每刻检查玩家状态，发现即移除并提示。
// 仅作用于玩家；若后续需要同时清理生物，
// 可在此文件追加 LivingEntity 逻辑。
// ============================================================

var CHRONOS_ID = 'runiclib:chronos'
var NOTIFY_TAG = 'SYChronosBlocked'

PlayerEvents.tick(event => {
  var player = event.player

  try {
    if (!player.hasEffect(CHRONOS_ID)) {
      // 无效果时重置提醒标记（下次获得会再提示一次）
      if (player.persistentData.getBoolean(NOTIFY_TAG)) {
        player.persistentData.putBoolean(NOTIFY_TAG, false)
      }
      return
    }

    player.removeEffect(CHRONOS_ID)

    if (!player.persistentData.getBoolean(NOTIFY_TAG)) {
      player.persistentData.putBoolean(NOTIFY_TAG, true)
      player.tell('§c✖ 时间冻结已被服务器禁用')
    }
  } catch (e) {
    console.error('[ChronosBlock] 处理失败: ' + e)
  }
})
