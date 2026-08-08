#!/usr/bin/env node
/**
 * 阶段 6A.2 可运行测试（node Server/tools/export_phase6a_audit.test.mjs）
 */
import {
  parseRecipeEntryPath,
  classifyPackKind,
  addProvider,
  uniteStatusOf,
  classifyContentType,
  suggestTier,
  pickLowestChefTier,
  dishEventCoverage,
  toCsv,
  assertCsvRectangular,
  jsonHash,
  matchesToken,
  parseManualOverrides,
  applyManualOverride
} from './phase6a_lib.mjs'

let passed = 0
let failed = 0
function test(name, fn) {
  try {
    fn()
    passed++
    console.log(`  PASS  ${name}`)
  } catch (e) {
    failed++
    console.error(`  FAIL  ${name}`)
    console.error(`        ${e.message}`)
  }
}
function assert(cond, msg) {
  if (!cond) throw new Error(msg || 'assertion failed')
}

console.log('phase6a_lib tests')

// 1. pack 路径解析
test('packs/unite_bakeries/... → pack=unite_bakeries', () => {
  const p = parseRecipeEntryPath('packs/unite_bakeries/data/bakeries/recipe/example.json')
  assert(p, 'parsed')
  assert(p.pack === 'unite_bakeries', `pack=${p.pack}`)
  assert(p.namespace === 'bakeries', p.namespace)
  assert(p.recipeId === 'bakeries:example', p.recipeId)
})

test('data/bakeries/recipe/example.json → pack 为空', () => {
  const p = parseRecipeEntryPath('data/bakeries/recipe/example.json')
  assert(p && p.pack === '', `pack="${p?.pack}"`)
  assert(p.recipeId === 'bakeries:example')
})

test('advancement 路径不应当作 recipe 时由调用方过滤；parse 对 recipe 路径有效', () => {
  const p = parseRecipeEntryPath('data/bakeries/recipe/oven/bagel.json')
  assert(p.path === 'oven/bagel')
})

// 2. 多提供者
test('同 ID 基础+UNITE 提供者都被保存', () => {
  const store = new Map()
  addProvider(store, 'bakeries:flour_bag', {
    jarDisplay: 'Bakeries', pack: '', jsonHash: 'aaa', type: 'minecraft:crafting_shapeless'
  })
  addProvider(store, 'bakeries:flour_bag', {
    jarDisplay: 'Kaleidoscope Compat', pack: 'unite_bakeries', jsonHash: 'bbb', type: 'minecraft:crafting_shapeless'
  })
  const rec = store.get('bakeries:flour_bag')
  assert(rec.providers.length === 2, `providers=${rec.providers.length}`)
  assert(uniteStatusOf(rec.providers, true) === 'UNITE覆盖', uniteStatusOf(rec.providers, true))
})

test('仅 UNITE 添加', () => {
  const st = uniteStatusOf([{ pack: 'unite_farmersdelight', jarDisplay: 'KC' }], true)
  assert(st === 'UNITE添加', st)
})

test('disable 覆盖', () => {
  const st = uniteStatusOf([
    { pack: '', jarDisplay: 'FD' },
    { pack: 'disable_farmersdelight_cooking_pot', jarDisplay: 'KC' }
  ], false)
  assert(st.includes('disable'), st)
})

test('classifyPackKind', () => {
  assert(classifyPackKind('') === 'base')
  assert(classifyPackKind('unite_bakeries') === 'unite')
  assert(classifyPackKind('disable_x') === 'disable')
  assert(classifyPackKind('always') === 'always')
})

// 3-4 inactive / valid count logic covered by aggregation rules in main script;
// unit: content types

// 5. ice cream / creamy soup
test('adzuki_ice_cream → DISH', () => {
  const c = classifyContentType({
    productId: 'neapolitan:adzuki_ice_cream',
    recipeType: 'minecraft:crafting_shapeless',
    edible: '是'
  })
  assert(c.contentType === 'DISH', c.contentType)
})

test('banana_ice_cream → DISH', () => {
  assert(classifyContentType({
    productId: 'neapolitan:banana_ice_cream',
    recipeType: 'minecraft:crafting_shapeless',
    edible: '是'
  }).contentType === 'DISH')
})

test('neapolitan_ice_cream → DISH', () => {
  assert(classifyContentType({
    productId: 'neapolitan:neapolitan_ice_cream',
    recipeType: 'minecraft:crafting_shapeless',
    edible: '是'
  }).contentType === 'DISH')
})

test('creamy_onion_soup → DISH', () => {
  assert(classifyContentType({
    productId: 'brewinandchewin:creamy_onion_soup',
    recipeType: 'farmersdelight:cooking',
    edible: '是'
  }).contentType === 'DISH')
})

test('breeze_cream_cone → DISH', () => {
  assert(classifyContentType({
    productId: 'dungeonsdelight:breeze_cream_cone',
    recipeType: 'dungeonsdelight:monster_cooking',
    edible: '是'
  }).contentType === 'DISH')
})

// 6. ingredients
test('cheese_cream → INGREDIENT', () => {
  assert(classifyContentType({
    productId: 'bakeries:cheese_cream',
    recipeType: 'bakeries:blender',
    edible: '是'
  }).contentType === 'INGREDIENT')
})

test('foamed_cream → INGREDIENT', () => {
  assert(classifyContentType({
    productId: 'bakeries:foamed_cream',
    recipeType: 'bakeries:blender',
    edible: '是'
  }).contentType === 'INGREDIENT')
})

test('ghast_dough → INGREDIENT', () => {
  assert(classifyContentType({
    productId: 'mynethersdelight:ghast_dough',
    recipeType: 'minecraft:crafting_shapeless',
    edible: '是'
  }).contentType === 'INGREDIENT')
})

test('scarecrow → NON_FOOD', () => {
  assert(classifyContentType({
    productId: 'fowlplay:scarecrow',
    recipeType: 'minecraft:crafting_shaped',
    edible: '否',
    isScarecrow: true
  }).contentType === 'NON_FOOD')
})

test('blazier → NON_FOOD', () => {
  assert(classifyContentType({
    productId: '',
    recipeType: 'mynethersdelight:blazier_heating',
    isBlazier: true
  }).contentType === 'NON_FOOD')
})

test('pressing tub fluid → INGREDIENT', () => {
  assert(classifyContentType({
    productId: '',
    recipeType: 'kaleidoscope_tavern:pressing_tub',
    isFluidOnly: true
  }).contentType === 'INGREDIENT')
})

// 7. 多路径最低档
test('pickLowestChefTier COMMON < T2 < T3候选', () => {
  assert(pickLowestChefTier(['T2', 'COMMON', 'T3候选']) === 'COMMON')
  assert(pickLowestChefTier(['T3候选', 'T2']) === 'T2')
})

// 8. T3 不能仅因 monster_cooking
test('monster_cooking alone is not T3 candidate', () => {
  const t = suggestTier({
    contentType: 'DISH',
    productId: 'dungeonsdelight:simple_meal',
    recipeType: 'dungeonsdelight:monster_cooking',
    ingredientKinds: 2,
    ingredientSlots: 2,
    ingredientsText: 'minecraft:rotten_flesh',
    processChainDepth: 1
  })
  assert(t.tier !== 'T3', t.tier)
  assert(t.tier !== 'T3候选' || t.isT3Candidate === false || t.tier === 'T2' || t.tier === 'COMMON', JSON.stringify(t))
  // should be T2 due to monster_cooking
  assert(t.tier === 'T2' || t.tier === 'COMMON', t.tier)
})

test('T3候选 requires multi evidence and is not formal T3', () => {
  const t = suggestTier({
    contentType: 'DISH',
    productId: 'dungeonsdelight:warden_feast',
    recipeType: 'dungeonsdelight:monster_cooking',
    ingredientKinds: 6,
    ingredientSlots: 6,
    ingredientsText: 'minecraft:echo_shard;minecraft:sculk',
    processChainDepth: 3,
    hasSimpleAlt: false
  })
  assert(t.tier === 'T3候选', t.tier)
  assert(t.isT3Candidate === true)
})

// 9. CSV rectangular
test('csv rectangular', () => {
  const rows = [['a', 'b'], ['1', '2'], ['3', '4']]
  assertCsvRectangular(rows)
  const csv = toCsv(rows)
  assert(csv.startsWith('\uFEFF'))
  assert(csv.includes('"a","b"'))
})

// 10. campfire coverage
test('campfire is NOT covered for DishCookedEvent', () => {
  const c = dishEventCoverage('minecraft:campfire_cooking')
  assert(c.status === '未覆盖', c.status)
})

test('smelting/crafting covered', () => {
  assert(dishEventCoverage('minecraft:crafting_shaped').status === '已覆盖')
  assert(dishEventCoverage('minecraft:smelting').status === '已覆盖')
  assert(dishEventCoverage('minecraft:smoking').status === '已覆盖')
})

test('jsonHash stable', () => {
  assert(jsonHash({ a: 1 }) === jsonHash({ a: 1 }))
})

// ---------- 6A.2 ----------
test('matchesToken rejects substring tart→tartaric', () => {
  assert(!matchesToken('tartaric_acid_painting', 'tart'))
  assert(matchesToken('egg_tart', 'tart'))
  assert(matchesToken('tart', 'tart'))
})

test('matchesToken rejects roast→roasted_adzuki_crate', () => {
  assert(!matchesToken('roasted_adzuki_crate', 'roast'))
  assert(matchesToken('roast_stuffed_hoglin', 'roast'))
})

test('non-FOOD dishStrong name is not DISH', () => {
  const c = classifyContentType({
    productId: 'fake:mystery_soup',
    recipeType: 'minecraft:crafting_shapeless',
    edible: '否'
  })
  assert(c.contentType !== 'DISH', c.contentType)
})

test('tartaric_acid_painting → NON_FOOD', () => {
  assert(classifyContentType({
    productId: 'kaleidoscope_tavern:tartaric_acid_painting',
    recipeType: 'minecraft:crafting_shaped',
    edible: '否'
  }).contentType === 'NON_FOOD')
})

test('roasted_adzuki_crate → NON_FOOD', () => {
  assert(classifyContentType({
    productId: 'neapolitan:roasted_adzuki_crate',
    recipeType: 'minecraft:crafting_shaped',
    edible: '否'
  }).contentType === 'NON_FOOD')
})

test('raw_egg_tart / egg_tart_shell → INGREDIENT', () => {
  assert(classifyContentType({
    productId: 'bakeries:raw_egg_tart',
    recipeType: 'minecraft:crafting_shapeless',
    edible: '否'
  }).contentType === 'INGREDIENT')
  assert(classifyContentType({
    productId: 'bakeries:egg_tart_shell',
    recipeType: 'minecraft:crafting_shapeless',
    edible: '否'
  }).contentType === 'INGREDIENT')
})

test('raw_stuffed_hoglin → INGREDIENT', () => {
  assert(classifyContentType({
    productId: 'mynethersdelight:raw_stuffed_hoglin',
    recipeType: 'minecraft:crafting_shaped',
    edible: '否'
  }).contentType === 'INGREDIENT')
})

test('roast_stuffed_hoglin non-food → SERVING_DISH', () => {
  assert(classifyContentType({
    productId: 'mynethersdelight:roast_stuffed_hoglin',
    recipeType: 'minecraft:smelting',
    edible: '否'
  }).contentType === 'SERVING_DISH')
})

test('cake / pizza / ice_cream_block → SERVING_DISH', () => {
  assert(classifyContentType({
    productId: 'minecraft:cake', recipeType: 'minecraft:crafting_shaped', edible: '否'
  }).contentType === 'SERVING_DISH')
  assert(classifyContentType({
    productId: 'brewinandchewin:pizza', recipeType: 'minecraft:crafting_shaped', edible: '否'
  }).contentType === 'SERVING_DISH')
  assert(classifyContentType({
    productId: 'neapolitan:vanilla_ice_cream_block', recipeType: 'minecraft:crafting_shaped', edible: '否'
  }).contentType === 'SERVING_DISH')
  assert(classifyContentType({
    productId: 'neapolitan:chocolate_cake', recipeType: 'minecraft:crafting_shaped', edible: '否'
  }).contentType === 'SERVING_DISH')
})

test('handheld ice_cream still DISH when edible', () => {
  assert(classifyContentType({
    productId: 'neapolitan:mint_ice_cream',
    recipeType: 'minecraft:crafting_shapeless',
    edible: '是'
  }).contentType === 'DISH')
})

test('sculk_mayo / wardenzola → INGREDIENT', () => {
  assert(classifyContentType({
    productId: 'dungeonsdelight:sculk_mayo',
    recipeType: 'dungeonsdelight:monster_cooking',
    edible: '是'
  }).contentType === 'INGREDIENT')
  assert(classifyContentType({
    productId: 'dungeonsdelight:wardenzola',
    recipeType: 'brewinandchewin:fermenting',
    edible: '是'
  }).contentType === 'INGREDIENT')
})

test('SERVING_DISH suggestTier 不进入厨师', () => {
  const t = suggestTier({ contentType: 'SERVING_DISH', productId: 'minecraft:cake' })
  assert(t.tier === '不进入厨师', t.tier)
  assert(!t.isT3Candidate)
})

test('parseManualOverrides rejects duplicate id', () => {
  const csv = `产物ID,最终内容类型,最终建议档次,决策证据,是否允许厨师经验,是否允许Field Guide,是否属于整盘料理,备注
a:b,INGREDIENT,不进入厨师,x,否,否,否,
a:b,INGREDIENT,不进入厨师,y,否,否,否,`
  let threw = false
  try { parseManualOverrides(csv) } catch { threw = true }
  assert(threw, 'should reject duplicate')
})

test('parseManualOverrides rejects illegal ResourceLocation', () => {
  const csv = `产物ID,最终内容类型,最终建议档次,决策证据,是否允许厨师经验,是否允许Field Guide,是否属于整盘料理,备注
NotAnId,DISH,COMMON,x,是,是,否,`
  let threw = false
  try { parseManualOverrides(csv) } catch { threw = true }
  assert(threw)
})

test('parseManualOverrides rejects illegal content type', () => {
  const csv = `产物ID,最终内容类型,最终建议档次,决策证据,是否允许厨师经验,是否允许Field Guide,是否属于整盘料理,备注
a:b,MEAL,COMMON,x,是,是,否,`
  let threw = false
  try { parseManualOverrides(csv) } catch { threw = true }
  assert(threw)
})

test('parseManualOverrides rejects INGREDIENT with chef tier', () => {
  const csv = `产物ID,最终内容类型,最终建议档次,决策证据,是否允许厨师经验,是否允许Field Guide,是否属于整盘料理,备注
a:b,INGREDIENT,T2,x,否,否,否,`
  let threw = false
  try { parseManualOverrides(csv) } catch { threw = true }
  assert(threw)
})

test('applyManualOverride priority over auto', () => {
  const csv = `产物ID,最终内容类型,最终建议档次,决策证据,是否允许厨师经验,是否允许Field Guide,是否属于整盘料理,备注
mod:item,INGREDIENT,不进入厨师,forced,否,否,否,note`
  const map = parseManualOverrides(csv)
  const auto = { contentType: 'DISH', evidence: 'auto', confidence: 'HIGH' }
  const tier = { tier: 'T3候选', reason: 'x', t3Evidence: 'y' }
  const r = applyManualOverride(auto, tier, 'mod:item', map, true)
  assert(r.overridden === true)
  assert(r.contentType === 'INGREDIENT')
  assert(r.tier === '不进入厨师')
  assert(r.allowChefXp === false)
})

test('auto DISH without FOOD rejected', () => {
  const auto = { contentType: 'DISH', evidence: 'name', confidence: 'HIGH' }
  const tier = { tier: 'COMMON', reason: 'x' }
  const r = applyManualOverride(auto, tier, 'mod:block', new Map(), false)
  assert(r.contentType === 'REVIEW', r.contentType)
  assert(r.allowChefXp === false)
})

test('manual override deterministic twice', () => {
  const csv = `产物ID,最终内容类型,最终建议档次,决策证据,是否允许厨师经验,是否允许Field Guide,是否属于整盘料理,备注
x:y,DISH,COMMON,e,是,是,否,n`
  const a = parseManualOverrides(csv)
  const b = parseManualOverrides(csv)
  assert(a.get('x:y').contentType === b.get('x:y').contentType)
  assert(a.get('x:y').tier === b.get('x:y').tier)
})

test('sandwich_board → NON_FOOD by decor token', () => {
  assert(classifyContentType({
    productId: 'kaleidoscope_tavern:base_sandwich_board',
    recipeType: 'minecraft:crafting_shaped',
    edible: '否'
  }).contentType === 'NON_FOOD')
})

// ---------- 6A.3：16 项覆盖语义（通过覆盖优先）----------
test('6A.3 override settles former REVIEW set', () => {
  const csv = `产物ID,最终内容类型,最终建议档次,决策证据,是否允许厨师经验,是否允许Field Guide,是否属于整盘料理,备注
bakeries:bagel_filled_sauce,DISH,T2,FOOD+recipe,是,是,否,6A.3
bakeries:country_bread,SERVING_DISH,不进入厨师,javap AKnifeCutBlock,否,是,是,6A.3
bakeries:mould_toast,INGREDIENT,不进入厨师,MouldToastBlock demould,否,否,否,6A.3
neapolitan:strawberries,RAW_FOOD,不进入厨师,basket unpack,否,否,否,6A.3
dungeonsdelight:spider_pie,SERVING_DISH,不进入厨师,EXPPieBlock,否,是,是,6A.3
dungeonsdelight:spider_donut,SERVING_DISH,不进入厨师,SpiderDonutBlock DONUTS,否,是,是,6A.3`
  const map = parseManualOverrides(csv)
  const cases = [
    ['bakeries:bagel_filled_sauce', 'DISH', 'T2', true],
    ['bakeries:country_bread', 'SERVING_DISH', '不进入厨师', false],
    ['bakeries:mould_toast', 'INGREDIENT', '不进入厨师', false],
    ['neapolitan:strawberries', 'RAW_FOOD', '不进入厨师', false],
    ['dungeonsdelight:spider_pie', 'SERVING_DISH', '不进入厨师', false],
    ['dungeonsdelight:spider_donut', 'SERVING_DISH', '不进入厨师', false]
  ]
  for (const [id, ct, tier, chef] of cases) {
    const auto = { contentType: 'REVIEW', evidence: 'x', confidence: 'LOW' }
    const r = applyManualOverride(auto, { tier: '待复审', reason: 'x' }, id, map, true)
    assert(r.contentType === ct, `${id} type ${r.contentType}`)
    assert(r.tier === tier, `${id} tier ${r.tier}`)
    assert(r.allowChefXp === chef, `${id} chef ${r.allowChefXp}`)
  }
})

test('RAW_FOOD suggestTier 不进入厨师', () => {
  const t = suggestTier({ contentType: 'RAW_FOOD', productId: 'neapolitan:strawberries' })
  assert(t.tier === '不进入厨师', t.tier)
})

console.log(`\n${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
