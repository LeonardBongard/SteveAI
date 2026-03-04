package com.steve.ai.testing;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class StevePlaytestRunner {
    private static final String SCENARIO_IRON_PICKAXE = "iron_pickaxe";
    private static final int DEFAULT_TIMEOUT_SECONDS = 420;
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 1800;
    private static final int NO_PROGRESS_TIMEOUT_TICKS = 20 * 45;
    private static final int IDLE_RECOVERY_TRIGGER_TICKS = 20 * 8;
    private static final int MAX_SESSION_RECOVERY_ATTEMPTS = 1;
    private static final int START_DEBOUNCE_TICKS = 20;
    private static final UUID SYSTEM_REQUESTER = new UUID(0L, 0L);
    private static boolean worldAutoTriggered = false;
    private static long worldAutoTriggerTick = -1L;

    private static final Item IRON_PICKAXE_ITEM = BuiltInRegistries.ITEM
        .getOptional(Identifier.fromNamespaceAndPath("minecraft", "iron_pickaxe"))
        .orElse(null);

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Long> LAST_START_TICK_BY_STEVE = new HashMap<>();

    private StevePlaytestRunner() {
    }

    public static int startIronPickaxe(ServerPlayer requester, SteveEntity steve, int timeoutSeconds) {
        return startIronPickaxeInternal(requester, steve, timeoutSeconds);
    }

    private static int startIronPickaxeInternal(ServerPlayer requester, SteveEntity steve, int timeoutSeconds) {
        if (IRON_PICKAXE_ITEM == null) {
            if (requester != null) {
                requester.sendSystemMessage(Component.literal("[playtest] Failed: missing minecraft:iron_pickaxe item id"));
            }
            return 0;
        }
        int boundedTimeout = Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, timeoutSeconds));
        long nowTick = steve.level().getGameTime();
        Session existing = SESSIONS.get(steve.getUUID());
        if (existing != null && SCENARIO_IRON_PICKAXE.equals(existing.scenario())) {
            Long lastStartTick = LAST_START_TICK_BY_STEVE.get(steve.getUUID());
            if (lastStartTick != null && nowTick - lastStartTick <= START_DEBOUNCE_TICKS) {
                SteveMod.LOGGER.info(
                    "[PLAYTEST] ignored duplicate start scenario={} steve={} tickDelta={}",
                    SCENARIO_IRON_PICKAXE,
                    steve.getSteveName(),
                    nowTick - lastStartTick
                );
                return 0;
            }
        }

        cancelDuplicateSessionsForSteve(steve);
        cancelForSteve(steve.getUUID(), "replaced-by-new-run");

        steve.getActionExecutor().stopCurrentAction();
        steve.getMemory().clearTaskQueue();

        Session session = new Session(
            steve.getUUID(),
            steve.getSteveName(),
            requester != null ? requester.getUUID() : SYSTEM_REQUESTER,
            requester != null ? requester.getName().getString() : "system-auto",
            SCENARIO_IRON_PICKAXE,
            nowTick,
            nowTick,
            boundedTimeout * 20L,
            steve.getX(),
            steve.getY(),
            steve.getZ(),
            steve.getInventorySummary(),
            steve.getActionExecutor().isExecuting() || steve.getActionExecutor().isPlanning(),
            0
        );
        SESSIONS.put(steve.getUUID(), session);
        LAST_START_TICK_BY_STEVE.put(steve.getUUID(), nowTick);

        Map<String, Object> params = new HashMap<>();
        params.put("item", "iron_pickaxe");
        params.put("quantity", 1);
        steve.getActionExecutor().enqueueTask(new Task("craft", params));

        if (requester != null) {
            requester.sendSystemMessage(Component.literal(
                "[playtest] Started iron_pickaxe for " + steve.getSteveName()
                    + " timeout=" + boundedTimeout + "s (direct-task mode)"
            ));
        }
        SteveMod.LOGGER.info(
            "[PLAYTEST] start scenario={} steve={} timeoutSeconds={} requester={} mode=direct-task",
            SCENARIO_IRON_PICKAXE,
            steve.getSteveName(),
            boundedTimeout,
            requester != null ? requester.getName().getString() : "system-auto"
        );
        return 1;
    }

    public static void tick(MinecraftServer server) {
        if (server == null || SESSIONS.isEmpty()) {
            maybeAutoStartOnWorldLoad(server);
            if (server == null || SESSIONS.isEmpty()) {
                return;
            }
        }
        SteveManager manager = SteveMod.getSteveManager();
        long nowTick = server.overworld().getGameTime();

        Map<UUID, Session> snapshot = new HashMap<>(SESSIONS);
        for (Session session : snapshot.values()) {
            SteveEntity steve = manager.getSteve(session.steveName());
            if (steve == null || !steve.isAlive()) {
                finish(server, session, false, "steve-missing-or-dead");
                continue;
            }
            if (!steve.getUUID().equals(session.steveUuid())) {
                if (session.recoveryAttempts() < MAX_SESSION_RECOVERY_ATTEMPTS) {
                    rebindAndRecoverSession(session, steve, nowTick, "steve-rebound");
                    continue;
                }
                finish(server, session, false, "steve-replaced-no-recovery-budget");
                continue;
            }

            boolean hasGoalItem = steve.getItemCount(IRON_PICKAXE_ITEM) > 0;
            boolean busy = steve.getActionExecutor().isExecuting() || steve.getActionExecutor().isPlanning();
            String inv = steve.getInventorySummary();
            String debug = steve.getDebugStatus();
            double movedSqr = distanceSqr(steve.getX(), steve.getY(), steve.getZ(), session.lastX(), session.lastY(), session.lastZ());
            boolean inventoryChanged = !inv.equals(session.lastInventorySummary());
            boolean progressed = movedSqr > 0.04 || inventoryChanged || (busy != session.lastBusy());

            if (hasGoalItem) {
                finish(server, session, true, "iron-pickaxe-crafted");
                continue;
            }

            if (progressed) {
                SESSIONS.put(
                    session.steveUuid(),
                    session.withProgress(nowTick, steve.getX(), steve.getY(), steve.getZ(), inv, busy)
                );
                session = SESSIONS.get(session.steveUuid());
            }

            if (!busy
                && debug != null
                && debug.toLowerCase(Locale.ROOT).contains("idle")
                && nowTick - session.lastProgressTick() >= IDLE_RECOVERY_TRIGGER_TICKS) {
                if (session.recoveryAttempts() < MAX_SESSION_RECOVERY_ATTEMPTS) {
                    restartScenarioTask(session, steve, nowTick, "idle-recovery");
                    continue;
                }
                finish(server, session, false, "idle-no-task-recovery-exhausted");
                continue;
            }

            if (nowTick - session.startTick() >= session.timeoutTicks()) {
                finish(server, session, false, "timeout");
                continue;
            }
            if (nowTick - session.lastProgressTick() >= NO_PROGRESS_TIMEOUT_TICKS) {
                finish(server, session, false, "no-progress-stall");
            }
        }
    }

    private static void rebindAndRecoverSession(Session session, SteveEntity steve, long nowTick, String reason) {
        SESSIONS.remove(session.steveUuid());
        Session rebound = new Session(
            steve.getUUID(),
            steve.getSteveName(),
            session.requesterUuid(),
            session.requesterName(),
            session.scenario(),
            session.startTick(),
            nowTick,
            session.timeoutTicks(),
            steve.getX(),
            steve.getY(),
            steve.getZ(),
            steve.getInventorySummary(),
            steve.getActionExecutor().isExecuting() || steve.getActionExecutor().isPlanning(),
            session.recoveryAttempts() + 1
        );
        SESSIONS.put(rebound.steveUuid(), rebound);
        LAST_START_TICK_BY_STEVE.put(rebound.steveUuid(), nowTick);
        restartScenarioTask(rebound, steve, nowTick, reason);
    }

    private static void restartScenarioTask(Session session, SteveEntity steve, long nowTick, String reason) {
        steve.getActionExecutor().stopCurrentAction();
        steve.getMemory().clearTaskQueue();
        Map<String, Object> params = new HashMap<>();
        params.put("item", "iron_pickaxe");
        params.put("quantity", 1);
        steve.getActionExecutor().enqueueTask(new Task("craft", params));
        SESSIONS.put(
            session.steveUuid(),
            session.withProgress(nowTick, steve.getX(), steve.getY(), steve.getZ(), steve.getInventorySummary(), true)
        );
        SteveMod.LOGGER.warn(
            "[PLAYTEST] recovery scenario={} steve={} reason={} recoveryAttempts={}",
            session.scenario(),
            steve.getSteveName(),
            reason,
            SESSIONS.get(session.steveUuid()).recoveryAttempts()
        );
    }

    private static void maybeAutoStartOnWorldLoad(MinecraftServer server) {
        if (server == null || !SteveConfig.AUTO_RUN_IRON_PICKAXE_PLAYTEST_ON_WORLD_LOAD.get()) {
            worldAutoTriggered = false;
            worldAutoTriggerTick = -1L;
            return;
        }
        if (worldAutoTriggered) {
            return;
        }

        ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (player == null) {
            return;
        }

        SteveManager manager = SteveMod.getSteveManager();
        String steveName = SteveConfig.AUTO_PLAYTEST_STEVE_NAME.get();
        if (steveName == null || steveName.isBlank()) {
            steveName = "Steve";
        }

        SteveEntity steve = manager.getSteve(steveName);
        if (steve == null && SteveConfig.AUTO_PLAYTEST_AUTO_SPAWN_STEVE.get()) {
            ServerLevel level = (ServerLevel) player.level();
            steve = manager.spawnSteve(level, player.position().add(2.0, 0.0, 2.0), steveName);
            if (steve != null) {
                SteveMod.LOGGER.info("[PLAYTEST] auto-spawned '{}' for world-load playtest", steveName);
            }
        }
        if (steve == null) {
            return;
        }

        int timeoutSeconds = SteveConfig.AUTO_PLAYTEST_TIMEOUT_SECONDS.get();
        int started = startIronPickaxeInternal(player, steve, timeoutSeconds);
        if (started > 0) {
            worldAutoTriggered = true;
            worldAutoTriggerTick = server.overworld().getGameTime();
            SteveMod.LOGGER.info(
                "[PLAYTEST] world-load auto-start triggered at tick={} steve={}",
                worldAutoTriggerTick,
                steveName
            );
        }
    }

    private static void cancelForSteve(UUID steveUuid, String reason) {
        if (steveUuid == null) {
            return;
        }
        Session removed = SESSIONS.remove(steveUuid);
        if (removed != null) {
            SteveMod.LOGGER.info(
                "[PLAYTEST] cancel scenario={} steve={} reason={}",
                removed.scenario(),
                removed.steveName(),
                reason
            );
        }
        LAST_START_TICK_BY_STEVE.remove(steveUuid);
    }

    private static void cancelDuplicateSessionsForSteve(SteveEntity steve) {
        if (steve == null) {
            return;
        }
        UUID steveUuid = steve.getUUID();
        String steveName = steve.getSteveName();
        Map<UUID, Session> snapshot = new HashMap<>(SESSIONS);
        for (Map.Entry<UUID, Session> entry : snapshot.entrySet()) {
            Session session = entry.getValue();
            if (session == null) {
                continue;
            }
            boolean sameUuid = steveUuid.equals(session.steveUuid());
            boolean sameName = steveName.equalsIgnoreCase(session.steveName());
            if (!sameUuid && sameName) {
                SESSIONS.remove(entry.getKey());
                LAST_START_TICK_BY_STEVE.remove(entry.getKey());
                SteveMod.LOGGER.warn(
                    "[PLAYTEST] canceled duplicate concurrent session steve={} scenario={} reason=same-name-conflict",
                    session.steveName(),
                    session.scenario()
                );
            }
        }
    }

    private static void finish(MinecraftServer server, Session session, boolean success, String reason) {
        SESSIONS.remove(session.steveUuid());
        SteveEntity steve = SteveMod.getSteveManager().getSteve(session.steveName());
        String inventory = steve != null ? steve.getInventorySummary() : "steve-missing";
        String debugStatus = steve != null ? steve.getDebugStatus() : "steve-missing";
        double x = steve != null ? steve.getX() : session.lastX();
        double y = steve != null ? steve.getY() : session.lastY();
        double z = steve != null ? steve.getZ() : session.lastZ();
        long nowTick = server.overworld().getGameTime();
        long elapsedTicks = Math.max(0, nowTick - session.startTick());
        long elapsedSeconds = elapsedTicks / 20L;

        String report = buildReport(session, success, reason, elapsedSeconds, x, y, z, inventory, debugStatus);
        Path reportPath = writeReport(report, session.steveName(), session.scenario(), nowTick);

        ServerPlayer requester = server.getPlayerList().getPlayer(session.requesterUuid());
        String message = "[playtest] " + session.scenario() + " for " + session.steveName()
            + " => " + (success ? "PASS" : "FAIL")
            + " (" + reason + "), " + elapsedSeconds + "s"
            + ", report=" + reportPath;
        if (requester != null) {
            requester.sendSystemMessage(Component.literal(message));
        }

        SteveMod.LOGGER.info(
            "[PLAYTEST] finish scenario={} steve={} result={} reason={} elapsedSeconds={} report={}",
            session.scenario(),
            session.steveName(),
            success ? "PASS" : "FAIL",
            reason,
            elapsedSeconds,
            reportPath
        );
    }

    private static String buildReport(
        Session session,
        boolean success,
        String reason,
        long elapsedSeconds,
        double x,
        double y,
        double z,
        String inventory,
        String debugStatus
    ) {
        return "timestamp=" + Instant.now() + "\n"
            + "scenario=" + session.scenario() + "\n"
            + "steve=" + session.steveName() + "\n"
            + "requester=" + session.requesterName() + "\n"
            + "result=" + (success ? "PASS" : "FAIL") + "\n"
            + "reason=" + reason + "\n"
            + "elapsed_seconds=" + elapsedSeconds + "\n"
            + String.format(Locale.ROOT, "position=%.2f,%.2f,%.2f%n", x, y, z)
            + "inventory=" + inventory + "\n"
            + "debug_status=" + debugStatus + "\n";
    }

    private static Path writeReport(String report, String steveName, String scenario, long nowTick) {
        Path reportDir = Paths.get("logs", "steve_playtests");
        try {
            Files.createDirectories(reportDir);
            String filename = scenario + "_" + steveName + "_" + nowTick + ".txt";
            Path output = reportDir.resolve(filename);
            Files.writeString(output, report, StandardCharsets.UTF_8);
            return output;
        } catch (IOException e) {
            SteveMod.LOGGER.warn("[PLAYTEST] failed writing report", e);
            return reportDir.resolve("write_failed.txt");
        }
    }

    private static double distanceSqr(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    public static int defaultTimeoutSeconds() {
        return DEFAULT_TIMEOUT_SECONDS;
    }

    private record Session(
        UUID steveUuid,
        String steveName,
        UUID requesterUuid,
        String requesterName,
        String scenario,
        long startTick,
        long lastProgressTick,
        long timeoutTicks,
        double lastX,
        double lastY,
        double lastZ,
        String lastInventorySummary,
        boolean lastBusy,
        int recoveryAttempts
    ) {
        private Session withProgress(long tick, double x, double y, double z, String inventorySummary, boolean busy) {
            return new Session(
                steveUuid,
                steveName,
                requesterUuid,
                requesterName,
                scenario,
                startTick,
                tick,
                timeoutTicks,
                x,
                y,
                z,
                inventorySummary,
                busy,
                recoveryAttempts
            );
        }
    }
}
