// 食韵筑家III：魔酿师悬赏清单
// 与 Bountiful 的 brewer.json 保持一致；独立于厨师悬赏。

const BOUNTY_BREWER_ENTRIES = [
  { id: 'kaleidoscope_tavern:brandy', tier: 'T2', tierName: '桶酿成品', unitWorth: 35, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:carignan', tier: 'T2', tierName: '桶酿成品', unitWorth: 35, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:glowflower_brew', tier: 'T2', tierName: '桶酿成品', unitWorth: 70, amountMin: 1, amountMax: 3 },
  { id: 'kaleidoscope_tavern:honey_wine', tier: 'T2', tierName: '桶酿成品', unitWorth: 70, amountMin: 1, amountMax: 3 },
  { id: 'kaleidoscope_tavern:luminous_bride', tier: 'T2', tierName: '桶酿成品', unitWorth: 80, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:miners_star', tier: 'T2', tierName: '桶酿成品', unitWorth: 72, amountMin: 1, amountMax: 3 },
  { id: 'kaleidoscope_tavern:molotov', tier: 'T2', tierName: '桶酿成品', unitWorth: 45, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:mother_snow', tier: 'T2', tierName: '桶酿成品', unitWorth: 68, amountMin: 1, amountMax: 3 },
  { id: 'kaleidoscope_tavern:plum_wine', tier: 'T2', tierName: '桶酿成品', unitWorth: 35, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:polaris_sweet_white', tier: 'T2', tierName: '桶酿成品', unitWorth: 78, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:red_queen', tier: 'T2', tierName: '桶酿成品', unitWorth: 48, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:riesling_dry_white', tier: 'T2', tierName: '桶酿成品', unitWorth: 70, amountMin: 1, amountMax: 3 },
  { id: 'kaleidoscope_tavern:rum', tier: 'T2', tierName: '桶酿成品', unitWorth: 24, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:sakura_wine', tier: 'T2', tierName: '桶酿成品', unitWorth: 38, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:sauvignon_blanc_dry_white', tier: 'T2', tierName: '桶酿成品', unitWorth: 50, amountMin: 1, amountMax: 3 },
  { id: 'kaleidoscope_tavern:sunset_glow', tier: 'T2', tierName: '桶酿成品', unitWorth: 75, amountMin: 1, amountMax: 3 },
  { id: 'kaleidoscope_tavern:sweet_berry_wine', tier: 'T2', tierName: '桶酿成品', unitWorth: 35, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:vodka', tier: 'T2', tierName: '桶酿成品', unitWorth: 24, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:whiskey', tier: 'T2', tierName: '桶酿成品', unitWorth: 25, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:wine', tier: 'T2', tierName: '桶酿成品', unitWorth: 28, amountMin: 1, amountMax: 4 },
  { id: 'kaleidoscope_tavern:allium_garden', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 105, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:bloody_mary', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 105, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:brass_heart', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 220, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:depth_charge', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 180, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:emerald', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 165, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:godfather', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 155, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:grasshopper', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 140, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:mojito', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 110, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:nether_special', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 170, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:screwdriver', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 225, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:sculk_special', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 180, amountMin: 1, amountMax: 2 },
  { id: 'kaleidoscope_tavern:white_lady', tier: 'T3', tierName: '多阶段鸡尾酒', unitWorth: 160, amountMin: 1, amountMax: 2 }
]

global.SYBountyBrewer = {
  entries: BOUNTY_BREWER_ENTRIES,
  byId: Object.fromEntries(BOUNTY_BREWER_ENTRIES.map(e => [e.id, e])),
  tooltipTitle: '§6★ 魔酿师悬赏',
  tooltipHint: '§7食韵筑家 · 魔酿师法令可在告示板交付'
}

console.info(`[食韵筑家] 魔酿师悬赏注册：${BOUNTY_BREWER_ENTRIES.length} 项`)
