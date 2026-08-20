#!/usr/bin/env node
/* 6E.0.3: static-only bounty pricing preview + anchor/effect review tables. Never edits formal pools. */
import { readFile, readdir, writeFile } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import { parseCsvTable, isValidResourceLocation, toCsv, assertCsvRectangular } from './phase6a_lib.mjs'

const ROOT = resolve(new URL('../..', import.meta.url).pathname)
const TABLE = resolve(ROOT, '配方与经济管理/统一配方表')
const SERVER = resolve(ROOT, 'Server')
export const OUTPUTS = {
  preview: resolve(TABLE, '新增食物模组悬赏定价预览.csv'),
  missing: resolve(TABLE, '新增食物模组悬赏原料价格缺口表.csv'),
  risk: resolve(TABLE, '新增食物模组悬赏套利风险复审表.csv'),
  anchors: resolve(TABLE, '新增食物模组悬赏定价锚点复审表.csv'),
  effects: resolve(TABLE, '新增食物模组悬赏效果复审表.csv'),
  containerSemantics: resolve(TABLE, '新增食物模组悬赏容器语义决策表.csv'),
  tagAudit: resolve(TABLE, '新增食物模组悬赏Tag成员审计表.csv'),
  coverage: resolve(TABLE, '新增食物模组悬赏原料人工定价覆盖摘要.md'),
  pending: resolve(TABLE, '新增食物模组悬赏人工定价未决项表.csv'),
  summary: resolve(TABLE, '新增食物模组悬赏定价摘要.md')
}
export const MANUAL_TABLE = resolve(TABLE, '新增食物模组悬赏原料锚点人工定价表.csv')
export const MANUAL_DECISIONS = Object.freeze(['DEFINED_CANDIDATE', 'PROVISIONAL', 'REVIEW', 'EXCLUDED'])
export const MANUAL_HEADER = ['dependencyId', 'dependencyKind', 'affectedTargetCount', 'currentPrice', 'currentPriceSource', 'decision', 'proposedPrice', 'evidenceType', 'evidenceDetail', 'renewability', 'acquisitionDifficulty', 'farmingRisk', 'economicRisk', 'rationale', 'status']
const PENDING_HEADER = ['dependencyId', 'dependencyKind', 'affectedTargetCount', 'decision', 'rationale', 'status']

export const TIER_DIFFICULTY = Object.freeze({ COMMON: 1.15, T2: 1.65, T3: 2.45 })
export const BUFF_FACTOR = Object.freeze({ NONE: 1, LIGHT: 1.15, STRONG: 1.35, NEGATIVE: 0.85 })
export const PRICE_CONFIDENCE = Object.freeze({ DEFINED: 'DEFINED', PROVISIONAL: 'PROVISIONAL', REVIEW: 'REVIEW' })
const JAR_PATTERNS = [/bakeries-1\.21\.1.*\.jar$/i, /BrewinAndChewin-neoforge-4\.5\.0.*\.jar$/i, /dungeonsdelight-1\.21\.1-1\.5\.0\.jar$/i, /MyNethersDelight-1\.21\.1-1\.10\.4\.jar$/i, /neapolitan-1\.21\.1-6\.0\.1\.jar$/i]
const PREVIEW_HEADER = ['itemId', '档次', '静态候选配方ID', '设备', '输出数量', '整份原料成本', '单件原料成本', '难度系数', 'buff系数', 'common系数', '建议unitWorth', 'runtimeStatus', 'runtimeVerified', 'staticEvidence', '来源JAR', 'recipe JSON路径', '原料选择', '容器决策', '证据链', '风险', 'REVIEW原因']
const MISSING_HEADER = ['targetItemId', 'targetTier', 'staticCandidateRecipeId', 'directMissingDependency', 'dependencyKind', 'fullDependencyChain', 'slotIndex', 'alternativeIndex', 'occurrence', 'hasOtherValidRoute', 'sourceJar', 'sourceRecipePath', 'suggestedAction', 'status']
const RISK_HEADER = ['targetItemId', 'recipeId', 'sourceJar', 'sourceRecipePath', '风险类型', '风险对象', '证据', '建议动作', '状态']
const ANCHOR_HEADER = ['dependencyId', 'dependencyKind', 'occurrenceRows', 'affectedTargetCount', 'affectedTargetIds', 'affectedTiers', 'currentPrice', 'currentPriceSource', 'priceConfidence', 'containerDecision', 'suggestedPrice', 'suggestedPriceEvidence', 'economicRisk', 'decision']
const EFFECT_HEADER = ['effectId', 'sourceMod', 'affectedDishCount', 'affectedDishIds', 'evidenceSource', 'positiveOrNegative', 'suggestedClass', 'factor', 'decision', 'reviewReason']

function val(row, header, name) { return (row[header.indexOf(name)] || '').trim() }
function idOf(x) { if (!x) return ''; if (typeof x === 'string') return x; if (x.item) return typeof x.item === 'string' ? x.item : idOf(x.item); if (x.id) return typeof x.id === 'string' ? x.id : idOf(x.id); if (x.tag) return `#${x.tag}`; return '' }
function norm(id) { return id?.startsWith('#') ? id : (id || '').replace(/^c:/, '#c:') }
function machine(type) { return ({ 'minecraft:crafting_shaped': '工作台（有序）', 'minecraft:crafting_shapeless': '工作台（无序）', 'minecraft:smelting': '熔炉', 'minecraft:campfire_cooking': '营火', 'farmersdelight:cooking': '农夫乐事烹饪锅', 'farmersdelight:cutting': '农夫乐事切菜砧板', 'dungeonsdelight:monster_cooking': '怪物烹饪锅', 'bakeries:oven': '烘焙坊烤箱', 'bakeries:blender': '烘焙坊搅拌机' })[type] || type || '未知设备' }
export function tierDifficulty(tier) { return TIER_DIFFICULTY[tier] ?? null }
export function calculateWorth(cost, tier, buff, common = 1) { const d = tierDifficulty(tier); if (d == null || buff == null) return null; return Math.ceil(cost * d * buff * common) }
export function priceConfidenceOf(source) {
  const s = (source || '').trim()
  if (/^已定义/.test(s)) return PRICE_CONFIDENCE.DEFINED
  if (/(猜想|缺省|探索锚点)/.test(s)) return PRICE_CONFIDENCE.PROVISIONAL
  if (['配方派生', '派生', '派生/通用池', '派生(最便宜路径)', '最便宜锚点', '番茄/卷心菜锚点', '对齐番茄'].some(x => s.startsWith(x))) return PRICE_CONFIDENCE.DEFINED
  return PRICE_CONFIDENCE.REVIEW
}

const BUFF_BY_FULL_ID = Object.freeze({
  'minecraft:fire_resistance': 'STRONG', 'minecraft:regeneration': 'STRONG', 'minecraft:strength': 'STRONG', 'minecraft:resistance': 'STRONG', 'minecraft:absorption': 'STRONG', 'minecraft:instant_health': 'STRONG',
  'minecraft:speed': 'LIGHT', 'minecraft:haste': 'LIGHT', 'minecraft:night_vision': 'LIGHT', 'minecraft:water_breathing': 'LIGHT', 'minecraft:saturation': 'LIGHT', 'minecraft:slow_falling': 'LIGHT', 'farmersdelight:nourishment': 'LIGHT',
  'minecraft:poison': 'NEGATIVE', 'minecraft:hunger': 'NEGATIVE', 'minecraft:weakness': 'NEGATIVE', 'minecraft:slowness': 'NEGATIVE', 'minecraft:nausea': 'NEGATIVE', 'minecraft:unluck': 'NEGATIVE', 'minecraft:bad_omen': 'NEGATIVE',
  'bakeries:enjoy': 'STRONG', 'bakeries:cheese_power': 'STRONG', 'bakeries:cocoa_mania': 'LIGHT',
  'dungeonsdelight:ravenous_rush': 'STRONG', 'dungeonsdelight:putrid_scent': 'NEGATIVE',
  'mynethersdelight:b_pungent': 'NEGATIVE',
  'neapolitan:sugar_rush': 'STRONG', 'neapolitan:berserking': 'STRONG'
})
const EFFECT_CATEGORY = Object.freeze({
  'bakeries:enjoy': 'BENEFICIAL', 'bakeries:cheese_power': 'BENEFICIAL', 'bakeries:cocoa_mania': 'BENEFICIAL',
  'dungeonsdelight:ravenous_rush': 'BENEFICIAL', 'dungeonsdelight:putrid_scent': 'HARMFUL',
  'dungeonsdelight:exudation': 'NEUTRAL', 'dungeonsdelight:pouncing': 'NEUTRAL', 'dungeonsdelight:swift_step': 'NEUTRAL', 'dungeonsdelight:rotgut': 'NEUTRAL', 'dungeonsdelight:decisive': 'NEUTRAL', 'dungeonsdelight:voracity': 'NEUTRAL', 'dungeonsdelight:tenacity': 'NEUTRAL', 'dungeonsdelight:burrow_gut': 'NEUTRAL',
  'mynethersdelight:g_pungent': 'BENEFICIAL', 'mynethersdelight:b_pungent': 'HARMFUL',
  'neapolitan:vanilla_scent': 'BENEFICIAL', 'neapolitan:sugar_rush': 'BENEFICIAL', 'neapolitan:agility': 'NEUTRAL', 'neapolitan:berserking': 'BENEFICIAL', 'neapolitan:harmony': 'BENEFICIAL',
  'minecraft:levitation': 'NEUTRAL'
})
const EFFECT_BEHAVIOR_EVIDENCE = Object.freeze({
  'bakeries:enjoy': 'javap applyEffectTick：回血 + 移除负面效果 → STRONG',
  'bakeries:cheese_power': 'javap addAttributeModifier ATTACK_DAMAGE → STRONG',
  'bakeries:cocoa_mania': 'javap addAttributeModifier ATTACK_SPEED → LIGHT(类急迫)',
  'dungeonsdelight:ravenous_rush': 'javap addAttributeModifier MOVEMENT_SPEED+0.3 / ATTACK_SPEED+0.1 → STRONG',
  'mynethersdelight:g_pungent': 'javap tick 复杂火防效果（transformEffect/火焰保护判定），强度证据不足 → REVIEW',
  'neapolitan:vanilla_scent': 'javap 无属性加成，tick 行为无源码证据 → REVIEW',
  'neapolitan:sugar_rush': 'javap addAttributeModifier MOVEMENT_SPEED / BLOCK_BREAK_SPEED → STRONG',
  'neapolitan:berserking': 'javap addAttributeModifier ARMOR / ATTACK_DAMAGE → STRONG',
  'neapolitan:harmony': 'javap 无属性加成，tick 行为无源码证据 → REVIEW',
  'dungeonsdelight:putrid_scent': 'javap MobEffectCategory.HARMFUL → NEGATIVE',
  'mynethersdelight:b_pungent': 'javap MobEffectCategory.HARMFUL → NEGATIVE'
})
const CONTAINER_PART_OF_RESULT_TYPES = new Set([
  'farmersdelight:cooking', 'dungeonsdelight:monster_cooking', 'farmersdelight:food_serving',
  'bakeries:blender', 'bakeries:drink', 'brewinandchewin:fermenting', 'brewinandchewin:keg_pouring',
  'brewinandchewin:create_potion_pouring', 'kaleidoscope_tavern:barrel', 'kaleidoscope_tavern:shaker'
])
export function buildContainerEvidence(byProduct) {
  const decisionById = new Map()
  const seen = new Set()
  const rows = []
  for (const recipes of byProduct.values()) {
    for (const r of recipes) {
      if (!r.data || !r.data.container) continue
      const containerId = norm(idOf(r.data.container))
      const key = `${r.recipeId}|${containerId}`
      if (seen.has(key)) continue
      seen.add(key)
      const partOfResult = CONTAINER_PART_OF_RESULT_TYPES.has(r.type)
      const decision = partOfResult ? 'PART_OF_RESULT' : 'REVIEW'
      if (!decisionById.has(r.recipeId)) decisionById.set(r.recipeId, decision)
      rows.push({ device: r.type, recipeId: r.recipeId, containerId, resultItem: r.output.id, returnPath: partOfResult ? 'NONE' : 'UNKNOWN', evidence: partOfResult ? 'recipe JSON container 字段 + 烹饪/发酵/搅拌设备行为：容器随交付物给出（非返还）' : '设备类型未确认容器语义，保持 REVIEW', decision })
    }
  }
  return { decisionById, rows }
}
export function loadStaticTags(jars, server) {
  const raw = new Map()
  for (const jar of jars) {
    const path = resolve(server, 'mods', jar)
    const entries = execFileSync('unzip', ['-Z1', path], { encoding: 'utf8' }).split(/\r?\n/).filter(e => /^data\/[^/]+\/tags\/item(s)?\/.+\.json$/.test(e))
    for (const e of entries) {
      const parts = e.split('/')
      const tagId = `#${parts[1]}:${parts.slice(4).join('/').replace(/\.json$/, '')}`
      let data
      try { data = readJson(path, e) } catch { continue }
      if (!raw.has(tagId)) raw.set(tagId, { items: new Set(), tags: new Set() })
      const g = raw.get(tagId)
      for (const v of (data.values || [])) {
        const id = typeof v === 'string' ? v : (v && v.id)
        if (!id) continue
        if (id.startsWith('#')) g.tags.add(id)
        else g.items.add(norm(id))
      }
    }
  }
  const resolved = new Map()
  const resolveTag = (tagId, stack = []) => {
    if (resolved.has(tagId)) return resolved.get(tagId)
    if (stack.includes(tagId)) return new Set()
    const g = raw.get(tagId)
    if (!g) return new Set()
    const out = new Set(g.items)
    for (const sub of g.tags) for (const m of resolveTag(sub, [...stack, tagId])) out.add(m)
    resolved.set(tagId, out)
    return out
  }
  for (const tagId of raw.keys()) resolveTag(tagId)
  return resolved
}
export function computeRecipeClosure(byProduct, targets, staticTags = new Map()) {
  const closure = new Set()
  const queue = targets.map(t => t.id)
  const seen = new Set()
  while (queue.length) {
    const id = queue.shift()
    if (seen.has(id)) continue
    seen.add(id)
    for (const r of (byProduct.get(id) || [])) {
      if (closure.has(r.recipeId)) continue
      closure.add(r.recipeId)
      for (const slot of r.slots) for (const c of slot.choices) {
        if (c.id.startsWith('#')) { for (const m of (staticTags.get(c.id) || [])) queue.push(m) } else queue.push(c.id)
      }
    }
  }
  return closure
}
const CONTAINER_HEADER = ['设备', 'recipeId', '输入容器', '交付物', '返还路径', '证据', '决定', 'scopeStatus', 'runtimeStatus']
export function buildContainerSemanticsTable(byProduct, closure) {
  return buildContainerEvidence(byProduct).rows.map(r => ({ ...r, scopeStatus: closure.has(r.recipeId) ? 'IN_SCOPE' : 'OUT_OF_SCOPE', runtimeStatus: 'UNKNOWN' }))
}
const BUFF_BY_SHORT = Object.freeze({
  fire_resistance: 'STRONG', regeneration: 'STRONG', strength: 'STRONG', resistance: 'STRONG', absorption: 'STRONG', berserking: 'STRONG',
  speed: 'LIGHT', haste: 'LIGHT', night_vision: 'LIGHT', water_breathing: 'LIGHT', nourishment: 'LIGHT', nutrition: 'LIGHT', saturation: 'LIGHT', warm: 'LIGHT',
  poison: 'NEGATIVE', hunger: 'NEGATIVE', weakness: 'NEGATIVE', slowness: 'NEGATIVE', nausea: 'NEGATIVE', unluck: 'NEGATIVE', bad_omen: 'NEGATIVE'
})
export function extractEffectIds(text) {
  return [...new Set([...String(text || '').toLowerCase().matchAll(/effect\.([a-z0-9_.-]+)\.([a-z0-9_./-]+)/g)].map(m => `${m[1]}:${m[2]}`))]
}
export function classifyBuff(text) {
  const raw = String(text || '')
  const s = raw.toLowerCase()
  if (!s.trim()) return { factor: BUFF_FACTOR.NONE, category: 'NONE', review: false, tokens: [], effectIds: [] }
  const effectIds = extractEffectIds(s)
  const tokens = [...new Set(s.match(/[a-z][a-z0-9_:-]*/g) || [])]
  if (effectIds.length) {
    const classes = []; const unknown = []
    for (const id of effectIds) { const cls = BUFF_BY_FULL_ID[id]; if (cls) classes.push(cls); else unknown.push(id) }
    const classSet = new Set(classes)
    if (unknown.length) return { factor: null, category: 'REVIEW', review: true, tokens, effectIds, unknown }
    if (classSet.has('NEGATIVE') && (classSet.has('STRONG') || classSet.has('LIGHT'))) return { factor: null, category: 'MIXED', review: true, tokens, effectIds }
    if (classSet.has('STRONG')) return { factor: BUFF_FACTOR.STRONG, category: 'STRONG', review: false, tokens, effectIds }
    if (classSet.has('LIGHT')) return { factor: BUFF_FACTOR.LIGHT, category: 'LIGHT', review: false, tokens, effectIds }
    if (classSet.has('NEGATIVE')) return { factor: BUFF_FACTOR.NEGATIVE, category: 'NEGATIVE', review: false, tokens, effectIds }
    return { factor: null, category: 'REVIEW', review: true, tokens, effectIds, unknown }
  }
  if (s.includes('effect.')) return { factor: null, category: 'REVIEW', review: true, tokens, effectIds, unknown: [raw] }
  const candidates = tokens
  if (!candidates.length) return { factor: null, category: 'REVIEW', review: true, tokens, effectIds, unknown: [raw] }
  const classes = []; const unknown = []
  for (const c of candidates) { const cls = BUFF_BY_SHORT[c]; if (cls) classes.push(cls); else unknown.push(c) }
  const classSet = new Set(classes)
  if (unknown.length) return { factor: null, category: 'REVIEW', review: true, tokens, effectIds, unknown }
  if (classSet.has('NEGATIVE') && (classSet.has('STRONG') || classSet.has('LIGHT'))) return { factor: null, category: 'MIXED', review: true, tokens, effectIds }
  if (classSet.has('STRONG')) return { factor: BUFF_FACTOR.STRONG, category: 'STRONG', review: false, tokens, effectIds }
  if (classSet.has('LIGHT')) return { factor: BUFF_FACTOR.LIGHT, category: 'LIGHT', review: false, tokens, effectIds }
  if (classSet.has('NEGATIVE')) return { factor: BUFF_FACTOR.NEGATIVE, category: 'NEGATIVE', review: false, tokens, effectIds }
  return { factor: null, category: 'REVIEW', review: true, tokens, effectIds, unknown }
}

const ITEM_ID_RE = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/
const TAG_ID_RE = /^#[a-z0-9_.-]+:[a-z0-9_./-]+$/
export function validId(id) {
  const s = String(id || '').trim()
  return ITEM_ID_RE.test(s) || TAG_ID_RE.test(s)
}
export function expandIngredient(x, out = []) {
  if (x == null || x === '') return out
  if (Array.isArray(x)) { x.forEach(i => expandIngredient(i, out)); return out }
  if (x.type === 'neoforge:compound' && Array.isArray(x.children)) { x.children.forEach(c => expandIngredient(c, out)); return out }
  out.push(x)
  return out
}
export function ingredientSlots(data) {
  const slots = []
  const addSlot = (x, occurrence = 1, declared = false) => {
    const list = expandIngredient(x)
    if (!declared && list.length === 0) return
    if (list.length === 0) { slots.push({ choices: [], occurrence, status: 'EMPTY' }); return }
    const parsed = []
    let invalid = false
    for (const c of list) {
      const s = parseStack(c)
      if (!s || !s.id) { invalid = true; continue }
      if (!validId(norm(s.id))) { invalid = true; continue }
      if (!validCount(s.count)) { invalid = true; continue }
      parsed.push({ id: norm(s.id), count: s.count })
    }
    slots.push({ choices: parsed, occurrence, status: invalid ? 'INVALID' : 'VALID' })
  }
  if (Array.isArray(data.ingredients)) data.ingredients.forEach(x => addSlot(x, 1, true))
  else if (data.ingredient) addSlot(data.ingredient, 1, true)
  else if (data.input) addSlot(data.input, 1, true)
  if (data.key && Array.isArray(data.pattern)) {
    const counts = new Map()
    for (const row of data.pattern) for (const symbol of row) if (symbol !== ' ') counts.set(symbol, (counts.get(symbol) || 0) + 1)
    for (const [symbol, count] of counts) {
      if (data.key[symbol] === undefined) { slots.push({ choices: [], occurrence: count, status: 'INVALID' }); continue }
      addSlot(data.key[symbol], count, true)
    }
  }
  if (data.base) addSlot(data.base, 1, true)
  if (data.addition) addSlot(data.addition, 1, true)
  return slots
}
export function parseStack(x) {
  if (x == null) return null
  if (typeof x === 'string') return { id: x, count: 1, chance: undefined }
  const chance = x.chance === undefined ? undefined : Number(x.chance)
  if (x.item && typeof x.item === 'object' && x.item !== null) {
    const inner = x.item
    const id = typeof inner === 'string' ? inner : (typeof inner.id === 'string' ? inner.id : idOf(inner))
    const count = Number(inner.count ?? inner.amount ?? 1)
    return { id, count, chance }
  }
  if (x.item && typeof x.item === 'string') return { id: x.item, count: Number(x.count ?? x.amount ?? 1), chance }
  if (x.id) return { id: x.id, count: Number(x.count ?? x.amount ?? 1), chance }
  if (x.tag) return { id: `#${x.tag}`, count: Number(x.count ?? x.amount ?? 1), chance }
  return null
}
export function validCount(n) { return Number.isFinite(n) && n > 0 && Number.isInteger(n) }
export function results(data) {
  const x = data.result || data.output || data.results
  const list = Array.isArray(x) ? x : x ? [x] : []
  const outputs = []
  let probabilistic = false
  for (const raw of list) {
    const stack = parseStack(raw)
    if (!stack || !stack.id || !validId(stack.id)) return null
    if (!validCount(stack.count)) return null
    if (stack.chance !== undefined && !(Number.isFinite(stack.chance) && stack.chance > 0 && stack.chance <= 1)) return null
    if (stack.chance !== undefined && stack.chance < 1) probabilistic = true
    outputs.push({ id: stack.id, count: stack.count, chance: stack.chance === undefined ? null : stack.chance })
  }
  return { outputs, probabilistic }
}
function readJson(jar, entry) { return JSON.parse(execFileSync('unzip', ['-p', jar, entry], { encoding: 'utf8' })) }

export function containerDecision(data, prices, evidence = new Map(), recipeId = '') {
  if (!data || !data.container) return { cost: 0, text: 'NONE', risk: [] }
  const id = norm(idOf(data.container)); const p = prices.get(id)
  if (!id) return { cost: 0, text: 'CONTAINER_SEMANTICS_REVIEW:dynamic', risk: ['CONTAINER_SEMANTICS_REVIEW'] }
  const decision = evidence.get(recipeId) || 'REVIEW'
  if (decision === 'CONSUMED' || decision === 'PART_OF_RESULT') {
    if (!p || p.confidence === PRICE_CONFIDENCE.REVIEW) return { cost: 0, type: decision, text: `${decision}:${id}`, risk: ['PRICE_SOURCE_REVIEW'], priceConfidence: p?.confidence || null }
    return { cost: p.value, type: decision, text: `${decision}:${id}=${p.value}`, risk: [], priceConfidence: p.confidence }
  }
  if (decision === 'RETURNED' || decision === 'REUSABLE') return { cost: 0, type: decision, text: `${decision}:${id}=0`, risk: [] }
  return { cost: 0, type: 'REVIEW', text: `REVIEW:${id}`, risk: ['CONTAINER_SEMANTICS_REVIEW'] }
}

export function chooseAlternative(choices, resolveCost) {
  const candidates = choices.map((id, index) => ({ id, index, result: resolveCost(id) })).filter(x => x.result && x.result.unitCost != null)
  candidates.sort((a, b) => a.result.unitCost - b.result.unitCost || a.id.localeCompare(b.id))
  return candidates[0] || null
}

const BLOCKING_RISKS = new Set(['MISSING_PRICE', 'TAG_PRICE_MISSING', 'RECIPE_CYCLE', 'CONTAINER_SEMANTICS_REVIEW', 'ZERO_COST', 'CO_PRODUCT_ALLOCATION_REVIEW', 'INVALID_INGREDIENT_STACK', 'PROBABILISTIC_OUTPUT_REVIEW', 'PRICE_SOURCE_REVIEW'])
export const ADVISORY_RISKS = new Set(['PROVISIONAL_PRICE_ANCHOR'])
export function isBlockingRisk(r) { return BLOCKING_RISKS.has(r) }

function directResult(id, engine) {
  const direct = engine.prices.get(id)
  if (!direct || direct.confidence === PRICE_CONFIDENCE.REVIEW) return null
  const provisional = direct.confidence === PRICE_CONFIDENCE.PROVISIONAL ? [`${id}=${direct.value}(${direct.source})`] : []
  const advisory = provisional.length ? ['PROVISIONAL_PRICE_ANCHOR'] : []
  return { unitCost: direct.value, rawCost: direct.value, outputCount: 1, recipeId: '', recipeType: 'DIRECT_PRICE', sourceJar: '', sourceRecipePath: '', ingredientChoices: [], containerDecision: 'NONE', priceValue: direct.value, priceSource: direct.source, priceConfidence: direct.confidence, provisionalAnchors: provisional, blocking: [], advisory, risk: advisory, evidenceChain: [`${id}=${direct.value}(${direct.source})`] }
}
export function resolveCost(id, engine, path = []) {
  const direct = directResult(id, engine)
  if (direct && direct.priceConfidence === PRICE_CONFIDENCE.DEFINED) return direct
  if (path.includes(id)) return { unitCost: null, rawCost: null, evidenceChain: [...path, id], blocking: ['RECIPE_CYCLE'], advisory: [], risk: ['RECIPE_CYCLE'] }
  const candidates = (engine.recipesByProduct.get(id) || []).map(r => evaluateCandidate(r, engine, path))
  const valid = candidates.filter(c => c.rawCost != null)
  const defPaths = valid.filter(c => c.blocking.length === 0 && c.advisory.length === 0).sort((a, b) => a.unitCost - b.unitCost || a.recipeId.localeCompare(b.recipeId))
  const provPaths = valid.filter(c => c.blocking.length === 0 && c.advisory.length > 0).sort((a, b) => a.unitCost - b.unitCost || a.recipeId.localeCompare(b.recipeId))
  if (defPaths.length) return defPaths[0]
  if (direct && direct.priceConfidence === PRICE_CONFIDENCE.PROVISIONAL) {
    const pool = [direct, ...provPaths].sort((a, b) => a.unitCost - b.unitCost || (a.recipeId || '').localeCompare(b.recipeId || ''))
    return pool[0]
  }
  if (provPaths.length) return provPaths[0]
  return candidates[0] || { unitCost: null, rawCost: null, evidenceChain: [...path, id], blocking: ['MISSING_PRICE'], advisory: [], risk: ['MISSING_PRICE'] }
}
export function evaluateCandidate(r, engine, path = []) {
  let raw = 0; const choices = []; const blocking = []; const advisory = []; const childEvidence = []; const provisionalAnchors = []; const chain = [...path, r.output.id]
  if (r.coProduct) blocking.push('CO_PRODUCT_ALLOCATION_REVIEW')
  if (r.probabilistic) blocking.push('PROBABILISTIC_OUTPUT_REVIEW')
  for (let i = 0; i < r.slots.length; i++) {
    const slot = r.slots[i]
    if (!slot) continue
    if (slot.status === 'INVALID') { blocking.push('INVALID_INGREDIENT_STACK'); continue }
    if (slot.status === 'EMPTY') continue
    const outcomes = slot.choices.map((c, alternativeIndex) => { const ch = typeof c === 'string' ? { id: c, count: 1 } : c; return { dep: ch.id, index: alternativeIndex, count: ch.count ?? 1, result: resolveCost(ch.id, engine, chain) } })
    const usable = outcomes.filter(o => o.result && o.result.unitCost != null)
    const evidenceRank = o => (o.result.advisory || []).length === 0 ? 0 : 1
    const totalCost = o => o.result.unitCost * o.count * slot.occurrence
    const selected = usable.length ? [...usable].sort((a, b) => { const ra = evidenceRank(a), rb = evidenceRank(b); if (ra !== rb) return ra - rb; const ta = totalCost(a), tb = totalCost(b); if (ta !== tb) return ta - tb; return a.dep.localeCompare(b.dep) })[0] : null
    if (!selected) {
      const cycleHit = outcomes.some(o => (o.result.risk || []).includes('RECIPE_CYCLE'))
      outcomes.forEach(o => {
        const kind = o.dep.startsWith('#') ? 'TAG' : 'ITEM'
        engine.onGap({ target: engine.rootTargetItemId || r.output.id, tier: engine.rootTargetTier || '', recipe: r.recipeId, dep: o.dep, kind, chain: [...chain, o.dep].join(' -> '), slot: i, alt: o.index, occurrence: slot.occurrence, hasAlt: false, sourceJar: r.sourceJar, sourceRecipePath: r.sourceRecipePath, action: '补充直接 ID/tag 锚点或人工确认成员' })
      })
      blocking.push(...(cycleHit ? ['RECIPE_CYCLE'] : []))
      blocking.push(...outcomes.map(o => o.dep.startsWith('#') ? 'TAG_PRICE_MISSING' : 'MISSING_PRICE'))
      continue
    }
    const totalSlotCost = totalCost(selected)
    raw += totalSlotCost
    choices.push(`${selected.dep}×${selected.count * slot.occurrence}`)
    blocking.push(...(selected.result.blocking || []))
    advisory.push(...(selected.result.advisory || []))
    provisionalAnchors.push(...(selected.result.provisionalAnchors || []))
    if (selected.result.evidenceChain) childEvidence.push(...selected.result.evidenceChain)
  }
  const container = containerDecision(r.data, engine.prices, engine.containerEvidence, r.recipeId)
  raw += container.cost; blocking.push(...container.risk)
  if (r.data && r.data.container) {
    const cid = norm(idOf(r.data.container))
    if (container.risk.length) engine.onGap({ target: engine.rootTargetItemId || r.output.id, tier: engine.rootTargetTier || '', recipe: r.recipeId, dep: cid, kind: 'CONTAINER', chain: [...chain, cid].join(' -> '), slot: r.slots.length, alt: -1, occurrence: 1, hasAlt: false, sourceJar: r.sourceJar, sourceRecipePath: r.sourceRecipePath, action: '确认容器是否消耗、返还或属于成品' })
    if ((container.type === 'CONSUMED' || container.type === 'PART_OF_RESULT') && container.priceConfidence === PRICE_CONFIDENCE.PROVISIONAL && container.cost > 0) { const p = engine.prices.get(cid); if (p) provisionalAnchors.push(`${cid}=${p.value}(${p.source})`) }
  }
  if (raw <= 0) blocking.push('ZERO_COST')
  if (provisionalAnchors.length) advisory.push('PROVISIONAL_PRICE_ANCHOR')
  const risk = [...new Set([...blocking, ...advisory])]
  const valid = blocking.length === 0
  const evidenceChain = [`${r.output.id} <- recipe=${r.recipeId};type=${r.type};output=${r.output.count};choices=${choices.join(' + ')};container=${container.text}`, ...childEvidence]
  return { recipeId: r.recipeId, recipeType: r.type, type: r.type, outputCount: r.output.count, rawCost: valid ? raw : null, unitCost: valid ? raw / r.output.count : Infinity, ingredientChoices: choices, containerDecision: container, sourceJar: r.sourceJar, sourceRecipePath: r.sourceRecipePath, evidenceChain, blocking: [...new Set(blocking)], advisory: [...new Set(advisory)], provisionalAnchors: [...new Set(provisionalAnchors)], risk }
}
export function evaluateTargetCandidates(data, target, onGap = () => {}, containerEvidence) {
  const evidence = containerEvidence === undefined ? (data.containerEvidence || new Map()) : containerEvidence
  const engine = { prices: data.prices, recipesByProduct: data.byProduct, containerEvidence: evidence, rootTargetItemId: target.id, rootTargetTier: target.tier, onGap }
  const candidates = (data.byProduct.get(target.id) || []).map(r => evaluateCandidate(r, engine)).sort((a, b) => a.unitCost - b.unitCost || a.recipeId.localeCompare(b.recipeId))
  const selected = candidates.find(x => x.rawCost != null)
  return { engine, candidates, selected, displayed: selected || candidates[0] }
}

export function validateManualTable(rows) {
  const errors = []
  const seen = new Map()
  for (const r of rows) {
    if (r.length !== MANUAL_HEADER.length) { errors.push(`column count ${r.length} != ${MANUAL_HEADER.length}`); continue }
    const dep = val(r, MANUAL_HEADER, 'dependencyId')
    const kind = val(r, MANUAL_HEADER, 'dependencyKind')
    const decision = val(r, MANUAL_HEADER, 'decision')
    const proposed = val(r, MANUAL_HEADER, 'proposedPrice')
    const key = `${dep}|${kind}`
    if (!dep) errors.push('empty dependencyId')
    if (!validId(dep)) errors.push(`illegal dependencyId: ${dep}`)
    if (seen.has(key)) errors.push(`duplicate row: ${key}`)
    seen.set(key, true)
    if (!['ITEM', 'TAG', 'CONTAINER'].includes(kind)) errors.push(`illegal dependencyKind: ${dep} -> ${kind}`)
    if (!MANUAL_DECISIONS.includes(decision)) errors.push(`illegal decision: ${dep} -> ${decision}`)
    if (dep.includes('..')) errors.push(`suspicious path: ${dep}`)
    if (decision === 'DEFINED_CANDIDATE' || decision === 'PROVISIONAL') {
      if (proposed === '') errors.push(`missing proposedPrice: ${dep}`)
      else { const n = Number(proposed); if (!Number.isInteger(n) || n <= 0) errors.push(`illegal proposedPrice: ${dep} -> ${proposed}`) }
      for (const f of ['evidenceType', 'evidenceDetail', 'rationale', 'economicRisk', 'status']) if (!val(r, MANUAL_HEADER, f)) errors.push(`missing ${f}: ${dep}`)
    } else if (proposed !== '') errors.push(`REVIEW/EXCLUDED must not carry price: ${dep}`)
  }
  return errors
}
export function loadManualTableStrict(manualCsv) {
  if (!manualCsv || manualCsv.header.length !== MANUAL_HEADER.length || manualCsv.header.some((h, i) => h !== MANUAL_HEADER[i])) throw new Error('人工定价表表头与 15 列结构不符')
  const errors = validateManualTable(manualCsv.rows)
  if (errors.length) throw new Error(`人工定价表校验失败: ${errors.join(';')}`)
  return manualCsv.rows
}
export function loadManualPrices(manualRows) {
  const out = new Map()
  for (const r of manualRows) {
    const dep = val(r, MANUAL_HEADER, 'dependencyId')
    const decision = val(r, MANUAL_HEADER, 'decision')
    if (decision !== 'DEFINED_CANDIDATE' && decision !== 'PROVISIONAL') continue
    const proposed = Number(val(r, MANUAL_HEADER, 'proposedPrice'))
    out.set(dep, { value: proposed, source: `人工定价(${decision}):${val(r, MANUAL_HEADER, 'evidenceType')}`, confidence: decision === 'DEFINED_CANDIDATE' ? PRICE_CONFIDENCE.DEFINED : PRICE_CONFIDENCE.PROVISIONAL, manual: true })
  }
  return out
}
export function mergeManualPrices(base, manual) {
  const merged = new Map(base)
  for (const [id, mp] of manual) {
    const existing = merged.get(id)
    if (mp.confidence === PRICE_CONFIDENCE.DEFINED) { merged.set(id, mp); continue }
    if (existing && existing.confidence === PRICE_CONFIDENCE.DEFINED) continue
    merged.set(id, mp)
  }
  return merged
}
export function checkManualCoverage(manualKeys, baselineKeys) {
  const missing = [...baselineKeys].filter(k => !manualKeys.has(k))
  const extra = [...manualKeys].filter(k => !baselineKeys.has(k))
  return { missing, extra }
}

export async function loadPricingData({ root = ROOT } = {}) {
  const table = resolve(root, '配方与经济管理/统一配方表'); const server = resolve(root, 'Server')
  const handheld = parseCsvTable(await readFile(resolve(table, '新增食物模组普通手持料理清单.csv'), 'utf8'))
  const authorityTable = parseCsvTable(await readFile(resolve(table, '食物三档分类表.csv'), 'utf8'))
  const pricesCsv = parseCsvTable(await readFile(resolve(table, '原料单价参考表.csv'), 'utf8'))
  JSON.parse(await readFile(resolve(server, 'kubejs/config/food_recipe_export.json'), 'utf8'))
  const basePrices = new Map()
  for (const r of pricesCsv.rows) { const id = norm(val(r, pricesCsv.header, '物品ID')); const n = Number(val(r, pricesCsv.header, '单价_铜币')); const source = val(r, pricesCsv.header, '来源'); if (id && Number.isFinite(n)) basePrices.set(id, { value: n, source, confidence: priceConfidenceOf(source) }) }
  const manualRows = loadManualTableStrict(parseCsvTable(await readFile(resolve(table, '新增食物模组悬赏原料锚点人工定价表.csv'), 'utf8')))
  const manualPrices = loadManualPrices(manualRows)
  const mergedPrices = mergeManualPrices(basePrices, manualPrices)
  const targets = handheld.rows.map(r => ({ id: val(r, handheld.header, '产物ID'), tier: val(r, handheld.header, '建议厨师档次'), effects: '' }))
  for (const r of authorityTable.rows) if (val(r, authorityTable.header, '入池说明') === '6D整盘分食单份产物') targets.push({ id: val(r, authorityTable.header, '产物ID'), tier: 'T2', effects: val(r, authorityTable.header, '效果buff') })
  const targetMap = new Map(targets.map(x => [x.id, x])); const recipes = []
  const jars = (await readdir(resolve(server, 'mods'))).filter(n => n.endsWith('.jar') && JAR_PATTERNS.some(p => p.test(n)))
  for (const jar of jars) { const path = resolve(server, 'mods', jar); const entries = execFileSync('unzip', ['-Z1', path], { encoding: 'utf8' }).split(/\r?\n/).filter(e => /^data\/[^/]+\/recipe\/.+\.json$/.test(e)); for (const e of entries) { try { const data = readJson(path, e); const out = results(data); if (!out) continue; const coProduct = new Set(out.outputs.map(o => o.id)).size > 1; for (const output of out.outputs) recipes.push({ recipeId: `${e.slice(5, e.indexOf('/recipe/'))}:${e.slice(e.indexOf('/recipe/') + 8, -5)}`, type: data.type || '', data, output, slots: ingredientSlots(data), sourceJar: jar, sourceRecipePath: e, coProduct, probabilistic: out.probabilistic }) } catch {} } }
  const byProduct = new Map(); for (const r of recipes) { if (!byProduct.has(r.output.id)) byProduct.set(r.output.id, []); byProduct.get(r.output.id).push(r) }
  const containerEvidence = buildContainerEvidence(byProduct).decisionById
  const staticTags = loadStaticTags(jars, server)
  return { basePrices, mergedPrices, prices: mergedPrices, manualRows, manualPrices, byProduct, targets, targetMap, authorityTable, containerEvidence, staticTags }
}

export function buildAnchorTable(missingRows, prices) {
  const groups = new Map()
  for (const r of missingRows) {
    const dep = r[3]; const kind = r[4]; const key = `${dep}|${kind}`
    if (!groups.has(key)) groups.set(key, { dep, kind, rows: [], targets: new Set(), tiers: new Set() })
    const g = groups.get(key); g.rows.push(r); g.targets.add(r[0]); g.tiers.add(r[1])
  }
  const out = []
  for (const g of [...groups.values()].sort((a, b) => a.kind.localeCompare(b.kind) || a.dep.localeCompare(b.dep))) {
    const price = prices.get(g.dep)
    const confidence = price?.confidence || PRICE_CONFIDENCE.REVIEW
    const isContainer = g.kind === 'CONTAINER'
    let decision = '', suggestedPrice = '', suggestedEvidence = '', econRisk = ''
    if (isContainer) { decision = 'BLOCKED'; econRisk = '容器语义未确认，禁止仅因价格存在判定消耗' }
    else if (g.kind === 'TAG') {
      if (price && confidence === PRICE_CONFIDENCE.DEFINED) { decision = 'DEFINED'; suggestedPrice = price.value; suggestedEvidence = `原料单价参考表锚点 来源=${price.source}` }
      else { decision = price ? 'PROVISIONAL' : 'REVIEW'; econRisk = 'TAG 无成员价格证据，不得伪装为已验证具体物品' }
    } else {
      if (price && confidence === PRICE_CONFIDENCE.DEFINED) { decision = 'DEFINED'; suggestedPrice = price.value; suggestedEvidence = `原料单价参考表来源=${price.source}` }
      else if (price && confidence === PRICE_CONFIDENCE.PROVISIONAL) { decision = 'PROVISIONAL'; econRisk = '仅猜想锚点，禁止进入正式池' }
      else { decision = 'REVIEW'; econRisk = '缺少直接价格锚点' }
    }
    out.push([g.dep, g.kind, g.rows.length, g.targets.size, [...g.targets].sort().join(';'), [...g.tiers].sort().join(';'), price?.value ?? '', price?.source ?? '', confidence, isContainer ? 'REVIEW' : '', suggestedPrice === '' ? '' : suggestedPrice, suggestedEvidence, econRisk, decision])
  }
  return out
}

export function buildEffectTable(targets, authorityTable) {
  const groups = new Map()
  const evidenceSource = '食物三档分类表.csv 效果buff'
  for (const target of targets) {
    const authorityRow = authorityTable.rows.find(r => val(r, authorityTable.header, '产物ID') === target.id)
    const buffText = target.effects || val(authorityRow || [], authorityTable.header, '效果buff')
    for (const id of extractEffectIds(buffText)) {
      if (!groups.has(id)) groups.set(id, { id, targets: new Set() })
      groups.get(id).targets.add(target.id)
    }
  }
  const out = []
  for (const g of [...groups.values()].sort((a, b) => a.id.localeCompare(b.id))) {
    const ns = g.id.split(':')[0]
    const cat = EFFECT_CATEGORY[g.id] || null
    const cls = BUFF_BY_FULL_ID[g.id] || null
    let positiveOrNegative = 'UNKNOWN', suggestedClass = '', factor = '', decision = 'REVIEW', reviewReason = '模组效果未确认，需服务器实际 JAR/匹配版本源码或 javap 证据，不得按名称猜测'
    if (cls) {
      decision = 'DEFINED'; suggestedClass = cls; factor = BUFF_FACTOR[cls]
      if (cat) { positiveOrNegative = cat === 'HARMFUL' ? 'negative' : cat === 'NEUTRAL' ? 'neutral' : 'positive'; reviewReason = EFFECT_BEHAVIOR_EVIDENCE[g.id] || `javap(MobEffectCategory.${cat}) 安装JAR` }
      else { positiveOrNegative = cls === 'NEGATIVE' ? 'negative' : 'positive'; reviewReason = '原版/已批准因子' }
    } else if (cat) {
      decision = 'REVIEW'; positiveOrNegative = cat.toLowerCase(); suggestedClass = ''; factor = ''
      reviewReason = (EFFECT_BEHAVIOR_EVIDENCE[g.id] || `javap(MobEffectCategory.${cat}) 安装JAR；正负未定，禁止猜价`) + (EFFECT_CATEGORY[g.id] === 'BENEFICIAL' ? '；BENEFICIAL 仅方向证据，行为强度证据不足，保持 REVIEW' : '')
    }
    out.push([g.id, ns, g.targets.size, [...g.targets].sort().join(';'), `javap(MobEffectCategory.${cat || 'KNOWN'}) 食物三档分类表.csv 效果buff`, positiveOrNegative, suggestedClass, factor, decision, reviewReason])
  }
  return out
}
const TAG_AUDIT_HEADER = ['tagId', 'runtimeMemberCount', 'memberIds', 'replace来源', '数据包来源', '已定价成员数', '最低可信成员价', '价格置信度', '建议决策', '风险', 'evidence']
export function buildTagAuditTable(manualRows) {
  const out = []
  for (const r of manualRows) {
    if (val(r, MANUAL_HEADER, 'dependencyKind') !== 'TAG') continue
    const tag = val(r, MANUAL_HEADER, 'dependencyId')
    out.push([tag, 'UNKNOWN', '', 'UNKNOWN', 'RUNTIME_BLOCKED', '', '', 'REVIEW', 'REVIEW', '运行时导出 BLOCKED，成员无法核验；不得凭标签名称猜价', '其他生产服务端在运行，未启动当前服务端；c: 标签成员为运行时约定'])
  }
  return out
}

function buildTargetRow(data, target, prices, onGap, containerEvidence) {
  const { candidates, selected, displayed } = evaluateTargetCandidates({ ...data, prices }, target, onGap, containerEvidence)
  const authorityRow = data.authorityTable.rows.find(r => val(r, data.authorityTable.header, '产物ID') === target.id)
  const buffText = target.effects || val(authorityRow || [], data.authorityTable.header, '效果buff')
  const buff = classifyBuff(buffText); const tier = target.tier; const common = tier === 'COMMON' ? 0.95 : 1
  const rowRisk = [...new Set([...(displayed?.risk || []), ...candidates.filter(x => x !== selected).flatMap(x => x.risk.map(y => `ALTERNATIVE_${y}`)), ...(candidates.length ? [] : ['NO_STATIC_RECIPE'])])]
  if (selected?.type?.startsWith('create:')) rowRisk.push('AUTOMATION_REVIEW')
  if (buff.review) rowRisk.push(buff.category === 'MIXED' ? 'MIXED_BUFF_REVIEW' : 'UNKNOWN_BUFF')
  const ctext = displayed?.containerDecision?.text || ''
  const provisionalNote = selected?.provisionalAnchors?.length ? `;猜想锚点:${selected.provisionalAnchors.join('|')}` : ''
  const reasons = rowRisk.join(';') + provisionalNote
  const riskEntry = reasons ? [target.id, displayed?.recipeId || '', displayed?.sourceJar || '', displayed?.sourceRecipePath || '', reasons, target.id, displayed?.evidenceChain?.join(' -> ') || '无可确认静态路径', '补齐证据后复审，不写入正式池', 'REVIEW'] : null
  const unit = selected && !buff.review ? calculateWorth(selected.unitCost, tier, buff.factor, common) : ''
  const row = [target.id, tier, displayed?.recipeId || '', machine(displayed?.type), displayed?.outputCount || '', selected?.rawCost?.toFixed(2) || '', selected?.unitCost?.toFixed(2) || '', tierDifficulty(tier), buff.factor ?? 'REVIEW', common, unit, 'STALE', 'false', 'JAR', displayed?.sourceJar || '', displayed?.sourceRecipePath || '', displayed?.ingredientChoices?.join(' + ') || '', ctext, displayed?.evidenceChain?.join(' -> ') || '', rowRisk.join(';'), reasons || '']
  return { row, priced: unit !== '', riskEntry, ctext }
}

export async function buildPreview({ root = ROOT } = {}) {
  const data = await loadPricingData({ root })
  const { targets, targetMap, authorityTable } = data
  const sortedTargets = [...targetMap.values()].sort((a, b) => a.id.localeCompare(b.id))
  const coverageKeys = new Set(); const coverageAddMissing = (g) => { coverageKeys.add(`${g.dep}|${g.kind}`) }
  for (const target of sortedTargets) buildTargetRow(data, target, data.basePrices, coverageAddMissing, new Map())
  const manualKeys = new Set(data.manualRows.map(r => `${val(r, MANUAL_HEADER, 'dependencyId')}|${val(r, MANUAL_HEADER, 'dependencyKind')}`))
  const coverage = checkManualCoverage(manualKeys, coverageKeys)
  if (coverage.missing.length || coverage.extra.length) throw new Error(`人工定价表与基线锚点集不一致: 缺失 ${coverage.missing.join(',')} / 多余 ${coverage.extra.join(',')}`)
  const basePriced = new Set()
  for (const target of sortedTargets) { const { priced } = buildTargetRow(data, target, data.basePrices, () => {}, data.containerEvidence); if (priced) basePriced.add(target.id) }
  const missing = []; const risks = []; const missingKeys = new Set()
  const addMissing = (g) => { const key = [g.target, g.recipe, g.dep, g.kind, g.slot, g.alt].join('|'); if (!missingKeys.has(key)) { missingKeys.add(key); missing.push([g.target, g.tier, g.recipe, g.dep, g.kind, g.chain, g.slot, g.alt, g.occurrence, g.hasAlt ? '是' : '否', g.sourceJar, g.sourceRecipePath, g.action, 'REVIEW']) } }
  const rows = []
  for (const target of sortedTargets) { const { row, riskEntry } = buildTargetRow(data, target, data.mergedPrices, addMissing, data.containerEvidence); rows.push(row); if (riskEntry) risks.push(riskEntry) }
  const pricedRows = rows.filter(r => r[10] !== '')
  const pricedProvisional = pricedRows.filter(r => (r[19] || '').includes('PROVISIONAL_PRICE_ANCHOR'))
  const pricedDefined = pricedRows.length - pricedProvisional.length
  const blocked = rows.filter(r => r[10] === '' && r[19] !== '')
  const unknownBuff = rows.filter(r => r[19].includes('UNKNOWN_BUFF')).length
  const mixedBuff = rows.filter(r => r[19].includes('MIXED_BUFF_REVIEW')).length
  const containerReview = rows.filter(r => (r[17] || '').startsWith('REVIEW:') || (r[17] || '').startsWith('CONTAINER_SEMANTICS_REVIEW:')).length
  const anchors = buildAnchorTable(missing, data.mergedPrices)
  const effects = buildEffectTable(targets, authorityTable)
  const unknownEffects = effects.filter(e => e[8] !== 'DEFINED')
  const manualByDecision = { DEFINED_CANDIDATE: 0, PROVISIONAL: 0, REVIEW: 0, EXCLUDED: 0 }
  for (const r of data.manualRows) { const d = val(r, MANUAL_HEADER, 'decision'); if (manualByDecision[d] !== undefined) manualByDecision[d]++ }
  const mergedPriced = new Set(pricedRows.map(r => r[0]))
  const newComputable = [...mergedPriced].filter(id => !basePriced.has(id))
  const blockedBy = (re) => blocked.filter(r => (r[19] || '').split(';').some(x => re.test(x))).length
  const blockedStats = { MISSING_PRICE: blockedBy(/^(MISSING_PRICE|TAG_PRICE_MISSING)$/), CONTAINER_SEMANTICS_REVIEW: blockedBy(/^CONTAINER_SEMANTICS_REVIEW$/), PROBABILISTIC_OUTPUT_REVIEW: blockedBy(/^PROBABILISTIC_OUTPUT_REVIEW$/), CO_PRODUCT_ALLOCATION_REVIEW: blockedBy(/^CO_PRODUCT_ALLOCATION_REVIEW$/), UNKNOWN_MIXED_BUFF: blockedBy(/^(UNKNOWN_BUFF|MIXED_BUFF_REVIEW)$/), RECIPE_CYCLE: blockedBy(/^RECIPE_CYCLE$/), INVALID_INGREDIENT_STACK: blockedBy(/^INVALID_INGREDIENT_STACK$/), ZERO_COST: blockedBy(/^ZERO_COST$/) }
  const counts = { COMMON: targets.filter(x => x.tier === 'COMMON').length, T2: targets.filter(x => x.tier === 'T2').length, T3: targets.filter(x => x.tier === 'T3').length, PRICED: pricedRows.length, PRICED_DEFINED: pricedDefined, PRICED_PROVISIONAL: pricedProvisional.length, BLOCKED: blocked.length, UNKNOWN_BUFF: unknownBuff, MIXED_BUFF: mixedBuff, CONTAINER_REVIEW: containerReview, UNIQUE_ANCHORS: anchors.length, UNIQUE_UNKNOWN_EFFECTS: unknownEffects.length, MANUAL_DEFINED: manualByDecision.DEFINED_CANDIDATE, MANUAL_PROVISIONAL: manualByDecision.PROVISIONAL, MANUAL_REVIEW: manualByDecision.REVIEW, MANUAL_EXCLUDED: manualByDecision.EXCLUDED, NEW_COMPUTABLE: newComputable.length, BASE_PRICED: basePriced.size, ...blockedStats }
  const invalidIngredientRecipes = new Set(); const probabilisticRecipeIds = new Set(); const coProductRecipeIds = new Set()
  for (const recipesById of data.byProduct.values()) for (const r of recipesById) { if (r.slots.some(s => s && s.status === 'INVALID')) invalidIngredientRecipes.add(r.recipeId); if (r.probabilistic) probabilisticRecipeIds.add(r.recipeId); if (r.coProduct) coProductRecipeIds.add(r.recipeId) }
  const pending = data.manualRows.filter(r => ['REVIEW', 'EXCLUDED'].includes(val(r, MANUAL_HEADER, 'decision'))).map(r => [val(r, MANUAL_HEADER, 'dependencyId'), val(r, MANUAL_HEADER, 'dependencyKind'), val(r, MANUAL_HEADER, 'affectedTargetCount'), val(r, MANUAL_HEADER, 'decision'), val(r, MANUAL_HEADER, 'rationale'), val(r, MANUAL_HEADER, 'status')])
  const containerRows = buildContainerSemanticsTable(data.byProduct, computeRecipeClosure(data.byProduct, targets, data.staticTags))
  const partOfResultIds = new Set(containerRows.filter(c => c.decision === 'PART_OF_RESULT').map(c => c.containerId))
  const containerAnchors = data.manualRows.filter(r => val(r, MANUAL_HEADER, 'dependencyKind') === 'CONTAINER').map(r => val(r, MANUAL_HEADER, 'dependencyId'))
  const confirmedContainers = containerAnchors.filter(id => partOfResultIds.has(id)).length
  const tagAudit = buildTagAuditTable(data.manualRows)
  const runtime = { status: 'BLOCKED', reason: '其他生产服务端(Mc_Server_0.1, PID 2066, port 19764)仍在运行，非安全停机窗口；未启动当前服务端，避免同机资源争用', tagMembers: 'UNKNOWN', datapacks: 'UNKNOWN', unite: 'UNKNOWN' }
  const inScopeContainers = containerRows.filter(c => c.scopeStatus === 'IN_SCOPE').length
  const coverageMd = `# 6E.0.6 运行时权威导出（RUNTIME BLOCKED）\n\n- RUNTIME EXPORT：${runtime.status}（${runtime.reason}）。\n- 静态收口：170 项目标递归依赖闭包已计算，容器语义表 ${containerRows.length} 条中 IN_SCOPE ${inScopeContainers} 条 / OUT_OF_SCOPE ${containerRows.length - inScopeContainers} 条；已确认无返还路径写 NONE；所有静态配方 runtimeStatus=UNKNOWN（覆盖无法静态确认）。\n- 容器锚点：已确认 ${confirmedContainers} 个（PART_OF_RESULT），仍 REVIEW ${containerAnchors.length - confirmedContainers} 个。\n- TAG 审计：${tagAudit.length} 个待审 TAG，runtimeMemberCount=UNKNOWN（运行时导出 BLOCKED），全部 REVIEW。\n- 效果审计：javap 行为证据确认 ${effects.filter(e => e[8] === 'DEFINED').length} 个，仍 REVIEW ${effects.filter(e => e[8] !== 'DEFINED').length} 个。\n- 人工决策分布：DEFINED_CANDIDATE ${counts.MANUAL_DEFINED} / PROVISIONAL ${counts.MANUAL_PROVISIONAL} / REVIEW ${counts.MANUAL_REVIEW} / EXCLUDED ${counts.MANUAL_EXCLUDED}，共 ${data.manualRows.length} 行。\n- 基线可计算 ${counts.BASE_PRICED} 项；合并后可计算 ${counts.PRICED} 项；真实 NEW_COMPUTABLE = ${counts.NEW_COMPUTABLE} 项${newComputable.length ? '（' + newComputable.join('、') + '）' : ''}。\n- 仍阻断料理：${counts.BLOCKED} 项（缺价/缺标签 ${counts.MISSING_PRICE}、概率 ${counts.PROBABILISTIC_OUTPUT_REVIEW}、共产品 ${counts.CO_PRODUCT_ALLOCATION_REVIEW}、效果 ${counts.UNKNOWN_MIXED_BUFF}、循环 ${counts.RECIPE_CYCLE}、非法原料 ${counts.INVALID_INGREDIENT_STACK}、零成本 ${counts.ZERO_COST}）。\n- 人工定价表只读；RUNTIME EXPORT STALE；PREVIEW ONLY。\n`
  const summary = `# 6E.0.6 运行时权威导出（RUNTIME BLOCKED）\n\n- SCOPE PASS：${targets.length} 项，COMMON ${counts.COMMON}、T2 ${counts.T2}、T3 ${counts.T3}。\n- RUNTIME EXPORT BLOCKED：其他生产服务端仍在运行（PID 2066，端口 19764），非安全停机窗口，未启动当前服务端；配方/TAG 成员/数据包/UNITE 最终状态仍未运行时核验。\n- 静态收口：170 项目标递归依赖闭包已计算；容器表 IN_SCOPE ${inScopeContainers}/${containerRows.length}，无返还路径写 NONE，静态配方 runtimeStatus=UNKNOWN。\n- CONTAINER：${containerAnchors.length} 个容器锚点，已确认 ${confirmedContainers} 个（PART_OF_RESULT）；recipeId 作用域 + 置信度硬阻断。\n- EFFECT：javap 行为证据，已确认 ${effects.filter(e => e[8] === 'DEFINED').length} / 仍 REVIEW ${effects.filter(e => e[8] !== 'DEFINED').length}。\n- TAG：${tagAudit.length} 个待审 TAG 全部 REVIEW。\n- PRICE PROVENANCE：可计算暂定价格 ${counts.PRICED} 项（DEFINED-only ${counts.PRICED_DEFINED}、含 PROVISIONAL ${counts.PRICED_PROVISIONAL}）；NEW_COMPUTABLE = ${counts.NEW_COMPUTABLE}；blocking ${counts.BLOCKED}。\n- runtimeStatus=STALE；runtimeVerified=false；staticEvidence=JAR。\n- FORMAL BOUNTIFUL POOLS NOT MODIFIED；SERVER NOT STARTED；PLAYER LIVE NOT TESTED；8E WORKTREE PRESERVED；commit/push NOT DONE。\n`
  return { rows: [PREVIEW_HEADER, ...rows], missing: [MISSING_HEADER, ...missing.sort((a, b) => a.join('|').localeCompare(b.join('|')))], risks: [RISK_HEADER, ...risks.sort((a, b) => a.join('|').localeCompare(b.join('|')))], anchors: [ANCHOR_HEADER, ...anchors], effects: [EFFECT_HEADER, ...effects], containerSemantics: [CONTAINER_HEADER, ...containerRows.map(c => [c.device, c.recipeId, c.containerId, c.resultItem, c.returnPath, c.evidence, c.decision, c.scopeStatus, c.runtimeStatus])], tagAudit: [TAG_AUDIT_HEADER, ...tagAudit], coverage: coverageMd, pending: [PENDING_HEADER, ...pending.sort((a, b) => a[0].localeCompare(b[0]))], summary, counts, targets, runtime, basePricedIds: [...basePriced].sort(), mergedPricedIds: [...mergedPriced].sort() }
}

if (import.meta.url === `file://${process.argv[1]}`) { const out = await buildPreview(); for (const [key, path] of Object.entries(OUTPUTS)) { const data = key === 'preview' ? out.rows : key === 'missing' ? out.missing : key === 'risk' ? out.risks : key === 'anchors' ? out.anchors : key === 'effects' ? out.effects : key === 'containerSemantics' ? out.containerSemantics : key === 'tagAudit' ? out.tagAudit : key === 'pending' ? out.pending : key === 'coverage' ? out.coverage : out.summary; if (Array.isArray(data)) assertCsvRectangular(data); await writeFile(path, Array.isArray(data) ? toCsv(data) : data) } console.log(JSON.stringify({ counts: out.counts, preview: out.rows.length - 1, missing: out.missing.length - 1, risks: out.risks.length - 1, anchors: out.anchors.length - 1, effects: out.effects.length - 1, pending: out.pending.length - 1, containerSemantics: out.containerSemantics.length - 1, tagAudit: out.tagAudit.length - 1 })) }