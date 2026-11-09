package com.lightning.northstar.block.tech.rocket_thruster;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;

import java.util.Optional;

public record RocketThrusterFuelProperties(
        Optional<ResourceLocation> fluid,
        float thrustMultiplier,
        float consumptionMultiplier) {

    public static final Codec<RocketThrusterFuelProperties> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.optionalFieldOf("fluid").forGetter(RocketThrusterFuelProperties::fluid),
            Codec.FLOAT.optionalFieldOf("thrust_multiplier", 1f).forGetter(RocketThrusterFuelProperties::thrustMultiplier),
            Codec.FLOAT.optionalFieldOf("consumption_multiplier", 1f).forGetter(RocketThrusterFuelProperties::consumptionMultiplier)
    ).apply(i, RocketThrusterFuelProperties::new));

    public static final RocketThrusterFuelProperties DEFAULT = new RocketThrusterFuelProperties(Optional.empty(), 1, 1);

    public Fluid resolveFluid() {
        return fluid.map(BuiltInRegistries.FLUID::get).orElse(null);
    }

}
