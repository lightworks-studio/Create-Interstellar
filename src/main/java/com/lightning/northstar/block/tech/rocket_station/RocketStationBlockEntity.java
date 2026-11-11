package com.lightning.northstar.block.tech.rocket_station;

import com.lightning.northstar.config.NorthstarConfigs;
import com.lightning.northstar.contraption.rocket.RocketContraption;
import com.lightning.northstar.contraption.rocket.RocketContraptionEntity;
import com.lightning.northstar.contraption.rocket.RocketDestination;
import com.lightning.northstar.util.BetterSimpleContainer;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.egg.dimensionalsable.SubLevelWarper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.List;

public class RocketStationBlockEntity extends SmartBlockEntity
        implements
        IDisplayAssemblyExceptions,
        dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor {

    private static final Logger LOGGER = LogUtils.getLogger();

    public final SimpleContainer container = new BetterSimpleContainer(2);
    public AssemblyException lastException;
    public RocketDestination destination;
    public long lastTeleportTime;

    @Override
    public void sable$physicsTick(final ServerSubLevel subLevel, final RigidBodyHandle handle, final double timeStep) {
        if (lastTeleportTime > 40 && //tick cooldown
                subLevel.latestLinearVelocity.y > 0.015
                && subLevel.logicalPose().position().y > NorthstarConfigs.server().atmosphereTeleportHeight.get()) {
            lastTeleportTime = 0;
            warpSublevelToDestination(subLevel, handle);
        }
        lastTeleportTime++;
    }

    private void warpSublevelToDestination(final ServerSubLevel subLevel, final RigidBodyHandle handle) {
        if (destination == null) {
            return;
        }

        final ServerLevel destLevel = subLevel.getLevel().getServer().getLevel(destination.dimKey());
        if (destLevel == null) {
            LOGGER.warn("Rocket station at {} cannot warp: dimension {} is not loaded", worldPosition, destination.dim());
            return;
        }

        // Destination waypoint center, or the ship's current position if no waypoint is set
        final Vector3d destPos = destination.pos() != null
                ? JOMLConversion.atCenterOf(destination.pos())
                : new Vector3d(subLevel.logicalPose().position());

        // Flip the rocket: its nose (local +Y) must point in the opposite world direction after the warp,
        // so a ship that entered the atmosphere nose-up emerges nose-down
        final Quaterniond orientation = subLevel.logicalPose().orientation();
        final Vector3d forward = new Vector3d(0, 1, 0);
        orientation.transform(forward);
        final Vector3d refAxis = Math.abs(forward.y) > 0.99 ? new Vector3d(1, 0, 0) : new Vector3d(0, 1, 0);
        final Vector3d flipAxis = new Vector3d().cross(forward, refAxis).normalize();
        final Quaterniond flippedOrientation = new Quaterniond().fromAxisAngleRad(flipAxis, Math.PI).mul(orientation);

        final int warped = SubLevelWarper.warpConnectedSubLevels(subLevel, destLevel, destPos, flippedOrientation);

        // Zero the velocity so the ship does not immediately re-trigger the warp in the new dimension
        handle.addLinearAndAngularVelocity(handle.getLinearVelocity(new Vector3d()).negate(), handle.getAngularVelocity(new Vector3d()).negate());

        LOGGER.info("Rocket station at {} warped {} sub-level(s) to {} @ [{}]", worldPosition, warped, destination.dim(), destPos);
    }


    public RocketStationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        registerAwardables(behaviours, AllAdvancements.CONTRAPTION_ACTORS);
    }

    @Override
    public void destroy() {
        super.destroy();

        Containers.dropContents(level, worldPosition, container);
    }

    public RocketContraption assembleContraption() {
        lastException = null;
        RocketContraption rocket;
        try {
            if (level.northstar$planet() == null) {
                throw new AssemblyException(Component.translatable("northstar.contraption.rocket.assembly.unsupported_dimension"));
            }

            rocket = new RocketContraption();
            rocket.assemble(getLevel(), getBlockPos());

            if (!rocket.hasControls) {
                throw new AssemblyException(Component.translatable("northstar.contraption.rocket.assembly.missing_controls"));
            }

            rocket.destination = destination;
        } catch (AssemblyException exception) {
            lastException = exception;
            rocket = null;
        }
        sendData();
        return rocket;
    }

    public void assemble() {
        RocketContraption rocket = assembleContraption();
        if (rocket == null) {
            return;
        }

        RocketContraptionEntity entity = RocketContraptionEntity.create(level, rocket);
        entity.setPos(Vec3.atLowerCornerOf(worldPosition));

        AllSoundEvents.CONTRAPTION_ASSEMBLE.playOnServer(level, worldPosition);

        rocket.removeBlocksFromWorld(level, BlockPos.ZERO);
        level.addFreshEntity(entity);
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        AssemblyException.write(compound, registries, lastException);
        compound.put("Inventory", container.createTag(registries));
        if (destination != null) compound.put("Destination", destination.toTag());
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        lastException = AssemblyException.read(compound, registries);
        container.fromTag(compound.getList("Inventory", Tag.TAG_COMPOUND), registries);
        destination = RocketDestination.fromTag(compound.getCompound("Destination"));

        if (compound.contains("item", Tag.TAG_COMPOUND)) {
            container.setItem(0, ItemStack.parseOptional(registries, compound.getCompound("item")));
        }
    }

    @Override
    public AssemblyException getLastAssemblyException() {
        return lastException;
    }

}
