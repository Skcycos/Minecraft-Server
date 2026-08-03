// 食韵筑家III：食品配方 CSV 导出器
//
// 用法：在已启动且加载完数据包的服务端控制台，或有权限的游戏内聊天框执行：
//   /syexport food_recipes
//
// 输出文件：kubejs/config/food_recipe_export.json
// 再执行 Server/tools/export_food_recipe_csv.mjs，将生成 CSV 文件。
//
// 设计原则：只按来源模组筛选，而不是用“是否可食用”过滤。这样会保留锅、刀具、
// 菜板等与料理流程有关的配方；食品属性仅在产物可食用时填写。

const SYFoodRecipeExport = {
  sourceMods: new Set([
    'farmersdelight',
    'kaleidoscope_cookery',
    'ordertocook',
    'kaleidoscope_compat',
    // 动物生态含鱼类、蒸蛤蜊、罐装鲱鱼等食品配方；保留其来源，便于统一菜单化。
    'spawn'
  ]),

  // 需要把其他模组“产出食物”的配方也纳入时，改为 true。
  // 例如 Create 的搅拌盆配方；默认关闭，避免总表混入无关配方。
  includeFoodOutputsFromOtherMods: false,

  machineNames: {
    'minecraft:crafting_shaped': '工作台（有序合成）',
    'minecraft:crafting_shapeless': '工作台（无序合成）',
    'minecraft:smelting': '熔炉',
    'minecraft:blasting': '高炉',
    'minecraft:smoking': '烟熏炉',
    'minecraft:campfire_cooking': '营火',
    'farmersdelight:cutting': '切菜板',
    'farmersdelight:cooking': '烹饪锅',
    'kaleidoscope_cookery:stockpot': '万花筒汤锅',
    'kaleidoscope_cookery:steamer': '万花筒蒸笼',
    'create:mixing': '搅拌盆',
    'create:pressing': '动力压盘',
    'create:deploying': '机械手',
    'create:crushing': '粉碎轮',
    'create:haunting': '闹鬼',
    'create:compacting': '机械压力机',
    'create:emptying': '排液',
    'create:filling': '灌装'
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
    // 模组物品可能注册为 block，因此同时尝试 item 与 block 翻译键。
    return translations['item.' + dotId] || translations['block.' + dotId] ||
      this.safe(() => String(stack.getHoverName().getString()), id)
  },

  // 服务器端 Component 会解析当前已加载的物品名称。禁止直接读 JAR，避免突破 KubeJS 沙箱。
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
    // JsonIO 是 KubeJS 官方暴露的受限文件接口，只能写 kubejs/config 或存档目录。
    JsonIO.write('kubejs/config/food_recipe_export.json', files)
    return 'kubejs/config/food_recipe_export.json'
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
    const counts = {}
    let exported = 0
    let skipped = 0
    const iterator = recipes.entrySet().iterator()

    while (iterator.hasNext()) {
      var recipeEntry = iterator.next()
      var recipeId = String(recipeEntry.getKey())
      var sourceMod = recipeId.split(':')[0]
      // 默认只导出名单内的模组。先过滤，避免为六千多条无关配方反射创建产物，
      // 也避免触发 Create 客户端专用物品在专用服务端上的反射警告。
      if (!this.sourceMods.has(sourceMod) && !this.includeFoodOutputsFromOtherMods) continue
      var recipe = recipeEntry.getValue().value()
      var result = this.safe(() => recipe.getResultItem(server.registryAccess()), null)
      var resultId = result === null || result.isEmpty() ? '' : this.itemId(result)
      var food = result === null || result.isEmpty() ? { edible: '否', nutrition: '', saturation: '', effects: '' } : this.foodData(result)
      var included = this.sourceMods.has(sourceMod) || (this.includeFoodOutputsFromOtherMods && food.edible === '是')
      if (!included) continue

      var type = this.safe(() => String(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer())), '未知')
      var ingredients = this.safe(() => recipe.getIngredients(), [])
      var ingredientTexts = []
      for (var index = 0; index < ingredients.size(); index++) {
        var ingredient = ingredients.get(index)
        var text = this.ingredientText(ingredient, translations)
        ingredientTexts.push(text)
        ingredientRows.push([recipeId, index + 1, text, this.safe(() => ingredient.getItems().length, 0), '多选项以 / 分隔；自定义配方参数请查看游戏内 JEI'])
      }

      var experience = ''
      var cookingTime = ''
      if (recipe instanceof AbstractCookingRecipe) {
        experience = this.safe(() => recipe.getExperience(), '')
        cookingTime = this.safe(() => recipe.getCookingTime(), '')
      }

      var resultName = resultId === '' ? '' : this.itemName(result, translations)
      mainRows.push([
        sourceMod, recipeId, resultName, type, this.machineName(type), resultName, resultId,
        resultId === '' ? '' : this.safe(() => result.getCount(), ''), food.edible, food.nutrition, food.saturation,
        food.effects, experience, cookingTime, ingredientTexts.join('；'),
        resultId === '' ? '此配方没有固定产物，或产物需由容器/上下文决定。' : ''
      ])
      if (resultId !== '') {
        outputRows.push([recipeId, 1, resultName, resultId, result.getCount(), food.edible, food.nutrition, food.saturation, food.effects])
      }
      counts[sourceMod] = (counts[sourceMod] || 0) + 1
      exported++
    }

    const summaryRows = [['来源模组', '导出配方数量']]
    Object.keys(counts).sort().forEach(modId => summaryRows.push([modId, counts[modId]]))
    const outputDir = this.writeFiles(server, {
      recipe_rows: mainRows,
      ingredient_rows: ingredientRows,
      output_rows: outputRows,
      mod_summary_rows: summaryRows
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
          console.info(`[食韵筑家] 食品配方 CSV 导出完成：${exportReport.exported} 条，目录：${exportReport.outputDir}`)
          return 1
        } catch (error) {
          console.error(`[食韵筑家] 食品配方 CSV 导出失败：${error}`)
          context.source.sendFailure(Component.literal(`[食韵筑家] 导出失败，详见 logs/kubejs/server.log：${error}`))
          return 0
        }
      }))
  )
})
