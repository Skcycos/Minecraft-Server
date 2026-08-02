// 食韵筑家III：烟火长歌
// 根据 startup 中的 global.SYDisabledCraft 删除合成配方
// 维护清单请改：startup_scripts/disabled_craft_registry.js

ServerEvents.recipes(event => {
  const registry = global.SYDisabledCraft
  if (!registry || !registry.ids || registry.ids.length === 0) {
    console.warn('[食韵筑家] SYDisabledCraft 为空，跳过配方禁用')
    return
  }

  let removed = 0
  let skipped = 0

  registry.ids.forEach(id => {
    try {
      // 无物品形态时跳过，避免异常
      if (Item.of(id).isEmpty()) {
        console.warn(`[食韵筑家] 跳过无物品 ID（配方）: ${id}`)
        skipped++
        return
      }
    } catch (e) {
      console.warn(`[食韵筑家] 跳过无效 ID（配方）: ${id}`)
      skipped++
      return
    }

    event.remove({ output: id })
    removed++
  })

  console.info(`[食韵筑家] 禁用合成输出：处理 ${removed} 项，跳过 ${skipped} 项`)
})
