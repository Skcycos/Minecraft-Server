// 食韵筑家III：烟火长歌
// 客户端：悬赏食物显示档位与收购单价
// 清单：startup_scripts/bounty_food_registry.js
// 需同步整个 kubejs/ 到客户端整合包

ItemEvents.modifyTooltips(event => {
  const registry = global.SYBountyFood
  if (!registry || !registry.entries || registry.entries.length === 0) {
    console.warn('[食韵筑家] SYBountyFood 为空，跳过悬赏 tooltip')
    return
  }

  const title = registry.tooltipTitle || '§6★ 悬赏收购'
  const hint = registry.tooltipHint || '§7可在告示板悬赏中交付收购'
  let ok = 0
  let skipped = 0

  const tierColor = {
    T1: '§a',
    T2: '§e',
    T3: '§6'
  }

  registry.entries.forEach(e => {
    const id = e.id
    try {
      if (Item.of(id).isEmpty()) {
        console.warn(`[食韵筑家] 跳过无物品 ID（悬赏 tip）: ${id}`)
        skipped++
        return
      }
    } catch (err) {
      console.warn(`[食韵筑家] 跳过无效 ID（悬赏 tip）: ${id}`)
      skipped++
      return
    }

    const c = tierColor[e.tier] || '§7'
    const lines = [
      Text.of(''),
      Text.of(title),
      Text.of(`${c}档位：${e.tier} · ${e.tierName}`),
      Text.of(`§7收购价：§f${e.unitWorth} §7铜币/个`),
      Text.of(`§8常见数量：${e.amountMin}～${e.amountMax}`)
    ]
    lines.push(Text.of(hint))

    try {
      event.add(id, lines)
      ok++
    } catch (err) {
      console.warn(`[食韵筑家] 无法为 ${id} 添加悬赏 tip: ${err}`)
      skipped++
    }
  })

  console.info(`[食韵筑家] 悬赏食物 tooltip：成功 ${ok}，跳过 ${skipped}`)
})
