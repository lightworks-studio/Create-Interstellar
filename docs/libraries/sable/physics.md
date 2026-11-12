# Libraries — Sable (Physics)

Notes on how this mod uses [Sable](https://github.com/RyanHCode/Sable)'s
sub-level physics API (`dev.ryanhcode.sable`, `sable-neoforge-1.21.1`).

## `BlockEntitySubLevelActor` — sub-level actor hooks

Interface: `dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor`.
Block entities implementing it (e.g. `AbstractThrusterBlockEntity`,
`RocketThrusterBlockEntity`) become "actors" that Sable ticks while they are
mounted on a sub-level.

### `sable$physicsTick(ServerSubLevel, RigidBodyHandle, double timeStep)`

- Called **once per physics tick** (there may be multiple physics ticks per
  game tick — fixed-timestep pipeline).
- Invoked from Sable's `ServerSubLevel.prePhysicsTick()`, which iterates the
  actor block entities registered in that sub-level's plot and calls this on
  each.
- Only fires for actors **mounted on a sub-level** (picked up into a
  contraption). A block entity sitting in normal world chunks is never called.
- One BE instance belongs to exactly one sub-level at a time; it is not fanned
  out across multiple sub-levels. N thrusters spread over M sub-levels means
  each sub-level ticks its own actors.

Parameters:

| Param | Meaning |
| --- | --- |
| `subLevel` | The `ServerSubLevel` this actor currently sits on. |
| `handle` | Rigid-body handle for that sub-level's physics body (velocity, forces, ...). |
| `timeStep` | Duration of this physics step (seconds) — scale impulses/forces by it. |

Related hook: `sable$tick(ServerSubLevel)` — once per **server game tick**
(non-physics bookkeeping).

### Usage in this mod (thrusters)

- Single blocks: `AbstractThrusterBlockEntity.sable$physicsTick` builds an
  impulse sample (`ThrusterForceProvider.createSample`) and applies it at the
  nozzle position via `SimulatedThrustAdapter.applyImpulseAtPoint(subLevel,
  pointLocal(), adjustedImpulse)` (impulse divided by
  `getThrustUnitsPerKn()`).
- Multiblocks: only the **controller** BE applies the combined impulse at the
  multiblock center nozzle; non-controller parts return early.
- Before applying an impulse, `AbstractThrusterBlockEntity.getVelocityThrottle`
  reads the body's linear velocity and returns a `0..1` multiplier: `1` up to
  90% of `contraption_maxVelocity` (blocks/second), tapering linearly to `0` at
  the cap. The impulse is scaled by it, so active thrusters accelerate a ship
  toward the configured maximum without overshooting it. A value of `0`
  disables the throttle.

### Usage in this mod (rocket station)

- `RocketStationBlockEntity.sable$physicsTick` only handles the atmospheric
  warp countdown; it no longer clamps the sub-level's linear velocity. Speed
  limiting is the thruster throttle's responsibility.
- If the sub-level is frozen by a pending warp or recovery hold
  (`SublevelDimensionalTeleportUtils.isFrozen`), the station returns early and
  leaves the velocity untouched so the warp logic owns the body.

This is the mechanism that feeds thrust into Sable's physics world so
contraptions accelerate/rotate in flight.

