package com.steve.ai.testing;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.config.StevePersona;
import com.steve.ai.config.StevePersonaProfiles;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StevePlaytestRunner {
    private static final String SCENARIO_IRON_PICKAXE = "iron_pickaxe";
    private static final int DEFAULT_TIMEOUT_SECONDS = 420;
    private static final int MIN_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 1800;
    private static final int NO_PROGRESS_TIMEOUT_TICKS = 20 * 45;
    private static final int IDLE_RECOVERY_TRIGGER_TICKS = 20 * 8;
    private static final int MAX_SESSION_RECOVERY_ATTEMPTS = 1;
    private static final int START_DEBOUNCE_TICKS = 20;
    private static final double KPI_MIN_PATH_EFFICIENCY = 0.20;
    private static final double KPI_MIN_PROGRESS_EVENTS_PER_MIN = 8.0;
    private static final double KPI_MAX_SEARCHING_TICKS_PER_MIN = 700.0;
    private static final long KPI_MAX_NO_PROGRESS_TICKS = 20L * 30L;
    private static final double KPI_MIN_BUSY_RATIO = 0.55;
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
        return startScenarioInternal(requester, steve, SCENARIO_IRON_PICKAXE, timeoutSeconds);
    }

    private static int startScenarioInternal(ServerPlayer requester, SteveEntity steve, String scenario, int timeoutSeconds) {
        String normalizedScenario = normalizeScenario(scenario);
        if (!isScenarioSupported(normalizedScenario)) {
            if (requester != null) {
                requester.sendSystemMessage(Component.literal("[playtest] Failed: unsupported scenario '" + scenario + "'"));
            }
            return 0;
        }
        if (SCENARIO_IRON_PICKAXE.equals(normalizedScenario) && IRON_PICKAXE_ITEM == null) {
            if (requester != null) {
                requester.sendSystemMessage(Component.literal("[playtest] Failed: missing minecraft:iron_pickaxe item id"));
            }
            return 0;
        }
        int boundedTimeout = Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, timeoutSeconds));
        long nowTick = steve.level().getGameTime();
        Session existing = SESSIONS.get(steve.getUUID());
        if (existing != null && normalizedScenario.equals(existing.scenario())) {
            Long lastStartTick = LAST_START_TICK_BY_STEVE.get(steve.getUUID());
            if (lastStartTick != null && nowTick - lastStartTick <= START_DEBOUNCE_TICKS) {
                SteveMod.LOGGER.info(
                    "[PLAYTEST] ignored duplicate start scenario={} steve={} tickDelta={}",
                    normalizedScenario,
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
            normalizedScenario,
            nowTick,
            nowTick,
            boundedTimeout * 20L,
            steve.getX(),
            steve.getY(),
            steve.getZ(),
            steve.getX(),
            steve.getY(),
            steve.getZ(),
            steve.getInventorySummary(),
            steve.getDebugStatus(),
            steve.getActionExecutor().isExecuting() || steve.getActionExecutor().isPlanning(),
            0,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0.0,
            0
        );
        SESSIONS.put(steve.getUUID(), session);
        LAST_START_TICK_BY_STEVE.put(steve.getUUID(), nowTick);
        steve.setPlaytestStatus("RUNNING", normalizedScenario + " (started)");
        enqueueScenarioTask(steve, normalizedScenario);

        if (requester != null) {
            requester.sendSystemMessage(Component.literal(
                "[playtest] Started " + normalizedScenario + " for " + steve.getSteveName()
                    + " timeout=" + boundedTimeout + "s (direct-task mode)"
            ));
        }
        SteveMod.LOGGER.info(
            "[PLAYTEST] start scenario={} steve={} timeoutSeconds={} requester={} mode=direct-task",
            normalizedScenario,
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

            boolean hasGoalItem = isScenarioSatisfied(session.scenario(), steve);
            boolean busy = steve.getActionExecutor().isExecuting() || steve.getActionExecutor().isPlanning();
            String inv = steve.getInventorySummary();
            String debug = steve.getDebugStatus();
            double movedSqr = distanceSqr(steve.getX(), steve.getY(), steve.getZ(), session.lastX(), session.lastY(), session.lastZ());
            boolean inventoryChanged = !inv.equals(session.lastInventorySummary());
            boolean progressed = movedSqr > 0.04 || inventoryChanged || (busy != session.lastBusy());
            boolean searching = debug != null && debug.toLowerCase(Locale.ROOT).contains("search");

            if (hasGoalItem) {
                session = session.withTickSample(
                    nowTick,
                    steve.getX(),
                    steve.getY(),
                    steve.getZ(),
                    inv,
                    debug,
                    busy,
                    inventoryChanged,
                    searching,
                    movedSqr,
                    progressed
                );
                SESSIONS.put(session.steveUuid(), session);
                finish(server, session, true, "iron-pickaxe-crafted");
                continue;
            }

            session = session.withTickSample(
                nowTick,
                steve.getX(),
                steve.getY(),
                steve.getZ(),
                inv,
                debug,
                busy,
                inventoryChanged,
                searching,
                movedSqr,
                progressed
            );
            SESSIONS.put(session.steveUuid(), session);

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
            session.startX(),
            session.startY(),
            session.startZ(),
            steve.getX(),
            steve.getY(),
            steve.getZ(),
            steve.getInventorySummary(),
            steve.getDebugStatus(),
            steve.getActionExecutor().isExecuting() || steve.getActionExecutor().isPlanning(),
            session.progressEvents(),
            session.inventoryChangeEvents(),
            session.busyTicks(),
            session.idleTicks(),
            session.searchingTicks(),
            session.debugStatusChangeEvents(),
            session.maxNoProgressTicks(),
            session.cumulativeDistanceBlocks(),
            session.recoveryAttempts() + 1
        );
        SESSIONS.put(rebound.steveUuid(), rebound);
        LAST_START_TICK_BY_STEVE.put(rebound.steveUuid(), nowTick);
        restartScenarioTask(rebound, steve, nowTick, reason);
    }

    private static void restartScenarioTask(Session session, SteveEntity steve, long nowTick, String reason) {
        steve.getActionExecutor().stopCurrentAction();
        steve.getMemory().clearTaskQueue();
        enqueueScenarioTask(steve, session.scenario());
        SESSIONS.put(
            session.steveUuid(),
            session.withTickSample(
                nowTick,
                steve.getX(),
                steve.getY(),
                steve.getZ(),
                steve.getInventorySummary(),
                steve.getDebugStatus(),
                true,
                false,
                false,
                0.0,
                true
            )
        );
        steve.setPlaytestStatus("RUNNING", session.scenario() + " (" + reason + ")");
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
        List<AutoPlaytestSpec> specs = parseAutoPlaytestSpecs(
            SteveConfig.AUTO_PLAYTEST_MATRIX.get(),
            SteveConfig.AUTO_PLAYTEST_STEVE_NAME.get()
        );
        int timeoutSeconds = SteveConfig.AUTO_PLAYTEST_TIMEOUT_SECONDS.get();
        int started = 0;
        ServerLevel level = (ServerLevel) player.level();

        for (int i = 0; i < specs.size(); i++) {
            AutoPlaytestSpec spec = specs.get(i);
            String steveName = spec.name();
            StevePersona persona = spec.persona();
            if (persona != null) {
                StevePersonaProfiles.setRuntimeOverride(steveName, persona);
            }
            SteveEntity steve = manager.getSteve(steveName);
            if (steve == null && SteveConfig.AUTO_PLAYTEST_AUTO_SPAWN_STEVE.get()) {
                // Stagger spawn positions to avoid overlap when running multi-persona tests.
                steve = manager.spawnSteve(level, player.position().add(2.0 + (i * 2.0), 0.0, 2.0), steveName);
                if (steve != null) {
                    SteveMod.LOGGER.info(
                        "[PLAYTEST] auto-spawned '{}' for world-load playtest scenario={} persona={}",
                        steveName,
                        spec.scenario(),
                        persona == null ? "default" : persona
                    );
                }
            }
            if (steve == null) {
                continue;
            }
            started += startScenarioInternal(player, steve, spec.scenario(), timeoutSeconds);
        }

        if (started > 0) {
            worldAutoTriggered = true;
            worldAutoTriggerTick = server.overworld().getGameTime();
            SteveMod.LOGGER.info(
                "[PLAYTEST] world-load auto-start triggered at tick={} specs={} started={}",
                worldAutoTriggerTick,
                specs,
                started
            );
        }
    }

    private static List<AutoPlaytestSpec> parseAutoPlaytestSpecs(String matrixRaw, String fallbackNamesRaw) {
        List<AutoPlaytestSpec> specs = parseMatrixEntries(matrixRaw);
        if (!specs.isEmpty()) {
            return specs;
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (fallbackNamesRaw != null) {
            for (String entry : fallbackNamesRaw.split(",")) {
                String name = entry == null ? "" : entry.trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        if (names.isEmpty()) {
            names.add("Steve");
            names.add("Alex");
        }

        List<AutoPlaytestSpec> fallback = new ArrayList<>();
        for (String name : names) {
            fallback.add(new AutoPlaytestSpec(name, null, SCENARIO_IRON_PICKAXE));
        }
        return fallback;
    }

    private static List<AutoPlaytestSpec> parseMatrixEntries(String raw) {
        List<AutoPlaytestSpec> specs = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return specs;
        }
        for (String entry : raw.split(",")) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split(":");
            if (parts.length < 2) {
                continue;
            }
            String name = parts[0].trim();
            if (name.isBlank()) {
                continue;
            }
            StevePersona persona = null;
            String scenario;
            if (parts.length >= 3) {
                persona = StevePersona.fromString(parts[1].trim());
                scenario = normalizeScenario(parts[2]);
            } else {
                String maybeScenario = normalizeScenario(parts[1]);
                if (isScenarioSupported(maybeScenario)) {
                    scenario = maybeScenario;
                } else {
                    persona = StevePersona.fromString(parts[1].trim());
                    scenario = SCENARIO_IRON_PICKAXE;
                }
            }
            if (!isScenarioSupported(scenario)) {
                continue;
            }
            specs.add(new AutoPlaytestSpec(name, persona, scenario));
        }
        return specs;
    }

    private static String normalizeScenario(String raw) {
        if (raw == null || raw.isBlank()) {
            return SCENARIO_IRON_PICKAXE;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isScenarioSupported(String scenario) {
        return SCENARIO_IRON_PICKAXE.equals(normalizeScenario(scenario));
    }

    private static boolean isScenarioSatisfied(String scenario, SteveEntity steve) {
        String normalized = normalizeScenario(scenario);
        if (SCENARIO_IRON_PICKAXE.equals(normalized)) {
            return IRON_PICKAXE_ITEM != null && steve.getItemCount(IRON_PICKAXE_ITEM) > 0;
        }
        return false;
    }

    private static void enqueueScenarioTask(SteveEntity steve, String scenario) {
        String normalized = normalizeScenario(scenario);
        if (SCENARIO_IRON_PICKAXE.equals(normalized)) {
            Map<String, Object> params = new HashMap<>();
            params.put("item", "iron_pickaxe");
            params.put("quantity", 1);
            steve.getActionExecutor().enqueueTask(new Task("craft", params));
            return;
        }
        SteveMod.LOGGER.warn("[PLAYTEST] unsupported scenario enqueue attempted: {}", scenario);
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
        KpiMetrics metrics = KpiMetrics.fromSession(session, elapsedSeconds, x, y, z);
        Path previousReport = findLatestPreviousReport(session.steveName(), session.scenario(), nowTick);
        KpiMetrics previousMetrics = previousReport == null ? null : parseKpiMetrics(previousReport);
        KpiEvaluation evaluation = evaluateKpis(metrics, success);
        KpiComparison comparison = compareKpis(metrics, previousMetrics);
        String report = buildReport(
            session,
            success,
            reason,
            elapsedSeconds,
            x,
            y,
            z,
            inventory,
            debugStatus,
            metrics,
            evaluation,
            comparison,
            previousReport
        );
        Path reportPath = writeReport(report, session.steveName(), session.scenario(), nowTick);
        if (steve != null) {
            steve.setPlaytestStatus(success ? "PASS" : "FAIL", session.scenario() + " (" + reason + ")");
        }

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
        String debugStatus,
        KpiMetrics metrics,
        KpiEvaluation evaluation,
        KpiComparison comparison,
        Path previousReport
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
            + "debug_status=" + debugStatus + "\n"
            + "kpi_progress_events=" + metrics.progressEvents() + "\n"
            + "kpi_inventory_changes=" + metrics.inventoryChangeEvents() + "\n"
            + "kpi_busy_ticks=" + metrics.busyTicks() + "\n"
            + "kpi_idle_ticks=" + metrics.idleTicks() + "\n"
            + "kpi_searching_ticks=" + metrics.searchingTicks() + "\n"
            + "kpi_debug_status_changes=" + metrics.debugStatusChangeEvents() + "\n"
            + "kpi_max_no_progress_ticks=" + metrics.maxNoProgressTicks() + "\n"
            + String.format(Locale.ROOT, "kpi_distance_path_blocks=%.2f%n", metrics.pathDistance())
            + String.format(Locale.ROOT, "kpi_distance_displacement_blocks=%.2f%n", metrics.netDisplacement())
            + String.format(Locale.ROOT, "kpi_path_efficiency=%.4f%n", metrics.pathEfficiency())
            + String.format(Locale.ROOT, "kpi_avg_speed_blocks_per_sec=%.4f%n", metrics.avgSpeed())
            + String.format(Locale.ROOT, "kpi_progress_events_per_min=%.2f%n", metrics.progressPerMinute())
            + String.format(Locale.ROOT, "kpi_searching_ticks_per_min=%.2f%n", metrics.searchPerMinute())
            + String.format(Locale.ROOT, "kpi_busy_ratio=%.4f%n", metrics.busyRatio())
            + String.format(Locale.ROOT, "kpi_idle_ratio=%.4f%n", metrics.idleRatio())
            + "kpi_recovery_attempts=" + metrics.recoveryAttempts() + "\n"
            + "kpi_eval_overall=" + (evaluation.overallPass() ? "PASS" : "FAIL") + "\n"
            + "kpi_eval_fail_count=" + evaluation.failCount() + "\n"
            + "kpi_eval_details=" + evaluation.details() + "\n"
            + "kpi_cmp_previous_report=" + (previousReport == null ? "none" : previousReport) + "\n"
            + "kpi_cmp_previous_available=" + (comparison.previousAvailable() ? "true" : "false") + "\n"
            + String.format(Locale.ROOT, "kpi_cmp_path_efficiency_delta=%.4f%n", comparison.pathEfficiencyDelta())
            + String.format(Locale.ROOT, "kpi_cmp_progress_events_per_min_delta=%.2f%n", comparison.progressPerMinDelta())
            + String.format(Locale.ROOT, "kpi_cmp_searching_ticks_per_min_delta=%.2f%n", comparison.searchPerMinDelta())
            + String.format(Locale.ROOT, "kpi_cmp_max_no_progress_ticks_delta=%.0f%n", comparison.maxNoProgressTicksDelta())
            + String.format(Locale.ROOT, "kpi_cmp_busy_ratio_delta=%.4f%n", comparison.busyRatioDelta());
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

    private static Path findLatestPreviousReport(String steveName, String scenario, long nowTick) {
        Path reportDir = Paths.get("logs", "steve_playtests");
        if (!Files.isDirectory(reportDir)) {
            return null;
        }
        Pattern pattern = Pattern.compile(
            "^" + Pattern.quote(scenario) + "_" + Pattern.quote(steveName) + "_(\\d+)\\.txt$"
        );
        try (var stream = Files.list(reportDir)) {
            return stream
                .filter(path -> path != null && Files.isRegularFile(path))
                .map(path -> {
                    Matcher matcher = pattern.matcher(path.getFileName().toString());
                    if (!matcher.matches()) {
                        return null;
                    }
                    try {
                        long tick = Long.parseLong(matcher.group(1));
                        if (tick >= nowTick) {
                            return null;
                        }
                        return new ReportCandidate(path, tick);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .filter(candidate -> candidate != null)
                .max(Comparator.comparingLong(ReportCandidate::tick))
                .map(ReportCandidate::path)
                .orElse(null);
        } catch (IOException e) {
            SteveMod.LOGGER.debug("[PLAYTEST] failed scanning prior reports for comparator", e);
            return null;
        }
    }

    private static KpiMetrics parseKpiMetrics(Path reportPath) {
        if (reportPath == null || !Files.isRegularFile(reportPath)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(reportPath, StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                int idx = line.indexOf('=');
                if (idx <= 0 || idx >= line.length() - 1) {
                    continue;
                }
                values.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
            if (!values.containsKey("kpi_progress_events")) {
                return null;
            }
            return new KpiMetrics(
                parseLong(values.get("kpi_progress_events"), 0L),
                parseLong(values.get("kpi_inventory_changes"), 0L),
                parseLong(values.get("kpi_busy_ticks"), 0L),
                parseLong(values.get("kpi_idle_ticks"), 0L),
                parseLong(values.get("kpi_searching_ticks"), 0L),
                parseLong(values.get("kpi_debug_status_changes"), 0L),
                parseLong(values.get("kpi_max_no_progress_ticks"), 0L),
                parseDouble(values.get("kpi_distance_path_blocks"), 0.0),
                parseDouble(values.get("kpi_distance_displacement_blocks"), 0.0),
                parseDouble(values.get("kpi_path_efficiency"), 0.0),
                parseDouble(values.get("kpi_avg_speed_blocks_per_sec"), 0.0),
                parseDouble(values.get("kpi_progress_events_per_min"), 0.0),
                parseDouble(values.get("kpi_searching_ticks_per_min"), 0.0),
                parseDouble(values.get("kpi_busy_ratio"), 0.0),
                parseDouble(values.get("kpi_idle_ratio"), 0.0),
                parseLong(values.get("kpi_recovery_attempts"), 0L)
            );
        } catch (IOException e) {
            SteveMod.LOGGER.debug("[PLAYTEST] failed reading prior report {}", reportPath, e);
            return null;
        }
    }

    private static KpiEvaluation evaluateKpis(KpiMetrics metrics, boolean success) {
        if (metrics == null) {
            return new KpiEvaluation(false, 1, "missing-metrics");
        }
        List<String> checks = new ArrayList<>();
        int fails = 0;

        boolean goalPass = success;
        checks.add("goal_outcome=" + passFail(goalPass));
        if (!goalPass) {
            fails++;
        }

        boolean pathPass = metrics.pathEfficiency() >= KPI_MIN_PATH_EFFICIENCY;
        checks.add("path_eff=" + passFail(pathPass) + "(" + fmt(metrics.pathEfficiency(), 4) + ">=" + fmt(KPI_MIN_PATH_EFFICIENCY, 2) + ")");
        if (!pathPass) {
            fails++;
        }

        boolean progressPass = metrics.progressPerMinute() >= KPI_MIN_PROGRESS_EVENTS_PER_MIN;
        checks.add("progress_rate=" + passFail(progressPass) + "(" + fmt(metrics.progressPerMinute(), 2) + ">=" + fmt(KPI_MIN_PROGRESS_EVENTS_PER_MIN, 2) + ")");
        if (!progressPass) {
            fails++;
        }

        boolean searchPass = metrics.searchPerMinute() <= KPI_MAX_SEARCHING_TICKS_PER_MIN;
        checks.add("search_rate=" + passFail(searchPass) + "(" + fmt(metrics.searchPerMinute(), 2) + "<=" + fmt(KPI_MAX_SEARCHING_TICKS_PER_MIN, 2) + ")");
        if (!searchPass) {
            fails++;
        }

        boolean noProgressPass = metrics.maxNoProgressTicks() <= KPI_MAX_NO_PROGRESS_TICKS;
        checks.add("max_no_progress=" + passFail(noProgressPass) + "(" + metrics.maxNoProgressTicks() + "<=" + KPI_MAX_NO_PROGRESS_TICKS + ")");
        if (!noProgressPass) {
            fails++;
        }

        boolean busyPass = metrics.busyRatio() >= KPI_MIN_BUSY_RATIO;
        checks.add("busy_ratio=" + passFail(busyPass) + "(" + fmt(metrics.busyRatio(), 4) + ">=" + fmt(KPI_MIN_BUSY_RATIO, 2) + ")");
        if (!busyPass) {
            fails++;
        }

        return new KpiEvaluation(fails == 0, fails, String.join(" | ", checks));
    }

    private static KpiComparison compareKpis(KpiMetrics current, KpiMetrics previous) {
        if (current == null || previous == null) {
            return new KpiComparison(false, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        return new KpiComparison(
            true,
            current.pathEfficiency() - previous.pathEfficiency(),
            current.progressPerMinute() - previous.progressPerMinute(),
            current.searchPerMinute() - previous.searchPerMinute(),
            current.maxNoProgressTicks() - previous.maxNoProgressTicks(),
            current.busyRatio() - previous.busyRatio()
        );
    }

    private static String passFail(boolean pass) {
        return pass ? "PASS" : "FAIL";
    }

    private static String fmt(double value, int precision) {
        return String.format(Locale.ROOT, "%." + precision + "f", value);
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double distanceSqr(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        return Math.sqrt(distanceSqr(x1, y1, z1, x2, y2, z2));
    }

    private record AutoPlaytestSpec(String name, StevePersona persona, String scenario) {
    }

    private record ReportCandidate(Path path, long tick) {
    }

    private record KpiMetrics(
        long progressEvents,
        long inventoryChangeEvents,
        long busyTicks,
        long idleTicks,
        long searchingTicks,
        long debugStatusChangeEvents,
        long maxNoProgressTicks,
        double pathDistance,
        double netDisplacement,
        double pathEfficiency,
        double avgSpeed,
        double progressPerMinute,
        double searchPerMinute,
        double busyRatio,
        double idleRatio,
        long recoveryAttempts
    ) {
        private static KpiMetrics fromSession(Session session, long elapsedSeconds, double x, double y, double z) {
            double netDisplacement = distance(session.startX(), session.startY(), session.startZ(), x, y, z);
            double pathDistance = session.cumulativeDistanceBlocks();
            double pathEfficiency = pathDistance <= 1.0e-9 ? 0.0 : (netDisplacement / pathDistance);
            double elapsedMinutes = elapsedSeconds <= 0 ? 0.0 : (elapsedSeconds / 60.0);
            double progressPerMinute = elapsedMinutes <= 1.0e-9 ? 0.0 : (session.progressEvents() / elapsedMinutes);
            double searchPerMinute = elapsedMinutes <= 1.0e-9 ? 0.0 : (session.searchingTicks() / elapsedMinutes);
            double busyRatio = elapsedSeconds <= 0 ? 0.0 : (session.busyTicks() / (double) (elapsedSeconds * 20L));
            double idleRatio = elapsedSeconds <= 0 ? 0.0 : (session.idleTicks() / (double) (elapsedSeconds * 20L));
            double avgSpeed = elapsedSeconds <= 0 ? 0.0 : (pathDistance / elapsedSeconds);
            return new KpiMetrics(
                session.progressEvents(),
                session.inventoryChangeEvents(),
                session.busyTicks(),
                session.idleTicks(),
                session.searchingTicks(),
                session.debugStatusChangeEvents(),
                session.maxNoProgressTicks(),
                pathDistance,
                netDisplacement,
                pathEfficiency,
                avgSpeed,
                progressPerMinute,
                searchPerMinute,
                busyRatio,
                idleRatio,
                session.recoveryAttempts()
            );
        }
    }

    private record KpiEvaluation(boolean overallPass, int failCount, String details) {
    }

    private record KpiComparison(
        boolean previousAvailable,
        double pathEfficiencyDelta,
        double progressPerMinDelta,
        double searchPerMinDelta,
        double maxNoProgressTicksDelta,
        double busyRatioDelta
    ) {
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
        double startX,
        double startY,
        double startZ,
        double lastX,
        double lastY,
        double lastZ,
        String lastInventorySummary,
        String lastDebugStatus,
        boolean lastBusy,
        long progressEvents,
        long inventoryChangeEvents,
        long busyTicks,
        long idleTicks,
        long searchingTicks,
        long debugStatusChangeEvents,
        long maxNoProgressTicks,
        double cumulativeDistanceBlocks,
        int recoveryAttempts
    ) {
        private Session withTickSample(
            long tick,
            double x,
            double y,
            double z,
            String inventorySummary,
            String debugStatus,
            boolean busy,
            boolean inventoryChanged,
            boolean searching,
            double movedSqr,
            boolean progressed
        ) {
            long nextProgressEvents = progressEvents + (progressed ? 1 : 0);
            long nextInventoryChanges = inventoryChangeEvents + (inventoryChanged ? 1 : 0);
            long nextBusyTicks = busyTicks + (busy ? 1 : 0);
            long nextIdleTicks = idleTicks + (busy ? 0 : 1);
            long nextSearchingTicks = searchingTicks + (searching ? 1 : 0);

            boolean debugChanged = false;
            if (debugStatus != null) {
                String prev = lastDebugStatus == null ? "" : lastDebugStatus;
                debugChanged = !debugStatus.equals(prev);
            }
            long nextDebugChanges = debugStatusChangeEvents + (debugChanged ? 1 : 0);
            long noProgressTicks = progressed ? 0 : (tick - lastProgressTick);
            long nextMaxNoProgressTicks = Math.max(maxNoProgressTicks, noProgressTicks);
            // Accumulate actual movement every tick; progress status may change due inventory/debug events.
            double movedBlocks = Math.sqrt(Math.max(0.0, movedSqr));
            double nextCumulativeDistance = cumulativeDistanceBlocks + movedBlocks;
            long nextLastProgressTick = progressed ? tick : lastProgressTick;

            return new Session(
                steveUuid,
                steveName,
                requesterUuid,
                requesterName,
                scenario,
                startTick,
                nextLastProgressTick,
                timeoutTicks,
                startX,
                startY,
                startZ,
                x,
                y,
                z,
                inventorySummary,
                debugStatus,
                busy,
                nextProgressEvents,
                nextInventoryChanges,
                nextBusyTicks,
                nextIdleTicks,
                nextSearchingTicks,
                nextDebugChanges,
                nextMaxNoProgressTicks,
                nextCumulativeDistance,
                recoveryAttempts
            );
        }
    }
}
