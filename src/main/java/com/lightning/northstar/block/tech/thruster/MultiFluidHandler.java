package com.lightning.northstar.block.tech.thruster;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;

public class MultiFluidHandler implements IFluidHandler {

    private final IFluidHandler first;
    private final IFluidHandler second;

    public MultiFluidHandler(IFluidHandler first, IFluidHandler second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public int getTanks() {
        return first.getTanks() + second.getTanks();
    }

    @Override
    public @NotNull FluidStack getFluidInTank(int tank) {
        int firstTanks = first.getTanks();
        return tank < firstTanks
                ? first.getFluidInTank(tank)
                : second.getFluidInTank(tank - firstTanks);
    }

    @Override
    public int getTankCapacity(int tank) {
        int firstTanks = first.getTanks();
        return tank < firstTanks
                ? first.getTankCapacity(tank)
                : second.getTankCapacity(tank - firstTanks);
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        int firstTanks = first.getTanks();
        return tank < firstTanks
                ? first.isFluidValid(tank, stack)
                : second.isFluidValid(tank - firstTanks, stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        int filled = first.fill(resource, action);
        return filled > 0 ? filled : second.fill(resource, action);
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        FluidStack drained = first.drain(resource, action);
        return drained.isEmpty() ? second.drain(resource, action) : drained;
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        FluidStack drained = first.drain(maxDrain, action);
        return drained.isEmpty() ? second.drain(maxDrain, action) : drained;
    }

}
