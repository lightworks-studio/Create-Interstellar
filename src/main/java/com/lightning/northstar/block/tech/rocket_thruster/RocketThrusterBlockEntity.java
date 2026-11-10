package com.lightning.northstar.block.tech.rocket_thruster;

import com.lightning.northstar.block.tech.thruster.MultiFluidHandler;
import com.lightning.northstar.block.tech.thruster.ThrusterBlockEntity;
import com.lightning.northstar.config.NorthstarConfigs;
import com.lightning.northstar.content.NorthstarBlockEntityTypes;
import com.lightning.northstar.content.NorthstarBlocks;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public class RocketThrusterBlockEntity extends ThrusterBlockEntity {

    public RocketThrusterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected boolean isCompatibleBlock(BlockState state) {
        return state.is(NorthstarBlocks.ROCKET_THRUSTER.get());
    }

    @Override
    protected int getBaseTankCapacity() {
        return NorthstarConfigs.server().rocketThrusterFuelCapacity.get();
    }

    @Override
    protected float getBaseThrust() {
        return NorthstarConfigs.server().rocketThrusterBaseThrust.getF();
    }

    @Override
    protected float getFuelUsage() {
        return NorthstarConfigs.server().rocketThrusterFuelUsage.getF();
    }

    @Override
    protected int getMaxLength() {
        return NorthstarConfigs.server().rocketThrusterMaxLength.get();
    }

    @Override
    protected Component getFuelTankLabel() {
        return Component.translatableWithFallback("northstar.gui.goggles.rocket_thruster.fuel", "Fuel");
    }

    @Override
    protected Component getOxidizerTankLabel() {
        return Component.translatableWithFallback("northstar.gui.goggles.rocket_thruster.oxidizer", "Oxidizer");
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, NorthstarBlockEntityTypes.ROCKET_THRUSTER.get(), (be, face) -> {
            ThrusterBlockEntity controller = be.getControllerBE();
            if (controller == null)
                return null;
            return new MultiFluidHandler(controller.fuelTank.getCapability(), controller.oxidizerTank.getCapability());
        });
    }

}
