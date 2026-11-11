package com.lightning.northstar.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.egg.dimensionalsable.SubLevelWarper;
import dev.ryanhcode.sable.api.command.SubLevelArgumentType;
import dev.ryanhcode.sable.command.argument.SubLevelSelector;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Collection;

/**
 * /northstar warp <sublevel> <overworld|nether|end> <x> <y> <z>
 */
public class WarpSubLevelCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("northstar")
                .requires(src -> src.hasPermission(2));

        LiteralArgumentBuilder<CommandSourceStack> warp = Commands.literal("warp")
                .then(Commands.argument("sublevel", new SubLevelArgumentType(false, false))
                        // Overworld choice
                        .then(Commands.literal("overworld")
                                .then(posArguments(Level.OVERWORLD)))
                        // Nether choice
                        .then(Commands.literal("nether")
                                .then(posArguments(Level.NETHER)))
                        // End choice
                        .then(Commands.literal("end")
                                .then(posArguments(Level.END)))
                );

        root.then(warp);
        dispatcher.register(root);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Double> posArguments(ResourceKey<Level> dimKey) {
        return Commands.argument("posX", DoubleArgumentType.doubleArg(-1.0E6, 1.0E6))
                .then(Commands.argument("posY", DoubleArgumentType.doubleArg(-2.048E7, 2.048E7))
                        .then(Commands.argument("posZ", DoubleArgumentType.doubleArg(-1.0E6, 1.0E6))
                                .executes(ctx -> execute(ctx, dimKey))));
    }

    private static int execute(CommandContext<CommandSourceStack> ctx, ResourceKey<Level> dimKey) {
        CommandSourceStack source = ctx.getSource();

        SubLevelSelector selector;
        try {
            selector = ctx.getArgument("sublevel", SubLevelSelector.class);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to parse sub-level selector: " + e.getMessage()));
            return 0;
        }

        Collection<ServerSubLevel> subLevels;
        try {
            subLevels = selector.getSubLevels(source);
        } catch (Exception e) {
            source.sendFailure(Component.literal("No Sable sub-level found matching the selector."));
            return 0;
        }

        if (subLevels.isEmpty()) {
            source.sendFailure(Component.literal("No Sable sub-level found matching the selector."));
            return 0;
        }

        ServerSubLevel subLevel = subLevels.iterator().next();

        ServerLevel destLevel = source.getServer().getLevel(dimKey);
        if (destLevel == null) {
            source.sendFailure(Component.literal("Target dimension level not found on server!"));
            return 0;
        }

        double x = DoubleArgumentType.getDouble(ctx, "posX");
        double y = DoubleArgumentType.getDouble(ctx, "posY");
        double z = DoubleArgumentType.getDouble(ctx, "posZ");
        Vector3d destPos = new Vector3d(x, y, z);

        // Default identity rotation
        Quaterniond orient = new Quaterniond();

        int warped = SubLevelWarper.warpConnectedSubLevels(subLevel, destLevel, destPos, orient);

        source.sendSuccess(() -> Component.literal("Warped " + warped + " sub-level(s) to coordinates [" + x + ", " + y + ", " + z + "]"), true);
        return warped;
    }
}