// 食韵筑家III：烟火长歌
// 客户端：为禁用合成的物品添加描述
// 维护清单请改：startup_scripts/disabled_craft_registry.js
// 需把本 kubejs 目录同步到客户端整合包，否则看不到 tooltip

ItemEvents.modifyTooltips(event => {
  const registry = global.SYDisabledCraft
  if (!registry || (!registry.ids && !registry.patterns)) {
    console.warn('[食韵筑家] SYDisabledCraft 为空，跳过禁用合成 tooltip')
    return
  }

  const title = registry.tooltipTitle || '§c✖ 禁止合成'
  const hint = registry.tooltipHint || '§7服务器已禁用该物品的合成'
  const reasons = registry.reasons || {}

  let ok = 0
  let skipped = 0

  const makeLines = reason => {
    const lines = [
      Text.of(''),
      Text.of(title)
    ]
    if (reason) {
      lines.push(Text.of(`§8原因：§7${reason}`))
    }
    lines.push(Text.of(hint))
    return lines
  }

  // 精确 id
  ;(registry.ids || []).forEach(id => {
    // 无物品形态的方块（如 create 转向架）会在 Ingredient 解析时报错，必须跳过
    try {
      if (Item.of(id).isEmpty()) {
        console.warn(`[食韵筑家] 跳过无物品 ID（tooltip）: ${id}`)
        skipped++
        return
      }
    } catch (e) {
      console.warn(`[食韵筑家] 跳过无效 ID（tooltip）: ${id} → ${e}`)
      skipped++
      return
    }

    try {
      event.add(id, makeLines(reasons[id]))
      ok++
    } catch (e) {
      console.warn(`[食韵筑家] 无法为 ${id} 添加 tooltip: ${e}`)
      skipped++
    }
  })

  // 正则 pattern（正则无法用 Item.of 校验，直接作为 Ingredient 添加）
  ;(registry.patterns || []).forEach(pattern => {
    const key = String(pattern)
    try {
      event.add(pattern, makeLines(reasons[key]))
      ok++
    } catch (e) {
      console.warn(`[食韵筑家] 无法为正则 ${pattern} 添加 tooltip: ${e}`)
      skipped++
    }
  })

  console.info(`[食韵筑家] 禁用合成 tooltip：成功 ${ok}，跳过 ${skipped}`)
})
