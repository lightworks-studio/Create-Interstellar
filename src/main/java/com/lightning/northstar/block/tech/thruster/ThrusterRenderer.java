package com.lightning.northstar.block.tech.thruster;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SmartBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * Shared multiblock renderer for all thrusters. The partial models are authored pointing
 * upwards (body toward {@code +Y}, nozzle toward {@code -Y}); the assembly is rotated around
 * the controller block's center so that the body extends along {@link ThrusterBlock#FACING}.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public abstract class ThrusterRenderer<T extends ThrusterBlockEntity> extends SmartBlockEntityRenderer<T> {

    protected ThrusterRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    protected abstract PartialModel getBody(int width);

    protected abstract PartialModel getCap(int width);

    protected int getCopperEnd(int width) {
        return width == 2 ? 8 : 6;
    }

    @Override
    protected void renderSafe(T be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        if (!be.isController() || !be.isMultiblock())
            return;

        int width = be.getWidth();
        int length = be.getLength();
        int copperEnd = getCopperEnd(width);
        float capHeight = (16 - copperEnd) / 16f * width;

        ms.pushPose();
        ms.translate(0.5, 0.5, 0.5);
        applyFacingRotation(ms, be.getFacing());
        ms.translate(-0.5, -0.5, -0.5);

        ms.pushPose();
        ms.translate(0, length, 0);
        ms.mulPose(Axis.XP.rotationDegrees(90));
        ms.scale(width, width, length - capHeight);

        CachedBuffers.partial(getBody(width), be.getBlockState())
                .light(light)
                .overlay(overlay)
                .renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));

        ms.popPose();

        ms.pushPose();
        ms.translate(0, capHeight, 0);
        ms.mulPose(Axis.XP.rotationDegrees(90));
        ms.scale(width, width, width);

        CachedBuffers.partial(getCap(width), be.getBlockState())
                .light(light)
                .overlay(overlay)
                .renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));

        ms.popPose();
        ms.popPose();
    }

    /**
     * Rotates the up-authored assembly so that its body axis points along {@code facing}.
     */
    protected static void applyFacingRotation(PoseStack ms, Direction facing) {
        switch (facing) {
            case UP -> {
            }
            case DOWN -> ms.mulPose(Axis.XP.rotationDegrees(180));
            case NORTH -> ms.mulPose(Axis.XP.rotationDegrees(270));
            case SOUTH -> ms.mulPose(Axis.XP.rotationDegrees(90));
            case EAST -> ms.mulPose(Axis.ZP.rotationDegrees(270));
            case WEST -> ms.mulPose(Axis.ZP.rotationDegrees(90));
        }
    }

}
