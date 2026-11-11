package com.lightning.northstar.block.tech.thruster;

import com.lightning.northstar.content.NorthstarPackets;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record SyncThrusterFuelsPacket(
        Map<ResourceLocation, FluidThrusterProperties> fuelMap,
        Set<ResourceLocation> removedFuelIds
) implements ClientboundPacketPayload {

    public static final StreamCodec<FriendlyByteBuf, SyncThrusterFuelsPacket> STREAM_CODEC =
            StreamCodec.of(SyncThrusterFuelsPacket::encode, SyncThrusterFuelsPacket::decode);

    private static void encode(FriendlyByteBuf buf, SyncThrusterFuelsPacket packet) {
        Map<ResourceLocation, FluidThrusterProperties> map = packet.fuelMap();
        Set<ResourceLocation> removed = packet.removedFuelIds();

        buf.writeInt(map.size());
        for (Map.Entry<ResourceLocation, FluidThrusterProperties> entry : map.entrySet()) {
            buf.writeResourceLocation(entry.getKey());
            entry.getValue().encode(buf);
        }

        buf.writeInt(removed.size());
        for (ResourceLocation id : removed) {
            buf.writeResourceLocation(id);
        }
    }

    private static SyncThrusterFuelsPacket decode(FriendlyByteBuf buf) {
        int mapSize = buf.readInt();
        Map<ResourceLocation, FluidThrusterProperties> fuelMap = new HashMap<>(mapSize);
        for (int i = 0; i < mapSize; i++) {
            ResourceLocation id = buf.readResourceLocation();
            FluidThrusterProperties props = FluidThrusterProperties.decode(buf);
            fuelMap.put(id, props);
        }

        int removedSize = buf.readInt();
        Set<ResourceLocation> removedFuelIds = new HashSet<>(removedSize);
        for (int i = 0; i < removedSize; i++) {
            removedFuelIds.add(buf.readResourceLocation());
        }

        return new SyncThrusterFuelsPacket(fuelMap, removedFuelIds);
    }

    public static SyncThrusterFuelsPacket create(Map<Fluid, FluidThrusterProperties> mapToSync, Set<ResourceLocation> removedFuelIds) {
        Map<ResourceLocation, FluidThrusterProperties> fuelMap = new HashMap<>(mapToSync.size());
        for (Map.Entry<Fluid, FluidThrusterProperties> entry : mapToSync.entrySet()) {
            fuelMap.put(BuiltInRegistries.FLUID.getKey(entry.getKey()), entry.getValue());
        }
        return new SyncThrusterFuelsPacket(fuelMap, removedFuelIds);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void handle(LocalPlayer player) {
        ThrusterFuelManager.updateClient(fuelMap, removedFuelIds);
    }

    @Override
    public PacketTypeProvider getTypeProvider() {
        return NorthstarPackets.SYNC_THRUSTER_FUELS;
    }
}
