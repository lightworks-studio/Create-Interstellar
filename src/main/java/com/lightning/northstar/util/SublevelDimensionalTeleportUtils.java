package com.lightning.northstar.util;

import com.lightning.northstar.contraption.rocket.RocketDestination;
import com.lightning.northstar.contraption.rocket.RocketContraptionEntity;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import dev.egg.dimensionalsable.SubLevelWarper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Queue;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.lightning.northstar.Northstar.LOGGER;

public class SublevelDimensionalTeleportUtils {

    private record Inhabitant(Entity entity, Vec3 relativePosition, UUID vehicle,
                              RocketContraptionEntity.WarpSeat rocketSeat, SeatAnchor anchor) {}
    private record SeatAnchor(UUID ship, Vec3 plotOffset) {}
    private record WarpRequest(TeleportProperties properties, List<Inhabitant> inhabitants) {}
    private static final Queue<WarpRequest> DEFERRED_WARPS = new ConcurrentLinkedQueue<>();
    private record PendingSeat(ServerLevel level, UUID passenger, UUID vehicle,
                               RocketContraptionEntity.WarpSeat seat, SeatAnchor anchor, int attempts) {}
    private static final Queue<PendingSeat> PENDING_SEATS = new ConcurrentLinkedQueue<>();
    private record InhabitantRecovery(ServerLevel level, UUID ship, UUID inhabitant, Vec3 localPosition, int attempts) {}
    private static final Queue<InhabitantRecovery> INHABITANT_RECOVERY = new ConcurrentLinkedQueue<>();

    private static Entity resolveSeat(ServerLevel level, UUID oldVehicle, SeatAnchor anchor) {
        if (anchor == null) return level.getEntity(oldVehicle);
        var container = SubLevelContainer.getContainer(level);
        var ship = container == null ? null : container.getSubLevel(anchor.ship());
        if (ship == null || ship.isRemoved()) return null;
        Vec3 position = Vec3.atLowerCornerOf(ship.getPlot().getCenterBlock()).add(anchor.plotOffset());
        if (!(level.getBlockState(BlockPos.containing(position)).getBlock() instanceof SeatBlock)) return null;
        for (Entity candidate : level.getAllEntities()) {
            if (candidate instanceof SeatEntity && !candidate.isRemoved() && candidate.position().distanceToSqr(position) < 0.01) return candidate;
        }
        SeatEntity seat = new SeatEntity(level);
        seat.setPos(position.x, position.y, position.z);
        if (!level.addFreshEntity(seat)) return null;
        LOGGER.info("[ROCKET SEAT RESOLVED] ship={} oldVehicle={} newVehicle={} plotPosition={}", anchor.ship(), oldVehicle, seat.getUUID(), position);
        return seat;
    }

    private static String describe(Entity entity) {
        return entity == null ? "MISSING" : entity.getName().getString() + " uuid=" + entity.getUUID()
                + " type=" + entity.getType() + " dimension=" + entity.level().dimension().location()
                + " position=" + entity.position() + " vehicle=" + (entity.getVehicle() == null ? "none" : entity.getVehicle().getUUID());
    }

    private static void recoverInhabitants() {
        int count = INHABITANT_RECOVERY.size();
        for (int i = 0; i < count; i++) {
            InhabitantRecovery saved = INHABITANT_RECOVERY.poll();
            if (saved == null) break;
            Entity player = null;
            for (ServerLevel level : saved.level().getServer().getAllLevels()) {
                player = level.getEntity(saved.inhabitant());
                if (player != null) break;
            }
            var container = SubLevelContainer.getContainer(saved.level());
            var ship = container == null ? null : container.getSubLevel(saved.ship());
            if (player != null && ship != null && !ship.isRemoved() && !player.isShiftKeyDown()) {
                Vec3 target = ship.logicalPose().transformPosition(saved.localPosition());
                if (!player.isPassenger() && (player.level() != saved.level() || player.position().distanceToSqr(target) > 64)) {
                    LOGGER.warn("[ROCKET RECOVER ENTITY] ship={} entity={} target={} remaining={}", saved.ship(), describe(player), target, saved.attempts());
                    player.teleportTo(saved.level(), target.x, target.y, target.z, java.util.Set.of(), player.getYRot(), player.getXRot());
                }
            }
            LOGGER.info("[ROCKET RECOVERY CHECK] ship={} entity={} remaining={}", saved.ship(), describe(player), saved.attempts());
            if (saved.attempts() > 1 && (player == null || !player.isShiftKeyDown())) {
                INHABITANT_RECOVERY.add(new InhabitantRecovery(saved.level(), saved.ship(), saved.inhabitant(), saved.localPosition(), saved.attempts() - 1));
            } else LOGGER.info("[ROCKET FINAL ENTITY] ship={} entity={}", saved.ship(), describe(player));
        }
    }

    private static void retrySeats() {
        int count = PENDING_SEATS.size();
        for (int i = 0; i < count; i++) {
            PendingSeat saved = PENDING_SEATS.poll();
            if (saved == null) break;
            Entity passenger = saved.level().getEntity(saved.passenger());
            Entity vehicle = resolveSeat(saved.level(), saved.vehicle(), saved.anchor());
            // Respect an explicit dismount or a deliberate switch to another vehicle.
            if (passenger != null && (passenger.isShiftKeyDown()
                    || passenger.getVehicle() != null && passenger.getVehicle() != vehicle)) continue;
            boolean mounted = false;
            if (passenger != null && vehicle != null) {
                mounted = vehicle instanceof RocketContraptionEntity rocket && saved.seat() != null
                        ? rocket.restoreWarpSeat(passenger, saved.seat())
                        : passenger.getVehicle() == vehicle || passenger.startRiding(vehicle, true);
                if (mounted && passenger instanceof ServerPlayer player) {
                    player.connection.send(new ClientboundSetPassengersPacket(vehicle));
                }
            }
            if (saved.attempts() > 1) {
                PENDING_SEATS.add(new PendingSeat(saved.level(), saved.passenger(), saved.vehicle(), saved.seat(), saved.anchor(), saved.attempts() - 1));
            } else {
                LOGGER.info("Rocket seat verification: passenger={}, vehicle={}, dimension={}, passengerPresent={}, vehiclePresent={}, mounted={}",
                        saved.passenger(), saved.vehicle(), saved.level().dimension().location(), passenger != null, vehicle != null, mounted);
            }
        }
    }

    public record TeleportProperties(RocketDestination destination,
                                     ServerSubLevel subLevel, RigidBodyHandle handle, boolean flipOrientation) {

    }

    public static void enqueueWarp(TeleportProperties properties) {
        ServerSubLevel ship = properties.subLevel();
        ServerLevel source = ship.getLevel();
        Vec3 origin = JOMLConversion.toMojang(ship.logicalPose().position());
        List<ServerSubLevel> group = SubLevelWarper.getSubLevelGroup(ship);
        Map<UUID, Inhabitant> inhabitants = new LinkedHashMap<>();
        for (Entity entity : source.getAllEntities()) {
            if (group.contains(Sable.HELPER.getTrackingSubLevel(entity))) {
                captureTree(entity.getRootVehicle(), source, origin, inhabitants);
            }
        }
        for (ServerSubLevel member : group) {
            for (Entity entity : source.getEntities(null, member.boundingBox().toMojang().inflate(1.0))) {
                captureTree(entity.getRootVehicle(), source, origin, inhabitants);
            }
        }
        LOGGER.info("[ROCKET BEFORE SUMMARY] ship={} source={} destination={} inhabitants={}", ship.getUniqueId(), source.dimension().location(), properties.destination().dim(), inhabitants.size());
        for (Inhabitant saved : inhabitants.values()) LOGGER.info("[ROCKET BEFORE] ship={} entity={} savedVehicle={} savedSeat={}", ship.getUniqueId(), describe(saved.entity()), saved.vehicle(), saved.rocketSeat());
        for (ServerPlayer player : source.players()) LOGGER.info("[ROCKET PLAYER CAPTURE AUDIT] ship={} included={} player={} tracking={}", ship.getUniqueId(), inhabitants.containsKey(player.getUUID()), describe(player), Sable.HELPER.getTrackingSubLevel(player));
        DEFERRED_WARPS.add(new WarpRequest(properties, List.copyOf(inhabitants.values())));
    }

    private static void captureTree(Entity entity, ServerLevel source, Vec3 origin, Map<UUID, Inhabitant> inhabitants) {
        var type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (type.toString().equals("create:super_glue") || type.toString().equals("simulated:honey_glue")) return;
        if (inhabitants.containsKey(entity.getUUID())) return;
        Vec3 worldPosition = Sable.HELPER.projectOutOfSubLevel(source, entity.position());
        Entity vehicle = entity.getVehicle();
        SeatAnchor anchor = null;
        if (vehicle instanceof SeatEntity) {
            var containing = Sable.HELPER.getContaining(vehicle);
            if (containing != null) {
                anchor = new SeatAnchor(containing.getUniqueId(), vehicle.position().subtract(Vec3.atLowerCornerOf(containing.getPlot().getCenterBlock())));
                LOGGER.info("[ROCKET SEAT CAPTURE] passenger={} ship={} plotOffset={}", entity.getUUID(), anchor.ship(), anchor.plotOffset());
            }
        }
        RocketContraptionEntity.WarpSeat rocketSeat = vehicle instanceof RocketContraptionEntity rocket
                ? rocket.captureWarpSeat(entity) : null;
        LOGGER.info("Northstar saving rocket inhabitant {} ({}) with vehicle {} and seat {} before warp",
                entity.getUUID(), entity.getType(), vehicle == null ? null : vehicle.getUUID(), rocketSeat);
        inhabitants.put(entity.getUUID(), new Inhabitant(entity, worldPosition.subtract(origin),
                vehicle == null ? null : vehicle.getUUID(), rocketSeat, anchor));
        for (Entity passenger : entity.getPassengers()) captureTree(passenger, source, origin, inhabitants);
    }

    public static void processDeferredWarps() {
        recoverInhabitants();
        retrySeats();
        WarpRequest request;
        while ((request = DEFERRED_WARPS.poll()) != null) {
            warpSublevelToDestination(request);
        }
    }


    public final static RocketDestination TEST_END_DESTINATION = new RocketDestination(Level.END.location(), new BlockPos(0, 100, 0), null);
    public final static  RocketDestination TEST_OVERWORLD_DESTINATION = new RocketDestination(Level.END.location(), new BlockPos(0, 100, 0), null);



    private static void warpSublevelToDestination(WarpRequest request) {
        TeleportProperties properties = request.properties();

        System.out.println("ATTEMPTING TO TELEPORT");
        final ServerLevel destLevel = properties.subLevel.getLevel().getServer().getLevel(properties.destination.dimKey());
        if (destLevel == null) {
            LOGGER.warn("Rocket station at {} cannot warp: dimension {} is not loaded", properties.destination.dim());
            return;
        }

        // Destination waypoint center, or the ship's current position if no waypoint is set
        final Vector3d destPos = properties.destination.pos() != null
                ? JOMLConversion.atCenterOf(properties.destination.pos())
                : new Vector3d(properties.subLevel.logicalPose().position());

        Quaterniond rotation = new Quaterniond();
        if (properties.flipOrientation) {
            // Flip the rocket: its nose (local +Y) must point in the opposite world direction after the warp,
            // so a ship that entered the atmosphere nose-up emerges nose-down
            final Quaterniond orientation = properties.subLevel.logicalPose().orientation();
            final Vector3d forward = new Vector3d(0, 1, 0);
            orientation.transform(forward);
            final Vector3d refAxis = Math.abs(forward.y) > 0.99 ? new Vector3d(1, 0, 0) : new Vector3d(0, 1, 0);
            final Vector3d crossResult = new Vector3d().cross(forward, refAxis);
            final Vector3d flipAxis;
            if (crossResult.lengthSquared() < 1.0E-6) {
                // Fallback axis if forward and refAxis are collinear
                flipAxis = new Vector3d(1, 0, 0);
            } else {
                flipAxis = crossResult.normalize();
            }
            rotation.fromAxisAngleRad(flipAxis, Math.PI);
        }

        int warped = SubLevelWarper.warpConnectedSubLevels(properties.subLevel, destLevel, destPos, rotation);
        LOGGER.info("[ROCKET WARP RESULT] ship={} warped={} expectedInhabitants={}", properties.subLevel.getUniqueId(), warped, request.inhabitants().size());
        if (warped > 0) {
            Map<UUID, Entity> transferred = new LinkedHashMap<>();
            for (Inhabitant inhabitant : request.inhabitants()) {
                Entity entity = inhabitant.entity();
                if (entity instanceof SeatEntity) continue; // Rebind seats by plot location, never move them into world space.
                Entity existing = destLevel.getEntity(entity.getUUID());
                LOGGER.info("[ROCKET AFTER DIMENSIONAL-SABLE] ship={} beforeName={} uuid={} destinationEntity={} sourceEntity={}", properties.subLevel.getUniqueId(), entity.getName().getString(), entity.getUUID(), describe(existing), describe(properties.subLevel.getLevel().getEntity(entity.getUUID())));
                if (existing != null) {
                    transferred.put(entity.getUUID(), existing);
                    continue;
                }
                if (entity.isRemoved() || entity.level() != properties.subLevel.getLevel()) {
                    LOGGER.warn("Could not locate rocket inhabitant {} after warp", entity.getUUID());
                    continue;
                }
                LOGGER.info("Northstar transferring missed rocket inhabitant {} ({}) from {} to {}", entity.getUUID(), entity.getType(), entity.level().dimension().location(), destLevel.dimension().location());
                entity.stopRiding();
                Vector3d offset = rotation.transform(new Vector3d(inhabitant.relativePosition().x, inhabitant.relativePosition().y, inhabitant.relativePosition().z));
                Vec3 target = new Vec3(destPos.x + offset.x, destPos.y + offset.y, destPos.z + offset.z);
                Entity moved = entity.changeDimension(new DimensionTransition(destLevel, target, entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(), DimensionTransition.DO_NOTHING));
                if (moved == null) LOGGER.warn("Could not transfer rocket inhabitant {}", entity.getUUID());
                else transferred.put(entity.getUUID(), moved);
            }
            for (Inhabitant inhabitant : request.inhabitants()) {
                LOGGER.info("[ROCKET AFTER RECOVERY] ship={} name={} uuid={} entity={}", properties.subLevel.getUniqueId(), inhabitant.entity().getName().getString(), inhabitant.entity().getUUID(), describe(destLevel.getEntity(inhabitant.entity().getUUID())));
                if (!(inhabitant.entity() instanceof SeatEntity)) {
                    Vec3 sourcePosition = JOMLConversion.toMojang(properties.subLevel.logicalPose().position()).add(inhabitant.relativePosition());
                    Vec3 local = properties.subLevel.logicalPose().transformPositionInverse(sourcePosition);
                    INHABITANT_RECOVERY.add(new InhabitantRecovery(destLevel, properties.subLevel.getUniqueId(), inhabitant.entity().getUUID(), local, 40));
                }
            }
            for (Inhabitant inhabitant : request.inhabitants()) {
                if (inhabitant.vehicle() == null) continue;
                PENDING_SEATS.add(new PendingSeat(destLevel, inhabitant.entity().getUUID(), inhabitant.vehicle(), inhabitant.rocketSeat(), inhabitant.anchor(), 40));
                Entity passenger = transferred.get(inhabitant.entity().getUUID());
                Entity vehicle = resolveSeat(destLevel, inhabitant.vehicle(), inhabitant.anchor());
                if (passenger == null || vehicle == null) {
                    LOGGER.warn("Could not restore rocket passenger {} to missing vehicle {}", inhabitant.entity().getUUID(), inhabitant.vehicle());
                    continue;
                }
                boolean restored = vehicle instanceof RocketContraptionEntity rocket && inhabitant.rocketSeat() != null
                        ? rocket.restoreWarpSeat(passenger, inhabitant.rocketSeat())
                        : passenger.getVehicle() == vehicle || passenger.startRiding(vehicle, true);
                if (!restored) {
                    LOGGER.warn("Could not restore rocket passenger {} to vehicle {}", passenger.getUUID(), vehicle.getUUID());
                }
            }
        }

        // Zero the velocity so the ship does not immediately re-trigger the warp in the new dimension
//        handle.addLinearAndAngularVelocity(handle.getLinearVelocity(new Vector3d()).negate(), handle.getAngularVelocity(new Vector3d()).negate());
    }
}
