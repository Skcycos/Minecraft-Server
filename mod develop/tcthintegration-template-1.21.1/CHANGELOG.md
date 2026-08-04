# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (0.1.0 scope)

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

### Deferred (needs live verification)

- **EXCELLENT/SUPERB quality bonus (+2–4 XP)** for dishes:
  - Feature: dishes with EXCELLENT or SUPERB Kaleidoscope Cookery quality gain
    an extra 2–4 XP on top of the base tier reward.
  - Static tests: **pass** (quality mapping, condition, preset).
  - Arc data loading: **pass** (dish_cooked_excellent.json loaded, zero errors).
  - Player live settlement: **not yet verified** — this round's KC dishes were
    all POOR/UNKNOWN quality; the user decided to defer.
  - Risk: base tier rewards are unaffected; only the live evidence for the
    quality bonus is missing.
  - Verification path: cook one EXCELLENT or SUPERB dish, record XP before and
    after take-out; expected delta = base tier XP + 2–4 XP.
  - `dish_cooked_excellent.json` is kept enabled (not removed, not disabled).

### Release status

- **Test-server usable; quality bonus awaits live verification.**

### Not yet implemented

- Unified dish-cooked event and all third-party compat modules (Jobs+, Arc,
  Kaleidoscope Cookery, Farmer's Delight, Bountiful, Order to Cook,
  Lightman's Currency). No feature is claimed in this release.
