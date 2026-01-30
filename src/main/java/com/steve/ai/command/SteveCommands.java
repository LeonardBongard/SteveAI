package com.steve.ai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import com.mojang.brigadier.arguments.IntegerArgumentType;

public class SteveCommands {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("steve")
            .then(Commands.literal("spawn")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::spawnSteve)))
            .then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::removeSteve)))
            .then(Commands.literal("list")
                .executes(SteveCommands::listSteves))
            .then(Commands.literal("stop")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::stopSteve)))
            .then(Commands.literal("inventory")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::inventorySteve)))
            .then(Commands.literal("give")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("item", StringArgumentType.string())
                        .executes(ctx -> giveFromSteve(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> giveFromSteve(ctx, IntegerArgumentType.getInteger(ctx, "count")))))))
            .then(Commands.literal("drop")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("item", StringArgumentType.string())
                        .executes(ctx -> dropFromSteve(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> dropFromSteve(ctx, IntegerArgumentType.getInteger(ctx, "count")))))))
            .then(Commands.literal("tell")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(SteveCommands::tellSteve))))
        );
    }

    private static int spawnSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        ServerLevel serverLevel = source.getLevel();
        if (serverLevel == null) {
            source.sendFailure(Component.literal("Command must be run on server"));
            return 0;
        }

        SteveManager manager = SteveMod.getSteveManager();
        
        Vec3 sourcePos = source.getPosition();
        if (source.getEntity() != null) {
            Vec3 lookVec = source.getEntity().getLookAngle();
            sourcePos = sourcePos.add(lookVec.x * 3, 0, lookVec.z * 3);
        } else {
            sourcePos = sourcePos.add(3, 0, 0);
        }
        Vec3 spawnPos = sourcePos;
        
        SteveEntity steve = manager.spawnSteve(serverLevel, spawnPos, name);
        if (steve != null) {
            source.sendSuccess(() -> Component.literal("Spawned Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to spawn Steve. Name may already exist or max limit reached."));
            return 0;
        }
    }

    private static int removeSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        SteveManager manager = SteveMod.getSteveManager();
        if (manager.removeSteve(name)) {
            source.sendSuccess(() -> Component.literal("Removed Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    private static int listSteves(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveManager manager = SteveMod.getSteveManager();
        
        var names = manager.getSteveNames();
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active Steves"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Active Steves (" + names.size() + "): " + String.join(", ", names)), false);
        }
        return 1;
    }

    private static int stopSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        SteveManager manager = SteveMod.getSteveManager();
        SteveEntity steve = manager.getSteve(name);
        
        if (steve != null) {
            steve.getActionExecutor().stopCurrentAction();
            steve.getMemory().clearTaskQueue();
            source.sendSuccess(() -> Component.literal("Stopped Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    private static int tellSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String command = StringArgumentType.getString(context, "command");
        CommandSourceStack source = context.getSource();
        
        SteveManager manager = SteveMod.getSteveManager();
        SteveEntity steve = manager.getSteve(name);
        
        if (steve != null) {
            // Disabled command feedback message
            // source.sendSuccess(() -> Component.literal("Instructing " + name + ": " + command), true);
            
            new Thread(() -> {
                steve.getActionExecutor().processNaturalLanguageCommand(command);
            }).start();
            
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    private static int inventorySteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();

        SteveManager manager = SteveMod.getSteveManager();
        SteveEntity steve = manager.getSteve(name);

        if (steve != null) {
            source.sendSuccess(() -> Component.literal(
                "Inventory for " + name + ": " + steve.getInventorySummary()
            ), false);
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    private static int giveFromSteve(CommandContext<CommandSourceStack> context, int count) {
        String name = StringArgumentType.getString(context, "name");
        String itemName = StringArgumentType.getString(context, "item");
        CommandSourceStack source = context.getSource();

        SteveEntity steve = SteveMod.getSteveManager().getSteve(name);
        if (steve == null) {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }

        Item item = parseItem(itemName);
        if (item == null) {
            source.sendFailure(Component.literal("Unknown item: " + itemName));
            return 0;
        }

        ItemStack extracted = steve.extractItem(item, count);
        if (extracted.isEmpty()) {
            source.sendFailure(Component.literal("Steve does not have enough: " + itemName));
            return 0;
        }

        if (source.getEntity() != null) {
            if (source.getEntity() instanceof net.minecraft.world.entity.player.Player player) {
                boolean added = player.getInventory().add(extracted);
                if (!added) {
                    player.drop(extracted, false);
                }
            }
            source.sendSuccess(() -> Component.literal("Gave " + extracted.getCount() + " " + itemName + " from " + name), false);
        } else {
            dropItemStack(steve, extracted);
            source.sendSuccess(() -> Component.literal("Dropped " + extracted.getCount() + " " + itemName + " from " + name), false);
        }
        return 1;
    }

    private static int dropFromSteve(CommandContext<CommandSourceStack> context, int count) {
        String name = StringArgumentType.getString(context, "name");
        String itemName = StringArgumentType.getString(context, "item");
        CommandSourceStack source = context.getSource();

        SteveEntity steve = SteveMod.getSteveManager().getSteve(name);
        if (steve == null) {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }

        Item item = parseItem(itemName);
        if (item == null) {
            source.sendFailure(Component.literal("Unknown item: " + itemName));
            return 0;
        }

        ItemStack extracted = steve.extractItem(item, count);
        if (extracted.isEmpty()) {
            source.sendFailure(Component.literal("Steve does not have enough: " + itemName));
            return 0;
        }

        dropItemStack(steve, extracted);
        source.sendSuccess(() -> Component.literal("Dropped " + extracted.getCount() + " " + itemName + " from " + name), false);
        return 1;
    }

    private static Item parseItem(String itemName) {
        String normalized = itemName.toLowerCase().replace(" ", "_");
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        Identifier id = Identifier.tryParse(normalized);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    private static void dropItemStack(SteveEntity steve, ItemStack stack) {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        ItemEntity entity = new ItemEntity(
            serverLevel,
            steve.getX(),
            steve.getY() + 0.5,
            steve.getZ(),
            stack
        );
        serverLevel.addFreshEntity(entity);
    }
}
