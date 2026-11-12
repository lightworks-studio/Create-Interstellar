package com.lightning.northstar.block.tech.thruster;

import com.lightning.northstar.Northstar;
import com.lightning.northstar.config.NorthstarConfigs;
import com.lightning.northstar.content.NorthstarBlockEntityTypes;
import com.lightning.northstar.content.NorthstarTags.NorthstarFluidTags;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.utility.CreateLang;
import dev.ryanhcode.sable.Sable;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.joml.Vector3d;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RocketThrusterBlockEntity extends AbstractThrusterBlockEntity {
    public static final int MAX_WIDTH = 3;

    private static final BehaviourType<SmartFluidTankBehaviour> FUEL_TANK = new BehaviourType<>("Fuel");
    private static final BehaviourType<SmartFluidTankBehaviour> OXIDIZER_TANK = new BehaviourType<>("Oxidizer");

    public SmartFluidTankBehaviour tank;
    public SmartFluidTankBehaviour oxidizerTank;

    protected boolean updateConnectivity = true;
    protected double lastConsumedMbPerTick = 0.0d;
    protected double lastOxidizerConsumedMbPerTick = 0.0d;
    protected double fuelDrainAccumulator = 0.0d;
    protected double oxidizerDrainAccumulator = 0.0d;
    // Runtime-only: serialized topology is a hint until the current world validates it.
    private static final int DISASSEMBLY_GRACE_TICKS = 10;
    private static final int CONNECTIVITY_INTERVAL = 20;
    private int disassemblyCooldown = DISASSEMBLY_GRACE_TICKS;
    private int connectivityTicks;
    private int invalidTopologyChecks;
    private volatile boolean topologyReady;

    private enum CubeStatus { VALID, INVALID, UNAVAILABLE }


    public RocketThrusterBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
    }

    public RocketThrusterBlockEntity(BlockPos pos, BlockState state) {
        this(NorthstarBlockEntityTypes.ROCKET_THRUSTER.get(), pos, state);
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, NorthstarBlockEntityTypes.ROCKET_THRUSTER.get(), (be, face) -> be.getFluidHandler(face));
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        tank = new SmartFluidTankBehaviour(FUEL_TANK, this, 1, getBaseTankCapacityMb(), false);
        behaviours.add(tank);
        tank.getPrimaryHandler().setValidator(stack -> ThrusterFuelManager.getProperties(stack.getFluid()) != null);

        oxidizerTank = new SmartFluidTankBehaviour(OXIDIZER_TANK, this, 1, getBaseTankCapacityMb(), false);
        behaviours.add(oxidizerTank);
        oxidizerTank.getPrimaryHandler().setValidator(stack ->
                NorthstarFluidTags.C_OXYGEN.matches(stack) || NorthstarFluidTags.C_LIQUID_OXYGEN.matches(stack));
    }


    @Nullable
    public RocketThrusterBlockEntity getControllerBE() {
        if (isController()) return this;
        if (level == null || width < 2 || width > MAX_WIDTH
                || !containsCell(controllerPos, width, worldPosition)
                || !isCellLoaded(controllerPos)) return null;
        net.minecraft.world.level.block.entity.BlockEntity be =
                SimulatedThrustAdapter.getBlockEntitySafe(level, controllerPos);
        if (!(be instanceof RocketThrusterBlockEntity ctrl)
                || ctrl.isRemoved() || !ctrl.isController() || ctrl.width != width
                || ctrl.getClass() != getClass() || ctrl.getFacing() != getFacing()
                || ctrl.getBlockState().getBlock() != getBlockState().getBlock()
                || !sameSubLevel(ctrl.worldPosition)) return null;
        return ctrl;
    }

    @Override
    public void tick() {
        if (level == null || isRemoved()) return;
        // Resolve ownership before the base tick can consume fuel or produce thrust.
        if (!level.isClientSide) tickConnectivity();
        super.tick();
    }

    private void tickConnectivity() {
        if (disassemblyCooldown > 0) {
            disassemblyCooldown--;
            setTopologyReady(false);
            return;
        }
        if (++connectivityTicks < CONNECTIVITY_INTERVAL && !updateConnectivity) return;
        connectivityTicks = 0;
        updateConnectivity = false;
        if (!supportsMultiblock()) {
            if (width != 1 || controllerPos != null) resetMultiblockForRebuild();
            setTopologyReady(true);
            reconcileMultiblockState();
            return;
        }

        BlockPos origin = isController() ? worldPosition : controllerPos;
        if (width > 1 || controllerPos != null) {
            CubeStatus status = inspectFormedCube(origin, width, getFacing());
            if (status == CubeStatus.UNAVAILABLE) {
                setTopologyReady(false);
                stopThrustForRebuild();
                return; // Never turn an unloaded chunk into a missing member.
            }
            if (status == CubeStatus.INVALID) {
                setTopologyReady(false);
                stopThrustForRebuild();
                // Require repeated observations after settling; placement may span ticks.
                if (++invalidTopologyChecks < 2) return;
                if (isController() && isMultiblock()) disassembleMulti();
                else resetMultiblockForRebuild();
                return;
            } else {
                invalidTopologyChecks = 0;
                setTopologyReady(true);
                reconcileMultiblockState();
                // A complete 2-cube may grow into a complete 3-cube.
                if (isController() && width < MAX_WIDTH) tryAssemble();
                return;
            }
        }
        invalidTopologyChecks = 0;
        setTopologyReady(true);
        reconcileMultiblockState();
        tryAssemble(); // Kept periodic even after failure; no neighbour hook is required.
    }

    private void setTopologyReady(boolean ready) {
        if (topologyReady == ready) return;
        topologyReady = ready;
        if (level != null && !level.isClientSide) level.invalidateCapabilities(worldPosition);
    }

    private boolean isCellLoaded(BlockPos pos) {
        return level != null && level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private boolean sameSubLevel(BlockPos pos) {
        return Sable.HELPER.getContaining(level, pos)
                == Sable.HELPER.getContaining(level, worldPosition);
    }

    private static boolean containsCell(BlockPos origin, int size, BlockPos pos) {
        return origin != null && pos.getX() >= origin.getX() && pos.getX() < origin.getX() + size
                && pos.getY() >= origin.getY() && pos.getY() < origin.getY() + size
                && pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + size;
    }

    private CubeStatus inspectFormedCube(BlockPos origin, int size, Direction facing) {
        if (level == null || origin == null || size < 2 || size > MAX_WIDTH
                || !containsCell(origin, size, worldPosition)) return CubeStatus.INVALID;
        // Check availability first, independently of iteration order.
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) for (int z = 0; z < size; z++) {
            if (!isCellLoaded(origin.offset(x, y, z))) return CubeStatus.UNAVAILABLE;
        }
        boolean invalid = false;
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) for (int z = 0; z < size; z++) {
            BlockPos pos = origin.offset(x, y, z);
            BlockState state = SimulatedThrustAdapter.getBlockStateSafe(level, pos);
            if (!sameSubLevel(pos) || state.getBlock() != getBlockState().getBlock()
                    || !state.hasProperty(AbstractThrusterBlock.FACING)
                    || state.getValue(AbstractThrusterBlock.FACING) != facing) {
                invalid = true;
                continue;
            }
            net.minecraft.world.level.block.entity.BlockEntity be = SimulatedThrustAdapter.getBlockEntitySafe(level, pos);
            if (be == null) return CubeStatus.UNAVAILABLE;
            if (!(be instanceof RocketThrusterBlockEntity t) || t.isRemoved()
                    || t.getClass() != getClass() || !t.supportsMultiblock() || t.width != size
                    || (pos.equals(origin) ? !t.isController() : !origin.equals(t.controllerPos))) {
                invalid = true;
            }
        }
        return invalid ? CubeStatus.INVALID : CubeStatus.VALID;
    }

    private void stopThrustForRebuild() {
        boolean changed = thrusterData.getThrust() != 0 || lastConsumedMbPerTick != 0
                || lastOxidizerConsumedMbPerTick != 0;
        thrusterData.setThrust(0);
        lastConsumedMbPerTick = 0;
        lastOxidizerConsumedMbPerTick = 0;
        isThrustDirty = true;
        if (changed) {
            setChanged();
            notifyUpdate();
        }
    }

    private void reconcileMultiblockState() {
        if (level == null || level.isClientSide || !isCellLoaded(worldPosition)) return;
        BlockState state = SimulatedThrustAdapter.getBlockStateSafe(level, worldPosition);
        // Do not restore a block which is being removed, or change another block's state.
        if (state.getBlock() != getBlockState().getBlock() || !state.hasProperty(ThrusterBlock.MULTIBLOCK)) return;
        if (state.getValue(ThrusterBlock.MULTIBLOCK) != isMultiblock()) {
            level.setBlock(worldPosition, state.setValue(ThrusterBlock.MULTIBLOCK, isMultiblock()), Block.UPDATE_CLIENTS);
        }
    }

    private void resizeTanksPreservingContents() {
        int cap = getBaseTankCapacityMb() * (isController() ? width * width * width : 1);
        // An orphan can own more than one block's capacity. Keep it until it is drained
        // or a future compatible assembly can accept it; never trim recovery contents.
        if (tank != null) tank.getPrimaryHandler().setCapacity(Math.max(cap, tank.getPrimaryHandler().getFluidAmount()));
        if (oxidizerTank != null) oxidizerTank.getPrimaryHandler().setCapacity(Math.max(cap, oxidizerTank.getPrimaryHandler().getFluidAmount()));
    }

    private void topologyChanged() {
        boundingBox = null;
        multiblockBoundingBox = null;
        resizeTanksPreservingContents();
        stopThrustForRebuild();
        if (level != null && !level.isClientSide) {
            reconcileMultiblockState();
            level.invalidateCapabilities(worldPosition);
            setChanged();
            notifyUpdate();
        }
    }

    private void resetMultiblockForRebuild() {
        width = 1;
        controllerPos = null;
        updateConnectivity = true;
        invalidTopologyChecks = 0;
        topologyReady = false;
        topologyChanged();
    }

    protected void tryAssemble() {
        Direction facing = getBlockState().getValue(AbstractThrusterBlock.FACING);
        for (int size = MAX_WIDTH; size >= 2; size--) {
            BlockPos origin = findCubeOrigin(size, facing);
            if (origin != null) {
                formMulti(origin, size, facing);
                return;
            }
        }
    }

    @Nullable
    protected BlockPos findCubeOrigin(int size, Direction facing) {
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                for (int dz = 0; dz < size; dz++) {
                    BlockPos origin = worldPosition.offset(-dx, -dy, -dz);
                    if (isValidCube(origin, size, facing)) return origin;
                }
            }
        }
        return null;
    }

    protected boolean isValidCube(BlockPos origin, int size, Direction facing) {
        if (level == null || size < 2 || size > MAX_WIDTH) return false;
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) for (int z = 0; z < size; z++) {
            BlockPos pos = origin.offset(x, y, z);
            if (!isCellLoaded(pos) || !sameSubLevel(pos)) return false;
            BlockState state = SimulatedThrustAdapter.getBlockStateSafe(level, pos);
            if (state.getBlock() != getBlockState().getBlock()
                    || !state.hasProperty(AbstractThrusterBlock.FACING)
                    || state.getValue(AbstractThrusterBlock.FACING) != facing) return false;
            net.minecraft.world.level.block.entity.BlockEntity be = SimulatedThrustAdapter.getBlockEntitySafe(level, pos);
            if (!(be instanceof RocketThrusterBlockEntity t) || t.isRemoved()
                    || t.getClass() != getClass() || !t.supportsMultiblock()
                    || t.disassemblyCooldown > 0 || !t.topologyReady
                    || t.tank == null || t.oxidizerTank == null) return false;
            if (t.isMultiblock()) {
                RocketThrusterBlockEntity ctrl = t.getControllerBE();
                // Upgrade only if the entire old assembly is enclosed, so no member
                // outside this cube retains a link to a repurposed controller.
                if (ctrl == null || t.width >= size
                        || !containsCell(origin, size, ctrl.worldPosition)
                        || !containsCell(origin, size, ctrl.worldPosition.offset(t.width - 1, t.width - 1, t.width - 1))
                        || !ctrl.isValidFormedCube(ctrl.worldPosition, ctrl.width, facing)) return false;
            } else if (!t.isController()) return false;
        }
        return true;
    }

    protected void formMulti(BlockPos origin, int size, Direction facing) {
        if (level == null || level.isClientSide || !isValidCube(origin, size, facing)) return;
        List<RocketThrusterBlockEntity> members = new ArrayList<>(size * size * size);
        FluidStack totalFuel = FluidStack.EMPTY;
        FluidStack totalOxidizer = FluidStack.EMPTY;
        int newCap = getBaseTankCapacityMb() * size * size * size;
        // Preflight every reference, fluid component and capacity before changing anything.
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) for (int z = 0; z < size; z++) {
            net.minecraft.world.level.block.entity.BlockEntity be = SimulatedThrustAdapter.getBlockEntitySafe(level, origin.offset(x, y, z));
            if (!(be instanceof RocketThrusterBlockEntity t) || t.tank == null || t.oxidizerTank == null) return;
            FluidStack fuel = t.tank.getPrimaryHandler().getFluid();
            FluidStack ox = t.oxidizerTank.getPrimaryHandler().getFluid();
            if (!canPool(totalFuel, fuel, newCap) || !canPool(totalOxidizer, ox, newCap)) return;
            totalFuel = mergeFluid(totalFuel, fuel);
            totalOxidizer = mergeFluid(totalOxidizer, ox);
            members.add(t);
        }
        RocketThrusterBlockEntity controller = members.get(0); // Origin is always first.
        double fuelRemainder = 0;
        double oxidizerRemainder = 0;
        for (RocketThrusterBlockEntity t : members) {
            t.setTopologyReady(false);
            t.stopThrustForRebuild();
        }
        for (RocketThrusterBlockEntity t : members) {
            fuelRemainder += t.fuelDrainAccumulator;
            oxidizerRemainder += t.oxidizerDrainAccumulator;
            t.tank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
            t.oxidizerTank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
            t.fuelDrainAccumulator = 0;
            t.oxidizerDrainAccumulator = 0;
            t.controllerPos = t == controller ? null : origin.immutable();
            t.width = size;
            t.invalidTopologyChecks = 0;
            t.updateConnectivity = false;
        }
        controller.tank.getPrimaryHandler().setCapacity(newCap);
        controller.oxidizerTank.getPrimaryHandler().setCapacity(newCap);
        controller.tank.getPrimaryHandler().setFluid(totalFuel);
        controller.oxidizerTank.getPrimaryHandler().setFluid(totalOxidizer);
        controller.fuelDrainAccumulator = fuelRemainder;
        controller.oxidizerDrainAccumulator = oxidizerRemainder;
        for (RocketThrusterBlockEntity t : members) {
            t.setTopologyReady(true);
            t.topologyChanged();
        }
        controller.calculateObstruction(level, origin, facing);
    }

    private static boolean canPool(FluidStack pool, FluidStack addition, int capacity) {
        return (pool.isEmpty() || addition.isEmpty() || FluidStack.isSameFluidSameComponents(pool, addition))
                && (long) pool.getAmount() + addition.getAmount() <= capacity;
    }

    public void disassembleMulti() {
        if (!isController() || !isMultiblock() || level == null || level.isClientSide) return;
        int size = width;
        List<RocketThrusterBlockEntity> members = new ArrayList<>();
        members.add(this);
        // Only touch loaded cells that still claim THIS controller. Missing cells recover
        // individually on their next tick; never load chunks or modify a neighbouring cube.
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) for (int z = 0; z < size; z++) {
            BlockPos pos = worldPosition.offset(x, y, z);
            if (pos.equals(worldPosition) || !isCellLoaded(pos) || !sameSubLevel(pos)) continue;
            net.minecraft.world.level.block.entity.BlockEntity be = SimulatedThrustAdapter.getBlockEntitySafe(level, pos);
            if (be instanceof RocketThrusterBlockEntity t && t.getClass() == getClass()
                    && t.width == size && worldPosition.equals(t.controllerPos)) members.add(t);
        }
        // Fill only accepted amounts. The source retains any incompatible or excess fluid,
        // including components, instead of silently discarding a leftover pool.
        for (RocketThrusterBlockEntity t : members) {
            if (t == this) continue;
            redistributeTank(tank, t.tank);
            redistributeTank(oxidizerTank, t.oxidizerTank);
        }
        for (RocketThrusterBlockEntity t : members) {
            t.resetMultiblockForRebuild();
            t.disassemblyCooldown = DISASSEMBLY_GRACE_TICKS;
        }
    }

    private void redistributeTank(SmartFluidTankBehaviour source, SmartFluidTankBehaviour target) {
        if (source == null || target == null) return;
        FluidStack fluid = source.getPrimaryHandler().getFluid();
        if (fluid.isEmpty()) return;
        int room = Math.max(0, getBaseTankCapacityMb() - target.getPrimaryHandler().getFluidAmount());
        if (room == 0) return;
        FluidStack offered = fluid.copy();
        offered.setAmount(Math.min(room, fluid.getAmount()));
        int accepted = target.getPrimaryHandler().fill(offered, IFluidHandler.FluidAction.EXECUTE);
        if (accepted > 0) source.getPrimaryHandler().drain(accepted, IFluidHandler.FluidAction.EXECUTE);
    }

    private static FluidStack mergeFluid(FluidStack pool, FluidStack addition) {
        if (addition.isEmpty()) return pool;
        if (pool.isEmpty()) return addition.copy();
        if (FluidStack.isSameFluidSameComponents(pool, addition)) {
            FluidStack out = pool.copy();
            out.grow(addition.getAmount());
            return out;
        }
        return pool;
    }

    private static FluidStack trimToCapacity(FluidStack stack, int cap) {
        if (stack.isEmpty() || stack.getAmount() <= cap) return stack;
        FluidStack out = stack.copy();
        out.setAmount(cap);
        return out;
    }

    public IFluidHandler getFluidHandler(Direction side) {
        RocketThrusterBlockEntity ctrl = isController() ? this : getControllerBE();
        if (ctrl == null || ctrl.tank == null || (level != null && !level.isClientSide
                && (!topologyReady || !ctrl.topologyReady))) return null;

        IFluidHandler fuel = ctrl.tank.getPrimaryHandler();
        IFluidHandler ox = (ctrl.oxidizerTank != null) ? ctrl.oxidizerTank.getPrimaryHandler() : null;
        IFluidHandler combined = (ox != null) ? new MultiFluidHandler(fuel, ox) : fuel;

        if (!ctrl.isMultiblock()) {
            if (side == null || side == getFluidCapSide()) {
                return combined;
            }
            return null;
        }

        if (side == null) {
            return combined;
        }

        if (!isFrontLayerCell(ctrl, ctrl.getBlockState().getValue(AbstractThrusterBlock.FACING))) return null;

        if (side == ctrl.getBlockState().getValue(AbstractThrusterBlock.FACING).getOpposite()) {
            return null;
        }

        return combined;
    }

    @Override
    public boolean supportsMultiblock() {
        return true;
    }

    private boolean isFrontLayerCell(RocketThrusterBlockEntity ctrl, Direction cubeFacing) {
        BlockPos origin = ctrl.worldPosition;
        int size = ctrl.width;
        int rel;
        switch (cubeFacing.getAxis()) {
            case X -> rel = worldPosition.getX() - origin.getX();
            case Y -> rel = worldPosition.getY() - origin.getY();
            case Z -> rel = worldPosition.getZ() - origin.getZ();
            default -> {
                return false;
            }
        }
        int frontIdx = cubeFacing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? size - 1 : 0;
        return rel == frontIdx;
    }

    @Override
    public void afterMove(ServerLevel oldLevel, ServerLevel newLevel, BlockState state, BlockPos oldPos, BlockPos newPos) {
        // Relative offsets in copied NBT are not rotated by every schematic/move path.
        // Retain local tank contents (even an old controller's whole cube tank).
        resetMultiblockForRebuild();
        disassemblyCooldown = DISASSEMBLY_GRACE_TICKS;
        connectivityTicks = 0;
        super.afterMove(oldLevel, newLevel, state, oldPos, newPos);
    }

    @Override
    public void updateThrust(BlockState currentBlockState) {
        if (!topologyReady || !isController()) {
            stopThrustForRebuild();
            return;
        }
        if (isMultiblock()) {
            updateMultiThrust(currentBlockState);
        } else {
            updateSingleThrust(currentBlockState);
        }
    }

    protected void updateSingleThrust(BlockState currentBlockState) {
        final double prevConsumedMbPerTick = lastConsumedMbPerTick;
        final double prevOxidizerConsumedMbPerTick = lastOxidizerConsumedMbPerTick;
        final int prevFuelAmount = tank != null ? tank.getPrimaryHandler().getFluidAmount() : 0;
        final int prevOxidizerAmount = oxidizerTank != null ? oxidizerTank.getPrimaryHandler().getFluidAmount() : 0;
        float thrust = 0;
        float currentPower = getPower();
        lastConsumedMbPerTick = 0.0d;
        lastOxidizerConsumedMbPerTick = 0.0d;
        if (isWorking() && currentPower > 0) {
            FluidThrusterProperties properties = getFuelProperties(fluidStack().getFluid());
            float obstructionEffect = calculateObstructionEffect();
            float thrustPercentage = Math.min(currentPower, obstructionEffect);

            if (thrustPercentage > 0 && properties != null) {
                final int tickRate = 10;
                double requestedConsumption = calculateFuelConsumption(currentPower, properties.consumptionMultiplier(), tickRate);
                int consumption = consumeFuelWithAccumulator(requestedConsumption);
                FluidStack drainedStack = tank.getPrimaryHandler().drain(consumption, IFluidHandler.FluidAction.EXECUTE);
                int fuelConsumed = drainedStack.getAmount();

                // Single thrusters burn oxidizer in 1:1 lockstep with fuel.
                int oxConsumption = consumeOxidizerWithAccumulator(requestedConsumption);
                FluidStack oxidizerDrained = (oxidizerTank != null)
                        ? oxidizerTank.getPrimaryHandler().drain(oxConsumption, IFluidHandler.FluidAction.EXECUTE)
                        : FluidStack.EMPTY;
                lastOxidizerConsumedMbPerTick = (double) oxidizerDrained.getAmount() / (double) tickRate;

                if ((fuelConsumed > 0 || (consumption == 0 && !fluidStack().isEmpty()))
                        && (oxidizerDrained.getAmount() > 0 || (oxConsumption == 0 && validOxidizer()))) {
                    // Keep thrust continuous for sub-1 mB windows: accumulator-based drain can
                    // legitimately round to 0 for this update while fluid is still available.
                    float fuelRatio = consumption > 0 ? (float) fuelConsumed / (float) consumption : 1.0f;
                    float oxRatio = oxConsumption > 0 ? (float) oxidizerDrained.getAmount() / (float) oxConsumption : 1.0f;
                    float consumptionRatio = Math.min(fuelRatio, oxRatio);
                    float fuelEfficiency = ThrusterFuelManager.getEfficiency(fluidStack().getFluid());
                    float baseThrustPn = (float) (getBaseThrust() * getThrustUnitsPerKn());
                    baseThrustPn *= (float) calculateAtmosphericFactor();
                    thrust = baseThrustPn * thrustPercentage * properties.thrustMultiplier() * fuelEfficiency * consumptionRatio;
                    lastConsumedMbPerTick = (double) fuelConsumed / (double) tickRate;
                }
            }
        }
        setThrustAndSync(thrust);
        if (didSingleTooltipTelemetryChange(prevConsumedMbPerTick, prevOxidizerConsumedMbPerTick, prevFuelAmount, prevOxidizerAmount)) {
            setChanged();
            notifyUpdate();
        }
        isThrustDirty = false;
    }

    protected int consumeOxidizerWithAccumulator(double requestedAmount) {
        oxidizerDrainAccumulator += requestedAmount;
        int consumption = (int) Math.floor(oxidizerDrainAccumulator);
        if (consumption > 0) {
            oxidizerDrainAccumulator -= consumption;
        }
        return consumption;
    }

    protected void updateMultiThrust(BlockState currentBlockState) {
        final double prevConsumedMbPerTick = lastConsumedMbPerTick;
        final double prevOxidizerConsumedMbPerTick = lastOxidizerConsumedMbPerTick;
        final int prevFuelAmount = tank != null ? tank.getPrimaryHandler().getFluidAmount() : 0;
        final int prevOxidizerAmount = oxidizerTank != null ? oxidizerTank.getPrimaryHandler().getFluidAmount() : 0;
        int n = width * width * width;
        float totalThrust = 0;
        float currentPower = getPower();
        lastConsumedMbPerTick = 0.0d;
        lastOxidizerConsumedMbPerTick = 0.0d;

        if (isWorking() && currentPower > 0) {
            FluidThrusterProperties properties = getFuelProperties(fluidStack().getFluid());
            float obstructionEffect = calculateObstructionEffect();
            float thrustPercentage = Math.min(currentPower, obstructionEffect);
            if (thrustPercentage > 0 && properties != null) {
                final int tickRate = 10;
                double baseConsumption = calculateFuelConsumption(currentPower, properties.consumptionMultiplier(), tickRate);

                boolean canUseOxidizer = validOxidizer();
                // Multiblock fuel efficiency always applies; oxidizer adds an extra multiplier.
                double fuelEff = getMultiblockFuelEfficiency(width);
                if (canUseOxidizer) {
                    fuelEff *= getMultiblockOxidizerEfficiency(width);
                }
                double fuelNeededDouble = baseConsumption * (double) n * fuelEff;
                int fuelNeeded = consumeFuelWithAccumulator(fuelNeededDouble);

                FluidStack fuelSim = tank.getPrimaryHandler().drain(fuelNeeded, IFluidHandler.FluidAction.SIMULATE);
                int fuelAvail = fuelSim.getAmount();
                float fuelRatio = fuelNeeded > 0
                        ? (float) fuelAvail / (float) fuelNeeded
                        : (fluidStack().isEmpty() ? 0.0f : 1.0f);

                if (fuelRatio > 0) {
                    int fuelActual = (int) (fuelNeeded * fuelRatio);
                    tank.getPrimaryHandler().drain(fuelActual, IFluidHandler.FluidAction.EXECUTE);

                    if (canUseOxidizer) {
                        double oxNeededDouble = baseConsumption * (double) n * getMultiblockOxidizerEfficiency(width);
                        int oxToDrain = consumeOxidizerWithAccumulator(oxNeededDouble * fuelRatio);
                        if (oxToDrain > 0) {
                            oxidizerTank.getPrimaryHandler().drain(oxToDrain, IFluidHandler.FluidAction.EXECUTE);
                        }
                        lastOxidizerConsumedMbPerTick = (double) oxToDrain / (double) tickRate;
                    }

                    float fuelEfficiency = ThrusterFuelManager.getEfficiency(fluidStack().getFluid());
                    float baseThrustPn = (float) (getBaseThrust() * getThrustUnitsPerKn());
                    baseThrustPn *= (float) calculateAtmosphericFactor();
                    float oxidizerThrustMultiplier = canUseOxidizer ? getMultiblockOxidizerThrustMultiplier(width) : 1.0f;
                    totalThrust = baseThrustPn * thrustPercentage * properties.thrustMultiplier() * fuelEfficiency * fuelRatio * n * getMultiblockThrustMultiplier(width) * oxidizerThrustMultiplier;
                    lastConsumedMbPerTick = (double) fuelActual / (double) tickRate;
                }
            }
        }

        // Controller holds the total thrust; non-members get 0 so they skip physics and sound.
        setThrustAndSync(totalThrust);
        if (didMultiTooltipTelemetryChange(prevConsumedMbPerTick, prevOxidizerConsumedMbPerTick, prevFuelAmount, prevOxidizerAmount)) {
            setChanged();
            notifyUpdate();
        }
        BlockPos origin = worldPosition;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                for (int z = 0; z < width; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    net.minecraft.world.level.block.entity.BlockEntity be = SimulatedThrustAdapter.getBlockEntitySafe(level, origin.offset(x, y, z));
                    if (be instanceof RocketThrusterBlockEntity t) {
                        t.getThrusterData().setThrust(0);
                        t.isThrustDirty = false;
                        t.lastConsumedMbPerTick = this.lastConsumedMbPerTick;
                        t.lastOxidizerConsumedMbPerTick = this.lastOxidizerConsumedMbPerTick;
                    }
                }
            }
        }
        isThrustDirty = false;
    }

    private boolean didSingleTooltipTelemetryChange(double prevConsumedMbPerTick, double prevOxidizerConsumedMbPerTick,
                                                    int prevFuelAmount, int prevOxidizerAmount) {
        if (Math.abs(lastConsumedMbPerTick - prevConsumedMbPerTick) > 1e-4d) {
            return true;
        }
        if (Math.abs(lastOxidizerConsumedMbPerTick - prevOxidizerConsumedMbPerTick) > 1e-4d) {
            return true;
        }
        int fuelAmountNow = tank != null ? tank.getPrimaryHandler().getFluidAmount() : 0;
        if (fuelAmountNow != prevFuelAmount) {
            return true;
        }
        int oxidizerAmountNow = oxidizerTank != null ? oxidizerTank.getPrimaryHandler().getFluidAmount() : 0;
        return oxidizerAmountNow != prevOxidizerAmount;
    }

    private boolean didMultiTooltipTelemetryChange(double prevConsumedMbPerTick, double prevOxidizerConsumedMbPerTick,
                                                   int prevFuelAmount, int prevOxidizerAmount) {
        if (Math.abs(lastConsumedMbPerTick - prevConsumedMbPerTick) > 1e-4d) {
            return true;
        }
        if (Math.abs(lastOxidizerConsumedMbPerTick - prevOxidizerConsumedMbPerTick) > 1e-4d) {
            return true;
        }
        int fuelAmountNow = tank != null ? tank.getPrimaryHandler().getFluidAmount() : 0;
        if (fuelAmountNow != prevFuelAmount) {
            return true;
        }
        int oxidizerAmountNow = oxidizerTank != null ? oxidizerTank.getPrimaryHandler().getFluidAmount() : 0;
        return oxidizerAmountNow != prevOxidizerAmount;
    }

    @Override
    public void calculateObstruction(Level lvl, BlockPos pos, Direction forwardDirection) {
        if (!isController() && isMultiblock()) {
            return;
        }
        if (isController() && isMultiblock()) {
            super.calculateObstruction(lvl, worldPosition, getBlockState().getValue(AbstractThrusterBlock.FACING));
            return;
        }
        super.calculateObstruction(lvl, pos, forwardDirection);
    }

    @Override
    protected float calculateObstructionEffect() {
        if (!isController() && isMultiblock()) {
            RocketThrusterBlockEntity ctrl = getControllerBE();
            return ctrl != null ? ctrl.calculateObstructionEffect() : 0f;
        }
        return super.calculateObstructionEffect();
    }

    @Override
    public int getEmptyBlocks() {
        if (!isController() && isMultiblock()) {
            RocketThrusterBlockEntity ctrl = getControllerBE();
            return ctrl != null ? ctrl.getEmptyBlocks() : 0;
        }
        return super.getEmptyBlocks();
    }

    private Vec3 getMultiblockCenterNozzlePositionLocal() {
        Direction exhaustDirection = getFacing().getOpposite();
        Vec3 localExhaustDirection = new Vec3(exhaustDirection.getStepX(), exhaustDirection.getStepY(), exhaustDirection.getStepZ());
        double half = width * 0.5d;
        Vec3 localCubeCenter = new Vec3(
                worldPosition.getX() + half,
                worldPosition.getY() + half,
                worldPosition.getZ() + half
        );
        return localCubeCenter.add(localExhaustDirection.scale(half + 0.45d));
    }

    @Override
    public Vec3 getParticleDebugNozzlePositionLocal() {
        if (!isController() && isMultiblock()) {
            RocketThrusterBlockEntity ctrl = getControllerBE();
            return ctrl != null ? ctrl.getParticleDebugNozzlePositionLocal() : super.getParticleDebugNozzlePositionLocal();
        }
        if (isController() && isMultiblock()) {
            return getMultiblockCenterNozzlePositionLocal();
        }
        return super.getParticleDebugNozzlePositionLocal();
    }

    @Override
    public WorldExhaustRay getWorldExhaustRay() {
        if (!isController() && isMultiblock()) {
            RocketThrusterBlockEntity ctrl = getControllerBE();
            return ctrl != null ? ctrl.getWorldExhaustRay() : super.getWorldExhaustRay();
        }
        if (!(isController() && isMultiblock()) || level == null) {
            return super.getWorldExhaustRay();
        }

        Direction exhaustDirection = getFacing().getOpposite();
        Vec3 localNozzle = getMultiblockCenterNozzlePositionLocal();
        Vector3d localNozzleVec = new Vector3d(localNozzle.x, localNozzle.y, localNozzle.z);
        Vector3d localExhaustVec = new Vector3d(exhaustDirection.getStepX(), exhaustDirection.getStepY(), exhaustDirection.getStepZ()).normalize();
        SimulatedThrustAdapter.Projection projection = SimulatedThrustAdapter.projectToWorld(level, worldPosition, localNozzleVec, localExhaustVec);

        Vec3 worldDirection = projection.direction();
        if (worldDirection.lengthSqr() < epsilon) {
            worldDirection = new Vec3(localExhaustVec.x, localExhaustVec.y, localExhaustVec.z);
        } else {
            worldDirection = worldDirection.normalize();
        }
        return new WorldExhaustRay(projection.level(), projection.position(), worldDirection);
    }

    @Override
    protected boolean shouldDamageEntities() {
        if (isMultiblock() && !isController()) {
            return false;
        }
        return super.shouldDamageEntities();
    }

    @Override
    public void sable$physicsTick(final dev.ryanhcode.sable.sublevel.ServerSubLevel subLevel, final dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle handle, final double timeStep) {
        if (!topologyReady || (isMultiblock() && !isController())) return;
        if (isMultiblock()) {
            float thrust = getCurrentThrust();
            if (thrust <= 0.0f || !Float.isFinite(thrust)) return;
            Vector3d directionLocal = new Vector3d(getThrustDirectionLocal()).normalize();
            Vec3 centerNozzle = getMultiblockCenterNozzlePositionLocal();
            Vector3d applicationPoint = new Vector3d(centerNozzle.x, centerNozzle.y, centerNozzle.z);
            Vector3d impulseLocal = new Vector3d(directionLocal).mul(thrust * timeStep);
            Vector3d adjustedImpulse = new Vector3d(impulseLocal).div(getThrustUnitsPerKn());
            SimulatedThrustAdapter.applyImpulseAtPoint(subLevel, applicationPoint, adjustedImpulse);
            return;
        }
        super.sable$physicsTick(subLevel, handle, timeStep);
    }

    private static float getMultiblockFuelEfficiency(int cubeWidth) {
        if (cubeWidth <= 1) return 1.0f;
        if (cubeWidth == 2) return NorthstarConfigs.server().thrusterMultiblock2xFuelEfficiency.get().floatValue();
        if (cubeWidth == 3) return NorthstarConfigs.server().thrusterMultiblock3xFuelEfficiency.get().floatValue();
        return 1.0f;
    }

    private static float getMultiblockOxidizerEfficiency(int cubeWidth) {
        if (cubeWidth <= 1) return 1.0f;
        if (cubeWidth == 2) return NorthstarConfigs.server().thrusterMultiblock2xOxidizerEfficiency.get().floatValue();
        if (cubeWidth == 3) return NorthstarConfigs.server().thrusterMultiblock3xOxidizerEfficiency.get().floatValue();
        return 1.0f;
    }

    private static float getMultiblockThrustMultiplier(int cubeWidth) {
        if (cubeWidth == 2) return NorthstarConfigs.server().thrusterMultiblock2xThrustMultiplier.get().floatValue();
        if (cubeWidth == 3) return NorthstarConfigs.server().thrusterMultiblock3xThrustMultiplier.get().floatValue();
        return 1.0f;
    }

    private static float getMultiblockOxidizerThrustMultiplier(int cubeWidth) {
        float oxidizerEfficiency = getMultiblockOxidizerEfficiency(cubeWidth);
        if (oxidizerEfficiency <= 0.0f) return 1.0f;
        return 1.0f / oxidizerEfficiency;
    }

    @Override
    public float getPower() {
        if (!isController() && isMultiblock()) {
            RocketThrusterBlockEntity ctrl = getControllerBE();
            return ctrl != null ? ctrl.getPower() : 0f;
        }
        if (controlMode == ControlMode.PERIPHERAL) {
            return digitalInput;
        }
        if (isController() && isMultiblock()) {
            return getAggregatedRedstone() / 15.0f;
        }
        return redstoneInput / 15.0f;
    }

    private int getAggregatedRedstone() {
        int max = redstoneInput;
        if (level == null) return max;
        BlockPos origin = worldPosition;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                for (int z = 0; z < width; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    net.minecraft.world.level.block.entity.BlockEntity be = SimulatedThrustAdapter.getBlockEntitySafe(level, origin.offset(x, y, z));
                    if (be instanceof RocketThrusterBlockEntity t && t.redstoneInput > max) {
                        max = t.redstoneInput;
                    }
                }
            }
        }
        return max;
    }

    @Override
    public void setRedstoneInput(int power) {
        if (this.redstoneInput == power) return;
        this.redstoneInput = power;
        if (controlMode == ControlMode.NORMAL) {
            dirtyThrust();
            notifyUpdate();
        }
        if (isMultiblock() && !isController()) {
            RocketThrusterBlockEntity ctrl = getControllerBE();
            if (ctrl != null) {
                ctrl.dirtyThrust();
                ctrl.notifyUpdate();
            }
        }
    }

    protected boolean validOxidizer() {
        return oxidizerTank != null && !oxidizerTank.isEmpty() && oxidizerTank.getPrimaryHandler().getFluidAmount() > 0;
    }

    @Override
    protected boolean isWorking() {
        return (level == null || level.isClientSide || topologyReady) && validFluid() && validOxidizer();
    }

    @Override
    public boolean shouldEmitPlume() {
        if (!super.shouldEmitPlume()) {
            return false;
        }
        if (isMultiblock()) {
            // Treat assembled cubes as one engine effect: only controller emits.
            if (!isController()) return false;
            if (calculateObstructionEffect() <= 0f) return false;
        }
        FluidThrusterProperties properties = getFuelProperties(fluidStack().getFluid());
        return properties != null;
    }

    @Override
    public void emitPlumeParticles(Level level, BlockPos pos, BlockState state) {
        if (!(isController() && isMultiblock())) {
            super.emitPlumeParticles(level, pos, state);
            return;
        }
        if (!shouldEmitPlume()) return;
        float power = getPower();
        float emissionScale = (float) Math.max(power, epsilon);
        if (power <= 0) return;

        Direction direction = state.getValue(AbstractThrusterBlock.FACING);
        Direction oppositeDirection = direction.getOpposite();

        Vec3 localExhaustDirection = new Vec3(oppositeDirection.getStepX(), oppositeDirection.getStepY(), oppositeDirection.getStepZ());
        // Emit from the center nozzle plane of the whole assembled cube.
        Vec3 localNozzlePosition = getMultiblockCenterNozzlePositionLocal();

        Vec3 worldNozzlePosition = Sable.HELPER.projectOutOfSubLevel(level, localNozzlePosition);
        Vec3 worldAheadPosition = Sable.HELPER.projectOutOfSubLevel(level, localNozzlePosition.add(localExhaustDirection));
        Vec3 worldExhaustDirection = worldAheadPosition.subtract(worldNozzlePosition);
        if (worldExhaustDirection.lengthSqr() < epsilon) {
            worldExhaustDirection = localExhaustDirection;
        } else {
            worldExhaustDirection = worldExhaustDirection.normalize();
        }

        double particleCountMultiplier = org.joml.Math.clamp(0.0d, PARTICLE_MULTIPLIER_CAP, getParticleCountMultiplier());
        if (particleCountMultiplier <= 0) return;
        double particleVelocityMultiplier = org.joml.Math.clamp(0.0d, PARTICLE_MULTIPLIER_CAP, getParticleVelocityMultiplier());

        float velocityScale = width == 2 ? 1.15f : 1.3f;
        Vector3d particleVelocity = new Vector3d(worldExhaustDirection.x, worldExhaustDirection.y, worldExhaustDirection.z)
                .mul(4.0f * emissionScale * velocityScale * particleVelocityMultiplier);
        ParticleOptions particleData = createParticleOptions();

        double speedPerTick = particleVelocity.length();
        int streamParticles = Math.max(1, (int) Math.ceil(speedPerTick / TARGET_PARTICLE_SPACING_BLOCKS * particleCountMultiplier));
        int crossSectionParticles = Math.max(1, (int) Math.round((width == 2 ? 14 : 28) * particleCountMultiplier));
        int particlesToSpawn = Math.max(streamParticles, crossSectionParticles);
        double plumeRadius = width == 2 ? 0.45 : 0.7;
        for (int i = 0; i < particlesToSpawn; i++) {
            double ox = (level.random.nextDouble() * 2.0 - 1.0) * plumeRadius;
            double oy = (level.random.nextDouble() * 2.0 - 1.0) * plumeRadius;
            double oz = (level.random.nextDouble() * 2.0 - 1.0) * plumeRadius;
            // Keep spread mostly perpendicular to exhaust direction.
            switch (oppositeDirection.getAxis()) {
                case X -> ox = 0.0;
                case Y -> oy = 0.0;
                case Z -> oz = 0.0;
            }
            double beamFrac = particlesToSpawn <= 1 ? 0.0 : (double) i / (double) particlesToSpawn;
            double px = worldNozzlePosition.x + ox + particleVelocity.x * beamFrac;
            double py = worldNozzlePosition.y + oy + particleVelocity.y * beamFrac;
            double pz = worldNozzlePosition.z + oz + particleVelocity.z * beamFrac;
            emitParticle(px, py, pz, particleVelocity, particleData);
        }
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (!isController() && isMultiblock()) {
            RocketThrusterBlockEntity ctrl = getControllerBE();
            if (ctrl != null && ctrl != this) {
                return ctrl.addToGoggleTooltip(tooltip, isPlayerSneaking);
            }
        }
        return super.addToGoggleTooltip(tooltip, isPlayerSneaking);
    }

    private boolean isValidFormedCube(BlockPos origin, int size, Direction facing) {
        return inspectFormedCube(origin, size, facing) == CubeStatus.VALID;
    }

    @Override
    protected double getBaseThrust() {
        return NorthstarConfigs.server().thrusterBaseThrust.get();
    }

    @Override
    protected double getRawThrustCap() {
        return NorthstarConfigs.server().thrusterBaseThrust.get();
    }

    public Direction getFluidCapSide() {
        return getBlockState().getValue(ThrusterBlock.FACING);
    }

    @Override
    public double getNozzleOffsetFromCenter() {
        return 0.95;
    }

    @Override
    protected LangBuilder getGoggleStatus() {
        if (fluidStack().isEmpty()) {
            return CreateLang.builder().add(Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.status.no_fuel")).style(ChatFormatting.RED);
        } else if (!validFluid()) {
            return CreateLang.builder().add(Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.status.wrong_fuel")).style(ChatFormatting.RED);
        } else if (!validOxidizer()) {
            return CreateLang.builder().add(Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.status.no_oxidizer")).style(ChatFormatting.RED);
        } else if (!isPowered()) {
            return CreateLang.builder().add(Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.status.not_powered")).style(ChatFormatting.GOLD);
        } else if (getEmptyBlocks() == 0) {
            return CreateLang.builder().add(Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.obstructed")).style(ChatFormatting.RED);
        } else {
            return CreateLang.builder().add(Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.status.working")).style(ChatFormatting.GREEN);
        }
    }

    @Override
    public double getDisplayedThrustPnForTooltip() {
        if (isMultiblock()) {
            RocketThrusterBlockEntity ctrl = isController() ? this : getControllerBE();
            if (ctrl == null) return super.getDisplayedThrustPnForTooltip();
            return ctrl.getThrusterData().getThrust();
        }
        return super.getDisplayedThrustPnForTooltip();
    }

    @Override
    protected void addThrusterDetails(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addThrusterDetails(tooltip, isPlayerSneaking);
        RocketThrusterBlockEntity ctrl = isController() ? this : getControllerBE();
        if (ctrl == null) return;

        if (ctrl.isMultiblock()) {
            // --- Efficiency bonus ---
            float fuelEff = getMultiblockFuelEfficiency(ctrl.width);
            float oxEff = getMultiblockOxidizerEfficiency(ctrl.width);
            int fuelSavePct = Math.round((1.0f - fuelEff) * 100.0f);
            int oxSavePct = Math.round((1.0f - oxEff) * 100.0f);
            if (fuelSavePct > 0 || oxSavePct > 0) {
                boolean hasOx = ctrl.validOxidizer();
                // Header line for base multiblock savings (always active for multiblocks).
                CreateLang.builder()
                        .add(Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.bulk_bonus"))
                        .text(":")
                        .space()
                        .add(Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.bulk_bonus_active").withStyle(ChatFormatting.GREEN))
                        .style(ChatFormatting.AQUA)
                        .forGoggles(tooltip);

                // Base multiblock fuel savings.
                if (fuelSavePct > 0) {
                    CreateLang.builder()
                            .add(Component.literal("  "))
                            .add(Component.literal("Fuel: ").withStyle(ChatFormatting.GRAY))
                            .add(Component.literal("-" + fuelSavePct + "%").withStyle(ChatFormatting.AQUA))
                            .forGoggles(tooltip);
                }

                // Additional oxidizer-based fuel savings.
                if (oxSavePct > 0) {
                    CreateLang.builder()
                            .add(Component.literal("  "))
                            .add(Component.literal("Oxidizer Bonus: ").withStyle(ChatFormatting.GRAY))
                            .add(hasOx
                                    ? Component.literal("-" + oxSavePct + "%").withStyle(ChatFormatting.AQUA)
                                    : Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.bulk_bonus_inactive").withStyle(ChatFormatting.RED))
                            .forGoggles(tooltip);
                }

                float oxThrustMultiplier = getMultiblockOxidizerThrustMultiplier(ctrl.width);
                int thrustBonusPct = Math.round((oxThrustMultiplier - 1.0f) * 100.0f);
                if (thrustBonusPct > 0) {
                    CreateLang.builder()
                            .add(Component.literal("  "))
                            .add(Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.thrust_bonus").withStyle(ChatFormatting.GRAY))
                            .add(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                            .add(hasOx
                                    ? Component.literal("+" + thrustBonusPct + "%").withStyle(ChatFormatting.AQUA)
                                    : Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.bulk_bonus_inactive").withStyle(ChatFormatting.RED))
                            .forGoggles(tooltip);
                }
            }
        }

        if (ctrl.tank == null) return;

        // --- Fuel tank (always shown) ---
        addFluidContainerTooltip(tooltip,
                Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.fuel_label"),
                ctrl.tank.getPrimaryHandler(), ctrl.lastConsumedMbPerTick);

        // --- Oxidizer tank: required for all sizes ---
        if (ctrl.oxidizerTank != null) {
            addFluidContainerTooltip(tooltip,
                    Component.translatable(Northstar.MOD_ID+".gui.goggles.thruster.oxidizer_label"),
                    ctrl.oxidizerTank.getPrimaryHandler(), ctrl.lastOxidizerConsumedMbPerTick);
        }
    }

    private void addFluidContainerTooltip(List<Component> tooltip, Component label,
                                          IFluidHandler handler, double consumptionRate) {
        if (handler == null || handler.getTanks() <= 0) return;
        int capacity = handler.getTankCapacity(0);
        FluidStack fluid = handler.getFluidInTank(0);
        int amount = fluid.getAmount();

        // Label line: "Fuel:"
        CreateLang.builder()
                .add(label.copy())
                .style(ChatFormatting.WHITE)
                .forGoggles(tooltip);

        // Storage line: "  100 / 1000 mB"
        CreateLang.builder()
                .add(Component.literal("  "))
                .add(Component.literal(Integer.toString(amount)).withStyle(ChatFormatting.AQUA))
                .add(Component.literal(" / ").withStyle(ChatFormatting.GRAY))
                .add(Component.literal(Integer.toString(capacity)).withStyle(ChatFormatting.AQUA))
                .add(Component.literal(" mB").withStyle(ChatFormatting.GRAY))
                .forGoggles(tooltip);

        // Consumption line: "  1.5 mB/t"
        CreateLang.builder()
                .add(Component.literal("  "))
                .add(Component.literal(String.format(Locale.ROOT, "%.1f", consumptionRate)).withStyle(ChatFormatting.AQUA))
                .add(Component.literal(" mB/t").withStyle(ChatFormatting.GRAY))
                .forGoggles(tooltip);
    }

    protected float getFuelEfficiencyMultiplier() {
        FluidStack currentFluid = fluidStack();
        if (currentFluid.isEmpty()) {
            return 1.0f;
        }
        return ThrusterFuelManager.getEfficiency(currentFluid.getFluid());
    }

    public FluidStack fluidStack() {
        RocketThrusterBlockEntity ctrl = isController() ? this : getControllerBE();
        if (ctrl == null) ctrl = this;
        return ctrl.tank == null ? FluidStack.EMPTY : ctrl.tank.getPrimaryHandler().getFluid();
    }

    public boolean validFluid() {
        if (fluidStack().isEmpty()) return false;
        return getFuelProperties(fluidStack().getFluid()) != null;
    }

    public FluidThrusterProperties getFuelProperties(Fluid fluid) {
        return ThrusterFuelManager.getProperties(fluid);
    }

    @Override
    protected ParticleOptions createParticleOptions() {
        FluidThrusterProperties properties = getFuelProperties(fluidStack().getFluid());
        if (properties == null) {
            return super.createParticleOptions();
        }
        FluidThrusterProperties resolvedProperties = properties;
        if (properties.useFluidColor()) {
            int fluidColor = IClientFluidTypeExtensions.of(fluidStack().getFluid()).getTintColor(fluidStack()) & 0xFFFFFF;
            resolvedProperties = new FluidThrusterProperties(
                    properties.thrustMultiplier(),
                    properties.consumptionMultiplier(),
                    properties.particleType(),
                    properties.overrideTextures(),
                    fluidColor,
                    true
            );
        }
        return resolvedProperties.particleType().createParticleOptions(resolvedProperties);
    }

    private double calculateFuelConsumption(float powerPercentage, float fluidPropertiesConsumptionMultiplier, int tickRate) {
        return getFuelConsumptionPerTickAtFullThrottle() * powerPercentage * fluidPropertiesConsumptionMultiplier * tickRate;
    }

    protected double getFuelConsumptionPerTickAtFullThrottle() {
        return NorthstarConfigs.server().thrusterFuelMbPerTickAtFullThrottle.get();
    }

    private int consumeFuelWithAccumulator(double requestedAmount) {
        if (requestedAmount <= 0.0d) {
            return 0;
        }
        double total = fuelDrainAccumulator + requestedAmount;
        int toConsume = (int) Math.floor(total);
        fuelDrainAccumulator = total - toConsume;
        return Math.max(0, toConsume);
    }

    @Override
    protected void write(CompoundTag compound, net.minecraft.core.HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putInt("Width", width);
        compound.putDouble("LastConsumedMbPerTick", lastConsumedMbPerTick);
        compound.putDouble("LastOxidizerConsumedMbPerTick", lastOxidizerConsumedMbPerTick);
        compound.putDouble("FuelDrainAccumulator", fuelDrainAccumulator);
        compound.putDouble("OxidizerDrainAccumulator", oxidizerDrainAccumulator);

        if (tank != null) {
            compound.put("FuelTankSync", tank.getPrimaryHandler().getFluid().saveOptional(registries));
        }
        if (oxidizerTank != null) {
            compound.put("OxidizerTankSync", oxidizerTank.getPrimaryHandler().getFluid().saveOptional(registries));
        }

        compound.remove("ControllerOffX");
        compound.remove("ControllerOffY");
        compound.remove("ControllerOffZ");
        compound.remove("UpdateConnectivity");
        if (controllerPos != null) {
            compound.putInt("ControllerOffX", controllerPos.getX() - worldPosition.getX());
            compound.putInt("ControllerOffY", controllerPos.getY() - worldPosition.getY());
            compound.putInt("ControllerOffZ", controllerPos.getZ() - worldPosition.getZ());
        }
        if (updateConnectivity) {
            compound.putBoolean("UpdateConnectivity", true);
        }
    }

    @Override
    protected void read(CompoundTag compound, net.minecraft.core.HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        width = Math.max(1, Math.min(MAX_WIDTH, compound.getInt("Width")));
        controllerPos = compound.contains("ControllerOffX") && compound.contains("ControllerOffY")
                && compound.contains("ControllerOffZ") ? worldPosition.offset(
                compound.getInt("ControllerOffX"), compound.getInt("ControllerOffY"),
                compound.getInt("ControllerOffZ")) : null;
        lastConsumedMbPerTick = compound.getDouble("LastConsumedMbPerTick");
        lastOxidizerConsumedMbPerTick = compound.getDouble("LastOxidizerConsumedMbPerTick");
        fuelDrainAccumulator = compound.getDouble("FuelDrainAccumulator");
        oxidizerDrainAccumulator = compound.getDouble("OxidizerDrainAccumulator");

        // Restore owner/width BEFORE choosing capacity, including on repeated client packets.
        // Explicit sync stacks keep the whole fluid even when stale NBT describes a single.
        restoreTank(tank, "FuelTankSync", compound, registries);
        restoreTank(oxidizerTank, "OxidizerTankSync", compound, registries);
        boundingBox = null;
        multiblockBoundingBox = null;
        if (!clientPacket) {
            setTopologyReady(false);
            updateConnectivity = true; // Never trust a saved false one-shot retry flag.
            disassemblyCooldown = DISASSEMBLY_GRACE_TICKS;
            connectivityTicks = 0;
            invalidTopologyChecks = 0;
            thrusterData.setThrust(0);
            isThrustDirty = true;
        }
    }

    private void restoreTank(SmartFluidTankBehaviour behaviour, String key, CompoundTag compound,
                             net.minecraft.core.HolderLookup.Provider registries) {
        if (behaviour == null) return;
        FluidStack fluid = compound.contains(key)
                ? FluidStack.parseOptional(registries, compound.getCompound(key))
                : behaviour.getPrimaryHandler().getFluid().copy();
        int capacity = getBaseTankCapacityMb() * (isController() ? width * width * width : 1);
        behaviour.getPrimaryHandler().setCapacity(Math.max(capacity, fluid.getAmount()));
        behaviour.getPrimaryHandler().setFluid(fluid);
    }

    protected int getBaseTankCapacityMb() {
        return NorthstarConfigs.server().thrusterFuelTankCapacityMb.get();
    }
}