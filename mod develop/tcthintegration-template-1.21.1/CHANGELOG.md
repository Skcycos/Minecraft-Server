# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Deferred (needs live verification)

- **EXCELLENT/SUPERB quality bonus (+2–4 XP)** for dishes. Static tests and
  Arc data loading pass; live settlement remains deferred. Base tier rewards
  are unaffected and `dish_cooked_excellent.json` remains enabled.
- Farmer integrations still awaiting dedicated live coverage: Farmers Delight
  tomato/rice, Create harvester, FakePlayer, and protected-region cancellation.

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
