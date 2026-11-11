package com.lightning.northstar.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.lightning.northstar.Northstar;

@EventBusSubscriber(modid = Northstar.MOD_ID)
public class NorthstarCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher = event.getDispatcher();

        WarpSubLevelCommand.register(dispatcher);
    }

}
