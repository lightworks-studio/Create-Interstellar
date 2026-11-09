package com.lightning.northstar.block.tech.rocket_thruster;

import com.lightning.northstar.config.NorthstarConfigs;
import com.lightning.northstar.content.NorthstarBlockEntityTypes;
import com.lightning.northstar.content.NorthstarTags.NorthstarFluidTags;
import com.lightning.northstar.particle.ThrusterPlumeParticleData;
import com.lightning.northstar.util.NorthstarLang;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class RocketThrusterBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation, BlockEntitySubLevelActor, BlockSubLevelAssemblyListener {

    public static final int MAX_WIDTH = 3;
    public static final int MIN_LENGTH = 2;

    public static final BehaviourType<SmartFluidTankBehaviour>
            FUEL_TANK = new BehaviourType<>("Fuel"),
            OXIDIZER_TANK = new BehaviourType<>("Oxidizer");

    private static final double NOZZLE_OFFSET = 0.45;

    public SmartFluidTankBehaviour fuelTank;
    public SmartFluidTankBehaviour oxidizerTank;

    protected int width = 1;
    protected int length = 1;
    @Nullable
    protected BlockPos controllerPos;

    protected int redstoneInput;
    protected float power;
    protected float currentThrust;
    protected float fuelUsageBuffer;
    protected float oxidizerUsageBuffer;
    protected int clearBlocks;
    protected boolean powerDirty = true;
    protected boolean needsConnectivityUpdate = true;
    protected boolean disassembling;
    protected int assemblyCooldown;

    public RocketThrusterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        int capacity = getBaseTankCapacity();
        behaviours.add(fuelTank = new SmartFluidTankBehaviour(FUEL_TANK, this, 1, capacity, false));
        behaviours.add(oxidizerTank = new SmartFluidTankBehaviour(OXIDIZER_TANK, this, 1, capacity, false));

        fuelTank.getPrimaryHandler().setValidator(stack -> RocketThrusterFuelManager.isFuel(stack.getFluid()));
        oxidizerTank.getPrimaryHandler().setValidator(this::isOxygen);
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null)
            return;

        if (level.isClientSide) {
            tickParticles();
            return;
        }

        if (assemblyCooldown > 0)
            assemblyCooldown--;

        if (needsConnectivityUpdate) {
            needsConnectivityUpdate = false;
            assemblyCooldown = 5;
            isDirty();
        }

        if (!isController()) {
            if (getControllerBE() == null)
                resetToSingle();
            return;
        }

        if (isMultiblock()) {
            if (level.getGameTime() % 20 == 0 && !validateMulti())
                return;
        } else if (assemblyCooldown == 0 && level.getGameTime() % 5 == 0 && hasNeighbourThruster()) {
            tryAssemble();
        }

        if (!isController())
            return;

        if (level.getGameTime() % 10 == 0)
            updateClearBlocks();

        updateThrust();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        needsConnectivityUpdate = true;
        if (level != null && !level.isClientSide) {
            redstoneInput = level.getBestNeighborSignal(worldPosition);
            powerDirty = true;
        }
    }

    @Override
    public void afterMove(ServerLevel oldLevel, ServerLevel newLevel, BlockState state, BlockPos oldPos, BlockPos newPos) {
        width = 1;
        length = 1;
        controllerPos = null;
        needsConnectivityUpdate = true;
        assemblyCooldown = 20;
        powerDirty = true;
        if (newLevel != null) {
            redstoneInput = newLevel.getBestNeighborSignal(newPos);
            setChanged();
        }
    }

    public boolean isMultiblock() {
        return width > 1 && length >= width;
    }

    public boolean isController() {
        return controllerPos == null;
    }

    @Nullable
    public RocketThrusterBlockEntity getControllerBE() {
        if (controllerPos == null)
            return this;
        if (level == null)
            return null;
        return getBlockEntitySafe(level, controllerPos) instanceof RocketThrusterBlockEntity controller ? controller : null;
    }

    public int getWidth() {
        return width;
    }

    public int getLength() {
        return length;
    }

    public float getPower() {
        return power;
    }

    public float getCurrentThrust() {
        return currentThrust;
    }

    public int getClearBlocks() {
        return clearBlocks;
    }

    public void updateConnectivity() {
        needsConnectivityUpdate = true;
        assemblyCooldown = 0;
        if (level != null && !level.isClientSide)
            setChanged();
    }

    public void setRedstoneInput(int input) {
        if (redstoneInput == input)
            return;

        redstoneInput = input;
        powerDirty = true;
        isDirty();

        RocketThrusterBlockEntity controller = getControllerBE();
        if (controller != null && controller != this)
            controller.powerDirty = true;
    }

    private void isDirty() {
        if (level != null && !level.isClientSide) {
            setChanged();
            notifyUpdate();
        }
    }

    private boolean hasNeighbourThruster() {
        return level != null && (isThruster(worldPosition.above()) ||
                isThruster(worldPosition.east()) ||
                isThruster(worldPosition.south()));
    }

    private boolean isThruster(BlockPos pos) {
        return level != null && getBlockStateSafe(level, pos).is(com.lightning.northstar.content.NorthstarBlocks.ROCKET_THRUSTER.get());
    }

    private void tryAssemble() {
        if (level == null || level.isClientSide || width > 1)
            return;

        for (int candidateWidth = MAX_WIDTH; candidateWidth >= 2; candidateWidth--) {
            if (!isLayerComplete(candidateWidth, 0))
                continue;

            int candidateLength = findLength(candidateWidth);
            if (candidateLength >= candidateWidth)
                formMulti(candidateWidth, candidateLength);
            return;
        }
    }

    private int findLength(int candidateWidth) {
        if (level == null)
            return 0;

        int maxLength = NorthstarConfigs.server().rocketThrusterMaxLength.get();
        int result = 0;
        for (int y = 0; y < maxLength; y++) {
            if (!isLayerComplete(candidateWidth, y))
                break;
            result++;
        }

        if (result == maxLength && isLayerComplete(candidateWidth, maxLength))
            return 0;

        return result;
    }

    private boolean isLayerComplete(int candidateWidth, int y) {
        if (level == null)
            return false;

        for (int x = 0; x < candidateWidth; x++) {
            for (int z = 0; z < candidateWidth; z++) {
                BlockPos pos = worldPosition.offset(x, y, z);
                BlockState state = getBlockStateSafe(level, pos);
                if (!state.is(com.lightning.northstar.content.NorthstarBlocks.ROCKET_THRUSTER.get()))
                    return false;
                if (!(getBlockEntitySafe(level, pos) instanceof RocketThrusterBlockEntity thruster) || thruster.width > 1)
                    return false;
            }
        }
        return true;
    }

    private void formMulti(int newWidth, int newLength) {
        if (level == null || level.isClientSide)
            return;

        int capacity = getBaseTankCapacity() * newWidth * newWidth * newLength;
        List<RocketThrusterBlockEntity> members = new ArrayList<>(newWidth * newWidth * newLength);
        FluidStack fuelPool = FluidStack.EMPTY;
        FluidStack oxidizerPool = FluidStack.EMPTY;

        for (int y = 0; y < newLength; y++) {
            for (int x = 0; x < newWidth; x++) {
                for (int z = 0; z < newWidth; z++) {
                    BlockPos pos = worldPosition.offset(x, y, z);
                    if (!(getBlockEntitySafe(level, pos) instanceof RocketThrusterBlockEntity thruster))
                        return;

                    FluidStack fuel = thruster.fuelTank.getPrimaryHandler().getFluid();
                    FluidStack oxidizer = thruster.oxidizerTank.getPrimaryHandler().getFluid();
                    if (!fuel.isEmpty()) {
                        if (fuelPool.isEmpty())
                            fuelPool = fuel.copy();
                        else if (FluidStack.isSameFluidSameComponents(fuelPool, fuel))
                            fuelPool.grow(fuel.getAmount());
                        else
                            return;
                    }
                    if (!oxidizer.isEmpty()) {
                        if (oxidizerPool.isEmpty())
                            oxidizerPool = oxidizer.copy();
                        else if (FluidStack.isSameFluidSameComponents(oxidizerPool, oxidizer))
                            oxidizerPool.grow(oxidizer.getAmount());
                        else
                            return;
                    }
                    members.add(thruster);
                }
            }
        }

        for (RocketThrusterBlockEntity member : members) {
            member.fuelTank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
            member.oxidizerTank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
        }

        fuelTank.getPrimaryHandler().setCapacity(capacity);
        oxidizerTank.getPrimaryHandler().setCapacity(capacity);
        fuelTank.getPrimaryHandler().setFluid(trimToCapacity(fuelPool, capacity));
        oxidizerTank.getPrimaryHandler().setFluid(trimToCapacity(oxidizerPool, capacity));

        for (RocketThrusterBlockEntity member : members) {
            member.width = newWidth;
            member.length = newLength;
            member.controllerPos = member == this ? null : worldPosition;
            member.needsConnectivityUpdate = false;
            member.powerDirty = true;
            member.fuelUsageBuffer = 0;
            member.oxidizerUsageBuffer = 0;
            member.redstoneInput = level.getBestNeighborSignal(member.worldPosition);

            BlockState state = member.getBlockState();
            if (state.hasProperty(RocketThrusterBlock.MULTI) && !state.getValue(RocketThrusterBlock.MULTI)) {
                level.setBlock(member.worldPosition, state.setValue(RocketThrusterBlock.MULTI, true), Block.UPDATE_CLIENTS);
            }

            member.setChanged();
            member.notifyUpdate();
            member.sendData();
        }

        assemblyCooldown = 20;
        updateClearBlocks();
    }

    public void disassembleMulti() {
        if (disassembling || level == null || level.isClientSide || !isController() || !isMultiblock())
            return;

        disassembling = true;
        try {
            int oldWidth = width;
            int oldLength = length;
            int capacity = getBaseTankCapacity();

            FluidStack fuelPool = fuelTank.getPrimaryHandler().getFluid().copy();
            FluidStack oxidizerPool = oxidizerTank.getPrimaryHandler().getFluid().copy();
            fuelTank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
            oxidizerTank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
            fuelTank.getPrimaryHandler().setCapacity(capacity);
            oxidizerTank.getPrimaryHandler().setCapacity(capacity);

            for (int y = 0; y < oldLength; y++) {
                for (int x = 0; x < oldWidth; x++) {
                    for (int z = 0; z < oldWidth; z++) {
                        BlockPos pos = worldPosition.offset(x, y, z);
                        if (!(getBlockEntitySafe(level, pos) instanceof RocketThrusterBlockEntity thruster))
                            continue;

                        thruster.width = 1;
                        thruster.length = 1;
                        thruster.controllerPos = null;
                        thruster.needsConnectivityUpdate = true;
                        thruster.assemblyCooldown = 20;
                        thruster.powerDirty = true;
                        thruster.power = 0;
                        thruster.currentThrust = 0;
                        thruster.fuelUsageBuffer = 0;
                        thruster.oxidizerUsageBuffer = 0;
                        thruster.redstoneInput = level.getBestNeighborSignal(pos);
                        thruster.fuelTank.getPrimaryHandler().setCapacity(capacity);
                        thruster.oxidizerTank.getPrimaryHandler().setCapacity(capacity);

                        fuelPool = distributeFluid(thruster.fuelTank.getPrimaryHandler(), fuelPool, capacity);
                        oxidizerPool = distributeFluid(thruster.oxidizerTank.getPrimaryHandler(), oxidizerPool, capacity);

                        BlockState state = thruster.getBlockState();
                        if (state.hasProperty(RocketThrusterBlock.MULTI) && state.getValue(RocketThrusterBlock.MULTI)) {
                            level.setBlock(pos, state.setValue(RocketThrusterBlock.MULTI, false), Block.UPDATE_CLIENTS);
                        }

                        thruster.setChanged();
                        thruster.notifyUpdate();
                        thruster.sendData();
                    }
                }
            }

            width = 1;
            length = 1;
            controllerPos = null;
            power = 0;
            currentThrust = 0;
            assemblyCooldown = 20;
        } finally {
            disassembling = false;
        }
    }

    private boolean validateMulti() {
        if (level == null || !isController() || !isMultiblock())
            return true;

        for (int y = 0; y < length; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    BlockPos pos = worldPosition.offset(x, y, z);
                    if (!level.isLoaded(pos))
                        return true;
                    if (!(getBlockEntitySafe(level, pos) instanceof RocketThrusterBlockEntity thruster) ||
                            thruster.width != width ||
                            thruster.length != length ||
                            (thruster != this && !worldPosition.equals(thruster.controllerPos))) {
                        disassembleMulti();
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void resetToSingle() {
        width = 1;
        length = 1;
        controllerPos = null;
        power = 0;
        currentThrust = 0;
        needsConnectivityUpdate = true;
        isDirty();
    }

    private void updateThrust() {
        RocketThrusterFuelProperties properties = getFuelProperties();
        boolean working = properties != null && isOxygen(oxidizerTank.getPrimaryHandler().getFluid());

        if (powerDirty) {
            float newPower = computePower();
            powerDirty = false;
            if (Math.abs(newPower - power) > 1e-4f) {
                power = newPower;
                notifyUpdate();
            }
        }

        if (!working || power <= 0) {
            setCurrentThrust(0);
            fuelUsageBuffer = 0;
            oxidizerUsageBuffer = 0;
            return;
        }

        float widthMultiplier = width == 2 ? 1.25f : width == 3 ? 1.5f : 1f;
        float thrust = NorthstarConfigs.server().rocketThrusterBaseThrust.getF() *
                width * width * length * widthMultiplier * properties.thrustMultiplier() * power;
        setCurrentThrust(thrust);

        float usage = NorthstarConfigs.server().rocketThrusterFuelUsage.getF() * width * width * length * power * properties.consumptionMultiplier();
        fuelUsageBuffer += usage;
        oxidizerUsageBuffer += usage;
        fuelUsageBuffer = drainTank(fuelTank.getPrimaryHandler(), fuelUsageBuffer);
        oxidizerUsageBuffer = drainTank(oxidizerTank.getPrimaryHandler(), oxidizerUsageBuffer);

        if (fuelTank.getPrimaryHandler().getFluidAmount() <= 0 || oxidizerTank.getPrimaryHandler().getFluidAmount() <= 0)
            setCurrentThrust(0);
    }

    private float computePower() {
        int maxInput = redstoneInput;
        if (isMultiblock()) {
            for (int y = 0; y < length; y++) {
                for (int x = 0; x < width; x++) {
                    for (int z = 0; z < width; z++) {
                        if (level == null)
                            return maxInput / 15f;
                        if (getBlockEntitySafe(level, worldPosition.offset(x, y, z)) instanceof RocketThrusterBlockEntity thruster)
                            maxInput = Math.max(maxInput, thruster.redstoneInput);
                    }
                }
            }
        }
        return maxInput / 15f;
    }

    private void setCurrentThrust(float thrust) {
        if (Math.abs(currentThrust - thrust) > 1e-4f) {
            currentThrust = thrust;
            notifyUpdate();
        }
    }

    @Nullable
    private RocketThrusterFuelProperties getFuelProperties() {
        FluidStack fuel = fuelTank.getPrimaryHandler().getFluid();
        return fuel.isEmpty() ? null : RocketThrusterFuelManager.getProperties(fuel.getFluid());
    }

    private boolean isOxygen(FluidStack stack) {
        return !stack.isEmpty() &&
                (NorthstarFluidTags.C_OXYGEN.matches(stack.getFluid()) ||
                        NorthstarFluidTags.C_LIQUID_OXYGEN.matches(stack.getFluid()));
    }

    private static float drainTank(SmartFluidTank tank, float buffer) {
        int drainable = Math.min(tank.getFluidAmount(), (int) buffer);
        tank.drain(drainable, IFluidHandler.FluidAction.EXECUTE);
        return buffer - drainable;
    }

    private static FluidStack trimToCapacity(FluidStack stack, int capacity) {
        if (stack.getAmount() <= capacity)
            return stack;
        FluidStack result = stack.copy();
        result.setAmount(capacity);
        return result;
    }

    private static FluidStack distributeFluid(SmartFluidTank tank, FluidStack pool, int capacity) {
        if (pool.isEmpty())
            return pool;

        int amount = Math.min(capacity, pool.getAmount());
        FluidStack slice = pool.copy();
        slice.setAmount(amount);
        int filled = tank.fill(slice, IFluidHandler.FluidAction.EXECUTE);
        if (filled <= 0)
            return pool;

        FluidStack remaining = pool.copy();
        remaining.shrink(filled);
        return remaining;
    }

    private void updateClearBlocks() {
        if (level == null)
            return;

        int count = 0;
        for (int y = 1; y <= 5; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    if (getBlockStateSafe(level, worldPosition.offset(x, -y, z)).isAir())
                        count++;
                }
            }
        }

        if (count != clearBlocks) {
            clearBlocks = count;
            notifyUpdate();
        }
    }

    @OnlyIn(Dist.CLIENT)
    private void tickParticles() {
        if (!isController() || power <= 0 || clearBlocks <= 0 || getFuelProperties() == null ||
                !isOxygen(oxidizerTank.getPrimaryHandler().getFluid()))
            return;
        if (level == null || level.random.nextFloat() > power)
            return;

        double localX = worldPosition.getX() + width * 0.5;
        double localY = worldPosition.getY() - NOZZLE_OFFSET;
        double localZ = worldPosition.getZ() + width * 0.5;

        Vec3 spawn = new Vec3(localX, localY, localZ);
        Vec3 direction = new Vec3(0, -1, 0);
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, worldPosition);
        if (subLevel != null) {
            spawn = subLevel.logicalPose().transformPosition(spawn);
            direction = subLevel.logicalPose().transformNormal(direction);
        }

        double speed = 0.4 + power * 1.4;
        level.addAlwaysVisibleParticle(ThrusterPlumeParticleData.INSTANCE, true,
                spawn.x, spawn.y, spawn.z,
                direction.x * speed, direction.y * speed, direction.z * speed);
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        if (!isController() || currentThrust <= 0 || power <= 0)
            return;

        Vector3d point = new Vector3d(
                worldPosition.getX() + width * 0.5,
                worldPosition.getY() - NOZZLE_OFFSET,
                worldPosition.getZ() + width * 0.5
        );
        Vector3d impulse = new Vector3d(0, currentThrust * timeStep, 0);

        QueuedForceGroup forceGroup = subLevel.getOrCreateQueuedForceGroup(ForceGroups.PROPULSION.get());
        forceGroup.applyAndRecordPointForce(point, impulse);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (!isController()) {
            RocketThrusterBlockEntity controller = getControllerBE();
            if (controller != null)
                return controller.addToGoggleTooltip(tooltip, isPlayerSneaking);
        }

        NorthstarLang.addTankTooltip(tooltip, fuelTank.getPrimaryHandler(),
                Component.translatableWithFallback("northstar.gui.goggles.rocket_thruster.fuel", "Fuel"));
        NorthstarLang.addTankTooltip(tooltip, oxidizerTank.getPrimaryHandler(),
                Component.translatableWithFallback("northstar.gui.goggles.rocket_thruster.oxidizer", "Oxidizer"));

        return true;
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public AABB getRenderBoundingBox() {
        if (!isController() || !isMultiblock())
            return super.getRenderBoundingBox();

        return new AABB(
                worldPosition.getX(), worldPosition.getY() - width * 0.25 - 0.25, worldPosition.getZ(),
                worldPosition.getX() + width, worldPosition.getY() + length + 0.25, worldPosition.getZ() + width
        );
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        compound.putInt("Width", width);
        compound.putInt("Length", length);
        compound.putInt("Redstone", redstoneInput);
        compound.putFloat("Power", power);
        compound.putFloat("Thrust", currentThrust);
        compound.putFloat("FuelUsageBuffer", fuelUsageBuffer);
        compound.putFloat("OxidizerUsageBuffer", oxidizerUsageBuffer);
        compound.putInt("ClearBlocks", clearBlocks);

        if (controllerPos != null) {
            compound.putInt("ControllerX", controllerPos.getX() - worldPosition.getX());
            compound.putInt("ControllerY", controllerPos.getY() - worldPosition.getY());
            compound.putInt("ControllerZ", controllerPos.getZ() - worldPosition.getZ());
        }
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        width = Math.max(1, compound.getInt("Width"));
        length = Math.max(1, compound.getInt("Length"));
        boolean isController = !compound.contains("ControllerX");

        if (isController && width > 1) {
            int capacity = getBaseTankCapacity() * width * width * length;
            if (fuelTank != null)
                fuelTank.getPrimaryHandler().setCapacity(capacity);
            if (oxidizerTank != null)
                oxidizerTank.getPrimaryHandler().setCapacity(capacity);
        }

        super.read(compound, registries, clientPacket);

        redstoneInput = compound.getInt("Redstone");
        power = compound.getFloat("Power");
        currentThrust = compound.getFloat("Thrust");
        fuelUsageBuffer = compound.getFloat("FuelUsageBuffer");
        oxidizerUsageBuffer = compound.getFloat("OxidizerUsageBuffer");
        clearBlocks = compound.getInt("ClearBlocks");

        if (isController) {
            controllerPos = null;
        } else {
            controllerPos = worldPosition.offset(
                    compound.getInt("ControllerX"),
                    compound.getInt("ControllerY"),
                    compound.getInt("ControllerZ"));
        }
    }

    @Nullable
    public static BlockEntity getBlockEntitySafe(Level level, BlockPos pos) {
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, pos);
        if (subLevel != null) {
            Vec3 world = subLevel.logicalPose().transformPosition(
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            if (level.isOutsideBuildHeight((int) Math.floor(world.y)))
                return level.getChunkAt(pos).getBlockEntity(pos);
        }
        return level.getBlockEntity(pos);
    }

    public static BlockState getBlockStateSafe(Level level, BlockPos pos) {
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, pos);
        if (subLevel != null) {
            Vec3 world = subLevel.logicalPose().transformPosition(
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            if (level.isOutsideBuildHeight((int) Math.floor(world.y)))
                return level.getChunkAt(pos).getBlockState(pos);
        }
        return level.getBlockState(pos);
    }

    private static int getBaseTankCapacity() {
        return NorthstarConfigs.server().rocketThrusterFuelCapacity.get();
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, NorthstarBlockEntityTypes.ROCKET_THRUSTER.get(), (be, face) -> {
            RocketThrusterBlockEntity controller = be.getControllerBE();
            if (controller == null)
                return null;
            return new MultiFluidHandler(controller.fuelTank.getCapability(), controller.oxidizerTank.getCapability());
        });
    }

}
