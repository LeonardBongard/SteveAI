package com.steve.ai.mining;

import com.steve.ai.SteveMod;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class BlockTransitionMap {
    private static final String RESOURCE_PATH = "/steve/block_transitions.csv";
    private static final Map<String, String> SOURCE_TO_TARGET = new HashMap<>();
    private static final Map<String, Set<String>> TARGET_TO_SOURCES = new HashMap<>();
    private static boolean loaded = false;

    private BlockTransitionMap() {
    }

    public static synchronized void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;

        try (InputStream stream = BlockTransitionMap.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                SteveMod.LOGGER.warn("Block transitions file not found at {}", RESOURCE_PATH);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split(",", 2);
                    if (parts.length != 2) {
                        continue;
                    }
                    String source = parts[0].trim();
                    String target = parts[1].trim();
                    if (source.isEmpty() || target.isEmpty()) {
                        continue;
                    }
                    SOURCE_TO_TARGET.put(source, target);
                    TARGET_TO_SOURCES.computeIfAbsent(target, ignored -> new HashSet<>()).add(source);
                }
            }
        } catch (Exception ex) {
            SteveMod.LOGGER.warn("Failed to load block transition map", ex);
        }
    }

    public static String getTransitionTarget(String sourceBlockId) {
        loadIfNeeded();
        return SOURCE_TO_TARGET.get(sourceBlockId);
    }

    public static Set<String> getAcceptableSources(String targetBlockId) {
        loadIfNeeded();
        Set<String> sources = TARGET_TO_SOURCES.get(targetBlockId);
        if (sources == null || sources.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(sources);
    }

    public static Set<String> getAcceptableSourcesRecursive(String targetBlockId) {
        loadIfNeeded();
        if (targetBlockId == null || targetBlockId.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        Set<String> frontier = new HashSet<>();
        frontier.add(targetBlockId);
        while (!frontier.isEmpty()) {
            Set<String> next = new HashSet<>();
            for (String target : frontier) {
                Set<String> sources = TARGET_TO_SOURCES.get(target);
                if (sources == null || sources.isEmpty()) {
                    continue;
                }
                for (String source : sources) {
                    if (result.add(source)) {
                        String sourceTarget = SOURCE_TO_TARGET.get(source);
                        if (sourceTarget != null && !result.contains(sourceTarget)) {
                            next.add(sourceTarget);
                        }
                    }
                }
            }
            frontier = next;
        }
        return result.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(result);
    }
}
