package com.lightning.northstar.block.tech.rocket_thruster;

import com.lightning.northstar.content.NorthstarPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class RocketThrusterRenderer extends SmartBlockEntityRenderer<RocketThrusterBlockEntity> {

    public RocketThrusterRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(RocketThrusterBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        if (!be.isController() || !be.isMultiblock())
            return;

        int width = be.getWidth();
        int length = be.getLength();
        int copperEnd = width == 2 ? 8 : 6;
        float capHeight = (16 - copperEnd) / 16f * width;

        PartialModel body = width == 2
                ? NorthstarPartialModels.ROCKET_THRUSTER_MULTIBLOCK_2X2X2_BODY
                : NorthstarPartialModels.ROCKET_THRUSTER_MULTIBLOCK_3X3X3_BODY;
        PartialModel cap = width == 2
                ? NorthstarPartialModels.ROCKET_THRUSTER_MULTIBLOCK_2X2X2_CAP
                : NorthstarPartialModels.ROCKET_THRUSTER_MULTIBLOCK_3X3X3_CAP;

        ms.pushPose();
        ms.translate(0, length, 0);
        ms.mulPose(Axis.XP.rotationDegrees(90));
        ms.scale(width, width, length - capHeight);

        CachedBuffers.partial(body, be.getBlockState())
                .light(light)
                .overlay(overlay)
                .renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));

        ms.popPose();

        ms.pushPose();
        ms.translate(0, capHeight, 0);
        ms.mulPose(Axis.XP.rotationDegrees(90));
        ms.scale(width, width, width);

        CachedBuffers.partial(cap, be.getBlockState())
                .light(light)
                .overlay(overlay)
                .renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));

        ms.popPose();
    }

}
