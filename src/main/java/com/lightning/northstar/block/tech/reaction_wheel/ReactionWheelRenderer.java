package com.lightning.northstar.block.tech.reaction_wheel;

import com.lightning.northstar.content.NorthstarPartialModels;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import org.joml.Quaternionf;

public class ReactionWheelRenderer extends SafeBlockEntityRenderer<ReactionWheelBlockEntity> {
    public ReactionWheelRenderer(BlockEntityRendererProvider.Context context) {

    }

    protected void renderSafe(ReactionWheelBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
        if (!VisualizationManager.supportsVisualization(be.getLevel())) {
            VertexConsumer vb = buffer.getBuffer(RenderType.cutout());
            float flywheelSpeed = be.effectiveSpeedAt(partialTicks) * 3.0F / 10.0F;
            float flywheelAngle = be.getAngle() + flywheelSpeed * partialTicks;
            float shaftAngle = (float)Math.toDegrees((double)KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), Axis.Y));
            ClientSubLevel subLevel = Sable.HELPER.getContainingClient(be);
            Quaternionf comp = subLevel != null ? (new Quaternionf(subLevel.renderPose(partialTicks).orientation())).invert() : null;
            boolean inverted = be.getBlockState().getValue(ReactionWheelBlock.FACING) == Direction.UP;
            float flywheelLift = inverted ? 0.006250024F : 0.61875F;
            float shaftXDeg = inverted ? -90.0F : 90.0F;
            float shaftAngleSigned = inverted ? shaftAngle : -shaftAngle;
            ms.pushPose();
            if (comp != null) {
                ms.translate(0.5F, 0.5F, 0.5F);
                ms.mulPose(comp);
                ms.translate(-0.5F, -0.5F, -0.5F);
            }

            ((SuperByteBuffer)((SuperByteBuffer)((SuperByteBuffer)CachedBuffers.partial(NorthstarPartialModels.GYROSCOPE_FLYWHEEL, be.getBlockState()).translate(0.5F, 0.5F + flywheelLift, 0.5F)).rotateYDegrees(flywheelAngle)).translate(-0.5F, -0.5F, -0.5F)).light(light).renderInto(ms, vb);
            ms.popPose();
            ((SuperByteBuffer)((SuperByteBuffer)((SuperByteBuffer)((SuperByteBuffer)CachedBuffers.partial(AllPartialModels.SHAFT_HALF, be.getBlockState()).translate(0.5F, 0.5F, 0.5F)).rotateXDegrees(shaftXDeg)).rotateZDegrees(shaftAngleSigned)).translate(-0.5F, -0.5F, -0.5F)).light(light).renderInto(ms, vb);
        }
    }
}
