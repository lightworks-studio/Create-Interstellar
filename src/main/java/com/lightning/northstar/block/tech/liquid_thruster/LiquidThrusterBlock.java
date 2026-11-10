package com.lightning.northstar.block.tech.liquid_thruster;

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
public class LiquidThrusterBlock extends ThrusterBlock<LiquidThrusterBlockEntity> {

    public static final MapCodec<LiquidThrusterBlock> CODEC = simpleCodec(LiquidThrusterBlock::new);

    public LiquidThrusterBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public Class<LiquidThrusterBlockEntity> getBlockEntityClass() {
        return LiquidThrusterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends LiquidThrusterBlockEntity> getBlockEntityType() {
        return NorthstarBlockEntityTypes.LIQUID_THRUSTER.get();
    }

    @Override
    public boolean isCompatibleBlock(BlockState state) {
        return state.is(NorthstarBlocks.LIQUID_THRUSTER.get());
    }

}
