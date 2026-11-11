# Module: Thrusters

The mod has one thruster block:

| Block | Block entity | Thrust source | Contraption behaviour |
| --- | --- | --- | --- |
| `northstar:rocket_thruster` | `RocketThrusterBlockEntity` | `thruster*` config | yes (`RocketThrusterMovementBehaviour`) |

It extends the shared base classes:

- `block/tech/thruster/AbstractThrusterBlock` / `ThrusterBlock` — abstract + concrete
  `DirectionalBlock` (`IBE<T>`, `IWrenchable`).
- `block/tech/thruster/AbstractThrusterBlockEntity` — abstract `SmartBlockEntity` containing all
  multiblock, fuel, redstone, particle and physics logic.
- `block/tech/thruster/RocketThrusterRenderer` — `SmartBlockEntityRenderer` that renders
  the assembled multiblock.
- `block/tech/thruster/ThrusterFuelManager` / `ThrusterFuelProperties` — data-driven fuel
  definitions (`data/<ns>/thruster_fuels/*.json`).
- `block/tech/thruster/MultiFluidHandler` — combines the fuel and oxidizer tanks into one
  `IFluidHandler` capability.

## FACING semantics

`ThrusterBlock.FACING` is the **thrust direction**:

- The body extends from the controller **toward** `FACING`.
- The nozzle / exhaust is on the opposite (`FACING.getOpposite()`) side.
- Placement sets `FACING = context.getClickedFace()`, so the nozzle seats against the
  clicked surface.

All member offsets are derived from `FACING` through a cross-section basis:

| FACING | U axis (`crossAxisU`) | V axis (`crossAxisV`) |
| --- | --- | --- |
| UP | EAST | SOUTH |
| DOWN | EAST | NORTH |
| NORTH | EAST | UP |
| SOUTH | EAST | DOWN |
| EAST | DOWN | SOUTH |
| WEST | UP | SOUTH |

`RocketThrusterBlockEntity.memberPos(u, v, layer)` = controller position + `u·U + v·V + layer·FACING`.
The controller is the minimum corner in that basis. `exhaustPos(...)` mirrors the same
basis on the `FACING.getOpposite()` side for air-clearance checks and plumes.

## Multiblock assembly

- Shape: a `W×W×L` prism (`W ∈ {1,2,3}`, `L ≥ W`). This includes the `2×2×2` and
  `3×3×3` cases.
- `isMultiblock()` ⇔ `W > 1 && L ≥ W`.
- Assembly is attempted by `RocketThrusterBlockEntity.tick` (every 5 ticks) when a neighbouring
  compatible thruster exists. A layer must be complete (`W×W`) before the next layer is
  considered; the found length must be `≥ W`.
- Members store `width`, `length` and (for non-controllers) `controllerPos`. The controller
  owns the merged fluid buffers; on assembly, both member fuel **and** oxidizer tanks are
  pooled into the controller's tanks (aborting if the members hold different fluid types).
- The `multi` blockstate property hides the members' static models; the controller's
  block entity renderer draws the assembled model.
- Validation runs every 20 ticks; invalid assemblies disassemble and distribute both fuel and
  oxidizer back into members. `onRemove` of any member disassembles the whole multiblock.

## Fuel and oxidizer

- Every thruster (single-block included) has two `SmartFluidTankBehaviour`s: a fuel tank
  (validated against `ThrusterFuelManager`) and an oxidizer tank (validated to any fluid in
  the `c:oxygen` or `c:liquid_oxygen` tags, i.e. oxygen gas **and** liquid oxygen). Both have
  capacity `thrusterFuelTankCapacityMb` (default 1000 mB) for a single block; multiblocks
  scale the controller tanks by `W²·L`.
- Each tank uses its own named `BehaviourType` (`"Fuel"` / `"Oxidizer"`). Create's
  `SmartFluidTankBehaviour.single(...)` factory shares one static type, so two `.single()`
  tanks in a single block entity would collide in the behaviour map and NBT key; distinct
  types are required for both tanks to tick, save and load.
- **Both fluids are required to operate at every size.** `isWorking()` is
  `validFluid() && validOxidizer()`; when either tank is empty the thruster produces no
  thrust, plume or damage. Goggle status reports a dedicated `no_oxidizer` state after the
  fuel-empty/wrong-fuel states (lang key
  `createkinetic.gui.goggles.thruster.status.no_oxidizer`).
- A single fuel source can be either an explicit `thruster_fuels` JSON entry or fall back to
  any fluid accepted by `FuelType`.
- Single thrusters burn fuel and oxidizer in **1:1 lockstep** (same requested consumption,
  same accumulator logic); effective thrust scales with the smaller of the two drain ratios.
- Multiblock thrusters use the bulk efficiency multipliers: fuel cost is scaled by
  `multiblock{2x,3x}FuelEfficiency` and oxidizer cost by
  `multiblock{2x,3x}OxidizerEfficiency`, with matching thrust multipliers. Since oxidizer is
  now required, these multipliers act on a mandatory drain rather than an optional bonus.
- Thrust = `baseThrust · W² · L · widthMultiplier · fuel.thrustMultiplier · power`, where
  `power = maxRedstoneSignal / 15` across all members; consumption scales the same way.
- `MultiFluidHandler` exposes fuel and oxidizer through the block's fluid capability on
  every size (single thrusters expose it only on the `FACING` face, i.e. the body side).

## Rendering

`RocketThrusterRenderer` renders only the controller of a multiblock. Partial models are authored
pointing **up** (body `+Y`, nozzle `-Y`) and are rotated about the controller block center
by `applyFacingRotation` so the body aligns with `FACING`.

| FACING | PoseStack rotation |
| --- | --- |
| UP | identity |
| DOWN | `XP 180°` |
| NORTH | `XP 270°` |
| SOUTH | `XP 90°` |
| EAST | `ZP 270°` |
| WEST | `ZP 90°` |

The body is drawn from `(0, length, 0)` and the cap from `(0, capHeight, 0)`, matching the
historical rocket renderer; `capHeight = (16 - copperEnd) / 16 · W` with
`copperEnd = W == 2 ? 8 : 6`.

Blockstate variants for the single (`W = 1`) model use the vanilla up-authored mapping
(`up {}`, `down {x:180}`, `north {x:90}`, `south {x:90,y:180}`, `east {x:90,y:90}`,
`west {x:90,y:270}`); every `multi=true` variant resolves to the empty model.

## Particles and physics

- Client plume: `ThrusterPlumeParticleData.INSTANCE` spawned at the nozzle centre
  (`controller + U·W/2 + V·W/2 - FACING·0.45`), travelling toward `-FACING`. Sub-level
  transforms are applied when the thruster is on a Sable sub-level.
- Physics: `sable$physicsTick` applies a point force along `FACING` with magnitude
  `currentThrust·Δt` under `ForceGroups.PROPULSION`.
- The rocket-only `RocketThrusterMovementBehaviour` emits the contraption plume from the
  exhaust end of a `FACING == UP` thruster column (skipping members whose exhaust neighbour
  is also a thruster).

## Configuration (`server.thruster` + `server.multiblockThruster`)

```
thruster: baseThrust, obstructionScanLength, obstructionIgnoreOtherSubLevels, fuelTankCapacityMb,
          fuelMbPerTickAtFullThrottle, damageEntities, clientParticlesPerTick
multiblockThruster: multiblock2x/3xThrustMultiplier, multiblock2x/3xFuelEfficiency,
                    multiblock2x/3xOxidizerEfficiency
```

Defaults live in `ServerConfig.java`; a runtime-generated `config/Northstar/server.json` is
written on first launch. The physics sub-group (`thrusterPhysics`) covers nozzle offset and
force application.

## Registration points

- `content/NorthstarBlocks`: `ROCKET_THRUSTER`.
- `content/NorthstarBlockEntityTypes`: matching block entity entries + renderers.
- `content/NorthstarPartialModels`: four partials per thruster (`2×2×2` / `3×3×3` × body/cap).
- `events/NorthstarCommonEvents`: `registerCapabilities` for the rocket thruster.
- `Northstar`: `ThrusterFuelManager` resource reload listener.
- `content/recipe/NorthstarStandardRecipeGen`: crafting recipes.
