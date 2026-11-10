package com.lightning.northstar.block.tech.rocket_thruster;

import com.lightning.northstar.block.tech.thruster.ThrusterRenderer;
import com.lightning.northstar.content.NorthstarPartialModels;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class RocketThrusterRenderer extends ThrusterRenderer<RocketThrusterBlockEntity> {

    public RocketThrusterRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected PartialModel getBody(int width) {
        return width == 2
                ? NorthstarPartialModels.ROCKET_THRUSTER_MULTIBLOCK_2X2X2_BODY
                : NorthstarPartialModels.ROCKET_THRUSTER_MULTIBLOCK_3X3X3_BODY;
    }

    @Override
    protected PartialModel getCap(int width) {
        return width == 2
                ? NorthstarPartialModels.ROCKET_THRUSTER_MULTIBLOCK_2X2X2_CAP
                : NorthstarPartialModels.ROCKET_THRUSTER_MULTIBLOCK_3X3X3_CAP;
    }

}
