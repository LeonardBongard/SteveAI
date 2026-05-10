package com.steve.ai.client;

import com.steve.ai.config.SteveConfig;
import com.steve.ai.config.SteveRuntimeSettings;
import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

public final class SteveDebugTargetRenderer {
    private static int particleTick = 0;
    private static final float MEMORY_MARKER_SIZE = 4.0F;
    private static final float CHEST_MEMORY_MARKER_SIZE = 4.5F;
    private SteveDebugTargetRenderer() {}

    public static void onRenderHighlightBlock(RenderHighlightEvent.Block event) {
        if (!SteveConfig.ENABLE_DEBUG_OVERLAY.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        SteveEntity selectedSteve = SteveDebugBlocksData.getSelectedSteve(mc, 128.0);
        if (selectedSteve == null) {
            return;
        }

        event.setCustomRenderer((source, poseStack, translucent, state) -> {
            if (translucent) {
                return;
            }
            int steveColor = ARGB.colorFromFloat(1.0F, 0.15F, 0.85F, 1.0F);
            int lookedColor = ARGB.colorFromFloat(0.65F, 1.0F, 1.0F, 1.0F);
            int memoryColor = ARGB.colorFromFloat(0.9F, 1.0F, 0.95F, 0.2F);
            int episodicMemoryColor = ARGB.colorFromFloat(0.9F, 0.25F, 0.95F, 0.35F);
            int chestMemoryColor = ARGB.colorFromFloat(0.95F, 1.0F, 0.55F, 0.1F);
            float markerScale = SteveRuntimeSettings.getMemoryMarkerScale();
            double camX = state.cameraRenderState.pos.x;
            double camY = state.cameraRenderState.pos.y;
            double camZ = state.cameraRenderState.pos.z;

            BlockPos lookedPos = event.getTarget().getBlockPos();
            drawBlockOutline(source.getBuffer(RenderTypes.lines()), poseStack.last(), lookedPos, camX, camY, camZ, lookedColor, 1.25F);

            SteveEntity steve = selectedSteve;
            for (BlockPos remembered : parseRememberedPositions(steve.getMemoryBlockPositionsSynced())) {
                if (!shouldRenderMemoryAt(mc, remembered)) {
                    continue;
                }
                drawBlockOutlineSized(source.getBuffer(RenderTypes.lines()), poseStack.last(), remembered, camX, camY, camZ, memoryColor, 1.55F, MEMORY_MARKER_SIZE * markerScale);
            }
            for (BlockPos episodicRemembered : parseRememberedPositions(steve.getMemoryEpisodicPositionsSynced())) {
                if (!shouldRenderMemoryAt(mc, episodicRemembered)) {
                    continue;
                }
                drawBlockOutlineSized(source.getBuffer(RenderTypes.lines()), poseStack.last(), episodicRemembered, camX, camY, camZ, episodicMemoryColor, 1.4F, 2.0F * markerScale);
            }
            for (BlockPos chestRemembered : parseRememberedPositions(steve.getMemoryChestPositionsSynced())) {
                if (!shouldRenderMemoryAt(mc, chestRemembered)) {
                    continue;
                }
                drawBlockOutlineSized(source.getBuffer(RenderTypes.lines()), poseStack.last(), chestRemembered, camX, camY, camZ, chestMemoryColor, 1.85F, CHEST_MEMORY_MARKER_SIZE * markerScale);
            }
            BlockPos target = steve.getDebugTargetBlock();
            if (target != null) {
                drawBlockOutline(source.getBuffer(RenderTypes.lines()), poseStack.last(), target, camX, camY, camZ, steveColor, 2.5F);
            }
        });
    }

    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?, ?> event) {
        if (!SteveConfig.ENABLE_DEBUG_OVERLAY.get()) {
            return;
        }
        if (event.getState() == null || event.getState().entityType != SteveMod.STEVE_ENTITY.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        SteveEntity selectedSteve = SteveDebugBlocksData.getSelectedSteve(mc, 128.0);
        if (selectedSteve == null) {
            return;
        }
        final double camX = event.getCameraState().pos.x;
        final double camY = event.getCameraState().pos.y;
        final double camZ = event.getCameraState().pos.z;
        final int steveColor = ARGB.colorFromFloat(1.0F, 0.15F, 0.85F, 1.0F);
        final int memoryColor = ARGB.colorFromFloat(0.9F, 1.0F, 0.95F, 0.2F);
        final int episodicMemoryColor = ARGB.colorFromFloat(0.9F, 0.25F, 0.95F, 0.35F);
        final int chestMemoryColor = ARGB.colorFromFloat(0.95F, 1.0F, 0.55F, 0.1F);
        final float markerScale = SteveRuntimeSettings.getMemoryMarkerScale();
        SubmitNodeCollector collector = event.getNodeCollector();
        PoseStack poseStack = event.getPoseStack();
        collector.submitCustomGeometry(
            poseStack,
            RenderTypes.lines(),
            (pose, consumer) -> {
                SteveEntity steve = selectedSteve;
                for (BlockPos remembered : parseRememberedPositions(steve.getMemoryBlockPositionsSynced())) {
                    if (!shouldRenderMemoryAt(mc, remembered)) {
                        continue;
                    }
                    drawBlockOutlineSized(consumer, pose, remembered, camX, camY, camZ, memoryColor, 1.45F, MEMORY_MARKER_SIZE * markerScale);
                }
                for (BlockPos episodicRemembered : parseRememberedPositions(steve.getMemoryEpisodicPositionsSynced())) {
                    if (!shouldRenderMemoryAt(mc, episodicRemembered)) {
                        continue;
                    }
                    drawBlockOutlineSized(consumer, pose, episodicRemembered, camX, camY, camZ, episodicMemoryColor, 1.35F, 2.0F * markerScale);
                }
                for (BlockPos chestRemembered : parseRememberedPositions(steve.getMemoryChestPositionsSynced())) {
                    if (!shouldRenderMemoryAt(mc, chestRemembered)) {
                        continue;
                    }
                    drawBlockOutlineSized(consumer, pose, chestRemembered, camX, camY, camZ, chestMemoryColor, 1.75F, CHEST_MEMORY_MARKER_SIZE * markerScale);
                }
                BlockPos target = steve.getDebugTargetBlock();
                if (target != null) {
                    drawBlockOutline(consumer, pose, target, camX, camY, camZ, steveColor, 2.25F);
                }
            }
        );
    }

    public static void tickParticleMarkers() {
        if (!SteveConfig.ENABLE_DEBUG_OVERLAY.get()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        particleTick++;
        if (particleTick % 6 != 0) {
            return;
        }
        SteveEntity selectedSteve = SteveDebugBlocksData.getSelectedSteve(mc, 128.0);
        if (selectedSteve == null) {
            return;
        }
        DustParticleOptions marker = new DustParticleOptions(0x26D9FF, 1.0F);
        BlockPos target = selectedSteve.getDebugTargetBlock();
        if (target != null) {
            spawnCornerParticle(mc, marker, target.getX() + 0.05, target.getY() + 0.05, target.getZ() + 0.05);
            spawnCornerParticle(mc, marker, target.getX() + 0.95, target.getY() + 0.05, target.getZ() + 0.05);
            spawnCornerParticle(mc, marker, target.getX() + 0.05, target.getY() + 0.05, target.getZ() + 0.95);
            spawnCornerParticle(mc, marker, target.getX() + 0.95, target.getY() + 0.05, target.getZ() + 0.95);
            spawnCornerParticle(mc, marker, target.getX() + 0.05, target.getY() + 0.95, target.getZ() + 0.05);
            spawnCornerParticle(mc, marker, target.getX() + 0.95, target.getY() + 0.95, target.getZ() + 0.05);
            spawnCornerParticle(mc, marker, target.getX() + 0.05, target.getY() + 0.95, target.getZ() + 0.95);
            spawnCornerParticle(mc, marker, target.getX() + 0.95, target.getY() + 0.95, target.getZ() + 0.95);
        }
    }

    private static List<BlockPos> parseRememberedPositions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>();
        String[] entries = raw.split(";");
        for (String entry : entries) {
            String[] xyz = entry.split(",");
            if (xyz.length != 3) {
                continue;
            }
            try {
                int x = Integer.parseInt(xyz[0]);
                int y = Integer.parseInt(xyz[1]);
                int z = Integer.parseInt(xyz[2]);
                out.add(new BlockPos(x, y, z));
            } catch (NumberFormatException ignored) {
                // ignore malformed debug entries
            }
        }
        return out;
    }

    private static boolean shouldRenderMemoryAt(Minecraft mc, BlockPos pos) {
        if (mc == null || mc.level == null || pos == null) {
            return false;
        }
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR)) {
            return false;
        }
        // Reduce noise: skip non-solid/transparent blocks that often look like
        // floating memory markers (grass, leaves, etc.).
        return state.canOcclude();
    }

    private static void spawnCornerParticle(Minecraft mc, DustParticleOptions marker, double x, double y, double z) {
        mc.level.addParticle(marker, x, y, z, 0.0, 0.01, 0.0);
    }

    private static void drawBlockOutline(
        VertexConsumer consumer,
        PoseStack.Pose pose,
        BlockPos block,
        double camX,
        double camY,
        double camZ,
        int color,
        float lineWidth
    ) {
        float minX = (float) (block.getX() - camX);
        float minY = (float) (block.getY() - camY);
        float minZ = (float) (block.getZ() - camZ);
        float maxX = minX + 1.0F;
        float maxY = minY + 1.0F;
        float maxZ = minZ + 1.0F;

        emitLine(consumer, pose, minX, minY, minZ, maxX, minY, minZ, color, lineWidth);
        emitLine(consumer, pose, maxX, minY, minZ, maxX, minY, maxZ, color, lineWidth);
        emitLine(consumer, pose, maxX, minY, maxZ, minX, minY, maxZ, color, lineWidth);
        emitLine(consumer, pose, minX, minY, maxZ, minX, minY, minZ, color, lineWidth);

        emitLine(consumer, pose, minX, maxY, minZ, maxX, maxY, minZ, color, lineWidth);
        emitLine(consumer, pose, maxX, maxY, minZ, maxX, maxY, maxZ, color, lineWidth);
        emitLine(consumer, pose, maxX, maxY, maxZ, minX, maxY, maxZ, color, lineWidth);
        emitLine(consumer, pose, minX, maxY, maxZ, minX, maxY, minZ, color, lineWidth);

        emitLine(consumer, pose, minX, minY, minZ, minX, maxY, minZ, color, lineWidth);
        emitLine(consumer, pose, maxX, minY, minZ, maxX, maxY, minZ, color, lineWidth);
        emitLine(consumer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, color, lineWidth);
        emitLine(consumer, pose, minX, minY, maxZ, minX, maxY, maxZ, color, lineWidth);
    }

    private static void drawBlockOutlineSized(
        VertexConsumer consumer,
        PoseStack.Pose pose,
        BlockPos block,
        double camX,
        double camY,
        double camZ,
        int color,
        float lineWidth,
        float size
    ) {
        float half = size / 2.0F;
        float centerX = (float) (block.getX() + 0.5 - camX);
        float centerY = (float) (block.getY() + 0.5 - camY);
        float centerZ = (float) (block.getZ() + 0.5 - camZ);
        float minX = centerX - half;
        float minY = centerY - half;
        float minZ = centerZ - half;
        float maxX = centerX + half;
        float maxY = centerY + half;
        float maxZ = centerZ + half;

        emitLine(consumer, pose, minX, minY, minZ, maxX, minY, minZ, color, lineWidth);
        emitLine(consumer, pose, maxX, minY, minZ, maxX, minY, maxZ, color, lineWidth);
        emitLine(consumer, pose, maxX, minY, maxZ, minX, minY, maxZ, color, lineWidth);
        emitLine(consumer, pose, minX, minY, maxZ, minX, minY, minZ, color, lineWidth);

        emitLine(consumer, pose, minX, maxY, minZ, maxX, maxY, minZ, color, lineWidth);
        emitLine(consumer, pose, maxX, maxY, minZ, maxX, maxY, maxZ, color, lineWidth);
        emitLine(consumer, pose, maxX, maxY, maxZ, minX, maxY, maxZ, color, lineWidth);
        emitLine(consumer, pose, minX, maxY, maxZ, minX, maxY, minZ, color, lineWidth);

        emitLine(consumer, pose, minX, minY, minZ, minX, maxY, minZ, color, lineWidth);
        emitLine(consumer, pose, maxX, minY, minZ, maxX, maxY, minZ, color, lineWidth);
        emitLine(consumer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, color, lineWidth);
        emitLine(consumer, pose, minX, minY, maxZ, minX, maxY, maxZ, color, lineWidth);
    }

    private static void emitLine(
        VertexConsumer consumer,
        PoseStack.Pose pose,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        int color,
        float lineWidth
    ) {
        Vector3f normal = new Vector3f(x2 - x1, y2 - y1, z2 - z1);
        if (normal.lengthSquared() > 0.0f) {
            normal.normalize();
        } else {
            normal.set(0.0f, 1.0f, 0.0f);
        }
        consumer.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, normal).setLineWidth(lineWidth);
        consumer.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, normal).setLineWidth(lineWidth);
    }
}
