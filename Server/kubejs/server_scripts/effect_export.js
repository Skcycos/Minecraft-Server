// 食韵筑家III：烟火长歌
// 全服状态效果（Buff/MobEffect）注册表导出工具（OP 权限 2 级）
//
// 用法：
//   /exporteffects
//
// 输出：kubejs/exports/mob_effects_<ISO时间戳>.json
//
// 说明：遍历原版 MOB_EFFECT 注册表，包含原版与全部模组新增效果。
//       每条记录：id / 来源模组(namespace) / 类别(beneficial|harmful|neutral) /
//       颜色(color) / 显示名(name)。

var EXPORT_DIR = 'kubejs/exports'
var REQUIRED_PERMISSION_LEVEL = 2

ServerEvents.commandRegistry(event => {
  var Commands = event.commands
  event.register(
    Commands.literal('exporteffects')
      .requires(function (source) { return source.hasPermission(REQUIRED_PERMISSION_LEVEL) })
      .executes(function (ctx) {
        var source = ctx.source
        var player = null

        try {
          player = source.player
          if (!player) {
            source.fail('§c仅玩家可执行 /exporteffects')
            return 0
          }

          var BuiltInRegistries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries')
          var reg = BuiltInRegistries.MOB_EFFECT

          var keys = reg.keySet().toArray()
          var effects = []

          for (var i = 0; i < keys.length; i++) {
            var rl = keys[i]
            try {
              var eff = reg.get(rl)
              var category = ''
              var color = 0
              var name = ''
              try { category = String(eff.getCategory()) } catch (e) {}
              try { color = eff.getColor() } catch (e) {}
              try { name = eff.getDisplayName().getString() } catch (e) {}

              effects.push({
                id: rl.toString(),
                mod: rl.getNamespace(),
                name: name,
                category: category,
                color: '0x' + ('000000' + ((color & 0xFFFFFF).toString(16)).toUpperCase()).slice(-6)
              })
            } catch (e) {
              effects.push({ id: rl.toString(), mod: rl.getNamespace(), error: String(e) })
            }
          }

          effects.sort(function (a, b) { return a.id < b.id ? -1 : a.id > b.id ? 1 : 0 })

          // 统计来源模组分布
          var byMod = {}
          effects.forEach(function (e) { byMod[e.mod] = (byMod[e.mod] || 0) + 1 })

          var exportData = {
            exportedAt: new Date().toISOString(),
            total: effects.length,
            byMod: byMod,
            effects: effects
          }

          var fileName = 'mob_effects_' + exportData.exportedAt.replace(/[:.]/g, '-') + '.json'
          var filePath = EXPORT_DIR + '/' + fileName

          JsonIO.write(filePath, exportData)

          player.tell('§a已导出 §e' + effects.length + ' §a个状态效果！ §7' + filePath)
          console.info('[EffectExport] 共 ' + effects.length + ' 个 -> ' + filePath)
          return 1
        } catch (error) {
          if (player) {
            player.tell('§c状态效果导出失败，请查看 kubejs 日志。')
          }
          console.error('[EffectExport] 导出失败: ' + error)
          return 0
        }
      })
  )
})
