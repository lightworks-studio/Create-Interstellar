package com.lightning.northstar.config;

import net.createmod.catnip.config.ConfigBase;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ServerConfig extends ConfigBase {

    public final ConfigInt spaceAtlasMaxWaypoints = i(256, 0, "spaceAtlasMaxWaypoints", "The maximum waypoints the space atlas can hold");

    public final ConfigGroup atmosphere = group(1, "atmosphere");

    // For default worlds (max Y = 320), fade at 800 until 1000 and change dimension at 2000
    public final ConfigInt atmosphereBaseHeight = i(480, "atmosphereBaseHeight", "The height after which the atmosphere starts to fade out. Relative to the maximum build height.");
    public final ConfigInt atmosphereThickness = i(200, 1, "atmosphereThickness", "The thickness of the atmosphere");
    public final ConfigInt atmosphereTeleportHeight = i(2000, 0, "atmosphereTeleportHeight", "The height at which the rocket changes dimension, relative to the atmosphere base height + thickness");
    public final ConfigBool allowDimensionTraversal = b(true, "allowDimensionTraversal");

    public final ConfigGroup rocket = group(1, "rocket contraption");

    public final ConfigBool contraption_enableReturnTicketCreation = b(true, "enableReturnTicketCreation", "Wether return tickets should be created after the rocket lands.");
    public final ConfigFloat contraption_targetingComputerEfficiency = f(0.4f, 0f, 1f, "targetingComputerEfficiency", "The total fuel efficiency applied for the maximum amount of targeting computers");
    public final ConfigInt contraption_targetingComputersNeeded = i(48, 0, "targetingComputersNeeded", "The amount of targeting computers required to reach maximum efficiency");
    public final ConfigInt contraption_launchCountdownRadius = i(20, 0, "launchCountdownRadius", "From how far away should the launch countdown messages/sound be announced to players");
    public final ConfigFloat contraption_thrusterPower = f(500, 1, "thrusterPower", "The engine's force in Newtons (kg/m/s^2) assuming rocket weight is in kg");
    public final ConfigFloat contraption_maxVelocity = f(100, 0, "maxVelocity", "The maximum speed in blocks/second a rocket sub-level's thrusters will accelerate it to. Thrust tapers off as this speed is approached. Set to 0 to disable the limit.");
    public final ConfigFloat contraption_landingMaxSafeSpeed = f(5, 0, "landingMaxSafeSpeed", "The maximum safe speed in blocks/second the rocket can move at before exploding on landing");
    public final ConfigFloat contraption_rocketExplosionFraction = f(0.005f, 0, 1, "explosionFraction", "The fraction of blocks to explode when a rocket crash lands. Set to zero to disable.");
    public final ConfigInt contraption_rocketExplosionSize = i(8, 1, "explosionSize", "The size of individual explosions when a rocket crash lands.");

    public final ConfigGroup rocketFuel = group(2, "fuel", "Fuel properties for rockets");

    public final ConfigFloat takeoffFuelScale = f(30, 0, "takeoffFuelScale");
    public final ConfigFloat landingFuelScale = f(10, 0, "landingFuelScale");
    public final ConfigFloat travelFuelScale = f(10, 0, "travelFuelScale");

    public final ConfigGroup thruster = group(1, "thruster", "Rocket thruster properties");

    public final ConfigFloat thrusterBaseThrust = f(200.0f, 1, 10_000_000, "baseThrust",
            "Base thrust at redstone 15 and full obstruction efficiency for the standard thruster.",
            "Default tuned for 1000-unit thrust scale parity with Sable physics.",
            "Effective thrust uses: baseThrust * fuel_thrust_percent / 100.");

    public final ConfigInt thrusterObstructionScanLength = i(10, 1, 64, "obstructionScanLength",
            "How many blocks behind the nozzle are checked for obstruction.");
    public final ConfigBool thrusterObstructionIgnoreOtherSubLevels = b(true, "obstructionIgnoreOtherSubLevels",
            "Ignore non-sublevel blocks when checking for obstruction.");
    public final ConfigInt thrusterFuelTankCapacityMb = i(1000, 250, 10_000_000, "fuelTankCapacityMb",
            "Internal fuel tank capacity in millibuckets.");
    public final ConfigFloat thrusterFuelMbPerTickAtFullThrottle = f(1, 0.0001f, 1000, "fuelMbPerTickAtFullThrottle",
            "Fuel consumption in millibuckets per tick at full redstone throttle.");
    public final ConfigBool thrusterDamageEntities = b(true, "damageEntities",
            "If true, entities inside active thruster plume are damaged.");
    public final ConfigInt thrusterClientParticlesPerTick = i(4, 0, 64, "clientParticlesPerTick",
            "Max client particles per tick while active.");

    public final ConfigGroup thrusterMultiblock = group(2, "multiblockThruster", "Multiblock thruster properties");

    public final ConfigFloat thrusterMultiblock2xThrustMultiplier = f(1.125f, 0.01f, 10, "multiblock2xThrustMultiplier",
            "Thrust multiplier for a 2x2x2 multiblock thruster (e.g. 1.10 = 10% bonus).");
    public final ConfigFloat thrusterMultiblock3xThrustMultiplier = f(1.25f, 0.01f, 10, "multiblock3xThrustMultiplier",
            "Thrust multiplier for a 3x3x3 multiblock thruster (e.g. 1.25 = 25% bonus).");
    public final ConfigFloat thrusterMultiblock2xFuelEfficiency = f(0.6f, 0.01f, 10, "multiblock2xFuelEfficiency",
            "Fuel cost multiplier for a 2x2x2 multiblock thruster (e.g. 1.0 = no reduction, 0.8 = 20% cheaper).");
    public final ConfigFloat thrusterMultiblock3xFuelEfficiency = f(0.4f, 0.01f, 10, "multiblock3xFuelEfficiency",
            "Fuel cost multiplier for a 3x3x3 multiblock thruster (e.g. 0.95 = 5% cheaper).");
    public final ConfigFloat thrusterMultiblock2xOxidizerEfficiency = f(0.85f, 0.01f, 10, "multiblock2xOxidizerEfficiency",
            "Oxidizer cost multiplier for a 2x2x2 multiblock thruster. 0.85 = 15% savings.");
    public final ConfigFloat thrusterMultiblock3xOxidizerEfficiency = f(0.75f, 0.01f, 10, "multiblock3xOxidizerEfficiency",
            "Oxidizer cost multiplier for a 3x3x3 multiblock thruster. 0.75 = 25% savings.");

    public final ConfigGroup thrusterPhysics = group(2, "physics", "Thruster physics properties");

    public final ConfigFloat thrusterNozzleOffsetFromCenter = f(0.45f, 0, 1.5f, "nozzleOffsetFromCenter",
            "Offset from the block center where force is applied.");
    public final ConfigBool thrusterUseAtmosphericPressure = b(false, "useAtmosphericPressure",
            "If true, atmospheric pressure affects thruster output at altitude.");
    public final ConfigFloat thrusterAtmosphericPressureAmount = f(1, 0, 2, "atmosphericPressureAmount",
            "Strength of atmospheric pressure influence. 1.0 = full effect, 0.0 = no effect.");
    public final ConfigFloat thrustUnitsPerKn = f(1000, 1, 1_000_000, "thrustUnitsPerKn",
            "Shared thrust unit scale: how many internal thrust units correspond to 1 kN.",
            "Used by physics conversion, tooltip display, and ComputerCraft kN helpers.");

    public final ConfigGroup reactionWheel = group(1, "reactionWheel", "Reaction wheel (gyroscope) properties");

    public final ConfigFloat reactionWheelReferenceRpm = f(256f, 1f, 4096f, "referenceRpm", "RPM at which the gyroscope reaches 100% effectiveness. Above this, output is capped.");
    public final ConfigFloat reactionWheelOmegaTarget = f(3f, 0.1f, 10f, "omegaTarget", "Target natural frequency of the closed loop in rad/s. Higher = snappier correction. Goes up to ~10 before discretization starts to bite.");
    public final ConfigFloat reactionWheelDampingRatio = f(0.9f, 0.1f, 2f, "dampingRatio", "Target damping ratio of the closed loop. 0.9 settles fast with negligible overshoot, 1.0 is critically damped (no overshoot, slower).");
    public final ConfigFloat reactionWheelAuthorityPerUnit = f(5000f, 100f, 1_000_000f, "authorityPerUnit", "How much ship inertia (kg*m^2) one gyroscope fully stabilizes at reference RPM. Bigger ships need more gyros.");
    public final ConfigFloat reactionWheelFeedForwardGain = f(1f, 0f, 1f, "feedForwardGain", "Fraction of the observed external disturbance the controller cancels via feed-forward. 1.0 = full cancellation, 0.0 = disable (PD loop only).");
    public final ConfigFloat reactionWheelFeedForwardSmoothing = f(0.2f, 0.01f, 1f, "feedForwardSmoothing", "Exponential moving average factor for the disturbance estimate. Lower = smoother but slower to track changes.");
    public final ConfigFloat reactionWheelStressImpact = f(16f, 0f, 1024f, "stressImpact", "Base stress impact in SU per RPM. Total SU draw is roughly impact * |RPM|. Set to 0 to disable.");

    public final ConfigGroup thrusterFuelProperties = group(2, "fuelProperties",
            "Fuel properties by fluid id. Configure efficiency and burn rate separately as percentages.");

    public final Map<String, ConfigInt> fuelEfficiencyEntries = new LinkedHashMap<>();
    public final Map<String, ConfigInt> fuelBurnRateEntries = new LinkedHashMap<>();

    public final CValue<List<? extends String>, ModConfigSpec.ConfigValue<List<? extends String>>> additionalThrusterFuelPropertyLines =
            new CValue<>("additionalThrusterFuelLines",
                    builder -> builder.comment(
                                    "Additional thruster fuel lines (same format as defaults: fluid_id=efficiencyPercent,burnRatePercent).",
                                    "Use for fluids that do not have a fuelProperties subsection. Entries here override matching fluids from the table above.")
                            .defineListAllowEmpty("additionalThrusterFuelLines", ArrayList::new, obj -> obj instanceof String));

    {
        for (String entry : defaultFuelProperties()) {
            String[] split = entry.split("=", 2);
            if (split.length != 2)
                continue;
            String fluidId = split[0];
            String[] values = split[1].split(",", 2);
            if (values.length != 2)
                continue;
            int efficiency;
            int burnRate;
            try {
                efficiency = Integer.parseInt(values[0].trim());
                burnRate = Integer.parseInt(values[1].trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            group(3, configKeyForFluidId(fluidId));
            fuelEfficiencyEntries.put(fluidId, i(efficiency, 0, 10000, "efficiency",
                    "Fuel efficiency percentage for " + fluidId + "."));
            fuelBurnRateEntries.put(fluidId, i(burnRate, 0, 10000, "burnRate",
                    "Fuel burn rate percentage for " + fluidId + "."));
        }
    }

    public final ConfigGroup gameplay = group(1, "gameplay");

    public final ConfigFloat telescopePlanetSpeed = f(10, 0, "telescopePlanetSpeed", "How fast should planets move in the telescope screen");

    public final ConfigInt spacesuitBaseOxygen = i(1800, 0, "spacesuitBaseOxygen", "The amount of oxygen that can be stored in spacesuits, uses 1 mB/second");
    public final ConfigInt spacesuitAdditionalOxygen = i(600, 0, "spacesuitAdditionalOxygen", "The added oxygen capacity for each level of capacity");

    public final ConfigBool doDripstoneDripWithoutGravity = b(false, "doDripstoneDripWithoutGravity", "Wether to allow dripstones to drip in places with no gravity");
    public final ConfigBool doDripstoneGrowWithoutGravity = b(false, "doDripstoneGrowWithoutGravity", "Wether to allow dripstones to grow in places with no gravity");
    public final ConfigFloat zeroGravityJumpStrength = f(0.2f, 0.0f, "zeroGravityJumpStrength", "The jump strength scale applied in zero gravity worlds");
    public final ConfigBool removeStalledProjectiles = b(true, "removeStalledProjectiles", "If enabled, triggers a collision with stopped projectiles in zero gravity dimensions. Causes arrows to despawn, potions / experience bottles to explode, loyalty tridents to return, etc...");

    public final ConfigGroup sealer = group(1, "sealer");

    public final ConfigInt sealerBaseCheckBlocksPerTick = i(128, 1, "baseCheckBlocksPerTick", "Base maximum blocks tested per tick, higher values increase the speed at which seals updates but decreases performance, value increases dynamically with additional blocks that might add volume.");
    public final ConfigInt sealerMaxCheckedBlocksPerTick = i(4096, 1, "maxCheckedBlocksPerTick", "Absolute maximum blocks tested per tick");
    public final ConfigInt sealerCheckDelay = i(20, 1, "checkDelay", "Delay in ticks before checking again after a seal completed");

    public final ConfigGroup oxygen = group(2, "oxygen");

    public final ConfigInt oxygenSealerBlocksPerRpm = i(16, 1, "blocksPerRpm");
    public final ConfigInt oxygenSealerMaxContraptionSealed = i(4096, 0, "maxContraptionSealed");
    public final ConfigFloat oxygenSealerPassiveDrain = f(0.0001f, 0, "passiveDrain", "Passive oxygen drain in mB per block per tick");
    public final ConfigFloat oxygenSealerBlockActiveDrain = f(0.01f, 0, "blockActiveDrain", "Active drain scaling in mB per block per tick. Applies to block with dynamic consumption like fire, torches, leaves, etc...");
    public final ConfigFloat oxygenSealerEntityActiveDrain = f(0.05f, 0, "entityActiveDrain", "Active drain for entities in mB per entity per tick.");
    // 1800 mB / 6 mB/t = 15 seconds (base) to 30 seconds (capacity 3)
    public final ConfigInt oxygenFillerMaxTransferPerTick = i(6, 0, "oxygenFillerMaxTransferPerTick", "The maximum oxygen that can be inserted/extracted using an oxygen filler, in mB/t");
    public final ConfigInt mountedOxygenFillerSpeed = i(6, 0, "mountedOxygenFillerSpeed", "The speed of oxygen fillers mounted on contraptions, in mB/t. Set to 0 to disable.");
    public final ConfigBool relitExtinguishedBlocks = b(true, "relitExtinguishedBlocks", "Whether blocks extinguished from the lack of oxygen (torches, campfires, etc...) should be relit automatically when oxygen is available again. This doesn't affect blocks extinguished manually or fire.");
    public final ConfigBool relitExtinguishedFire = b(false, "relitExtinguishedFire", "Whether fire extinguished from the lack of oxygen should be relit automatically.");

    public final ConfigGroup temperature = group(2, "temperature");

    public final ConfigInt temperatureRegulatorBlocksPerRpm = i(16, 1, "blocksPerRpm");
    public final ConfigInt temperatureRegulatorMaxContraptionSealed = i(4096, 0, "maxContraptionSealed");

    public final ConfigGroup largeFan = group(1, "largeFan");

    public final ConfigInt largeFanMaxSize = i(5, 1, "maxSize", "Maximum size of large fans.");
    public final ConfigFloat largeFanSizeExponent = f(2.2f, 0, "sizeExp", Comments.LARGE_FAN_FORMULA);
    public final ConfigFloat largeFanMultiplier = f(12, 0, "multiplier", Comments.LARGE_FAN_FORMULA);

    @Override
    public @NotNull String getName() {
        return "server";
    }

    public int getCombinedTeleportHeight() {
        return atmosphereBaseHeight.get() + atmosphereThickness.get() + atmosphereTeleportHeight.get();
    }

    public static List<String> defaultFuelProperties() {
        return new ArrayList<>(List.of(
                "minecraft:lava=75,100",
                "createdieselgenerators:plant_oil=55,170",
                "immersiveengineering:plantoil=55,170",
                "createdieselgenerators:ethanol=70,140",
                "immersiveengineering:ethanol=70,140",
                "mekanismgenerators:bioethanol=75,135",
                "northstar:biofuel=80,125",
                "createdieselgenerators:biodiesel=90,110",
                "immersiveengineering:biodiesel=90,110",
                "immersiveengineering:high_power_biodiesel=105,95",
                "createdieselgenerators:diesel=100,100",
                "tfmg:diesel=100,100",
                "stellaris:diesel=100,100",
                "tfmg:naphtha=95,105",
                "tfmg:kerosene=230,90",
                "createdieselgenerators:gasoline=125,80",
                "tfmg:gasoline=125,80",
                "tfmg:lpg=120,85",
                "northstar:hydrocarbon=130,75",
                "stellaris:fuel=115,100",
                "mekanism:hydrogen=230,90",
                "createaddition:bioethanol=75,135",
                "createaddition:seed_oil=55,170",
                "northstar:methane=105,95",
                "northstar:liquid_hydrogen=230,90",
                "immersivepetroleum:diesel_sulfur=100,100"
        ));
    }

    private static String configKeyForFluidId(String fluidId) {
        return fluidId
                .replace(':', '_')
                .replace('/', '_')
                .replace('.', '_')
                .replace('-', '_');
    }

    public List<? extends String> getFuelPropertiesOrDefault() {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();

        if (!fuelEfficiencyEntries.isEmpty() && !fuelBurnRateEntries.isEmpty()) {
            for (Map.Entry<String, ConfigInt> e : fuelEfficiencyEntries.entrySet()) {
                try {
                    ConfigInt burnRate = fuelBurnRateEntries.get(e.getKey());
                    if (burnRate == null) {
                        continue;
                    }
                    merged.put(e.getKey(), e.getValue().get() + "," + burnRate.get());
                } catch (IllegalStateException ignored) {
                    // Config not ready — skip entry
                }
            }
        }

        try {
            List<? extends String> extra = additionalThrusterFuelPropertyLines.get();
            if (extra != null) {
                for (Object o : extra) {
                    if (!(o instanceof String raw)) {
                        continue;
                    }
                    String line = raw.trim();
                    int sep = line.indexOf('=');
                    if (sep <= 0 || sep >= line.length() - 1) {
                        continue;
                    }
                    String fluidId = line.substring(0, sep).trim();
                    String rhs = line.substring(sep + 1).trim();
                    if (ResourceLocation.tryParse(fluidId) == null || !rhs.contains(",")) {
                        continue;
                    }
                    merged.put(fluidId, rhs);
                }
            }
        } catch (IllegalStateException ignored) {
            // ignore until config load completes
        }

        if (merged.isEmpty()) {
            return defaultFuelProperties();
        }

        List<String> out = new ArrayList<>(merged.size());
        for (Map.Entry<String, String> e : merged.entrySet()) {
            out.add(e.getKey() + "=" + e.getValue());
        }
        return out;
    }

    public float getThrustUnitsPerKnOrDefault() {
        try {
            return thrustUnitsPerKn.getF();
        } catch (IllegalStateException ignored) {
            return 1000.0f;
        }
    }

    public float calculateAtmosphereBlend(Level level, double y) {
        return calculateAtmosphereBlend(level.getMaxBuildHeight(), y);
    }

    public float calculateAtmosphereBlend(int worldHeight, double y) {
        int atmosphereHeight = worldHeight + atmosphereBaseHeight.get();
        int thickness = atmosphereThickness.get();
        return (float) Mth.clamp(Mth.inverseLerp(y, atmosphereHeight, atmosphereHeight + thickness), 0, 1);
    }

}
