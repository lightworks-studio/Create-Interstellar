# 0001 — Unify thrusters on FACING-based base classes

## Context

The mod had a vertical-only rocket thruster (`TOP`/`BOTTOM`/`MULTI` blockstates, +Y
geometry hard-coded in the block entity) and needed a liquid thruster ported from a
reference mod. Copying the rocket implementation would duplicate a large amount of
multiblock, fuel and physics logic and leave two divergent behaviours.

## Decision

Introduce shared base classes and make **both** thrusters six-way `FACING`-based:

- `ThrusterBlock<T extends ThrusterBlockEntity>` — abstract directional block.
- `ThrusterBlockEntity` — all shared logic, parameterised by a `FACING`-derived cross-axis
  basis (`crossAxisU` / `crossAxisV`).
- `ThrusterRenderer<T>` — shared multiblock renderer; subclasses only supply partial models.
- Concrete `Rocket*` and `Liquid*` subclasses supply config getters, compatibility checks,
  tank labels and capability registration.

`FACING` is the thrust direction. The controller is the minimum corner in the
`(crossAxisU, crossAxisV, FACING)` basis, so the same assembly/validation code works for
every orientation. `FACING == UP` reproduces the previous rocket behaviour exactly.

Assets remain **separate** per thruster (`block/rocket_thruster/*`,
`block/liquid_thruster/*`), even though the renderer code is shared.

## Consequences

- One implementation to maintain for multiblock, fuel, redstone, particles and physics.
- Rocket NBT keys are preserved for backwards compatibility.
- Rockets gain arbitrary-orientation thrusters; the contraption movement behaviour only
  emits plumes for upward thrusters (the rocket use case) from the exhaust end.
- New `liquidThruster*` config keys allow independent tuning without breaking the existing
  `rocketThruster*` keys.
