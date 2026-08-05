# TCTH Integration

A configurable, data-driven **NeoForge integration framework** that connects
cooking, professions, bounties, orders, and economy systems across supported
mods.

> **Status:** pre-release / work in progress. Public API stability is not yet
> guaranteed. See [CHANGELOG.md](CHANGELOG.md).

---

## Requirements

| Dependency | Version | Type |
|---|---|---|
| Minecraft | 1.21.1 | required |
| NeoForge | 21.1.247 | required |
| Java | 21 | required (toolchain) |

## Supported mods

All third-party integrations are **optional**: TCTH starts and runs normally
when any of these mods is absent. Compat modules are only loaded when their
target mod is installed.

| Mod | Mod ID | Tested version | Status |
|---|---|---|---|
| Jobs+ | `jobsplus` | 9.0.0 | planned |
| Arc | `arc` | 9.0.0 | planned |
| Kaleidoscope Cookery | `kaleidoscope_cookery` | 1.4.1 | planned |
| Farmer's Delight | `farmersdelight` | 1.3.2 | planned |
| Bountiful | `bountiful` | 8.0.0-beta.2 | planned |
| Order to Cook | `ordertocook` | 1.3.5 | planned |
| Lightman's Currency | `lightmanscurrency` | 2.3.0.5 | planned |
| Kaleidoscope Compat | `kaleidoscope_compat` | 2.9.7 | planned |

No compat feature is implemented yet; the table above lists the integration
targets and the exact versions verified against the test server. Features are
added one module at a time and are documented here as they land.

## Installation

1. Install Minecraft 1.21.1 with NeoForge **21.1.247** (or newer compatible
   loader).
2. Copy `tcth-0.1.0.jar` (or the current version) into the `mods/` folder.
3. Start the server / game once to generate the default config file at
   `config/tcth-common.toml`.

No third-party mod JAR is bundled with, or required to be copied into, TCTH's
own release archive.

## Configuration

After the first launch, edit `config/tcth-common.toml`:

- `enabled` — master switch for the whole framework. As of phase 1A this is
  **mechanically enforced** at the unified publishing entry: the dish-cooked
  event dispatcher posts no events when it is `false`. Compat modules are also
  expected to check it (or their own toggle) before performing business logic.

Every individual compat feature added in later phases gets its own toggle, so
each integration can be turned off independently.

**Reward switch semantics:** `jobsPlusRewardsEnabled` controls whether the
`tcth:on_dish_cooked` Arc action is sent for dish events. It does **not** gate
the preset's `taste_meal` action — that is a separate `arc:on_eat` action: as
soon as the `tcth-chef` data pack is enabled, eating `#tcth:chef_meals` grants
1 XP even when `jobsPlusRewardsEnabled=false`. During zero-reward drills, do
not eat those dishes.

## Client requirement

When Jobs+/Arc integration or the `tcth-chef` preset is enabled, **the server
and every client must install a matching version of TCTH Integration**. TCTH
registers custom Arc action/condition/data types and ships the Jobs+ job
translation resources; without it on the client, the Jobs+ GUI cannot resolve
the TCTH types and job/powerup text is missing.

## Building from source

```bash
./gradlew clean build
```

The built JAR is written to `build/libs/`.

### Development-only third-party dependencies

During development, third-party mod JARs may be placed in `libs/` and wired up
as `compileOnly` / `localRuntime` dependencies for local compilation and
testing. They are **never** packaged into the released JAR.

For public CI, third-party compile dependencies must be fetched from their
published Maven repositories (see the CI acquisition plan below) so that
`./gradlew clean build` works on a machine with no local `Minecraft-Server`
directory.

### CI dependency acquisition plan

GitHub Actions builds run on a clean runner with **no local `Server/mods/` and
no `libs/` folder**, so every compile-time dependency must be fetchable from a
published, repeatable source. The rules are:

- Each dependency used directly by source code is declared as `compileOnly`
  (API surface only); the full mod is only added to `localRuntime` for local
  tests. **javac requires every third-party type the source references** — the
  runtime-side isolation provided by reflection / `ModList`-guarded loading does
  not remove the compile-time need.
- Before a compat module is developed, a repeatable acquisition source must be
  pinned and verified (official Maven repositories such as
  `https://maven.modrinth.com`, the mod author's own Maven, or a Maven
  publication of the exact tested version).
- If a mod has no usable public Maven artifact, a concrete plan must be
  submitted first: either a CI-compatible acquisition step (e.g. downloading
  the exact jar from a pinned URL and adding it as a flat-dir dependency), or a
  strict interface-isolation design that keeps the third-party types out of the
  compiled sources entirely. "Reflection handles it" is **not** an acceptable
  substitute for a build-time dependency plan.
- Directly downloading a third-party JAR for CI must comply with that project's
  license and redistribution rules. Third-party mod JARs are **never**
  committed to this repository and **never** bundled into the released TCTH
  JAR.

## Links

- Homepage / source repository / issue tracker: **TBD** — to be filled once the
  public GitHub repository is created. No placeholder URLs are used.

## License

This project is licensed under the [MIT License](LICENSE). Copyright (c) 2026
Tanrunn. The original NeoForged MDK template license is retained in
[TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt).

> License status is *provisional*: it will be confirmed by the project owner
> before any public release.

---

中文说明见 [README_zh_CN.md](README_zh_CN.md)。

## Dish signing (phase 3C)

Newly taken-out dishes carry the `tcth:cooking_signature` component
(`chefId` = chef UUID, `chefName` = name snapshot at signing time) and show a
single low-key `Chef: <name>` tooltip line on the client. `/tcth chef inspect`
is a read-only inspector for the held dish.

- Toggle: `dishSignaturesEnabled` (default `true`). Only affects signing of
  NEWLY produced dishes; disabling it does not remove existing signatures and
  does not affect cooking stats, Field Guide unlocks or Jobs+/Arc.
- When: the player personally takes the dish out (crafting table, furnace,
  smoker, FD cooking pot, KC wok/stockpot/steamer) — the REAL delivered stack
  is signed. Automated extraction, failed take-outs, bowls/shovels/containers,
  ingredients and `raw_dough` are never signed. Bulk results are signed as a
  whole; re-processing by the same chef keeps the signature consistent.
- Stacking: dishes by the same chef (same UUID + same name) stack together;
  dishes by different chefs do not. After a rename, new dishes stack
  separately from old-name signatures — a historical snapshot, expected
  behaviour, not an error.
- Security boundary: the signature is provenance/display data, NOT a trusted
  economic credential. Creative mode, admin commands or third-party mods can
  construct items with any component; future gold/experience/order settlement
  must never trust the signature alone — rewards stay keyed to real server-side
  `DishCookedEvent`s, order state and idempotent records. No reward is granted
  from signatures in this phase.
