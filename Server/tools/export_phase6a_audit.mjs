#!/usr/bin/env node
/**
 * 阶段 6A.2：新增食物模组配方审计（语义拆分 + 人工覆盖）
 *
 * 输入：
 *  - kubejs/config/food_recipe_export.json（运行时 RecipeManager）
 *  - Server/mods/*.jar
 *  - 配方与经济管理/统一配方表/新增食物模组人工分类覆盖.csv
 * 输出：
 *  - 配方与经济管理/统一配方表/新增食物模组配方审计表.csv
 *  - 配方与经济管理/统一配方表/新增食物模组产物待分级表.csv
 *  - 配方与经济管理/统一配方表/新增食物模组未加载产物附表.csv
 *  - 配方与经济管理/统一配方表/新增食物模组T3候选复审表.csv
 *  - 配方与经济管理/统一配方表/新增食物模组普通手持料理清单.csv
 *  - 配方与经济管理/统一配方表/新增食物模组整盘料理清单.csv
 *  - 配方与经济管理/统一配方表/新增食物模组饮品清单.csv
 *  - 配方与经济管理/统一配方表/新增食物模组中间产物清单.csv
 *  - 配方与经济管理/统一配方表/新增食物模组待复审清单.csv
 *  - kubejs/config/phase6a_audit_summary.json
 *
 * 执行顺序：1 自动启发式 → 2 人工覆盖 → 3 一致性验证 → 4 输出
 */
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  parseRecipeEntryPath,
  classifyPackKind,
  jsonHash,
  resultFromRecipe,
  ingredientsFromRecipe,
  cookingTimeOf,
  temperatureOf,
  addProvider,
  uniteStatusOf,
  classifyContentType,
  suggestTier,
  pickLowestChefTier,
  dishEventCoverage,
  toCsv,
  assertCsvRectangular,
  itemRef,
  parseManualOverrides,
  applyManualOverride
} from './phase6a_lib.mjs'

const toolDir = dirname(fileURLToPath(import.meta.url))
const serverDir = resolve(toolDir, '..')
const rootDir = resolve(serverDir, '..')
const modsDir = resolve(serverDir, 'mods')
const runtimePath = resolve(serverDir, 'kubejs/config/food_recipe_export.json')
const outDir = resolve(rootDir, '配方与经济管理/统一配方表')
const overridePath = resolve(outDir, '新增食物模组人工分类覆盖.csv')
const summaryPath = resolve(serverDir, 'kubejs/config/phase6a_audit_summary.json')

const TARGET_MODS = [
  { display: 'Neapolitan', version: '6.0.1', match: n => /neapolitan/i.test(n) && /6\.0\.1/.test(n) },
  { display: "Dungeon's Delight", version: '1.5.0', match: n => /dungeonsdelight/i.test(n) && /1\.5\.0/.test(n) },
  { display: "My Nether's Delight", version: '1.10.4', match: n => /mynethersdelight/i.test(n) && /1\.10\.4/.test(n) },
  { display: "Brewin' and Chewin'", version: '4.5.0', match: n => /brewinandchewin|brewin/i.test(n) && /4\.5\.0/.test(n) },
  { display: 'Bakeries', version: '1.0.1', match: n => /bakeries/i.test(n) && /1\.0\.1/.test(n) },
  { display: 'Kaleidoscope Tavern', version: '1.2.0', match: n => /kaleidoscopetavern|kaleidoscope_tavern/i.test(n) && /1\.2\.0/.test(n) },
  { display: 'Fowl Play', version: '1.2.3', match: n => /fowlplay/i.test(n) && /1\.2\.3/.test(n) },
  { display: 'Kaleidoscope Compat', version: '2.9.7', match: n => /kaleidoscope_compat/i.test(n) && /2\.9\.7/.test(n), isCompat: true }
]

const FOCUS_NAMESPACES = new Set([
  'neapolitan', 'dungeonsdelight', 'mynethersdelight', 'brewinandchewin',
  'bakeries', 'kaleidoscope_tavern', 'fowlplay'
])

const MACHINE = {
  'minecraft:crafting_shaped': '工作台（有序）',
  'minecraft:crafting_shapeless': '工作台（无序）',
  'minecraft:smelting': '熔炉',
  'minecraft:smoking': '烟熏炉',
  'minecraft:campfire_cooking': '营火（无事件检测）',
  'minecraft:blasting': '高炉',
  'minecraft:stonecutting': '切石机',
  'farmersdelight:cooking': 'FD 烹饪锅',
  'farmersdelight:cutting': 'FD 切菜板（非出锅）',
  'dungeonsdelight:monster_cooking': '怪物烹饪锅',
  'brewinandchewin:fermenting': '发酵桶',
  'brewinandchewin:keg_pouring': '木桶倾倒',
  'mynethersdelight:blazier_heating': '烈焰炉加热',
  'mynethersdelight:blazier_cooling': '烈焰炉冷却',
  'bakeries:oven': '烘焙坊烤箱',
  'bakeries:blender': '烘焙坊搅拌机',
  'bakeries:bread_knife': '烘焙坊面包刀',
  'bakeries:dough_crafting_table': '烘焙坊揉面台',
  'bakeries:drink': '烘焙坊饮品',
  'bakeries:fermentation_box': '烘焙坊发酵箱',
  'bakeries:flour_sieve': '烘焙坊面粉筛',
  'kaleidoscope_tavern:barrel': '酒馆木桶',
  'kaleidoscope_tavern:shaker': '酒馆摇酒器',
  'kaleidoscope_tavern:pressing_tub': '酒馆压汁桶',
  'create:mixing': 'Create 搅拌（自动化）',
  'create:cutting': 'Create 切割（自动化）',
  'create:milling': 'Create 碾磨（自动化）',
  'create:pressing': 'Create 压制（自动化）',
  'create:sequenced_assembly': 'Create 序列组装（自动化）'
}

function sha256File(path) {
  return execFileSync('shasum', ['-a', '256', path], { encoding: 'utf8' }).trim().split(/\s+/)[0]
}

function listJar(path) {
  return execFileSync('unzip', ['-Z1', path], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
    .split(/\r?\n/).filter(Boolean)
}

function readJarEntry(path, entry) {
  return execFileSync('unzip', ['-p', path, entry], { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 })
}

function parseJsonSafe(text) {
  try { return JSON.parse(text) } catch { return null }
}

function loadTranslations(modJars) {
  const translations = {}
  for (const jar of modJars) {
    let entries
    try { entries = listJar(jar.path) } catch { continue }
    for (const entry of entries) {
      if (!/^assets\/[^/]+\/lang\/zh_cn\.json$/i.test(entry)) continue
      try {
        Object.assign(translations, JSON.parse(readJarEntry(jar.path, entry)))
      } catch { /* ignore */ }
    }
  }
  return translations
}

function itemName(id, translations) {
  if (!id || id.startsWith('#') || id.startsWith('[')) return id
  const dot = id.replace(':', '.')
  return translations[`item.${dot}`] || translations[`block.${dot}`] || id
}

// ---------- resolve jars ----------
const modFiles = await readdir(modsDir)
const resolvedMods = []
for (const spec of TARGET_MODS) {
  const name = modFiles.find(n => n.endsWith('.jar') && spec.match(n))
  if (!name) {
    console.error(`缺少目标 JAR：${spec.display} ${spec.version}`)
    process.exit(1)
  }
  const path = resolve(modsDir, name)
  resolvedMods.push({
    ...spec,
    fileName: name,
    path,
    sha256: sha256File(path)
  })
}

// ---------- multi-provider store ----------
/** @type {Map<string, {recipeId, providers: any[], runtimePresent, runtime}>} */
const store = new Map()
const packRecipeCounts = {}
const jarRawCounts = {}

for (const mod of resolvedMods) {
  jarRawCounts[mod.display] = { raw: 0, byType: {} }
  let entries
  try { entries = listJar(mod.path) } catch { continue }
  for (const entry of entries) {
    if (entry.includes('/advancement/')) continue
    if (!entry.endsWith('.json') || !entry.includes('/recipe/')) continue
    const parsed = parseRecipeEntryPath(entry)
    if (!parsed) continue
    const { pack, namespace, recipeId } = parsed
    let text
    try { text = readJarEntry(mod.path, entry) } catch { continue }
    const data = parseJsonSafe(text)
    if (!data || !data.type) continue

    const product = resultFromRecipe(data)
    const productNs = product.id ? String(product.id).split(':')[0] : ''
    const related = FOCUS_NAMESPACES.has(namespace)
      || FOCUS_NAMESPACES.has(productNs)
      || mod.isCompat
      || (mod.display.includes('Nether') && ['farmersdelight', 'brewinandchewin', 'minecraft', 'farmersrespite'].includes(namespace))
    if (!related) continue

    jarRawCounts[mod.display].raw++
    const t = String(data.type)
    jarRawCounts[mod.display].byType[t] = (jarRawCounts[mod.display].byType[t] || 0) + 1

    if (pack) {
      packRecipeCounts[pack] = (packRecipeCounts[pack] || 0) + 1
    }

    const ings = ingredientsFromRecipe(data)
    const provider = {
      jarFile: mod.fileName,
      jarDisplay: mod.display,
      jarVersion: mod.version,
      jarSha: mod.sha256,
      jarRecipeNs: namespace,
      pack,
      packKind: classifyPackKind(pack),
      isCompat: !!mod.isCompat,
      type: t,
      productId: product.id || '',
      count: product.count ?? '',
      isFluidResult: !!product.isFluid,
      ingredients: ings,
      ingredientKinds: new Set(ings.map(i => i.text.split(' / ')[0])).size,
      ingredientSlots: ings.filter(i => ['原料', '基础', '附加'].includes(i.role)).length || ings.length,
      container: itemRef(data.container || data.carrier || ''),
      fluid: itemRef(data.fluid || data.base_fluid || ''),
      cookingTime: cookingTimeOf(data),
      experience: data.experience ?? data.xp ?? '',
      temperature: temperatureOf(data),
      jsonHash: jsonHash(data),
      entryPath: entry,
      raw: data
    }
    addProvider(store, recipeId, provider)
  }
}

// ---------- runtime ----------
let runtime
try {
  runtime = JSON.parse(await readFile(runtimePath, 'utf8'))
} catch {
  console.error(`缺少运行时导出 ${runtimePath}。请先 /syexport food_recipes`)
  process.exit(1)
}

const runtimeById = new Map()
for (let i = 1; i < (runtime.recipe_rows || []).length; i++) {
  const row = runtime.recipe_rows[i]
  runtimeById.set(row[1], {
    sourceMod: row[0],
    recipeId: row[1],
    type: row[3],
    machine: row[4],
    productName: row[5],
    productId: row[6],
    count: row[7],
    edible: row[8],
    nutrition: row[9],
    saturation: row[10],
    effects: row[11],
    experience: row[12],
    cookingTime: row[13],
    ingredientsText: row[14],
    note: row[15]
  })
}

// mark runtime presence on store + add runtime-only ids
for (const [id, rt] of runtimeById) {
  if (!store.has(id)) {
    store.set(id, { recipeId: id, providers: [], runtimePresent: true, runtime: rt })
  } else {
    store.get(id).runtimePresent = true
    store.get(id).runtime = rt
  }
}

// also attach runtime to existing
for (const [id, rec] of store) {
  if (runtimeById.has(id)) {
    rec.runtimePresent = true
    rec.runtime = runtimeById.get(id)
  }
}

const translations = loadTranslations(resolvedMods)

// ---------- manual overrides（优先于启发式）----------
let manualOverrides
try {
  const overrideText = await readFile(overridePath, 'utf8')
  manualOverrides = parseManualOverrides(overrideText)
} catch (e) {
  console.error(`人工覆盖加载失败: ${overridePath}`)
  console.error(e.message || e)
  process.exit(1)
}

// ---------- build audit rows ----------
const auditHeader = [
  '模组显示名', '实际JAR文件', 'JAR版本', 'JAR原始来源命名空间',
  '运行时配方ID', '配方序列化类型', '机器/工序',
  '产物中文名', '产物ID', '数量',
  '是否可食用', '饥饿值', '饱和度', '效果',
  '内容类型', '分类证据', '分类置信度',
  '原料种类数', '原料槽位数', '稀有原料', '中间产物',
  '容器/载体', '流体', '烹饪时间', '设备温度',
  'UNITE状态', '提供者包列表', '是否为最终有效配方',
  '建议厨师档次', '建议依据', 'T3证据',
  '是否需要料理完成事件适配', '建议CookingDevice', '设备覆盖说明',
  '人工复审备注'
]

const auditRows = []
const contentCounts = {
  DISH: 0, SERVING_DISH: 0, DRINK: 0, INGREDIENT: 0,
  RAW_FOOD: 0, ANIMAL_FOOD: 0, NON_FOOD: 0, REVIEW: 0
}
let runtimeValid = 0
let uniteAdd = 0
let uniteOverride = 0
let uniteDisable = 0
let baseOnly = 0
let overrideAppliedRecipes = 0

function pickPrimaryProvider(providers) {
  // prefer base non-compat, then any
  return providers.find(p => !p.pack && !p.isCompat)
    || providers.find(p => p.packKind === 'base')
    || providers[0]
    || null
}

function isEdibleFlag(v) {
  return v === '是' || v === true || v === 'true'
}

for (const recipeId of [...store.keys()].sort()) {
  const rec = store.get(recipeId)
  const providers = rec.providers
  const rt = rec.runtime
  const primary = pickPrimaryProvider(providers)
  const rns = recipeId.split(':')[0]
  const type = rt?.type || primary?.type || ''
  const productId = String(rt?.productId || primary?.productId || '')
  const pns = productId ? productId.split(':')[0] : ''

  const related = FOCUS_NAMESPACES.has(rns) || FOCUS_NAMESPACES.has(pns)
    || providers.some(p => /Neapolitan|Dungeon|Nether|Brewin|Bakeries|Tavern|Fowl/i.test(p.jarDisplay))
  if (!related && !FOCUS_NAMESPACES.has(rt?.sourceMod || '')) continue

  const finalValid = !!rec.runtimePresent && !!rt
  if (finalValid) runtimeValid++

  const ings = primary?.ingredients || []
  const ingredientKinds = primary?.ingredientKinds
    ?? (rt?.ingredientsText ? rt.ingredientsText.split('；').filter(Boolean).length : 0)
  const ingredientSlots = primary?.ingredientSlots
    ?? (rt?.ingredientsText ? rt.ingredientsText.split('；').filter(Boolean).length : 0)
  const ingredientsText = rt?.ingredientsText || ings.map(i => i.text).join('；')

  const autoCls = classifyContentType({
    productId,
    recipeType: type,
    edible: rt?.edible || '',
    isFluidOnly: !productId && (type === 'kaleidoscope_tavern:pressing_tub' || primary?.isFluidResult),
    isBlazier: type.includes('blazier'),
    isScarecrow: productId === 'fowlplay:scarecrow'
  })

  const autoTier = suggestTier({
    contentType: autoCls.contentType,
    productId,
    recipeType: type,
    ingredientKinds,
    ingredientSlots,
    cookingTime: Number(rt?.cookingTime || primary?.cookingTime) || 0,
    ingredientsText,
    processChainDepth: /dough|cream|ferment|aged|slice|batter/.test(ingredientsText) ? 2 : 1,
    hasSimpleAlt: false
  })

  const final = applyManualOverride(
    autoCls,
    autoTier,
    productId,
    manualOverrides,
    isEdibleFlag(rt?.edible)
  )
  if (final.overridden) overrideAppliedRecipes++

  // 一致性：非覆盖的 DISH 必须 FOOD
  if (!final.overridden && final.contentType === 'DISH' && !isEdibleFlag(rt?.edible)) {
    throw new Error(`ASSERT: DISH without FOOD and no override: ${productId} @ ${recipeId}`)
  }
  // SERVING_DISH 不得自动进入普通厨师档次
  if (final.contentType === 'SERVING_DISH' && ['COMMON', 'T2', 'T3候选', 'T3'].includes(final.tier)) {
    throw new Error(`ASSERT: SERVING_DISH has chef tier ${final.tier}: ${productId}`)
  }
  if (['INGREDIENT', 'NON_FOOD', 'RAW_FOOD'].includes(final.contentType)
    && ['COMMON', 'T2', 'T3候选', 'T3'].includes(final.tier)) {
    throw new Error(`ASSERT: ${final.contentType} has chef tier ${final.tier}: ${productId}`)
  }

  contentCounts[final.contentType] = (contentCounts[final.contentType] || 0) + 1

  const uniteStatus = uniteStatusOf(providers, finalValid)
  if (uniteStatus === 'UNITE添加') uniteAdd++
  if (uniteStatus === 'UNITE覆盖') uniteOverride++
  if (uniteStatus.includes('disable')) uniteDisable++
  if (uniteStatus === '无' && finalValid) baseOnly++

  const packList = providers.map(p => p.pack || '(base)').join('|')
  const cov = dishEventCoverage(type)
  const productName = rt?.productName || itemName(productId, translations)
  const notes = []
  if (final.overridden) notes.push('人工覆盖优先')
  if (final.isServingDish) notes.push('整盘料理（防双算待设计）')
  if (type.includes('blazier')) notes.push('Blazier状态切换')
  if (type === 'minecraft:campfire_cooking') notes.push('营火：TCTH 当前无 DishCookedEvent 检测')
  if (type?.startsWith('create:')) notes.push('Create 自动化不得记玩家厨技')
  if (type === 'farmersdelight:cutting') notes.push('切菜板非出锅事件')
  if (recipeId.startsWith('fowlplay:')) notes.push('Fowl Play 无玩家料理')
  if (!finalValid) notes.push('运行时未加载（依赖/条件）')
  if (final.note) notes.push(final.note)

  const jarDisplay = primary?.jarDisplay || rt?.sourceMod || providers[0]?.jarDisplay || ''
  auditRows.push([
    jarDisplay,
    primary?.jarFile || providers[0]?.jarFile || '',
    primary?.jarVersion || providers[0]?.jarVersion || '',
    primary?.jarRecipeNs || rns,
    recipeId,
    type,
    MACHINE[type] || `自定义（${type}）`,
    productName,
    productId,
    rt?.count ?? primary?.count ?? '',
    rt?.edible ?? '',
    rt?.nutrition ?? '',
    rt?.saturation ?? '',
    rt?.effects ?? '',
    final.contentType,
    final.evidence,
    final.confidence,
    ingredientKinds,
    ingredientSlots,
    /netherite|dragon|wither|warden|sculk|sniffer|ghast_tear|blaze_rod|ancient/.test(ingredientsText) ? '是' : '否',
    final.contentType === 'INGREDIENT' ? '是' : '否',
    primary?.container || '',
    primary?.fluid || '',
    rt?.cookingTime || primary?.cookingTime || '',
    primary?.temperature || '',
    uniteStatus,
    packList,
    finalValid ? '是' : '否',
    final.tier,
    final.reason || autoTier.reason,
    final.t3Evidence || '',
    cov.status,
    cov.device,
    cov.note,
    notes.join('；')
  ])
}

assertCsvRectangular([auditHeader, ...auditRows])

// ---------- product aggregation: ONLY runtime-valid ----------
const validRows = auditRows.filter(r => r[27] === '是') // 是否为最终有效配方
const allRowsByProduct = new Map()
for (const row of auditRows) {
  const pid = row[8]
  if (!pid) continue
  if (!allRowsByProduct.has(pid)) allRowsByProduct.set(pid, { valid: [], invalid: [] })
  if (row[27] === '是') allRowsByProduct.get(pid).valid.push(row)
  else allRowsByProduct.get(pid).invalid.push(row)
}

const productHeader = [
  '产物中文名', '产物ID', '内容类型', '分类证据', '分类置信度',
  '是否可食用', '饥饿值', '饱和度', '效果',
  '有效配方数', '无效/JAR候选配方数',
  '有效配方ID列表', '未加载配方ID列表',
  '序列化类型列表', '来源模组',
  '建议厨师档次', '建议依据', 'T3证据',
  '冲突说明', '人工复审备注'
]

const productRows = []
const inactiveProductRows = []
const t3CandidateRows = []
const tierCounts = { COMMON: 0, T2: 0, T3候选: 0, '不进入厨师': 0, '待复审': 0 }
const productContentCounts = {
  DISH: 0, SERVING_DISH: 0, DRINK: 0, INGREDIENT: 0,
  RAW_FOOD: 0, ANIMAL_FOOD: 0, NON_FOOD: 0, REVIEW: 0
}
const edibleIngredientReview = []
let productOverrideCount = 0

const CONTENT_VOTE_ORDER = [
  'DISH', 'SERVING_DISH', 'DRINK', 'INGREDIENT', 'RAW_FOOD', 'ANIMAL_FOOD', 'NON_FOOD', 'REVIEW'
]

function pickContentTypeFromVotes(votes) {
  for (const t of CONTENT_VOTE_ORDER) {
    if (votes.includes(t)) return t
  }
  return votes[0] || 'REVIEW'
}

for (const [productId, bag] of [...allRowsByProduct.entries()].sort((a, b) => a[0].localeCompare(b[0]))) {
  const valid = bag.valid
  const invalid = bag.invalid
  if (valid.length === 0) {
    // inactive-only → 附表；仍可应用产物级人工覆盖分类
    const sample = invalid[0]
    let inactiveType = sample[14]
    if (manualOverrides.has(productId)) {
      inactiveType = manualOverrides.get(productId).contentType
    }
    inactiveProductRows.push([
      sample[7],
      productId,
      inactiveType,
      invalid.length,
      invalid.map(r => r[4]).join(' | '),
      invalid.map(r => r[5]).join(' | '),
      sample[25],
      '无运行时有效配方；不得进入当前服务器厨师候选'
    ])
    continue
  }

  // 分类/档次仅基于 valid；产物级人工覆盖优先
  const contentVotes = valid.map(r => r[14])
  let contentType = pickContentTypeFromVotes(contentVotes)
  let evidence = ''
  let confidence = 'HIGH'
  let finalTier
  let reason
  let conflict = ''
  let t3Evidence = ''
  let note = ''

  const typed = valid.filter(r => r[14] === contentType)
  const best = typed.sort((a, b) => {
    const rank = { HIGH: 3, MEDIUM: 2, LOW: 1 }
    return (rank[b[16]] || 0) - (rank[a[16]] || 0)
  })[0] || valid[0]

  evidence = best[15]
  confidence = best[16]

  const o = manualOverrides.get(productId)
  if (o) {
    productOverrideCount++
    contentType = o.contentType
    evidence = `manual_override;${o.evidence}`
    confidence = 'HIGH'
    note = o.note
    if (contentType === 'DISH') {
      finalTier = o.tier || '待复审'
      reason = o.evidence
      if (['COMMON', 'T2', 'T3候选'].includes(finalTier) && !o.allowChefXp) {
        finalTier = '不进入厨师'
        reason = '人工覆盖禁止厨师经验'
      }
    } else if (contentType === 'REVIEW') {
      finalTier = o.tier || '待复审'
      reason = o.evidence
    } else {
      finalTier = '不进入厨师'
      reason = o.evidence || `内容类型=${contentType}`
    }
  } else {
    const tiers = new Set(valid.map(r => r[28]))
    if (contentType !== 'DISH') {
      finalTier = contentType === 'REVIEW' ? '待复审' : '不进入厨师'
      reason = contentType === 'SERVING_DISH'
        ? '整盘料理：本阶段不进入普通厨师档次（防双算待设计）'
        : `内容类型=${contentType}`
    } else {
      // DISH 且非人工：要求至少一条 FOOD
      if (!valid.some(r => r[10] === '是')) {
        contentType = 'REVIEW'
        finalTier = '待复审'
        reason = 'auto_dish_without_food_rejected_at_product'
        evidence = reason
      } else {
        const chefTiers = [...tiers].filter(t => ['COMMON', 'T2', 'T3候选', 'T3'].includes(t))
        finalTier = pickLowestChefTier(chefTiers) || '待复审'
        if (chefTiers.length > 1) {
          conflict = `多路径 ${chefTiers.join('/')} → 最低 ${finalTier}`
        }
        reason = best[29] || ''
        t3Evidence = best[30] || ''
        if (finalTier === 'T3') finalTier = 'T3候选'
      }
    }
  }

  // 档次表：仅普通 DISH 进入 COMMON/T2/T3候选
  if (contentType !== 'DISH' && ['COMMON', 'T2', 'T3候选', 'T3'].includes(finalTier)) {
    finalTier = contentType === 'REVIEW' ? '待复审' : '不进入厨师'
  }

  tierCounts[finalTier] = (tierCounts[finalTier] || 0) + 1
  productContentCounts[contentType] = (productContentCounts[contentType] || 0) + 1

  const row = [
    best[7],
    productId,
    contentType,
    evidence,
    confidence,
    best[10],
    best[11],
    best[12],
    best[13],
    valid.length,
    invalid.length,
    valid.map(r => r[4]).join(' | '),
    invalid.map(r => r[4]).join(' | '),
    [...new Set(valid.map(r => r[5]))].join(' | '),
    [...new Set(valid.map(r => r[0]).filter(Boolean))].join(' | '),
    finalTier,
    reason,
    t3Evidence,
    conflict,
    note
  ]
  productRows.push(row)

  if (best[10] === '是' && (contentType === 'INGREDIENT' || contentType === 'REVIEW')) {
    edibleIngredientReview.push({
      productId,
      name: best[7],
      contentType,
      evidence,
      confidence,
      nutrition: best[11]
    })
  }

  if (finalTier === 'T3候选' && contentType === 'DISH') {
    t3CandidateRows.push([
      best[7],
      productId,
      valid.map(r => r[4]).join(' | '),
      t3Evidence || reason,
      best[30] || '',
      '见 T3证据',
      conflict.includes('简单') ? '是' : '否',
      '未人工确认，禁止写入正式 T3'
    ])
  }
}

// inactive appendix header
const inactiveHeader = [
  '产物中文名', '产物ID', '内容类型（JAR启发式）', '未加载配方数',
  '未加载配方ID列表', '序列化类型', 'UNITE状态样例', '备注'
]
const t3Header = [
  '产物中文名', '产物ID', '有效配方ID列表', '建议理由',
  '稀有原料证据', '加工步骤证据', '是否存在更简单替代', '状态'
]

assertCsvRectangular([productHeader, ...productRows])
assertCsvRectangular([inactiveHeader, ...inactiveProductRows])
if (t3CandidateRows.length) assertCsvRectangular([t3Header, ...t3CandidateRows])

// Assertions (hard fail)
function assert(cond, msg) {
  if (!cond) throw new Error(`ASSERT: ${msg}`)
}

// blazing blood sausage must not be in valid product table
assert(!productRows.some(r => r[1] === 'dungeonsdelight:blazing_blood_sausage'),
  'blazing_blood_sausage must not be in valid product table')
// ice creams dish（手持 ItemStack，非 block）
for (const id of [
  'neapolitan:adzuki_ice_cream',
  'neapolitan:banana_ice_cream',
  'neapolitan:neapolitan_ice_cream',
  'brewinandchewin:creamy_onion_soup',
  'dungeonsdelight:breeze_cream_cone'
]) {
  const p = productRows.find(r => r[1] === id)
  assert(p, `missing product ${id}`)
  assert(p[2] === 'DISH', `${id} should be DISH, got ${p[2]}`)
}
for (const id of [
  'bakeries:cheese_cream', 'bakeries:foamed_cream', 'mynethersdelight:ghast_dough',
  'dungeonsdelight:sculk_mayo', 'dungeonsdelight:wardenzola',
  'bakeries:egg_tart_shell', 'bakeries:raw_egg_tart', 'mynethersdelight:raw_stuffed_hoglin'
]) {
  const p = productRows.find(r => r[1] === id)
  assert(p, `missing ${id}`)
  assert(p[2] === 'INGREDIENT', `${id} should be INGREDIENT, got ${p[2]}`)
  assert(!['COMMON', 'T2', 'T3候选'].includes(p[15]), `${id} must not have chef tier`)
}

// 6A.2 回归：substring / SERVING_DISH
const mustNonFood = [
  'kaleidoscope_tavern:tartaric_acid_painting',
  'neapolitan:roasted_adzuki_crate'
]
for (const id of mustNonFood) {
  const p = productRows.find(r => r[1] === id) || inactiveProductRows.find(r => r[1] === id)
  // painting/crate may only appear as product of recipes in audit
  const a = auditRows.find(r => r[8] === id)
  const type = p ? p[2] : a?.[14]
  assert(type === 'NON_FOOD', `${id} should be NON_FOOD, got ${type}`)
}

const servingExpect = [
  'mynethersdelight:roast_stuffed_hoglin',
  'brewinandchewin:pizza',
  'neapolitan:chocolate_cake',
  'neapolitan:vanilla_ice_cream_block'
]
for (const id of servingExpect) {
  const p = productRows.find(r => r[1] === id)
  assert(p, `missing serving ${id}`)
  assert(p[2] === 'SERVING_DISH', `${id} should be SERVING_DISH, got ${p[2]}`)
  assert(p[15] === '不进入厨师', `${id} SERVING_DISH must not enter chef tiers`)
}

// T3 候选必须为 0（sculk_mayo/wardenzola 已驳回）
assert(t3CandidateRows.length === 0, `T3候选 must be 0, got ${t3CandidateRows.length}`)
assert(tierCounts['T3候选'] === 0 || !tierCounts['T3候选'],
  `tier T3候选 must be 0, got ${tierCounts['T3候选']}`)
assert(productContentCounts.DISH >= 0)
assert(
  productRows.filter(r => r[2] === 'DISH' && r[5] === '是').length === productContentCounts.DISH
  || productRows.filter(r => r[2] === 'DISH').every(r => r[5] === '是' || manualOverrides.has(r[1])),
  'DISH products should be edible unless manual override'
)

// 6A.3：最终有效表 REVIEW 必须为 0
assert((productContentCounts.REVIEW || 0) === 0,
  `REVIEW must be 0 after 6A.3, got ${productContentCounts.REVIEW}`)
assert(productRows.filter(r => r[2] === 'REVIEW').length === 0, 'product table still has REVIEW rows')

// 6A.2 遗留 16 条必须有人工覆盖
const required16 = [
  'bakeries:bagel_filled_sauce',
  'bakeries:baguette_with_filling',
  'bakeries:country_bread',
  'bakeries:meat_floss_bread_roll',
  'bakeries:mould_cheese_cocoa_toast',
  'bakeries:mould_toast',
  'dungeonsdelight:sculk_apple',
  'dungeonsdelight:spider_donut',
  'dungeonsdelight:spider_pie',
  'mynethersdelight:bleeding_tartar',
  'mynethersdelight:hot_cream_cone',
  'mynethersdelight:stuffed_pepper',
  'neapolitan:chocolate_strawberries',
  'neapolitan:strawberries',
  'neapolitan:strawberry_scones',
  'neapolitan:white_strawberries'
]
for (const id of required16) {
  assert(manualOverrides.has(id), `6A.3 required override missing: ${id}`)
  const p = productRows.find(r => r[1] === id)
  assert(p, `6A.3 product missing from valid table: ${id}`)
  assert(p[2] !== 'REVIEW', `6A.3 ${id} still REVIEW`)
}

// 类型-档次互斥
for (const p of productRows) {
  const contentType = p[2]
  const t = p[15]
  if (contentType === 'DISH') {
    assert(p[5] === '是', `DISH without FOOD: ${p[1]} edible=${p[5]}`)
  }
  if (contentType === 'RAW_FOOD') {
    assert(!['COMMON', 'T2', 'T3候选', 'T3'].includes(t), `RAW_FOOD chef tier: ${p[1]}=${t}`)
  }
  if (['INGREDIENT', 'NON_FOOD', 'DRINK', 'SERVING_DISH'].includes(contentType)) {
    assert(!['COMMON', 'T2', 'T3候选', 'T3'].includes(t),
      `${contentType} chef tier leak: ${p[1]}=${t}`)
  }
  if (contentType === 'SERVING_DISH') {
    assert(t === '不进入厨师', `SERVING_DISH tier: ${p[1]}=${t}`)
  }
}

// 每个 item_id 仅一行
{
  const seen = new Set()
  for (const p of productRows) {
    assert(!seen.has(p[1]), `duplicate product id in table: ${p[1]}`)
    seen.add(p[1])
  }
}

// scarecrow is runtime valid NON_FOOD
const scareP = productRows.find(r => r[1] === 'fowlplay:scarecrow')
assert(scareP && scareP[2] === 'NON_FOOD', 'scarecrow NON_FOOD')

// SERVING_DISH never has chef tier in product table
for (const p of productRows.filter(r => r[2] === 'SERVING_DISH')) {
  assert(p[15] === '不进入厨师', `SERVING_DISH tier leak: ${p[1]}=${p[15]}`)
}

// valid recipe counts reconcilable
for (const p of productRows) {
  const pid = p[1]
  const n = Number(p[9])
  const fromAudit = validRows.filter(r => r[8] === pid).length
  assert(n === fromAudit, `有效配方数 mismatch ${pid}: table=${n} audit=${fromAudit}`)
}

// UNITE counts not stuck at 0 if packs exist
const hasUnitePacks = Object.keys(packRecipeCounts).some(p => p.startsWith('unite'))
if (hasUnitePacks) {
  assert(Object.entries(packRecipeCounts).some(([k, v]) => k.startsWith('unite') && v > 0),
    'unite pack recipe counts must be >0 when packs present')
}

// ---------- inventory lists ----------
const listHeader = ['产物中文名', '产物ID', '内容类型', '是否可食用', '建议厨师档次', '分类证据', '备注']
const handheldDish = productRows.filter(r => r[2] === 'DISH').map(r => [r[0], r[1], r[2], r[5], r[15], r[3], r[19]])
const servingDish = productRows.filter(r => r[2] === 'SERVING_DISH').map(r => [r[0], r[1], r[2], r[5], r[15], r[3], r[19]])
const drinkList = productRows.filter(r => r[2] === 'DRINK').map(r => [r[0], r[1], r[2], r[5], r[15], r[3], r[19]])
const ingredientList = productRows.filter(r => r[2] === 'INGREDIENT').map(r => [r[0], r[1], r[2], r[5], r[15], r[3], r[19]])
const reviewList = productRows.filter(r => r[2] === 'REVIEW').map(r => [r[0], r[1], r[2], r[5], r[15], r[3], r[19]])

assertCsvRectangular([listHeader, ...handheldDish].length > 1 ? [listHeader, ...handheldDish] : [listHeader])
assertCsvRectangular([listHeader, ...servingDish].length > 1 ? [listHeader, ...servingDish] : [listHeader])

// write
await mkdir(outDir, { recursive: true })
const auditPath = resolve(outDir, '新增食物模组配方审计表.csv')
const productPath = resolve(outDir, '新增食物模组产物待分级表.csv')
const inactivePath = resolve(outDir, '新增食物模组未加载产物附表.csv')
const t3Path = resolve(outDir, '新增食物模组T3候选复审表.csv')
const handheldPath = resolve(outDir, '新增食物模组普通手持料理清单.csv')
const servingPath = resolve(outDir, '新增食物模组整盘料理清单.csv')
const drinkPath = resolve(outDir, '新增食物模组饮品清单.csv')
const ingredientPath = resolve(outDir, '新增食物模组中间产物清单.csv')
const reviewPath = resolve(outDir, '新增食物模组待复审清单.csv')

await writeFile(auditPath, toCsv([auditHeader, ...auditRows]), 'utf8')
await writeFile(productPath, toCsv([productHeader, ...productRows]), 'utf8')
await writeFile(inactivePath, toCsv([inactiveHeader, ...inactiveProductRows]), 'utf8')
await writeFile(t3Path, toCsv([t3Header, ...t3CandidateRows]), 'utf8')
await writeFile(handheldPath, toCsv([listHeader, ...handheldDish]), 'utf8')
await writeFile(servingPath, toCsv([listHeader, ...servingDish]), 'utf8')
await writeFile(drinkPath, toCsv([listHeader, ...drinkList]), 'utf8')
await writeFile(ingredientPath, toCsv([listHeader, ...ingredientList]), 'utf8')
await writeFile(reviewPath, toCsv([listHeader, ...reviewList]), 'utf8')

const dishEdibleCount = productRows.filter(r => r[2] === 'DISH' && r[5] === '是').length
const dishChefCommon = productRows.filter(r => r[2] === 'DISH' && r[15] === 'COMMON').length
const dishChefT2 = productRows.filter(r => r[2] === 'DISH' && r[15] === 'T2').length
const dishChefT3 = productRows.filter(r => r[2] === 'DISH' && r[15] === 'T3候选').length

const summary = {
  phase: '6A.3',
  generated_at: new Date().toISOString(),
  note: '6A.3 最终语义收口：REVIEW=0；6A.1 的 203 DISH 与未收口的 6A.2 REVIEW 已废止。禁止进入 6B。',
  jars: resolvedMods.map(m => ({
    display: m.display,
    file: m.fileName,
    version: m.version,
    sha256: m.sha256,
    raw_recipe_count: jarRawCounts[m.display]?.raw ?? 0,
    types: jarRawCounts[m.display]?.byType ?? {}
  })),
  pack_recipe_counts: packRecipeCounts,
  runtime_export_recipes: Math.max(0, (runtime.recipe_rows?.length || 1) - 1),
  audit_recipe_rows: auditRows.length,
  runtime_valid_in_audit: runtimeValid,
  unite_add_count: uniteAdd,
  unite_override_count: uniteOverride,
  unite_disable_count: uniteDisable,
  content_type_counts_recipe_rows: contentCounts,
  product_content_type_counts: productContentCounts,
  dish_food_true: dishEdibleCount,
  serving_dish_count: productContentCounts.SERVING_DISH || 0,
  ingredient_count: productContentCounts.INGREDIENT || 0,
  drink_count: productContentCounts.DRINK || 0,
  raw_food_count: productContentCounts.RAW_FOOD || 0,
  non_food_count: productContentCounts.NON_FOOD || 0,
  review_count: productContentCounts.REVIEW || 0,
  dish_tier_counts: {
    COMMON: dishChefCommon,
    T2: dishChefT2,
    T3候选: dishChefT3
  },
  manual_override_entries: manualOverrides.size,
  manual_override_applied_products: productOverrideCount,
  manual_override_applied_recipes: overrideAppliedRecipes,
  valid_unique_products: productRows.length,
  inactive_only_products: inactiveProductRows.length,
  unique_edible_products: productRows.filter(r => r[5] === '是').length,
  tier_suggestion_counts: tierCounts,
  edible_but_ingredient_or_review: edibleIngredientReview,
  dish_event_coverage_note: {
    campfire: '未覆盖（仅枚举，无检测）',
    furnace_smoker_crafting: '已覆盖',
    blasting: 'ItemSmelted 可能触发但不适合料理',
    create: '自动化不记玩家',
    cutting: '非出锅'
  },
  outputs: {
    auditPath,
    productPath,
    inactivePath,
    t3Path,
    handheldPath,
    servingPath,
    drinkPath,
    ingredientPath,
    reviewPath,
    overridePath,
    summaryPath
  },
  deterministic_files: [
    '新增食物模组配方审计表.csv',
    '新增食物模组产物待分级表.csv',
    '新增食物模组未加载产物附表.csv',
    '新增食物模组T3候选复审表.csv',
    '新增食物模组普通手持料理清单.csv',
    '新增食物模组整盘料理清单.csv',
    '新增食物模组饮品清单.csv',
    '新增食物模组中间产物清单.csv',
    '新增食物模组待复审清单.csv',
    '新增食物模组人工分类覆盖.csv'
  ]
}

await writeFile(summaryPath, JSON.stringify(summary, null, 2), 'utf8')

console.log(JSON.stringify({
  phase: '6A.3',
  audit_rows: auditRows.length,
  runtime_valid: runtimeValid,
  valid_products: productRows.length,
  inactive_products: inactiveProductRows.length,
  product_content: productContentCounts,
  recipe_content: contentCounts,
  dish_food_true: dishEdibleCount,
  serving_dish: productContentCounts.SERVING_DISH,
  dish_tiers: { COMMON: dishChefCommon, T2: dishChefT2, T3候选: dishChefT3 },
  tiers: tierCounts,
  manual_overrides: manualOverrides.size,
  override_products: productOverrideCount,
  review_count: productContentCounts.REVIEW || 0,
  unite_add: uniteAdd,
  unite_override: uniteOverride,
  unite_disable: uniteDisable,
  pack_counts: packRecipeCounts,
  edible_ingredient_review: edibleIngredientReview.length,
  t3_candidates: t3CandidateRows.length
}, null, 2))
