package com.lightning.northstar.network.packet;

import com.lightning.northstar.content.NorthstarPackets;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.redstone.link.controller.LecternControllerBlockEntity;
import com.simibubi.create.content.redstone.link.controller.LinkedControllerClientHandler;
import io.netty.buffer.ByteBuf;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.createmod.catnip.codecs.stream.CatnipStreamCodecs;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

public record ForceLinkedControllerPacket(
        @Nullable BlockPos lecternPos
) implements ClientboundPacketPayload {

    public static final StreamCodec<ByteBuf, ForceLinkedControllerPacket> STREAM_CODEC = StreamCodec.composite(
            CatnipStreamCodecs.NULLABLE_BLOCK_POS, ForceLinkedControllerPacket::lecternPos,
            ForceLinkedControllerPacket::new
    );

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(LocalPlayer player) {
        if (lecternPos != null) {
            if (player.level().getBlockEntity(lecternPos) instanceof LecternControllerBlockEntity lectern
                    && lectern.isUsedBy(player)) {
                // Do not call deactivateInLectern(): it would send a stop packet for the old plot
                // position, which may now be the rebuilt lectern in the destination.
                LinkedControllerClientHandler.MODE = LinkedControllerClientHandler.Mode.IDLE;
                LinkedControllerClientHandler.activateInLectern(lecternPos);
            }
            return;
        }

        if (!AllItems.LINKED_CONTROLLER.isIn(player.getMainHandItem())
                && !AllItems.LINKED_CONTROLLER.isIn(player.getOffhandItem())) {
            return;
        }
        if (LinkedControllerClientHandler.MODE == LinkedControllerClientHandler.Mode.IDLE) {
            LinkedControllerClientHandler.toggle();
        }
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return NorthstarPackets.FORCE_LINKED_CONTROLLER;
    }

}
