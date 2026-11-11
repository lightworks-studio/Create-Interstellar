# Thruster Unification + FACING-based Thrusters — Work Tracker

**Status: COMPLETE.** The shared bases, both concrete thrusters, registrations, assets,
recipe/lang generation and documentation are implemented and the project builds. See
[`modules/thruster.md`](modules/thruster.md) for the current subsystem reference; the
sections below are retained as the original design/implementation plan and audit trail.

This file tracks what is done and what remains for the task of:
1. Porting the **Liquid Thruster** from the reference mod **Create Kinetic** (`OTHER MODS/CreateKinetic`, read-only).
2. **Unifying** the existing `RocketThruster*` and the new `LiquidThruster*` on shared base classes (no copy-paste).
3. Making **both thrusters FACING-based** (any of 6 directions), replacing rocket's vertical-only `TOP`/`BOTTOM`/`MULTI` model.
4. Keeping **2×2×2 and 3×3×3 multiblock** on **both** thrusters, with **separate models**.

Repo: `com.lightning.northstar` (Create Interstellar), NeoForge, Create 6.0.10-280, working dir `/home/sampw/Documents/minecraft/mods/Create-Interstellar`.

---

## 1. User-approved requirements (fixed scope)

- Scope: **Liquid thruster only** (no Ion/Vector/Creative from Create Kinetic).
- **Unify** rocket + liquid on shared base classes.
- **FACING-based** (any of 6 directions) for **both**.
- **BOTH** support **2×2×2 and 3×3×3 multiblocks**; keep **models SEPARATE**.
- New code in `com.lightning.northstar`; do **NOT** import `org.lightning323.createkinetic`; `OTHER MODS/` is **read-only** (never modify).
- Follow Interstellar patterns (`NorthstarRegistrite`, `SmartFluidTankBehaviour`, `NorthstarConfigs`, `NorthstarLang`, `NorthstarPartialModels`, `NorthstarFluidTags`).
- Per AGENTS.md: update `docs/architecture.md` and create `docs/modules/thruster.md`.

## 2. Agent-decided (reasonable; flag to user to change)

1. **Shared base block extends `DirectionalBlock`** (template in-repo: `OxygenDetectorBlock`). Props: `FACING` (6-way) + `MULTIBLOCK` BooleanProperty. Thrust = `FACING`; exhaust/nozzle = `FACING.getOpposite()`; nozzle center offset toward `-FACING`. `FACING=UP` must reproduce today's rocket behavior.
2. **Keep the JSON fuel system** (`*FuelManager`/`*FuelProperties`, post-rename) for **both**. Reuse `NorthstarFluidTags.C_OXYGEN` / `C_LIQUID_OXYGEN`. No new fluids.
3. **Move the fuel classes + `MultiFluidHandler` into shared `com.lightning.northstar.block.tech.thruster` and rename** `RocketThrusterFuelManager`→`ThrusterFuelManager`, `RocketThrusterFuelProperties`→`ThrusterFuelProperties`, `MultiFluidHandler`→(shared) `MultiFluidHandler`. Only `Northstar.java:5,146` references the manager — update that import.
4. **Skip porting:** dye coloring, CC peripheral, `SimulatedThrustAdapter`, `WorldExhaustRay`, `KineticConfig`, `KineticFluids`, `ThrusterForceProvider`, `PlumeParticleData` (reuse Interstellar equivalents).
5. **Keep** rocket contraption (`contraption/rocket/` + `RocketThrusterMovementBehaviour`). **Liquid thruster does NOT get contraption behavior.**
6. **Multiblock shape = shared W×W×L prism** (width 1–3, length ≥ width). Includes 2×2×2 and 3×3×3. Rocket is ALREADY this prism. Keep for both; liquid gains it.
7. **Keep** rocket's Sable `QueuedForceGroup(ForceGroups.PROPULSION)` force in the shared base (generic, applies to both).
8. **Placement: `FACING = context.getClickedFace()`** (thrust direction; nozzle seats against clicked surface).
9. **Liquid plume particle: REUSE `ThrusterPlumeParticleData.INSTANCE`** for both thrusters.
10. **Config: generalize to overridable getters in the base BE; keep existing `rocketThruster*` keys** (do NOT delete). Rocket overrides read the existing keys; liquid can override the getters (default: same values; distinct `liquidThruster*` keys only if desired).

## 3. Corrected plan details (important compile-safety notes)

- **A. Base block `codec()`:** `ThrusterBlock extends DirectionalBlock` **must** declare `MapCodec`/`codec()` (like `OxygenDetectorBlock`).
- **B. `getControllerBE()` returns the BASE type `RocketThrusterBlockEntity`** in the base (not a concrete type). Callers needing a concrete type `instanceof`-check.
- **C. No hardcoded `NorthstarBlocks.ROCKET_THRUSTER` in the base.** Use abstract hook `protected abstract boolean isCompatibleBlock(BlockState state)` — Rocket→`ROCKET_THRUSTER`, Liquid→`LIQUID_THRUSTER`. `isThruster`/`isLayerComplete`/`validateMulti` call it.
- **D. `registerCapabilities` per-subclass** (each registers its own `NorthstarBlockEntityTypes.X`). `NorthstarCommonEvents` calls both.
- **E. `getBlockEntityType()` override** in each concrete block (`IBE` requires `getBlockEntityClass()` AND `getBlockEntityType()`).

## 4. DONE (this + prior sessions)

- [x] **Created `ThrusterBlock.java`** — shared abstract base block.
  Path: `src/main/java/com/lightning/northstar/block/tech/thruster/ThrusterBlock.java` (103 lines).
  - `abstract extends DirectionalBlock implements IBE<ThrusterBlockEntity>, IWrenchable`.
  - `MULTIBLOCK` BooleanProperty; default state `FACING=UP, MULTIBLOCK=false`.
  - `createBlockStateDefinition` adds `FACING` + `MULTIBLOCK`.
  - `getStateForPlacement` → `FACING = clicked face`.
  - `rotate`/`mirror` on `FACING`.
  - `onPlace`/`neighborChanged` → redstone input via `be.setRedstoneInput(...)`.
  - `onRemove` → disassemble via controller `disassembleMulti()`.
  - `abstract getBlockEntityClass()` → `Class<? extends ThrusterBlockEntity>`.
  - `abstract getBlockEntityType()` → `BlockEntityType<? extends ThrusterBlockEntity>`.
  - `isCompatibleBlock(BlockState)` default `state.is(this)` (base BE overrides via hook).
  - **KNOWN GAP (still to fix): missing `MapCodec`/`codec()` override** (see §3.A). Add:
    `public static final MapCodec<ThrusterBlock> CODEC = simpleCodec(ThrusterBlock::new);`
    `@Override protected MapCodec<? extends DirectionalBlock> codec() { return CODEC; }`
    (import `com.mojang.serialization.MapCodec`). Subclasses can inherit.
- [x] Handoff + verification reads of all rocket-thruster classes, config, registration, assets (this session).
- [x] This tracking doc created (`docs/thruster_code.md`).

## 5. NOT DONE (remaining work, in suggested order)

### 5.1 Shared base block entity — `RocketThrusterBlockEntity` (BIGGEST remaining piece)
Create `src/main/java/com/lightning/northstar/block/tech/thruster/ThrusterBlockEntity.java`, abstract, extracted from `RocketThrusterBlockEntity` (735 lines, `block/tech/rocket_thruster/`). Must:
- Extend `SmartBlockEntity`, implement `IHaveGoggleInformation`, `BlockEntitySubLevelActor`, `BlockSubLevelAssemblyListener`.
- Move all shared fields: `width, length, controllerPos, redstoneInput, power, currentThrust, fuelUsageBuffer, oxidizerUsageBuffer, clearBlocks, powerDirty, needsConnectivityUpdate, disassembling, assemblyCooldown`, `fuelTank/oxidizerTank` (`SmartFluidTankBehaviour` + `FUEL_TANK`/`OXIDIZER_TANK` BehaviourTypes).
- Implement (shared, FACING-aware): `tick`, `onLoad`, `afterMove`, `isMultiblock` (`width>1 && length>=width`), `isController`, `getControllerBE` (**returns base type**), getters, `updateConnectivity`, `setRedstoneInput`, `tryAssemble`, `findLength`, `isLayerComplete`, `formMulti`, `disassembleMulti`, `validateMulti`, `resetToSingle`, `updateThrust`, `computePower`, `setCurrentThrust`, `isOxygen`, `drainTank`, `trimToCapacity`, `distributeFluid`, `updateClearBlocks`, `tickParticles`, `sable$physicsTick`, `addToGoggleTooltip`, `getRenderBoundingBox`, NBT `write`/`read`, static `getBlockEntitySafe`/`getBlockStateSafe`.
- **Make block-type-aware via abstract `protected abstract boolean isCompatibleBlock(BlockState state)`** (replace the hardcoded `NorthstarBlocks.ROCKET_THRUSTER` in `isThruster`/`isLayerComplete`).
- **Make FACING-aware** (currently hard-coded +Y / -Y / +X / +Z):
  - `hasNeighbourThruster()`: currently `above()/east()/south()` → derive the 3 relevant offsets from `FACING` (1 along `FACING` + 2 cross axes).
  - `findLength`/`isLayerComplete`/`formMulti`/`disassembleMulti`/`validateMulti`/`computePower`/`updateClearBlocks`: all use `worldPosition.offset(x, y, z)` (length along +Y) → generalize to `offsetAlongFACING`.
  - `sable$physicsTick`: force point `(x+w*0.5, y-0.45, z+w*0.5)`, impulse `(0, thrust*dt, 0)` → rotate to FACING (thrust along `FACING`, point offset toward `-FACING`).
  - `tickParticles`: spawn point + direction `(0,-1,0)` → FACING (direction = `-FACING`, point offset toward `-FACING`).
  - `getRenderBoundingBox`: currently `-width*0.25-0.25 .. +length+0.25` in Y → FACING-aware AABB.
  - `updateClearBlocks`: currently checks `offset(x, -y, z)` for air → FACING-aware (5 blocks along `-FACING`).
- **Overridable config getters** (default read `NorthstarConfigs.server().rocketThruster*`; subclasses may override): `getBaseTankCapacity()`, `getBaseThrust()`, `getFuelUsage()`, `getMaxLength()`, plus `getFuelProperties` (uses `ThrusterFuelManager`).
- **Per-subclass `static registerCapabilities(RegisterCapabilitiesEvent)`** (see §5.7).
- Keep NBT keys unchanged: `Width,Length,Redstone,Power,Thrust,FuelUsageBuffer,OxidizerUsageBuffer,ClearBlocks,ControllerX/Y/Z` (for rocket NBT compat).

### 5.2 Concrete `RocketThrusterBlockEntity extends ThrusterBlockEntity`
Create `block/tech/rocket_thruster/RocketThrusterBlockEntity.java` (thin subclass) OR keep the existing file and re-parent. Either way:
- Override `isCompatibleBlock(state)` → `state.is(NorthstarBlocks.ROCKET_THRUSTER.get())`.
- Keep/override config getters reading `rocketThruster*` keys.
- Keep `addToGoggleTooltip` labels `northstar.gui.goggles.rocket_thruster.fuel`/`.oxidizer`.
- `registerCapabilities` registers `NorthstarBlockEntityTypes.ROCKET_THRUSTER`.
- **Remove the duplicated shared logic** that moves to the base.

### 5.3 Concrete `RocketThrusterBlock extends ThrusterBlock`
Rewrite `block/tech/rocket_thruster/RocketThrusterBlock.java` (currently 105 lines, `extends Block` with `TOP`/`BOTTOM`/`MULTI`):
- `extends ThrusterBlock`; remove `TOP`/`BOTTOM`/`MULTI` (base supplies `MULTIBLOCK`+`FACING`).
- Override `getBlockEntityClass()`→`RocketThrusterBlockEntity.class`, `getBlockEntityType()`→`NorthstarBlockEntityTypes.ROCKET_THRUSTER.get()`, `isCompatibleBlock(state)`→`state.is(NorthstarBlocks.ROCKET_THRUSTER.get())`.
- Remove the old `updateConnectivity` private helper (base `onPlace`/`neighborChanged`/`onRemove` now handle it via base BE).

### 5.4 NEW `LiquidThrusterBlockEntity extends ThrusterBlockEntity`
Create `block/tech/liquid_thruster/LiquidThrusterBlockEntity.java`:
- Override `isCompatibleBlock(state)` → `state.is(NorthstarBlocks.LIQUID_THRUSTER.get())`.
- Config getters: default (or add `liquidThruster*` keys if user wants distinct).
- `addToGoggleTooltip` labels `northstar.gui.goggles.liquid_thruster.fuel`/`.oxidizer` (add lang keys).
- `registerCapabilities` registers `NorthstarBlockEntityTypes.LIQUID_THRUSTER`.
- **No contraption/movement behavior.**

### 5.5 NEW `LiquidThrusterBlock extends ThrusterBlock`
Create `block/tech/liquid_thruster/LiquidThrusterBlock.java`:
- Override `getBlockEntityClass()`→`LiquidThrusterBlockEntity.class`, `getBlockEntityType()`→`NorthstarBlockEntityTypes.LIQUID_THRUSTER.get()`, `isCompatibleBlock(state)`→`state.is(NorthstarBlocks.LIQUID_THRUSTER.get())`.

### 5.6 NEW `RocketThrusterRenderer` base + concrete renderers
Create `client` base `ThrusterRenderer extends SmartBlockEntityRenderer<ThrusterBlockEntity>` with:
- abstract `getMultiblockBody(int width)` / `getMultiblockCap(int width)` (return `PartialModel`).
- shared `renderSafe` (guard `!isController() || !isMultiblock()`), **FACING-aware** (apply FACING rotation to the translate/scale, currently hard-coded `Axis.XP.rotationDegrees(90)` + Y translate).
Then:
- `RocketThrusterRenderer extends ThrusterRenderer` (pass the 4 `ROCKET_THRUSTER_MULTIBLOCK_*` partials; keep `copperEnd = width==2?8:6`).
- NEW `LiquidThrusterRenderer extends ThrusterRenderer` (pass the 4 `LIQUID_THRUSTER_MULTIBLOCK_*` partials).

### 5.7 Fuel class move + rename (shared package)
- Move `RocketThrusterFuelManager`→`block/tech/thruster/ThrusterFuelManager`.
- Move `RocketThrusterFuelProperties`→`block/tech/thruster/ThrusterFuelProperties`.
- Move `MultiFluidHandler`→`block/tech/thruster/MultiFluidHandler`.
- Update all imports: `Northstar.java:5,146`, base BE, both concrete BEs.
- Keep `data/northstar/thruster_fuels/*.json` (5 files) — shared, unchanged.

### 5.8 Registration
- `NorthstarBlocks.java`:
  - Add `LIQUID_THRUSTER` entry (copy `ROCKET_THRUSTER` at line 373–383): `LiquidThrusterBlock`, path `"liquid_thruster"`, model `block/liquid_thruster/single`, **OMIT** `.onRegister(MovementBehaviour.movementBehaviour(new RocketThrusterMovementBehaviour()))`, **OMIT** `NorthstarBlockTags.ROCKET_ALWAYS_ACTIVE_ACTORS.tag`, mapColor `COLOR_CYAN`.
  - Update `ROCKET_THRUSTER` import to new package (still `block.tech.rocket_thruster.RocketThrusterBlock` — unchanged location, but class now extends `ThrusterBlock`).
- `NorthstarBlockEntityTypes.java` (line 171–175):
  - Add `LIQUID_THRUSTER = REGISTRATE.blockEntity("liquid_thruster", LiquidThrusterBlockEntity::new).validBlocks(NorthstarBlocks.LIQUID_THRUSTER).renderer(() -> LiquidThrusterRenderer::new).register();`
  - `ROCKET_THRUSTER` unchanged (still `RocketThrusterBlockEntity`/`RocketThrusterRenderer`).
- `NorthstarPartialModels.java` (line 43–46): add 4 `LIQUID_THRUSTER_MULTIBLOCK_{2X2X2,3X3X3}_{BODY,CAP}` = `block("liquid_thruster/multiblock_...")`.
- `NorthstarCommonEvents.java` (events/, line 31): add `LiquidThrusterBlockEntity.registerCapabilities(event);` (+ import).
- `Northstar.java:5,146`: update fuel-manager import to new package.
- **Optional integrations (flag to user; not blocking):** `NorthstarJEI.java:145` add `LIQUID_THRUSTER` as a fuel-type recipe catalyst; `NorthstarPonderPlugin.java:43` / `NorthstarPonderTags.java:31` optionally add `LIQUID_THRUSTER`; `compat/jei/category/FuelTypeCategory.java:41,85-90` optionally add a liquid-thruster drawable line; `NorthstarStandardRecipeGen.java:165` add a `LIQUID_THRUSTER` recipe entry.

### 5.9 `RocketThrusterMovementBehaviour` FACING fix (rocket-only)
`block/tech/rocket_thruster/RocketThrusterMovementBehaviour.java` currently checks `context.state.getValue(RocketThrusterBlock.BOTTOM)`. After refactor `BOTTOM` no longer exists.
- Change to: only emit plume when `context.state.getValue(ThrusterBlock.FACING) == Direction.UP` (thrust pointing UP ⇒ body extends upward from this block; matches original `BOTTOM=true` intent).
- **Liquid thruster does NOT get this behavior** (no contraption).

### 5.10 Assets (SEPARATE models per user requirement)
Rocket (already exists under `src/main/resources/assets/northstar/`):
- `blockstates/rocket_thruster.json` — **MUST be rewritten** to FACING variants (6 directions; multiblock members all map to `empty.json` since only the controller renders the multiblock).
- `models/block/rocket_thruster/`: `single.json`, `multiblock_2x2x2_body.json`, `multiblock_2x2x2_cap.json`, `multiblock_3x3x3_body.json`, `multiblock_3x3x3_cap.json` (keep). `top/bottom/middle/empty` can remain (empty still used by multiblock members) or be pruned.
- Textures: `rocket_thruster_1.png`, `rocket_thruster_2.png`, `rocket_thruster/multiblock_2x2x2.png`, `rocket_thruster/multiblock_3x3x3.png` (keep).

Liquid (NEW — create):
- `blockstates/liquid_thruster.json` — FACING variants → `northstar:block/liquid_thruster/single` (6 directions).
- `models/block/liquid_thruster/single.json` (copy rocket `single.json`, swap texture refs to `liquid_thruster_*`).
- `models/block/liquid_thruster/multiblock_2x2x2_body.json`, `multiblock_2x2x2_cap.json`, `multiblock_3x3x3_body.json`, `multiblock_3x3x3_cap.json` (copy rocket equivalents, swap texture refs).
- Textures: `liquid_thruster_1.png`, `liquid_thruster_2.png`, `liquid_thruster/multiblock_2x2x2.png`, `liquid_thruster/multiblock_3x3x3.png`.
- Generated: `src/generated/assets/northstar/models/item/liquid_thruster.json` (or regenerate).

### 5.11 Data gen / recipe
- Add `LIQUID_THRUSTER` to `NorthstarStandardRecipeGen.java:165` (or similar) so a crafting recipe + loot table + advancement are generated.
- Run `./gradlew generateResources` (or equivalent) to produce `src/generated/data/northstar/.../liquid_thruster.json`.

### 5.12 Docs (per AGENTS.md)
- Write `docs/architecture.md` (currently empty).
- Create `docs/modules/thruster.md` describing the unified thruster subsystem (base block, base BE, FACING semantics, multiblock shape, fuel system, config, particles, contraption-only-for-rocket).

### 5.13 Build & test
- `./gradlew build` — ensure it compiles.
- In-game test: place single rocket thruster, place 2×2×2 and 3×3×3, verify FACING placement (all 6), verify plume direction, verify multiblock forms/dismantles, verify liquid thruster identical behavior (minus contraption), verify rocket contraption still works.

## 6. External-reference audit (only these sites reference rocket-thruster classes)

- `NorthstarBlocks.java:28-29,373-383` — imports + `ROCKET_THRUSTER` registration.
- `NorthstarBlockEntityTypes.java:32-33,171-175` — imports + `ROCKET_THRUSTER` BE entry.
- `NorthstarPartialModels.java:43-46` — 4 rocket partials.
- `Northstar.java:5,146` — fuel manager import + `event.addListener`.
- `NorthstarCommonEvents.java:31` — `registerCapabilities` call (in `events/`, not `content/`).
- `NorthstarContentRemapper.java:43` — `remap("jet_engine", ROCKET_THRUSTER)` (keep valid).
- `contraption/rocket/RocketContraption.java:7,105` — `instanceof RocketThrusterBlock` (keep rocket-specific; liquid must NOT affect thrust count).
- `NorthstarJEI.java:145`, `NorthstarPonderPlugin.java:43`, `NorthstarPonderTags.java:31`, `compat/jei/category/FuelTypeCategory.java:41,85-90`, `NorthstarStandardRecipeGen.java:165` — optional integrations (flag to user).

## 7. Config (ServerConfig `thruster` group, lines 39–42)

```
rocketThrusterBaseThrust    = f(500, 0, "baseThrust", ...)
rocketThrusterFuelUsage     = f(1,   0, "fuelUsage",  ...)   // (implied by updateThrust usage)
rocketThrusterFuelCapacity  = i(1000,1, "fuelCapacity", ...)
rocketThrusterMaxLength     = i(64,  3, "maxLength", ...)
```
Keep all keys; expose as overridable getters in base BE; rocket reads them; liquid defaults to same (or add `liquidThruster*` keys if user wants distinct).

## 8. Files to touch (checklist)

**Create (new):**
- [ ] `src/main/java/com/lightning/northstar/block/tech/thruster/ThrusterBlockEntity.java`
- [ ] `src/main/java/com/lightning/northstar/block/tech/thruster/ThrusterFuelManager.java` (moved + renamed)
- [ ] `src/main/java/com/lightning/northstar/block/tech/thruster/ThrusterFuelProperties.java` (moved + renamed)
- [ ] `src/main/java/com/lightning/northstar/block/tech/thruster/MultiFluidHandler.java` (moved)
- [ ] `src/main/java/com/lightning/northstar/block/tech/liquid_thruster/LiquidThrusterBlock.java`
- [ ] `src/main/java/com/lightning/northstar/block/tech/liquid_thruster/LiquidThrusterBlockEntity.java`
- [ ] `src/main/java/com/lightning/northstar/client/.../ThrusterRenderer.java` (base; place per Interstellar client package)
- [ ] `src/main/java/com/lightning/northstar/client/.../LiquidThrusterRenderer.java`
- [ ] `src/main/resources/assets/northstar/blockstates/liquid_thruster.json`
- [ ] `src/main/resources/assets/northstar/models/block/liquid_thruster/single.json`
- [ ] `src/main/resources/assets/northstar/models/block/liquid_thruster/multiblock_2x2x2_body.json`
- [ ] `src/main/resources/assets/northstar/models/block/liquid_thruster/multiblock_2x2x2_cap.json`
- [ ] `src/main/resources/assets/northstar/models/block/liquid_thruster/multiblock_3x3x3_body.json`
- [ ] `src/main/resources/assets/northstar/models/block/liquid_thruster/multiblock_3x3x3_cap.json`
- [ ] `src/main/resources/assets/northstar/textures/block/liquid_thruster_1.png`
- [ ] `src/main/resources/assets/northstar/textures/block/liquid_thruster_2.png`
- [ ] `src/main/resources/assets/northstar/textures/block/liquid_thruster/multiblock_2x2x2.png`
- [ ] `src/main/resources/assets/northstar/textures/block/liquid_thruster/multiblock_3x3x3.png`
- [ ] `docs/architecture.md` (write)
- [ ] `docs/modules/thruster.md` (write)

**Modify (existing):**
- [ ] `src/main/java/com/lightning/northstar/block/tech/thruster/ThrusterBlock.java` (add `MapCodec`/`codec()`)
- [ ] `src/main/java/com/lightning/northstar/block/tech/rocket_thruster/RocketThrusterBlock.java` (re-parent to `ThrusterBlock`, remove `TOP/BOTTOM/MULTI` + `updateConnectivity`)
- [ ] `src/main/java/com/lightning/northstar/block/tech/rocket_thruster/RocketThrusterBlockEntity.java` (re-parent to `RocketThrusterBlockEntity`, drop shared logic, keep rocket-specific overrides)
- [ ] `src/main/java/com/lightning/northstar/block/tech/rocket_thruster/RocketThrusterRenderer.java` (re-parent to `RocketThrusterRenderer` base, FACING-aware)
- [ ] `src/main/java/com/lightning/northstar/block/tech/rocket_thruster/RocketThrusterMovementBehaviour.java` (FACING check replaces `BOTTOM`)
- [ ] `src/main/java/com/lightning/northstar/block/tech/rocket_thruster/RocketThrusterFuelManager.java` (delete after move)
- [ ] `src/main/java/com/lightning/northstar/block/tech/rocket_thruster/RocketThrusterFuelProperties.java` (delete after move)
- [ ] `src/main/java/com/lightning/northstar/block/tech/rocket_thruster/MultiFluidHandler.java` (delete after move)
- [ ] `src/main/java/com/lightning/northstar/Northstar.java` (import update)
- [ ] `src/main/java/com/lightning/northstar/content/NorthstarBlocks.java` (add `LIQUID_THRUSTER`)
- [ ] `src/main/java/com/lightning/northstar/content/NorthstarBlockEntityTypes.java` (add `LIQUID_THRUSTER`)
- [ ] `src/main/java/com/lightning/northstar/content/NorthstarPartialModels.java` (add 4 liquid partials)
- [ ] `src/main/java/com/lightning/northstar/events/NorthstarCommonEvents.java` (add liquid `registerCapabilities`)
- [ ] `src/main/java/com/lightning/northstar/content/recipe/NorthstarStandardRecipeGen.java` (add liquid recipe)
- [ ] `src/main/resources/assets/northstar/blockstates/rocket_thruster.json` (rewrite to FACING)
- [ ] `src/main/java/com/lightning/northstar/compat/jei/NorthstarJEI.java` (optional)
- [ ] `src/main/java/com/lightning/northstar/compat/jei/category/FuelTypeCategory.java` (optional)
- [ ] `src/main/java/com/lightning/northstar/ponder/NorthstarPonderPlugin.java` (optional)
- [ ] `src/main/java/com/lightning/northstar/ponder/NorthstarPonderTags.java` (optional)

**Keep as-is:**
- `src/main/java/com/lightning/northstar/contraption/rocket/RocketContraption.java` (rocket-specific `instanceof RocketThrusterBlock` stays).
- `src/main/resources/data/northstar/thruster_fuels/*.json` (5 files, shared).

## 9. Verified facts (use these, do not re-derive)

- `ThrusterBlock` currently declares `abstract getBlockEntityClass()` + `abstract getBlockEntityType()` + concrete `isCompatibleBlock(BlockState state){ return state.is(this); }`.
- `RocketThrusterBlockEntity` fields: `MAX_WIDTH=3`, `MIN_LENGTH=2`, `NOZZLE_OFFSET=0.45`. W×W×L prism extending +Y.
- NBT keys: `Width,Length,Redstone,Power,Thrust,FuelUsageBuffer,OxidizerUsageBuffer,ClearBlocks,ControllerX/Y/Z`.
- `NorthstarLang.addTankTooltip(List<Component>, SmartFluidTank, Component label)` exists.
- `FuelType.getFuelType(fluid)` fallback in `*FuelManager.getProperties`.
- `NorthstarFluidTags.C_OXYGEN` / `C_LIQUID_OXYGEN` are the oxidizer tags.
- `ThrusterPlumeParticleData.INSTANCE` is the plume particle (reuse for liquid).
- `NorthstarParticles.ROCKET_PLUME` / `COLD_AIR` are the contraption plume particles (rocket-only, used by `RocketThrusterMovementBehaviour`).
- `IBE` interface requires `getBlockEntityClass()` AND `getBlockEntityType()`.
- `OxygenDetectorBlock` is the in-repo template for `DirectionalBlock` + `MapCodec`/`codec()`.
- `SableCompanion.INSTANCE.getContaining(level, pos)` for sublevel-aware BE/state lookups.
- `QueuedForceGroup(ForceGroups.PROPULSION)` for the thrust force.
- `MovementBehaviour.movementBehaviour(...)` is the contraption hook (rocket-only, line 382 in `NorthstarBlocks`).

## 10. Open questions for the user

1. Distinct `liquidThruster*` config keys, or share `rocketThruster*` keys (default plan: share)?
2. Add optional JEI/ponder/recipegen integrations for `LIQUID_THRUSTER` (default plan: yes for recipegen, skip JEI/ponder unless asked)?
3. Liquid-thruster mapColor `COLOR_CYAN` OK? (default plan: yes)
4. Prune the now-unused rocket `top.json`/`bottom.json`/`middle.json` model files, or keep them (default plan: keep, harmless)?
5. Should the liquid thruster be a `ROCKET_ALWAYS_ACTIVE_ACTORS` tag member? (default plan: NO — it's not a rocket actor)

## 11. Completion notes (final)

Implemented on top of the plan above, with these concrete choices:

- **Generic base block:** `ThrusterBlock<T extends ThrusterBlockEntity>` so each subclass can
  return its exact `Class<T>`/`BlockEntityType<? extends T>` (required by `IBE<T>`).
- **Config:** separate `liquidThruster{BaseThrust,FuelUsage,FuelCapacity,MaxLength}` keys were
  added (user choice) alongside the untouched `rocketThruster*` keys.
- **Blockstate mapping** (up-authored single model, verified against vanilla `end_rod`):
  `up {}`, `down {x:180}`, `north {x:90}`, `south {x:90,y:180}`, `east {x:90,y:90}`,
  `west {x:90,y:270}`; all `multi=true` variants use the `empty` model.
- **Renderer** rotates the up-authored assembly about the controller **block center** with
  `UP identity, DOWN XP180, NORTH XP270, SOUTH XP90, EAST ZP270, WEST ZP90`; this matches the
  `crossAxisU/V` table used by the block entity exactly.
- **Liquid assets** are copies of the rocket models/textures under `liquid_thruster/`
  (separate files, per the user requirement).
- **Movement behaviour** now uses `context.localPos` + `contraption.getBlocks()` to skip
  plume emission when the exhaust-side neighbour is also a thruster, so multiblocks emit a
  single plume.
- **Generated resources** (`runData`): item model, crafting recipe, loot table, advancement
  and lang entries for `liquid_thruster`.
- Docs: this file, `docs/architecture.md`, `docs/modules/thruster.md` and
  `docs/decisions/0001-thruster-unification.md`.

Validation performed: `gradlew compileJava`, `gradlew build`, `gradlew runData` all succeed.
