package com.steve.ai.event;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.network.DebugUiTracker;
import com.steve.ai.network.SteveNetwork;
import com.steve.ai.network.VisibleBlocksPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

@Mod.EventBusSubscriber(modid = SteveMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VisibleBlocksServerTicker {
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        int interval = SteveConfig.DEBUG_VISIBLE_BLOCK_TICK_INTERVAL.get();
        if (interval <= 0) {
            return;
        }

        tickCounter++;
        if (tickCounter % interval != 0) {
            return;
        }

        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }

        boolean broadcast = SteveConfig.DEBUG_BROADCAST_VISIBLE_BLOCKS.get();
        if (!broadcast && DebugUiTracker.isEmpty()) {
            return;
        }

        int radius = SteveConfig.DEBUG_VISIBLE_BLOCK_RADIUS.get();
        int maxEntries = SteveConfig.DEBUG_VISIBLE_BLOCK_MAX_ENTRIES.get();

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        for (SteveEntity steve : SteveMod.getSteveManager().getAllSteves()) {
            if (!(steve.level() instanceof ServerLevel level)) {
                continue;
            }

            List<VisibleBlocksPacket.BlockEntry> blocks = collectBlocks(level, steve.blockPosition(), radius, maxEntries);
            VisibleBlocksPacket packet = new VisibleBlocksPacket(steve.getUUID(), steve.getSteveName(), blocks);

            for (ServerPlayer player : players) {
                if (!broadcast && !DebugUiTracker.isSubscribed(player.getUUID())) {
                    continue;
                }
                if (player.level() != level) {
                    continue;
                }
                SteveNetwork.sendToPlayer(player, packet);
            }
        }
    }

    private static List<VisibleBlocksPacket.BlockEntry> collectBlocks(ServerLevel level, BlockPos center, int radius, int maxEntries) {
        List<VisibleBlocksPacket.BlockEntry> entries = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - radius);
        int maxY = Math.min(level.getMaxBuildHeight(), center.getY() + radius);
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (entries.size() >= maxEntries) {
                        return entries;
                    }
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    String metadata = state.getProperties().isEmpty() ? null : state.getValues().toString();
                    entries.add(new VisibleBlocksPacket.BlockEntry(blockId, cursor.immutable(), metadata));
                }
            }
        }

        return entries;
    }
}
