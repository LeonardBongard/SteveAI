package com.steve.ai.execution;

/**
 * Placeholder panic scaffolding.
 *
 * Behavior hooks are intentionally no-op for now. This enum exists so panic
 * can be carried through safety snapshots and surfaced in debug/logging.
 */
public enum PanicLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
