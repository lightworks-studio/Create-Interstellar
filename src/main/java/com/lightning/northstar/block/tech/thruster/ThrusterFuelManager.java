package com.lightning.northstar.block.tech.thruster;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.lightning.northstar.Northstar;
import com.lightning.northstar.contraption.FuelType;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.foundation.fluid.FluidHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class ThrusterFuelManager extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "thruster_fuels";

    private static volatile Map<Fluid, ThrusterFuelProperties> fuels = Map.of();

    public ThrusterFuelManager() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, @NotNull ResourceManager resourceManager, @NotNull ProfilerFiller profiler) {
        Map<Fluid, ThrusterFuelProperties> loaded = new HashMap<>();

        jsons.forEach((location, json) -> ThrusterFuelProperties.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> Northstar.LOGGER.error("Failed to parse thruster fuel '{}': {}", location, error))
                .ifPresent(properties -> {
                    Fluid fluid = properties.resolveFluid();
                    if (fluid == null) {
                        Northstar.LOGGER.warn("Thruster fuel '{}' does not declare a valid fluid", location);
                        return;
                    }
                    loaded.put(FluidHelper.convertToStill(fluid), properties);
                }));

        fuels = Map.copyOf(loaded);
    }

    public static boolean isFuel(Fluid fluid) {
        return getProperties(fluid) != null;
    }

    public static ThrusterFuelProperties getProperties(Fluid fluid) {
        if (fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY)
            return null;

        ThrusterFuelProperties properties = fuels.get(FluidHelper.convertToStill(fluid));
        if (properties != null)
            return properties;

        return FuelType.getFuelType(fluid) == null ? null : ThrusterFuelProperties.DEFAULT;
    }

}
