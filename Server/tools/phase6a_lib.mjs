/**
 * 阶段 6A.1：纯逻辑库（可被审计脚本与测试共同 import）
 * 不含文件系统副作用。
 */

export function parseRecipeEntryPath(entry) {
  // 必须先匹配 packs/<pack>/data/...，避免被通用 data/ 正则吞掉 pack
  const packMatch = entry.match(
    /^packs\/([^/]+)\/data\/([^/]+)\/recipe\/(.+)\.json$/
  )
  if (packMatch) {
    return {
      pack: packMatch[1],
      namespace: packMatch[2],
      path: packMatch[3],
      recipeId: `${packMatch[2]}:${packMatch[3]}`
    }
  }
  const baseMatch = entry.match(/^data\/([^/]+)\/recipe\/(.+)\.json$/)
  if (baseMatch) {
    return {
      pack: '',
      namespace: baseMatch[1],
      path: baseMatch[2],
      recipeId: `${baseMatch[1]}:${baseMatch[2]}`
    }
  }
  // 某些 jar 可能带前缀目录
  const nestedPack = entry.match(
    /(?:^|\/)packs\/([^/]+)\/data\/([^/]+)\/recipe\/(.+)\.json$/
  )
  if (nestedPack && !entry.includes('/advancement/')) {
    // 若同时含 packs 与 data，以 packs 优先（已在上面处理绝对 packs）
    // 仅当路径中间出现 packs/ 时
    if (entry.includes('/packs/')) {
      return {
        pack: nestedPack[1],
        namespace: nestedPack[2],
        path: nestedPack[3],
        recipeId: `${nestedPack[2]}:${nestedPack[3]}`
      }
    }
  }
  const nestedBase = entry.match(/(?:^|\/)data\/([^/]+)\/recipe\/(.+)\.json$/)
  if (nestedBase && !entry.includes('/advancement/') && !entry.includes('/packs/')) {
    return {
      pack: '',
      namespace: nestedBase[1],
      path: nestedBase[2],
      recipeId: `${nestedBase[1]}:${nestedBase[2]}`
    }
  }
  return null
}

export function classifyPackKind(pack) {
  if (!pack) return 'base'
  if (pack.startsWith('disable_')) return 'disable'
  if (pack === 'unite') return 'unite'
  if (pack.startsWith('unite_')) return 'unite'
  if (pack === 'always') return 'always'
  if (pack === 'compat') return 'compat'
  if (pack === 'soup') return 'compat'
  return 'compat'
}

export function jsonHash(obj) {
  const s = typeof obj === 'string' ? obj : JSON.stringify(obj)
  // 简单稳定哈希（非加密）；测试与去重用
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return (h >>> 0).toString(16).padStart(8, '0')
}

export function normalizeItemId(v) {
  if (v === null || v === undefined) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'object') {
    if (typeof v.id === 'string') return v.id
    if (typeof v.item === 'string') return v.item
    if (v.item && typeof v.item === 'object' && typeof v.item.id === 'string') return v.item.id
  }
  return String(v)
}

export function itemRef(obj) {
  if (!obj) return ''
  if (typeof obj === 'string') return obj
  if (Array.isArray(obj)) {
    if (obj.length === 0) return ''
    return obj.map(itemRef).filter(Boolean).join(' / ')
  }
  if (obj.item) return typeof obj.item === 'string' ? obj.item : (obj.item.id || '')
  if (obj.id) return typeof obj.id === 'string' ? obj.id : normalizeItemId(obj.id)
  if (obj.tag) return `#${obj.tag}`
  if (obj.fluid) {
    return `[fluid]${typeof obj.fluid === 'string' ? obj.fluid : (obj.fluid.id || JSON.stringify(obj.fluid))}`
  }
  if (obj.ingredient) return itemRef(obj.ingredient)
  return ''
}

export function resultFromRecipe(data) {
  if (!data || typeof data !== 'object') return { id: '', count: '' }
  const r = data.result || data.output || data.results
  if (!r) return { id: '', count: '' }
  if (Array.isArray(r)) {
    const first = r[0]
    if (!first) return { id: '', count: '' }
    return {
      id: normalizeItemId(first.id || first.item || first),
      count: first.count ?? first.amount ?? 1,
      multi: r.length > 1
    }
  }
  const id = normalizeItemId(r.id || r.item || r)
  if (r.amount !== undefined && r.count === undefined) {
    return { id, count: r.amount, isFluid: true }
  }
  return { id, count: r.count ?? 1 }
}

export function ingredientsFromRecipe(data) {
  const out = []
  const add = (role, v) => {
    if (v === undefined || v === null) return
    if (Array.isArray(v)) {
      v.forEach((e, i) => {
        const t = itemRef(e)
        if (t) out.push({ role, text: t, index: i })
      })
      return
    }
    const t = itemRef(v)
    if (t) out.push({ role, text: t })
  }
  if (data.ingredients) add('原料', data.ingredients)
  else if (data.ingredient) add('原料', data.ingredient)
  else if (data.input) add('原料', data.input)
  else if (data.inputs) add('原料', data.inputs)
  if (data.key && data.pattern) {
    const used = new Set(data.pattern.join('').replace(/ /g, '').split(''))
    for (const k of used) {
      if (data.key[k]) add('原料', data.key[k])
    }
  }
  if (data.base) add('基础', data.base)
  if (data.addition) add('附加', data.addition)
  if (data.container) add('容器', data.container)
  if (data.carrier) add('载体', data.carrier)
  if (data.tool) add('工具', data.tool)
  if (data.base_fluid) add('基础流体', data.base_fluid)
  if (data.fluid && data.type !== 'kaleidoscope_tavern:pressing_tub') add('流体', data.fluid)
  if (data.fluid && data.type === 'kaleidoscope_tavern:pressing_tub') add('产出流体', data.fluid)
  return out
}

export function cookingTimeOf(data) {
  return data.cookingtime ?? data.cooking_time ?? data.fermenting_time ?? data.time ?? data.processing_time ?? ''
}

export function temperatureOf(data) {
  if (data.min !== undefined || data.max !== undefined || data.perfect !== undefined) {
    return `min=${data.min ?? ''};perfect=${data.perfect ?? ''};max=${data.max ?? ''}`
  }
  if (data.temperature !== undefined) return String(data.temperature)
  return ''
}

/** 记录一个 recipe 的多提供者 */
export function addProvider(store, recipeId, provider) {
  if (!store.has(recipeId)) {
    store.set(recipeId, {
      recipeId,
      providers: [],
      runtimePresent: false,
      runtime: null
    })
  }
  const rec = store.get(recipeId)
  // 同 pack+hash 去重
  const key = `${provider.jarDisplay}|${provider.pack}|${provider.jsonHash}`
  if (!rec.providers.some(p => `${p.jarDisplay}|${p.pack}|${p.jsonHash}` === key)) {
    rec.providers.push(provider)
  }
  return rec
}

export function uniteStatusOf(providers, runtimePresent) {
  const kinds = new Set(providers.map(p => classifyPackKind(p.pack)))
  const packs = providers.map(p => p.pack).filter(Boolean)
  const hasBase = kinds.has('base') || providers.some(p => !p.pack && !p.isCompat)
  const hasUnite = [...kinds].some(k => k === 'unite') || packs.some(p => p === 'unite' || p.startsWith('unite_'))
  const hasDisable = [...kinds].some(k => k === 'disable') || packs.some(p => p.startsWith('disable_'))
  const hasAlways = kinds.has('always') || packs.includes('always')
  const hasCompat = kinds.has('compat') || packs.includes('compat')

  if (hasDisable && hasBase) return 'disable覆盖（可能禁用）'
  if (hasDisable) return 'disable包'
  if (hasUnite && hasBase) return 'UNITE覆盖'
  if (hasUnite) return 'UNITE添加'
  if (hasAlways) return 'always包'
  if (hasCompat) return 'compat包'
  if (runtimePresent && providers.length === 0) return '仅运行时'
  if (!runtimePresent && providers.length > 0) return '仅JAR原始（运行时未加载）'
  return '无'
}

/** 完整 token 匹配：禁止 tart→tartaric、roast→roasted_adzuki_crate 这类子串误伤 */
export function matchesToken(path, token) {
  if (!path || !token) return false
  return path === token
    || path.startsWith(token + '_')
    || path.endsWith('_' + token)
    || path.includes('_' + token + '_')
}

export function matchesAnyToken(path, tokens) {
  return tokens.some(t => matchesToken(path, t))
}

export const VALID_CONTENT_TYPES = new Set([
  'DISH', 'SERVING_DISH', 'INGREDIENT', 'DRINK', 'RAW_FOOD', 'ANIMAL_FOOD', 'NON_FOOD', 'REVIEW'
])

export const VALID_TIERS = new Set([
  'COMMON', 'T2', 'T3候选', '不进入厨师', '待复审', ''
])

/**
 * 内容类型判定（6A.2）
 * 优先级：DRINK → SERVING_DISH(无FOOD分食) → DISH(必须FOOD) → INGREDIENT → RAW_FOOD → NON_FOOD → REVIEW
 * 无 FOOD 组件不得仅凭 cake/pizza/roast 名称成为 DISH。
 */
export function classifyContentType(ctx) {
  const {
    productId = '',
    recipeType = '',
    edible = '',
    pathHint = '',
    isFluidOnly = false,
    isBlazier = false,
    isScarecrow = false
  } = ctx

  const id = (productId || '').toLowerCase()
  const path = (id.split(':')[1] || id || pathHint || '').toLowerCase()
  const type = (recipeType || '').toLowerCase()
  const isEdible = edible === '是' || edible === true || edible === 'true'

  if (isBlazier || type.includes('blazier')) {
    return { contentType: 'NON_FOOD', confidence: 'HIGH', evidence: 'blazier_state_recipe' }
  }
  if (isScarecrow || id === 'fowlplay:scarecrow') {
    return { contentType: 'NON_FOOD', confidence: 'HIGH', evidence: 'fowlplay_scarecrow_only' }
  }
  if (isFluidOnly || type === 'kaleidoscope_tavern:pressing_tub') {
    return { contentType: 'INGREDIENT', confidence: 'HIGH', evidence: 'fluid_only_pressing_tub_or_no_item_result' }
  }
  if (!productId && !isFluidOnly) {
    return { contentType: 'REVIEW', confidence: 'LOW', evidence: 'no_product_id' }
  }

  // 明确装饰/箱装/画
  if (matchesAnyToken(path, ['painting', 'crate', 'cabinet', 'sign', 'trophy'])
    || path.endsWith('_crate') || path.includes('sandwich_board')) {
    return { contentType: 'NON_FOOD', confidence: 'HIGH', evidence: 'decor_or_storage_token' }
  }

  // 显式中间产物 ID（优先于 drink/dish 设备启发式；人工覆盖仍可再盖）
  const ingredientExact = new Set([
    'bakeries:cheese_cream', 'bakeries:foamed_cream', 'bakeries:bottle_cream',
    'bakeries:butter_cube', 'mynethersdelight:ghast_dough', 'bakeries:meat_floss',
    'bakeries:mashed_taro', 'bakeries:fresh_cheese_cube', 'bakeries:pineapple_oil',
    'bakeries:egg_tart_shell', 'bakeries:raw_egg_tart',
    'mynethersdelight:raw_stuffed_hoglin',
    'dungeonsdelight:sculk_mayo', 'dungeonsdelight:wardenzola',
    'mynethersdelight:hot_cream', 'mynethersdelight:minced_strider'
  ])
  if (ingredientExact.has(id)) {
    return { contentType: 'INGREDIENT', confidence: 'HIGH', evidence: 'explicit_ingredient_id' }
  }

  // 1) DRINK
  const drinkType = [
    'bakeries:drink',
    'brewinandchewin:fermenting',
    'brewinandchewin:keg_pouring',
    'brewinandchewin:create_potion_pouring',
    'kaleidoscope_tavern:barrel',
    'kaleidoscope_tavern:shaker'
  ].includes(recipeType)
  const drinkTokens = [
    'juice', 'wine', 'beer', 'mead', 'cider', 'cocktail', 'milkshake', 'latte', 'coffee',
    'tea', 'sake', 'vodka', 'rum', 'brandy', 'whiskey', 'ale', 'grog', 'soda', 'drink',
    'smoothie', 'kombucha', 'nog'
  ]
  if (drinkType || matchesAnyToken(path, drinkTokens) || path.endsWith('_smoothie')) {
    return {
      contentType: 'DRINK',
      confidence: 'HIGH',
      evidence: drinkType ? `recipe_type=${recipeType}` : `drink_token=${path}`
    }
  }

  // 2) SERVING_DISH：无 FOOD，但可放置分食（启发式 + 已知模式；最终以人工覆盖为准）
  // 禁止：raw_ 前缀、_shell、_dough、crate、painting、sandwich_board
  const servingPath =
    path === 'cake'
    || path.endsWith('_cake')
    || path.endsWith('_ice_cream_block')
    || path === 'pizza'
    || path.endsWith('_pizza')
    || path === 'roast_stuffed_hoglin'
    || path.endsWith('_feast')
    || (path.endsWith('_block') && matchesAnyToken(path, ['cake', 'pizza', 'cheese_wheel']))
  if (!isEdible && servingPath && !path.startsWith('raw_') && !path.endsWith('_shell') && !path.includes('candle_cake')) {
    return {
      contentType: 'SERVING_DISH',
      confidence: 'MEDIUM',
      evidence: 'non_food_placeable_feast_pattern'
    }
  }

  // 3) DISH：必须 isEdible（FOOD 组件）
  const dishExact = new Set([
    'neapolitan:adzuki_ice_cream',
    'neapolitan:banana_ice_cream',
    'neapolitan:chocolate_ice_cream',
    'neapolitan:strawberry_ice_cream',
    'neapolitan:vanilla_ice_cream',
    'neapolitan:mint_ice_cream',
    'neapolitan:neapolitan_ice_cream',
    'brewinandchewin:creamy_onion_soup',
    'dungeonsdelight:breeze_cream_cone'
  ])
  if (isEdible && dishExact.has(id)) {
    return { contentType: 'DISH', confidence: 'HIGH', evidence: 'explicit_dish_allowlist' }
  }

  // token 级成品名（不含裸 tart/roast 子串）
  const dishTokens = [
    'ice_cream', 'soup', 'stew', 'curry', 'salad', 'sandwich', 'pasta', 'noodles',
    'dumpling', 'burger', 'taco', 'hotdog', 'casserole', 'lasagna', 'risotto',
    'cookie', 'pudding', 'parfait', 'sundae', 'mochi', 'waffle', 'pancake',
    'scone', 'muffin', 'croissant', 'donut', 'fritter', 'omelette', 'skewer',
    'kebab', 'jerky', 'bacon', 'sausage', 'chowder', 'bisque', 'gumbo', 'paella',
    'sampler', 'steak', 'tenderloin', 'popsicle', 'bonbons', 'fudge', 'candies'
  ]
  // 多词后缀
  const dishSuffixOk =
    path.endsWith('_ice_cream')
    || path.endsWith('_soup')
    || path.endsWith('_stew')
    || path.endsWith('_salad')
    || path.endsWith('_sandwich')
    || path.endsWith('_pasta')
    || path.endsWith('_cookie')
    || path.endsWith('_pie') // egg_pie etc; egg_tart handled as ingredient if raw/shell
    || (path.endsWith('_bread') && !path.includes('dough'))
    || (path.endsWith('_roll') && !path.includes('dough'))
    || (path.endsWith('_bun') && !path.includes('dough'))
    || (path.endsWith('_bagel') && !path.includes('dough'))
    || (path.endsWith('_baguette') && !path.includes('dough'))
    || path.endsWith('_toast')
    || path.endsWith('_slice') && matchesAnyToken(path, ['bread', 'toast', 'quiche', 'cake', 'pie', 'pizza'])
    || path === 'egg_tart' // 烤好的蛋挞有 FOOD
    || path.endsWith('_on_a_stick')
    || path === 'hot_wings_bucket'
    || path === 'mint_chocolate'
    || path === 'chocolate_bar'
    || path === 'adzuki_bun'
    || path === 'vanilla_chocolate_fingers'
    || path === 'mint_chops'

  const dishNameStrong = matchesAnyToken(path, dishTokens) || dishSuffixOk

  // 明确中间产物（token 边界；exact 已在上方）
  const ingredientTokens = [
    'dough', 'flour', 'paste', 'batter', 'starter', 'sourdough',
    'sugar_cube', 'minced_strider', 'meat_floss', 'mashed_taro'
  ]
  const ingredientName =
    matchesAnyToken(path, ingredientTokens)
    || path.endsWith('_dough')
    || path.endsWith('_flour')
    || path.endsWith('_shell')
    || path.startsWith('raw_')
    || path.endsWith('_cream') // cheese_cream, foamed_cream, hot_cream — not cream_bread
    || path === 'cheese_cream'
    || path === 'foamed_cream'

  if (ingredientName && !dishSuffixOk) {
    return {
      contentType: 'INGREDIENT',
      confidence: 'HIGH',
      evidence: `ingredient_token=${path}`
    }
  }

  // DISH 必须 FOOD
  if (isEdible && dishNameStrong && !ingredientName) {
    return {
      contentType: 'DISH',
      confidence: 'HIGH',
      evidence: `dish_token;edible=true;type=${recipeType}`
    }
  }

  const cookingDeviceDish = [
    'farmersdelight:cooking',
    'dungeonsdelight:monster_cooking',
    'bakeries:oven',
    'kaleidoscope_cookery:pot',
    'kaleidoscope_cookery:stockpot',
    'kaleidoscope_cookery:steamer',
    'minecraft:smelting',
    'minecraft:smoking',
    'minecraft:campfire_cooking'
  ].includes(recipeType)

  if (isEdible && cookingDeviceDish && !ingredientName) {
    if (recipeType === 'bakeries:blender') {
      // blender mostly ingredients
    } else {
      return {
        contentType: 'DISH',
        confidence: 'HIGH',
        evidence: `cooking_device=${recipeType};food_component`
      }
    }
  }

  // 无 FOOD 时：绝不能因 dishStrong 变成 DISH
  if (!isEdible && dishNameStrong) {
    // 可能是装饰误匹配或未识别整盘 — 已处理 serving/crate；其余 NON_FOOD 或 REVIEW
    if (path.includes('crate') || path.includes('painting') || path.includes('board')) {
      return { contentType: 'NON_FOOD', confidence: 'HIGH', evidence: 'non_edible_false_dish_name' }
    }
    // raw_ / shell already ingredient
    if (path.startsWith('raw_') || path.endsWith('_shell')) {
      return { contentType: 'INGREDIENT', confidence: 'HIGH', evidence: 'raw_or_shell_no_food' }
    }
    return {
      contentType: 'REVIEW',
      confidence: 'LOW',
      evidence: 'non_edible_dishlike_name_needs_serving_proof'
    }
  }

  // ingredient process
  const ingredientType = [
    'bakeries:flour_sieve',
    'bakeries:fermentation_box',
    'bakeries:bread_knife',
    'farmersdelight:cutting',
    'create:milling',
    'create:cutting',
    'bakeries:blender',
    'bakeries:dough_crafting_table'
  ].includes(recipeType)
  if (ingredientType && !isEdible) {
    return {
      contentType: 'INGREDIENT',
      confidence: 'MEDIUM',
      evidence: `ingredient_process=${recipeType}`
    }
  }
  if (ingredientType && isEdible && !dishNameStrong) {
    return {
      contentType: 'INGREDIENT',
      confidence: 'MEDIUM',
      evidence: `ingredient_process=${recipeType}`
    }
  }

  // 4) RAW_FOOD
  if (isEdible) {
    if (path.startsWith('raw_') || path.startsWith('uncooked_')
      || matchesAnyToken(path, ['banana', 'bullet_pepper', 'mint_leaves', 'berry', 'carrot', 'potato', 'tomato', 'onion', 'egg', 'seeds', 'seed'])) {
      if (!dishNameStrong) {
        return { contentType: 'RAW_FOOD', confidence: 'MEDIUM', evidence: 'edible_rawish_token' }
      }
    }
  }

  // 5) NON_FOOD
  if (!isEdible) {
    if (matchesAnyToken(path, [
      'crate', 'cabinet', 'sign', 'fence', 'door', 'slab', 'stairs', 'planks',
      'torch', 'button', 'pressure', 'scarecrow', 'keg', 'pot', 'stove', 'knife',
      'sapling', 'soil', 'farmland', 'lantern', 'scrap', 'ingot', 'nugget', 'ore',
      'glass', 'brick', 'painting', 'trophy'
    ]) || path.endsWith('_block') && !servingPath) {
      return { contentType: 'NON_FOOD', confidence: 'HIGH', evidence: 'non_edible_block_or_tool' }
    }
    return { contentType: 'NON_FOOD', confidence: 'MEDIUM', evidence: 'not_edible' }
  }

  // 6) REVIEW
  return {
    contentType: 'REVIEW',
    confidence: 'LOW',
    evidence: 'edible_but_ambiguous_name'
  }
}

/**
 * 解析人工覆盖 CSV 文本 → Map
 */
export function parseManualOverrides(csvText) {
  const lines = csvText.replace(/^\uFEFF/, '').trim().split(/\r?\n/)
  if (lines.length < 2) return new Map()
  const header = splitCsvLine(lines[0])
  const idx = Object.fromEntries(header.map((h, i) => [h.trim(), i]))
  const required = ['产物ID', '最终内容类型', '最终建议档次', '决策证据', '是否允许厨师经验', '是否允许Field Guide', '是否属于整盘料理', '备注']
  for (const col of required) {
    if (!(col in idx)) throw new Error(`人工覆盖缺少列: ${col}`)
  }
  const map = new Map()
  for (let li = 1; li < lines.length; li++) {
    if (!lines[li].trim()) continue
    const cols = splitCsvLine(lines[li])
    const id = (cols[idx['产物ID']] || '').trim()
    if (!id) throw new Error(`第${li + 1}行缺少产物ID`)
    if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/i.test(id)) {
      throw new Error(`非法 ResourceLocation: ${id}`)
    }
    if (map.has(id)) throw new Error(`重复产物ID: ${id}`)
    const contentType = (cols[idx['最终内容类型']] || '').trim()
    if (!VALID_CONTENT_TYPES.has(contentType)) {
      throw new Error(`非法内容类型 ${contentType} @ ${id}`)
    }
    const tier = (cols[idx['最终建议档次']] || '').trim()
    if (tier && !VALID_TIERS.has(tier)) {
      throw new Error(`非法档次 ${tier} @ ${id}`)
    }
    if (['INGREDIENT', 'NON_FOOD', 'RAW_FOOD', 'ANIMAL_FOOD', 'SERVING_DISH', 'DRINK'].includes(contentType)) {
      if (tier && !['不进入厨师', '待复审', ''].includes(tier)) {
        throw new Error(`${contentType} 不得具有厨师档次 ${tier} @ ${id}`)
      }
    }
    if (contentType === 'DISH' && !['COMMON', 'T2', 'T3候选', '待复审', '不进入厨师'].includes(tier)) {
      // DISH 应有档次
    }
    map.set(id, {
      productId: id,
      contentType,
      tier: tier || (contentType === 'DISH' ? '待复审' : '不进入厨师'),
      evidence: (cols[idx['决策证据']] || '').trim(),
      allowChefXp: (cols[idx['是否允许厨师经验']] || '') === '是',
      allowFieldGuide: (cols[idx['是否允许Field Guide']] || '') === '是',
      isServingDish: (cols[idx['是否属于整盘料理']] || '') === '是' || contentType === 'SERVING_DISH',
      note: (cols[idx['备注']] || '').trim()
    })
  }
  return map
}

/** RFC-style CSV line split（支持引号、逗号、"" 转义） */
export function splitCsvLine(line) {
  const out = []
  let cur = ''
  let inQ = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (inQ) {
      if (c === '"') {
        if (line[i + 1] === '"') { cur += '"'; i++ }
        else inQ = false
      } else cur += c
    } else if (c === '"') inQ = true
    else if (c === ',') { out.push(cur); cur = '' }
    else cur += c
  }
  out.push(cur)
  return out
}

/** 解析完整 CSV 文本 → { header, rows }；处理 BOM；按行 splitCsvLine */
export function parseCsvTable(csvText) {
  const text = String(csvText || '').replace(/^\uFEFF/, '')
  if (!text.trim()) return { header: [], rows: [] }
  // 支持字段内换行：简单状态机
  const rows = []
  let row = []
  let cur = ''
  let inQ = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (inQ) {
      if (c === '"') {
        if (text[i + 1] === '"') { cur += '"'; i++ }
        else inQ = false
      } else cur += c
    } else if (c === '"') {
      inQ = true
    } else if (c === ',') {
      row.push(cur)
      cur = ''
    } else if (c === '\n') {
      row.push(cur)
      cur = ''
      // drop CR
      if (row.length === 1 && row[0] === '' && rows.length === 0) {
        row = []
        continue
      }
      rows.push(row)
      row = []
    } else if (c === '\r') {
      // ignore; handled with \n
    } else {
      cur += c
    }
  }
  if (cur.length || row.length) {
    row.push(cur)
    rows.push(row)
  }
  // trailing empty line
  while (rows.length && rows[rows.length - 1].every(c => c === '')) rows.pop()
  if (!rows.length) return { header: [], rows: [] }
  const header = rows[0].map(h => String(h).trim())
  const body = rows.slice(1).filter(r => r.some(c => String(c).trim() !== ''))
  return { header, rows: body }
}

/**
 * Minecraft ResourceLocation 严格校验（不自动 lowercase 修复）。
 * namespace: [a-z0-9_.-]+ ；path: [a-z0-9_./-]+ ；禁止任何大写字母。
 */
export function isValidResourceLocation(id) {
  if (!id || typeof id !== 'string') return false
  // 无 /i：大写字母一律非法
  if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(id)) return false
  if (id.includes('..')) return false
  const colon = id.indexOf(':')
  if (colon <= 0 || colon !== id.lastIndexOf(':')) return false
  const ns = id.slice(0, colon)
  const path = id.slice(colon + 1)
  if (!ns || !path) return false
  if (ns.startsWith('.') || ns.endsWith('.') || path.startsWith('.') || path.endsWith('.')) return false
  return true
}

/** item:namespace/path 形式的 Field Guide entry id */
export function fieldGuideEntryId(itemId) {
  if (!isValidResourceLocation(itemId)) return ''
  const [ns, path] = itemId.split(':')
  return `item:${ns}/${path}`
}

export function authorityTierToName(code) {
  const m = { '1': 'COMMON', '2': 'T2', '3': 'T3', COMMON: 'COMMON', T2: 'T2', T3: 'T3' }
  return m[String(code || '').trim()] || ''
}

/**
 * 6B.0 合并状态分类（纯函数，便于测试）
 * 注意：显式读取 ctx 字段，避免解构默认值与空字符串混淆。
 * @param {object} ctx
 */
export function classifyMergeStatus(ctx = {}) {
  const itemId = ctx.itemId || ''
  const contentType = ctx.contentType || 'DISH'
  const edible = ctx.edible !== false && ctx.edible !== '否' && ctx.edible !== 0
  const suggestTier = ctx.suggestTier || ''
  const oldAuthorityTiers = Array.isArray(ctx.oldAuthorityTiers) ? ctx.oldAuthorityTiers : []
  const currentItemTier = ctx.itemTier || ctx.currentItemTier || ''
  const recipeTierList = Array.isArray(ctx.recipeTiers) ? ctx.recipeTiers : []
  const excluded = !!ctx.excluded
  const excludeReason = ctx.excludeReason || ''

  if (excluded || excludeReason) {
    return {
      status: 'EXCLUDED_OR_INVALID',
      reason: excludeReason || 'excluded',
      reviewRequired: true
    }
  }
  if (!isValidResourceLocation(itemId)) {
    return { status: 'EXCLUDED_OR_INVALID', reason: 'illegal_resource_location', reviewRequired: true }
  }
  if (contentType !== 'DISH') {
    return { status: 'EXCLUDED_OR_INVALID', reason: `contentType=${contentType}`, reviewRequired: true }
  }
  if (!edible) {
    return { status: 'EXCLUDED_OR_INVALID', reason: 'dish_without_food', reviewRequired: true }
  }
  if (suggestTier === 'T3' || suggestTier === 'T3候选') {
    return {
      status: 'EXCLUDED_OR_INVALID',
      reason: 'new_T3_forbidden_in_6b0',
      reviewRequired: true
    }
  }
  if (suggestTier !== 'COMMON' && suggestTier !== 'T2') {
    return {
      status: 'EXCLUDED_OR_INVALID',
      reason: `suggest_tier_not_mergeable=${suggestTier}`,
      reviewRequired: true
    }
  }

  const oldUnique = [...new Set(oldAuthorityTiers.filter(Boolean))]
  const recipeUnique = [...new Set(recipeTierList.filter(Boolean))]
  const hasOld = oldUnique.length > 0
  const hasItem = currentItemTier !== ''
  const hasRecipe = recipeUnique.length > 0

  let displayTier = ''
  if (hasRecipe) displayTier = recipeUnique[0]
  else if (hasItem) displayTier = currentItemTier
  else if (hasOld) displayTier = oldUnique[0]

  if (hasOld && !hasItem) {
    return {
      status: 'EXISTING_UNMAPPED',
      reason: 'in_authority_csv_but_no_item_tier_json',
      reviewRequired: true,
      displayTier,
      oldTiers: oldUnique,
      recipeTiers: recipeUnique
    }
  }

  if (!hasOld && !hasItem && !hasRecipe) {
    return {
      status: 'NEW',
      reason: 'not_in_old_system',
      reviewRequired: false,
      displayTier: '',
      oldTiers: [],
      recipeTiers: []
    }
  }

  const baselines = []
  if (hasOld) baselines.push(...oldUnique)
  if (hasItem) baselines.push(currentItemTier)
  const baselineUnique = [...new Set(baselines)]
  const conflictWithSuggest = baselineUnique.some(t => t && t !== suggestTier)
  const recipeDiffers = recipeUnique.some(t => t !== suggestTier)

  if (conflictWithSuggest) {
    return {
      status: 'TIER_CONFLICT',
      reason: 'old_or_item_tier_differs_from_6a3',
      reviewRequired: true,
      displayTier,
      oldTiers: oldUnique,
      recipeTiers: recipeUnique
    }
  }

  if (!hasOld && !hasItem && hasRecipe) {
    if (recipeUnique.length === 1 && recipeUnique[0] === suggestTier) {
      return {
        status: 'SAME_TIER',
        reason: 'recipe_only_same_as_6a3',
        reviewRequired: false,
        displayTier,
        oldTiers: [],
        recipeTiers: recipeUnique
      }
    }
    return {
      status: 'TIER_CONFLICT',
      reason: 'recipe_only_differs_or_multi',
      reviewRequired: true,
      displayTier,
      oldTiers: [],
      recipeTiers: recipeUnique
    }
  }

  if (baselineUnique.length === 1 && baselineUnique[0] === suggestTier) {
    return {
      status: 'SAME_TIER',
      reason: recipeDiffers
        ? 'item_old_same_as_6a3_but_recipe_differs_display_only'
        : 'same_tier',
      reviewRequired: false,
      displayTier,
      oldTiers: oldUnique,
      recipeTiers: recipeUnique,
      recipeDisplayOnlyNote: recipeDiffers
    }
  }

  if (baselineUnique.length > 1) {
    return {
      status: 'TIER_CONFLICT',
      reason: 'multi_baseline_tiers',
      reviewRequired: true,
      displayTier,
      oldTiers: oldUnique,
      recipeTiers: recipeUnique
    }
  }

  return {
    status: 'TIER_CONFLICT',
    reason: 'unresolved',
    reviewRequired: true,
    displayTier,
    oldTiers: oldUnique,
    recipeTiers: recipeUnique
  }
}

/** 6A/6B 建议档次 → Field Guide category 文件名（不含 .json） */
export const FG_TIER_CATEGORY = {
  COMMON: 'chef_common',
  T2: 'chef_t2',
  T3: 'chef_t3',
  T3候选: 'chef_t3'
}

/**
 * Field Guide 合并状态：必须校验 entry 所在档次分类与建议档次一致。
 * COMMON → chef_common；T2 → chef_t2。
 */
export function classifyFieldGuideStatus(ctx) {
  const {
    itemId = '',
    contentType = 'DISH',
    suggestTier = '',
    categoriesByEntry = new Map(), // entryId -> [category...]
    blocked = false,
    blockReason = ''
  } = ctx
  const entryId = fieldGuideEntryId(itemId)
  if (blocked || blockReason) {
    return { status: 'FG_BLOCKED', entryId, categories: [], reason: blockReason || 'blocked' }
  }
  if (contentType !== 'DISH') {
    return { status: 'FG_BLOCKED', entryId, categories: [], reason: `contentType=${contentType}` }
  }
  if (!['COMMON', 'T2'].includes(suggestTier)) {
    return { status: 'FG_BLOCKED', entryId, categories: [], reason: `tier=${suggestTier}` }
  }
  if (!entryId) {
    return { status: 'FG_ID_CONFLICT', entryId: '', categories: [], reason: 'bad_entry_id' }
  }
  const expectedCat = FG_TIER_CATEGORY[suggestTier]
  const cats = [...(categoriesByEntry.get(entryId) || [])].sort()
  if (cats.length === 0) {
    return { status: 'FG_NEW', entryId, categories: [], reason: 'absent', expectedCategory: expectedCat }
  }
  if (cats.length > 1) {
    return {
      status: 'FG_ID_CONFLICT',
      entryId,
      categories: cats,
      reason: 'multi_category',
      expectedCategory: expectedCat
    }
  }
  // 仅一个分类：必须与期望档次一致
  if (cats[0] !== expectedCat) {
    return {
      status: 'FG_ID_CONFLICT',
      entryId,
      categories: cats,
      reason: 'tier_category_mismatch',
      expectedCategory: expectedCat
    }
  }
  return {
    status: 'FG_ALREADY_PRESENT',
    entryId,
    categories: cats,
    reason: 'present',
    expectedCategory: expectedCat
  }
}

export function applyManualOverride(autoCls, autoTier, productId, overrides, isEdible) {
  const o = overrides.get(productId)
  if (!o) {
    // 自动：DISH 要求 FOOD
    if (autoCls.contentType === 'DISH' && !isEdible) {
      return {
        contentType: 'REVIEW',
        tier: '待复审',
        evidence: 'auto_dish_without_food_rejected',
        confidence: 'HIGH',
        allowChefXp: false,
        isServingDish: false,
        overridden: false
      }
    }
    const tier = autoTier.tier
    const allowChef = autoCls.contentType === 'DISH' && ['COMMON', 'T2', 'T3候选'].includes(tier)
    return {
      contentType: autoCls.contentType,
      tier: autoCls.contentType === 'SERVING_DISH' ? '不进入厨师' : tier,
      evidence: autoCls.evidence,
      confidence: autoCls.confidence,
      allowChefXp: allowChef,
      isServingDish: autoCls.contentType === 'SERVING_DISH',
      t3Evidence: autoTier.t3Evidence || '',
      reason: autoTier.reason,
      overridden: false
    }
  }
  return {
    contentType: o.contentType,
    tier: o.contentType === 'SERVING_DISH' || o.contentType === 'DRINK' || o.contentType === 'INGREDIENT'
      || o.contentType === 'NON_FOOD' || o.contentType === 'RAW_FOOD'
      ? '不进入厨师'
      : o.tier,
    evidence: `manual_override;${o.evidence}`,
    confidence: 'HIGH',
    allowChefXp: o.allowChefXp && o.contentType === 'DISH',
    isServingDish: o.isServingDish || o.contentType === 'SERVING_DISH',
    t3Evidence: '',
    reason: o.evidence,
    overridden: true,
    note: o.note
  }
}

const RARE_RE =
  /netherite|dragon|wither|warden|sculk|echo_shard|sniffer|ghast_tear|blaze_rod|ancient_debris|heart_of_the_sea|elytra|nether_star|dragon_breath|shulker|phantom_membrane|totem|enchanted_golden_apple|diamond|emerald_block/

/**
 * 档次建议。T3 只输出「T3候选」，不写正式 T3。
 */
export function suggestTier(ctx) {
  const {
    contentType,
    productId = '',
    recipeType = '',
    ingredientKinds = 0,
    ingredientSlots = 0,
    cookingTime = 0,
    ingredientsText = '',
    processChainDepth = 1,
    hasSimpleAlt = false
  } = ctx

  if (contentType !== 'DISH') {
    const map = {
      DRINK: '不进入厨师',
      SERVING_DISH: '不进入厨师',
      INGREDIENT: '不进入厨师',
      RAW_FOOD: '不进入厨师',
      ANIMAL_FOOD: '不进入厨师',
      NON_FOOD: '不进入厨师',
      REVIEW: '待复审'
    }
    return {
      tier: map[contentType] || '待复审',
      reason: contentType === 'SERVING_DISH'
        ? '整盘料理：本阶段不进入普通厨师档次（防双算待设计）'
        : `内容类型=${contentType}`,
      t3Evidence: '',
      isT3Candidate: false
    }
  }

  const id = productId.toLowerCase()
  const path = id.split(':')[1] || id
  const rareHit = RARE_RE.test(ingredientsText) || RARE_RE.test(id)
  const multiStep = processChainDepth >= 2 || /dough|cream|batter|ferment|aged/.test(ingredientsText)
  const feast = /feast|banquet|deluxe|royal|gourmet|stuffed_pumpkin|roast_beast|ossobuco|guardian_angel|warden|buddha|manchu|full_course|gilded|ancient_egg|sniffer_feast/.test(path)

  // T3 候选：必须多重证据，禁止单独靠槽位/monster_cooking/时间
  let t3Score = 0
  const t3Bits = []
  if (feast) { t3Score += 2; t3Bits.push('宴席/名菜名') }
  if (rareHit) { t3Score += 2; t3Bits.push('稀有原料') }
  if (processChainDepth >= 3) { t3Score += 2; t3Bits.push(`加工链深度=${processChainDepth}`) }
  else if (processChainDepth >= 2 && multiStep) { t3Score += 1; t3Bits.push('多段加工') }
  if (ingredientKinds >= 5 && rareHit) { t3Score += 1; t3Bits.push('多种+稀有') }
  // monster_cooking 单独不够
  if (recipeType === 'dungeonsdelight:monster_cooking' && (rareHit || feast || processChainDepth >= 2)) {
    t3Score += 1
    t3Bits.push('怪物锅+附加证据')
  }
  if (hasSimpleAlt) {
    t3Score = Math.max(0, t3Score - 2)
    t3Bits.push('存在更简单替代→降档')
  }

  if (t3Score >= 3 && !hasSimpleAlt) {
    return {
      tier: 'T3候选',
      reason: '复杂宴席/稀有/多段链，待人工确认',
      t3Evidence: t3Bits.join('；'),
      isT3Candidate: true,
      whyNotT2: '满足多重 T3 证据，但未人工确认前不得写入正式 T3',
      rareEvidence: rareHit ? '命中稀有原料启发式' : '无',
      processEvidence: t3Bits.filter(x => x.includes('加工') || x.includes('链')).join('；') || '见 t3Evidence',
      simpleAlt: hasSimpleAlt ? '是' : '否'
    }
  }

  // COMMON 先于宽泛 T2：基础单步熟食/简单甜点
  const commonName = /^(cooked_|baked_|smoked_|fried_|roasted_)/.test(path)
    || /_from_smelting|_from_smoking|_from_campfire/.test(path)
    || path === 'toast' || /simple_|plain_/.test(path)
  if (
    recipeType === 'minecraft:smelting'
    || recipeType === 'minecraft:smoking'
    || recipeType === 'minecraft:campfire_cooking'
    || commonName
    || (ingredientKinds <= 2 && ingredientSlots <= 2
      && recipeType?.startsWith('minecraft:crafting')
      && !/feast|banquet|deluxe|stuffed|casserole|lasagna/.test(path))
  ) {
    // 仍允许明显正餐名抬到 T2
    if (!/soup|stew|curry|pasta|salad|sandwich|pizza|pie|cake|ice_cream|casserole|lasagna|feast/.test(path)) {
      return {
        tier: 'COMMON',
        reason: '基础熟食或少量常见原料/单步合成',
        t3Evidence: '',
        isT3Candidate: false
      }
    }
  }

  // T2：正餐/甜点/常规怪物锅/多步骤烘焙
  const t2Name = /stew|soup|pasta|noodles|curry|salad|sandwich|pizza|pie|cake|bread|bagel|baguette|burger|taco|hotdog|sausage|dumpling|toast|waffle|pancake|pudding|ice_cream|parfait|risotto|chowder|omelette|cookie|muffin|croissant|tart|mochi|roll|skewer|bacon|ham|roast|fried|grilled|baked|smoked|cone|casserole|steak|sampler/.test(path)
  if (
    t2Name
    || recipeType === 'farmersdelight:cooking'
    || recipeType === 'dungeonsdelight:monster_cooking'
    || recipeType === 'bakeries:oven'
    || (ingredientKinds >= 3 && isLikelyMeal(path))
    || multiStep
  ) {
    return {
      tier: 'T2',
      reason: t2Name
        ? '正餐/甜点名称'
        : `设备或复杂度：type=${recipeType};kinds=${ingredientKinds}`,
      t3Evidence: '',
      isT3Candidate: false
    }
  }

  // COMMON 兜底：剩余简单 DISH
  if (ingredientSlots <= 3 || ingredientKinds <= 3) {
    return {
      tier: 'COMMON',
      reason: '简单 DISH 兜底',
      t3Evidence: '',
      isT3Candidate: false
    }
  }

  return {
    tier: '待复审',
    reason: 'DISH 但规则未覆盖',
    t3Evidence: '',
    isT3Candidate: false
  }
}

function isLikelyMeal(path) {
  return /meal|dish|plate|bowl|serving|dinner|lunch|breakfast/.test(path)
}

export function rankTier(t) {
  return {
    COMMON: 1,
    T2: 2,
    T3候选: 3,
    T3: 3,
    '不进入厨师': 0,
    '待复审': 9
  }[t] ?? 9
}

/** 同产物多有效路径取最低厨师档（T3候选 视为高于 T2） */
export function pickLowestChefTier(tiers) {
  const chef = [...tiers].filter(t => ['COMMON', 'T2', 'T3候选', 'T3'].includes(t))
  if (chef.length === 0) return null
  return chef.sort((a, b) => rankTier(a) - rankTier(b))[0]
}

/**
 * 现有 TCTH 是否会因该工序产生 DishCookedEvent
 * 注意：CAMPFIRE 枚举存在但无检测实现。
 */
export function dishEventCoverage(recipeType) {
  const covered = {
    'minecraft:crafting_shaped': { status: '已覆盖', device: 'CRAFTING', note: 'ItemCraftedEvent' },
    'minecraft:crafting_shapeless': { status: '已覆盖', device: 'CRAFTING', note: 'ItemCraftedEvent' },
    'minecraft:smelting': { status: '已覆盖', device: 'FURNACE', note: 'ItemSmeltedEvent（菜单启发）' },
    'minecraft:smoking': { status: '已覆盖', device: 'SMOKER', note: 'ItemSmeltedEvent' },
    'minecraft:blasting': { status: '基本覆盖但不宜作料理', device: 'FURNACE', note: 'ItemSmeltedEvent 可能触发；高炉产物通常非菜' },
    'minecraft:campfire_cooking': { status: '未覆盖', device: 'CAMPFIRE（仅枚举）', note: '无营火取餐检测，不会产生 DishCookedEvent' },
    'farmersdelight:cooking': { status: '已覆盖', device: 'FARMERS_DELIGHT_COOKING_POT', note: 'CookingPotResultSlot#onTake' },
    'farmersdelight:cutting': { status: '未覆盖（非出锅）', device: '—', note: '切菜板不属于料理出锅事件' },
    'kaleidoscope_cookery:pot': { status: '已覆盖', device: 'KALEIDOSCOPE_COOKING_POT', note: 'KC Mixin' },
    'kaleidoscope_cookery:stockpot': { status: '已覆盖', device: 'KALEIDOSCOPE_STOCKPOT', note: 'KC Mixin' },
    'kaleidoscope_cookery:steamer': { status: '已覆盖', device: 'KALEIDOSCOPE_STEAMER', note: 'KC Mixin' },
    'dungeonsdelight:monster_cooking': { status: '需适配', device: 'DUNGEONS_DELIGHT_MONSTER_POT（建议）', note: 'MonsterPotResultSlot#onTake' },
    'bakeries:oven': { status: '需适配', device: 'BAKERIES_OVEN（建议）', note: 'OvenBlockEntity 取物' },
    'bakeries:blender': { status: '需适配', device: 'BAKERIES_BLENDER（建议）', note: '多数为原料' },
    'bakeries:drink': { status: '需适配', device: 'BAKERIES_DRINK（建议）', note: '饮品路线' },
    'bakeries:dough_crafting_table': { status: '需适配', device: 'BAKERIES_DOUGH_TABLE（建议）', note: '' },
    'bakeries:fermentation_box': { status: '需适配', device: 'BAKERIES_FERMENT_BOX（建议）', note: '中间产物' },
    'brewinandchewin:fermenting': { status: '需适配', device: 'BREWIN_KEG（建议）', note: '流体中间态' },
    'brewinandchewin:keg_pouring': { status: '需适配', device: 'BREWIN_KEG_POUR（建议）', note: '装瓶；若做酒保' },
    'kaleidoscope_tavern:barrel': { status: '需适配', device: 'TAVERN_BARREL（建议）', note: 'doTapExtract/getRecipeId' },
    'kaleidoscope_tavern:shaker': { status: '需适配', device: 'TAVERN_SHAKER（建议）', note: '鸡尾酒' },
    'kaleidoscope_tavern:pressing_tub': { status: '不接入厨师', device: '—', note: '流体' },
    'create:mixing': { status: '不接入玩家事件', device: '—', note: 'Create 自动化不得记玩家厨技' },
    'create:cutting': { status: '不接入玩家事件', device: '—', note: 'Create 自动化' },
    'create:milling': { status: '不接入玩家事件', device: '—', note: 'Create 自动化' },
    'create:pressing': { status: '不接入玩家事件', device: '—', note: 'Create 自动化' },
    'create:sequenced_assembly': { status: '不接入玩家事件', device: '—', note: 'Create 自动化' }
  }
  return covered[recipeType] || {
    status: '待评估',
    device: 'OTHER',
    note: ''
  }
}

export function cell(v) {
  const t = v === null || v === undefined ? '' : String(v)
  return `"${t.replaceAll('"', '""').replace(/\r?\n/g, ' ')}"`
}

export function toCsv(rows) {
  return '\uFEFF' + rows.map(r => r.map(cell).join(',')).join('\r\n') + '\r\n'
}

export function assertCsvRectangular(rows) {
  if (!rows.length) throw new Error('empty csv')
  const w = rows[0].length
  for (let i = 0; i < rows.length; i++) {
    if (rows[i].length !== w) {
      throw new Error(`row ${i} width ${rows[i].length} != header ${w}`)
    }
  }
}
