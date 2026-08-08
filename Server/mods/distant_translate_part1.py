import json, os

with open('temp_distant_en_us.json', 'r', encoding='utf-8') as f:
    en = json.load(f)

t = {}

# === General ===
t["distanthorizons.title"] = "遥远的地平线"
t["distanthorizons.general.true"] = "是"
t["distanthorizons.general.false"] = "否"
t["distanthorizons.general.yes"] = "是"
t["distanthorizons.general.no"] = "否"
t["distanthorizons.general.back"] = "返回"
t["distanthorizons.general.next"] = "下一步"
t["distanthorizons.general.done"] = "完成"
t["distanthorizons.general.cancel"] = "取消"
t["distanthorizons.general.reset"] = "重置"
t["distanthorizons.general.spacer"] = ""
t["distanthorizons.general.apiOverride"] = "API 锁定"
t["distanthorizons.general.disabledByApi.@tooltip"] = "此选项由另一个模组通过 DH 的 API 控制，因此无法通过 UI 或配置文件更改。"
t["distanthorizons.general.unsupportedMcVersion"] = "版本锁定"
t["distanthorizons.general.unsupportedMcVersion.@tooltip"] = "DH 不支持在此版本的 Minecraft 上更改此选项。配置文件或 API 设置的值将被忽略。"

# === Updater ===
t["distanthorizons.updater.title"] = "遥远的地平线自动更新器"
t["distanthorizons.updater.updateAvailable"] = "§l有新更新可用！"
t["distanthorizons.updater.updateConfirmation"] = "§f是否要从 %s§f 更新到 %s§f？"
t["distanthorizons.updater.later"] = "暂不"
t["distanthorizons.updater.never"] = "不再显示"
t["distanthorizons.updater.update"] = "更新"
t["distanthorizons.updater.update.@tooltip"] = "本次更新模组\n（游戏关闭时更新）"
t["distanthorizons.updater.silent"] = "始终静默更新"
t["distanthorizons.updater.silent.@tooltip"] = "每次有可用更新时都会更新\n（§6警告§r：更新时不会提示您）"
t["distanthorizons.updater.waitingForClose"] = "遥远的地平线将在游戏重启后完成更新"

# === Config ===
t["distanthorizons.config.title"] = "遥远的地平线配置"
t["distanthorizons.config.client"] = "客户端"
t["distanthorizons.config.client.quickEnableRendering"] = "启用渲染"
t["distanthorizons.config.client.quickEnableRendering.@tooltip"] = "如果启用，遥远的地平线将在原版渲染距离之外渲染 LOD。"
t["distanthorizons.config.client.quickShowWorldGenProgress"] = "显示 LOD 生成/导入进度"
t["distanthorizons.config.client.quickShowWorldGenProgress.@tooltip"] = "如果启用，运行时将显示世界生成/导入进度。"
t["distanthorizons.config.client.qualityPresetSetting"] = "质量预设"
t["distanthorizons.config.client.qualityPresetSetting.@tooltip"] = "修改多项图形设置以快速改变遥远的地平线渲染质量。\n\n如果 GPU 使用率达到上限或出现帧率问题，请降低此设置。"
t["distanthorizons.config.client.threadPresetSetting"] = "CPU 负载"
t["distanthorizons.config.client.threadPresetSetting.@tooltip"] = "修改遥远的地平线使用的线程数。\n\n增加此设置将提高远程生成器速度和 LOD 加载速度，\n但也会增加 CPU/内存使用并可能引入卡顿。\n\n注意：这是相对于 CPU 的设置。\n它应该对 2 核 CPU 和 64 核 CPU 施加相同的压力。"
t["distanthorizons.config.client.optionsButton"] = "显示选项按钮"
t["distanthorizons.config.client.optionsButton.@tooltip"] = "在 FOV 按钮左侧显示配置按钮"

# === Advanced ===
t["distanthorizons.config.client.advanced"] = "高级选项"
t["distanthorizons.config.client.advanced.graphics"] = "图形"
t["distanthorizons.config.client.advanced.worldGenerator"] = "世界生成器"
t["distanthorizons.config.client.advanced.server"] = "服务器"
t["distanthorizons.config.client.advanced.lodBuilding"] = "LOD 构建"
t["distanthorizons.config.client.advanced.multiThreading"] = "多线程"
t["distanthorizons.config.client.advanced.logging"] = "日志"

# === Graphics Quality ===
t["distanthorizons.config.client.advanced.graphics.quality"] = "质量"
t["distanthorizons.config.client.advanced.graphics.quality.lodChunkRenderDistanceRadius"] = "LOD 区块渲染距离半径"
t["distanthorizons.config.client.advanced.graphics.quality.lodChunkRenderDistanceRadius.@tooltip"] = "遥远的地平线的渲染距离，以区块为单位。\n\n注意：这是一个尽力而为的数字。\n实际渲染距离可能高于或低于此数字，\n具体取决于您的其他图形设置。"
t["distanthorizons.config.client.advanced.graphics.quality.horizontalQuality"] = "LOD 衰减距离"
t["distanthorizons.config.client.advanced.graphics.quality.horizontalQuality.@tooltip"] = "这表示 LOD 质量下降之间的距离。\n\n较高的设置会增加下降之间的距离，\n但会增加内存和 GPU 使用。"
t["distanthorizons.config.client.advanced.graphics.quality.maxHorizontalResolution"] = "最大水平分辨率"
t["distanthorizons.config.client.advanced.graphics.quality.maxHorizontalResolution.@tooltip"] = "LOD 可以渲染的最大细节。\n\n§6最快：§r区块\n§6最精美：§r方块"
t["distanthorizons.config.client.advanced.graphics.quality.verticalQuality"] = "垂直质量"
t["distanthorizons.config.client.advanced.graphics.quality.verticalQuality.@tooltip"] = "LOD 如何表示悬垂物、洞穴、悬崖等。\n\n较高的选项会增加内存和 GPU 使用。"
t["distanthorizons.config.client.advanced.graphics.quality.useCameraPositionForQualityDropOff"] = "使用相机位置进行质量衰减"
t["distanthorizons.config.client.advanced.graphics.quality.useCameraPositionForQualityDropOff.@tooltip"] = "如果启用，DH 将在确定 LOD 质量衰减时尝试使用相机位置。\n如果禁用，DH 将使用玩家位置。\n\n启用有助于自由相机模组正确渲染。\n禁用有助于多相机模组正确渲染（例如沉浸式传送门或相机模组）。"
t["distanthorizons.config.client.advanced.graphics.quality.increaseQualityWhenZoomedIn"] = "放大时提高质量"
t["distanthorizons.config.client.advanced.graphics.quality.increaseQualityWhenZoomedIn.@tooltip"] = "如果启用，当相机放大时 LOD 质量会增加，\n例如使用望远镜或缩放模组时。\n\n只有通过相机视图可见的 LOD 会受到影响。\n\n放大时，LOD 将加载到与靠近它们时相同的细节级别。"
t["distanthorizons.config.client.advanced.graphics.quality.maxZoomQualityIncrease"] = "最大放大质量提升"
t["distanthorizons.config.client.advanced.graphics.quality.maxZoomQualityIncrease.@tooltip"] = "放大可以将 LOD 质量提高多少个细节级别。\n较高的数字允许更强的缩放渲染更清晰的地形，\n但在放大时会增加内存和 GPU 使用。\n\n原版望远镜需要 4 个细节级别才能达到完整质量。"
t["distanthorizons.config.client.advanced.graphics.quality.horizontalScale"] = "水平缩放"
t["distanthorizons.config.client.advanced.graphics.quality.horizontalScale.@tooltip"] = "LOD 质量下降的速度。\n\n较大的数字会改善远处地形的外观，\n但会增加内存和 GPU 使用。"
t["distanthorizons.config.client.advanced.graphics.quality.transparency"] = "透明度"
t["distanthorizons.config.client.advanced.graphics.quality.lodShading"] = "LOD 着色"
t["distanthorizons.config.client.advanced.graphics.quality.lodShading.@tooltip"] = "定义 LOD 应如何着色。\n可用于改善着色器兼容性。"
t["distanthorizons.config.client.advanced.graphics.quality.grassSideRendering"] = "草方块侧面渲染"
t["distanthorizons.config.client.advanced.graphics.quality.grassSideRendering.@tooltip"] = "草方块 LOD 的侧面和底部应如何渲染？"
t["distanthorizons.config.client.advanced.graphics.quality.ditherDhFade"] = "淡化附近的 DH LOD"
t["distanthorizons.config.client.advanced.graphics.quality.ditherDhFade.@tooltip"] = "如果启用，LOD 会在靠近时逐渐消失。\n如果禁用，LOD 会在相机设定距离处突然消失。\n此设置受原版过绘制预防配置的影响。"
t["distanthorizons.config.client.advanced.graphics.quality.vanillaFadeMode"] = "原版淡化模式"
t["distanthorizons.config.client.advanced.graphics.quality.vanillaFadeMode.@tooltip"] = "原版 Minecraft 应如何淡化到遥远的地平线 LOD？\n\n无：最快，DH 和 MC 渲染之间会有明显的边界。\n单次传递：在 MC 的透明传递后淡化，水下不透明方块不会被淡化。\n双重传递：最慢，在 MC 的不透明和透明传递后淡化，提供最平滑的过渡。"
t["distanthorizons.config.client.advanced.graphics.quality.dhFadeFarClipPlane"] = "在远裁剪平面之前淡化"
t["distanthorizons.config.client.advanced.graphics.quality.dhFadeFarClipPlane.@tooltip"] = "DH 是否应该在到达远裁剪平面之前淡出？\n这有助于防止 DH 云层在远处突然消失。"
t["distanthorizons.config.client.advanced.graphics.quality.brightnessMultiplier"] = "亮度倍率"
t["distanthorizons.config.client.advanced.graphics.quality.brightnessMultiplier.@tooltip"] = "LOD 颜色的亮度。\n\n0 = 黑色\n1 = 正常\n2 = 接近白色"
t["distanthorizons.config.client.advanced.graphics.quality.saturationMultiplier"] = "饱和度倍率"
t["distanthorizons.config.client.advanced.graphics.quality.saturationMultiplier.@tooltip"] = "LOD 颜色的饱和度。\n\n0 = 黑白\n1 = 正常\n2 = 鲜艳"
t["distanthorizons.config.client.advanced.graphics.quality.lodBiomeBlending"] = "生物群系混合"
t["distanthorizons.config.client.advanced.graphics.quality.lodBiomeBlending.@tooltip"] = "这与 LOD 区域的原版生物群系混合设置相同。\n\n注意：任何高于 '0' 的值都会减慢 LOD 加载时间。\n\n'0' 等于原版生物群系混合 '1x1'，\n'1' 等于原版生物群系混合 '3x3'，\n'2' 等于原版生物群系混合 '5x5'..."

# === SSAO ===
t["distanthorizons.config.client.advanced.graphics.enableSsao"] = "启用环境光遮蔽"
t["distanthorizons.config.client.advanced.graphics.enableSsao.@tooltip"] = "环境光遮蔽为方块的照明增加深度。"

# === Generic Rendering ===
t["distanthorizons.config.client.advanced.graphics.genericRendering"] = "通用对象渲染"
t["distanthorizons.config.client.advanced.graphics.genericRendering.enableGenericRendering"] = "启用通用渲染"
t["distanthorizons.config.client.advanced.graphics.genericRendering.enableGenericRendering.@tooltip"] = "如果启用，DH 将渲染非地形对象。\n例如信标光束和云层。"
t["distanthorizons.config.client.advanced.graphics.genericRendering.enableBeaconRendering"] = "启用信标渲染"
t["distanthorizons.config.client.advanced.graphics.genericRendering.beaconRenderHeight"] = "信标渲染高度"
t["distanthorizons.config.client.advanced.graphics.genericRendering.beaconRenderHeight.@tooltip"] = "设置信标将渲染到的最大高度。需要重新加载世界才能生效。"
t["distanthorizons.config.client.advanced.graphics.genericRendering.expandDistantBeacons"] = "扩展远处信标"
t["distanthorizons.config.client.advanced.graphics.genericRendering.expandDistantBeacons.@tooltip"] = "如果启用，LOD 信标光束将在极远处渲染得更宽，\n使它们更容易看到。\n如果禁用，所有 LOD 信标光束将始终只有 1 格宽。"
t["distanthorizons.config.client.advanced.graphics.genericRendering.enableBeaconRendering.@tooltip"] = "如果启用，将渲染 LOD 信标光束。"
t["distanthorizons.config.client.advanced.graphics.genericRendering.enableCloudRendering"] = "启用云层渲染"
t["distanthorizons.config.client.advanced.graphics.genericRendering.enableCloudRendering.@tooltip"] = "如果启用，将渲染 LOD 云层。"
t["distanthorizons.config.client.advanced.graphics.genericRendering.dimensionEnabledCloudRenderingCsv"] = "云层启用维度 CSV 列表"
t["distanthorizons.config.client.advanced.graphics.genericRendering.dimensionEnabledCloudRenderingCsv.@tooltip"] = "DH 云层将渲染的维度资源位置的逗号分隔列表。\n\n示例：\"minecraft:overworld,minecraft:the_end\"\n\n更改需要重新加载世界。"
t["distanthorizons.config.client.advanced.graphics.genericRendering.enableMultiLayerClouds"] = "启用多层云层"
t["distanthorizons.config.client.advanced.graphics.genericRendering.enableMultiLayerClouds.@tooltip"] = "禁用 = DH 将渲染单层云层，如原版 Minecraft。\n启用 = DH 将在不同高度渲染 3 层云层。"

# === Fog ===
t["distanthorizons.config.client.advanced.graphics.fog"] = "雾"
t["distanthorizons.config.client.advanced.graphics.fog.enableDhFog"] = "启用遥远的地平线雾"
t["distanthorizons.config.client.advanced.graphics.fog.enableDhFog.@tooltip"] = "确定是否在 DH LOD 上绘制雾。"
t["distanthorizons.config.client.advanced.graphics.fog.colorMode"] = "雾颜色模式"
t["distanthorizons.config.client.advanced.graphics.fog.colorMode.@tooltip"] = "LOD 上雾的颜色。"
t["distanthorizons.config.client.advanced.graphics.fog.enableVanillaFog"] = "启用原版雾"
t["distanthorizons.config.client.advanced.graphics.fog.enableVanillaFog.@tooltip"] = "§6启用：§r Minecraft 正常渲染雾。\n§6禁用：§r 禁用原版区块上的 Minecraft 雾。\n\n可能影响其他修改雾的模组。"
t["distanthorizons.config.client.advanced.graphics.fog.advancedFog"] = "高级雾选项"
t["distanthorizons.config.client.advanced.graphics.fog.farFogStart"] = "雾起始"
t["distanthorizons.config.client.advanced.graphics.fog.farFogStart.@tooltip"] = "雾应该从哪里开始？\n\n  '0.0'：雾从玩家位置开始。\n  '1.0'：雾起始的圆正好适合 LOD 渲染距离的方形。\n'1.414'：LOD 渲染距离的方形正好适合雾起始的圆。"

# Print remaining keys to translate
done = set(t.keys())
remaining = {k: v for k, v in en.items() if k not in done}
print(f"Done: {len(done)}, Remaining: {len(remaining)}")
for k in remaining:
    print(f"TODO: {k}")
