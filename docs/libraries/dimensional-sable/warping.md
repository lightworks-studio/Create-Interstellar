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
Sable physics callback, then queues a warp. `ServerTickEvent.Post` processes
the queue after the server's level ticks, avoiding removal of the active
physics body inside a physics callback. The destination dimension comes from
the configured `RocketDestination`; the station currently constructs a target
at X/Z zero and the configured atmosphere height. The utility centers a target
block position, or retains the ship's current position when none is supplied.

When requested, the utility supplies a 180° rotation about an axis perpendicular
to the rocket's current up direction. Dimensional-Sable applies that rotation
to orientation and velocity; Northstar does not currently zero velocity.

## Inhabitant transfer and limitations

Create block seats are rebound by location after reconstruction: capture the
containing sub-level UUID and seat entity position relative to its plot center.
In the destination, resolve the rebuilt sub-level, add that offset to its new
plot center, verify a `SeatBlock`, and reuse the seat entity there or create one
before restoring the passenger. Old seat UUIDs are not required for this path.
Seat entities are excluded from world-space positional recovery; moving them
out of the plot would cause their seat-block validation to fail. Diagnostics
use `[ROCKET SEAT CAPTURE]` and `[ROCKET SEAT RESOLVED]`.

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
can be missed. Northstar snapshots loaded entities that Sable tracks on any
connected sub-level and entities in Dimensional-Sable's scan bounds at enqueue
time, including their complete vehicle trees, world-relative positions, and
rocket contraption seat indices or virtual-seat offsets. It logs each saved
inhabitant and each fallback transfer. After a successful warp it resolves each UUID in the
destination, transfers missing entities that remain in the source, and attempts
to restore their riding relationships. Entities that are neither tracked nor
inside Dimensional-Sable's bounds cannot be identified reliably as inhabitants;
unloaded, removed, or non-transferable entities and rejected mounts cannot be
guaranteed. Failures are logged. Rocket contraption seats use Create's seat
mapping and synchronize virtual-seat offsets; other vehicle types use normal
`startRiding` and may require additional mod-specific metadata.

After a successful warp, saved mounts are checked for 40 server ticks. Missing
mounts are retried, and seated players receive the vehicle passenger packet
again while destination tracking initializes. Sneaking or riding a different
vehicle cancels the check. The final verification logs whether the passenger
and vehicle exist in the destination and whether the mount is restored. This
does not pause ship motion during loading.

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
