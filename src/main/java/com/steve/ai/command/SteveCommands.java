package com.steve.ai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.execution.SafetySnapshot;
import com.steve.ai.testing.StevePlaytestRunner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
            .then(Commands.literal("debug")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.literal("status")
                        .executes(SteveCommands::debugStatus))
                    .then(Commands.literal("safety")
                        .executes(SteveCommands::debugSafety))
                    .then(Commands.literal("hearts")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 10))
                            .executes(SteveCommands::debugSetHearts)))
                    .then(Commands.literal("health")
                        .then(Commands.argument("value", FloatArgumentType.floatArg(0.0f, 20.0f))
                            .executes(SteveCommands::debugSetHealth)))
                    .then(Commands.literal("food")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, 20))
                            .executes(SteveCommands::debugSetFood)))
                    .then(Commands.literal("saturation")
                        .then(Commands.argument("value", FloatArgumentType.floatArg(0.0f, 20.0f))
                            .executes(SteveCommands::debugSetSaturation)))))
            .then(Commands.literal("test")
                .then(Commands.literal("iron_pickaxe")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> runIronPickaxeTest(ctx, StevePlaytestRunner.defaultTimeoutSeconds()))
                        .then(Commands.argument("timeoutSeconds", IntegerArgumentType.integer(30, 1800))
                            .executes(ctx -> runIronPickaxeTest(
                                ctx,
                                IntegerArgumentType.getInteger(ctx, "timeoutSeconds")
                            )))))
                .then(Commands.literal("iron_pickaxe_spawn")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .executes(ctx -> spawnAndRunIronPickaxeTest(ctx, StevePlaytestRunner.defaultTimeoutSeconds()))
                        .then(Commands.argument("timeoutSeconds", IntegerArgumentType.integer(30, 1800))
                            .executes(ctx -> spawnAndRunIronPickaxeTest(
                                ctx,
                                IntegerArgumentType.getInteger(ctx, "timeoutSeconds")
                            ))))))
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
        
        Vec3 spawnPos = resolveSpawnPos(source);
        
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

    private static int debugStatus(CommandContext<CommandSourceStack> context) {
        SteveEntity steve = getSteveByName(context);
        if (steve == null) {
            return 0;
        }
        SafetySnapshot safety = steve.getActionExecutor().getCurrentSafetySnapshot();
        context.getSource().sendSuccess(() -> Component.literal(
            "Debug status for " + steve.getSteveName()
                + " | health=" + formatFloat(steve.getHealth())
                + "/" + formatFloat(steve.getMaxHealth())
                + " | hearts=" + formatFloat(steve.getHealth() / 2.0f)
                + " | food=" + steve.getFoodLevel()
                + " | saturation=" + formatFloat(steve.getSaturationLevel())
                + " | safety=" + safety.state()
                + ":" + safety.safetyScore()
                + ":" + safety.recommendedDecision()
                + " | panic=" + safety.panicLevel()
                + ":" + safety.panicScore()
        ), false);
        return 1;
    }

    private static int debugSafety(CommandContext<CommandSourceStack> context) {
        SteveEntity steve = getSteveByName(context);
        if (steve == null) {
            return 0;
        }
        SafetySnapshot safety = steve.getActionExecutor().getCurrentSafetySnapshot();
        String reasons = safety.reasons().isEmpty() ? "none" : String.join(",", safety.reasons());
        String retreat = safety.retreatTarget() == null
            ? "none"
            : safety.retreatTarget().getX() + "," + safety.retreatTarget().getY() + "," + safety.retreatTarget().getZ();
        context.getSource().sendSuccess(() -> Component.literal(
            "Safety for " + steve.getSteveName()
                + " | state=" + safety.state()
                + " | score=" + safety.safetyScore()
                + " | decision=" + safety.recommendedDecision()
                + " | panic=" + safety.panicLevel() + ":" + safety.panicScore()
                + " | reasons=" + reasons
                + " | retreat=" + retreat
        ), false);
        return 1;
    }

    private static int debugSetHearts(CommandContext<CommandSourceStack> context) {
        SteveEntity steve = getSteveByName(context);
        if (steve == null) {
            return 0;
        }
        int hearts = IntegerArgumentType.getInteger(context, "value");
        float health = Math.max(0.0f, Math.min(20.0f, hearts * 2.0f));
        steve.setHealth(health);
        context.getSource().sendSuccess(() -> Component.literal(
            "Set " + steve.getSteveName() + " hearts to " + hearts + " (health=" + formatFloat(health) + ")"
        ), true);
        return 1;
    }

    private static int debugSetHealth(CommandContext<CommandSourceStack> context) {
        SteveEntity steve = getSteveByName(context);
        if (steve == null) {
            return 0;
        }
        float health = FloatArgumentType.getFloat(context, "value");
        steve.setHealth(Math.max(0.0f, Math.min(health, steve.getMaxHealth())));
        context.getSource().sendSuccess(() -> Component.literal(
            "Set " + steve.getSteveName() + " health to " + formatFloat(steve.getHealth())
        ), true);
        return 1;
    }

    private static int debugSetFood(CommandContext<CommandSourceStack> context) {
        SteveEntity steve = getSteveByName(context);
        if (steve == null) {
            return 0;
        }
        int food = IntegerArgumentType.getInteger(context, "value");
        steve.setFoodLevel(food);
        context.getSource().sendSuccess(() -> Component.literal(
            "Set " + steve.getSteveName() + " food to " + steve.getFoodLevel()
        ), true);
        return 1;
    }

    private static int debugSetSaturation(CommandContext<CommandSourceStack> context) {
        SteveEntity steve = getSteveByName(context);
        if (steve == null) {
            return 0;
        }
        float saturation = FloatArgumentType.getFloat(context, "value");
        steve.setSaturationLevel(saturation);
        context.getSource().sendSuccess(() -> Component.literal(
            "Set " + steve.getSteveName() + " saturation to " + formatFloat(steve.getSaturationLevel())
        ), true);
        return 1;
    }

    private static int runIronPickaxeTest(CommandContext<CommandSourceStack> context, int timeoutSeconds) {
        String name = StringArgumentType.getString(context, "name");
        SteveEntity steve = SteveMod.getSteveManager().getSteve(name);
        if (steve == null) {
            context.getSource().sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
        ServerPlayer requester = context.getSource().getPlayer();
        if (requester == null) {
            context.getSource().sendFailure(Component.literal("Command must be run by a player"));
            return 0;
        }
        return StevePlaytestRunner.startIronPickaxe(requester, steve, timeoutSeconds);
    }

    private static int spawnAndRunIronPickaxeTest(CommandContext<CommandSourceStack> context, int timeoutSeconds) {
        CommandSourceStack source = context.getSource();
        ServerPlayer requester = source.getPlayer();
        if (requester == null) {
            source.sendFailure(Component.literal("Command must be run by a player"));
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");
        ServerLevel serverLevel = source.getLevel();
        if (serverLevel == null) {
            source.sendFailure(Component.literal("Command must be run on server"));
            return 0;
        }

        SteveManager manager = SteveMod.getSteveManager();
        SteveEntity existing = manager.getSteve(name);
        if (existing != null) {
            manager.removeSteve(name);
        }

        SteveEntity steve = manager.spawnSteve(serverLevel, resolveSpawnPos(source), name);
        if (steve == null) {
            source.sendFailure(Component.literal("Failed to spawn Steve: " + name));
            return 0;
        }
        return StevePlaytestRunner.startIronPickaxe(requester, steve, timeoutSeconds);
    }

    private static SteveEntity getSteveByName(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        SteveEntity steve = SteveMod.getSteveManager().getSteve(name);
        if (steve == null) {
            context.getSource().sendFailure(Component.literal("Steve not found: " + name));
        }
        return steve;
    }

    private static String formatFloat(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static Vec3 resolveSpawnPos(CommandSourceStack source) {
        Vec3 sourcePos = source.getPosition();
        if (source.getEntity() != null) {
            Vec3 lookVec = source.getEntity().getLookAngle();
            return sourcePos.add(lookVec.x * 3, 0, lookVec.z * 3);
        }
        return sourcePos.add(3, 0, 0);
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
