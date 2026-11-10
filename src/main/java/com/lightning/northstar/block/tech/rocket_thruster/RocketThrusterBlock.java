package com.lightning.northstar.block.tech.rocket_thruster;

import com.lightning.northstar.block.tech.thruster.ThrusterBlock;
import com.lightning.northstar.content.NorthstarBlockEntityTypes;
import com.lightning.northstar.content.NorthstarBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class RocketThrusterBlock extends ThrusterBlock<RocketThrusterBlockEntity> {

    public static final MapCodec<RocketThrusterBlock> CODEC = simpleCodec(RocketThrusterBlock::new);

    public RocketThrusterBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
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
    public boolean isCompatibleBlock(BlockState state) {
        return state.is(NorthstarBlocks.ROCKET_THRUSTER.get());
    }

}
