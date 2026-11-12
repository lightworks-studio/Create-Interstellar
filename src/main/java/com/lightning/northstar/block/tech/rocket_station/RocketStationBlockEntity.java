package com.lightning.northstar.block.tech.rocket_station;

import com.lightning.northstar.config.NorthstarConfigs;
import com.lightning.northstar.contraption.rocket.RocketContraption;
import com.lightning.northstar.contraption.rocket.RocketContraptionEntity;
import com.lightning.northstar.contraption.rocket.RocketDestination;
import com.lightning.northstar.util.BetterSimpleContainer;
import com.lightning.northstar.util.DimensionalTeleportUtils;
import com.mojang.logging.LogUtils;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
    DimensionalTeleportUtils.TeleportProperties teleportProperties = null;
    private boolean teleportQueued;


    @Override
    public void sable$physicsTick(final ServerSubLevel subLevel, final RigidBodyHandle handle, final double timeStep) {
//        if (lastTeleportTime > 100 && //tick cooldown
//                subLevel.latestLinearVelocity.y > 0.015
//                && subLevel.logicalPose().position().y > 200) {//NorthstarConfigs.server().atmosphereTeleportHeight.get()
//            lastTeleportTime = 0;
//            teleportProperties = new DimensionalTeleportUtils.TeleportProperties(
//                    new RocketDestination(
//                            destination.dim(),
//                            new BlockPos(0, 100, 0),
//                            null),
//                    subLevel, handle, true);
//            DimensionalTeleportUtils.warpSublevelToDestination(teleportProperties);
//        }
//        lastTeleportTime++;

        if (lastTeleportTime == 0 && !teleportQueued && destination != null) {
            // The warper removes/recreates the Rapier body. Defer it until this
            // physics callback has returned so Sable does not reuse a removed handle.
            System.out.println(destination.dim());
            final RocketDestination warpDestination = new RocketDestination(
                    destination.dim(), new BlockPos(0, 100, 0), null);
            final DimensionalTeleportUtils.TeleportProperties properties =
                    new DimensionalTeleportUtils.TeleportProperties(warpDestination, subLevel, handle, false);

            teleportQueued = true;
            lastTeleportTime++;
            DimensionalTeleportUtils.enqueueWarp(properties);
        }

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
