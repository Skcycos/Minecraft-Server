// 食韵筑家III：烟火长歌
// 服务器树苗与种子导出工具（OP 权限 2 级）
//
// 用法：
//   /exportsaplingsseeds
//
// 输出：kubejs/exports/saplings_and_seeds_<ISO时间戳>.json
//
// 说明：遍历服务器当前注册的全部物品，按照常见的树苗/种子物品标签筛选。
// 同一个物品可能同时命中多个标签，导出时只保留一条记录并列出全部命中标签。

var EXPORT_DIR = 'kubejs/exports'
var REQUIRED_PERMISSION_LEVEL = 2

var ITEM_TAG_IDS = [
  'minecraft:saplings',
  'minecraft:villager_plantable_seeds',
  'c:saplings',
  'c:seeds',
  'forge:saplings',
  'forge:seeds',
  'neoforge:saplings',
  'neoforge:seeds',
  'tconstruct:seeds'
]

function createItemTags() {
  var TagKey = Java.loadClass('net.minecraft.tags.TagKey')
  var Registries = Java.loadClass('net.minecraft.core.registries.Registries')
  var ResourceLocation = Java.loadClass('net.minecraft.resources.ResourceLocation')
  var tags = []

  ITEM_TAG_IDS.forEach(function (id) {
    try {
      tags.push({
        id: id,
        key: TagKey.create(Registries.ITEM, ResourceLocation.parse(id)),
        ingredient: Ingredient.of('#' + id)
      })
    } catch (e) {
      console.warn('[SaplingSeedExport] 创建标签失败：' + id + ' -> ' + e)
    }
  })

  return tags
}

function getItemName(item) {
  try {
    var ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack')
    return new ItemStack(item).getHoverName().getString()
  } catch (e) {
    try { return String(item.getDescriptionId()) } catch (ignored) { return '' }
  }
}

ServerEvents.commandRegistry(event => {
  var Commands = event.commands
  event.register(
    Commands.literal('exportsaplingsseeds')
      .requires(function (source) { return source.hasPermission(REQUIRED_PERMISSION_LEVEL) })
      .executes(function (ctx) {
        var source = ctx.source
        var player = null

        try {
          player = source.player
          if (!player) {
            source.fail('§c仅玩家可执行 /exportsaplingsseeds')
            return 0
          }

          var BuiltInRegistries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries')
          var ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack')
          var itemRegistry = BuiltInRegistries.ITEM
          var tags = createItemTags()
          var keys = itemRegistry.keySet().toArray()
          var items = []

          for (var i = 0; i < keys.length; i++) {
            var resourceLocation = keys[i]
            var item = itemRegistry.get(resourceLocation)
            if (!item) {
              continue
            }

            var stack = new ItemStack(item)
            var matchedTags = []
            tags.forEach(function (tag) {
              try {
                // Ingredient 的标签匹配由 KubeJS 处理，兼容 NeoForge 的动态标签。
                if (tag.ingredient.test(stack)) {
                  matchedTags.push(tag.id)
                }
              } catch (e) {
                // 兼容少数环境中 Ingredient.test 不可用的情况。
                try {
                  if (stack.is(tag.key)) {
                    matchedTags.push(tag.id)
                  }
                } catch (ignored) {
                  // 某些模组物品可能在标签检查时抛出异常，不影响其他物品导出。
                }
              }
            })

            if (matchedTags.length === 0) {
              continue
            }

            var id = resourceLocation.toString()
            items.push({
              id: id,
              mod: resourceLocation.getNamespace(),
              name: getItemName(item),
              tags: matchedTags
            })
          }

          items.sort(function (a, b) { return a.id < b.id ? -1 : a.id > b.id ? 1 : 0 })

          var byTag = {}
          items.forEach(function (entry) {
            entry.tags.forEach(function (tag) { byTag[tag] = (byTag[tag] || 0) + 1 })
          })

          var exportData = {
            exportedAt: new Date().toISOString(),
            tagSources: ITEM_TAG_IDS,
            total: items.length,
            byTag: byTag,
            items: items
          }

          var fileName = 'saplings_and_seeds_' + exportData.exportedAt.replace(/[:.]/g, '-') + '.json'
          var filePath = EXPORT_DIR + '/' + fileName
          JsonIO.write(filePath, exportData)

          player.tell('§a已导出 §e' + items.length + ' §a个树苗/种子物品！ §7' + filePath)
          console.info('[SaplingSeedExport] 共 ' + items.length + ' 个 -> ' + filePath)
          return 1
        } catch (error) {
          if (player) {
            player.tell('§c树苗和种子导出失败，请查看 kubejs 日志。')
          }
          console.error('[SaplingSeedExport] 导出失败：' + error)
          return 0
        }
      })
  )
})
