# Dimensional-Sable — cross-dimension sub-level warping

[Dimensional-Sable](https://modrinth.com/mod/dimensional-sable)
(`dev.egg.dimensionalsable`) extends Sable to teleport connected sub-levels
and nearby entities between dimensions.

## Warping API

`SubLevelWarper.warpConnectedSubLevels(ServerSubLevel, ServerLevel, Vector3d,
Quaterniond)` returns the number of sub-levels warped. The position is the
destination world position; the quaternion is a rotation transform applied to
the existing orientation and velocities. Same-dimension requests and missing
sub-level containers return zero.

The `/northstar warp <sublevel> <dimension> <x> <y> <z>` command uses this API.
Its Minecraft `DimensionArgument` supports loaded vanilla and modded dimensions.

## Rocket station

The station checks upward velocity and the configured atmosphere height in its
Sable physics callback, then queues a warp. Speed limiting happens on the
thruster side instead: active thrusters taper their impulse as the ship
approaches the configured `maxVelocity` (blocks/second), so the station itself
does not modify the body's velocity. `ServerTickEvent.Post` processes
the queue after the server's level ticks, avoiding removal of the active
physics body inside a physics callback. `enqueueWarp` cancels the request and
logs `[ROCKET WARP CANCELLED]` when the destination dimension equals the
ship's current dimension. The destination dimension comes from the configured
`RocketDestination`; the station currently constructs a target at X/Z zero and
the configured atmosphere height. The utility centers a target block position,
or retains the ship's current position when none is supplied.

## Warp sequence

`SublevelDimensionalTeleportUtils` stages the transfer so nothing is captured or
moved while the ship is still flying:

1. **Decelerate** — `enqueueWarp` only records the original velocities and
   starts a `SlowingWarp`. Each server tick `decelerateShip` reduces the linear
   and angular speed of every connected body by
   `contraption_warpDeceleration` (blocks/second², with the same value applied
   to angular motion in rad/s², converted from per-second to per-tick).
2. **Halt and freeze** — once every body is within `STOP_EPSILON`,
   `completeSlowingWarp` freezes the group with `freezeConnectedSubLevels`, then
   captures inhabitants (the first player/entity tracking in the sequence), and
   performs the dimension transfer.
3. **Warp and recover** — the destination bodies are locked with fixed
   constraints while entities, mounts and seats are recovered.
4. **Ramp** — when the recovery hold releases, the locks are removed and each
   body's velocity is raised from zero toward its saved (and, for flipping
   rockets, rotated) velocity at `contraption_warpAcceleration`
   (blocks/second², angular in rad/s²). Once the target is reached the body is
   dropped from the ramp and the ship runs with no further interference.

Both rates live in the `rocket contraption` section of `ServerConfig`
(`warpDeceleration`, default `20`; `warpAcceleration`, default `10`).
While a body is slowing, frozen or ramping, `isVelocityControlled(subLevel)` is
true; thrusters, reaction wheels and the rocket station skip velocity work for
those bodies.

When requested, the utility supplies a 180° rotation about an axis perpendicular
to the rocket's current up direction. Dimensional-Sable applies that rotation
to orientation and velocity. Northstar uses the same rotation for the ramp
target, so the ship resumes its original motion in the new dimension.

## Recovery hold

Immediately before warping, `freezeConnectedSubLevels` saves and zeros each
connected body's linear and angular velocity. Each frozen body's UUID is kept
in the utility's frozen set, so `isFrozen(subLevel)` lets physics actors (such
as the rocket station) skip velocity changes while the hold owns the body.
If the warp does not happen, `abortWarp` clears the frozen flags and starts a
velocity ramp back to the original velocities instead of snapping them back.
Immediately after reconstruction, each destination body is
attached to the world using a temporary fixed physics constraint. This keeps
thrust and gravity from moving the ship during inhabitant recovery. Seat and
positional recovery continue while the hold is active. Held ships do not
enqueue another automatic warp.

The hold lasts at least 40 ticks. Every captured player must exist in the
destination and have their saved mount restored, when applicable. Other
inhabitants are given up to 100 ticks; they cannot hold the ship indefinitely.
An unresolved player keeps the ship held, with status printed every 20 ticks.
On release the constraints are removed, the bodies are unfrozen, and a velocity
ramp is started from rest toward the saved rotated velocities. Diagnostics:
`[ROCKET WARP SLOWING]`, `[ROCKET HOLD BEFORE WARP]`, `[ROCKET HOLD AFTER WARP]`,
`[ROCKET HOLD WAITING]`, `[ROCKET HOLD RELEASED]`, `[ROCKET VELOCITY RAMP START]`,
`[ROCKET VELOCITY RAMP COMPLETE]`, and `[ROCKET WARP FAILED]`.
All warp state (pending slowdowns, holds, recovery queues, ramps, and frozen
ships) is cleared when the server starts and stops.

## Inhabitant transfer and limitations

Create block seats are rebound by location after reconstruction: capture the
containing sub-level UUID and seat entity position relative to its plot center.
In the destination, resolve the rebuilt sub-level, add that offset to its new
plot center, verify a `SeatBlock`, and reuse the seat entity there or create one
before restoring the passenger. Old seat UUIDs are not required for this path.
Seat entities are excluded from world-space positional recovery; moving them
out of the plot would cause their seat-block validation to fail. Diagnostics
use `[ROCKET SEAT CAPTURE]` and `[ROCKET SEAT RESOLVED]`.
Capture falls back to the seat's block position when entity containment is
unavailable. `[ROCKET SEAT CAPTURE FAILED]` reports missing source containment;
`[ROCKET SEAT LOOKUP FAILED]` distinguishes a missing saved anchor, missing
destination sub-level, and a destination block that is not a seat.

Northstar excludes `create:super_glue` and `simulated:honey_glue` from its
inhabitant manifest and recovery. These structural entities must remain under
the library's plot-transfer handling rather than being repositioned in world
space by Northstar's recovery loop.

`SubLevelWarper` first reconstructs every connected sub-level in the destination.
For each source sub-level it selects entities intersecting its world bounding
box inflated by one block, excludes passengers from the initial selection, then
recursively includes passengers of selected vehicles. It transfers them with
`Entity.teleportTo`, remembers vehicle/passenger UUIDs, and attempts to restore
the riding relationships. It deduplicates initially selected entities across
the connected group.

An entity outside those bounds, or a passenger whose vehicle is outside them,
can be missed. Once a queued warp's ship has come to a full stop, Northstar
snapshots loaded entities that Sable tracks on any connected sub-level and
entities in Dimensional-Sable's scan bounds, including their complete vehicle
trees, world-relative positions, and rocket contraption seat indices or virtual
-seat offsets. It logs each saved inhabitant and each fallback transfer. After a
successful warp it resolves each UUID in the destination, transfers missing
entities that remain in the source, and attempts to restore their riding
relationships. Entities that are neither tracked nor inside Dimensional-Sable's
bounds cannot be identified reliably as inhabitants; unloaded, removed, or
non-transferable entities and rejected mounts cannot be guaranteed. Failures are
logged. Rocket contraption seats use Create's seat mapping and synchronize
virtual-seat offsets; other vehicle types use normal `startRiding` and may
require additional mod-specific metadata.

After a successful warp, saved mounts are checked for 40 server ticks. Missing
mounts are retried, and seated players receive the vehicle passenger packet
again while destination tracking initializes. Sneaking or riding a different
vehicle cancels the check. The final verification logs whether the passenger
and vehicle exist in the destination and whether the mount is restored. Ship
motion is held stationary until recovery finishes and then ramped back up.

All captured inhabitants, including entities already transferred by
Dimensional-Sable, receive 40 ticks of UUID-based recovery checks across loaded
server dimensions. Unmounted entities in the wrong dimension or more than eight
blocks from their saved ship-local position are teleported to that position on
the destination ship's current pose. Mounted entities use seat restoration.
Sneaking cancels positional recovery. The manifest cannot recover an entity
that was never captured, or recreate a removed entity.

Console diagnostics use these prefixes: `[ROCKET BEFORE SUMMARY]` and
`[ROCKET BEFORE]` list the capture manifest; `[ROCKET PLAYER CAPTURE AUDIT]`
includes both selected and excluded source players; `[ROCKET AFTER
DIMENSIONAL-SABLE]` reports source/destination UUID lookups for every captured
entity; `[ROCKET AFTER RECOVERY]` reports initial fallback results; `[ROCKET
RECOVERY CHECK]`, `[ROCKET RECOVER ENTITY]`, and `[ROCKET FINAL ENTITY]` report
the subsequent checks, corrections, and final state. Entries include names,
UUIDs, entity types, positions, dimensions, and current vehicles.
