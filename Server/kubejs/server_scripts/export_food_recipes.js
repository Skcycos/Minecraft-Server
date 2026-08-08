// 食韵筑家III：食品配方 CSV 导出器（阶段 6A 扩展）
//
// 用法（数据包加载完成后）：
//   /syexport food_recipes
//
// 输出：kubejs/config/food_recipe_export.json
// 再执行：
//   node Server/tools/export_food_recipe_csv.mjs
//   node Server/tools/export_phase6a_audit.mjs
//
// 设计原则：
// - 运行时 RecipeManager 是最终有效配方权威（含 UNITE 数据包效果）
// - 来源模组按「配方 ID 命名空间」筛选导出范围，不把 JAR 来源与命名空间混为一谈
// - 食品属性仅在产物可食用时填写；空产物如实记录

const SYFoodRecipeExport = {
  // 阶段 6A：旧名单 + 新增食物模组命名空间
  sourceMods: new Set([
    'farmersdelight',
    'kaleidoscope_cookery',
    'ordertocook',
    'kaleidoscope_compat',
    'spawn',
    // 6A 新增
    'neapolitan',
    'dungeonsdelight',
    'mynethersdelight',
    'brewinandchewin',
    'bakeries',
    'kaleidoscope_tavern',
    'fowlplay'
  ]),

  // 产物命名空间属于新增模组时，即使配方 ID 命名空间不在名单（例如 MND 往
  // farmersdelight: 写入的配方）也导出，便于审计「JAR 来源 ≠ 配方命名空间」。
  productNamespacesAlwaysInclude: new Set([
    'neapolitan',
    'dungeonsdelight',
    'mynethersdelight',
    'brewinandchewin',
    'bakeries',
    'kaleidoscope_tavern',
    'fowlplay'
  ]),

  // 默认关闭：避免 Create 等海量非名单配方；需要时可改为 true。
  includeFoodOutputsFromOtherMods: false,

  machineNames: {
    'minecraft:crafting_shaped': '工作台（有序合成）',
    'minecraft:crafting_shapeless': '工作台（无序合成）',
    'minecraft:smelting': '熔炉',
    'minecraft:blasting': '高炉',
    'minecraft:smoking': '烟熏炉',
    'minecraft:campfire_cooking': '营火',
    'minecraft:stonecutting': '切石机',
    'farmersdelight:cutting': '切菜板',
    'farmersdelight:cooking': '烹饪锅',
    'farmersdelight:food_serving': '盛盘（FD）',
    'kaleidoscope_cookery:stockpot': '万花筒汤锅',
    'kaleidoscope_cookery:steamer': '万花筒蒸笼',
    'kaleidoscope_cookery:pot': '万花筒炒锅',
    'kaleidoscope_cookery:chopping_board': '万花筒砧板',
    'kaleidoscope_cookery:millstone': '万花筒石磨',
    'create:mixing': '搅拌盆',
    'create:pressing': '动力压盘',
    'create:deploying': '机械手',
    'create:crushing': '粉碎轮',
    'create:haunting': '闹鬼',
    'create:compacting': '机械压力机',
    'create:emptying': '排液',
    'create:filling': '灌装',
    'create:cutting': '切割（Create）',
    'create:milling': '碾磨',
    'create:sequenced_assembly': '序列组装',
    'create:splashing': '喷溅',
    // 6A 自定义
    'dungeonsdelight:monster_cooking': '怪物烹饪锅',
    'brewinandchewin:fermenting': '发酵桶（Keg）',
    'brewinandchewin:keg_pouring': '木桶倾倒',
    'brewinandchewin:create_potion_pouring': 'Create 药水倾倒',
    'mynethersdelight:blazier_heating': '烈焰炉加热（状态）',
    'mynethersdelight:blazier_cooling': '烈焰炉冷却（状态）',
    'bakeries:oven': '烘焙坊烤箱',
    'bakeries:blender': '烘焙坊搅拌机',
    'bakeries:bread_knife': '烘焙坊面包刀',
    'bakeries:dough_crafting_table': '烘焙坊揉面台',
    'bakeries:drink': '烘焙坊饮品',
    'bakeries:fermentation_box': '烘焙坊发酵箱',
    'bakeries:flour_sieve': '烘焙坊面粉筛',
    'kaleidoscope_tavern:barrel': '酒馆木桶',
    'kaleidoscope_tavern:shaker': '酒馆摇酒器',
    'kaleidoscope_tavern:pressing_tub': '酒馆压汁桶'
  },

  safe(call, fallback = '') {
    try {
      return call()
    } catch (error) {
      return fallback
    }
  },

  itemId(stack) {
    return this.safe(() => String(stack.getItem().builtInRegistryHolder().key().location()), '')
  },

  itemName(stack, translations) {
    const id = this.itemId(stack)
    const dotId = id.replace(':', '.')
    return translations['item.' + dotId] || translations['block.' + dotId] ||
      this.safe(() => String(stack.getHoverName().getString()), id)
  },

  loadChineseTranslations(server) {
    return {}
  },

  foodData(stack) {
    const DataComponents = Java.loadClass('net.minecraft.core.component.DataComponents')
    const food = this.safe(() => stack.get(DataComponents.FOOD), null)
    if (food === null) return { edible: '否', nutrition: '', saturation: '', effects: '' }

    const effects = []
    this.safe(() => {
      const effectIterator = food.effects().iterator()
      while (effectIterator.hasNext()) {
        var effectEntry = effectIterator.next()
        var effect = effectEntry.effect()
        var effectId = this.safe(() => String(effect.getEffect().value().getDescriptionId()), '未知效果')
        var duration = this.safe(() => effect.getDuration(), 0)
        var amplifier = this.safe(() => effect.getAmplifier() + 1, 1)
        var probability = this.safe(() => effectEntry.probability(), 1)
        effects.push(`${effectId} ${duration}tick ${amplifier}级 概率${probability}`)
      }
    })
    return {
      edible: '是',
      nutrition: this.safe(() => food.nutrition(), ''),
      saturation: this.safe(() => food.saturation(), ''),
      effects: effects.join('；')
    }
  },

  ingredientText(ingredient, translations) {
    const choices = []
    this.safe(() => {
      const stacks = ingredient.getItems()
      for (var index = 0; index < stacks.length; index++) {
        var stack = stacks[index]
        var id = this.itemId(stack)
        choices.push(`${this.itemName(stack, translations)} <${id}>`)
      }
    })
    if (choices.length === 0) return '[空原料或模组自定义参数]'
    return choices.join(' / ')
  },

  machineName(type) {
    return this.machineNames[type] || `自定义工序（${type}）`
  },

  writeFiles(server, files) {
    JsonIO.write('kubejs/config/food_recipe_export.json', files)
    return 'kubejs/config/food_recipe_export.json'
  },

  shouldInclude(sourceMod, resultId, food) {
    if (this.sourceMods.has(sourceMod)) return true
    if (resultId) {
      var pns = resultId.split(':')[0]
      if (this.productNamespacesAlwaysInclude.has(pns)) return true
    }
    if (this.includeFoodOutputsFromOtherMods && food.edible === '是') return true
    return false
  },

  export(server) {
    const BuiltInRegistries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries')
    const AbstractCookingRecipe = Java.loadClass('net.minecraft.world.item.crafting.AbstractCookingRecipe')
    const translations = this.loadChineseTranslations(server)
    const recipes = server.getRecipeManager().kjs$getRecipeIdMap()
    const mainRows = [[
      '来源模组', '配方 ID', '配方中文译名（主产物）', '配方序列化类型', '合成机器/工序', '产物中文名', '产物 ID', '产物数量',
      '是否可食用', '饥饿值', '饱食度', '食用效果/BUFF', '经验值', '烹饪时间(tick)', '原料概览', '备注'
    ]]
    const ingredientRows = [[
      '配方 ID', '原料序号', '原料可选项（中文名 <ID>）', '候选物品数量', '备注'
    ]]
    const outputRows = [[
      '配方 ID', '产物序号', '产物中文名', '产物 ID', '数量', '是否可食用', '饥饿值', '饱食度', '食用效果/BUFF'
    ]]
    // 6A 扩展行：供 audit 脚本使用（与 mainRows 对齐的扩展列）
    const extendedRows = [[
      '配方 ID', '配方序列化类型', '产物 ID', '产物数量', '是否可食用', '饥饿值', '饱食度', '食用效果/BUFF',
      '经验值', '烹饪时间(tick)', '原料概览', '运行时有效', '导出备注'
    ]]
    const counts = {}
    let exported = 0
    let skipped = 0
    const iterator = recipes.entrySet().iterator()

    while (iterator.hasNext()) {
      var recipeEntry = iterator.next()
      var recipeId = String(recipeEntry.getKey())
      var sourceMod = recipeId.split(':')[0]
      // 快速路径：配方命名空间既不在名单、也不在「产物关注」名单时，
      // 若未开启“其它模组食物产物”，跳过以避免 Create 等海量反射。
      // （产物命名空间过滤在拿到 result 后再做一次。）
      if (!this.sourceMods.has(sourceMod)
          && !this.productNamespacesAlwaysInclude.has(sourceMod)
          && !this.includeFoodOutputsFromOtherMods) {
        skipped++
        continue
      }
      var recipe = recipeEntry.getValue().value()
      var result = this.safe(() => recipe.getResultItem(server.registryAccess()), null)
      var resultId = result === null || result.isEmpty() ? '' : this.itemId(result)
      var food = result === null || result.isEmpty()
        ? { edible: '否', nutrition: '', saturation: '', effects: '' }
        : this.foodData(result)

      if (!this.shouldInclude(sourceMod, resultId, food)) {
        skipped++
        continue
      }

      var type = this.safe(() => String(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer())), '未知')
      var ingredients = this.safe(() => recipe.getIngredients(), [])
      var ingredientTexts = []
      for (var index = 0; index < ingredients.size(); index++) {
        var ingredient = ingredients.get(index)
        var text = this.ingredientText(ingredient, translations)
        ingredientTexts.push(text)
        ingredientRows.push([
          recipeId, index + 1, text,
          this.safe(() => ingredient.getItems().length, 0),
          '多选项以 / 分隔；自定义配方参数请查看游戏内 JEI'
        ])
      }

      var experience = ''
      var cookingTime = ''
      if (recipe instanceof AbstractCookingRecipe) {
        experience = this.safe(() => recipe.getExperience(), '')
        cookingTime = this.safe(() => recipe.getCookingTime(), '')
      }

      var resultName = resultId === '' ? '' : this.itemName(result, translations)
      var note = resultId === '' ? '此配方没有固定产物，或产物需由容器/上下文/流体决定。' : ''
      mainRows.push([
        sourceMod, recipeId, resultName, type, this.machineName(type), resultName, resultId,
        resultId === '' ? '' : this.safe(() => result.getCount(), ''),
        food.edible, food.nutrition, food.saturation, food.effects,
        experience, cookingTime, ingredientTexts.join('；'), note
      ])
      extendedRows.push([
        recipeId, type, resultId,
        resultId === '' ? '' : this.safe(() => result.getCount(), ''),
        food.edible, food.nutrition, food.saturation, food.effects,
        experience, cookingTime, ingredientTexts.join('；'), '是', note
      ])
      if (resultId !== '') {
        outputRows.push([
          recipeId, 1, resultName, resultId, result.getCount(),
          food.edible, food.nutrition, food.saturation, food.effects
        ])
      }
      counts[sourceMod] = (counts[sourceMod] || 0) + 1
      exported++
    }

    const summaryRows = [['来源模组', '导出配方数量']]
    Object.keys(counts).sort().forEach(modId => summaryRows.push([modId, counts[modId]]))
    const meta = {
      phase: '6A',
      source_mods: Array.from(this.sourceMods).sort(),
      product_namespaces_always_include: Array.from(this.productNamespacesAlwaysInclude).sort(),
      exported: exported,
      skipped: skipped
    }
    const outputDir = this.writeFiles(server, {
      recipe_rows: mainRows,
      ingredient_rows: ingredientRows,
      output_rows: outputRows,
      mod_summary_rows: summaryRows,
      extended_rows: extendedRows,
      export_meta: meta
    })
    return { outputDir: String(outputDir), exported, skipped }
  }
}

ServerEvents.commandRegistry(event => {
  const { commands: Commands } = event
  event.register(
    Commands.literal('syexport')
      .requires(source => source.hasPermission(2))
      .then(Commands.literal('food_recipes').executes(context => {
        try {
          var exportReport = SYFoodRecipeExport.export(context.source.server)
          context.source.sendSuccess(() => Component.literal(
            `[食韵筑家] 已导出 ${exportReport.exported} 条食品模组配方至 ${exportReport.outputDir}`
          ), false)
          console.info(`[食韵筑家] 食品配方导出完成：${exportReport.exported} 条（skip=${exportReport.skipped}），目录：${exportReport.outputDir}`)
          return 1
        } catch (error) {
          console.error(`[食韵筑家] 食品配方导出失败：${error}`)
          context.source.sendFailure(Component.literal(`[食韵筑家] 导出失败，详见 logs/kubejs/server.log：${error}`))
          return 0
        }
      }))
  )
})
