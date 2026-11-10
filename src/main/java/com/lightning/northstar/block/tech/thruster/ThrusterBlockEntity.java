package com.lightning.northstar.block.tech.thruster;

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
import net.minecraft.core.Direction;
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
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared logic for every directional thruster ({@link ThrusterBlock}) in the mod.
 *
 * <p>The assembly is a W×W×L prism anchored at the controller block and extending toward
 * {@link ThrusterBlock#FACING} (the thrust direction). The exhaust/nozzle is on the opposite
 * side. All member offsets are derived from {@code FACING} via {@link #crossAxisU} /
 * {@link #crossAxisV} so the same code works for all six directions.
 *
 * <p>Rocket-specific (contraption) and liquid-specific behaviour lives in the subclasses.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public abstract class ThrusterBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation, BlockEntitySubLevelActor, BlockSubLevelAssemblyListener {

    public static final int MAX_WIDTH = 3;
    public static final int MIN_LENGTH = 2;

    public static final BehaviourType<SmartFluidTankBehaviour>
            FUEL_TANK = new BehaviourType<>("Fuel"),
            OXIDIZER_TANK = new BehaviourType<>("Oxidizer");

    private static final double NOZZLE_OFFSET = 0.45;
    private static final double EXHAUST_SCAN_LENGTH = 5;

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

    public ThrusterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ------------------------------------------------------------------
    // Abstract hooks for concrete thrusters
    // ------------------------------------------------------------------

    /** Whether a world block is a compatible thruster block of this exact type. */
    protected abstract boolean isCompatibleBlock(BlockState state);

    protected abstract int getBaseTankCapacity();

    protected abstract float getBaseThrust();

    protected abstract float getFuelUsage();

    protected abstract int getMaxLength();

    protected abstract Component getFuelTankLabel();

    protected abstract Component getOxidizerTankLabel();

    // ------------------------------------------------------------------
    // Behaviours / lifecycle
    // ------------------------------------------------------------------

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        int capacity = getBaseTankCapacity();
        behaviours.add(fuelTank = new SmartFluidTankBehaviour(FUEL_TANK, this, 1, capacity, false));
        behaviours.add(oxidizerTank = new SmartFluidTankBehaviour(OXIDIZER_TANK, this, 1, capacity, false));

        fuelTank.getPrimaryHandler().setValidator(stack -> ThrusterFuelManager.isFuel(stack.getFluid()));
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

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    public boolean isMultiblock() {
        return width > 1 && length >= width;
    }

    public boolean isController() {
        return controllerPos == null;
    }

    @Nullable
    public ThrusterBlockEntity getControllerBE() {
        if (controllerPos == null)
            return this;
        if (level == null)
            return null;
        return getBlockEntitySafe(level, controllerPos) instanceof ThrusterBlockEntity controller ? controller : null;
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

    public Direction getFacing() {
        BlockState state = getBlockState();
        return state.hasProperty(ThrusterBlock.FACING) ? state.getValue(ThrusterBlock.FACING) : Direction.UP;
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

        ThrusterBlockEntity controller = getControllerBE();
        if (controller != null && controller != this)
            controller.powerDirty = true;
    }

    protected void isDirty() {
        if (level != null && !level.isClientSide) {
            setChanged();
            notifyUpdate();
        }
    }

    // ------------------------------------------------------------------
    // FACING-aware multiblock geometry
    // ------------------------------------------------------------------

    /**
     * First cross-section axis (the model's local +X direction) for a given thrust direction.
     * Chosen to match the block-entity renderer's rotation of the up-authored models.
     */
    public static Direction crossAxisU(Direction facing) {
        return switch (facing) {
            case WEST -> Direction.UP;
            case EAST -> Direction.DOWN;
            default -> Direction.EAST;
        };
    }

    /**
     * Second cross-section axis (the model's local +Z direction) for a given thrust direction.
     */
    public static Direction crossAxisV(Direction facing) {
        return switch (facing) {
            case UP -> Direction.SOUTH;
            case DOWN -> Direction.NORTH;
            case NORTH -> Direction.UP;
            case SOUTH -> Direction.DOWN;
            default -> Direction.SOUTH; // EAST / WEST
        };
    }

    /** Position of member {@code (u, v, layer)} relative to this (controller) block. */
    protected BlockPos memberPos(int u, int v, int layer) {
        Direction facing = getFacing();
        Direction uAxis = crossAxisU(facing);
        Direction vAxis = crossAxisV(facing);
        return worldPosition.offset(
                uAxis.getStepX() * u + vAxis.getStepX() * v + facing.getStepX() * layer,
                uAxis.getStepY() * u + vAxis.getStepY() * v + facing.getStepY() * layer,
                uAxis.getStepZ() * u + vAxis.getStepZ() * v + facing.getStepZ() * layer);
    }

    /** Position of an air-clearance cell {@code (u, v, layer)} on the exhaust side. */
    protected BlockPos exhaustPos(int u, int v, int layer) {
        Direction facing = getFacing().getOpposite();
        Direction uAxis = crossAxisU(getFacing());
        Direction vAxis = crossAxisV(getFacing());
        return worldPosition.offset(
                uAxis.getStepX() * u + vAxis.getStepX() * v + facing.getStepX() * layer,
                uAxis.getStepY() * u + vAxis.getStepY() * v + facing.getStepY() * layer,
                uAxis.getStepZ() * u + vAxis.getStepZ() * v + facing.getStepZ() * layer);
    }

    /** Center of the exhaust face, {@link #NOZZLE_OFFSET} past the nozzle plane. */
    protected Vec3 getNozzleCenter() {
        Direction facing = getFacing();
        Direction uAxis = crossAxisU(facing);
        Direction vAxis = crossAxisV(facing);
        double half = width * 0.5;
        return new Vec3(
                worldPosition.getX() + uAxis.getStepX() * half + vAxis.getStepX() * half - facing.getStepX() * NOZZLE_OFFSET,
                worldPosition.getY() + uAxis.getStepY() * half + vAxis.getStepY() * half - facing.getStepY() * NOZZLE_OFFSET,
                worldPosition.getZ() + uAxis.getStepZ() * half + vAxis.getStepZ() * half - facing.getStepZ() * NOZZLE_OFFSET);
    }

    // ------------------------------------------------------------------
    // Assembly
    // ------------------------------------------------------------------

    private boolean hasNeighbourThruster() {
        if (level == null)
            return false;
        Direction facing = getFacing();
        return isThruster(worldPosition.relative(facing)) ||
                isThruster(worldPosition.relative(crossAxisU(facing))) ||
                isThruster(worldPosition.relative(crossAxisV(facing)));
    }

    private boolean isThruster(BlockPos pos) {
        return level != null && isCompatibleBlock(getBlockStateSafe(level, pos));
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

        int maxLength = getMaxLength();
        int result = 0;
        for (int layer = 0; layer < maxLength; layer++) {
            if (!isLayerComplete(candidateWidth, layer))
                break;
            result++;
        }

        if (result == maxLength && isLayerComplete(candidateWidth, maxLength))
            return 0;

        return result;
    }

    private boolean isLayerComplete(int candidateWidth, int layer) {
        if (level == null)
            return false;

        for (int u = 0; u < candidateWidth; u++) {
            for (int v = 0; v < candidateWidth; v++) {
                BlockPos pos = memberPos(u, v, layer);
                BlockState state = getBlockStateSafe(level, pos);
                if (!isCompatibleBlock(state))
                    return false;
                if (!(getBlockEntitySafe(level, pos) instanceof ThrusterBlockEntity thruster) || thruster.width > 1)
                    return false;
            }
        }
        return true;
    }

    private void formMulti(int newWidth, int newLength) {
        if (level == null || level.isClientSide)
            return;

        int capacity = getBaseTankCapacity() * newWidth * newWidth * newLength;
        List<ThrusterBlockEntity> members = new ArrayList<>(newWidth * newWidth * newLength);
        FluidStack fuelPool = FluidStack.EMPTY;
        FluidStack oxidizerPool = FluidStack.EMPTY;

        for (int layer = 0; layer < newLength; layer++) {
            for (int u = 0; u < newWidth; u++) {
                for (int v = 0; v < newWidth; v++) {
                    BlockPos pos = memberPos(u, v, layer);
                    if (!(getBlockEntitySafe(level, pos) instanceof ThrusterBlockEntity thruster))
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

        for (ThrusterBlockEntity member : members) {
            member.fuelTank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
            member.oxidizerTank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
        }

        fuelTank.getPrimaryHandler().setCapacity(capacity);
        oxidizerTank.getPrimaryHandler().setCapacity(capacity);
        fuelTank.getPrimaryHandler().setFluid(trimToCapacity(fuelPool, capacity));
        oxidizerTank.getPrimaryHandler().setFluid(trimToCapacity(oxidizerPool, capacity));

        for (ThrusterBlockEntity member : members) {
            member.width = newWidth;
            member.length = newLength;
            member.controllerPos = member == this ? null : worldPosition;
            member.needsConnectivityUpdate = false;
            member.powerDirty = true;
            member.fuelUsageBuffer = 0;
            member.oxidizerUsageBuffer = 0;
            member.redstoneInput = level.getBestNeighborSignal(member.worldPosition);

            BlockState state = member.getBlockState();
            if (state.hasProperty(ThrusterBlock.MULTIBLOCK) && !state.getValue(ThrusterBlock.MULTIBLOCK)) {
                level.setBlock(member.worldPosition, state.setValue(ThrusterBlock.MULTIBLOCK, true), Block.UPDATE_CLIENTS);
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

            for (int layer = 0; layer < oldLength; layer++) {
                for (int u = 0; u < oldWidth; u++) {
                    for (int v = 0; v < oldWidth; v++) {
                        BlockPos pos = memberPos(u, v, layer);
                        if (!(getBlockEntitySafe(level, pos) instanceof ThrusterBlockEntity thruster))
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
                        if (state.hasProperty(ThrusterBlock.MULTIBLOCK) && state.getValue(ThrusterBlock.MULTIBLOCK)) {
                            level.setBlock(pos, state.setValue(ThrusterBlock.MULTIBLOCK, false), Block.UPDATE_CLIENTS);
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

        for (int layer = 0; layer < length; layer++) {
            for (int u = 0; u < width; u++) {
                for (int v = 0; v < width; v++) {
                    BlockPos pos = memberPos(u, v, layer);
                    if (!level.isLoaded(pos))
                        return true;
                    if (!(getBlockEntitySafe(level, pos) instanceof ThrusterBlockEntity thruster) ||
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

    // ------------------------------------------------------------------
    // Thrust / fuel
    // ------------------------------------------------------------------

    private void updateThrust() {
        ThrusterFuelProperties properties = getFuelProperties();
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
        float thrust = getBaseThrust() *
                width * width * length * widthMultiplier * properties.thrustMultiplier() * power;
        setCurrentThrust(thrust);

        float usage = getFuelUsage() * width * width * length * power * properties.consumptionMultiplier();
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
            for (int layer = 0; layer < length; layer++) {
                for (int u = 0; u < width; u++) {
                    for (int v = 0; v < width; v++) {
                        if (level == null)
                            return maxInput / 15f;
                        if (getBlockEntitySafe(level, memberPos(u, v, layer)) instanceof ThrusterBlockEntity thruster)
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
    private ThrusterFuelProperties getFuelProperties() {
        FluidStack fuel = fuelTank.getPrimaryHandler().getFluid();
        return fuel.isEmpty() ? null : ThrusterFuelManager.getProperties(fuel.getFluid());
    }

    protected boolean isOxygen(FluidStack stack) {
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
        for (int layer = 1; layer <= EXHAUST_SCAN_LENGTH; layer++) {
            for (int u = 0; u < width; u++) {
                for (int v = 0; v < width; v++) {
                    if (getBlockStateSafe(level, exhaustPos(u, v, layer)).isAir())
                        count++;
                }
            }
        }

        if (count != clearBlocks) {
            clearBlocks = count;
            notifyUpdate();
        }
    }

    // ------------------------------------------------------------------
    // Client particles
    // ------------------------------------------------------------------

    @OnlyIn(Dist.CLIENT)
    private void tickParticles() {
        if (!isController() || power <= 0 || clearBlocks <= 0 || getFuelProperties() == null ||
                !isOxygen(oxidizerTank.getPrimaryHandler().getFluid()))
            return;
        if (level == null || level.random.nextFloat() > power)
            return;

        Vec3 spawn = getNozzleCenter();
        Direction facing = getFacing();
        Vec3 direction = new Vec3(-facing.getStepX(), -facing.getStepY(), -facing.getStepZ());
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

    // ------------------------------------------------------------------
    // Physics
    // ------------------------------------------------------------------

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        if (!isController() || currentThrust <= 0 || power <= 0)
            return;

        Vec3 nozzle = getNozzleCenter();
        Vector3d point = new Vector3d(nozzle.x, nozzle.y, nozzle.z);
        Direction facing = getFacing();
        Vector3d impulse = new Vector3d(
                facing.getStepX() * currentThrust * timeStep,
                facing.getStepY() * currentThrust * timeStep,
                facing.getStepZ() * currentThrust * timeStep);

        QueuedForceGroup forceGroup = subLevel.getOrCreateQueuedForceGroup(ForceGroups.PROPULSION.get());
        forceGroup.applyAndRecordPointForce(point, impulse);
    }

    // ------------------------------------------------------------------
    // Goggles / rendering / persistence
    // ------------------------------------------------------------------

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (!isController()) {
            ThrusterBlockEntity controller = getControllerBE();
            if (controller != null)
                return controller.addToGoggleTooltip(tooltip, isPlayerSneaking);
        }

        NorthstarLang.addTankTooltip(tooltip, fuelTank.getPrimaryHandler(), getFuelTankLabel());
        NorthstarLang.addTankTooltip(tooltip, oxidizerTank.getPrimaryHandler(), getOxidizerTankLabel());

        return true;
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public AABB getRenderBoundingBox() {
        if (!isController() || !isMultiblock())
            return super.getRenderBoundingBox();

        Direction facing = getFacing();
        Direction uAxis = crossAxisU(facing);
        Direction vAxis = crossAxisV(facing);
        BlockPos far = worldPosition.offset(
                uAxis.getStepX() * (width - 1) + vAxis.getStepX() * (width - 1) + facing.getStepX() * (length - 1),
                uAxis.getStepY() * (width - 1) + vAxis.getStepY() * (width - 1) + facing.getStepY() * (length - 1),
                uAxis.getStepZ() * (width - 1) + vAxis.getStepZ() * (width - 1) + facing.getStepZ() * (length - 1));

        int minX = Math.min(worldPosition.getX(), far.getX());
        int minY = Math.min(worldPosition.getY(), far.getY());
        int minZ = Math.min(worldPosition.getZ(), far.getZ());
        int maxX = Math.max(worldPosition.getX(), far.getX()) + 1;
        int maxY = Math.max(worldPosition.getY(), far.getY()) + 1;
        int maxZ = Math.max(worldPosition.getZ(), far.getZ()) + 1;

        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.25);
        Direction exhaust = facing.getOpposite();
        return box.expandTowards(
                exhaust.getStepX() * (width * 0.5 + 0.25),
                exhaust.getStepY() * (width * 0.5 + 0.25),
                exhaust.getStepZ() * (width * 0.5 + 0.25));
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

    // ------------------------------------------------------------------
    // Sable-safe helpers
    // ------------------------------------------------------------------

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

}
