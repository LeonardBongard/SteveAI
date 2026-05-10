package com.steve.ai.gather;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.VisibleBlockEntry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public final class ResourceTargetResolver {
    public enum Confidence {
        HIGH(3),
        MEDIUM(2),
        LOW(1);

        private final int rank;

        Confidence(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }

        public static Confidence fromString(String raw) {
            if (raw == null) {
                return MEDIUM;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "high" -> HIGH;
                case "low" -> LOW;
                default -> MEDIUM;
            };
        }
    }

    public record Candidate(String blockId, Confidence confidence) {}

    private static final List<String> WOOD_SOURCE_BLOCKS = List.of(
        "minecraft:oak_log",
        "minecraft:spruce_log",
        "minecraft:birch_log",
        "minecraft:jungle_log",
        "minecraft:acacia_log",
        "minecraft:dark_oak_log",
        "minecraft:mangrove_log",
        "minecraft:cherry_log",
        "minecraft:bamboo_block",
        "minecraft:crimson_stem",
        "minecraft:warped_stem"
    );
    private static final String SOURCES_RESOURCE_PATH = "/steve/item_sources.csv";
    private static final String BLOCK_TRANSITIONS_RESOURCE_PATH = "/steve/block_transitions.csv";
    private static final String BLOCK_TOOL_REQUIREMENTS_RESOURCE_PATH = "/steve/block_tool_requirements.csv";
    private static final Map<String, List<Candidate>> CSV_SOURCES = new HashMap<>();
    private static final Set<String> KNOWN_BLOCK_IDS = new HashSet<>();
    private static boolean csvLoaded = false;
    private static boolean knownBlocksLoaded = false;

    private ResourceTargetResolver() {
    }

    /**
     * Returns candidate mine targets (block ids) that can satisfy a required item.
     */
    public static List<String> gatherCandidatesForItemId(String itemId) {
        List<Candidate> candidates = gatherCandidateEntriesForItemId(itemId);
        List<String> ids = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            ids.add(c.blockId());
        }
        return ids;
    }

    public static List<Candidate> gatherCandidateEntriesForItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return List.of();
        }
        String normalized = normalizeId(itemId);
        String path = pathOf(normalized);
        List<Candidate> highPriority = new ArrayList<>();

        // Any plank/stick/table can be sourced from common wood families.
        if (path.endsWith("_planks")
            || path.equals("stick")
            || path.equals("crafting_table")) {
            for (String blockId : WOOD_SOURCE_BLOCKS) {
                highPriority.add(new Candidate(blockId, Confidence.HIGH));
            }
            return expandToMineableCandidates(normalized, mergeWithCsvCandidates(normalized, highPriority), 0, new HashSet<>());
        }

        // Generic player phrasing should resolve to all common wood sources.
        if (path.equals("wood")
            || path.equals("woods")
            || path.equals("log")
            || path.equals("logs")
            || path.equals("tree")
            || path.equals("trees")) {
            for (String blockId : WOOD_SOURCE_BLOCKS) {
                highPriority.add(new Candidate(blockId, Confidence.HIGH));
            }
            return expandToMineableCandidates(normalized, mergeWithCsvCandidates(normalized, highPriority), 0, new HashSet<>());
        }

        // Specific wood/log/stem requests should still accept all wood families to avoid stalls in non-oak biomes.
        if (path.endsWith("_log")
            || path.endsWith("_wood")
            || path.endsWith("_stem")
            || path.endsWith("_hyphae")
            || path.equals("bamboo_block")) {
            for (String blockId : WOOD_SOURCE_BLOCKS) {
                Confidence confidence = blockId.equals(normalized) ? Confidence.HIGH : Confidence.MEDIUM;
                highPriority.add(new Candidate(blockId, confidence));
            }
            return expandToMineableCandidates(normalized, mergeWithCsvCandidates(normalized, highPriority), 0, new HashSet<>());
        }

        // Coal item can be sourced from both ore variants.
        if (path.equals("coal")) {
            highPriority.add(new Candidate("minecraft:coal_ore", Confidence.HIGH));
            highPriority.add(new Candidate("minecraft:deepslate_coal_ore", Confidence.HIGH));
            return expandToMineableCandidates(normalized, mergeWithCsvCandidates(normalized, highPriority), 0, new HashSet<>());
        }

        // Use generated knowledge map first, fallback to identity.
        List<Candidate> merged = expandToMineableCandidates(
            normalized,
            mergeWithCsvCandidates(normalized, highPriority),
            0,
            new HashSet<>()
        );
        if (!merged.isEmpty()) {
            return merged;
        }
        return List.of(new Candidate(normalized, Confidence.MEDIUM));
    }

    public static List<String> toPathList(List<String> blockIds) {
        if (blockIds == null || blockIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String id : blockIds) {
            String path = pathOf(normalizeId(id));
            if (!path.isBlank()) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    public static String chooseBestVisiblePath(SteveEntity steve, List<String> candidateBlockIds) {
        List<Candidate> candidates = new ArrayList<>();
        if (candidateBlockIds != null) {
            for (String id : candidateBlockIds) {
                candidates.add(new Candidate(normalizeId(id), Confidence.MEDIUM));
            }
        }
        Candidate best = chooseBestVisibleCandidate(steve, candidates);
        return best == null ? null : pathOf(normalizeId(best.blockId()));
    }

    public static Candidate chooseBestVisibleCandidate(SteveEntity steve, List<Candidate> candidates) {
        if (steve == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        Map<String, Confidence> candidateConfidence = new HashMap<>();
        for (Candidate candidate : candidates) {
            String normalized = normalizeId(candidate.blockId());
            Confidence existing = candidateConfidence.get(normalized);
            if (existing == null || candidate.confidence().rank() > existing.rank()) {
                candidateConfidence.put(normalized, candidate.confidence());
            }
        }

        List<VisibleCandidate> visible = new ArrayList<>();
        for (VisibleBlockEntry entry : steve.getMemory().getVisibleBlocks()) {
            String normalizedBlockId = normalizeId(entry.blockId());
            Confidence confidence = candidateConfidence.get(normalizedBlockId);
            if (confidence == null) {
                continue;
            }
            boolean feasible = steve.canMineBlockNow(normalizedBlockId);
            visible.add(new VisibleCandidate(normalizedBlockId, confidence, feasible, entry.distance()));
        }
        if (visible.isEmpty()) {
            return null;
        }

        // Prefer non-low confidence first. Only use low when no better visible option exists.
        List<VisibleCandidate> nonLow = visible.stream()
            .filter(v -> v.confidence != Confidence.LOW)
            .toList();
        List<VisibleCandidate> pool = new ArrayList<>(nonLow.isEmpty() ? visible : nonLow);

        pool.sort(Comparator
            .comparing((VisibleCandidate v) -> !v.feasible) // feasible first
            .thenComparing((VisibleCandidate v) -> -v.confidence.rank()) // higher confidence first
            .thenComparingDouble(v -> v.distance)); // closer first

        VisibleCandidate picked = pool.get(0);
        return new Candidate(picked.blockId, picked.confidence);
    }

    public static List<String> mergeCandidates(String primaryPath, List<String> alternativePaths) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (primaryPath != null && !primaryPath.isBlank()) {
            merged.add(primaryPath.toLowerCase(Locale.ROOT));
        }
        if (alternativePaths != null) {
            for (String alt : alternativePaths) {
                if (alt != null && !alt.isBlank()) {
                    merged.add(alt.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ArrayList<>(merged);
    }

    private static String normalizeId(String raw) {
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.contains(":")) {
            return trimmed;
        }
        return "minecraft:" + trimmed;
    }

    private static String pathOf(String namespacedId) {
        int idx = namespacedId.indexOf(':');
        if (idx < 0 || idx >= namespacedId.length() - 1) {
            return namespacedId;
        }
        return namespacedId.substring(idx + 1);
    }

    private static List<Candidate> mergeWithCsvCandidates(String targetItemId, List<Candidate> preferred) {
        loadCsvIfNeeded();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Candidate> merged = new ArrayList<>();
        if (preferred != null) {
            for (Candidate candidate : preferred) {
                String id = normalizeId(candidate.blockId());
                if (seen.add(id)) {
                    merged.add(new Candidate(id, candidate.confidence()));
                }
            }
        }
        List<Candidate> csv = CSV_SOURCES.getOrDefault(normalizeId(targetItemId), List.of());
        for (Candidate candidate : csv) {
            String id = normalizeId(candidate.blockId());
            if (seen.add(id)) {
                merged.add(new Candidate(id, candidate.confidence()));
            }
        }
        return merged;
    }

    private static List<Candidate> expandToMineableCandidates(
        String rootTarget,
        List<Candidate> baseCandidates,
        int depth,
        Set<String> seenTargets
    ) {
        if (baseCandidates == null || baseCandidates.isEmpty()) {
            return List.of();
        }
        if (depth > 3) {
            return baseCandidates;
        }
        String normalizedRoot = normalizeId(rootTarget);
        if (!seenTargets.add(normalizedRoot)) {
            return baseCandidates;
        }

        List<Candidate> expanded = new ArrayList<>();
        Map<String, Confidence> best = new HashMap<>();

        for (Candidate candidate : baseCandidates) {
            String sourceId = normalizeId(candidate.blockId());
            if (isBlockId(sourceId)) {
                mergeBest(best, sourceId, candidate.confidence());
                continue;
            }
            List<Candidate> nested = mergeWithCsvCandidates(sourceId, List.of());
            if (nested.isEmpty()) {
                continue;
            }
            List<Candidate> nestedExpanded = expandToMineableCandidates(sourceId, nested, depth + 1, seenTargets);
            for (Candidate n : nestedExpanded) {
                Confidence propagated = lowerConfidence(candidate.confidence(), n.confidence());
                mergeBest(best, normalizeId(n.blockId()), propagated);
            }
        }

        for (Map.Entry<String, Confidence> entry : best.entrySet()) {
            expanded.add(new Candidate(entry.getKey(), entry.getValue()));
        }
        if (!expanded.isEmpty()) {
            return expanded;
        }
        return baseCandidates;
    }

    private static boolean isBlockId(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return false;
        }
        boolean presentInRegistry = false;
        try {
            presentInRegistry = BuiltInRegistries.BLOCK.getOptional(identifier).isPresent();
        } catch (Throwable ignored) {
        }
        if (presentInRegistry) {
            return true;
        }
        loadKnownBlockIdsIfNeeded();
        return KNOWN_BLOCK_IDS.contains(normalizeId(id));
    }

    private static Confidence lowerConfidence(Confidence a, Confidence b) {
        return a.rank() <= b.rank() ? a : b;
    }

    private static void mergeBest(Map<String, Confidence> best, String id, Confidence confidence) {
        Confidence existing = best.get(id);
        if (existing == null || confidence.rank() > existing.rank()) {
            best.put(id, confidence);
        }
    }

    private static synchronized void loadCsvIfNeeded() {
        if (csvLoaded) {
            return;
        }
        csvLoaded = true;
        try (InputStream stream = ResourceTargetResolver.class.getResourceAsStream(SOURCES_RESOURCE_PATH)) {
            if (stream == null) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line = reader.readLine(); // header
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split(",", -1);
                    if (parts.length < 2) {
                        continue;
                    }
                    String target = normalizeId(parts[0]);
                    String source = normalizeId(parts[1]);
                    Confidence confidence = parts.length >= 4
                        ? Confidence.fromString(parts[3])
                        : Confidence.MEDIUM;
                    // Keep only non-trivial source relations for runtime selection.
                    if (target.equals(source)) {
                        continue;
                    }
                    CSV_SOURCES.computeIfAbsent(target, k -> new ArrayList<>()).add(new Candidate(source, confidence));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static synchronized void loadKnownBlockIdsIfNeeded() {
        if (knownBlocksLoaded) {
            return;
        }
        knownBlocksLoaded = true;
        loadBlockIdsFromCsv(BLOCK_TRANSITIONS_RESOURCE_PATH, 0, 1);
        loadBlockIdsFromCsv(BLOCK_TOOL_REQUIREMENTS_RESOURCE_PATH, 0);
        for (String blockId : WOOD_SOURCE_BLOCKS) {
            KNOWN_BLOCK_IDS.add(normalizeId(blockId));
        }
    }

    private static void loadBlockIdsFromCsv(String resourcePath, int... columns) {
        try (InputStream stream = ResourceTargetResolver.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split(",", -1);
                    for (int col : columns) {
                        if (col < 0 || col >= parts.length) {
                            continue;
                        }
                        String raw = parts[col].trim();
                        Identifier identifier = Identifier.tryParse(raw);
                        if (identifier != null) {
                            KNOWN_BLOCK_IDS.add(normalizeId(raw));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static class VisibleCandidate {
        private final String blockId;
        private final Confidence confidence;
        private final boolean feasible;
        private final float distance;

        private VisibleCandidate(String blockId, Confidence confidence, boolean feasible, float distance) {
            this.blockId = blockId;
            this.confidence = confidence;
            this.feasible = feasible;
            this.distance = distance;
        }
    }
}
