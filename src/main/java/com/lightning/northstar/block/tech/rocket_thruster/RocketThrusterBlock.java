package com.lightning.northstar.block.tech.rocket_thruster;

import com.lightning.northstar.content.NorthstarBlockEntityTypes;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class RocketThrusterBlock extends Block implements IBE<RocketThrusterBlockEntity>, IWrenchable {

    public static final MapCodec<RocketThrusterBlock> CODEC = simpleCodec(RocketThrusterBlock::new);

    public static final BooleanProperty TOP = BooleanProperty.create("top");
    public static final BooleanProperty BOTTOM = BooleanProperty.create("bottom");
    public static final BooleanProperty MULTI = BooleanProperty.create("multi");

    public RocketThrusterBlock(Properties properties) {
        super(properties);

        registerDefaultState(defaultBlockState()
                .setValue(TOP, false)
                .setValue(BOTTOM, false)
                .setValue(MULTI, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TOP, BOTTOM, MULTI);
    }

    @Override
    public Class<RocketThrusterBlockEntity> getBlockEntityClass() {
        return RocketThrusterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends RocketThrusterBlockEntity> getBlockEntityType() {
        return NorthstarBlockEntityTypes.ROCKET_THRUSTER.get();
    }

    @Override
    public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean moved) {
        if (!(oldState.getBlock() instanceof RocketThrusterBlock)) {
            updateConnectivity(world, pos, true);
            withBlockEntityDo(world, pos, RocketThrusterBlockEntity::updateConnectivity);
        }
    }

    @Override
    public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            withBlockEntityDo(world, pos, thruster -> {
                RocketThrusterBlockEntity controller = thruster.getControllerBE();
                if (controller != null)
                    controller.disassembleMulti();
            });
            IBE.onRemove(state, world, pos, newState);
        }

        if (!(newState.getBlock() instanceof RocketThrusterBlock))
            updateConnectivity(world, pos, false);
    }

    @Override
    public void neighborChanged(BlockState state, Level world, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, world, pos, neighborBlock, neighborPos, movedByPiston);

        withBlockEntityDo(world, pos, thruster -> thruster.setRedstoneInput(world.getBestNeighborSignal(pos)));
    }

    private void updateConnectivity(Level world, BlockPos pos, boolean added) {
        BlockState state = defaultBlockState()
                .setValue(TOP, updateConnectivity(world, pos.above(), added, BOTTOM))
                .setValue(BOTTOM, updateConnectivity(world, pos.below(), added, TOP));

        if (added)
            world.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE);
    }

    private boolean updateConnectivity(Level world, BlockPos pos, boolean added, BooleanProperty property) {
        BlockState state = RocketThrusterBlockEntity.getBlockStateSafe(world, pos);
        if (state.getBlock() instanceof RocketThrusterBlock) {
            world.setBlock(pos, state.setValue(property, added), Block.UPDATE_CLIENTS | Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE);
            return true;
        }
        return false;
    }

}
