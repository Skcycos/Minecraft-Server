# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### 8C.3.1 — Mixin FATAL fix, pre-commit finalization (BUILD-only, not deployed)

- **Root cause (from 8C.3 first deployment):** the `tcth-0.2.7.jar` built at
  8C.2.6 failed server load with a Sponge Mixin FATAL —
  `ItemStackDurabilityMixin.shouldSkipDurability` was a non-private static
  method, which Sponge Mixin rejects (it would be merged into the target
  class with a name collision).
- **Fix:** the helper is now `private static` and only delegates to a new
  plain-Java `DurabilityAbilityRouter` (hoe-first mutual exclusion, then
  knife, never both rolls). The testable routing logic moved out of the
  mixin; `FarmerTillingRoutingTest` now tests the router, plus reflection
  regressions (helper is private static, the @Inject handler still routes
  through the entry point, the private helper delegates to the router
  behaviourally).
- **8C.3 online acceptance: LOAD PASS only, PLAYER LIVE DEFERRED** (the
  operator skipped the live player part). Server loaded to `Done` with the
  fixed JAR; no asset transfer was verified online and none is claimed.
  See `docs/phase-8c.3-shadow-player-live-report.md`.
- **Deployment state:** `Server/mods/tcth-0.2.7.jar` =
  `2fa2143c…` (current deployment, the ONLY version with a server LOAD PASS);
  `bdbe2aa7…` (8C.2.6 build) is void — it failed server load with the Mixin
  FATAL; `3149987a…` (593,968 B, 8C.3.1 clean-build artifact) is BUILD PASS
  only — not deployed, no runtime verification, no LOAD PASS claimed; shadow
  config fully restored to OFF; server stopped.
- Full `./gradlew clean build`: **tests=1344 failures=0 errors=0 skipped=0**
  (was 1342 at 8C.2.6; net +2 after reworking the routing tests).

### 8C.2.6 — final strict schema boundary fixes (BUILD-only, not deployed, no version bump)

- **dataVersion lower bound.** Both stores now accept only
  `1 <= dataVersion <= DATA_VERSION`; version 0, negative and future versions
  all fail closed persistently (0 previously slipped through as a "valid"
  version).
- **Position matrix.** All three coordinate keys absent → legal (`null`);
  if ANY key is present, all three must exist as TAG_INT — partial presence,
  mixed types and all-wrong types fail closed (8C.2.5 conflated "present but
  mistyped" with "missing").
- **Scope**: transactions, quota, success rates, asset values, config
  defaults and business logic are untouched. 8C.2.5 is supplemented by this
  fix; the 8C.2–8C.2.6 static security finalization stands, and PLAYER LIVE
  PASS is still NOT claimed.
- Full `./gradlew clean build`: **tests=1342 failures=0 errors=0 skipped=0**
  (was 1334 at 8C.2.5).

### 8C.2.5 — strict NBT schema finalization (BUILD-only, not deployed, no version bump)

- **Strict audit root schema.** `dataVersion` must exist as TAG_INT; v1
  requires a `records` TAG_LIST with TAG_COMPOUND elements (empty allowed);
  v2 additionally requires the `failClosed` TAG_BYTE. Missing/mistyped root
  keys fail closed — never a healthy empty store via getInt/getList defaults.
- **Strict record fields.** Every required field (eventId/thief/target UUIDs,
  targetKind/auditState, itemCount/numericAmount/effectDurationTicks/
  timestampEpochMillis/serverTick, dimension) must exist with the exact NBT
  type; position must be all-or-none TAG_INT; optional fields missing → null,
  present with the wrong type → damage. Any violation fails the whole store
  closed instead of substituting 0/empty strings.
- **Versioned daily schema.** v1 needs `victims`; v2 adds `reservations`;
  v3 adds `failClosed` TAG_BYTE; lists must be compound lists; entry fields
  must exist with correct types — a missing `count` never reads as 0.
- **Load-capacity refinement.** When MAX_RECORDS are all critical
  (PENDING/RECOVERY_REQUIRED) and the next input is only a settled FINAL, it
  is safely dropped (store stays healthy, critical records intact); only a
  critical record that cannot be expressed fails closed.
- **Regressions**: missing/mistyped dataVersion, missing/mistyped root
  lists, non-compound elements, missing/mistyped scalars, partial position,
  mistyped optional fields, missing count/reservation state, minimal legal
  v1/v2/v3 schemas, the critical-cap load boundary, failClosed round-trips.
- **Phase statement**: the 8C.2–8C.2.5 static security finalization is
  complete; PLAYER LIVE PASS is still NOT claimed (not deployed, no online
  acceptance; real transfers need the operator to enable the four switches
  and pass online acceptance).
- Full `./gradlew clean build`: **tests=1334 failures=0 errors=0 skipped=0**
  (was 1311 at 8C.2.4).

### 8C.2.4 — persisted store health finalization (BUILD-only, not deployed, no version bump)

- **Persisted audit health.** `ShadowAuditStore` gains a persisted
  `failClosed` flag (data v2, survives save/load): future/negative versions,
  invalid records, conflicting duplicate eventIds and payloads that cannot be
  loaded conservatively all fail the store closed — no audit, no real asset
  transfer. Legacy v1 data migrates without locking.
- **Coordinator health gate.** `ShadowAuditWriter.isHealthy()` is checked in
  the audit gate BEFORE the candidate pool, the date source, any random call
  and the executor: unhealthy → `AUDIT_FAILED "audit_unhealthy"` with every
  asset call at zero. Test fakes default healthy and can be injected as
  unhealthy.
- **Audit capacity keeps critical records.** At `MAX_RECORDS` only the
  oldest settled FINAL may be evicted — PENDING pre-writes and
  RECOVERY_REQUIRED records are never dropped (append and load alike); when
  every record must be kept the append returns `false` and no asset commit
  may happen. Load overflows keep critical records, else fail closed.
- **Strict daily-limit loading.** Invalid UUIDs, invalid dates, negative
  counts, invalid reservation states and missing required fields no longer
  silently skip — they set the persisted `failClosed` flag; byte-identical
  duplicates stay acceptable; v1/v2/v3 data migrates; fail-closed keeps every
  ITEM query/reserve/commit/release conservatively refused.
- **Phase statement**: the 8C.2.3 transaction fixes (single SUCCESS audit
  write, quota-commit hard gate, PENDING→FINAL rules) remain valid; the real
  asset-transfer enablement gate is completed here (8C.2.4) — combined with
  the 8C.2.2 four-switch master gate, production requires an operator to
  enable all four switches explicitly.
- Full `./gradlew clean build`: **tests=1311 failures=0 errors=0 skipped=0**
  (was 1298 at 8C.2.3).

### 8C.2.3 — audit & load finalization (BUILD-only, not deployed, no version bump)

- **SUCCESS audit is written exactly once.** PENDING → FINAL SUCCESS is a
  single `safeAppend`; the settlement now goes through a dedicated
  `finishAfterFinalAudit` that only settles idempotency keys and posts the
  event — no second append, no reliance on coincidentally equal timestamps.
  Failed outcomes keep their single FINAL write via `finishAuditedAttempt`.
- **Test fake matches production audit rules.** `InMemoryAudit` now enforces
  the same state machine as `ShadowAuditStore.append` (PENDING → FINAL with
  preserved identity only; FINAL → byte-identical idempotent; everything else
  refused, never an unconditional overwrite). A new coordinator test runs
  against the REAL `ShadowAuditStore` with a fresh epoch value per call:
  exactly PENDING + FINAL appends, exactly one FINAL SUCCESS record, correct
  eventId/receipt/theftType/quota.
- **Conservative merge loading + persisted fail-closed flag.** Duplicate
  victim entries merge (max per day, never last-write-wins); a duplicate
  eventId with conflicting identity/state marks the store damaged instead of
  picking the last entry; the reservation back-fill never exceeds
  `MAX_VICTIMS`; anything not expressible within capacity (or a future/
  illegal data version) sets a persisted `failClosed` flag (data v3) that
  makes `isAtItemLimit` true, `tryReserve` REJECTED and commit/release false —
  no silent drops that reopen quotas. The flag survives save/load; v1/v2 data
  still migrates.
- **Corrupted-NBT regressions**: same-victim 10→1 stays 10, same-day 10→1
  stays 10, conflicting duplicate eventId fails closed globally, 1024+
  reservation victims stay bounded and fail closed, future version fails
  closed, legacy v1/v2 still works.
- **Docs note**: `playerTheftEnabled` is documented as the player-target
  gate; 8D (entity targets) would restructure the master gate to branch by
  `targetKind` — not implemented.
- Full `./gradlew clean build`: **tests=1298 failures=0 errors=0 skipped=0**
  (was 1290 at 8C.2.2).

### 8C.2.2 — daily quota transaction finalization (BUILD-only, not deployed, no version bump)

- **Combined four-switch master gate.** An attempt now needs
  `Config.ENABLED` && `shadowThiefIntegrationEnabled` &&
  `shadowPlayerTheftEnabled` && `shadowRealAssetTransfersEnabled` — every
  config read fails closed to FALSE (`masterEnabled` projection added to
  `ShadowFrameworkSettings`), so any read failure refuses the attempt before
  the pool, any random call, the PENDING audit and the executor.
- **UTC day captured after every gate/context check.** A gated-off attempt
  never touches the date supplier (zero calls); a failing date source prunes
  ITEM only and no ITEM asset can move.
- **Attempt-local quota state.** The per-attempt daily store, `utcDay` and
  reservation eventId are locals passed explicitly to the commit/release
  helpers (instance fields removed) — one coordinator safely runs consecutive
  attempts, and a later clean failure never releases an earlier SUCCESS quota.
- **Reservation index semantics.** A full index may only drop settled
  COMMITTED entries (occupied aggregates untouched — no victim quota
  reopens); an all-RESERVED index rejects; eventId reuse with a different
  victim/day rejects; load cross-validates reservations against aggregates so
  corrupted NBT never shrinks an occupied count.
- **Quota commit is a hard gate before SUCCESS.** New order: PENDING →
  reserve → asset commit → receipt validate → `commitReservation` → final
  audit → settle. A false/throwing quota commit never continues to SUCCESS:
  rollback once → release + ROLLED_BACK, or RECOVERY_REQUIRED keeping the
  quota. A successful rollback after a failed final audit also releases the
  committed quota.
- `defaults()` Javadoc, step numbering and the stale best-effort comments
  refreshed; feedback/DUPLICATE-silence/command-tree fixes untouched, no
  asset values changed.
- Full `./gradlew clean build`: **tests=1290 failures=0 errors=0 skipped=0**
  (was 1278 at 8C.2.1).

### 8C.2.1 — reservation protocol for the daily ITEM cap (BUILD-only, not deployed, no version bump)

- **Pre-commit reservation protocol for the daily ITEM quota.** The daily
  cap no longer counts successes afterwards — the coordinator now reserves
  a quota slot BEFORE the asset commit (`tryReserve`), occupies it
  immediately (`RESERVED`), commits it on SUCCESS (`COMMITTED`) and releases
  it on clean failures / successful rollbacks. `RECOVERY_REQUIRED` keeps the
  reservation (the assets may have moved; the quota stays occupied
  conservatively). EventId-idempotent: retrying the same attempt never
  double-counts; bounded by 4096 outstanding reservations with deterministic
  oldest-first eviction; invalid dates / null inputs / storage-full /
  exceptions all fail closed and refuse the ITEM transfer.
- **Audit config read failures now fail closed to FALSE** (was `true`);
  the config option itself still defaults to `true` for operators.
- **Feedback branches strictly by the drawn theft type** with
  `Locale.ROOT` number formatting; a `DUPLICATE` outcome cancels the
  interaction but is silent; the nearby-exposure notice excludes the victim
  (they already received the direct fail message).
- **Command tree fix.** The debug toggles move to
  `/tcth debug shadow on|off|status` (permission ≥3, consistent with the
  other debug subsystems); `/tcth shadow audit recent|player` is unchanged.
- Full `./gradlew clean build`: **tests=1278 failures=0 errors=0 skipped=0**
  (was 1274 at 8C.2).

### 8C.2 — controlled production wiring for player shadow theft (BUILD-only, not deployed, no version bump)

- **Independent real-transfer master gate.** New `shadowRealAssetTransfersEnabled`
  (default `false`); attempts only proceed when `enabled` +
  `shadowThiefIntegrationEnabled` + `shadowPlayerTheftEnabled` +
  `shadowRealAssetTransfersEnabled` are all on. Config read failures fail
  closed. With the gate off the attempt is refused BEFORE the candidate pool,
  any random call, the PENDING audit and the executor — provider/prepare/
  commit all see zero calls, no failure exposure, no audit records, and
  assets absolutely never change.
- **The engine is wired into `defaults()` but stays inert.** The production
  coordinator now uses `PlayerAssetTransferExecutor` and the daily-limit
  store, locked behind the gate; the 8B-era "empty provider / deny-all
  protection" config descriptions were refreshed to the current state.
- **Interaction consume + feedback.** `PlayerInteractHandler` now consumes the
  coordinator result: gate-off/invalid outcomes never cancel the original
  interaction and give no feedback; every attempt-stage outcome cancels the
  `EntityInteract` exactly once with exactly one `Component.translatable`
  feedback per event. SUCCESS shows the thief the gains and the victim the
  losses without the thief's identity; FAILED_ROLL exposes the thief's name,
  gives the loser a short glow + slowness and notifies nearby players;
  NO_CANDIDATE only says "nothing to steal"; technical outcomes never leak
  stack traces or internal reasons.
- **Daily item-loss cap.** `shadowDailyItemLossLimit` (default 3) is counted
  per victim UUID + UTC date in a new bounded, defensively-loaded SavedData
  (`tcth_shadow_daily_limits.dat`, injectable UTC date source, restart-safe);
  the check runs before the random draw and prepare; at the cap only ITEM
  leaves the pool (HEALTH/HUNGER/EFFECT unaffected, weights renormalise, one
  draw). COIN stays closed.
- **Read-only audit commands.** `/tcth shadow audit recent [limit]` (≥3) and
  `/tcth shadow audit player <player> [limit]` (≥3; ordinary players may only
  query themselves); limits are strictly bounded (1..100), output carries
  eventId/time/both sides/type/outcome/item-or-amount/dimension/position; no
  reset/delete commands exist.
- **Honest documentation.** SavedData is explicitly NOT an fsync WAL — the
  pre-write/final-write crash window remains (`RECOVERY_REQUIRED`); enabling
  real transfers on a live server requires operator confirmation; no
  PLAYER LIVE PASS is claimed.
- The 8C.1.3 numbers are superseded: **140 suites / 1274 tests / 0 failures**.
  TRANSACTION ENGINE BUILD PASS / PRODUCTION WIRING DISABLED / REAL PLAYER
  ENTRY STILL NO-OP / SERVER NOT STARTED / PLAYER LIVE NOT TESTED. Not
  deployed, no commit/push.

### 8C.1.3 — commit truthfulness & candidate-pool consistency closure (BUILD-only, no version bump)

- **ITEM single-item sources.** A source stack of exactly 1 now legally
  leaves `ItemStack.EMPTY` behind; the state classifier recognises
  VICTIM_REMOVED and COMMITTED for single-item stacks (normal commit, internal
  restore and external rollback all covered).
- **ITEM post-commit verification.** After writing the receiver slot both
  slots are re-read: COMMITTED requires the source to have lost exactly 1 and
  the receiver to have gained exactly 1 with identical components (a shared
  predicate used by the classifier and the verification). A no-op, clamped or
  wrong write triggers an internal restore — restorable → FAILED_CLEAN,
  unrestorable → RECOVERY_REQUIRED.
- **HUNGER four-write state machine.** The commit has four independent writes
  (victim food, victim saturation, thief food, thief saturation); the state
  machine enumerates every real intermediate stage
  (PRE → VICTIM_FOOD_REDUCED → VICTIM_SAT_REDUCED → THIEF_FOOD_RAISED →
  COMMITTED). Any setter exception, no-op or partial write is classified and
  restored; COMMITTED requires ALL FOUR post values to equal the plan —
  saturation legality alone never proves the transfer; a clamped value that is
  neither pre nor reduced is FOREIGN and cannot be safely rolled back
  (RECOVERY_REQUIRED, never a fabricated clean failure). External rollback
  still accepts only the full COMMITTED state.
- **PRE restore shortcut.** When the classification is already PRE, the
  internal restore returns `true` without re-writing any asset (no health
  events, no effect writes, no inventory writes).
- **Candidate pool / prepare share one feasibility source.** New
  `ShadowFeasibility` holds the read-only rules exactly once:
  `effectIsCandidateFor` (stealable AND the thief does not already hold the
  effect) and `computeHungerPlan` (full food/saturation conservation within
  the 1-point budget). Both the probe and `prepare` use them — a candidate in
  the pool implies a non-null plan without state drift; infeasible types
  never enter the pool and never consume a type draw.
- Production `defaults()` stays `NoopShadowTransferExecutor`; no deploy, no
  server start, no smoke/online tests, no profession experience, no ability
  tree, COIN still BLOCKED. The 8C.1.2 numbers are superseded:
  **138 suites / 1250 tests / 0 failures**. TRANSACTION ENGINE BUILD PASS /
  PRODUCTION WIRING DISABLED / REAL PLAYER ENTRY STILL NO-OP / SERVER NOT
  STARTED / PLAYER LIVE NOT TESTED. No commit/push.

### 8C.1.2 — rollback truthfulness & phase-state closure (BUILD-only, no version bump)

- **Internal vs external rollback split.** Every asset type now classifies the
  world state into explicitly enumerated states (PRE / intermediate /
  COMMITTED / FOREIGN — complete conjunctions of all relevant fields, never
  loose per-field ORs). Internal rollback (commit exceptions, mismatches,
  unregistered items) accepts the enumerated intermediate states; the
  coordinator's external rollback accepts ONLY the exact committed post-state
  and refuses intermediate states.
- **EFFECT phase states.** Commit honours the `removeEffect` return value (a
  cancelled removal aborts the transfer — the thief never receives the
  effect); PRE / VICTIM_REMOVED / VICTIM_REMAINDER_WRITTEN / COMMITTED are
  recognised with full field comparison (duration / amplifier / ambient /
  visible / showIcon); every restore re-reads both sides field by field, so a
  cancelled removal, a rejected/invalid `forceAddEffect` or a no-op write
  makes the restore return `false` — restorable states end in
  FAILED_CLEAN/ROLLED_BACK, only unrestorable ones in RECOVERY_REQUIRED.
- **Write-back verification for ITEM/HEALTH/HUNGER.** Every restore re-reads
  and verifies the full snapshots after writing; `setItem`/`setHealth`/
  `setFoodLevel`/`setSaturation` mocked as no-op, clamped or partially
  written now make the restore return `false` instead of fabricating success.
- **Regression coverage (10 new tests):** cancelled buff removal at commit,
  cancelled thief removal during restore → rollback=false, rejected
  forceAddEffect restore → rollback=false, exact restores from every EFFECT
  intermediate stage, external lookalike effects (same duration, different
  amplifier/flags) never overwritten, no-op write-backs on all four asset
  types never report true, external rollback refuses intermediate states,
  full post-state external rollback succeeds.
- Production `defaults()` stays `NoopShadowTransferExecutor`; no deploy, no
  server start, no smoke/online tests, no profession experience, no ability
  tree, COIN still BLOCKED. The 8C.1.1 numbers are superseded:
  **138 suites / 1237 tests / 0 failures**. TRANSACTION ENGINE BUILD PASS /
  PRODUCTION WIRING DISABLED / REAL PLAYER ENTRY STILL NO-OP / SERVER NOT
  STARTED / PLAYER LIVE NOT TESTED. No commit/push.

### 8C.1.1 — shadow theft asset-conservation fixes (BUILD-only, no version bump)

- **ITEM internal restores.** Every internal restore now distinguishes
  FAILED_CLEAN (restore succeeded, both slots exactly restored) from
  RECOVERY_REQUIRED (restore failed); the unregistered-item path gained an
  injectable `itemIdResolver` seam with real behavior tests for both
  outcomes.
- **Strict snapshot comparison.** HEALTH / HUNGER / EFFECT commits compare the
  world state strictly against the prepare snapshots before any mutation —
  any change on either side fails clean and is never "recomputed and
  continued".
- **HEALTH heal verification.** The engine measures the ACTUAL heal delta
  (a `LivingHealEvent` listener may modify or cancel the heal) and only ever
  deducts the identical amount from the victim; a zero/partial/unequal delta
  or a post-state mismatch triggers a full rollback (zero-heal and
  partial-heal tests prove "never deduct 1.0 for a 0.5 gain").
- **HUNGER saturation legality.** `0 <= saturation <= foodLevel` is always
  preserved: the feasible saturation range is computed from both sides'
  post-transfer food levels; if conservation is infeasible within the
  1-point budget no plan is produced. High-saturation, near-full and
  saturation-drift tests added.
- **EFFECT safest strategy.** An effect the thief already holds is never a
  candidate; commit strictly re-checks duration / amplifier / ambient /
  visible / showIcon and verifies the real post-state of both sides after
  `forceAddEffect` — the victim must lose exactly what the thief gains
  (never "victim -200, thief +100"), otherwise the snapshots are restored.
- **Protocol hardening.** The coordinator verifies `plan.type()` against the
  drawn type right after prepare (no roll, no commit, no assets on
  mismatch); commit/rollback reject mismatches; `ShadowTransferResult` now
  enforces a non-null receipt for COMMITTED and RECOVERY_REQUIRED; rollback
  only writes back old snapshots when the current state is still one this
  transaction could have produced (external changes are never overwritten).
- Production `defaults()` stays `NoopShadowTransferExecutor` — the engine is
  never wired; no real interaction wiring, no profession experience, no
  ability tree, COIN still BLOCKED.
- The 8C.1 numbers are superseded: **138 suites / 1227 tests / 0 failures**.
  TRANSACTION ENGINE BUILD PASS / PRODUCTION WIRING DISABLED / REAL PLAYER
  ENTRY STILL NO-OP / SERVER NOT STARTED / PLAYER LIVE NOT TESTED. Not
  deployed, no commit/push.

### 8C.1 — player asset transaction engine (BUILD-only, not enabled, no version bump)

- **Two-phase transaction protocol.** `ShadowTransferResult` now
  distinguishes COMMITTED / FAILED_CLEAN / RECOVERY_REQUIRED; plans are
  immutable pure data (UUIDs, slots, values, defensive snapshots — never
  Player/Inventory references); `prepare` may perform exactly one
  concrete-asset selection call (ITEM stack / EFFECT) and never re-draws the
  theft type; any commit exception after a partial change triggers an
  internal rollback — a failed internal rollback is never reported as a
  plain failure. The final-audit-failure rollback still runs exactly once.
- **Coordinator order**: type draw once → prepare → success chance (incl. the
  plan's high-value modifier) → success roll once → PENDING → pre-commit
  re-validation (protection / target / distance / world drift fail closed) →
  commit → FINAL.
- **Real engine (`PlayerAssetTransferExecutor`)**:
  - ITEM — main inventory 0..35 only, uniform server-side selection among
    stealable-and-receivable stacks, transfers exactly 1 with full
    components, high-value tag applies -0.10, drift → FAILED_CLEAN, rollback
    restores both slots exactly;
  - HEALTH — base 1 point, victim floor 2, only the actually deducted amount
    is healed (no over-heal), exact rollback;
  - HUNGER — base 2 food points + a small saturation transfer, both bounded,
    receipt records the actual food points, exact rollback of both sides'
    food/saturation;
  - EFFECT — whitelist/blacklist/beneficial/finite/non-ambient, base max 200
    ticks, amplifier never raised, stronger-or-equally-long thief effects are
    not selectable, exact snapshot rollback of both sides;
  - COIN is always refused.
- **Production wiring stays disabled.** `ShadowAttemptCoordinator.defaults()`
  keeps `NoopShadowTransferExecutor`; tests prove that with every switch
  forced on, attempts can only yield protected/candidate/failure results with
  zero asset changes.
- Test baseline supersedes 8C.0.1: **138 suites / 1215 tests / 0 failures**.
  TRANSACTION ENGINE BUILD PASS / PRODUCTION WIRING DISABLED / REAL PLAYER
  ENTRY STILL NO-OP / SERVER NOT STARTED / PLAYER LIVE NOT TESTED. Not
  deployed, no commit/push.

### 8C.0.1 — shadow thief structural fixes (BUILD-only, no version bump)

- **Real line of sight.** `PlayerInteractHandler` now writes the server-side
  ray-cast `thief.hasLineOfSight(victim)` into the attempt context: `true`
  enables the normal behind/watched vector facts, an API failure fails closed
  to `false`. Tests cover both paths, the exception path and the mutual
  exclusivity of the facts.
- **Per-stack ITEM capacity.** The read-only ITEM probe now checks capacity
  (free slot or mergeable stack) individually for EVERY stealable stack of
  the victim's main inventory 0..35 — ITEM is available when at least one
  stack can really be received (regression test: first stack unreceivable,
  second mergeable). Still read-only; no slot is ever chosen or modified.
- **Nested CI template fixed.** `.github/workflows/build.yml` now downloads
  and SHA-verifies `brewinandchewin-4.5.0.jar` and
  `openpartiesandclaims-0.29.3.jar` from pinned Modrinth CDN URLs (no
  "latest"), against the exact server-JAR hashes. Verified end-to-end: the
  temp `dev-mods/` was cleared and rebuilt from the workflow's download block
  (11 jars, all `sha256sum -c` OK) before a clean build.
- **`high_value_stealable_items` tag** confirmed present with empty values
  (consistent with `ShadowTags` and the stage-8A design; no unaudited items).
- Test baseline supersedes 8C.0: **137 suites / 1181 tests / 0 failures**.
  INTERACTION STRUCTURAL PASS / PROTECTION STRUCTURAL PASS / REAL ASSET
  TRANSFER NOT IMPLEMENTED / SERVER NOT STARTED / PLAYER LIVE NOT TESTED.
  Not deployed, no commit/push.

### 8C.0 — shadow thief interaction entry, protection layer & read-only probing (no version bump)

- **Player interaction entry.** `PlayerInteractHandler` listens to
  `PlayerInteractEvent.EntityInteract` at LOW priority (OPAC's own handler
  runs first and cancels protected interactions, which we skip): server-side
  only, real ServerPlayer, MAIN_HAND, sneaking, both hands empty, other
  player target (alive, same dimension, in range, not FakePlayer, not
  cancelled). Each event invokes the coordinator at most once; duplicate
  packets in the same tick are deduplicated by the attempt key. No Mixin;
  ordinary right-clicks are never disturbed; the master switch stays OFF by
  default so the listener is inert.
- **Composite protection service.** `ShadowCompositeProtectionService`
  combines self/FakePlayer/gamemode checks, new-player protection (verified
  `Stats.PLAY_TIME` below `shadowNewPlayerProtectionTicks`, default 1 h),
  vanilla spawn-protection radius (`MinecraftServer.isUnderSpawnProtection`)
  and a pluggable area provider. The Open Parties and Claims provider uses
  OPAC's actual entity-interaction query (javap-verified against
  `openpartiesandclaims-0.29.3`), loaded through string isolation
  (`ModList` + `Class.forName`; no mods.toml declaration, no bundled class);
  OPAC absent / API exception / unknown state all deny. No main-city/shop
  protection is claimed (no reliable source).
- **Read-only candidate probing.** `PlayerReadonlyCandidateProvider`
  reports ITEM (main inventory 0..35 only, unstealable tag + container
  components excluded, thief capacity checked), HEALTH (target above floor,
  thief not full), HUNGER (same), EFFECT (whitelist `#tcth:stealable_effects`
  minus blacklist, beneficial, finite, non-ambient) — and never COIN. No
  state is modified and no slot/effect/balance information leaves the server.
- **Production wiring stays triple fail-closed.** `defaults()` now wires the
  read-only provider and the composite protection, but keeps the
  `NoopShadowTransferExecutor` — with every switch forced on, attempts can
  only yield protected / candidate / failure results; assets never change.
- **Audit identity hardening.** A PENDING → FINAL transition must keep
  thief, target, targetKind, targetType, theftType, dimension, position and
  serverTick identical; swaps are refused and the original record preserved.
- **`/tcth debug shadow on|off|status`** (permission ≥3, default off) logs
  only eventId, outcome, candidate types, protection result and reason —
  never inventory contents, effect details or balances.
- Test baseline supersedes 8B.1.1: **137 suites / 1175 tests / 0 failures**.
  INTERACTION STRUCTURAL PASS / PROTECTION STRUCTURAL PASS / REAL ASSET
  TRANSFER NOT IMPLEMENTED / SERVER NOT STARTED / PLAYER LIVE NOT TESTED.
  Not deployed, no commit/push.

### 8B.1.1 — shadow thief transaction & audit finalisation (BUILD-only, no version bump)

- **Committed-but-mismatched receipts now roll back.** If the commit returns a
  receipt that does not match the drawn theft type, the coordinator runs
  rollback exactly once: success → `ROLLED_BACK`, failure/exception →
  `RECOVERY_REQUIRED` (reason `rollback_failed; receipt_type_mismatch`, empty
  event receipt, audit record marked with the ambiguous asset state). A plain
  `TRANSFER_FAILED` (which would claim nothing moved) is never reported after
  a commit. Rollback counts, audit records and event receipts are covered by
  new tests.
- **Audit asset rules are now outcome-keyed.** Only the asset-carrying
  outcomes (SUCCESS always, RECOVERY_REQUIRED when assets are present) force
  the item/numeric/effect fields to match the theft type; PENDING and every
  non-asset outcome (FAILED_ROLL, TRANSFER_FAILED, ROLLED_BACK, …) must carry
  no assets — a theft type of EFFECT with `effectId == null` is legal there.
  Covered by an EFFECT full state-machine test set (failed roll, prepare
  failure, PENDING, commit failure, success).
- **ATTEMPT keys now really contain the serverTick** (thief + target +
  serverTick): two different eventIds in the same tick are duplicates, while
  the same pair one tick later is not blocked by the idempotency key. All
  coordinator call sites and tests updated.
- **PENDING recovery alert posts once per JVM session.** The in-memory
  idempotency check now runs first; the first PENDING sighting posts
  `RECOVERY_REQUIRED` and settles the keys, so repeats are `DUPLICATE` with
  zero new events and zero new audit records (DUPLICATE no longer posts
  events). A restart alerts once more from the durable PENDING record.
- **Audit store state transitions are restricted.** New eventIds accept
  PENDING or FINAL; an existing PENDING may only become a FINAL record with
  an outcome; an existing FINAL only accepts a byte-identical idempotent
  re-write; every other transition returns `false` and leaves the stored
  record untouched.
- **Side fixes.** A null `auditStoreFactory` result fails closed as
  `AUDIT_FAILED` before any provider/random/executor call; the PLAYER/ENTITY ↔
  targetType invariant is enforced in the coordinator's early context
  validation instead of surfacing through later exceptions.
- **Still inert and safe:** no interaction listener, no real asset transfer,
  COIN stays BLOCKED, production defaults stay triple fail-closed. The 8B.1
  numbers are superseded: **130 suites / 1104 tests / 0 failures**. Not
  deployed, not live-tested, no server started, no commit/push.

### 8B.1 — shadow thief framework hardening (review fixes, no version bump)

- **Audit-first ordering + two-phase transfer.** The transfer interface became
  `prepare` (read-only planning) → `commit` (atomic transfer) → `rollback`
  (restore). The audit availability gate (disabled or unavailable) now refuses
  the attempt <em>before</em> any provider / random / executor call, and a
  PENDING pre-write audit record must exist before the commit can run. A
  failed final audit write triggers exactly one rollback → `ROLLED_BACK`; a
  failed rollback → `RECOVERY_REQUIRED` (committed receipt reported, never a
  fake SUCCESS/TRANSFER_FAILED). `ShadowTheftOutcome` gained
  `ROLLED_BACK` / `RECOVERY_REQUIRED`; `PENDING` is an audit-internal state;
  `AUDIT_FAILED` is now the pre-asset refusal (no receipt, no event).
- **Idempotency hardening.** The coordinator checks the eventId (durably via
  the audit store, then in-memory) and the thief+target+tick attempt key
  before any work; duplicates run zero provider/random/executor calls and add
  no audit records. Every audited outcome settles the idempotency keys. An
  unresolved PENDING record blocks its eventId with `RECOVERY_REQUIRED`.
- **Independent caches.** The new `ShadowIdempotencyTracker` (eventId / attempt
  keys, TTL-bounded) is fully separate from the gameplay
  `ShadowCooldownTracker` (cooldowns / alert / victim protection) — an
  eventId flood can never evict safety records; each cache has its own
  capacity, expiry, logout and stop cleanup tests.
- **Audit schema v1 (direct fix, never deployed).** Records now carry
  `targetType` (ENTITY required), `effectId` (EFFECT required),
  `timestampEpochMillis` (injectable clock) and `serverTick` (separate),
  `failureReason` (≤256 chars, control-character-free) and an
  `auditState` (PENDING/FINAL). Unknown present enums drop the record; missing
  nullable fields stay null; cross-field invariants are constructor-enforced.
- **SavedData cap fix.** On load the newest 10 000 <em>valid</em> records are
  kept (invalid records never count); append is an eventId upsert so the
  PENDING → final transition never duplicates the log; order stays
  chronological; identifiable-id tests prove oldest-drop/newest-keep.
- **Public API invariants.** `ShadowTheftEvent` now enforces outcome/receipt/
  theftType consistency (SUCCESS requires a matching receipt; only
  RECOVERY_REQUIRED may carry a committed receipt; everything else is empty).
- **Line of sight.** `ShadowAttemptContext` gained `hasLineOfSight`;
  watched/behind now require a proven unobstructed view and fail closed
  (no bonus, no penalty) otherwise. Positions are defensively copied to
  immutable `BlockPos` everywhere.
- **Dispatcher/audit policy.** `publish(null)` is rejected; listener-exception
  logs are 60-second throttled; PROTECTED/COOLDOWN/NO_CANDIDATE/FAILED_ROLL/
  TRANSFER_FAILED/ROLLED_BACK/RECOVERY_REQUIRED/SUCCESS all write bounded
  audit records; duplicates never do; framework-level refusals never do.
- **Still inert and safe:** no interaction listener, no real asset transfer,
  COIN stays BLOCKED, production defaults stay triple fail-closed (empty
  provider / no-op executor / deny-all protection). Test baseline moved from
  129 suites / 1058 tests to **130 suites / 1089 tests / 0 failures**. Not
  deployed, not live-tested, no server started. Crash-consistency limits of
  the non-fsync SavedData log are documented (see
  docs/phase-8b-shadow-theft-framework-report.md §8B.1-12).

### 8B — shadow thief framework skeleton (phase 8B, no version bump)

- **Framework only.** New public API `com.tanrunn.tcth.api.shadow`
  (`ShadowTheftType`, `ShadowTargetKind`, `ShadowTheftOutcome`, `ShadowTheftReceipt`,
  `ShadowTheftEvent`) and the internal `com.tanrunn.tcth.impl.shadow` package:
  candidate pool with the stage-8A weights (30/20/20/15/15), success-chance
  calculator (base 0.35, ±0.25/0.20 modifiers, clamp 0.05..0.85, single
  `<` roll), vector orientation math (45°/135° single-source thresholds),
  attempt coordinator (17-step state machine), protection service (fail-closed,
  production default denies everything), bounded tick-based cooldown/alert/
  idempotency tracker, the `tcth_shadow_audit` SavedData (overworld-bound,
  defensive load, 10 000-record cap) and the `ShadowTheftEvent` dispatcher.
- **Default OFF and inert by construction.** `shadowThiefIntegrationEnabled`,
  `shadowPlayerTheftEnabled`, `shadowEntityTheftEnabled` all default to
  `false`; even if flipped on, the production defaults (empty candidate
  provider, no-op transfer executor, deny-all protection) make real theft
  impossible. Config reads are fail-closed with injectable suppliers.
- **No real player property is ever moved**: no `PlayerInteractEvent.EntityInteract`
  listener, no Inventory/health/FoodData/MobEffect mutation, no Lightman's
  Currency call, no Open Parties and Claims reference, no Jobs+/Arc reward and
  no shadow_thief job preset in this phase (boundary-guard tests enforce all
  of these). COIN remains **BLOCKED** — Lightman's Currency offers no atomic
  transfer API, so the type is pruned from every pool.
- Audit semantics: `SUCCESS` is only posted after the transfer <em>and</em>
  the audit write succeed; an audit failure yields `AUDIT_FAILED` and never a
  fake success (future WAL/pre-write audit is planned).
- Test baseline moved from 117 suites / 934 tests to **129 suites / 1058 tests
  / 0 failures**. Not deployed, not live-tested, no server started.

### 4C.1 — durability-mixin hardening (throttle + exclusive routing)

- Chef knife-route failures now warn at most once per 60 s
  (`ChefAbilityModule` gained the farmer-style throttled warn; the path runs
  inside the per-durability mixin, so an unthrottled persistent failure would
  spam the log on every tool use); repeated-failure fail-closed behaviour is
  covered by a 100-call test plus a 60 s constant assertion.
- Durability routing is now mutually exclusive (`shouldSkipDurability`):
  a stack is classified once — hoe → farmer route only and the decision ends
  there, otherwise knife → chef route only. An item in BOTH
  `#minecraft:hoes` and `#c:tools/knife` can never roll both probabilities;
  each route reads its combined gate once and rolls its random once.
  Covered by routing tests (double-tag item, knife-only, neither-tag).
- Stale docs corrected: `FarmerAbilityModule` javadoc and
  `JobsPlusCompatModule` comments now describe the Java-driven tilling design
  (the `arc:on_hurt_item` data design is marked as abandoned in 4C), and the
  4B changelog entry no longer claims Arc-driven tilling.
- `tcth:hoe_durability_enabled` / `tcth:knife_durability_enabled` condition
  types are kept registered for data compatibility and marked `@Deprecated`
  (no datapack action references them since 4C).

### 4C — farmer ability tree deployment (0.2.7)

- Version bump 0.2.6 → 0.2.7; deployed together with the tcth-farmer
  datapack powerups (12 nodes / 15 arc actions) for the 4B farmer ability
  tree. Online acceptance performed on a single server start (see
  docs/phase-4c-farmer-abilities-online-report.md).
- **Root cause found during acceptance: `arc:on_hurt_item` never fires on
  NeoForge 21.1.247.** NeoForge's ItemStack patch moved the real durability
  logic into `hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)` (a
  NeoForge-added overload that the LivingEntity+EquipmentSlot entry point
  delegates to), while Arc 9.0.0 injects the thin ServerPlayer wrapper which
  hoes, mining and knives never call. Both the farmer tilling route and the
  chef knife route (same data-driven mechanism) were silently ineffective.
- **Fix: Java-driven durability mixin.** New `ItemStackDurabilityMixin`
  injects the real LivingEntity overload at HEAD (cancellable) and skips the
  durability loss with the audited probabilities — tilling 10% / 20% / 35%
  on `#minecraft:hoes`, knife 10% / 20% / 35% on `#c:tools/knife`, highest
  active tier only. Loaded via `tcth_farmer_abilities.mixins.json`
  (requiredMods: jobsplus). The tilling/knife arc data files were removed
  (Java-driven, like the harvest route); the enabled-condition types stay
  registered.
- Tilling/knife probabilities, tag gating, chance windows, gates and
  fail-closed behaviour covered by unit tests; mixin contract tests assert
  the config registration and the LivingEntity target.
- Online acceptance passed: GUI tree, tilling 10%/26%/34% (50 hoe strokes
  each), harvest I-III with 10s cooldown and immature negative, livestock
  I-III across breed/tame/shear with 20s cooldown, study ×1.15/×1.35/×1.60
  no-stacking (temporary fixed-XP action, restored afterwards), and the
  fixed knife route 10%/20%/~25% (40 knife uses each).

### 4B — farmer ability tree (tilling / harvest / livestock / study)

- Added the four-route Farmer ability tree with 12 powerup nodes.
  - Tilling (耕作, 5/20/45): using a `#minecraft:hoes` tool skips durability
    loss with 10% / 20% / 35% chance (Java-driven via
    `ItemStackDurabilityMixin`; the original `arc:on_hurt_item` data design
    was abandoned in 4C — Arc 9.0.0 injects the unused NeoForge ServerPlayer
    wrapper overload, see docs/phase-4c-farmer-abilities-online-report.md;
    never repairs or copies tools).
  - Harvest (丰收, 10/30/60): a real successful `CropHarvestedEvent` grants
    Haste I 5 s (I), Haste I + Speed I 8 s (II), Haste I + Speed I 12 s (III);
    higher tier overwrites, never stacks; automated / fake-player / immature
    harvests never trigger; shared 10 s cooldown committed only on success.
  - Livestock (畜牧, 15/35/55): successful breeding, taming or shearing
    grants Regeneration I 5 s (I), + Resistance I 8 s (II), + Speed I 15 s
    (III); shared 20 s cooldown; failed ops / non-player actors / mechanical
    paths never trigger.
  - Study (研修, 25/50/75): `tcth:farmer` job experience ×1.15 / ×1.35 /
    ×1.60 via `jobsplus:on_job_exp` + `job_exp_multiplier`; only the highest
    active tier applies (no stacking).
- Added `farmerAbilitiesEnabled` master switch plus four per-route switches
  and two cooldown lengths (harvest 200 ticks / livestock 400 ticks); all
  config reads fail closed (never flipped by inverted conditions) with 60 s
  warn throttling.
- Added Arc condition/reward registrations (`hoe_durability_enabled`,
  `farmer_study_abilities_enabled`, `farmer_livestock_abilities_enabled`,
  `farmer_livestock_cooldown`, `farmer_livestock_effects`) plus the shared
  Java-driven harvest handler with per-player in-memory cooldowns cleared on
  logout/stop (never written to playerdata).
- Added zh_cn / en_us names and descriptions for all 12 powerups plus the new
  config/condition/reward keys.
- No extra crops, animal products, gold or second XP pipeline; study applies
  only to the existing jobsplus job_exp settlement.
- Audit note: `#c:tools/hoes` does not exist on the server (only
  `#minecraft:hoes`, extended by mods); tilling targets `#minecraft:hoes`.

### 7F — J-key job GUI fix (Arc condition network serialization)

- Fixed client-side job GUI crash (`ResourceLocationException` on
  `arc:clientbound_update_actions`): `BrewerRewardsEnabledCondition` and
  `BeverageTierCondition` serializers omitted the `inverted` boolean that
  Arc's network reader always expects, shifting every condition's byte
  stream by 1 byte whenever brew_common/brew_t2 actions were loaded
  (intermittent historical issue, now stable-reproduced under version
  isolation + full 186-action datapack).
- Both serializers now call `IConditionSerializer.super.toNetwork` before
  writing their own data, restoring strict read/write symmetry for all 14
  TCTH conditions; verified via javap on the deployed JAR.
- Version bump 0.2.5 → 0.2.6.
- Brewer live acceptance (phase 7F) PASSED on 0.2.6: Keg COMMON/T2 single
  settlement, `/tcth brewer stats`, Field Guide unlock (give/pickup/drink
  never unlocks, Keg delivery does), brewing/tasting/resistance/study tier
  I–III effects with highest-tier-only stacking, tasting 20s cooldown,
  resistance negative cases; see docs/phase-7f-brewer-online-report.md.
- Version history note (7F.1): 0.2.4 (7D.1/7E deployment build, not
  committed) → 0.2.5 (7D.1/7E official commit 2914dc13) → 0.2.6 (7F fix).
  7F.1 clean build: 110 suites / 867 tests / 0 failures;
  JAR SHA-256 `6bcbf7c15a6aa78827c4fc5366a7a8381d284321152cf45f0909a1a2879cee9d`
  (415,740 B), identical to the deployed Server/mods copy.

### 7E — brewer ability tree (brewing / tasting / resistance / study)

- Added the four-route Mystic Brewer ability tree with 12 powerup nodes.
  - Brewing (调饮, 5/20/45): preparing a graded beverage grants Speed I 5 s
    (I), Speed I + Luck I 8 s (II), Speed I + Luck I 12 s (III); higher tier
    overwrites, never stacks.
  - Tasting (品鉴, 15/35/55): drinking `#tcth:brewer_drinks` grants
    Regeneration I 5 s (I), + Resistance I 8 s (II), + Speed I 15 s (III),
    sharing a 20 s cooldown committed only on success.
  - Resistance (魔酿耐受, 10/30/60): magical / indirect-magical / wither
    damage taken reduced 10% / 20% / 35% (never full immunity; fire, fall,
    melee and projectile damage unaffected).
  - Study (研修, 25/50/75): `tcth:brewer` job experience ×1.15 / ×1.35 / ×1.60
    via `jobsplus:on_job_exp` + `job_exp_multiplier`; only the highest active
    tier applies (no stacking).
- Added `brewerAbilitiesEnabled` master switch plus four per-route switches;
  all config reads fail closed (never flipped by inverted conditions) and
  high-frequency warnings are throttled (60 s).
- Added the shared `BrewerDrinkCooldown` (20 s, logout/stop cleanup) and Arc
  condition/reward registrations (`brewer_study_abilities_enabled`,
  `brewer_tasting_abilities_enabled`, `brewer_drink_cooldown`,
  `brewer_tasting_effects`).
- Only real, non-automated, graded events trigger brewing effects; automated /
  UNKNOWN / T3 never do. Tasting unlocks only on a completed drink of a
  `#tcth:brewer_drinks` beverage (canceled drinks never fire).
- Added zh_cn / en_us names and descriptions for all 12 powerups plus the new
  config/condition/reward keys.
- No gold, extra drops, beverage duplication or second XP pipeline; 7B/7C
  event and reward values unchanged.

### 7D.1 — brewer stats / Field Guide hardening

- `fieldGuideBrewerEnabled` now composes with the framework switch and the new
  `fieldGuideEnabled` master switch; every config read fails closed.
- Brewing-stats event-id cache gained expiry (40 ticks), logout and server-stop
  cleanup, and hard cap (4096) with success-driven commit.
- Unlock-failure logs throttled to 60 s; NBT load rejects negative counters,
  malformed resource locations and unknown enums (counters stay saturated).
- The most-prepared beverage tie-break is deterministic (full item id asc).

### 7D — brewer statistics archive + Field Guide brewer catalogue

- Added `PlayerBrewingStats` / `BrewingStatsData` (world/data/tcth_brewing_stats.dat,
  dataVersion 1, fixed overworld storage, UUID-keyed, 1024-player cap).
- Added `/tcth brewer stats [player]` (permission ≥ 3 for others), read-only.
- Added Field Guide brewer categories (brew_common 18 / brew_t2 46 explicit
  entries) unlocked only by real `BeveragePreparedEvent`s; pickup/drink/give
  never unlock (gate prerequisite `tcth:brewer_cookbook_gate`).
- Added `scripts/generate_brewer_field_guide.py` (deterministic, stale-cleaned,
  strict resource-location validation, no T3).

### 7C.2.1 — Keg delivery fix + regression guards

- Fixed the Keg `@Inject` handler parameter binding: the setItemInHand and
  drop handlers now publish the actually-delivered beverage (`deliveredStack`),
  not the original held stack, restoring events/XP for the hand-replacement and
  full-inventory drop branches.
- Added reflection-based regression tests for the two handlers (4 cases).

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
