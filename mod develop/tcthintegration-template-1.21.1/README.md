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
| Jobs+ | `jobsplus` | 9.0.0 | 已实现：厨师职业、料理经验、四路线能力树 |
| Arc | `arc` | 9.0.0 | 已实现：料理 Action、条件和能力奖励 |
| Kaleidoscope Cookery | `kaleidoscope_cookery` | 1.4.1 | 已实现：炒锅/汤锅/蒸笼出锅、品质与署名 |
| Farmer's Delight | `farmersdelight` | 1.3.2 | 已实现：烹饪锅出锅、recipeId 与署名 |
| Field Guide | `fieldguide` | 1.13.4 | 已实现：166 道料理图鉴与出锅解锁 |
| Bountiful | `bountiful` | 8.0.0-beta.2 | 规划中 |
| Order to Cook | `ordertocook` | 1.3.5 | 规划中 |
| Lightman's Currency | `lightmanscurrency` | 2.3.0.5 | 规划中 |
| Kaleidoscope Compat | `kaleidoscope_compat` | 2.9.7 | 已实现：`#c:tools/knife` 厨刀标签纳入刀工路线（4 种厨刀） |

Implemented integrations (English original):

- **Jobs+**: chef job (`tcth:chef`), dish experience rewards, and the four-route
  ability tree (knife / hearth / tasting / study).
- **Arc**: dish-cooked actions and conditions, plus the ability-tree rewards
  (tasting effects, fire damage multiplier, durability cancel).
- **Kaleidoscope Cookery**: pot/stockpot/steamer take-out detection, quality
  grading and chef signature.
- **Farmer's Delight**: cooking-pot take-out detection, recipeId mapping and
  chef signature.
- **Field Guide**: 166-dish chef cookbook with unlock-on-take-out.
- **Kaleidoscope Compat**: its `#c:tools/knife` tag feeds the knife route.

The table above lists the integration targets and the exact versions verified
against the test server. Features are added one module at a time and are
documented here as they land.

## Installation

1. Install Minecraft 1.21.1 with NeoForge **21.1.247** (or newer compatible
   loader).
2. Copy `tcth-0.2.0.jar` (or the current version) into the `mods/` folder.
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

**Farmer switches (phase 4A.2):** `farmerIntegrationEnabled` controls the
unified crop-harvest event framework (`CropHarvestedEvent` detection and
posting: break detector + right-click harvest mixins). `farmerRewardsEnabled`
controls ONLY the `tcth:on_crop_harvested` Jobs+/Arc farmer rewards and is
independent of `jobsPlusRewardsEnabled` (chef). Both default as follows:
`farmerIntegrationEnabled=true`, `farmerRewardsEnabled=false` (enable only
after live verification).

**Shadow thief switches (phase 8B, framework skeleton):**
`shadowThiefIntegrationEnabled`, `shadowPlayerTheftEnabled` and
`shadowEntityTheftEnabled` all default to **false**. Phase 8B ships an
executable but completely inert framework:
- No `PlayerInteractEvent.EntityInteract` listener, no real
  ITEM/COIN/HEALTH/HUNGER/EFFECT transaction, no Lightman's Currency calls,
  no claims-mod reference and no profession data pack;
- Even with the switches manually flipped to `true`, the empty candidate
  provider, the no-op transfer executor and the deny-all protection
  short-circuit the coordinator early — **no player asset can ever move**;
- The COIN type stays hard-blocked (Lightman's Currency 2.3.0.5 has no atomic
  transfer API; see docs/phase-8a-shadow-thief-authoritative-audit.md §5.2);
- Audit is on by default (`shadowAuditEnabled=true`, written to
  `world/data/tcth_shadow_audit.dat`); audit availability is enforced
  <em>before</em> any candidate/random/asset operation — disabled or
  unavailable audit refuses with `AUDIT_FAILED` and nothing ever moves. The
  transfer is a two-phase transaction (prepare → commit → rollback) and
  `SUCCESS` is only posted after both the commit and the final audit write
  succeed — a failed final audit write triggers exactly one rollback
  (`ROLLED_BACK`), and a failed rollback enters the `RECOVERY_REQUIRED` severe
  state (committed receipt reported for operator recovery), never a fake
  success.
- **Real transfers are gated.** The transaction engine is wired into the
  production coordinator but stays inert until `enabled` +
  `shadowThiefIntegrationEnabled` + `shadowPlayerTheftEnabled` +
  `shadowRealAssetTransfersEnabled` are ALL on (the last one defaults to
  `false`). With the gate off, attempts are refused before any candidate,
  random, audit or executor call and no asset can ever move. The audit log is
  a plain SavedData, <strong>not an fsync WAL</strong> — a crash between the
  pre-write and the final write can leave an unresolved `RECOVERY_REQUIRED`
  window; enabling real transfers on a live server requires operator
  confirmation. No live player acceptance has been performed.

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

### CI workflow template status

`.github/workflows/build.yml` is a **template for the future standalone TCTH
repository**. It downloads the five optional-mod dev jars (Farmer's Delight,
Kaleidoscope Cookery, Arc, Jobs+, Field Guide) from pinned Modrinth CDN URLs and
verifies each against the SHA-256 of the exact server JAR before running
`./gradlew clean build --no-daemon`.

> The current source lives at `mod develop/tcthintegration-template-1.21.1/`
> inside the Minecraft-Server repository. GitHub only executes workflows from
> the repository root, so this **nested workflow is not executed** by the
> current Minecraft-Server repository and **no claim of a passing GitHub
> Actions run is made**. Move it to the root of the standalone TCTH repository
> (and commit the template) before relying on it.

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
