package com.lightning.northstar.mixin.accessor;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.simibubi.create.content.contraptions.actors.trainControls.ControlsServerHandler$ControlsContext", remap = false)
public interface ControlsContextAccessor {

    @Accessor(value = "entity", remap = false)
    AbstractContraptionEntity northstar$getEntity();

    @Accessor(value = "controlsLocalPos", remap = false)
    BlockPos northstar$getControlsLocalPos();

}
