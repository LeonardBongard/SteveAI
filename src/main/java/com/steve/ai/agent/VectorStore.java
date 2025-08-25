package com.steve.ai.agent;

import java.util.*;

public class VectorStore {
    private final Map<String, EmbeddingEntry> store;
    private final int dimensions;
    
    public VectorStore(int dimensions) {
        this.dimensions = dimensions;
        this.store = new HashMap<>();
    }
    
    public void addText(String text, Map<String, Object> metadata) {
        String id = UUID.randomUUID().toString();
        float[] embedding = generateEmbedding(text);
        store.put(id, new EmbeddingEntry(id, text, embedding, metadata));
    }
    
    public List<EmbeddingEntry> similaritySearch(String query, int k) {
        float[] queryEmbedding = generateEmbedding(query);
        
        return store.values().stream()
            .sorted((a, b) -> Float.compare(
                cosineSimilarity(b.embedding, queryEmbedding),
                cosineSimilarity(a.embedding, queryEmbedding)
            ))
            .limit(k)
            .toList();
    }
    
    private float[] generateEmbedding(String text) {
        float[] embedding = new float[dimensions];
        Random random = new Random(text.hashCode());
        
        for (int i = 0; i < dimensions; i++) {
            embedding[i] = random.nextFloat();
        }
        
        return normalize(embedding);
    }
    
    private float[] normalize(float[] vector) {
        float magnitude = 0;
        for (float v : vector) {
            magnitude += v * v;
        }
        magnitude = (float) Math.sqrt(magnitude);
        
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / magnitude;
        }
        return normalized;
    }
    
    private float cosineSimilarity(float[] a, float[] b) {
        float dotProduct = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
        }
        return dotProduct;
    }
    
    public static class EmbeddingEntry {
        public final String id;
        public final String text;
        public final float[] embedding;
        public final Map<String, Object> metadata;
        
        public EmbeddingEntry(String id, String text, float[] embedding, Map<String, Object> metadata) {
            this.id = id;
            this.text = text;
            this.embedding = embedding;
            this.metadata = metadata;
        }
    }
}
