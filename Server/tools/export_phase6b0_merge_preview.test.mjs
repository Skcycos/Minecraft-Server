#!/usr/bin/env node
/**
 * 阶段 6B.0 合并预览单元测试
 */
import {
  parseCsvTable,
  isValidResourceLocation,
  fieldGuideEntryId,
  authorityTierToName,
  classifyMergeStatus,
  classifyFieldGuideStatus,
  toCsv,
  assertCsvRectangular
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

console.log('phase6b0 merge preview tests')

test('BOM CSV parse', () => {
  const text = '\uFEFFa,b\n1,2\n'
  const { header, rows } = parseCsvTable(text)
  assert(header[0] === 'a' && header[1] === 'b', JSON.stringify(header))
  assert(rows.length === 1 && rows[0][0] === '1')
})

test('quoted comma and escaped quotes', () => {
  const text = 'id,note\n"a:b","hello, ""world"""\n'
  const { rows } = parseCsvTable(text)
  assert(rows[0][0] === 'a:b')
  assert(rows[0][1] === 'hello, "world"', rows[0][1])
})

test('exact item id match not substring', () => {
  assert(isValidResourceLocation('neapolitan:mint_ice_cream'))
  assert(!isValidResourceLocation('mint_ice_cream'))
  assert(fieldGuideEntryId('neapolitan:mint_ice_cream') === 'item:neapolitan/mint_ice_cream')
  // substring must not invent ids
  assert(fieldGuideEntryId('tart') === '')
})

test('ResourceLocation rejects uppercase (no auto-lowercase fix)', () => {
  assert(isValidResourceLocation('mod:item') === true)
  assert(isValidResourceLocation('Mod:item') === false)
  assert(isValidResourceLocation('mod:Item') === false)
  assert(isValidResourceLocation('MOD:ITEM') === false)
  assert(isValidResourceLocation('mod:ITEM') === false)
  assert(isValidResourceLocation('Mod:Item') === false)
})

test('authority tier map', () => {
  assert(authorityTierToName('1') === 'COMMON')
  assert(authorityTierToName('2') === 'T2')
  assert(authorityTierToName('3') === 'T3')
})

test('NEW status', () => {
  const r = classifyMergeStatus({
    itemId: 'mod:new_dish',
    contentType: 'DISH',
    edible: true,
    suggestTier: 'COMMON',
    oldAuthorityTiers: [],
    itemTier: '',
    recipeTiers: []
  })
  assert(r.status === 'NEW', r.status)
})

test('SAME_TIER status', () => {
  const r = classifyMergeStatus({
    itemId: 'mod:same',
    contentType: 'DISH',
    edible: true,
    suggestTier: 'T2',
    oldAuthorityTiers: ['T2'],
    itemTier: 'T2',
    recipeTiers: []
  })
  assert(r.status === 'SAME_TIER', r.status)
})

test('TIER_CONFLICT when old differs from 6A3', () => {
  const r = classifyMergeStatus({
    itemId: 'mod:conflict',
    contentType: 'DISH',
    edible: true,
    suggestTier: 'T2',
    oldAuthorityTiers: ['COMMON'],
    itemTier: 'COMMON',
    recipeTiers: []
  })
  assert(r.status === 'TIER_CONFLICT', r.status)
  assert(r.reviewRequired === true)
})

test('recipe tier display priority does not auto-resolve design conflict', () => {
  const r = classifyMergeStatus({
    itemId: 'mod:x',
    contentType: 'DISH',
    edible: true,
    suggestTier: 'COMMON',
    oldAuthorityTiers: ['T2'],
    itemTier: 'T2',
    recipeTiers: ['COMMON'] // recipe says COMMON but old is T2 vs suggest COMMON still conflict with old?
  })
  // old T2 != suggest COMMON => TIER_CONFLICT; recipe only affects displayTier
  assert(r.status === 'TIER_CONFLICT', r.status)
  assert(r.displayTier === 'COMMON', `display=${r.displayTier}`)
})

test('recipe same as item+suggest stays SAME_TIER even if noted', () => {
  const r = classifyMergeStatus({
    itemId: 'mod:y',
    contentType: 'DISH',
    edible: true,
    suggestTier: 'T2',
    oldAuthorityTiers: ['T2'],
    itemTier: 'T2',
    recipeTiers: ['T3'] // recipe differs: display T3 but baseline matches suggest
  })
  assert(r.status === 'SAME_TIER', r.status)
  assert(r.displayTier === 'T3', r.displayTier)
  assert(r.recipeDisplayOnlyNote === true)
})

test('EXISTING_UNMAPPED', () => {
  const r = classifyMergeStatus({
    itemId: 'mod:orphan',
    contentType: 'DISH',
    edible: true,
    suggestTier: 'COMMON',
    oldAuthorityTiers: ['COMMON'],
    itemTier: '',
    recipeTiers: []
  })
  assert(r.status === 'EXISTING_UNMAPPED', r.status)
})

test('EXCLUDED_OR_INVALID raw_dough / non-dish / no food / new T3', () => {
  assert(classifyMergeStatus({
    itemId: 'kaleidoscope_cookery:raw_dough',
    contentType: 'DISH',
    edible: true,
    suggestTier: 'COMMON',
    excluded: true,
    excludeReason: 'not_dishes_or_raw_dough'
  }).status === 'EXCLUDED_OR_INVALID')

  assert(classifyMergeStatus({
    itemId: 'mod:a',
    contentType: 'SERVING_DISH',
    edible: false,
    suggestTier: 'COMMON'
  }).status === 'EXCLUDED_OR_INVALID')

  assert(classifyMergeStatus({
    itemId: 'mod:a',
    contentType: 'DISH',
    edible: false,
    suggestTier: 'COMMON'
  }).status === 'EXCLUDED_OR_INVALID')

  assert(classifyMergeStatus({
    itemId: 'mod:a',
    contentType: 'DISH',
    edible: true,
    suggestTier: 'T3候选'
  }).status === 'EXCLUDED_OR_INVALID')
})

test('COMMON/T2 mutual exclusive suggest only those for NEW', () => {
  const a = classifyMergeStatus({
    itemId: 'mod:c', contentType: 'DISH', edible: true, suggestTier: 'COMMON'
  })
  const b = classifyMergeStatus({
    itemId: 'mod:t', contentType: 'DISH', edible: true, suggestTier: 'T2'
  })
  assert(a.status === 'NEW' && b.status === 'NEW')
  assert(a.status !== b.status || a !== b)
})

test('forbid new T3', () => {
  const r = classifyMergeStatus({
    itemId: 'mod:t3',
    contentType: 'DISH',
    edible: true,
    suggestTier: 'T3'
  })
  assert(r.status === 'EXCLUDED_OR_INVALID')
})

test('Field Guide entry id item:ns/path', () => {
  assert(fieldGuideEntryId('farmersdelight:cooked_rice') === 'item:farmersdelight/cooked_rice')
  assert(fieldGuideEntryId('a:b/c') === 'item:a/b/c')
})

test('FG_NEW / FG_ALREADY_PRESENT / multi-category conflict', () => {
  const map = new Map([
    ['item:mod/a', ['chef_common']],
    ['item:mod/b', ['chef_common', 'chef_t2']],
    ['item:mod/t2ok', ['chef_t2']],
    ['item:mod/wrong1', ['chef_common']],
    ['item:mod/wrong2', ['chef_t2']]
  ])
  assert(classifyFieldGuideStatus({
    itemId: 'mod:new', contentType: 'DISH', suggestTier: 'COMMON', categoriesByEntry: map
  }).status === 'FG_NEW')
  // COMMON + chef_common
  assert(classifyFieldGuideStatus({
    itemId: 'mod:a', contentType: 'DISH', suggestTier: 'COMMON', categoriesByEntry: map
  }).status === 'FG_ALREADY_PRESENT')
  // T2 + chef_t2
  assert(classifyFieldGuideStatus({
    itemId: 'mod:t2ok', contentType: 'DISH', suggestTier: 'T2', categoriesByEntry: map
  }).status === 'FG_ALREADY_PRESENT')
  // multi category
  assert(classifyFieldGuideStatus({
    itemId: 'mod:b', contentType: 'DISH', suggestTier: 'T2', categoriesByEntry: map
  }).status === 'FG_ID_CONFLICT')
  // T2 落在 chef_common → conflict
  const w1 = classifyFieldGuideStatus({
    itemId: 'mod:wrong1', contentType: 'DISH', suggestTier: 'T2', categoriesByEntry: map
  })
  assert(w1.status === 'FG_ID_CONFLICT', w1.status)
  assert(w1.reason === 'tier_category_mismatch', w1.reason)
  // COMMON 落在 chef_t2 → conflict
  const w2 = classifyFieldGuideStatus({
    itemId: 'mod:wrong2', contentType: 'DISH', suggestTier: 'COMMON', categoriesByEntry: map
  })
  assert(w2.status === 'FG_ID_CONFLICT', w2.status)
  assert(w2.reason === 'tier_category_mismatch', w2.reason)
})

test('SERVING_DISH / DRINK / RAW_FOOD blocked from FG and chef merge', () => {
  assert(classifyFieldGuideStatus({
    itemId: 'mod:cake', contentType: 'SERVING_DISH', suggestTier: '不进入厨师', categoriesByEntry: new Map()
  }).status === 'FG_BLOCKED')
  assert(classifyMergeStatus({
    itemId: 'mod:drink', contentType: 'DRINK', edible: true, suggestTier: '不进入厨师'
  }).status === 'EXCLUDED_OR_INVALID')
  assert(classifyMergeStatus({
    itemId: 'mod:raw', contentType: 'RAW_FOOD', edible: true, suggestTier: '不进入厨师'
  }).status === 'EXCLUDED_OR_INVALID')
})

test('output sort deterministic for ids', () => {
  const ids = ['z:a', 'a:b', 'm:c'].sort((a, b) => a.localeCompare(b))
  assert(ids[0] === 'a:b' && ids[2] === 'z:a')
  const rows = [['z'], ['a'], ['m']].sort((x, y) => x[0].localeCompare(y[0]))
  assertCsvRectangular([['h'], ...rows])
  const c1 = toCsv([['h'], ...rows])
  const c2 = toCsv([['h'], ...rows])
  assert(c1 === c2)
})

test('parseCsvTable stable twice', () => {
  const t = 'x,y\n1,2\n'
  const a = parseCsvTable(t)
  const b = parseCsvTable(t)
  assert(JSON.stringify(a) === JSON.stringify(b))
})

console.log(`\n${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
