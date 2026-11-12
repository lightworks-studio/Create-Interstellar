package com.lightning.northstar.util;

import com.lightning.northstar.contraption.rocket.RocketDestination;
import dev.egg.dimensionalsable.SubLevelWarper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.lightning.northstar.Northstar.LOGGER;

public class DimensionalTeleportUtils {

    private static final Queue<TeleportProperties> DEFERRED_WARPS = new ConcurrentLinkedQueue<>();

    public record TeleportProperties(RocketDestination destination,
                                     ServerSubLevel subLevel, RigidBodyHandle handle, boolean flipOrientation) {

    }

    public static void enqueueWarp(TeleportProperties properties) {
        DEFERRED_WARPS.add(properties);
    }

    public static void processDeferredWarps() {
        TeleportProperties properties;
        while ((properties = DEFERRED_WARPS.poll()) != null) {
            warpSublevelToDestination(properties);
        }
    }


    public final static RocketDestination TEST_END_DESTINATION = new RocketDestination(Level.END.location(), new BlockPos(0, 100, 0), null);
    public final static  RocketDestination TEST_OVERWORLD_DESTINATION = new RocketDestination(Level.END.location(), new BlockPos(0, 100, 0), null);



    public static void warpSublevelToDestination(TeleportProperties properties) {

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
            final Quaterniond flippedOrientation = new Quaterniond()
                    .fromAxisAngleRad(flipAxis, Math.PI)
                    .mul(orientation);

            final int warped = SubLevelWarper.warpConnectedSubLevels(properties.subLevel, destLevel, destPos, flippedOrientation);
        } else {
            // Use the explicit-orientation overload, matching /northstar warp.
            // The convenience overload follows a different Dimensional-Sable
            // path and can leave the destination body's Rapier handle removed.
            final int warped = SubLevelWarper.warpConnectedSubLevels(
                    properties.subLevel, destLevel, destPos, new Quaterniond());
        }

        // Zero the velocity so the ship does not immediately re-trigger the warp in the new dimension
//        handle.addLinearAndAngularVelocity(handle.getLinearVelocity(new Vector3d()).negate(), handle.getAngularVelocity(new Vector3d()).negate());
    }
}
