#!/usr/bin/env node
// 将 KubeJS 受限环境导出的 JSON 转为 UTF-8 BOM CSV，供 Excel 直接打开。
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'
import { homedir } from 'node:os'

const toolDir = dirname(fileURLToPath(import.meta.url))
const serverDir = resolve(toolDir, '..')
const inputPath = resolve(serverDir, 'kubejs/config/food_recipe_export.json')
const outputDir = resolve(serverDir, 'kubejs/config/food_recipe_export')

async function loadChineseTranslations() {
  const translations = {}
  // 客户端资源索引中包含原版 zh_cn；不存在客户端目录时静默跳过。
  const assetDir = resolve(homedir(), 'Desktop/hmcl/.minecraft/assets')
  try {
    for (const indexName of await readdir(resolve(assetDir, 'indexes'))) {
      if (!indexName.endsWith('.json')) continue
      const index = JSON.parse(await readFile(resolve(assetDir, 'indexes', indexName), 'utf8'))
      const language = index.objects?.['minecraft/lang/zh_cn.json']
      if (!language?.hash) continue
      Object.assign(translations, JSON.parse(await readFile(
        resolve(assetDir, 'objects', language.hash.slice(0, 2), language.hash), 'utf8'
      )))
    }
  } catch {
    // 专用服务端通常没有客户端资源，不影响模组 zh_cn 的读取。
  }
  const modDir = resolve(serverDir, 'mods')
  for (const fileName of await readdir(modDir)) {
    if (!fileName.endsWith('.jar')) continue
    const jarPath = resolve(modDir, fileName)
    let entries
    try {
      entries = execFileSync('unzip', ['-Z1', jarPath], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
    } catch {
      continue
    }
    for (const entry of entries.split(/\r?\n/)) {
      if (!/^assets\/[^/]+\/lang\/zh_cn\.json$/i.test(entry)) continue
      try {
        Object.assign(translations, JSON.parse(execFileSync('unzip', ['-p', jarPath, entry], {
          encoding: 'utf8', maxBuffer: 32 * 1024 * 1024
        })))
      } catch {
        // 单个语言文件损坏不应中断已有导出数据的转换。
      }
    }
  }
  return translations
}

function itemName(itemId, fallback, translations) {
  const dotId = itemId.replace(':', '.')
  return translations[`item.${dotId}`] || translations[`block.${dotId}`] || fallback
}

function localizeIngredientText(text, translations) {
  return String(text).replace(/([^<>；/]+) <([a-z0-9_.-]+:[a-z0-9_./-]+)>/gi, (_, fallback, itemId) =>
    `${itemName(itemId, fallback.trim(), translations)} <${itemId}>`
  )
}

function localizeEffects(text, translations) {
  return String(text).replace(/effect\.[a-z0-9_.-]+/gi, key => translations[key] || key)
}

function localizeRows(data, translations) {
  for (let index = 1; index < data.recipe_rows.length; index++) {
    const row = data.recipe_rows[index]
    const translated = itemName(row[6], row[5], translations)
    row[2] = translated
    row[5] = translated
    row[11] = localizeEffects(row[11], translations)
    row[14] = localizeIngredientText(row[14], translations)
  }
  for (let index = 1; index < data.ingredient_rows.length; index++) {
    data.ingredient_rows[index][2] = localizeIngredientText(data.ingredient_rows[index][2], translations)
  }
  for (let index = 1; index < data.output_rows.length; index++) {
    const row = data.output_rows[index]
    row[2] = itemName(row[3], row[2], translations)
    row[8] = localizeEffects(row[8], translations)
  }
}

function displayRecipeValue(value, translations) {
  if (typeof value === 'string') return value.startsWith('#') ? value : itemName(value, value, translations) + ` <${value}>`
  if (Array.isArray(value)) return value.map(entry => displayRecipeValue(entry, translations)).join(' / ')
  if (!value || typeof value !== 'object') return String(value ?? '')
  if (value.item) {
    const item = typeof value.item === 'string' ? value.item : (value.item.id || value.item.item)
    const count = value.count || value.item.count || 1
    return item ? `${itemName(item, item, translations)} <${item}>${count > 1 ? ` ×${count}` : ''}` : JSON.stringify(value)
  }
  if (value.id) return `${itemName(value.id, value.id, translations)} <${value.id}>${value.count > 1 ? ` ×${value.count}` : ''}`
  if (value.tag) return `#${value.tag}${value.count > 1 ? ` ×${value.count}` : ''}`
  if (value.fluid) return `[流体] ${value.fluid}${value.amount ? ` ×${value.amount}mB` : ''}`
  return JSON.stringify(value)
}

function extractRecipeIngredients(recipe, translations) {
  const details = []
  const add = (role, value) => {
    const values = Array.isArray(value) ? value : [value]
    for (const entry of values) details.push({ role, text: displayRecipeValue(entry, translations) })
  }
  if (recipe.ingredients) add('原料', recipe.ingredients)
  else if (recipe.ingredient) add('原料', recipe.ingredient)
  else if (recipe.input) add('原料', recipe.input)
  else if (recipe.inputs) add('原料', recipe.inputs)
  if (recipe.base) add('基础材料', recipe.base)
  if (recipe.addition) add('附加材料', recipe.addition)
  if (recipe.carrier) add('容器/载体', recipe.carrier)
  if (recipe.tool) add('工具', recipe.tool)
  return details
}

async function loadRecipeIngredients(recipeIds, translations) {
  const result = new Map()
  const wanted = new Set(recipeIds)
  const modDir = resolve(serverDir, 'mods')
  for (const fileName of await readdir(modDir)) {
    if (!fileName.endsWith('.jar')) continue
    const jarPath = resolve(modDir, fileName)
    let entries
    try {
      entries = execFileSync('unzip', ['-Z1', jarPath], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
    } catch {
      continue
    }
    for (const entry of entries.split(/\r?\n/)) {
      const match = entry.match(/(?:^|\/)data\/([^/]+)\/recipe\/(.+)\.json$/)
      if (!match) continue
      const recipeId = `${match[1]}:${match[2]}`
      if (!wanted.has(recipeId) || result.has(recipeId)) continue
      try {
        const recipe = JSON.parse(execFileSync('unzip', ['-p', jarPath, entry], {
          encoding: 'utf8', maxBuffer: 32 * 1024 * 1024
        }))
        const details = extractRecipeIngredients(recipe, translations)
        if (details.length) result.set(recipeId, details)
      } catch {
        // 有问题的单个源配方不影响其他配方 CSV。
      }
    }
  }
  return result
}

function applyRawRecipeIngredients(data, ingredientsByRecipe) {
  const ingredientRows = [data.ingredient_rows[0]]
  for (let rowIndex = 1; rowIndex < data.recipe_rows.length; rowIndex++) {
    const recipeRow = data.recipe_rows[rowIndex]
    const recipeId = recipeRow[1]
    const details = ingredientsByRecipe.get(recipeId)
    if (!details) continue
    recipeRow[14] = details.map(detail => `${detail.role}：${detail.text}`).join('；')
    details.forEach((detail, index) => ingredientRows.push([
      recipeId, index + 1, detail.text, '', detail.role
    ]))
  }
  data.ingredient_rows = ingredientRows
}

function cell(value) {
  const text = value === null || value === undefined ? '' : String(value)
  return `"${text.replaceAll('"', '""').replace(/\r?\n/g, ' ')}"`
}

function csv(rows) {
  return '\uFEFF' + rows.map(row => row.map(cell).join(',')).join('\r\n') + '\r\n'
}

const data = JSON.parse(await readFile(inputPath, 'utf8'))
const translations = await loadChineseTranslations()
localizeRows(data, translations)
const recipeIds = data.recipe_rows.slice(1).map(row => row[1])
applyRawRecipeIngredients(data, await loadRecipeIngredients(recipeIds, translations))
const exports = {
  'food_recipes.csv': data.recipe_rows,
  'food_recipe_ingredients.csv': data.ingredient_rows,
  'food_recipe_outputs.csv': data.output_rows,
  'food_recipe_mod_summary.csv': data.mod_summary_rows
}

await mkdir(outputDir, { recursive: true })
for (const [name, rows] of Object.entries(exports)) {
  if (!Array.isArray(rows)) throw new Error(`导出数据缺少 ${name} 所需的行数组`)
  await writeFile(resolve(outputDir, name), csv(rows), 'utf8')
}

console.log(`已生成 ${Object.keys(exports).length} 个 CSV：${outputDir}`)
