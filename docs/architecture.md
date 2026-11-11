# Create Interstellar — Architecture

Create Interstellar extends Create with space travel, planets, life support and rocket
technology. The code is organised around Create's registrate-based registration and
`SmartBlockEntity`/`SmartFluidTankBehaviour` infrastructure.

## Layers

| Layer | Package | Responsibility |
| --- | --- | --- |
| Bootstrap | `com.lightning.northstar` | Mod entrypoint, registrate, config, resource reload listeners. |
| Content | `content` | Block/item/fluid/entity/recipe registration (`NorthstarBlocks`, `NorthstarBlockEntityTypes`, `NorthstarPartialModels`, `NorthstarTags`, ...). |
| Blocks & block entities | `block.simple`, `block.tech.*` | Gameplay blocks and their `SmartBlockEntity` logic. |
| Contraptions | `contraption` | Create contraption assemblies (rockets, physics, movement behaviours). |
| Worldgen & planets | `world`, `planet` | Dimensions, biomes, planet properties and data-driven planet definitions. |
| Commands | `commands` | In-game commands (e.g. `/northstar warp` for cross-dimension sub-level teleportation via Dimensional Sable). |
| Compat | `compat`, `ponder`, `data` | JEI, Ponder, Sable physics and data generation. |

## Data flow

1. **Registration** happens once through `Northstar.REGISTRATE`; blocks, items and block
   entities are declared as static fields in `content/*`.
2. **Resources** are generated with the `runData` task into `src/generated` (which is also
   a resource source set) and merged with hand-written resources in `src/main/resources`.
3. **Block entities** extend Create's `SmartBlockEntity` and declare behaviours
   (kinetics, fluid tanks, item handlers) in `addBehaviours`.
4. **Multi-block machines** are assembled at runtime by a controller block entity which
   validates and links member block entities; the controller owns the rendering of the
   assembled model.
5. **Sable physics** actors receive `sable$physicsTick` callbacks on sub-levels and queue
   forces through `QueuedForceGroup`.

## Goggle overlays

Block entities that implement `IHaveGoggleInformation` add tank/status lines in
`addToGoggleTooltip`, using the helpers in `util/NorthstarLang`.

## Documentation map

- `docs/architecture.md` — this file.
- `docs/modules/` — per-subsystem deep dives (e.g. [`modules/thruster.md`](modules/thruster.md)).
- `docs/decisions/` — short architecture decision records.
- `docs/thruster_code.md` — implementation tracker for the thruster unification work.
