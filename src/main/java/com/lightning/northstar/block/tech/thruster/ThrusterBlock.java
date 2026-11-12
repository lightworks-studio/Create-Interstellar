package com.lightning.northstar.block.tech.thruster;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import com.lightning.northstar.content.NorthstarBlockEntityTypes;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ThrusterBlock extends AbstractThrusterBlock {
    public static final MapCodec<ThrusterBlock> CODEC = simpleCodec(ThrusterBlock::new);
    public static final BooleanProperty MULTIBLOCK = BooleanProperty.create("multi");

    public ThrusterBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(MULTIBLOCK, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MULTIBLOCK);
    }

    @Override
    protected VoxelShape getShape(final BlockState state,
                                  final BlockGetter level,
                                  final BlockPos pos,
                                  final CollisionContext context) {
        if (state.hasProperty(MULTIBLOCK) && state.getValue(MULTIBLOCK)) {
            return Shapes.block();
        }
        Direction direction = state.getValue(FACING);
        if (direction == Direction.UP || direction == Direction.DOWN) direction = direction.getOpposite();
        return ThrusterShapes.THRUSTER.get(direction);
    }

    @Override
    public Class<AbstractThrusterBlockEntity> getBlockEntityClass() {
        return AbstractThrusterBlockEntity.class;
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(@Nonnull BlockPos pos, @Nonnull BlockState state) {
        return new RocketThrusterBlockEntity(NorthstarBlockEntityTypes.ROCKET_THRUSTER.get(), pos, state);
    }

    @Override
    public BlockEntityType<? extends AbstractThrusterBlockEntity> getBlockEntityType() {
        return NorthstarBlockEntityTypes.ROCKET_THRUSTER.get();
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RocketThrusterBlockEntity thruster) {
                RocketThrusterBlockEntity controller = thruster.isController() ? thruster : thruster.getControllerBE();
                if (controller != null) {
                    controller.disassembleMulti();
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!state.is(newState.getBlock()) && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            RocketThrusterBlockEntity.requestConnectivityAround(serverLevel, pos, true);
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        boolean facingChanged = oldState.hasProperty(FACING) && state.getValue(FACING) != oldState.getValue(FACING);
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel
                && (!oldState.is(state.getBlock()) || facingChanged)) {
            RocketThrusterBlockEntity.requestConnectivityAround(serverLevel, pos, true);
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)
                || !level.hasChunk(neighborPos.getX() >> 4, neighborPos.getZ() >> 4)) return;
        // Ignore ordinary redstone/neighbour activity outside the engine. Structural
        // removals are explicitly handled by onRemove, including the old controller.
        BlockState neighbour = SimulatedThrustAdapter.getBlockStateSafe(level, neighborPos);
        if (neighbour.getBlock() instanceof ThrusterBlock) {
            RocketThrusterBlockEntity.requestConnectivityAround(serverLevel, neighborPos, false);
        }
    }

    @Override
    protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level,
                        BlockPos pos, net.minecraft.util.RandomSource random) {
        super.tick(state, level, pos, random);
        var be = SimulatedThrustAdapter.getBlockEntitySafe(level, pos);
        if (be instanceof RocketThrusterBlockEntity thruster) thruster.runScheduledConnectivityCheck();
    }

    @Nullable
    @Override
    public <T extends net.minecraft.world.level.block.entity.BlockEntity> BlockEntityTicker<T> getTicker(@Nonnull Level level, @Nonnull BlockState state, @Nonnull BlockEntityType<T> type) {
        if (type == NorthstarBlockEntityTypes.ROCKET_THRUSTER.get()) {
            return new SmartBlockEntityTicker<>();
        }
        return null;
    }
}
