package com.lightning.northstar.block.tech.liquid_thruster;

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
public class LiquidThrusterBlockEntity extends ThrusterBlockEntity {

    public LiquidThrusterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected boolean isCompatibleBlock(BlockState state) {
        return state.is(NorthstarBlocks.LIQUID_THRUSTER.get());
    }

    @Override
    protected int getBaseTankCapacity() {
        return NorthstarConfigs.server().liquidThrusterFuelCapacity.get();
    }

    @Override
    protected float getBaseThrust() {
        return NorthstarConfigs.server().liquidThrusterBaseThrust.getF();
    }

    @Override
    protected float getFuelUsage() {
        return NorthstarConfigs.server().liquidThrusterFuelUsage.getF();
    }

    @Override
    protected int getMaxLength() {
        return NorthstarConfigs.server().liquidThrusterMaxLength.get();
    }

    @Override
    protected Component getFuelTankLabel() {
        return Component.translatableWithFallback("northstar.gui.goggles.liquid_thruster.fuel", "Fuel");
    }

    @Override
    protected Component getOxidizerTankLabel() {
        return Component.translatableWithFallback("northstar.gui.goggles.liquid_thruster.oxidizer", "Oxidizer");
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, NorthstarBlockEntityTypes.LIQUID_THRUSTER.get(), (be, face) -> {
            ThrusterBlockEntity controller = be.getControllerBE();
            if (controller == null)
                return null;
            return new MultiFluidHandler(controller.fuelTank.getCapability(), controller.oxidizerTank.getCapability());
        });
    }

}
