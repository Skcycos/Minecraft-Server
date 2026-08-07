# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.2] - 2026-08-07

### 4A.4 / 4A.4.1 — farmer crop compatibility expansion

- Added mature harvest detection for Neapolitan strawberry bushes, mint and
  adzuki sprouts, plus Dungeons Delight rotbulb crops.
- Added conditional right-click harvest detection for My Nether's Delight
  powdery cane, powdery cannon and the two fungus colonies.
- Kept crop identity tied to world block IDs under Kaleidoscope Compat UNITE
  mode; no duplicate Arc action, reward value change or new currency payout.
- Added fail-closed exclusions for placeable or maturity-ambiguous plants and
  normalized double-height rotbulb positions before idempotency checks.
- Pinned the new optional development JARs and Scorched Guns in the standalone
  CI template with exact download URLs and SHA-256 verification.
- Bounded optional dependency ranges to the server-verified version families.

### 5C / 5C.1 — gunner battlefield profile and medals

- Reuses 5A `PlayerGunnerStats` counters (no second kill/weapon/tier/distance
  store). `maxDistance` is the longest-kill field; finite/`>=0` on record;
  NaN/inf/negative sanitized on load.
- Main weapon / Top-N ranking: kill count descending, full item id ascending;
  Top-N returns immutable `Map.entry` snapshots (setValue cannot mutate
  internal kills) — 5C.1.
- Five permanent medals with single-source threshold constants; unlocked state
  in `tcth_gunner_stats.dat` (`dataVersion` 2).
- Silent reconcile for v1 archives and missing medals (`unlockedAt=0`, no chat).
- Live unlock after successful stats write; optional personal chat via
  `gunnerMedalAnnouncementsEnabled` (default true); multi-medal one-line merge.
- **5C.1 localization:** medal names, profile labels and unlock chat use
  `Component.translatable` + en_us/zh_cn keys; no server-side language sniffing
  or hardcoded player-facing zh/en strings.
- `/tcth gunner profile [player]` full battlefield view; `stats` kept as
  compact compatibility command.
- No gold, no extra XP, no items, no GD656 interaction.

### 5B.1 / 5B.1.1 — gunner ability fix

- Ammo-saver covers both real SG deduction entries: common
  `handleShoot` `Math.max` `@Redirect`, and BEAM-period `consumeAmmo` HEAD.
  Ordinary pistols/rifles/shotguns/rockets/grenades/Niami were missing in 5B.
- **5B.1.1:** beam HEAD no longer rolls when creative / `IgnoreAmmo` / empty
  ammo / missing stack — `AmmoSaverBeamGate` + read-only
  `AmmoSaverStackRead` (`DataComponents.CUSTOM_DATA` get + `copyTag` only).
- Control-flow docs corrected from live `javap` on the server SG 1.5 JAR:
  projectiles/beam run before the common ammo block; SEMI_BEAM has no periodic
  `consumeAmmo`; BEAM may hit both entries (one roll each).
- Split mixin configs: `scguns_compat` (Niami, `scguns`) and
  `scguns_ammo_compat` (ammo-saver, `scguns`+`jobsplus`).
- `JobsPlusCompatModule` registers SG-dependent gunner routes only when
  `scguns` is loaded; study route stays Jobs+/Arc-only.
- Structural dependency tests (`GunnerDependencyMatrixTest`) are explicitly
  **not** live four-combination boots; only full TCTH+SG+Jobs+/Arc is LIVE PASS.
- Throttled the two high-frequency `GunnerAbilityModule` WARN logs (one / 60 s).

### Deferred (needs live verification)

- **5B ammo-saver live acceptance (DEFERRED by owner, 2026-08-07)**:
  static tests, SG bytecode audit and the full-mod smoke test pass, but live
  checks for ordinary guns, shotgun, rocket/grenade, Niami, BEAM, SEMI_BEAM,
  one-round-left saving, creative mode and `IgnoreAmmo` remain unverified.
  This is not counted as PASS and does not block continued test-server
  development.
- **EXCELLENT/SUPERB quality bonus (+2–4 XP)** for dishes. Static tests and
  Arc data loading pass; live settlement remains deferred. Base tier rewards
  are unaffected and `dish_cooked_excellent.json` remains enabled.
- Farmer integrations still awaiting dedicated live coverage: Farmers Delight
  tomato/rice, Create harvester, FakePlayer, and protected-region cancellation.
- **5A.3 gunner negative-case re-verification (DEFERRED)**: delayed
  fire/lava/poison/wither death, gun-stock melee, vanilla bow/crossbow kills,
  one explosion killing multiple targets, FakePlayer/turret kills. Core
  positive paths, strong-evidence attribution and the four-tier XP settlement
  passed live acceptance (5A.2); test-server rewards stay enabled for
  observation. Not counted as PASS until re-verified.

## [0.2.1] - 2026-08-06

### Added

- Gunner abilities in-memory debug switch `/tcth debug gunner on|off|status`
  (default off; only logs confirmed gun-kill events).

## [0.2.0] - 2026-08-06

### Added

- Unified server-side cooking event across vanilla crafting/smelting, Farmers
  Delight cooking pots, and Kaleidoscope Cookery pots, stockpots and steamers.
- Data-driven `tcth:chef` preset with mutually exclusive dish tiers, Arc/Jobs+
  experience rewards, four ability routes, cooking statistics and commands.
- Field Guide cookbook integration with cook-only unlocks for 166 dishes.
- Persistent cooking signatures with client tooltips and inspection commands.
- Unified `CropHarvestedEvent` with maturity checks, FakePlayer rejection,
  bounded idempotency, break/right-click detectors and farming debug commands.
- Data-driven `tcth:farmer` preset with crop, breeding, taming and shearing
  rewards; verified single-settlement crop experience for supported crops.
- English and Simplified Chinese text for the custom professions, abilities,
  configuration and diagnostics.

### Changed

- Chef experience multipliers are capped at 1.25x / 1.5x / 2.0x and higher
  ability tiers exclude lower tiers.
- Farmer crop rewards use `tcth:on_crop_harvested` instead of the Jobs+ default
  harvest action, preventing duplicate settlement and enabling modded crops.
- Pumpkin and melon harvest rewards are excluded because placed fruit blocks
  cannot be distinguished safely from naturally grown fruit.

### Verified

- Full automated test suite and dedicated-server smoke tests.
- Player operation for the seven cooking devices, chef abilities, cookbook
  unlocks, cooking signatures, farmer crop events and farmer XP settlement.

## [0.1.0] - 2026-08-05

### Added

- Project skeleton migrated from the NeoForged MDK template:
  - Mod ID `tcth` (was `tcthintegration`), NeoForge 21.1.247, version 0.1.0.
  - Removed all generator sample code (example block / food / creative tab,
    magic-number config, sample logging).
  - Namespace-migrated resources: `tcth.mixins.json`, `assets/tcth/`,
    `tcth.*` translation keys (en_us + zh_cn).
  - Clean mod entry point, config skeleton and a conditional compat module
    loader (`com.tanrunn.tcth.api.compat.CompatModule` +
    `com.tanrunn.tcth.impl.compat.CompatLoader`).
- Official project files: `LICENSE` (MIT, 2026 Tanrunn), `README.md`,
  `README_zh_CN.md`, `CHANGELOG.md`. The NeoForged template license is kept in
  `TEMPLATE_LICENSE.txt`.
