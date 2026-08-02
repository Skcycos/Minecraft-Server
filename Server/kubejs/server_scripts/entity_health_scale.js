// 食韵筑家III：烟火长歌
// 生物生命倍率（InControl 的补充/兜底）
// - 敌对（monster）：随机 2.0 ~ 3.0 倍
// - 友好动物（creature/animal）：随机 5.0 ~ 8.0 倍
// 注意：已由 InControl spawn.json 处理的刷怪会先乘固定倍率；
// 若两边同时启用可能叠乘。默认仅在未检测到 InControl 标记时生效。
// 当前策略：只用 InControl 固定倍率；本脚本默认关闭。
// 若要启用随机倍率：把 ENABLE 改为 true，并把 incontrol/spawn.json 改回 []。

const ENABLE = false

EntityEvents.spawned(event => {
  if (!ENABLE) return

  const entity = event.entity
  if (!entity || entity.player) return
  if (entity.living !== true && typeof entity.isAlive === 'function' && !entity.isAlive()) return

  let cat = ''
  try {
    cat = String(entity.entityType.category).toLowerCase()
  } catch (e1) {
    try {
      cat = String(entity.type.category).toLowerCase()
    } catch (e2) {
      return
    }
  }

  let mult = null
  if (cat.includes('monster')) {
    mult = 2.0 + Math.random() * 1.0
  } else if (cat.includes('creature') || cat.includes('animal')) {
    mult = 5.0 + Math.random() * 3.0
  }
  if (mult === null) return

  const oldMax = entity.maxHealth
  if (!oldMax || oldMax <= 0) return
  const newMax = oldMax * mult

  if (typeof entity.setMaxHealth === 'function') {
    entity.setMaxHealth(newMax)
  } else if (typeof entity.setAttributeBaseValue === 'function') {
    entity.setAttributeBaseValue('minecraft:generic.max_health', newMax)
  } else {
    return
  }

  if (typeof entity.setHealth === 'function') {
    entity.setHealth(newMax)
  } else {
    entity.health = newMax
  }
})
