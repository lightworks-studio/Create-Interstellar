# Module: Thrusters

The mod has two thruster variants that share a single implementation:

| Block | Block entity | Thrust source | Contraption behaviour |
| --- | --- | --- | --- |
| `northstar:rocket_thruster` | `RocketThrusterBlockEntity` | `rocketThruster*` config | yes (`RocketThrusterMovementBehaviour`) |
| `northstar:liquid_thruster` | `LiquidThrusterBlockEntity` | `liquidThruster*` config | no |

Both extend the same base classes:

- `block/tech/thruster/ThrusterBlock` — abstract `DirectionalBlock` (`IBE<T>`, `IWrenchable`).
- `block/tech/thruster/ThrusterBlockEntity` — abstract `SmartBlockEntity` containing all
  multiblock, fuel, redstone, particle and physics logic.
- `block/tech/thruster/ThrusterRenderer` — abstract `SmartBlockEntityRenderer` that renders
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
  owns the merged fluid buffer; on assembly, member tanks are pooled into the controller
  (aborting if the members hold different fluid types).
- The `multi` blockstate property hides the members' static models; the controller's
  block entity renderer draws the assembled model.
- Validation runs every 20 ticks; invalid assemblies disassemble and distribute fluid back
  into members. `onRemove` of any member disassembles the whole multiblock.

## Fuel and oxidizer

- Two `SmartFluidTankBehaviour`s: `FUEL_TANK` (validated against `ThrusterFuelManager`) and
  `OXIDIZER_TANK` (validated against `NorthstarFluidTags.C_OXYGEN` / `C_LIQUID_OXYGEN`).
- A single fuel source can be either an explicit `thruster_fuels` JSON entry or fall back to
  any fluid accepted by `FuelType`.
- Thrust = `baseThrust · W² · L · widthMultiplier · fuel.thrustMultiplier · power`, where
  `power = maxRedstoneSignal / 15` across all members.
- Fuel consumption scales the same way; both tanks drain in lockstep.
- `MultiFluidHandler` exposes fuel and oxidizer through the block's fluid capability.

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

## Configuration (`server.thruster`)

```
rocketThrusterBaseThrust / rocketThrusterFuelUsage / rocketThrusterFuelCapacity / rocketThrusterMaxLength
liquidThrusterBaseThrust / liquidThrusterFuelUsage / liquidThrusterFuelCapacity / liquidThrusterMaxLength
```

## Registration points

- `content/NorthstarBlocks`: `ROCKET_THRUSTER`, `LIQUID_THRUSTER`.
- `content/NorthstarBlockEntityTypes`: matching block entity entries + renderers.
- `content/NorthstarPartialModels`: four partials per thruster (`2×2×2` / `3×3×3` × body/cap).
- `events/NorthstarCommonEvents`: `registerCapabilities` for both thrusters.
- `Northstar`: `ThrusterFuelManager` resource reload listener.
- `content/recipe/NorthstarStandardRecipeGen`: crafting recipes.
