package com.lightning.northstar.util;

import com.lightning.northstar.contraption.rocket.RocketDestination;
import com.lightning.northstar.contraption.rocket.RocketContraptionEntity;
import dev.egg.dimensionalsable.SubLevelWarper;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
                              RocketContraptionEntity.WarpSeat rocketSeat) {}
    private record WarpRequest(TeleportProperties properties, List<Inhabitant> inhabitants) {}
    private static final Queue<WarpRequest> DEFERRED_WARPS = new ConcurrentLinkedQueue<>();

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
        DEFERRED_WARPS.add(new WarpRequest(properties, List.copyOf(inhabitants.values())));
    }

    private static void captureTree(Entity entity, ServerLevel source, Vec3 origin, Map<UUID, Inhabitant> inhabitants) {
        if (inhabitants.containsKey(entity.getUUID())) return;
        Vec3 worldPosition = Sable.HELPER.projectOutOfSubLevel(source, entity.position());
        Entity vehicle = entity.getVehicle();
        RocketContraptionEntity.WarpSeat rocketSeat = vehicle instanceof RocketContraptionEntity rocket
                ? rocket.captureWarpSeat(entity) : null;
        LOGGER.info("Northstar saving rocket inhabitant {} ({}) with vehicle {} and seat {} before warp",
                entity.getUUID(), entity.getType(), vehicle == null ? null : vehicle.getUUID(), rocketSeat);
        inhabitants.put(entity.getUUID(), new Inhabitant(entity, worldPosition.subtract(origin),
                vehicle == null ? null : vehicle.getUUID(), rocketSeat));
        for (Entity passenger : entity.getPassengers()) captureTree(passenger, source, origin, inhabitants);
    }

    public static void processDeferredWarps() {
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
        if (warped > 0) {
            Map<UUID, Entity> transferred = new LinkedHashMap<>();
            for (Inhabitant inhabitant : request.inhabitants()) {
                Entity entity = inhabitant.entity();
                Entity existing = destLevel.getEntity(entity.getUUID());
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
                if (inhabitant.vehicle() == null) continue;
                Entity passenger = transferred.get(inhabitant.entity().getUUID());
                Entity vehicle = transferred.get(inhabitant.vehicle());
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
