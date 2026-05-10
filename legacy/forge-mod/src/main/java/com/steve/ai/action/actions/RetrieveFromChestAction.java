package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.gather.ResourceTargetResolver;
import com.steve.ai.resource.KnownResourceManager;
import com.steve.ai.resource.SourceRiskManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RetrieveFromChestAction extends BaseAction {
    private static final int MAX_NO_PROGRESS_TICKS = 80;

    private String itemId;
    private int quantity;
    private int radius;
    private int acquired;
    private int noProgressTicks;
    private boolean fallbackToGather;

    public RetrieveFromChestAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String rawItem = task.getStringParameter("item");
        quantity = Math.max(1, task.getIntParameter("quantity", 1));
        radius = Math.max(1, task.getIntParameter("radius", KnownResourceManager.configuredChestRadius()));
        fallbackToGather = toBoolean(task.getParameter("fallback_to_gather"));
        acquired = 0;
        noProgressTicks = 0;

        if (rawItem == null || rawItem.isBlank()) {
            SteveMod.LOGGER.warn("[CHEST] Steve '{}' missing retrieval item parameter", steve.getSteveName());
            result = ActionResult.failure("Missing chest retrieval item");
            return;
        }
        itemId = normalizeItemId(rawItem);
        SteveMod.LOGGER.info(
            "[CHEST] Steve '{}' start retrieve item={} quantity={} radius={} fallbackToGather={}",
            steve.getSteveName(),
            itemId,
            quantity,
            radius,
            fallbackToGather
        );
    }

    @Override
    protected void onTick() {
        if (itemId == null) {
            SteveMod.LOGGER.warn("[CHEST] Steve '{}' invalid retrieval item during tick", steve.getSteveName());
            result = ActionResult.failure("Invalid chest retrieval item");
            return;
        }
        if (acquired >= quantity) {
            result = ActionResult.success("Retrieved " + acquired + " " + pathOf(itemId) + " from known chests");
            return;
        }

        int remaining = quantity - acquired;
        SteveEntity.ChestAcquireAttempt attempt = steve.tryAcquireItemFromKnownChests(
            itemId,
            remaining,
            radius,
            SourceRiskManager.Urgency.NORMAL
        );
        int moved = Math.max(0, attempt.acquiredCount());
        acquired += moved;
        if (moved > 0) {
            SteveMod.LOGGER.info(
                "[CHEST] Steve '{}' moved {} {} from chest (progress={}/{})",
                steve.getSteveName(),
                moved,
                pathOf(itemId),
                acquired,
                quantity
            );
        }

        if (acquired >= quantity) {
            result = ActionResult.success("Retrieved " + acquired + " " + pathOf(itemId) + " from known chests");
            return;
        }
        if (attempt.moving()) {
            SteveMod.LOGGER.info(
                "[CHEST] Steve '{}' moving to known chest for {} (progress={}/{})",
                steve.getSteveName(),
                pathOf(itemId),
                acquired,
                quantity
            );
            noProgressTicks = 0;
            return;
        }
        if (moved > 0) {
            noProgressTicks = 0;
            return;
        }

        noProgressTicks++;
        if (noProgressTicks == 1 || noProgressTicks % 20 == 0) {
            SteveMod.LOGGER.warn(
                "[CHEST] Steve '{}' no progress retrieving {} (ticksWithoutProgress={}, progress={}/{}, hadKnownSources={})",
                steve.getSteveName(),
                pathOf(itemId),
                noProgressTicks,
                acquired,
                quantity,
                attempt.hadKnownSources()
            );
        }
        if (!attempt.hadKnownSources() || noProgressTicks >= MAX_NO_PROGRESS_TICKS) {
            handleUnavailable();
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Retrieve " + quantity + " " + itemId + " from known chests";
    }

    private void handleUnavailable() {
        int missing = Math.max(0, quantity - acquired);
        if (missing <= 0) {
            result = ActionResult.success("Retrieved " + acquired + " " + pathOf(itemId) + " from known chests");
            return;
        }
        if (!fallbackToGather) {
            SteveMod.LOGGER.warn(
                "[CHEST] Steve '{}' unavailable item={} progress={}/{} fallback disabled",
                steve.getSteveName(),
                pathOf(itemId),
                acquired,
                quantity
            );
            result = ActionResult.failure("Known chests unavailable for " + pathOf(itemId) + " (" + acquired + "/" + quantity + ")");
            return;
        }

        List<ResourceTargetResolver.Candidate> candidates = ResourceTargetResolver.gatherCandidateEntriesForItemId(itemId);
        if (candidates.isEmpty()) {
            SteveMod.LOGGER.warn(
                "[CHEST] Steve '{}' no gather fallback candidates for {}",
                steve.getSteveName(),
                pathOf(itemId)
            );
            result = ActionResult.failure("Known chests unavailable and no gather fallback for " + pathOf(itemId));
            return;
        }

        ResourceTargetResolver.Candidate preferred = ResourceTargetResolver.chooseBestVisibleCandidate(steve, candidates);
        String primary = preferred != null ? pathOf(preferred.blockId()) : pathOf(candidates.get(0).blockId());
        if (primary == null || primary.isBlank()) {
            SteveMod.LOGGER.warn(
                "[CHEST] Steve '{}' invalid gather fallback candidate for {}",
                steve.getSteveName(),
                pathOf(itemId)
            );
            result = ActionResult.failure("Known chests unavailable and invalid gather fallback for " + pathOf(itemId));
            return;
        }

        List<String> candidateIds = new ArrayList<>();
        for (ResourceTargetResolver.Candidate candidate : candidates) {
            candidateIds.add(candidate.blockId());
        }
        List<String> alternatives = new ArrayList<>(ResourceTargetResolver.toPathList(candidateIds));
        alternatives.remove(primary);
        steve.getActionExecutor().enqueueTask(new Task("gather", Map.of(
            "resource", primary,
            "quantity", missing,
            "alternatives", alternatives
        )));
        SteveMod.LOGGER.info(
            "[CHEST] Steve '{}' queued gather fallback item={} resource={} missing={} alternatives={}",
            steve.getSteveName(),
            pathOf(itemId),
            primary,
            missing,
            alternatives
        );

        result = ActionResult.success(
            "Known chests had " + acquired + "/" + quantity + " " + pathOf(itemId) + "; queued gather fallback for " + missing
        );
    }

    private String normalizeItemId(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private String pathOf(String namespacedId) {
        int idx = namespacedId.indexOf(':');
        if (idx < 0 || idx >= namespacedId.length() - 1) {
            return namespacedId;
        }
        return namespacedId.substring(idx + 1);
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return false;
    }
}
