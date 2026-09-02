// 食韵筑家III：烟火长歌
// 根据 startup 中的 global.SYDisabledCraft 删除合成配方
// 维护清单请改：startup_scripts/disabled_craft_registry.js

ServerEvents.recipes(event => {
  const registry = global.SYDisabledCraft
  if (!registry || (!registry.ids && !registry.patterns)) {
    console.warn('[食韵筑家] SYDisabledCraft 为空，跳过配方禁用')
    return
  }

  let removed = 0
  let skipped = 0

  // 精确 id
  ;(registry.ids || []).forEach(id => {
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

  // 正则 pattern（如带 NBT 的 potion 变体）
  ;(registry.patterns || []).forEach(pattern => {
    try {
      event.remove({ output: pattern })
      removed++
    } catch (e) {
      console.warn(`[食韵筑家] 正则配方删除失败: ${pattern} → ${e}`)
      skipped++
    }
  })

  console.info(`[食韵筑家] 禁用合成输出：处理 ${removed} 项，跳过 ${skipped} 项`)

  // —— Easy Mob Farm：全部农场 T0 合成禁用（T0 与 T1~3 是同一物品，只能按配方删） ——
  ;[
    'easy_mob_farm:mob_farm/animal_plains_farm/tier0_animal_plains_farm',
    'easy_mob_farm:mob_farm/bee_hive_farm/tier0_bee_hive_farm',
    'easy_mob_farm:mob_farm/desert_farm/tier0_desert_farm',
    'easy_mob_farm:mob_farm/iron_golem_farm/tier0_iron_golem_farm',
    'easy_mob_farm:mob_farm/jungle_farm/tier0_jungle_farm',
    'easy_mob_farm:mob_farm/monster_plains_cave/tier0_monster_plains_cave_farm',
    'easy_mob_farm:mob_farm/nether_fortress_farm/tier0_nether_fortress_farm',
    'easy_mob_farm:mob_farm/ocean_farm/tier0_ocean_farm',
    'easy_mob_farm:mob_farm/swamp_farm/tier0_swamp_farm',
    'easy_mob_farm:mob_farm/lucky_drop_farm/tier0_lucky_drop_farm',
  ].forEach(recipeId => event.remove({ id: recipeId }))
})
