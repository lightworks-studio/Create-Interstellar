package com.lightning.northstar.block.tech.thruster;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Shared base for the rocket and liquid thrusters. Both are FACING-based multiblock
 * assemblies: {@link #FACING} is the direction of thrust (the body extends from the
 * controller toward FACING, the exhaust/nozzle is on the opposite side).
 *
 * <p>Concrete subclasses provide the block-entity class/type and their own model.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public abstract class ThrusterBlock<T extends ThrusterBlockEntity> extends DirectionalBlock implements IBE<T>, IWrenchable {

    public static final BooleanProperty MULTIBLOCK = BooleanProperty.create("multi");

    protected ThrusterBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(FACING, Direction.UP)
                .setValue(MULTIBLOCK, false));
    }

    @Override
    protected abstract MapCodec<? extends DirectionalBlock> codec();

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder.add(FACING, MULTIBLOCK));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Thrust along the clicked face, so the nozzle (opposite side) seats against the clicked surface.
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, world, pos, oldState, moved);
        if (!world.isClientSide()) {
            withBlockEntityDo(world, pos, be -> {
                be.updateConnectivity();
                be.setRedstoneInput(world.getBestNeighborSignal(pos));
            });
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level world, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, world, pos, neighborBlock, neighborPos, movedByPiston);
        if (!world.isClientSide())
            withBlockEntityDo(world, pos, be -> be.setRedstoneInput(world.getBestNeighborSignal(pos)));
    }

    @Override
    public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.is(newState.getBlock()))
            return;

        if (!world.isClientSide) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ThrusterBlockEntity thruster) {
                ThrusterBlockEntity controller = thruster.isController() ? thruster : thruster.getControllerBE();
                if (controller != null)
                    controller.disassembleMulti();
            }
        }

        super.onRemove(state, world, pos, newState, isMoving);
        IBE.onRemove(state, world, pos, newState);
    }

    @Override
    public abstract Class<T> getBlockEntityClass();

    @Override
    public abstract BlockEntityType<? extends T> getBlockEntityType();

    /**
     * Whether a world block at {@code pos} is a compatible thruster block of the same
     * type as this one (used to detect neighbours and to form/validate multiblocks).
     */
    public boolean isCompatibleBlock(BlockState state) {
        return state.is(this);
    }

}
