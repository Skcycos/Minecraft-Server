#!/usr/bin/env node
import {
  buildPreview,
  loadPricingData,
  evaluateTargetCandidates,
  resolveCost,
  evaluateCandidate,
  classifyBuff,
  extractEffectIds,
  chooseAlternative,
  containerDecision,
  ingredientSlots,
  calculateWorth,
  TIER_DIFFICULTY,
  PRICE_CONFIDENCE,
  priceConfidenceOf,
  parseStack,
  results,
  validCount,
  validId,
  expandIngredient,
  validateManualTable,
  loadManualPrices,
  loadManualTableStrict,
  mergeManualPrices,
  checkManualCoverage,
  MANUAL_DECISIONS,
  OUTPUTS,
  MANUAL_TABLE,
  buildAnchorTable,
  buildEffectTable,
  isBlockingRisk,
  ADVISORY_RISKS
} from './export_phase6e0_bounty_pricing_preview.mjs'
import { assertCsvRectangular, isValidResourceLocation, parseCsvTable } from './phase6a_lib.mjs'
import { readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'

const manualCsv = parseCsvTable(await readFile('配方与经济管理/统一配方表/新增食物模组悬赏原料锚点人工定价表.csv', 'utf8'))
const manualRows = manualCsv.rows
if (manualRows.length !== 121) throw new Error(`manual table must have 121 anchor rows, got ${manualRows.length}`)
if (new Set(manualRows.map(r => r[0] + '|' + r[1])).size !== manualRows.length) throw new Error('manual anchor rows must be unique by dependencyId+dependencyKind')
if (validateManualTable(manualRows).length) throw new Error('manual table validation failed: ' + validateManualTable(manualRows).join(';'))
for (const r of manualRows) {
  const d = r[5]; const prop = r[6]
  if (!MANUAL_DECISIONS.includes(d)) throw new Error(`illegal decision ${d}`)
  if (d === 'DEFINED_CANDIDATE' || d === 'PROVISIONAL') { if (prop === '' || !Number.isInteger(Number(prop)) || Number(prop) <= 0) throw new Error(`manual price must be positive integer: ${r[0]} -> ${prop}`) }
  else if (prop !== '') throw new Error(`REVIEW/EXCLUDED must not carry price: ${r[0]}`)
}
if (Object.values(OUTPUTS).includes(MANUAL_TABLE)) throw new Error('generator must never overwrite the manual table')
const badManual = [
  ['', 'ITEM', '1', '', '', 'REVIEW', '', '', '', '', '', '', '', '', ''],
  ['no-colon', 'ITEM', '1', '', '', 'REVIEW', '', '', '', '', '', '', '', '', ''],
  ['minecraft:dup', 'ITEM', '1', '', '', 'REVIEW', '', '', '', '', '', '', '', '', ''],
  ['minecraft:dup', 'ITEM', '1', '', '', 'REVIEW', '', '', '', '', '', '', '', '', ''],
  ['minecraft:badprice', 'ITEM', '1', '', '', 'DEFINED_CANDIDATE', 'abc', '', '', '', '', '', '', '', ''],
  ['minecraft:badprice', 'ITEM', '1', '', '', 'PROVISIONAL', '0', '', '', '', '', '', '', '', ''],
  ['minecraft:badprice', 'ITEM', '1', '', '', 'PROVISIONAL', '-3', '', '', '', '', '', '', '', ''],
  ['minecraft:stick', 'ITEM', '1', '', '', 'REVIEW', '5', '', '', '', '', '', '', '', ''],
  ['minecraft:stick', 'ITEM', '1', '', '', 'WRONG', '', '', '', '', '', '', '', '', ''],
  ['../evil', 'ITEM', '1', '', '', 'REVIEW', '', '', '', '', '', '', '', '', '']
]
if (validateManualTable(badManual).length === 0) throw new Error('manual table validator must fail-fast on bad rows')
const manualPrices = loadManualPrices(manualRows)
if (manualPrices.get('minecraft:blaze_powder').value !== 25 || manualPrices.get('minecraft:blaze_powder').confidence !== PRICE_CONFIDENCE.PROVISIONAL) throw new Error('blaze_powder must stay PROVISIONAL (circular derivation)')
if (manualPrices.get('minecraft:bone_block').confidence !== PRICE_CONFIDENCE.PROVISIONAL || manualPrices.get('minecraft:magma_cream').value !== 33) throw new Error('manual PROVISIONAL must load as PROVISIONAL')
if ([...manualPrices.keys()].some(id => ['minecraft:echo_shard', 'minecraft:wind_charge', '#c:salt'].includes(id))) throw new Error('REVIEW/EXCLUDED must not enter cost calc')
let strictThrew = false
try { loadManualTableStrict({ header: ['wrong'], rows: [[...Array(15).fill('')]] }) } catch (e) { strictThrew = true }
if (!strictThrew) throw new Error('production manual load must fail-fast on bad header/table')
const mergeOldDefined = mergeManualPrices(new Map([['x', { value: 5, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }]]), new Map([['x', { value: 3, source: '人工定价(PROVISIONAL):t', confidence: PRICE_CONFIDENCE.PROVISIONAL }]]))
if (mergeOldDefined.get('x').value !== 5 || mergeOldDefined.get('x').confidence !== PRICE_CONFIDENCE.DEFINED) throw new Error('manual PROVISIONAL must not downgrade old DEFINED')
const mergeOldProv = mergeManualPrices(new Map([['x', { value: 5, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }]]), new Map([['x', { value: 3, source: '人工定价(PROVISIONAL):t', confidence: PRICE_CONFIDENCE.PROVISIONAL }]]))
if (mergeOldProv.get('x').value !== 3) throw new Error('manual PROVISIONAL must override old PROVISIONAL')
const mergeManDef = mergeManualPrices(new Map([['x', { value: 5, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }]]), new Map([['x', { value: 7, source: '人工定价(DEFINED_CANDIDATE):t', confidence: PRICE_CONFIDENCE.DEFINED }]]))
if (mergeManDef.get('x').value !== 7 || mergeManDef.get('x').confidence !== PRICE_CONFIDENCE.DEFINED) throw new Error('manual DEFINED_CANDIDATE must override')
const mergeManDefOld = mergeManualPrices(new Map([['x', { value: 5, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }]]), new Map([['x', { value: 7, source: '人工定价(DEFINED_CANDIDATE):t', confidence: PRICE_CONFIDENCE.DEFINED }]]))
if (mergeManDefOld.get('x').value !== 7) throw new Error('manual DEFINED_CANDIDATE must override old DEFINED')
const covBase = new Set(['a|ITEM', 'b|TAG'])
if (checkManualCoverage(new Set(['a|ITEM']), covBase).missing.length !== 1) throw new Error('missing anchor key must fail')
if (checkManualCoverage(new Set(['a|ITEM', 'b|TAG', 'c|CONTAINER']), covBase).extra.length !== 1) throw new Error('extra anchor key must fail')
const replaced = checkManualCoverage(new Set(['a|ITEM', 'z|TAG']), covBase)
if (replaced.missing.length !== 1 || replaced.extra.length !== 1) throw new Error('replaced anchor key must fail')
const defPathVsProv = { prices: new Map([['S', { value: 1, source: '人工定价(PROVISIONAL):t', confidence: PRICE_CONFIDENCE.PROVISIONAL }], ['A', { value: 2, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }]]), recipesByProduct: new Map([['S', [{ recipeId: 't:s_from_a', output: { id: 'S', count: 1 }, slots: [{ choices: ['A'], occurrence: 1 }], data: {} }]]]), containerEvidence: new Map(), rootTargetItemId: 'S', rootTargetTier: 'COMMON', onGap: () => {} }
const dpv = resolveCost('S', defPathVsProv)
if (dpv.unitCost !== 2 || dpv.recipeId !== 't:s_from_a' || dpv.advisory.length) throw new Error('DEFINED recipe path must beat manual PROVISIONAL direct')
const manualHashBefore = createHash('sha256').update(await readFile('配方与经济管理/统一配方表/新增食物模组悬赏原料锚点人工定价表.csv')).digest('hex')

const out = await buildPreview()
const rows = out.rows.slice(1)
if (rows.length !== 170) throw new Error(`expected 170 preview rows, got ${rows.length}`)
if (out.counts.COMMON !== 38 || out.counts.T2 !== 132 || out.counts.T3 !== 0) throw new Error(JSON.stringify(out.counts))
if (new Set(rows.map(r => r[0])).size !== 170) throw new Error('preview item IDs are not unique')
if (rows.some(r => !isValidResourceLocation(r[0]))) throw new Error('preview contains invalid ResourceLocation')
assertCsvRectangular(out.rows)
assertCsvRectangular(out.missing)
assertCsvRectangular(out.risks)
assertCsvRectangular(out.anchors)
assertCsvRectangular(out.effects)
if (!rows.every(r => r[11] === 'STALE' && r[12] === 'false' && r[13] === 'JAR')) throw new Error('runtime/static evidence state mismatch')
if (TIER_DIFFICULTY.COMMON !== 1.15 || TIER_DIFFICULTY.T2 !== 1.65 || TIER_DIFFICULTY.T3 !== 2.45) throw new Error('tier constants changed')
if (calculateWorth(22, 'T2', 1) !== 37) throw new Error('T2 coefficient regression')
if (calculateWorth(12, 'COMMON', 1, 0.95) !== 14) throw new Error('COMMON coefficient regression')
if (calculateWorth(10, 'COMMON', 1, 0.95) !== 11 || calculateWorth(10, 'T2', 1) !== 17) throw new Error('multi-output worth regression')

const cases = {
  'effect.minecraft.fire_resistance 300tick 1级 概率1': 1.35,
  'effect.minecraft.regeneration 1200tick 1级 概率1': 1.35,
  'effect.farmersdelight.nourishment 6000tick 1级 概率1': 1.15,
  'effect.minecraft.speed 600tick 1级 概率1；effect.minecraft.haste 600tick 1级 概率1': 1.15,
  'effect.minecraft.poison 600tick 1级 概率1': 0.85,
  'effect.minecraft.slow_falling 200tick 1级 概率1': 1.15
}
for (const [text, factor] of Object.entries(cases)) {
  const c = classifyBuff(text)
  if (c.review || c.factor !== factor) throw new Error(`real-format buff regression: ${text} -> ${JSON.stringify(c)}`)
}
if (extractEffectIds('effect.minecraft.fire_resistance 1800tick 1级 概率1').join() !== 'minecraft:fire_resistance') throw new Error('effect id extraction regression')
if (!extractEffectIds('effect.somemod.foo/bar-baz_qux.abc 200tick 1级 概率1').includes('somemod:foo/bar-baz_qux.abc')) throw new Error('effect RL path with /.-_ must not be truncated')
for (const bad of ['effect.unknown.strength', 'effect.unknown.speed', 'effect.unknown.poison']) {
  if (!classifyBuff(bad).review) throw new Error(`unknown-namespace same-name effect must not fall back to short name: ${bad}`)
}
if (classifyBuff('effect.minecraft.levitation 200tick 1级 概率1').review !== true) throw new Error('levitation must stay REVIEW without ops decision')
if (!classifyBuff('effect.minecraft').review || !classifyBuff('effect.minecraft.').review) throw new Error('truncated effect id must be rejected')
if (classifyBuff('effect.minecraft.fire_resistance 1800tick 1级 概率1').review) throw new Error('tick/概率/effect tokens must not cause REVIEW')
const mixed = classifyBuff('effect.minecraft.regeneration 1200tick 1级 概率1；effect.minecraft.poison 600tick 1级 概率1')
if (!mixed.review || mixed.category !== 'MIXED') throw new Error('mixed buff must review')
if (classifyBuff('effect.bakeries.enjoy 600tick 1级 概率1').factor !== 1.35) throw new Error('bakeries:enjoy must be STRONG (heal+cleanse via javap)')
if (classifyBuff('effect.bakeries.cheese_power 1200tick 1级 概率1').factor !== 1.35) throw new Error('cheese_power must be STRONG (ATTACK_DAMAGE)')
if (classifyBuff('effect.bakeries.cocoa_mania 600tick 1级 概率1').factor !== 1.15) throw new Error('cocoa_mania must be LIGHT (ATTACK_SPEED)')
if (classifyBuff('effect.dungeonsdelight.ravenous_rush 1800tick 1级 概率1').factor !== 1.35) throw new Error('ravenous_rush must be STRONG (MOVEMENT+ATTACK_SPEED)')
if (classifyBuff('effect.mynethersdelight.b_pungent 400tick 3级 概率1').factor !== 0.85) throw new Error('b_pungent must be NEGATIVE via javap HARMFUL')
if (!classifyBuff('effect.mynethersdelight.g_pungent 600tick 1级 概率1').review) throw new Error('g_pungent must stay REVIEW (behavior strength insufficient)')
if (!classifyBuff('effect.neapolitan.vanilla_scent 800tick 1级 概率1').review) throw new Error('vanilla_scent must stay REVIEW (no behavior evidence)')
if (!classifyBuff('effect.neapolitan.harmony 800tick 1级 概率1').review) throw new Error('harmony must stay REVIEW (no behavior evidence)')
if (classifyBuff('effect.neapolitan.berserking 800tick 1级 概率1').factor !== 1.35 || classifyBuff('berserking').factor !== 1.35) throw new Error('berserking full-ID and short-name must both be STRONG')
if (classifyBuff('effect.neapolitan.sugar_rush 800tick 1级 概率1').factor !== 1.35) throw new Error('sugar_rush must be STRONG (MOVEMENT+BLOCK_BREAK)')
if (!classifyBuff('effect.dungeonsdelight.tenacity 1800tick 1级 概率1').review) throw new Error('NEUTRAL effect must stay review')
if (!classifyBuff('effect.unknownmod.xyz 200tick 1级 概率1').review) throw new Error('unknown mod effect must review')
if (!classifyBuff('effect.neapolitan.sugar_rush 800tick 1级 概率1；effect.minecraft.poison 80tick 1级 概率1').review) throw new Error('real mixed/unknown+negative must review')
if (classifyBuff('').factor !== 1) throw new Error('NONE buff regression')
if (!classifyBuff('温暖 1分20秒').review) throw new Error('legacy Chinese buff must review')
if (classifyBuff('warm').factor !== 1.15) throw new Error('legacy short LIGHT buff regression')
if (classifyBuff('fire_resistance').factor !== 1.35) throw new Error('legacy short STRONG buff regression')
if (!classifyBuff('strength,poison').review) throw new Error('legacy mixed buff regression')

const slots = ingredientSlots({ type: 'minecraft:crafting_shaped', key: { A: { item: 'minecraft:apple' }, B: [{ item: 'minecraft:sugar' }, { item: 'minecraft:honey' }] }, pattern: ['ABA', ' B '] })
if (slots.length !== 2 || slots[0].occurrence !== 2 || slots[1].occurrence !== 2 || slots[1].choices.length !== 2) throw new Error('shaped/alternative slot regression')
const repeated = ingredientSlots({ key: { A: { item: 'minecraft:apple' } }, pattern: ['AAA', ' A '] })
if (repeated.length !== 1 || repeated[0].occurrence !== 4) throw new Error('shaped pattern occurrence regression')

const byId = new Map(rows.map(r => [r[0], r]))
for (const id of ['mynethersdelight:blue_tenderloin_steak', 'mynethersdelight:breakfast_sampler', 'mynethersdelight:nether_burger', 'mynethersdelight:red_loin_on_a_stick', 'mynethersdelight:sizzling_pudding']) {
  if (!byId.has(id)) throw new Error(`missing regression item ${id}`)
  const row = byId.get(id)
  if (!row[16] || row[16].includes('warped_roots') && row[16].includes('farmersdelight:straw')) throw new Error(`alternative ingredients were summed for ${id}`)
}
const apple = byId.get('brewinandchewin:apple_jelly')
if (!apple || !apple[17].startsWith('PART_OF_RESULT:minecraft:glass_bottle=2')) throw new Error('apple_jelly container decision regression (must be PART_OF_RESULT)')
if (Number(apple[10]) !== 40) throw new Error(`apple_jelly must price 40 via PART_OF_RESULT bottle, got ${apple[10]}`)
if (!apple[19].includes('PROVISIONAL_PRICE_ANCHOR') || !apple[20].includes('minecraft:glass_bottle=2')) throw new Error('apple_jelly provisional glass_bottle anchor evidence missing')
if (chooseAlternative(['a', 'b'], id => ({ unitCost: id === 'a' ? 40 : 10, recipeId: id })).id !== 'b') throw new Error('alternative selection must use unitCost')

const rc = byId.get('dungeonsdelight:amethyst_rock_candy')
if (Number(rc[10]) !== 63) throw new Error(`amethyst_rock_candy must compute provisional 63, got ${rc[10]}`)
if (!rc[19].includes('PROVISIONAL_PRICE_ANCHOR') || !rc[20].includes('minecraft:amethyst_shard=18(猜想)')) throw new Error('amethyst_rock_candy provisional anchor evidence missing')
const tp = byId.get('mynethersdelight:tear_popsicle')
if (Number(tp[10]) !== 121) throw new Error(`tear_popsicle must compute provisional 121, got ${tp[10]}`)
if (!tp[19].includes('PROVISIONAL_PRICE_ANCHOR') || !tp[20].includes('minecraft:ice=8(猜想)') || !tp[20].includes('minecraft:ghast_tear=40(猜想)') || tp[20].includes('minecraft:stick=1(猜想)')) throw new Error('tear_popsicle must use DEFINED stick path and provisional ice/ghast_tear')

const graph = { B: [{ recipeId: 'test:b_from_a', output: { id: 'B', count: 2 }, slots: [{ choices: ['A'], occurrence: 1 }], data: {} }], C: [{ recipeId: 'test:c_from_b', output: { id: 'C', count: 1 }, slots: [{ choices: ['B'], occurrence: 1 }], data: {} }] }
const eng = { prices: new Map([['A', { value: 10, source: 't', confidence: PRICE_CONFIDENCE.DEFINED }]]), recipesByProduct: new Map(Object.entries(graph)), containerEvidence: new Map(), rootTargetItemId: 'C', rootTargetTier: 'T2', onGap: () => {} }
const recursive = resolveCost('C', eng)
if (recursive.unitCost !== 5 || !['C', 'B', 'A'].every(id => recursive.evidenceChain.join(' -> ').includes(id))) throw new Error('recursive unit cost/evidence regression')
if (recursive.advisory.length || recursive.provisionalAnchors.length) throw new Error('DEFINED anchor must carry no provisional risk')
const prov = resolveCost('P', { prices: new Map([['P', { value: 7, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }]]), recipesByProduct: new Map(), containerEvidence: new Map(), rootTargetItemId: 'P', rootTargetTier: 'COMMON', onGap: () => {} })
if (prov.unitCost !== 7 || !prov.advisory.includes('PROVISIONAL_PRICE_ANCHOR') || !prov.provisionalAnchors.includes('P=7(猜想)') || prov.blocking.length) throw new Error('PROVISIONAL anchor must compute with advisory only')
const multiPrice = { prices: new Map([['A', { value: 5, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }], ['B', { value: 7, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }], ['C', { value: 9, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }]]), recipesByProduct: new Map([['X', [{ recipeId: 't:x', output: { id: 'X', count: 1 }, slots: [{ choices: ['A'], occurrence: 1 }, { choices: ['B'], occurrence: 1 }], data: {} }]], ['Y', [{ recipeId: 't:y', output: { id: 'Y', count: 1 }, slots: [{ choices: ['X'], occurrence: 1 }, { choices: ['C'], occurrence: 1 }], data: {} }]]]), containerEvidence: new Map(), rootTargetItemId: 'Y', rootTargetTier: 'COMMON', onGap: () => {} }
const merged = resolveCost('Y', multiPrice)
if (merged.unitCost !== 21 || merged.provisionalAnchors.length !== 2 || !merged.provisionalAnchors.some(a => a.includes('B=7(猜想)')) || !merged.provisionalAnchors.some(a => a.includes('C=9(猜想)'))) throw new Error('multi-level price source merge regression')
const blockingEng = { prices: new Map(), recipesByProduct: new Map([['Z', [{ recipeId: 't:z', output: { id: 'Z', count: 1 }, slots: [{ choices: ['NOPE'], occurrence: 1 }], data: {} }]]]), containerEvidence: new Map(), rootTargetItemId: 'Z', rootTargetTier: 'COMMON', onGap: () => {} }
const blockedCand = evaluateCandidate(blockingEng.recipesByProduct.get('Z')[0], blockingEng)
if (blockedCand.rawCost != null || blockedCand.blocking.includes('MISSING_PRICE') === false) throw new Error('blocking risk must forbid unitCost')
if (!isBlockingRisk('MISSING_PRICE') || !isBlockingRisk('RECIPE_CYCLE') || !isBlockingRisk('CONTAINER_SEMANTICS_REVIEW') || isBlockingRisk('PROVISIONAL_PRICE_ANCHOR')) throw new Error('risk classification regression')
const loop = resolveCost('X', { prices: new Map(), recipesByProduct: new Map([['X', [{ recipeId: 'x:loop', output: { id: 'X', count: 1 }, slots: [{ choices: ['X'], occurrence: 1 }], sourceJar: 'j', sourceRecipePath: 'p', data: {} }]]]), containerEvidence: new Map(), rootTargetItemId: 'X', rootTargetTier: 'COMMON', onGap: () => {} })
if (loop.rawCost != null || !loop.risk.includes('RECIPE_CYCLE')) throw new Error('cycle without legal candidate regression')
const isolated = resolveCost('C', { ...eng, recipesByProduct: new Map(Object.entries({ ...graph, B: [...graph.B, { recipeId: 'test:b_cycle', output: { id: 'B', count: 1 }, slots: [{ choices: ['B'], occurrence: 1 }], data: {} }] })) })
if (!isolated.evidenceChain.some(s => s.includes('test:b_from_a')) || isolated.evidenceChain.some(s => s.includes('test:b_cycle')) || isolated.risk.length) throw new Error('cycle alternative polluted legal path')

const rec = { recipeId: 'x:dish_recipe', type: 'minecraft:crafting_shapeless', output: { id: 'x:dish', count: 1 }, slots: [{ choices: ['x:ing'], occurrence: 1 }], sourceJar: 'j', sourceRecipePath: 'r', data: { container: { id: 'minecraft:bowl' } } }
const cp = new Map([['x:ing', { value: 5, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }], ['minecraft:bowl', { value: 2, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }]])
const baseEng = (evidence) => ({ prices: cp, recipesByProduct: new Map([['x:dish', [rec]]]), containerEvidence: new Map(evidence), rootTargetItemId: 'x:dish', rootTargetTier: 'COMMON', onGap: () => {} })
if (!evaluateCandidate(rec, baseEng([])).blocking.includes('CONTAINER_SEMANTICS_REVIEW')) throw new Error('container without evidence must block')
const consumedProvisional = evaluateCandidate(rec, baseEng([['x:dish_recipe', 'CONSUMED']]))
if (consumedProvisional.rawCost !== 7 || !consumedProvisional.provisionalAnchors.includes('minecraft:bowl=2(猜想)')) throw new Error('consumed provisional container must add price and anchor')
if (evaluateCandidate(rec, baseEng([['x:dish_recipe', 'RETURNED']])).rawCost !== 5) throw new Error('RETURNED container must not be priced')
if (evaluateCandidate(rec, baseEng([['x:dish_recipe', 'REUSABLE']])).risk.length) throw new Error('REUSABLE container must be clean')
const reviewPriceEng = { prices: new Map([['x:ing', { value: 5, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }], ['minecraft:bowl', { value: 2, source: '', confidence: PRICE_CONFIDENCE.REVIEW }]]), recipesByProduct: new Map([['x:dish', [rec]]]), containerEvidence: new Map([['x:dish_recipe', 'PART_OF_RESULT']]), rootTargetItemId: 'x:dish', rootTargetTier: 'COMMON', onGap: () => {} }
const reviewPriceCand = evaluateCandidate(rec, reviewPriceEng)
if (reviewPriceCand.rawCost != null || !reviewPriceCand.blocking.includes('PRICE_SOURCE_REVIEW')) throw new Error('REVIEW-confidence container price must block')
if (containerDecision({ container: { id: 'minecraft:bowl' } }, cp, new Map([['r', 'PART_OF_RESULT']]), 'r').cost !== 2) throw new Error('PART_OF_RESULT with provisional price must count cost')

if (priceConfidenceOf('已定义') !== PRICE_CONFIDENCE.DEFINED || priceConfidenceOf('已定义/对齐稻米') !== PRICE_CONFIDENCE.DEFINED || priceConfidenceOf('配方派生') !== PRICE_CONFIDENCE.DEFINED || priceConfidenceOf('最便宜锚点') !== PRICE_CONFIDENCE.DEFINED || priceConfidenceOf('番茄/卷心菜锚点') !== PRICE_CONFIDENCE.DEFINED || priceConfidenceOf('对齐番茄') !== PRICE_CONFIDENCE.DEFINED) throw new Error('defined confidence mapping regression')
if (priceConfidenceOf('猜想') !== PRICE_CONFIDENCE.PROVISIONAL || priceConfidenceOf('缺省猜想') !== PRICE_CONFIDENCE.PROVISIONAL || priceConfidenceOf('探索锚点') !== PRICE_CONFIDENCE.PROVISIONAL) throw new Error('provisional confidence mapping regression')
if (priceConfidenceOf('') !== PRICE_CONFIDENCE.REVIEW || priceConfidenceOf('未知') !== PRICE_CONFIDENCE.REVIEW) throw new Error('review confidence mapping regression')

if (JSON.stringify(parseStack({ id: 'a:b', count: 3 })) !== JSON.stringify({ id: 'a:b', count: 3 })) throw new Error('parseStack {id,count} regression')
if (JSON.stringify(parseStack({ item: 'a:b', count: 3 })) !== JSON.stringify({ id: 'a:b', count: 3 })) throw new Error('parseStack {item,count} regression')
if (JSON.stringify(parseStack({ item: { id: 'a:b', count: 3 } })) !== JSON.stringify({ id: 'a:b', count: 3 })) throw new Error('parseStack {item:{id,count}} regression')
if (parseStack({ item: { id: 'a:b' } }).count !== 1) throw new Error('parseStack inner count default regression')
if (results({ result: [{ item: { count: 7, id: 'x:y' } }] }).outputs[0].count !== 7) throw new Error('nested result count regression')
if (results({ result: [{ item: { count: 7, id: 'x:y' } }] }).probabilistic !== false) throw new Error('guaranteed result must not be probabilistic')
const probOut = results({ result: [{ item: { count: 3, id: 'a:b' } }, { chance: 0.25, item: { count: 1, id: 'a:b' } }] })
if (probOut.outputs.length !== 2 || probOut.outputs[1].chance !== 0.25 || probOut.probabilistic !== true) throw new Error('probabilistic result parsing regression')
if (probOut.outputs[0].chance !== null) throw new Error('missing chance must be null (guaranteed)')
if (results({ result: [{ chance: 0, item: { count: 1, id: 'x:y' } }] }) !== null || results({ result: [{ chance: 1.5, item: { count: 1, id: 'x:y' } }] }) !== null || results({ result: [{ chance: NaN, item: { count: 1, id: 'x:y' } }] }) !== null || results({ result: [{ chance: -0.5, item: { count: 1, id: 'x:y' } }] }) !== null) throw new Error('invalid chance must fail closed')
if (results({ result: [{ item: { count: 0, id: 'x:y' } }] }) !== null || results({ result: [{ item: { count: -2, id: 'x:y' } }] }) !== null || results({ result: [{ item: { count: 1.5, id: 'x:y' } }] }) !== null || results({ result: [{ item: { count: NaN, id: 'x:y' } }] }) !== null || results({ result: [{ item: { count: Infinity, id: 'x:y' } }] }) !== null) throw new Error('invalid output count must fail closed')
if (!validCount(1) || validCount(0) || validCount(-1) || validCount(1.5) || validCount(NaN) || validCount(Infinity)) throw new Error('validCount regression')
if (!validId('minecraft:stick') || !validId('#c:foods/bread') || validId('') || validId('no-colon') || validId('minecraft:bad id')) throw new Error('validId regression')
const badIng = ingredientSlots({ ingredients: [{ item: 'minecraft:apple' }, { item: 'minecraft:stick', count: 0 }] })
if (badIng[0].status !== 'VALID' || badIng[1].status !== 'INVALID') throw new Error('ingredient slot status regression')
if (ingredientSlots({}).length !== 0) throw new Error('absent optional fields must not create slots')
const compoundSlots = ingredientSlots({ ingredients: [{ type: 'neoforge:compound', children: [{ item: 'a:x' }, { item: 'a:y' }] }] })
if (compoundSlots.length !== 1 || compoundSlots[0].status !== 'VALID' || compoundSlots[0].choices.length !== 2) throw new Error('compound ingredient expansion regression')

const realData = await loadPricingData()
const countOf = (recipeId, outputId) => {
  const rs = realData.byProduct.get(outputId) || []
  const r = rs.find(x => x.recipeId === recipeId)
  return r ? r.output.count : null
}
const outputCounts = [
  ['mynethersdelight:cutting/hoglin_sausage', 'mynethersdelight:hoglin_sausage', 2],
  ['mynethersdelight:cutting/magma_cake', 'mynethersdelight:magma_cake_slice', 7],
  ['mynethersdelight:cutting/slices_of_bread', 'mynethersdelight:slices_of_bread', 5],
  ['brewinandchewin:cutting/pizza', 'brewinandchewin:pizza_slice', 4],
  ['brewinandchewin:cutting/quiche', 'brewinandchewin:quiche_slice', 4],
  ['dungeonsdelight:cutting/monster_cake', 'dungeonsdelight:monster_cake_slice', 7],
  ['dungeonsdelight:cutting/slime_bar', 'dungeonsdelight:slime_noodles', 2],
  ['dungeonsdelight:cutting/spider_pie', 'dungeonsdelight:spider_pie_slice', 4]
]
for (const [rid, oid, c] of outputCounts) {
  const got = countOf(rid, oid)
  if (got !== c) throw new Error(`output count regression: ${rid} -> ${oid} expected ${c} got ${got}`)
}
const slimeBar = (realData.byProduct.get('dungeonsdelight:slime_noodles') || []).find(r => r.recipeId === 'dungeonsdelight:cutting/slime_bar')
if (!slimeBar || slimeBar.coProduct !== true) throw new Error('multi-product recipe must be flagged coProduct')
const coEng = { prices: realData.prices, recipesByProduct: realData.byProduct, containerEvidence: new Map(), rootTargetItemId: 'dungeonsdelight:slime_noodles', rootTargetTier: 'T2', onGap: () => {} }
const coCand = evaluateCandidate(slimeBar, coEng)
if (!coCand.blocking.includes('CO_PRODUCT_ALLOCATION_REVIEW') || coCand.rawCost != null) throw new Error('co-product allocation must block pricing')
if (!isBlockingRisk('INVALID_INGREDIENT_STACK') || !isBlockingRisk('PROBABILISTIC_OUTPUT_REVIEW') || !isBlockingRisk('CO_PRODUCT_ALLOCATION_REVIEW') || isBlockingRisk('PROVISIONAL_PRICE_ANCHOR')) throw new Error('risk classification regression')

const probRecipes = {
  'mynethersdelight:cutting/balze_rod': ['minecraft:blaze_powder', 3, 0.25],
  'mynethersdelight:cutting/bullet_pepper': ['mynethersdelight:pepper_powder', 1, 0.25],
  'mynethersdelight:salvaging/powdery_furniture': ['mynethersdelight:powdery_planks', 1, 0.75]
}
for (const [rid, [oid, guaranteedCount, extraChance]] of Object.entries(probRecipes)) {
  const entries = (realData.byProduct.get(oid) || []).filter(r => r.recipeId === rid)
  if (rid === 'mynethersdelight:salvaging/powdery_furniture') {
    if (entries.length !== 1 || entries[0].output.chance !== 0.75 || entries[0].output.count !== guaranteedCount) throw new Error('probabilistic-only regression powdery_furniture')
  } else {
    const guaranteed = entries.find(r => r.output.chance === null)
    const extra = entries.find(r => r.output.chance != null)
    if (!guaranteed || guaranteed.output.count !== guaranteedCount || !extra || Math.abs(extra.output.chance - extraChance) > 1e-9) throw new Error(`guaranteed+extra regression ${rid}`)
  }
  if (!entries.every(r => r.probabilistic === true)) throw new Error(`recipe must be flagged probabilistic ${rid}`)
  const probEng = { prices: realData.prices, recipesByProduct: realData.byProduct, containerEvidence: new Map(), rootTargetItemId: oid, rootTargetTier: 'T2', onGap: () => {} }
  const probCand = evaluateCandidate(entries[0], probEng)
  if (!probCand.blocking.includes('PROBABILISTIC_OUTPUT_REVIEW') || probCand.rawCost != null) throw new Error(`probabilistic output must block pricing ${rid}`)
}
const balzeGuaranteed = (realData.byProduct.get('minecraft:blaze_powder') || []).find(r => r.recipeId === 'mynethersdelight:cutting/balze_rod' && r.output.chance === null)
const balzeExtra = (realData.byProduct.get('minecraft:blaze_powder') || []).find(r => r.recipeId === 'mynethersdelight:cutting/balze_rod' && r.output.chance != null)
if (!balzeGuaranteed || !balzeExtra || balzeGuaranteed.output.count !== 3 || balzeExtra.output.count !== 1) throw new Error('blaze_powder guaranteed+extra must not be merged')

const totalCostEng = { prices: new Map([['A', { value: 1, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }], ['B', { value: 2, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }]]), recipesByProduct: new Map(), containerEvidence: new Map(), rootTargetItemId: 'x:out', rootTargetTier: 'COMMON', onGap: () => {} }
const totalCostRec = { recipeId: 'x:rec', type: 'minecraft:crafting_shapeless', output: { id: 'x:out', count: 1 }, slots: [{ choices: [{ id: 'A', count: 10 }, { id: 'B', count: 1 }], occurrence: 1, status: 'VALID' }], data: {}, coProduct: false, probabilistic: false }
const tcc = evaluateCandidate(totalCostRec, totalCostEng)
if (tcc.rawCost !== 2 || tcc.ingredientChoices[0] !== 'B×1') throw new Error(`totalSlotCost must select B cost 2, got ${JSON.stringify(tcc)}`)
const evidEng = { prices: new Map([['A', { value: 1, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }], ['B', { value: 3, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }]]), recipesByProduct: new Map(), containerEvidence: new Map(), rootTargetItemId: 'x:out', rootTargetTier: 'COMMON', onGap: () => {} }
const evidRec = { recipeId: 'x:rec2', type: 'minecraft:crafting_shapeless', output: { id: 'x:out', count: 1 }, slots: [{ choices: [{ id: 'A', count: 1 }, { id: 'B', count: 1 }], occurrence: 1, status: 'VALID' }], data: {}, coProduct: false, probabilistic: false }
const evidCand = evaluateCandidate(evidRec, evidEng)
if (evidCand.rawCost !== 3 || evidCand.ingredientChoices[0] !== 'B×1' || evidCand.advisory.length) throw new Error('DEFINED alternative must beat cheaper PROVISIONAL alternative')
const invalidEng = { prices: new Map([['minecraft:apple', { value: 2, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }]]), recipesByProduct: new Map(), containerEvidence: new Map(), rootTargetItemId: 'x:out', rootTargetTier: 'COMMON', onGap: () => {} }
const invalidRec = { recipeId: 'x:bad', type: 'minecraft:crafting_shapeless', output: { id: 'x:out', count: 1 }, slots: badIng, data: {}, coProduct: false, probabilistic: false }
const invalidCand = evaluateCandidate(invalidRec, invalidEng)
if (invalidCand.rawCost != null || !invalidCand.blocking.includes('INVALID_INGREDIENT_STACK')) throw new Error('invalid ingredient stack must block pricing')
let p03gap = null
const p03Eng = { prices: new Map(), recipesByProduct: new Map(), containerEvidence: new Map(), rootTargetItemId: 'y:out', rootTargetTier: 'COMMON', onGap: (g) => { p03gap = g } }
const p03Rec = { recipeId: 'y:rec', type: 'minecraft:crafting_shapeless', output: { id: 'y:out', count: 1 }, slots: [{ choices: [{ id: 'y:miss1', count: 1 }, { id: 'y:miss2', count: 1 }], occurrence: 1, status: 'VALID' }], data: {}, coProduct: false, probabilistic: false }
evaluateCandidate(p03Rec, p03Eng)
if (!p03gap || p03gap.hasAlt !== false) throw new Error('two failing candidates must yield hasOtherValidRoute 否 (P0-3)')

const stickPrice = realData.prices.get('minecraft:stick')
if (stickPrice.value !== 1 || stickPrice.confidence !== PRICE_CONFIDENCE.PROVISIONAL) throw new Error('stick guess direct price must be 1 provisional')
const bambooPrice = realData.prices.get('minecraft:bamboo')
if (bambooPrice.value !== 2 || bambooPrice.confidence !== PRICE_CONFIDENCE.DEFINED) throw new Error('bamboo defined derived price must be 2 defined')
const stickEng = { prices: realData.prices, recipesByProduct: realData.byProduct, containerEvidence: new Map(), rootTargetItemId: 'minecraft:stick', rootTargetTier: 'COMMON', onGap: () => {} }
const stickResolved = resolveCost('minecraft:stick', stickEng)
if (stickResolved.unitCost !== 2 || stickResolved.recipeId !== 'mynethersdelight:cutting/stick_bamboo' || stickResolved.advisory.length) throw new Error(`stick must choose DEFINED bamboo path, got ${JSON.stringify(stickResolved)}`)

const reviewDirect = resolveCost('R', { prices: new Map([['R', { value: 5, source: '未知', confidence: PRICE_CONFIDENCE.REVIEW }]]), recipesByProduct: new Map(), containerEvidence: new Map(), rootTargetItemId: 'R', rootTargetTier: 'COMMON', onGap: () => {} })
if (reviewDirect.unitCost != null || reviewDirect.rawCost != null) throw new Error('REVIEW direct price must fail closed')
const provVsDef = { prices: new Map([['A', { value: 2, source: '已定义', confidence: PRICE_CONFIDENCE.DEFINED }], ['S', { value: 1, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }]]), recipesByProduct: new Map([['S', [{ recipeId: 't:s_from_a', output: { id: 'S', count: 1 }, slots: [{ choices: ['A'], occurrence: 1 }], data: {} }]]]), containerEvidence: new Map(), rootTargetItemId: 'S', rootTargetTier: 'COMMON', onGap: () => {} }
const pvd = resolveCost('S', provVsDef)
if (pvd.unitCost !== 2 || pvd.recipeId !== 't:s_from_a' || pvd.advisory.length) throw new Error('DEFINED recipe path must beat PROVISIONAL direct price')
const sameLevel = { prices: new Map([['A', { value: 10, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }], ['B', { value: 4, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }], ['T', { value: 5, source: '猜想', confidence: PRICE_CONFIDENCE.PROVISIONAL }]]), recipesByProduct: new Map([['T', [{ recipeId: 't:t_a', output: { id: 'T', count: 1 }, slots: [{ choices: ['A'], occurrence: 1 }], data: {} }, { recipeId: 't:t_b', output: { id: 'T', count: 1 }, slots: [{ choices: ['B'], occurrence: 1 }], data: {} }]]]), containerEvidence: new Map(), rootTargetItemId: 'T', rootTargetTier: 'COMMON', onGap: () => {} }
const sl = resolveCost('T', sameLevel)
if (sl.unitCost !== 4 || sl.recipeId !== 't:t_b') throw new Error('same-level provisional must pick lowest unitCost')

const data = await loadPricingData()
const bt = out.targets.find(t => t.id === 'mynethersdelight:blue_tenderloin_steak')
const ev = evaluateTargetCandidates(data, bt)
const btRow = byId.get('mynethersdelight:blue_tenderloin_steak')
if (btRow[2] !== (ev.displayed?.recipeId || '')) throw new Error('preview recipeId not sourced from shared evaluator')
if (btRow[18] !== (ev.displayed?.evidenceChain?.join(' -> ') || '')) throw new Error('preview evidence not sourced from shared evaluator')

const targetIds = new Set(out.targets.map(t => t.id))
const missingRows = out.missing.slice(1)
if (missingRows.some(r => !r[1])) throw new Error('gap row with blank targetTier')
if (missingRows.some(r => !targetIds.has(r[0]))) throw new Error('gap row with targetItemId outside 170 scope')
if (missingRows.some(r => !r[5].startsWith(r[0]) || !r[5].endsWith(r[3]))) throw new Error('gap chain must start at root target and end at direct dependency')
if (out.missing.slice(1).some(r => r[3] === '递归原料依赖' || r[5].includes('递归原料依赖'))) throw new Error('generic recursive dependency gap remains')

const anchorRows = out.anchors.slice(1)
if (out.anchors[0].length !== 14) throw new Error('anchor schema regression')
if (new Set(anchorRows.map(r => r[0] + '|' + r[1])).size !== anchorRows.length) throw new Error('anchor dependencyId+kind must be unique')
if (anchorRows.reduce((s, r) => s + Number(r[2]), 0) !== missingRows.length) throw new Error('anchor occurrenceRows sum must equal gap rows')
for (const r of anchorRows) {
  const ids = r[4].split(';')
  if (Number(r[3]) !== ids.length) throw new Error(`affectedTargetCount mismatch for ${r[0]}`)
  if (ids.some(id => !targetIds.has(id))) throw new Error(`anchor target outside scope: ${r[0]}`)
  if (r[1] === 'CONTAINER' && r[13] !== 'BLOCKED') throw new Error('container anchor must be BLOCKED without evidence')
  if (r[8] === 'REVIEW' && (r[10] !== '' || r[13] === 'DEFINED')) throw new Error('review anchor must not suggest price')
}
const effectRows = out.effects.slice(1)
if (out.effects[0].length !== 10) throw new Error('effect schema regression')
if (new Set(effectRows.map(r => r[0])).size !== effectRows.length) throw new Error('effectId must be unique')
for (const r of effectRows) {
  const ids = r[3].split(';')
  if (Number(r[2]) !== ids.length) throw new Error(`affectedDishCount mismatch for ${r[0]}`)
  if (ids.some(id => !targetIds.has(id))) throw new Error(`effect dish outside scope: ${r[0]}`)
  if (r[8] !== 'DEFINED' && (r[6] !== '' || r[7] !== '')) throw new Error(`unknown effect must not enter formal factors: ${r[0]}`)
}
const slowRow = effectRows.find(r => r[0] === 'minecraft:slow_falling')
if (!slowRow || slowRow[7] !== 1.15 || slowRow[8] !== 'DEFINED') throw new Error('slow_falling must be positive DEFINED 1.15')
const levRow = effectRows.find(r => r[0] === 'minecraft:levitation')
if (levRow && (levRow[8] === 'DEFINED' || levRow[7] !== '')) throw new Error('levitation must stay REVIEW without ops decision')
const unknownEffects = effectRows.filter(r => r[8] !== 'DEFINED')
if (out.counts.UNIQUE_UNKNOWN_EFFECTS !== unknownEffects.length) throw new Error('UNIQUE_UNKNOWN_EFFECTS count mismatch')

if (out.counts.UNIQUE_ANCHORS !== anchorRows.length) throw new Error('UNIQUE_ANCHORS count mismatch')
if (out.counts.PRICED !== rows.filter(r => r[10] !== '').length) throw new Error('PRICED count mismatch')
if (out.counts.PRICED_PROVISIONAL !== rows.filter(r => r[10] !== '' && (r[19] || '').includes('PROVISIONAL_PRICE_ANCHOR')).length) throw new Error('PRICED_PROVISIONAL count mismatch')
if (out.counts.PRICED_DEFINED !== out.counts.PRICED - out.counts.PRICED_PROVISIONAL) throw new Error('PRICED_DEFINED count mismatch')
if (out.counts.UNKNOWN_BUFF !== rows.filter(r => r[19].includes('UNKNOWN_BUFF')).length) throw new Error('UNKNOWN_BUFF count mismatch')
if (out.counts.MIXED_BUFF !== rows.filter(r => r[19].includes('MIXED_BUFF_REVIEW')).length) throw new Error('MIXED_BUFF count mismatch')
if (out.counts.CONTAINER_REVIEW !== rows.filter(r => (r[17] || '').startsWith('REVIEW:') || (r[17] || '').startsWith('CONTAINER_SEMANTICS_REVIEW:')).length) throw new Error('CONTAINER_REVIEW count mismatch')
if (out.counts.BLOCKED !== rows.filter(r => r[10] === '' && r[19] !== '').length) throw new Error('BLOCKED count mismatch')

const P0CupRecipes = ['poi_cup', 'rubaboo_cup', 'salt_soaked_stew_cup', 'spider_bubble_tea', 'spider_salmagundi_cup', 'tower_boreito']
for (const w of P0CupRecipes) {
  const cr = (out.containerSemantics.slice(1)).filter(r => r[1].includes(w))
  if (cr.length === 0) throw new Error(`container row missing for ${w}`)
  if (cr.some(r => r[7] !== 'IN_SCOPE')) throw new Error(`monster_foods container recipe must be IN_SCOPE via static tag closure: ${w}`)
}
const newComputableIds = out.mergedPricedIds.filter(id => !new Set(out.basePricedIds).has(id))
if (JSON.stringify(newComputableIds) !== JSON.stringify(['mynethersdelight:burnt_roll'])) throw new Error(`NEW_COMPUTABLE must be manual-price-driven only, got ${JSON.stringify(newComputableIds)}`)
if (!out.basePricedIds.includes('brewinandchewin:apple_jelly')) throw new Error('baseline pricing run must use same containerEvidence (apple_jelly priceable via container)')
if (out.counts.BASE_PRICED !== 6 || out.counts.PRICED !== 7 || out.counts.NEW_COMPUTABLE !== 1) throw new Error(`P0-2 base/merged mismatch: ${out.counts.BASE_PRICED}/${out.counts.PRICED}/${out.counts.NEW_COMPUTABLE}`)
if (out.missing.slice(1).some(r => r[9] === '是')) throw new Error('hasOtherValidRoute must be 否 when all alternatives fail (P0-3)')

const containerRows = out.containerSemantics.slice(1)
if (containerRows.length < 9) throw new Error(`container semantics must enumerate container recipes, got ${containerRows.length}`)
if (containerRows.some(r => r[6] !== 'PART_OF_RESULT')) throw new Error('all enumerated cooking-device containers must be PART_OF_RESULT')
if (containerRows.some(r => r[4] !== 'NONE')) throw new Error('confirmed non-return containers must write returnPath NONE')
if (containerRows.some(r => !['IN_SCOPE', 'OUT_OF_SCOPE'].includes(r[7]))) throw new Error('container rows must carry scopeStatus IN_SCOPE/OUT_OF_SCOPE')
if (containerRows.some(r => r[8] !== 'UNKNOWN')) throw new Error('static recipes must have runtimeStatus UNKNOWN while export blocked')
const anchorContainerIds = new Set(manualRows.filter(r => r[1] === 'CONTAINER').map(r => r[0]))
const inScopeContainerIds = new Set(containerRows.filter(r => r[7] === 'IN_SCOPE').map(r => r[2]))
if (![...anchorContainerIds].every(id => inScopeContainerIds.has(id))) throw new Error('all 9 anchor containers must be IN_SCOPE in dependency closure')
if (!out.coverage.includes('已确认 9 个') || out.coverage.includes('已确认 0 个')) throw new Error('container coverage summary must report 已确认 9 个')
if (out.counts.CONTAINER_REVIEW !== 0) throw new Error('all containers resolved, CONTAINER_REVIEW must be 0')
if (out.runtime.status !== 'BLOCKED') throw new Error('runtime export must be BLOCKED (other production server running)')
const tagRows = out.tagAudit.slice(1)
if (tagRows.length !== 38) throw new Error(`tag audit must cover 38 tags, got ${tagRows.length}`)
if (tagRows.some(r => r[1] !== 'UNKNOWN' || r[8] !== 'REVIEW')) throw new Error('tags must be runtime-UNKNOWN and REVIEW (export blocked)')
if (out.runtime.status !== 'BLOCKED') throw new Error('runtime export must be BLOCKED without server start')
const effRows = out.effects.slice(1)
const enjoyRow = effRows.find(r => r[0] === 'bakeries:enjoy')
if (!enjoyRow || enjoyRow[5] !== 'positive' || enjoyRow[6] !== 'STRONG' || enjoyRow[8] !== 'DEFINED' || !enjoyRow[4].includes('BENEFICIAL')) throw new Error('bakeries:enjoy must be javap-BENEFICIAL STRONG DEFINED')
const tenacityRow = effRows.find(r => r[0] === 'dungeonsdelight:tenacity')
if (!tenacityRow || tenacityRow[8] !== 'REVIEW' || !tenacityRow[9].includes('NEUTRAL')) throw new Error('dungeonsdelight:tenacity must stay REVIEW via NEUTRAL')
if (out.counts.UNIQUE_UNKNOWN_EFFECTS !== effRows.filter(r => r[8] !== 'DEFINED').length) throw new Error('UNKNOWN_EFFECTS count mismatch')
if (out.counts.MANUAL_DEFINED + out.counts.MANUAL_PROVISIONAL + out.counts.MANUAL_REVIEW + out.counts.MANUAL_EXCLUDED !== 121) throw new Error('manual decision counts must total 121')
if (out.counts.MANUAL_DEFINED !== 0 || out.counts.MANUAL_PROVISIONAL !== 5 || out.counts.MANUAL_REVIEW !== 109 || out.counts.MANUAL_EXCLUDED !== 7) throw new Error('manual decision distribution mismatch after blaze_powder downgrade')
if (out.counts.NEW_COMPUTABLE !== out.counts.PRICED - out.counts.BASE_PRICED) throw new Error('NEW_COMPUTABLE must equal mergedPriced minus basePriced set difference')
if (out.counts.BASE_PRICED !== 6) throw new Error('pricing baseline (basePrices + same containerEvidence) computable must be 6')
const sa = byId.get('dungeonsdelight:sculk_apple')
if (Number(sa[10]) !== 123) throw new Error(`sculk_apple must stay 123, got ${sa[10]}`)
const manualHashAfter = createHash('sha256').update(await readFile('配方与经济管理/统一配方表/新增食物模组悬赏原料锚点人工定价表.csv')).digest('hex')
if (manualHashBefore !== manualHashAfter) throw new Error('manual table must not be modified by generator')

const out2 = await buildPreview()
if (JSON.stringify(out) !== JSON.stringify(out2)) throw new Error('two buildPreview runs must be byte-identical')
console.log('phase6e0.5.1 pricing preview tests: PASS')