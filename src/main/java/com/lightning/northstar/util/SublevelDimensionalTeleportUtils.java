package com.lightning.northstar.util;

import com.lightning.northstar.config.NorthstarConfigs;
import com.lightning.northstar.contraption.rocket.RocketDestination;
import com.lightning.northstar.contraption.rocket.RocketContraptionEntity;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import dev.egg.dimensionalsable.SubLevelWarper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.lightning.northstar.Northstar.LOGGER;

/**
 * Drives cross-dimension warp sequences for Sable sub-levels.
 *
 * <p>The sequence is deliberately staged so that no entity tracking or dimension transfer happens
 * while the ship is still moving:</p>
 *
 * <ol>
 *     <li><b>Decelerate</b>: every connected body is slowed at
 *         {@code contraption_warpDeceleration} until it is completely at rest.</li>
 *     <li><b>Freeze</b>: velocities are zeroed and the bodies are flagged so other actors stop
 *         applying forces.</li>
 *     <li><b>Warp</b>: inhabitants are captured, the sub-levels are moved, and the destination
 *         bodies are locked with fixed constraints while entities and seats are recovered.</li>
 *     <li><b>Ramp</b>: once every inhabitant is recovered the bodies are released and their
 *         velocity is restored gradually at {@code contraption_warpAcceleration}, after which the
 *         ship runs with no interference from this utility.</li>
 * </ol>
 */
public class SublevelDimensionalTeleportUtils {

    /** Linear speed (blocks/second) and angular speed (radians/second) below which a body is at rest. */
    private static final double STOP_EPSILON = 1.0e-3d;
    /** Minecraft's nominal server tick rate, used to convert config rates from per-second to per-tick. */
    private static final double TICKS_PER_SECOND = 20.0d;
    private static final int MIN_HOLD_TICKS = 40;
    private static final int MAX_HOLD_TICKS = 100;
    private static final int RECOVERY_ATTEMPTS = 40;

    // ---------------------------------------------------------------------------------------------
    // Saved state records
    // ---------------------------------------------------------------------------------------------

    private record Inhabitant(Entity entity, Vec3 relativePosition, UUID vehicle,
                              RocketContraptionEntity.WarpSeat rocketSeat, SeatAnchor anchor) {
    }

    private record SeatAnchor(UUID ship, Vec3 plotOffset) {
    }

    private record PendingSeat(ServerLevel level, UUID passenger, UUID vehicle,
                               RocketContraptionEntity.WarpSeat seat, SeatAnchor anchor, int attempts) {
    }

    private record InhabitantRecovery(ServerLevel level, UUID ship, UUID inhabitant, Vec3 localPosition, int attempts) {
    }

    /** A destination body held in place by a fixed constraint while entities are recovered. */
    private record HeldBody(ServerSubLevel ship, FixedConstraintHandle lock, Vector3d linear, Vector3d angular) {
    }

    /** A frozen ship whose inhabitants are still being recovered in the destination dimension. */
    private record RecoveryHold(ServerLevel level, List<HeldBody> bodies, List<Inhabitant> inhabitants, int ticks) {
    }

    /** A body whose velocity was zeroed by {@link #freezeConnectedSubLevels(ServerSubLevel)}. */
    private record FrozenBody(ServerSubLevel subLevel, Vector3d linear, Vector3d angular) {
    }

    /** Velocity captured when a warp is requested; used as the post-warp ramp target. */
    private record SavedVelocity(Vector3d linear, Vector3d angular) {
    }

    /** A released destination body whose velocity is being ramped back to its target. */
    private record RampBody(ServerSubLevel ship, Vector3d linear, Vector3d angular) {
    }

    private record VelocityRamp(ServerLevel level, List<RampBody> bodies) {
    }

    /** A warp waiting for its ship to coast to a halt before the dimension transfer starts. */
    private static final class SlowingWarp {
        final TeleportProperties properties;
        final List<ServerSubLevel> group;
        final Map<UUID, SavedVelocity> targets;

        SlowingWarp(TeleportProperties properties, List<ServerSubLevel> group,
                    Map<UUID, SavedVelocity> targets) {
            this.properties = properties;
            this.group = group;
            this.targets = targets;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    private static final Queue<PendingSeat> PENDING_SEATS = new ConcurrentLinkedQueue<>();
    private static final Queue<InhabitantRecovery> INHABITANT_RECOVERY = new ConcurrentLinkedQueue<>();
    private static final Queue<RecoveryHold> HOLDS = new ConcurrentLinkedQueue<>();
    private static final Queue<VelocityRamp> VELOCITY_RAMPS = new ConcurrentLinkedQueue<>();

    /** Warps currently decelerating their ship, keyed by root ship UUID. */
    private static final Map<UUID, SlowingWarp> SLOWING_WARPS = new ConcurrentHashMap<>();

    /** Sub-levels whose velocity is owned by a warp freeze and must not be modified until release. */
    private static final Set<UUID> FROZEN_SHIPS = ConcurrentHashMap.newKeySet();

    /** Sub-levels whose velocity is currently being decelerated by a pending warp. */
    private static final Set<UUID> SLOWING_SHIPS = ConcurrentHashMap.newKeySet();

    /** Sub-levels whose velocity is currently being ramped back up after a warp. */
    private static final Set<UUID> RAMPING_SHIPS = ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    /**
     * @return whether the given sub-level is frozen by an active warp or recovery hold. Physics
     * actors must not modify the velocity of a frozen body.
     */
    public static boolean isFrozen(ServerSubLevel subLevel) {
        return FROZEN_SHIPS.contains(subLevel.getUniqueId());
    }

    /**
     * @return whether this utility currently owns the sub-level's velocity (slowing, frozen, or
     * ramping). Actors should skip thrust, torque and other velocity changes while this is true.
     */
    public static boolean isVelocityControlled(ServerSubLevel subLevel) {
        UUID id = subLevel.getUniqueId();
        return FROZEN_SHIPS.contains(id) || SLOWING_SHIPS.contains(id) || RAMPING_SHIPS.contains(id);
    }

    /** Clears all per-server warp state so stale holds or flags cannot leak between worlds. */
    public static void reset() {
        PENDING_SEATS.clear();
        INHABITANT_RECOVERY.clear();
        HOLDS.clear();
        VELOCITY_RAMPS.clear();
        SLOWING_WARPS.clear();
        FROZEN_SHIPS.clear();
        SLOWING_SHIPS.clear();
        RAMPING_SHIPS.clear();
    }

    /**
     * Requests a warp. The ship is first decelerated to a full stop; entity capture and the actual
     * dimension transfer only happen once that slowdown completes.
     */
    public static void enqueueWarp(TeleportProperties properties) {
        ServerSubLevel ship = properties.subLevel();
        ServerLevel source = ship.getLevel();

        if (!hasValidDestination(properties, source)) {
            return;
        }
        if (isVelocityControlled(ship) || isShipInHold(ship)) {
            return;
        }

        List<ServerSubLevel> group = SubLevelWarper.getSubLevelGroup(ship);
        SlowingWarp warp = new SlowingWarp(properties, group, captureVelocities(group));
        if (SLOWING_WARPS.putIfAbsent(ship.getUniqueId(), warp) != null) {
            return;
        }

        for (ServerSubLevel member : group) {
            SLOWING_SHIPS.add(member.getUniqueId());
        }
        LOGGER.info("[ROCKET WARP SLOWING] ship={} source={} destination={} bodies={}",
                ship.getUniqueId(), source.dimension().location(),
                properties.destination().dim(), group.size());
    }

    /** Runs once per server tick after the world has ticked. */
    public static void processDeferredWarps() {
        recoverInhabitants();
        retrySeats();
        updateHolds();
        tickVelocityRamps();
        tickSlowingWarps();
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 1 — decelerate to a halt
    // ---------------------------------------------------------------------------------------------

    private static boolean hasValidDestination(TeleportProperties properties, ServerLevel source) {
        if (properties.destination() == null || properties.destination().dim() == null) {
            LOGGER.warn("[ROCKET WARP CANCELLED] Destination is null");
            return false;
        }
        ResourceLocation destinationDimension = properties.destination().dim();
        if (source.dimension().location().equals(destinationDimension)) {
            LOGGER.warn("[ROCKET WARP CANCELLED] ship={} reason=destination_matches_current dimension={}",
                    properties.subLevel().getUniqueId(), destinationDimension);
            return false;
        }
        return true;
    }

    private static boolean isShipInHold(ServerSubLevel ship) {
        return HOLDS.stream().anyMatch(hold ->
                hold.bodies().stream().anyMatch(body -> body.ship() == ship));
    }

    private static boolean isHeld(ServerLevel level) {
        return HOLDS.stream().anyMatch(hold -> hold.level() == level);
    }

    /** Captures the current velocities of every connected body before the slowdown starts. */
    private static Map<UUID, SavedVelocity> captureVelocities(List<ServerSubLevel> group) {
        Map<UUID, SavedVelocity> velocities = new LinkedHashMap<>();
        for (ServerSubLevel member : group) {
            RigidBodyHandle handle = RigidBodyHandle.of(member);
            if (handle == null || !handle.isValid()) {
                continue;
            }
            velocities.put(member.getUniqueId(), new SavedVelocity(
                    handle.getLinearVelocity(new Vector3d()),
                    handle.getAngularVelocity(new Vector3d())));
        }
        return velocities;
    }

    private static void tickSlowingWarps() {
        for (Map.Entry<UUID, SlowingWarp> entry : SLOWING_WARPS.entrySet()) {
            SlowingWarp warp = entry.getValue();
            if (warp.properties.subLevel().isRemoved() || decelerateShip(warp)) {
                if (SLOWING_WARPS.remove(entry.getKey(), warp)) {
                    completeSlowingWarp(warp);
                }
            }
        }
    }

    /**
     * Reduces the linear and angular velocity of every connected body by at most one tick's worth
     * of the configured deceleration.
     *
     * @return {@code true} when the whole group has come to rest
     */
    private static boolean decelerateShip(SlowingWarp warp) {
        double maxDelta = perTick(NorthstarConfigs.server().contraption_warpDeceleration.getF());
        boolean stopped = true;

        for (ServerSubLevel member : warp.group) {
            if (member.isRemoved()) {
                continue;
            }
            RigidBodyHandle handle = RigidBodyHandle.of(member);
            if (handle == null || !handle.isValid()) {
                continue;
            }

            Vector3d linearDelta = new Vector3d();
            Vector3d angularDelta = new Vector3d();
            boolean linearStopped = reduceVelocityTowardZero(
                    handle.getLinearVelocity(new Vector3d()), maxDelta, linearDelta);
            boolean angularStopped = reduceVelocityTowardZero(
                    handle.getAngularVelocity(new Vector3d()), maxDelta, angularDelta);

            stopped &= linearStopped && angularStopped;
            if (linearDelta.lengthSquared() > 0.0d || angularDelta.lengthSquared() > 0.0d) {
                handle.addLinearAndAngularVelocity(linearDelta, angularDelta);
            }
        }
        return stopped;
    }

    /**
     * Computes the velocity delta that moves {@code velocity} toward zero by at most
     * {@code maxDelta} and writes it into {@code deltaOut}.
     *
     * @return {@code true} if the body is (or has just become) at rest
     */
    private static boolean reduceVelocityTowardZero(Vector3d velocity, double maxDelta, Vector3d deltaOut) {
        double speed = velocity.length();
        if (speed <= STOP_EPSILON) {
            velocity.negate(deltaOut);
            return true;
        }

        double targetSpeed = Math.max(0.0d, speed - maxDelta);
        new Vector3d(velocity).mul(targetSpeed / speed, deltaOut).sub(velocity);
        return targetSpeed <= 0.0d;
    }

    /** Called once the ship is fully stopped: capture inhabitants, freeze, and perform the warp. */
    private static void completeSlowingWarp(SlowingWarp warp) {
        clearSlowingShips(warp.group);

        ServerSubLevel ship = warp.properties.subLevel();
        if (ship.isRemoved()) {
            LOGGER.warn("[ROCKET WARP ABORTED] reason=ship_removed");
            return;
        }

        ServerLevel source = ship.getLevel();
        ServerLevel destination = source.getServer().getLevel(warp.properties.destination().dimKey());
        if (destination == null) {
            LOGGER.warn("[ROCKET WARP ABORTED] ship={} reason=destination_not_loaded destination={}",
                    ship.getUniqueId(), warp.properties.destination().dim());
            startVelocityRamp(source, rampBodiesFromTargets(warp.group, warp.targets));
            return;
        }

        List<FrozenBody> frozen = freezeConnectedSubLevels(ship);
        List<ServerSubLevel> frozenGroup = frozen.stream().map(FrozenBody::subLevel).toList();
        List<Inhabitant> inhabitants = captureInhabitants(ship, frozenGroup);
        performWarp(warp.properties, inhabitants, warp.targets, frozen, destination);
    }

    private static void clearSlowingShips(List<ServerSubLevel> group) {
        for (ServerSubLevel member : group) {
            SLOWING_SHIPS.remove(member.getUniqueId());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 2 — freeze and warp
    // ---------------------------------------------------------------------------------------------

    /**
     * Saves and zeroes the linear and angular velocity of every sub-level connected to the given
     * ship, marking them as frozen until the recovery hold releases them.
     */
    private static List<FrozenBody> freezeConnectedSubLevels(ServerSubLevel ship) {
        List<FrozenBody> frozen = new ArrayList<>();
        for (ServerSubLevel member : SubLevelWarper.getSubLevelGroup(ship)) {
            if (member.isRemoved()) {
                continue;
            }
            RigidBodyHandle handle = RigidBodyHandle.of(member);
            if (handle == null || !handle.isValid()) {
                LOGGER.warn("[ROCKET HOLD SKIPPED] ship={} reason=invalid_body", member.getUniqueId());
                continue;
            }

            Vector3d linear = handle.getLinearVelocity(new Vector3d());
            Vector3d angular = handle.getAngularVelocity(new Vector3d());
            handle.addLinearAndAngularVelocity(new Vector3d(linear).negate(), new Vector3d(angular).negate());

            FROZEN_SHIPS.add(member.getUniqueId());
            frozen.add(new FrozenBody(member, linear, angular));
        }
        LOGGER.info("[ROCKET HOLD BEFORE WARP] bodies={}",
                frozen.stream().map(body -> body.subLevel().getUniqueId()).toList());
        return frozen;
    }

    private static void performWarp(TeleportProperties properties, List<Inhabitant> inhabitants,
                                    Map<UUID, SavedVelocity> targets, List<FrozenBody> frozen,
                                    ServerLevel destination) {
        ServerSubLevel ship = properties.subLevel();
        ServerLevel source = ship.getLevel();
        Vector3d destinationPosition = computeDestinationPosition(properties);
        Quaterniond rotation = computeWarpRotation(properties);
        Map<UUID, SavedVelocity> rotatedTargets = rotateTargets(targets, rotation);

        int warped = SubLevelWarper.warpConnectedSubLevels(ship, destination, destinationPosition, rotation);
        LOGGER.info("[ROCKET WARP RESULT] ship={} warped={} expectedInhabitants={}",
                ship.getUniqueId(), warped, inhabitants.size());

        if (warped <= 0) {
            abortWarp(ship, frozen, targets);
            return;
        }

        List<HeldBody> held = lockDestinationBodies(destination, frozen, rotatedTargets);
        HOLDS.add(new RecoveryHold(destination, held, inhabitants, 0));
        LOGGER.info("[ROCKET HOLD AFTER WARP] bodies={} inhabitants={}", held.size(), inhabitants.size());

        Map<UUID, Entity> transferred = transferMissingInhabitants(properties, inhabitants, destination,
                destinationPosition, rotation);
        scheduleInhabitantRecovery(properties, inhabitants, destination);
        restoreSeats(inhabitants, destination, transferred);
    }

    private static Vector3d computeDestinationPosition(TeleportProperties properties) {
        // Destination waypoint center, or the ship's current position if no waypoint is set.
        return properties.destination().pos() != null
                ? JOMLConversion.atCenterOf(properties.destination().pos())
                : new Vector3d(properties.subLevel().logicalPose().position());
    }

    /**
     * Builds the rotation applied to the ship and its velocities. A flipping rocket rotates 180°
     * about an axis perpendicular to its current up direction so it emerges nose-down.
     */
    private static Quaterniond computeWarpRotation(TeleportProperties properties) {
        Quaterniond rotation = new Quaterniond();
        if (!properties.flipOrientation()) {
            return rotation;
        }

        Quaterniond orientation = properties.subLevel().logicalPose().orientation();
        Vector3d forward = orientation.transform(new Vector3d(0, 1, 0));
        Vector3d referenceAxis = Math.abs(forward.y) > 0.99d
                ? new Vector3d(1, 0, 0)
                : new Vector3d(0, 1, 0);
        Vector3d cross = new Vector3d().cross(forward, referenceAxis);
        Vector3d flipAxis = cross.lengthSquared() < 1.0e-6d
                ? new Vector3d(1, 0, 0)
                : cross.normalize();
        rotation.fromAxisAngleRad(flipAxis, Math.PI);
        return rotation;
    }

    private static Map<UUID, SavedVelocity> rotateTargets(Map<UUID, SavedVelocity> targets,
                                                          Quaterniond rotation) {
        Map<UUID, SavedVelocity> rotated = new LinkedHashMap<>();
        targets.forEach((id, velocity) -> rotated.put(id, new SavedVelocity(
                rotation.transform(new Vector3d(velocity.linear())),
                rotation.transform(new Vector3d(velocity.angular())))));
        return rotated;
    }

    /** Creates a fixed constraint per destination body so recovery cannot move the wreck. */
    private static List<HeldBody> lockDestinationBodies(ServerLevel destination, List<FrozenBody> frozen,
                                                        Map<UUID, SavedVelocity> targets) {
        List<HeldBody> held = new ArrayList<>();
        var container = SubLevelContainer.getContainer(destination);

        for (FrozenBody frozenBody : frozen) {
            UUID id = frozenBody.subLevel().getUniqueId();
            ServerSubLevel member = (ServerSubLevel) container.getSubLevel(id);
            if (member == null) {
                // A partial warp left this body behind; never let it stay frozen forever.
                LOGGER.warn("[ROCKET HOLD SKIPPED] ship={} reason=missing_from_destination", id);
                FROZEN_SHIPS.remove(id);
                continue;
            }
            SavedVelocity target = targets.getOrDefault(id,
                    new SavedVelocity(frozenBody.linear(), frozenBody.angular()));

            Vector3d position = new Vector3d(member.logicalPose().position());
            Vector3d local = member.logicalPose().transformPositionInverse(new Vector3d(position));
            FixedConstraintHandle lock = SubLevelPhysicsSystem.get(destination).getPipeline()
                    .addConstraint(null, member, new FixedConstraintConfiguration(
                            position, local, new Quaterniond(member.logicalPose().orientation())));
            if (lock == null) {
                LOGGER.warn("[ROCKET HOLD SKIPPED] ship={} reason=lock_failed", id);
                FROZEN_SHIPS.remove(id);
                continue;
            }
            held.add(new HeldBody(member, lock, target.linear(), target.angular()));
        }
        return held;
    }

    /** The warp did not happen: unfreeze and ramp the bodies back to their original velocities. */
    private static void abortWarp(ServerSubLevel ship, List<FrozenBody> frozen,
                                  Map<UUID, SavedVelocity> targets) {
        List<ServerSubLevel> group = new ArrayList<>();
        for (FrozenBody body : frozen) {
            FROZEN_SHIPS.remove(body.subLevel().getUniqueId());
            group.add(body.subLevel());
        }
        startVelocityRamp(ship.getLevel(), rampBodiesFromTargets(group, targets));
        LOGGER.warn("[ROCKET WARP FAILED] ship={} restored={}", ship.getUniqueId(), frozen.size());
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 3 — recover inhabitants, seats and entities
    // ---------------------------------------------------------------------------------------------

    private static List<Inhabitant> captureInhabitants(ServerSubLevel ship, List<ServerSubLevel> group) {
        ServerLevel source = ship.getLevel();
        Vec3 origin = JOMLConversion.toMojang(ship.logicalPose().position());
        Map<UUID, Inhabitant> inhabitants = new LinkedHashMap<>();

        for (Entity entity : source.getAllEntities()) {
            if (group.contains(Sable.HELPER.getTrackingSubLevel(entity))) {
                captureTree(entity.getRootVehicle(), source, origin, inhabitants);
            }
        }
        for (ServerSubLevel member : group) {
            for (Entity entity : source.getEntities(null, member.boundingBox().toMojang().inflate(1.0d))) {
                captureTree(entity.getRootVehicle(), source, origin, inhabitants);
            }
        }

        LOGGER.info("[ROCKET BEFORE SUMMARY] ship={} source={} inhabitants={}",
                ship.getUniqueId(), source.dimension().location(), inhabitants.size());
        for (Inhabitant saved : inhabitants.values()) {
            LOGGER.info("[ROCKET BEFORE] ship={} entity={} savedVehicle={} savedSeat={}",
                    ship.getUniqueId(), describe(saved.entity()), saved.vehicle(), saved.rocketSeat());
        }
        for (ServerPlayer player : source.players()) {
            LOGGER.info("[ROCKET PLAYER CAPTURE AUDIT] ship={} included={} player={} tracking={}",
                    ship.getUniqueId(), inhabitants.containsKey(player.getUUID()), describe(player),
                    Sable.HELPER.getTrackingSubLevel(player));
        }
        return List.copyOf(inhabitants.values());
    }

    private static boolean shouldCaptureEntity(Entity entity) {
        var type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (type.toString().equals("create:super_glue") || type.toString().equals("simulated:honey_glue")) {
            return false;
        }
        return entity instanceof LivingEntity; // TODO: Expand support for other entities such as boats
    }

    private static void captureTree(Entity entity, ServerLevel source, Vec3 origin,
                                    Map<UUID, Inhabitant> inhabitants) {
        if (!shouldCaptureEntity(entity) || inhabitants.containsKey(entity.getUUID())) {
            return;
        }

        Vec3 worldPosition = Sable.HELPER.projectOutOfSubLevel(source, entity.position());
        Entity vehicle = entity.getVehicle();
        SeatAnchor anchor = captureSeatAnchor(vehicle, source);
        RocketContraptionEntity.WarpSeat rocketSeat = vehicle instanceof RocketContraptionEntity rocket
                ? rocket.captureWarpSeat(entity)
                : null;

        LOGGER.info("Northstar saving rocket inhabitant {} ({}) with vehicle {} and seat {} before warp",
                entity.getUUID(), entity.getType(), vehicle == null ? null : vehicle.getUUID(), rocketSeat);
        inhabitants.put(entity.getUUID(), new Inhabitant(entity, worldPosition.subtract(origin),
                vehicle == null ? null : vehicle.getUUID(), rocketSeat, anchor));
        for (Entity passenger : entity.getPassengers()) {
            captureTree(passenger, source, origin, inhabitants);
        }
    }

    private static SeatAnchor captureSeatAnchor(Entity vehicle, ServerLevel source) {
        if (!(vehicle instanceof SeatEntity)) {
            return null;
        }
        var containing = Sable.HELPER.getContaining(vehicle);
        if (containing == null) {
            containing = Sable.HELPER.getContaining(source, vehicle.blockPosition());
        }
        if (containing == null) {
            LOGGER.warn("[ROCKET SEAT CAPTURE FAILED] vehicle={} reason=source_sublevel_missing",
                    describe(vehicle));
            return null;
        }

        SeatAnchor anchor = new SeatAnchor(containing.getUniqueId(),
                vehicle.position().subtract(Vec3.atLowerCornerOf(containing.getPlot().getCenterBlock())));
        LOGGER.info("[ROCKET SEAT CAPTURE] ship={} plotOffset={}", anchor.ship(), anchor.plotOffset());
        return anchor;
    }

    private static Map<UUID, Entity> transferMissingInhabitants(TeleportProperties properties,
                                                                List<Inhabitant> inhabitants,
                                                                ServerLevel destination,
                                                                Vector3d destinationPosition,
                                                                Quaterniond rotation) {
        Map<UUID, Entity> transferred = new LinkedHashMap<>();
        ServerLevel source = properties.subLevel().getLevel();

        for (Inhabitant inhabitant : inhabitants) {
            Entity entity = inhabitant.entity();
            if (entity instanceof SeatEntity) {
                continue; // Rebind seats by plot location, never move them into world space.
            }

            Entity existing = destination.getEntity(entity.getUUID());
            LOGGER.info("[ROCKET AFTER DIMENSIONAL-SABLE] ship={} beforeName={} uuid={} destinationEntity={} sourceEntity={}",
                    properties.subLevel().getUniqueId(), entity.getName().getString(), entity.getUUID(),
                    describe(existing), describe(source.getEntity(entity.getUUID())));
            if (existing != null) {
                transferred.put(entity.getUUID(), existing);
                continue;
            }
            if (entity.isRemoved() || entity.level() != source) {
                LOGGER.warn("Could not locate rocket inhabitant {} after warp", entity.getUUID());
                continue;
            }

            LOGGER.info("Northstar transferring missed rocket inhabitant {} ({}) from {} to {}",
                    entity.getUUID(), entity.getType(), entity.level().dimension().location(),
                    destination.dimension().location());
            entity.stopRiding();
            Vector3d offset = rotation.transform(new Vector3d(inhabitant.relativePosition().x,
                    inhabitant.relativePosition().y, inhabitant.relativePosition().z));
            Vec3 target = new Vec3(destinationPosition.x + offset.x,
                    destinationPosition.y + offset.y, destinationPosition.z + offset.z);
            Entity moved = entity.changeDimension(new DimensionTransition(destination, target,
                    entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(), DimensionTransition.DO_NOTHING));
            if (moved == null) {
                LOGGER.warn("Could not transfer rocket inhabitant {}", entity.getUUID());
            } else {
                transferred.put(entity.getUUID(), moved);
            }
        }
        return transferred;
    }

    private static void scheduleInhabitantRecovery(TeleportProperties properties, List<Inhabitant> inhabitants,
                                                   ServerLevel destination) {
        for (Inhabitant inhabitant : inhabitants) {
            LOGGER.info("[ROCKET AFTER RECOVERY] ship={} name={} uuid={} entity={}",
                    properties.subLevel().getUniqueId(), inhabitant.entity().getName().getString(),
                    inhabitant.entity().getUUID(), describe(destination.getEntity(inhabitant.entity().getUUID())));
            if (inhabitant.entity() instanceof SeatEntity) {
                continue;
            }

            Vec3 sourcePosition = JOMLConversion.toMojang(properties.subLevel().logicalPose().position())
                    .add(inhabitant.relativePosition());
            Vec3 local = properties.subLevel().logicalPose().transformPositionInverse(sourcePosition);
            INHABITANT_RECOVERY.add(new InhabitantRecovery(destination, properties.subLevel().getUniqueId(),
                    inhabitant.entity().getUUID(), local, RECOVERY_ATTEMPTS));
        }
    }

    private static void restoreSeats(List<Inhabitant> inhabitants, ServerLevel destination,
                                     Map<UUID, Entity> transferred) {
        for (Inhabitant inhabitant : inhabitants) {
            if (inhabitant.vehicle() == null) {
                continue;
            }
            PENDING_SEATS.add(new PendingSeat(destination, inhabitant.entity().getUUID(),
                    inhabitant.vehicle(), inhabitant.rocketSeat(), inhabitant.anchor(), RECOVERY_ATTEMPTS));

            Entity passenger = transferred.get(inhabitant.entity().getUUID());
            Entity vehicle = resolveSeat(destination, inhabitant.vehicle(), inhabitant.anchor());
            if (passenger == null || vehicle == null) {
                LOGGER.warn("Could not restore rocket passenger {} to missing vehicle {}",
                        inhabitant.entity().getUUID(), inhabitant.vehicle());
                continue;
            }

            boolean restored = vehicle instanceof RocketContraptionEntity rocket && inhabitant.rocketSeat() != null
                    ? rocket.restoreWarpSeat(passenger, inhabitant.rocketSeat())
                    : passenger.getVehicle() == vehicle || passenger.startRiding(vehicle, true);
            if (!restored) {
                LOGGER.warn("Could not restore rocket passenger {} to vehicle {}",
                        passenger.getUUID(), vehicle.getUUID());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 4 — release and ramp velocity back up
    // ---------------------------------------------------------------------------------------------

    private static void updateHolds() {
        int count = HOLDS.size();
        for (int i = 0; i < count; i++) {
            RecoveryHold hold = HOLDS.poll();
            if (hold == null) {
                break;
            }

            boolean playersReady = true;
            boolean othersReady = true;
            for (Inhabitant saved : hold.inhabitants()) {
                if (saved.entity() instanceof SeatEntity) {
                    continue;
                }
                Entity entity = hold.level().getEntity(saved.entity().getUUID());
                boolean ready = entity != null;
                if (ready && saved.vehicle() != null) {
                    Entity vehicle = resolveSeat(hold.level(), saved.vehicle(), saved.anchor());
                    ready = vehicle != null && entity.getVehicle() == vehicle;
                } else if (ready) {
                    Entity present = entity;
                    ready = hold.bodies().stream().anyMatch(body ->
                            Sable.HELPER.getTrackingSubLevel(present) == body.ship()
                                    || body.ship().boundingBox().toMojang().inflate(2)
                                    .intersects(present.getBoundingBox()));
                }
                if (saved.entity() instanceof ServerPlayer) {
                    playersReady &= ready;
                } else {
                    othersReady &= ready;
                }
            }

            if (hold.ticks() >= MIN_HOLD_TICKS && playersReady
                    && (othersReady || hold.ticks() >= MAX_HOLD_TICKS)) {
                releaseHold(hold, playersReady, othersReady);
            } else {
                if (hold.ticks() % 20 == 0) {
                    LOGGER.info("[ROCKET HOLD WAITING] playersReady={} othersReady={} ticks={}",
                            playersReady, othersReady, hold.ticks());
                }
                HOLDS.add(new RecoveryHold(hold.level(), hold.bodies(), hold.inhabitants(), hold.ticks() + 1));
            }
        }
    }

    /** Removes the destination locks, unfreezes the bodies and starts their post-warp ramp. */
    private static void releaseHold(RecoveryHold hold, boolean playersReady, boolean othersReady) {
        List<RampBody> ramps = new ArrayList<>();
        for (HeldBody body : hold.bodies()) {
            if (body.lock() != null && body.lock().isValid()) {
                body.lock().remove();
            }

            RigidBodyHandle handle = RigidBodyHandle.of(body.ship());
            if (!body.ship().isRemoved() && handle != null && handle.isValid()) {
                // The fixed constraint held the body in place; start the ramp from a full stop.
                handle.addLinearAndAngularVelocity(
                        handle.getLinearVelocity(new Vector3d()).negate(),
                        handle.getAngularVelocity(new Vector3d()).negate());
                ramps.add(new RampBody(body.ship(), body.linear(), body.angular()));
            }
            FROZEN_SHIPS.remove(body.ship().getUniqueId());
        }

        startVelocityRamp(hold.level(), ramps);
        LOGGER.info("[ROCKET HOLD RELEASED] playersReady={} othersReady={} ticks={}",
                playersReady, othersReady, hold.ticks());
    }

    private static List<RampBody> rampBodiesFromTargets(List<ServerSubLevel> group,
                                                        Map<UUID, SavedVelocity> targets) {
        List<RampBody> bodies = new ArrayList<>();
        for (ServerSubLevel member : group) {
            if (member.isRemoved()) {
                continue;
            }
            SavedVelocity target = targets.get(member.getUniqueId());
            if (target != null) {
                bodies.add(new RampBody(member, target.linear(), target.angular()));
            }
        }
        return bodies;
    }

    private static void startVelocityRamp(ServerLevel level, List<RampBody> bodies) {
        if (bodies.isEmpty()) {
            return;
        }
        for (RampBody body : bodies) {
            RAMPING_SHIPS.add(body.ship().getUniqueId());
        }
        VELOCITY_RAMPS.add(new VelocityRamp(level, bodies));
        LOGGER.info("[ROCKET VELOCITY RAMP START] level={} bodies={}",
                level.dimension().location(), bodies.size());
    }

    private static void tickVelocityRamps() {
        int count = VELOCITY_RAMPS.size();
        for (int i = 0; i < count; i++) {
            VelocityRamp ramp = VELOCITY_RAMPS.poll();
            if (ramp == null) {
                break;
            }

            double maxDelta = perTick(NorthstarConfigs.server().contraption_warpAcceleration.getF());
            boolean finished = true;
            for (RampBody body : ramp.bodies()) {
                finished &= rampBody(body, maxDelta);
            }

            if (finished) {
                for (RampBody body : ramp.bodies()) {
                    RAMPING_SHIPS.remove(body.ship().getUniqueId());
                }
                LOGGER.info("[ROCKET VELOCITY RAMP COMPLETE] level={} bodies={}",
                        ramp.level().dimension().location(), ramp.bodies().size());
            } else {
                VELOCITY_RAMPS.add(ramp);
            }
        }
    }

    /**
     * Moves one body's linear and angular velocity toward its target by at most {@code maxDelta}.
     *
     * @return {@code true} when the body has reached its target
     */
    private static boolean rampBody(RampBody body, double maxDelta) {
        RigidBodyHandle handle = RigidBodyHandle.of(body.ship());
        if (body.ship().isRemoved() || handle == null || !handle.isValid()) {
            return true;
        }

        Vector3d linearDelta = new Vector3d();
        Vector3d angularDelta = new Vector3d();
        boolean linearDone = approachVelocity(handle.getLinearVelocity(new Vector3d()),
                body.linear(), maxDelta, linearDelta);
        boolean angularDone = approachVelocity(handle.getAngularVelocity(new Vector3d()),
                body.angular(), maxDelta, angularDelta);

        if (linearDelta.lengthSquared() > 0.0d || angularDelta.lengthSquared() > 0.0d) {
            handle.addLinearAndAngularVelocity(linearDelta, angularDelta);
        }
        return linearDone && angularDone;
    }

    /**
     * Computes the velocity delta that moves {@code current} toward {@code target} by at most
     * {@code maxDelta} and writes it into {@code deltaOut}.
     *
     * @return {@code true} when the target is reached within this step
     */
    private static boolean approachVelocity(Vector3d current, Vector3d target, double maxDelta,
                                            Vector3d deltaOut) {
        new Vector3d(target).sub(current, deltaOut);
        double length = deltaOut.length();
        if (length <= maxDelta) {
            return true;
        }
        deltaOut.mul(maxDelta / length);
        return false;
    }

    private static double perTick(double perSecond) {
        return perSecond / TICKS_PER_SECOND;
    }

    // ---------------------------------------------------------------------------------------------
    // Ongoing recovery loops
    // ---------------------------------------------------------------------------------------------

    private static Entity resolveSeat(ServerLevel level, UUID oldVehicle, SeatAnchor anchor) {
        if (anchor == null) {
            Entity vehicle = level.getEntity(oldVehicle);
            if (vehicle == null) {
                LOGGER.warn("[ROCKET SEAT LOOKUP FAILED] oldVehicle={} reason=no_saved_anchor dimension={}",
                        oldVehicle, level.dimension().location());
            }
            return vehicle;
        }

        var container = SubLevelContainer.getContainer(level);
        var ship = container == null ? null : container.getSubLevel(anchor.ship());
        if (ship == null || ship.isRemoved()) {
            LOGGER.warn("[ROCKET SEAT LOOKUP FAILED] ship={} reason=destination_sublevel_missing", anchor.ship());
            return null;
        }

        Vec3 position = Vec3.atLowerCornerOf(ship.getPlot().getCenterBlock()).add(anchor.plotOffset());
        if (!(level.getBlockState(BlockPos.containing(position)).getBlock() instanceof SeatBlock)) {
            LOGGER.warn("[ROCKET SEAT LOOKUP FAILED] ship={} plotPosition={} plotOffset={} block={} reason=not_a_seat_block",
                    anchor.ship(), position, anchor.plotOffset(), level.getBlockState(BlockPos.containing(position)));
            return null;
        }
        for (Entity candidate : level.getAllEntities()) {
            if (candidate instanceof SeatEntity && !candidate.isRemoved()
                    && candidate.position().distanceToSqr(position) < 0.01d) {
                return candidate;
            }
        }

        SeatEntity seat = new SeatEntity(level);
        seat.setPos(position.x, position.y, position.z);
        if (!level.addFreshEntity(seat)) {
            return null;
        }
        LOGGER.info("[ROCKET SEAT RESOLVED] ship={} oldVehicle={} newVehicle={} plotPosition={}",
                anchor.ship(), oldVehicle, seat.getUUID(), position);
        return seat;
    }

    private static String describe(Entity entity) {
        return entity == null ? "MISSING" : entity.getName().getString() + " uuid=" + entity.getUUID()
                + " type=" + entity.getType() + " dimension=" + entity.level().dimension().location()
                + " position=" + entity.position() + " vehicle="
                + (entity.getVehicle() == null ? "none" : entity.getVehicle().getUUID());
    }

    private static void recoverInhabitants() {
        int count = INHABITANT_RECOVERY.size();
        for (int i = 0; i < count; i++) {
            InhabitantRecovery saved = INHABITANT_RECOVERY.poll();
            if (saved == null) {
                break;
            }

            Entity player = findEntity(saved.level(), saved.inhabitant());
            var container = SubLevelContainer.getContainer(saved.level());
            var ship = container == null ? null : container.getSubLevel(saved.ship());
            if (player != null && ship != null && !ship.isRemoved() && !player.isShiftKeyDown()) {
                Vec3 target = ship.logicalPose().transformPosition(saved.localPosition());
                if (!player.isPassenger() && (player.level() != saved.level()
                        || player.position().distanceToSqr(target) > 64.0d)) {
                    LOGGER.warn("[ROCKET RECOVER ENTITY] ship={} entity={} target={} remaining={}",
                            saved.ship(), describe(player), target, saved.attempts());
                    player.teleportTo(saved.level(), target.x, target.y, target.z,
                            Set.of(), player.getYRot(), player.getXRot());
                }
            }
            LOGGER.info("[ROCKET RECOVERY CHECK] ship={} entity={} remaining={}",
                    saved.ship(), describe(player), saved.attempts());
            if ((saved.attempts() > 1 || isHeld(saved.level()))
                    && (player == null || !player.isShiftKeyDown())) {
                INHABITANT_RECOVERY.add(new InhabitantRecovery(saved.level(), saved.ship(),
                        saved.inhabitant(), saved.localPosition(), Math.max(1, saved.attempts() - 1)));
            } else {
                LOGGER.info("[ROCKET FINAL ENTITY] ship={} entity={}", saved.ship(), describe(player));
            }
        }
    }

    private static Entity findEntity(ServerLevel level, UUID id) {
        for (ServerLevel candidate : level.getServer().getAllLevels()) {
            Entity entity = candidate.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static void retrySeats() {
        int count = PENDING_SEATS.size();
        for (int i = 0; i < count; i++) {
            PendingSeat saved = PENDING_SEATS.poll();
            if (saved == null) {
                break;
            }

            Entity passenger = saved.level().getEntity(saved.passenger());
            Entity vehicle = resolveSeat(saved.level(), saved.vehicle(), saved.anchor());
            // Respect an explicit dismount or a deliberate switch to another vehicle.
            if (passenger != null && (passenger.isShiftKeyDown()
                    || passenger.getVehicle() != null && passenger.getVehicle() != vehicle)) {
                continue;
            }

            boolean mounted = false;
            if (passenger != null && vehicle != null) {
                mounted = vehicle instanceof RocketContraptionEntity rocket && saved.seat() != null
                        ? rocket.restoreWarpSeat(passenger, saved.seat())
                        : passenger.getVehicle() == vehicle || passenger.startRiding(vehicle, true);
                if (mounted && passenger instanceof ServerPlayer player) {
                    player.connection.send(new ClientboundSetPassengersPacket(vehicle));
                }
            }

            if (saved.attempts() > 1 || isHeld(saved.level())) {
                PENDING_SEATS.add(new PendingSeat(saved.level(), saved.passenger(), saved.vehicle(),
                        saved.seat(), saved.anchor(), Math.max(1, saved.attempts() - 1)));
            } else {
                LOGGER.info("Rocket seat verification: passenger={}, vehicle={}, dimension={}, passengerPresent={}, vehiclePresent={}, mounted={}",
                        saved.passenger(), saved.vehicle(), saved.level().dimension().location(),
                        passenger != null, vehicle != null, mounted);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Value types / constants
    // ---------------------------------------------------------------------------------------------

    public record TeleportProperties(RocketDestination destination,
                                     ServerSubLevel subLevel, RigidBodyHandle handle, boolean flipOrientation) {
    }

    public static final RocketDestination TEST_END_DESTINATION =
            new RocketDestination(Level.END.location(), new BlockPos(0, 100, 0), null);
    public static final RocketDestination TEST_OVERWORLD_DESTINATION =
            new RocketDestination(Level.END.location(), new BlockPos(0, 100, 0), null);
}
