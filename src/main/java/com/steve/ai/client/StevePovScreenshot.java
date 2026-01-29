package com.steve.ai.client;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;

import java.io.File;
import java.util.List;

public final class StevePovScreenshot {
    private static boolean pending;
    private static boolean cameraSwitched;
    private static int ticksAfterSwitch;
    private static long requestTimeMs;
    private static Entity previousCamera;
    private static SteveEntity targetSteve;

    private static final long REQUEST_TIMEOUT_MS = 5000;

    private StevePovScreenshot() {}

    public static boolean toggleSteveCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }

        Entity currentCamera = mc.getCameraEntity();
        if (currentCamera instanceof SteveEntity) {
            mc.setCameraEntity(mc.player);
            SteveMod.LOGGER.info("Switched camera back to player");
            return true;
        }

        SteveEntity target = findNearestSteve(mc);
        if (target == null) {
            return false;
        }

        mc.setCameraEntity(target);
        SteveMod.LOGGER.info("Switched camera to Steve '{}'", target.getSteveName());
        return true;
    }

    public static boolean requestNearestSteve() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }

        SteveEntity nearest = findNearestSteve(mc);

        if (nearest == null) {
            return false;
        }

        pending = true;
        cameraSwitched = false;
        ticksAfterSwitch = 0;
        requestTimeMs = System.currentTimeMillis();
        targetSteve = nearest;
        SteveMod.LOGGER.info("Requested Steve POV screenshot for '{}'", nearest.getSteveName());
        return true;
    }

    public static void onClientTick() {
        if (!pending) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            reset();
            return;
        }

        if (System.currentTimeMillis() - requestTimeMs > REQUEST_TIMEOUT_MS) {
            SteveMod.LOGGER.warn("Steve POV screenshot request timed out");
            restoreCamera();
            reset();
            return;
        }

        if (targetSteve == null || targetSteve.isRemoved()) {
            SteveMod.LOGGER.warn("Steve POV screenshot target missing");
            restoreCamera();
            reset();
            return;
        }

        if (!cameraSwitched) {
            previousCamera = mc.getCameraEntity();
            mc.setCameraEntity(targetSteve);
            cameraSwitched = true;
            ticksAfterSwitch = 0;
            return;
        }

        ticksAfterSwitch++;
        if (ticksAfterSwitch >= 1) {
            captureScreenshot();
        }
    }

    private static void captureScreenshot() {
        Minecraft mc = Minecraft.getInstance();
        File screenshotDir = new File(mc.gameDirectory, Screenshot.SCREENSHOT_DIR);
        Screenshot.grab(screenshotDir, mc.getMainRenderTarget(), component -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(component, false);
            }
        });
        restoreCamera();
        reset();
    }

    private static void restoreCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (previousCamera != null) {
            mc.setCameraEntity(previousCamera);
        }
    }

    private static void reset() {
        pending = false;
        cameraSwitched = false;
        ticksAfterSwitch = 0;
        previousCamera = null;
        targetSteve = null;
        requestTimeMs = 0;
    }

    private static SteveEntity findNearestSteve(Minecraft mc) {
        List<SteveEntity> steves = mc.level.getEntitiesOfClass(
            SteveEntity.class,
            mc.player.getBoundingBox().inflate(96)
        );

        if (steves.isEmpty()) {
            return null;
        }

        SteveEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (SteveEntity steve : steves) {
            double dist = steve.distanceToSqr(mc.player);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = steve;
            }
        }

        return nearest;
    }
}
