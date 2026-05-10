package com.steve.ai.llm.async;

final class AsyncClientParamUtil {
    private AsyncClientParamUtil() {
    }

    static String stringParam(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String parsed = raw.toString().trim();
        return parsed.isEmpty() ? fallback : parsed;
    }

    static int intParam(Object raw, int fallback) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    static double doubleParam(Object raw, double fallback) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
