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

This is the mechanism that feeds thrust into Sable's physics world so
contraptions accelerate/rotate in flight.

## Dimensional-Sable — cross-dimension sub-level warping

Notes on how this mod uses [Dimensional-Sable](https://modrinth.com/mod/dimensional-sable)
(`dev.egg.dimensionalsable`, pulled from Modrinth maven and jar-in-jarred in
`build.gradle.kts`). It extends Sable by allowing whole sub-levels (plus their
connected sub-levels and the entities inside them) to be teleported between
dimensions.

### `SubLevelWarper.warpConnectedSubLevels(...)`

- Signature used: `int warpConnectedSubLevels(ServerSubLevel, ServerLevel dest, Vector3d pos, Quaterniond orient)` —
  returns the number of sub-levels warped.
- The position/orientation are the new **world** pose in the destination
  dimension (JOML types).

### Usage in this mod (rocket station)

`RocketStationBlockEntity.sable$physicsTick` watches for a ship leaving the
atmosphere (`latestLinearVelocity.y > 0.015` and world Y above
`NorthstarConfigs.server().atmosphereTeleportHeight`, with a ~40-physics-tick
cooldown). When triggered it warps the sub-level to the `RocketDestination`
configured in the station:

- Target dimension comes from `destination.dimKey()`; the target position is
  the waypoint block pos center (`JOMLConversion.atCenterOf`), or the ship's
  current position when no waypoint is set.
- **Orientation flip:** the rocket's nose is local +Y. The warp composes a
  180° rotation (axis perpendicular to the current world-facing direction)
  with the current orientation, so a ship that entered nose-up emerges
  nose-down.
- The rocket station queues the warp on the server instead of calling the
  warper inline from `sable$physicsTick`. Inline warping removes the current
  Rapier body while Sable is still processing the physics callback, leaving a
  stale `RigidBodyHandle` for the remainder of that physics tick.
- The rocket path uses the explicit-orientation overload even when no
  orientation change is needed, matching the working `/northstar warp`
  command. The convenience overload is not used.
- After warping, linear and angular velocity are zeroed via
  `RigidBodyHandle.addLinearAndAngularVelocity(negated)` — otherwise the
  preserved upward velocity would immediately re-trigger the warp in the new
  dimension.

Reference implementation for the same API: `/northstar warp <sublevel>
<dimension> <x> <y> <z>` (`WarpSubLevelCommand`). The command uses
Minecraft's `DimensionArgument`, so its dimension argument is resolved from
the server's loaded dimension registry and supports modded dimensions as well
as vanilla dimensions.
