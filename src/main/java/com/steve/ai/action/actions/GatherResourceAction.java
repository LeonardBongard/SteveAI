package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.gather.ResourceTargetResolver;
import com.steve.ai.memory.VisibleBlockEntry;

import java.util.ArrayList;
import java.util.List;

public class GatherResourceAction extends BaseAction {
    private String resourceType;
    private int quantity;

    public GatherResourceAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        resourceType = task.getStringParameter("resource");
        quantity = positiveOrDefault(task.getIntParameter("quantity", -1), task.getIntParameter("count", 1));
        
        if (resourceType == null || resourceType.isBlank()) {
            result = ActionResult.failure("Missing resource type");
            return;
        }

        List<String> alternatives = getAlternatives();
        List<String> resolvedCandidates = ResourceTargetResolver.toPathList(
            ResourceTargetResolver.gatherCandidatesForItemId(resourceType)
        );
        List<String> baseCandidates = resolvedCandidates.isEmpty()
            ? java.util.List.of(resourceType)
            : resolvedCandidates;
        List<String> candidates = new ArrayList<>(baseCandidates);
        if (alternatives != null && !alternatives.isEmpty()) {
            candidates = ResourceTargetResolver.mergeCandidates(candidates.get(0), alternatives);
            for (int i = 1; i < baseCandidates.size(); i++) {
                String next = baseCandidates.get(i);
                if (!candidates.contains(next)) {
                    candidates.add(next);
                }
            }
        }
        SelectionDecision decision = chooseBestCandidate(candidates);
        String selected = decision.candidate();
        if (selected == null || selected.isBlank()) {
            selected = resourceType;
        }
        SteveMod.LOGGER.info(
            "Steve '{}' [planner-tier={}] gather candidate '{}' for request '{}' (reason: {})",
            steve.getSteveName(),
            decision.tier(),
            selected,
            resourceType,
            decision.reason()
        );

        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("block", selected);
        params.put("quantity", quantity);
        String selectedPath = pathOf(selected);
        List<String> mineAlternatives = new ArrayList<>(candidates);
        mineAlternatives.removeIf(c -> pathOf(c).equalsIgnoreCase(selectedPath));
        if (!mineAlternatives.isEmpty()) {
            params.put("alternatives", mineAlternatives);
        }
        steve.getActionExecutor().enqueueTask(new com.steve.ai.action.Task("mine", params));
        result = ActionResult.success("Gathering " + quantity + " " + selected);
    }

    @Override
    protected void onTick() {
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Gather " + quantity + " " + resourceType;
    }

    private List<String> getAlternatives() {
        Object raw = task.getParameter("alternatives");
        List<String> alternatives = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry == null) {
                    continue;
                }
                String val = entry.toString().trim();
                if (!val.isEmpty()) {
                    alternatives.add(val);
                }
            }
        }
        return alternatives;
    }

    private SelectionDecision chooseBestCandidate(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new SelectionDecision("fallback", null, "no candidates");
        }

        String best = null;
        float bestDistance = Float.MAX_VALUE;
        for (VisibleBlockEntry entry : steve.getMemory().getVisibleBlocks()) {
            String path = pathOf(entry.blockId());
            for (String candidate : candidates) {
                if (path.equalsIgnoreCase(pathOf(candidate)) && entry.distance() < bestDistance) {
                    bestDistance = entry.distance();
                    best = path;
                }
            }
        }

        if (best != null) {
            return new SelectionDecision("working", best, "nearest visible distance=" + bestDistance);
        }
        return new SelectionDecision("fallback", candidates.get(0), "default first candidate");
    }

    private String pathOf(String value) {
        int idx = value.indexOf(':');
        return idx >= 0 ? value.substring(idx + 1) : value;
    }

    private int positiveOrDefault(int preferred, int fallback) {
        if (preferred > 0) {
            return preferred;
        }
        return Math.max(1, fallback);
    }

    private record SelectionDecision(String tier, String candidate, String reason) {}
}
