// 食韵筑家III：烟火长歌
// 拔刀剑新手赠刀保护
//
// 带有 starter_blade 标记的 slashblade:slashblade 只能作为玩家的成长用刀，
// 不允许放入玩家背包或工作台的合成输入栏参与其他拔刀剑的合成。

var STARTER_BLADE_ID = 'slashblade:slashblade'
var STARTER_MARKER = 'starter_blade'
var LEGACY_GIFT_TEXT = '新手赠礼'

function isStarterBlade(stack) {
  if (!stack || stack.isEmpty() || stack.id !== STARTER_BLADE_ID) {
    return false
  }

  // 新版赠刀使用 minecraft:custom_data 标记。
  try {
    if (stack.customData && stack.customData.getBoolean(STARTER_MARKER)) {
      return true
    }
  } catch (e) {
    // 兼容没有 customData 访问器的环境，继续检查旧赠刀格式。
  }

  // 兼容此前只带“新手赠礼”Lore、没有 starter_blade 标记的赠刀。
  try {
    var itemString = stack.toItemString()
    return itemString && itemString.indexOf(LEGACY_GIFT_TEXT) >= 0
  } catch (e) {
    return false
  }
}

function getCraftingInputSize(menu) {
  if (!menu || !menu.getClass) {
    return 0
  }

  var menuName = String(menu.getClass().getSimpleName())
  if (menuName === 'InventoryMenu') {
    return 4
  }
  if (menuName === 'CraftingMenu') {
    return 9
  }
  return 0
}

PlayerEvents.tick(event => {
  var player = event.player

  try {
    var menu = player.containerMenu
    var inputSize = getCraftingInputSize(menu)
    if (inputSize <= 0) {
      return
    }

    for (var index = 0; index < inputSize; index++) {
      var slot = menu.getSlot(index)
      var stack = slot.getItem()
      if (!isStarterBlade(stack)) {
        continue
      }

      var removed = slot.remove(stack.getCount())
      if (removed && !removed.isEmpty()) {
        player.give(removed)
        player.tell('§c新手赠礼不可用于锻造其他拔刀剑。§7请用它战斗、收集耀魂，让它自己成长。')
      }
    }
  } catch (e) {
    console.error('[StarterBladeGuard] 处理合成输入栏失败: ' + e)
  }
})

console.info('[食韵筑家] 已加载拔刀剑新手赠刀合成保护')
