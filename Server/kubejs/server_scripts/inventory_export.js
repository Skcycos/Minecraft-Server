// 食韵筑家III：烟火长歌
// 玩家背包导出工具（OP 权限 2 级）
//
// 用法（数据包加载完成后）：
//   /exportinv
//
// 输出：kubejs/inventory_exports/<玩家名>_<ISO时间戳>.json
//
// 说明：仅导出原版 Player Inventory（快捷栏 + 主背包 + 护甲 + 副手），
//       不含末影箱 / Curios / 模组背包 / 其他 Capability 额外物品栏。
// 保留 componentString 与 toItemString()，可完整还原附魔、耐久、自定义名称等
// 1.21 Data Components 信息。

var EXPORT_DIR = 'kubejs/inventory_exports'
var REQUIRED_PERMISSION_LEVEL = 2

ServerEvents.commandRegistry(event => {
  var Commands = event.commands
  event.register(
    Commands.literal('exportinv')
      .requires(function (source) { return source.hasPermission(REQUIRED_PERMISSION_LEVEL) })
      .executes(function (ctx) {
        var source = ctx.source
        var player = null

        try {
          player = source.player
          if (!player) {
            source.fail('§c仅玩家可执行 /exportinv')
            return 0
          }

          var inv = player.inventory
          var items = []

          var pushSlot = function (slot, name, stack) {
            var has = stack && !stack.isEmpty()
            var id = has ? stack.id : 'minecraft:air'
            var itemString = ''
            var componentString = ''
            if (has) {
              try { itemString = stack.toItemString() } catch (e) { itemString = id }
              try { componentString = stack.componentString } catch (e) { componentString = '' }
            }
            items.push({
              slot: slot,
              slotName: name,
              id: id,
              count: has ? stack.count : 0,
              itemString: itemString,
              components: componentString
            })
          }

          // 快捷栏 0-8 + 主背包 9-35
          for (var i = 0; i < 36; i++) {
            var name = i < 9 ? 'hotbar_' + i : 'inventory_' + i
            pushSlot(i, name, inv.items[i])
          }
          // 护甲 100-103（头盔/胸甲/护腿/靴子）
          var armorNames = ['helmet', 'chestplate', 'leggings', 'boots']
          for (var j = 0; j < 4; j++) {
            pushSlot(100 + j, armorNames[j] + '_' + (100 + j), inv.armor[j])
          }
          // 副手 40
          pushSlot(40, 'offhand_40', inv.offhand[0])

          var exportData = {
            player: {
              name: player.username || player.name || 'unknown',
              uuid: String(player.uuid)
            },
            exportedAt: new Date().toISOString(),
            inventorySize: items.length,
            items: items
          }

          var fileName = exportData.player.name + '_' + exportData.exportedAt.replace(/[:.]/g, '-') + '.json'
          var filePath = EXPORT_DIR + '/' + fileName

          JsonIO.write(filePath, exportData)

          player.tell('§a背包已导出！ §7' + filePath)
          console.info('[InventoryExport] ' + exportData.player.name + ' -> ' + filePath)
          return 1
        } catch (error) {
          if (player) {
            player.tell('§c背包导出失败，请查看 kubejs 日志。')
          }
          console.error('[InventoryExport] 导出失败: ' + error)
          return 0
        }
      })
  )
})