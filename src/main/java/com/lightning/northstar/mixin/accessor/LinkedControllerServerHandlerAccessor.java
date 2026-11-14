package com.lightning.northstar.mixin.accessor;

import com.simibubi.create.content.redstone.link.controller.LinkedControllerServerHandler;
import net.createmod.catnip.data.WorldAttached;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

@Mixin(value = LinkedControllerServerHandler.class, remap = false)
public interface LinkedControllerServerHandlerAccessor {

    @Accessor(value = "receivedInputs", remap = false)
    static WorldAttached<Map<UUID, Object>> northstar$getReceivedInputs() {
        throw new AssertionError();
    }

}
