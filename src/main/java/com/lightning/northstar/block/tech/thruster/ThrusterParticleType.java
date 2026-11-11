package com.lightning.northstar.block.tech.thruster;

import com.lightning.northstar.particle.ThrusterPlumeParticleData;
import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.core.particles.ParticleOptions;

public enum ThrusterParticleType {
    NONE,
    PLUME,
    PLASMA;

    public static final Codec<ThrusterParticleType> CODEC = Codec.STRING.xmap(
            ThrusterParticleType::fromString,
            ThrusterParticleType::serializedName
    );

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ThrusterParticleType fromString(String value) {
        if (value == null) {
            return PLUME;
        }
        try {
            return ThrusterParticleType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PLUME;
        }
    }

    public ParticleOptions createParticleOptions(FluidThrusterProperties properties) {
        return new ThrusterPlumeParticleData();
    }
}

