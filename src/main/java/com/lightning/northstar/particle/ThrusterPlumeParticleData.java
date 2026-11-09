package com.lightning.northstar.particle;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.particle.ICustomParticleDataWithSprite;
import net.minecraft.client.particle.ParticleEngine.SpriteParticleRegistration;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

public class ThrusterPlumeParticleData implements ParticleOptions, ICustomParticleDataWithSprite<ThrusterPlumeParticleData> {

    public static final ThrusterPlumeParticleData INSTANCE = new ThrusterPlumeParticleData();

    private static final MapCodec<ThrusterPlumeParticleData> CODEC = MapCodec.unit(INSTANCE);
    private static final StreamCodec<RegistryFriendlyByteBuf, ThrusterPlumeParticleData> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    private ThrusterPlumeParticleData() {
    }

    @Override
    public ParticleType<?> getType() {
        return NorthstarParticles.THRUSTER_PLUME.get();
    }

    @Override
    public MapCodec<ThrusterPlumeParticleData> getCodec(ParticleType<ThrusterPlumeParticleData> type) {
        return CODEC;
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, ThrusterPlumeParticleData> getStreamCodec() {
        return STREAM_CODEC;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public SpriteParticleRegistration<ThrusterPlumeParticleData> getMetaFactory() {
        return ThrusterPlumeParticle.Factory::new;
    }

}
