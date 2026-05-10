package com.steve.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ResponseParser {
    
    public static ParsedResponse parseAIResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        try {
            String jsonString = extractJSON(response);
            
            JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();
            
            String reasoning = json.has("reasoning") ? json.get("reasoning").getAsString() : "";
            String plan = json.has("plan") ? json.get("plan").getAsString() : "";
            List<Task> tasks = new ArrayList<>();
            
            if (json.has("tasks") && json.get("tasks").isJsonArray()) {
                JsonArray tasksArray = json.getAsJsonArray("tasks");
                
                for (JsonElement taskElement : tasksArray) {
                    if (taskElement.isJsonObject()) {
                        JsonObject taskObj = taskElement.getAsJsonObject();
                        Task task = parseTask(taskObj);
                        if (task != null) {
                            tasks.add(task);
                        }
                    }
                }
            }
            
            if (!reasoning.isEmpty()) {            }
            
            return new ParsedResponse(reasoning, plan, tasks);
            
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to parse AI response: {}", response, e);
            return null;
        }
    }

    private static String extractJSON(String response) {
        String cleaned = response.trim();
        
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        
        cleaned = cleaned.trim();
        
        // Fix common JSON formatting issues
        cleaned = cleaned.replaceAll("\\n\\s*", " ");
        
        // Fix missing commas between array/object elements (common AI mistake)
        cleaned = cleaned.replaceAll("}\\s+\\{", "},{");
        cleaned = cleaned.replaceAll("}\\s+\\[", "},[");
        cleaned = cleaned.replaceAll("]\\s+\\{", "],{");
        cleaned = cleaned.replaceAll("]\\s+\\[", "],[");
        
        return cleaned;
    }

    private static Task parseTask(JsonObject taskObj) {
        if (!taskObj.has("action") || !taskObj.get("action").isJsonPrimitive()) {
            return null;
        }
        String action = taskObj.get("action").getAsString().trim().toLowerCase(Locale.ROOT);
        if (action.isEmpty()) {
            return null;
        }
        Map<String, Object> parameters = new HashMap<>();
        
        if (taskObj.has("parameters") && taskObj.get("parameters").isJsonObject()) {
            JsonObject paramsObj = taskObj.getAsJsonObject("parameters");
            
            for (String key : paramsObj.keySet()) {
                JsonElement value = paramsObj.get(key);
                
                if (value.isJsonPrimitive()) {
                    if (value.getAsJsonPrimitive().isNumber()) {
                        parameters.put(key, value.getAsNumber());
                    } else if (value.getAsJsonPrimitive().isBoolean()) {
                        parameters.put(key, value.getAsBoolean());
                    } else {
                        parameters.put(key, value.getAsString());
                    }
                } else if (value.isJsonArray()) {
                    List<Object> list = new ArrayList<>();
                    for (JsonElement element : value.getAsJsonArray()) {
                        if (element.isJsonPrimitive()) {
                            if (element.getAsJsonPrimitive().isNumber()) {
                                list.add(element.getAsNumber());
                            } else {
                                list.add(element.getAsString());
                            }
                        }
                    }
                    parameters.put(key, list);
                }
            }
        }
        
        normalizeAliases(action, parameters);

        if (isWorkbenchBuildTask(action, parameters)) {
            Map<String, Object> craftParams = new HashMap<>();
            craftParams.put("item", "crafting_table");
            craftParams.put("quantity", positiveInt(parameters.get("quantity"), 1));
            return new Task("craft", craftParams);
        }

        return new Task(action, parameters);
    }

    private static void normalizeAliases(String action, Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return;
        }
        if ("craft".equals(action)) {
            copyAlias(parameters, "item", "recipe");
            copyAlias(parameters, "item", "output");
            copyAlias(parameters, "item", "target");
        }
        if ("mine".equals(action)) {
            copyAlias(parameters, "block", "resource");
            copyAlias(parameters, "block", "blockType");
        }
        if ("gather".equals(action)) {
            copyAlias(parameters, "resource", "item");
        }
        if (!parameters.containsKey("quantity") && parameters.containsKey("count")) {
            parameters.put("quantity", parameters.get("count"));
        }
    }

    private static void copyAlias(Map<String, Object> parameters, String primary, String alias) {
        if (parameters.containsKey(primary)) {
            return;
        }
        Object aliased = parameters.get(alias);
        if (aliased == null) {
            return;
        }
        String value = aliased.toString().trim();
        if (!value.isEmpty()) {
            parameters.put(primary, value);
        }
    }

    private static boolean isWorkbenchBuildTask(String action, Map<String, Object> parameters) {
        if (!"build".equals(action)) {
            return false;
        }
        Object raw = parameters.get("structure");
        if (raw == null) {
            return false;
        }
        String normalized = raw.toString().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        return normalized.equals("workbench") || normalized.equals("craftingtable") || normalized.equals("craftingbench");
    }

    private static int positiveInt(Object raw, int fallback) {
        if (raw instanceof Number n && n.intValue() > 0) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                int parsed = Integer.parseInt(s.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    public static class ParsedResponse {
        private final String reasoning;
        private final String plan;
        private final List<Task> tasks;

        public ParsedResponse(String reasoning, String plan, List<Task> tasks) {
            this.reasoning = reasoning;
            this.plan = plan;
            this.tasks = tasks;
        }

        public String getReasoning() {
            return reasoning;
        }

        public String getPlan() {
            return plan;
        }

        public List<Task> getTasks() {
            return tasks;
        }
    }
}
