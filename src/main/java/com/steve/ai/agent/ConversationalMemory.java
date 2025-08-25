package com.steve.ai.agent;

import java.util.*;

public class ConversationalMemory {
    private final List<ChatMessage> messages;
    private final int maxTokens;
    private final VectorStore vectorStore;
    
    public ConversationalMemory(int maxTokens) {
        this.messages = new ArrayList<>();
        this.maxTokens = maxTokens;
        this.vectorStore = new VectorStore(384);
    }
    
    public void addUserMessage(String content) {
        ChatMessage message = new ChatMessage("user", content, System.currentTimeMillis());
        messages.add(message);
        
        vectorStore.addText(content, Map.of(
            "role", "user",
            "timestamp", message.timestamp
        ));
    }
    
    public void addAssistantMessage(String content) {
        ChatMessage message = new ChatMessage("assistant", content, System.currentTimeMillis());
        messages.add(message);
        
        vectorStore.addText(content, Map.of(
            "role", "assistant",
            "timestamp", message.timestamp
        ));
    }
    
    public List<ChatMessage> getRecentMessages(int count) {
        int size = messages.size();
        int start = Math.max(0, size - count);
        return new ArrayList<>(messages.subList(start, size));
    }
    
    public List<VectorStore.EmbeddingEntry> searchRelevantMemories(String query, int k) {
        return vectorStore.similaritySearch(query, k);
    }
    
    public String formatMessages() {
        StringBuilder formatted = new StringBuilder();
        for (ChatMessage message : messages) {
            formatted.append(message.role)
                    .append(": ")
                    .append(message.content)
                    .append("\n");
        }
        return formatted.toString();
    }
    
    public void clear() {
        messages.clear();
    }
    
    public static class ChatMessage {
        public final String role;
        public final String content;
        public final long timestamp;
        
        public ChatMessage(String role, String content, long timestamp) {
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}
