package com.lightning.northstar.block.tech.thruster;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;

import java.util.Optional;

public record ThrusterFuelProperties(
        Optional<ResourceLocation> fluid,
        float thrustMultiplier,
        float consumptionMultiplier) {

    public static final Codec<ThrusterFuelProperties> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.optionalFieldOf("fluid").forGetter(ThrusterFuelProperties::fluid),
            Codec.FLOAT.optionalFieldOf("thrust_multiplier", 1f).forGetter(ThrusterFuelProperties::thrustMultiplier),
            Codec.FLOAT.optionalFieldOf("consumption_multiplier", 1f).forGetter(ThrusterFuelProperties::consumptionMultiplier)
    ).apply(i, ThrusterFuelProperties::new));

    public static final ThrusterFuelProperties DEFAULT = new ThrusterFuelProperties(Optional.empty(), 1, 1);

    public Fluid resolveFluid() {
        return fluid.map(BuiltInRegistries.FLUID::get).orElse(null);
    }

}
