#!/usr/bin/env node
/**
 * 阶段 6B.0：厨师档次与 Field Guide 合并预览（只读正式目录，只写预览文件）
 *
 * 输入：6A.3 DISH 清单 + 旧权威 CSV + 当前 dish_tiers / Field Guide
 * 输出：合并预览 CSV / 冲突表 / FG 预览 / 摘要 md
 *
 * 严禁修改：食物三档分类表、tcth-chef 预设、生成器、奖励、UNITE。
 */
import { mkdir, readFile, readdir, rename, writeFile, stat } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
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

const toolDir = dirname(fileURLToPath(import.meta.url))
const serverDir = resolve(toolDir, '..')
const rootDir = resolve(serverDir, '..')
const outDir = resolve(rootDir, '配方与经济管理/统一配方表')
const projectDir = resolve(rootDir, 'mod develop/tcthintegration-template-1.21.1')
const presetDir = resolve(projectDir, 'docs/presets/tcth-chef')
const dishTiersDir = resolve(presetDir, 'data/tcth/dish_tiers')
const itemsDir = resolve(dishTiersDir, 'items')
const recipesDir = resolve(dishTiersDir, 'recipes')
const fgDir = resolve(presetDir, 'data/tcth/fieldguide')
const tagsDir = resolve(presetDir, 'data/tcth/tags/item')
const notDishesPath = resolve(projectDir, 'src/main/resources/data/tcth/tags/item/not_dishes.json')

const PATHS = {
  handheld: resolve(outDir, '新增食物模组普通手持料理清单.csv'),
  product: resolve(outDir, '新增食物模组产物待分级表.csv'),
  override: resolve(outDir, '新增食物模组人工分类覆盖.csv'),
  serving: resolve(outDir, '新增食物模组整盘料理清单.csv'),
  drink: resolve(outDir, '新增食物模组饮品清单.csv'),
  ingredient: resolve(outDir, '新增食物模组中间产物清单.csv'),
  inactive: resolve(outDir, '新增食物模组未加载产物附表.csv'),
  authority: resolve(outDir, '食物三档分类表.csv'),
  generateDish: resolve(projectDir, 'scripts/generate_dish_tiers.py'),
  generateFg: resolve(projectDir, 'scripts/generate_field_guide.py')
}

const OUT = {
  merge: resolve(outDir, '新增食物模组厨师合并预览.csv'),
  conflict: resolve(outDir, '新增食物模组厨师档次冲突复审表.csv'),
  fg: resolve(outDir, '新增食物模组FieldGuide合并预览.csv'),
  summary: resolve(outDir, '新增食物模组6B0合并摘要.md'),
  report: resolve(projectDir, 'docs/phase-6b.0-chef-merge-preview-report.md')
}

const RAW_DOUGH = 'kaleidoscope_cookery:raw_dough'
const UNLOCK = 'tcth:chef_cookbook_gate（DishCookedEvent 严格解锁；拿取/食用不自动解锁）'

async function sha256File(p) {
  const buf = await readFile(p)
  return createHash('sha256').update(buf).digest('hex')
}

async function listFilesRecursive(dir) {
  const out = []
  async function walk(d) {
    let ents
    try { ents = await readdir(d, { withFileTypes: true }) } catch { return }
    for (const e of ents.sort((a, b) => a.name.localeCompare(b.name))) {
      const p = join(d, e.name)
      if (e.isDirectory()) await walk(p)
      else if (e.isFile()) out.push(p)
    }
  }
  await walk(dir)
  return out
}

async function protectedManifest() {
  const files = [
    PATHS.authority,
    PATHS.generateDish,
    PATHS.generateFg,
    notDishesPath, // 正式排除规则；工具实际读取，必须纳入前后哈希保护
    ...(await listFilesRecursive(dishTiersDir)),
    ...(await listFilesRecursive(fgDir)),
    ...(await listFilesRecursive(tagsDir))
  ]
  // 去重（not_dishes 不在 tagsDir 内，但若路径重合也不重复）
  const uniq = [...new Set(files.map(f => resolve(f)))]
  const lines = []
  for (const f of uniq.sort((a, b) => a.localeCompare(b))) {
    lines.push(`${await sha256File(f)}  ${relative(rootDir, f)}`)
  }
  const body = lines.join('\n') + '\n'
  const manifest = createHash('sha256').update(body).digest('hex')
  return { lines, body, manifest, count: lines.length }
}

function colIndex(header, name) {
  const i = header.indexOf(name)
  if (i < 0) throw new Error(`缺少列: ${name} in [${header.join(',')}]`)
  return i
}

function mapByHeader(header, row) {
  const o = {}
  header.forEach((h, i) => { o[h] = row[i] ?? '' })
  return o
}

async function loadCsv(path) {
  const text = await readFile(path, 'utf8')
  return parseCsvTable(text)
}

async function loadItemTiers() {
  const map = new Map() // itemId -> tier
  const files = await listFilesRecursive(itemsDir)
  for (const f of files) {
    if (!f.endsWith('.json')) continue
    const rel = relative(itemsDir, f).replace(/\\/g, '/')
    if (!rel.endsWith('.json')) continue
    const id = rel.slice(0, -5).replace(/\//, ':') // ns/path.json -> ns:path (only first /)
    // path may contain / : namespace is first segment
    const parts = rel.slice(0, -5).split('/')
    const ns = parts[0]
    const path = parts.slice(1).join('/')
    const itemId = `${ns}:${path}`
    const obj = JSON.parse(await readFile(f, 'utf8'))
    const tier = obj.tier
    if (map.has(itemId)) throw new Error(`duplicate item tier file for ${itemId}`)
    map.set(itemId, tier)
  }
  return map
}

async function loadRecipeTiers() {
  /** recipeId -> tier */
  const map = new Map()
  const files = await listFilesRecursive(recipesDir)
  for (const f of files) {
    if (!f.endsWith('.json')) continue
    const rel = relative(recipesDir, f).replace(/\\/g, '/')
    const parts = rel.slice(0, -5).split('/')
    const ns = parts[0]
    const path = parts.slice(1).join('/')
    const recipeId = `${ns}:${path}`
    const obj = JSON.parse(await readFile(f, 'utf8'))
    map.set(recipeId, obj.tier)
  }
  return map
}

async function loadFieldGuideIndex() {
  /** entryId -> [category keys] */
  const byEntry = new Map()
  const catDir = join(fgDir, 'categories')
  let files = []
  try { files = await listFilesRecursive(catDir) } catch { /* empty */ }
  for (const f of files) {
    if (!f.endsWith('.json')) continue
    const catName = relative(catDir, f).replace(/\\/g, '/').replace(/\.json$/, '')
    const obj = JSON.parse(await readFile(f, 'utf8'))
    const contents = obj.contents || []
    for (const c of contents) {
      if (!c || c.type !== 'entry' || !c.id) continue
      const id = c.id
      if (!byEntry.has(id)) byEntry.set(id, [])
      byEntry.get(id).push(catName)
    }
  }
  // sort category lists for determinism
  for (const [k, v] of byEntry) byEntry.set(k, [...new Set(v)].sort())
  return byEntry
}

async function loadTagIds(file) {
  try {
    const obj = JSON.parse(await readFile(file, 'utf8'))
    return new Set((obj.values || []).filter(v => typeof v === 'string' && !v.startsWith('#')))
  } catch {
    return new Set()
  }
}

async function atomicWrite(target, content) {
  const tmp = target + `.tmp.${process.pid}.${Date.now()}`
  await writeFile(tmp, content, 'utf8')
  // validate non-empty for csv/md
  const st = await stat(tmp)
  if (st.size <= 0) throw new Error(`empty write: ${target}`)
  await rename(tmp, target)
}

function sortIds(ids) {
  return [...ids].sort((a, b) => a.localeCompare(b))
}

// ---------- main ----------
const before = await protectedManifest()

const handheld = await loadCsv(PATHS.handheld)
const product = await loadCsv(PATHS.product)
const authority = await loadCsv(PATHS.authority)
const serving = await loadCsv(PATHS.serving)
const drink = await loadCsv(PATHS.drink)
const ingredient = await loadCsv(PATHS.ingredient)
const inactive = await loadCsv(PATHS.inactive)

const hIdx = {
  name: colIndex(handheld.header, '产物中文名'),
  id: colIndex(handheld.header, '产物ID'),
  type: colIndex(handheld.header, '内容类型'),
  edible: colIndex(handheld.header, '是否可食用'),
  tier: colIndex(handheld.header, '建议厨师档次'),
  evidence: colIndex(handheld.header, '分类证据'),
  note: colIndex(handheld.header, '备注')
}

const dishRows = handheld.rows
  .map(r => ({
    name: (r[hIdx.name] || '').trim(),
    id: (r[hIdx.id] || '').trim(),
    contentType: (r[hIdx.type] || '').trim(),
    edible: (r[hIdx.edible] || '').trim() === '是',
    suggestTier: (r[hIdx.tier] || '').trim(),
    evidence: (r[hIdx.evidence] || '').trim(),
    note: (r[hIdx.note] || '').trim()
  }))
  .filter(r => r.id)

// determinism: sort by id
dishRows.sort((a, b) => a.id.localeCompare(b.id))

// uniqueness
{
  const seen = new Set()
  for (const d of dishRows) {
    if (seen.has(d.id)) throw new Error(`duplicate DISH in handheld list: ${d.id}`)
    seen.add(d.id)
  }
}
if (dishRows.length !== 167) {
  throw new Error(`ASSERT: expected 167 DISH from handheld list, got ${dishRows.length}`)
}

// product map
const pHeader = product.header
const pId = colIndex(pHeader, '产物ID')
const productById = new Map()
for (const r of product.rows) {
  const id = (r[pId] || '').trim()
  if (!id) continue
  productById.set(id, mapByHeader(pHeader, r))
}

// authority map: id -> {tiers[], names, reasons}
const aTierCol = colIndex(authority.header, '等级')
const aIdCol = colIndex(authority.header, '产物ID')
const aNameCol = colIndex(authority.header, '产物显示名')
const aReasonCol = authority.header.indexOf('分级依据')
const authorityById = new Map()
for (const r of authority.rows) {
  const id = (r[aIdCol] || '').trim()
  if (!id) continue
  const code = (r[aTierCol] || '').trim()
  const tier = authorityTierToName(code)
  if (!authorityById.has(id)) {
    authorityById.set(id, { tiers: [], names: [], reasons: [], codes: [] })
  }
  const rec = authorityById.get(id)
  if (tier) rec.tiers.push(tier)
  rec.codes.push(code)
  rec.names.push((r[aNameCol] || '').trim())
  if (aReasonCol >= 0) rec.reasons.push((r[aReasonCol] || '').trim())
}

const itemTiers = await loadItemTiers()
const recipeTiers = await loadRecipeTiers()
const fgIndex = await loadFieldGuideIndex()
const notDishes = await loadTagIds(notDishesPath)
notDishes.add(RAW_DOUGH)

const servingIds = new Set(serving.rows.map(r => (r[1] || '').trim()).filter(Boolean))
const drinkIds = new Set(drink.rows.map(r => (r[1] || '').trim()).filter(Boolean))
const ingredientIds = new Set(ingredient.rows.map(r => (r[1] || '').trim()).filter(Boolean))
const inactiveIds = new Set(inactive.rows.map(r => (r[1] || '').trim()).filter(Boolean))

// chef tag sets for baseline stats
const chefCommon = await loadTagIds(join(tagsDir, 'chef_common.json'))
const chefT2 = await loadTagIds(join(tagsDir, 'chef_t2.json'))
const chefT3 = await loadTagIds(join(tagsDir, 'chef_t3.json'))

// recipeId -> productId from product table
const recipesOfProduct = new Map()
for (const [id, p] of productById) {
  const list = (p['有效配方ID列表'] || '').split('|').map(s => s.trim()).filter(Boolean)
  recipesOfProduct.set(id, list)
}

const mergeHeader = [
  'item_id', '中文名', '来源模组', '6A分类', '6A建议档次',
  '旧权威表档次', '当前item档次', '命中recipe覆盖', '当前实际档次',
  '合并状态', '是否新增', '有效配方ID', '分类证据', '建议动作'
]
const conflictHeader = [
  'item_id', '异常类型', '旧权威表档次', '当前item档次', 'recipe覆盖档次',
  '6A3建议档次', '旧依据', '新依据', '有效配方', '复杂度摘要', '状态', '备注'
]
const fgHeader = [
  'item_id', '建议分类', 'entry_id', '当前是否存在', '当前所在分类',
  '合并状态', '是否建议新增', '解锁方式', '备注'
]

const mergeRows = []
const conflictRows = []
const fgRows = []
const counts = {
  NEW: 0, SAME_TIER: 0, TIER_CONFLICT: 0, EXISTING_UNMAPPED: 0, EXCLUDED_OR_INVALID: 0
}
const fgCounts = { FG_NEW: 0, FG_ALREADY_PRESENT: 0, FG_ID_CONFLICT: 0, FG_BLOCKED: 0 }
const newByTier = { COMMON: 0, T2: 0 }
const suggestByTier = { COMMON: 0, T2: 0, other: 0 }

for (const d of dishRows) {
  const p = productById.get(d.id) || {}
  const mods = (p['来源模组'] || '').trim()
  const recipes = recipesOfProduct.get(d.id) || []
  const recipeHits = recipes
    .filter(rid => recipeTiers.has(rid))
    .map(rid => `${rid}=${recipeTiers.get(rid)}`)
  const recipeTierList = recipes
    .map(rid => recipeTiers.get(rid))
    .filter(Boolean)

  // exclusion checks
  let excludeReason = ''
  if (notDishes.has(d.id) || d.id === RAW_DOUGH) excludeReason = 'not_dishes_or_raw_dough'
  else if (servingIds.has(d.id)) excludeReason = 'listed_as_SERVING_DISH'
  else if (drinkIds.has(d.id)) excludeReason = 'listed_as_DRINK'
  else if (ingredientIds.has(d.id)) excludeReason = 'listed_as_INGREDIENT'
  else if (inactiveIds.has(d.id)) excludeReason = 'inactive_only'
  else if (d.contentType !== 'DISH') excludeReason = `handheld_type=${d.contentType}`
  else if (!d.edible) excludeReason = 'edible_false'

  const oldRec = authorityById.get(d.id)
  const oldTiers = oldRec ? [...new Set(oldRec.tiers)] : []
  const itemTier = itemTiers.get(d.id) || ''

  const merge = classifyMergeStatus({
    itemId: d.id,
    contentType: d.contentType,
    edible: d.edible,
    suggestTier: d.suggestTier,
    oldAuthorityTiers: oldTiers,
    itemTier,
    recipeTiers: recipeTierList,
    excluded: !!excludeReason,
    excludeReason
  })

  counts[merge.status] = (counts[merge.status] || 0) + 1
  if (['COMMON', 'T2'].includes(d.suggestTier)) suggestByTier[d.suggestTier]++
  else suggestByTier.other++

  const isNew = merge.status === 'NEW'
  if (isNew && (d.suggestTier === 'COMMON' || d.suggestTier === 'T2')) {
    newByTier[d.suggestTier]++
  }

  let action = ''
  if (merge.status === 'NEW') action = `6B.1 可考虑新增 item tier=${d.suggestTier} + FG entry`
  else if (merge.status === 'SAME_TIER') action = '无需新增 item/FG；保持现状'
  else if (merge.status === 'TIER_CONFLICT') action = '人工复审档次；禁止自动取高/取低'
  else if (merge.status === 'EXISTING_UNMAPPED') action = '查明为何未生成 item JSON；禁止直接回流'
  else action = `阻断合并：${merge.reason}`

  mergeRows.push([
    d.id,
    d.name || p['产物中文名'] || '',
    mods,
    d.contentType,
    d.suggestTier,
    oldTiers.join('|') || (oldRec ? '(无等级码)' : ''),
    itemTier,
    recipeHits.join(';') || '',
    merge.displayTier || '',
    merge.status,
    isNew ? '是' : '否',
    recipes.join(' | '),
    d.evidence || p['分类证据'] || '',
    action
  ])

  if (['TIER_CONFLICT', 'EXISTING_UNMAPPED', 'EXCLUDED_OR_INVALID'].includes(merge.status)) {
    conflictRows.push([
      d.id,
      merge.status,
      oldTiers.join('|'),
      itemTier,
      recipeHits.join(';'),
      d.suggestTier,
      (oldRec?.reasons || []).filter(Boolean).join('；'),
      d.evidence || p['建议依据'] || '',
      recipes.join(' | '),
      `有效配方数=${p['有效配方数'] || recipes.length};饥饿=${p['饥饿值'] || ''}`,
      'REVIEW_REQUIRED',
      merge.reason
    ])
  }

  const fg = classifyFieldGuideStatus({
    itemId: d.id,
    contentType: d.contentType,
    suggestTier: d.suggestTier,
    categoriesByEntry: fgIndex,
    blocked: merge.status === 'EXCLUDED_OR_INVALID',
    blockReason: merge.status === 'EXCLUDED_OR_INVALID' ? merge.reason : ''
  })
  fgCounts[fg.status] = (fgCounts[fg.status] || 0) + 1
  fgRows.push([
    d.id,
    d.suggestTier,
    fg.entryId,
    fg.categories.length ? '是' : '否',
    fg.categories.join('|'),
    fg.status,
    fg.status === 'FG_NEW' ? '是' : '否',
    UNLOCK,
    fg.reason
  ])
}

assertCsvRectangular([mergeHeader, ...mergeRows])
assertCsvRectangular([conflictHeader, ...conflictRows])
assertCsvRectangular([fgHeader, ...fgRows])
// 输入 167 全量且仅一次
if (mergeRows.length !== 167) throw new Error(`merge rows ${mergeRows.length} != 167`)

// baseline stats (recomputed)
const authorityDataRows = authority.rows.length
const authorityTierCounter = { COMMON: 0, T2: 0, T3: 0, none: 0 }
const authorityIds = new Set()
for (const r of authority.rows) {
  const id = (r[aIdCol] || '').trim()
  if (!id) continue
  authorityIds.add(id)
  const t = authorityTierToName(r[aTierCol])
  if (t) authorityTierCounter[t]++
  else authorityTierCounter.none++
}
const itemTierCounter = { COMMON: 0, T2: 0, T3: 0 }
for (const t of itemTiers.values()) {
  if (itemTierCounter[t] !== undefined) itemTierCounter[t]++
}
const fgEntryCount = fgIndex.size
const fgMulti = [...fgIndex.entries()].filter(([, c]) => c.length > 1)

// theoretical post-merge (only auto-safe NEW COMMON/T2)
const theoryNewItems = counts.NEW
const theoryItemTotal = itemTiers.size + theoryNewItems
const theoryFgNew = fgCounts.FG_NEW
const theoryFgTotal = fgEntryCount + theoryFgNew
const theoryCommon = itemTierCounter.COMMON + newByTier.COMMON
const theoryT2 = itemTierCounter.T2 + newByTier.T2
const theoryT3 = itemTierCounter.T3 // unchanged; no new T3

// write outputs
await mkdir(outDir, { recursive: true })
await mkdir(dirname(OUT.report), { recursive: true })

await atomicWrite(OUT.merge, toCsv([mergeHeader, ...mergeRows]))
await atomicWrite(OUT.conflict, toCsv([conflictHeader, ...conflictRows]))
await atomicWrite(OUT.fg, toCsv([fgHeader, ...fgRows]))

const unresolved = conflictRows.map(r => `- \`${r[0]}\` · ${r[1]} · ${r[11]}`).join('\n') || '（无）'

const summaryMd = `# 新增食物模组 6B.0 合并摘要

> 本文件为**合并预览**，不表示正式厨师数据或 Field Guide 已更新。

生成标记：phase-6B.0-preview（确定性导出；不含易变时间戳）

## 输入

| 项 | 值 |
|---|---|
| 手持 DISH 清单 | \`${relative(rootDir, PATHS.handheld)}\` |
| 输入 DISH 数 | **${dishRows.length}** |
| 旧权威表 | \`${relative(rootDir, PATHS.authority)}\` |
| dish_tiers/items | \`${relative(rootDir, itemsDir)}\` |
| dish_tiers/recipes | \`${relative(rootDir, recipesDir)}\` |
| Field Guide categories | \`${relative(rootDir, join(fgDir, 'categories'))}\` |

## 当前基线（工作区重新统计）

| 指标 | 数量 |
|---|---|
| 旧权威 CSV 数据行 | ${authorityDataRows} |
| 旧权威 unique 产物ID | ${authorityIds.size} |
| 旧权威档次 1/2/3（COMMON/T2/T3） | ${authorityTierCounter.COMMON} / ${authorityTierCounter.T2} / ${authorityTierCounter.T3}（无等级 ${authorityTierCounter.none}） |
| 当前 item tier JSON | ${itemTiers.size}（COMMON ${itemTierCounter.COMMON} / T2 ${itemTierCounter.T2} / T3 ${itemTierCounter.T3}） |
| 当前 recipe tier JSON | ${recipeTiers.size} |
| 当前 FG 显式 entry（categories 内） | ${fgEntryCount} |
| chef_common / chef_t2 / chef_t3 tag 直列 | ${chefCommon.size} / ${chefT2.size} / ${chefT3.size} |
| FG 跨分类冲突 entry | ${fgMulti.length} |

## 五类合并状态（167 DISH）

| 状态 | 数量 |
|---|---|
| NEW | **${counts.NEW}** |
| SAME_TIER | **${counts.SAME_TIER}** |
| TIER_CONFLICT | **${counts.TIER_CONFLICT}** |
| EXISTING_UNMAPPED | **${counts.EXISTING_UNMAPPED}** |
| EXCLUDED_OR_INVALID | **${counts.EXCLUDED_OR_INVALID}** |
| 合计 | **${Object.values(counts).reduce((a, b) => a + b, 0)}** |

6A.3 建议档次分布（输入）：COMMON ${suggestByTier.COMMON} / T2 ${suggestByTier.T2} / other ${suggestByTier.other}

NEW 中建议 COMMON/T2：${newByTier.COMMON} / ${newByTier.T2}

## Field Guide 四类状态

| 状态 | 数量 |
|---|---|
| FG_NEW | **${fgCounts.FG_NEW}** |
| FG_ALREADY_PRESENT | **${fgCounts.FG_ALREADY_PRESENT}** |
| FG_ID_CONFLICT | **${fgCounts.FG_ID_CONFLICT}** |
| FG_BLOCKED | **${fgCounts.FG_BLOCKED}** |

## 合并后理论规模（仅假设自动合并 NEW；冲突不自动）

| 指标 | 当前 | 理论（+NEW） |
|---|---|---|
| item tier 总数 | ${itemTiers.size} | **${theoryItemTotal}** |
| Field Guide 显式 entry | ${fgEntryCount} | **${theoryFgTotal}** |
| COMMON item | ${itemTierCounter.COMMON} | **${theoryCommon}** |
| T2 item | ${itemTierCounter.T2} | **${theoryT2}** |
| T3 item | ${itemTierCounter.T3} | **${theoryT3}**（不新增 T3） |
| recipe 覆盖 | ${recipeTiers.size} | 不变（本阶段不改） |

## 未解决冲突 / 异常

${unresolved}

## 正式合并建议（6B.1 候选，非本阶段执行）

1. **仅**将状态为 \`NEW\` 且建议档次为 COMMON/T2 的物品纳入合并草案。  
2. \`SAME_TIER\`：不重复生成 item JSON / FG entry。  
3. \`TIER_CONFLICT\` / \`EXISTING_UNMAPPED\` / \`EXCLUDED_OR_INVALID\`：**禁止**自动合并，先人工复审。  
4. 不创建新 T3；不改奖励数值与品质逻辑。  
5. SERVING_DISH / DRINK / RAW_FOOD / INGREDIENT **不进入**普通厨师与本 FG 预览。  
6. recipe 级覆盖保持独立，不得被 item 映射删除。

## 本阶段范围外

- 正式修改 \`食物三档分类表.csv\` / tcth-chef 预设  
- 设备完成事件 / Mixin  
- 玩家 Field Guide 进度  
- 服务器部署 / commit/push  
- 6B.1 正式合并  

## 正式目录完整性

保护 manifest SHA-256（运行前）：\`${before.manifest}\`（${before.count} 文件）
`

await atomicWrite(OUT.summary, summaryMd)

const previewHashes = {
  merge: await sha256File(OUT.merge),
  conflict: await sha256File(OUT.conflict),
  fg: await sha256File(OUT.fg),
  summary: await sha256File(OUT.summary)
}

const after = await protectedManifest()
if (after.manifest !== before.manifest) {
  throw new Error('PROTECTED FILES CHANGED during 6B.0 — abort')
}

// double-run will be done by caller; write report
const reportMd = `# 阶段 6B.0：厨师档次与 Field Guide 合并前差异审计

日期：见仓库阶段 6A.3/6B.0 复审日（文档确定性导出）

## 结论分层（请勿混淆）

| 层级 | 状态 |
|---|---|
| 数据审计通过 | **是**（167 DISH 全量唯一处理） |
| 合并预览生成 | **是**（仅预览 CSV/摘要） |
| 正式厨师数据已修改 | **否** |
| Field Guide 已正式更新 | **否** |
| 设备兼容已实现 | **否** |
| 玩家实测 | **未执行** |
| 进入 6B.1 | **否** |

## 1. 实际输入文件

| 文件 | 用途 |
|---|---|
| \`配方与经济管理/统一配方表/新增食物模组普通手持料理清单.csv\` | 6A.3 普通手持 DISH（权威输入） |
| \`…/新增食物模组产物待分级表.csv\` | 配方 ID、模组、证据 |
| \`…/新增食物模组人工分类覆盖.csv\` | 覆盖依据（只读） |
| \`…/食物三档分类表.csv\` | 旧权威档次（**只读，未改**） |
| \`docs/presets/tcth-chef/data/tcth/dish_tiers/\` | 当前 item/recipe tier（**只读**） |
| \`docs/presets/tcth-chef/data/tcth/fieldguide/\` | 当前 FG categories（**只读**） |
| \`docs/presets/tcth-chef/data/tcth/tags/item/\` | chef_* 标签（**只读**） |
| \`src/main/resources/data/tcth/tags/item/not_dishes.json\` | 排除项 |

权威语义：\`phase-6a-new-food-mod-audit.md\` **§10 6A.3**（DISH 167 / COMMON 38 / T2 129 / T3候选 0 / REVIEW 0）。

## 2. 当前基线（工作区重新统计，非历史报告硬编码）

| 指标 | 数量 |
|---|---|
| 旧权威 CSV 数据行 | **${authorityDataRows}** |
| 旧权威 unique ID | **${authorityIds.size}** |
| 旧权威 COMMON/T2/T3（等级码 1/2/3） | **${authorityTierCounter.COMMON}/${authorityTierCounter.T2}/${authorityTierCounter.T3}** |
| 当前 item tier JSON | **${itemTiers.size}**（${itemTierCounter.COMMON}/${itemTierCounter.T2}/${itemTierCounter.T3}） |
| 当前 recipe tier JSON | **${recipeTiers.size}** |
| FG 显式 entry | **${fgEntryCount}** |
| chef_common/t2/t3 直列 | **${chefCommon.size}/${chefT2.size}/${chefT3.size}** |
| FG 跨分类重复 | **${fgMulti.length}** |

## 3. 五类合并状态

| 状态 | 数量 | 含义 |
|---|---|---|
| NEW | **${counts.NEW}** | 旧表/item/recipe 均无 → 理论可新增 |
| SAME_TIER | **${counts.SAME_TIER}** | 已存在且与 6A.3 一致 → 不重复生成 |
| TIER_CONFLICT | **${counts.TIER_CONFLICT}** | 旧/item 与 6A.3 不一致 → 人工复审 |
| EXISTING_UNMAPPED | **${counts.EXISTING_UNMAPPED}** | 旧表有、item JSON 无 → 禁止直接回流 |
| EXCLUDED_OR_INVALID | **${counts.EXCLUDED_OR_INVALID}** | 排除/非法/新 T3 等 → 阻断 |

合计必须等于输入 DISH 数：**${Object.values(counts).reduce((a, b) => a + b, 0)}** / 输入 **${dishRows.length}**。

## 4. Field Guide 四类状态

| 状态 | 数量 |
|---|---|
| FG_NEW | **${fgCounts.FG_NEW}** |
| FG_ALREADY_PRESENT | **${fgCounts.FG_ALREADY_PRESENT}** |
| FG_ID_CONFLICT | **${fgCounts.FG_ID_CONFLICT}** |
| FG_BLOCKED | **${fgCounts.FG_BLOCKED}** |

entry_id 语义：\`item:<namespace>/<path>\`（与现有 category contents 一致）。  
解锁：\`${UNLOCK}\`。

## 5. 冲突与异常清单

详见：\`配方与经济管理/统一配方表/新增食物模组厨师档次冲突复审表.csv\`（${conflictRows.length} 条）。

${unresolved}

## 6. 理论合并规模（仅 NEW 自动安全）

| 指标 | 当前 → 理论 |
|---|---|
| item tier | ${itemTiers.size} → **${theoryItemTotal}** |
| FG entry | ${fgEntryCount} → **${theoryFgTotal}** |
| COMMON/T2/T3 item | ${itemTierCounter.COMMON}/${itemTierCounter.T2}/${itemTierCounter.T3} → **${theoryCommon}/${theoryT2}/${theoryT3}** |

## 7. 测试

运行：

\`\`\`bash
node Server/tools/export_phase6a_audit.test.mjs
node Server/tools/export_phase6b0_merge_preview.test.mjs
\`\`\`

（本报告生成时以实际终端输出为准；期望 6A 测试 47 passed，6B.0 测试全通过。）

## 8. 连续导出确定性

下列预览文件在连续两次运行中应保持相同 SHA-256（本轮）：

| 文件 | SHA-256 |
|---|---|
| 新增食物模组厨师合并预览.csv | \`${previewHashes.merge}\` |
| 新增食物模组厨师档次冲突复审表.csv | \`${previewHashes.conflict}\` |
| 新增食物模组FieldGuide合并预览.csv | \`${previewHashes.fg}\` |
| 新增食物模组6B0合并摘要.md | \`${previewHashes.summary}\` |

补充：167 DISH 与旧权威表重叠 **0**；NEW 建议档次 COMMON **${newByTier.COMMON}** / T2 **${newByTier.T2}**；冲突行 **${conflictRows.length}**。

## 9. 正式文件未变证据

| 项 | 值 |
|---|---|
| 保护文件数 | ${before.count} |
| 运行前 manifest | \`${before.manifest}\` |
| 运行后 manifest | \`${after.manifest}\` |
| 一致 | **${before.manifest === after.manifest ? '是' : '否'}** |

涵盖：\`食物三档分类表.csv\`、\`generate_dish_tiers.py\`、\`generate_field_guide.py\`、\`dish_tiers/**\`、\`fieldguide/**\`、\`tags/item/**\`。

## 10. 下一阶段建议（6B.1，未开始）

1. 人工关闭全部 \`TIER_CONFLICT\` / \`EXISTING_UNMAPPED\`。  
2. 仅合并 \`NEW\` 的 COMMON/T2。  
3. 同步生成 item JSON 与 FG category entry（\`item:ns/path\` + gate）。  
4. 再跑生成器干跑与互斥校验；**不**改奖励数值。  
5. 设备事件仍属后续阶段。

## 11. 建议暂存清单（不得自行 commit）

若复审通过后由用户提交，建议路径仅限预览与报告：

- \`Server/tools/export_phase6b0_merge_preview.mjs\`
- \`Server/tools/export_phase6b0_merge_preview.test.mjs\`
- \`Server/tools/phase6a_lib.mjs\`（CSV/合并纯函数扩展）
- \`配方与经济管理/统一配方表/新增食物模组厨师合并预览.csv\`
- \`配方与经济管理/统一配方表/新增食物模组厨师档次冲突复审表.csv\`
- \`配方与经济管理/统一配方表/新增食物模组FieldGuide合并预览.csv\`
- \`配方与经济管理/统一配方表/新增食物模组6B0合并摘要.md\`
- \`mod develop/tcthintegration-template-1.21.1/docs/phase-6b.0-chef-merge-preview-report.md\`

**禁止**将 \`食物三档分类表.csv\` 或 \`docs/presets/tcth-chef/\` 纳入本阶段提交。

---

**6B.0 停止。等待复审。不进入 6B.1。**
`

await atomicWrite(OUT.report, reportMd)

console.log(JSON.stringify({
  phase: '6B.0',
  input_dish: dishRows.length,
  counts,
  fgCounts,
  baseline: {
    authority_rows: authorityDataRows,
    authority_unique: authorityIds.size,
    authority_tiers: authorityTierCounter,
    item_tiers: itemTiers.size,
    item_tier_dist: itemTierCounter,
    recipe_tiers: recipeTiers.size,
    fg_entries: fgEntryCount,
    chef_tags: {
      common: chefCommon.size,
      t2: chefT2.size,
      t3: chefT3.size
    }
  },
  theory: {
    item_total: theoryItemTotal,
    fg_total: theoryFgTotal,
    common: theoryCommon,
    t2: theoryT2,
    t3: theoryT3
  },
  conflict_rows: conflictRows.length,
  protected_manifest: before.manifest,
  protected_files: before.count,
  protected_unchanged: true
}, null, 2))
