package com.steve.ai.action.search;

public final class LocalReevaluationPolicy {
    private final int cooldownTicks;
    private final int staleTargetTicks;
    private final int noProgressTicks;
    private final double minDistanceGainSqr;
    private final double hiddenTargetDistanceGainSqr;

    public LocalReevaluationPolicy(
        int cooldownTicks,
        int staleTargetTicks,
        int noProgressTicks,
        double minDistanceGainSqr,
        double hiddenTargetDistanceGainSqr
    ) {
        this.cooldownTicks = Math.max(0, cooldownTicks);
        this.staleTargetTicks = Math.max(1, staleTargetTicks);
        this.noProgressTicks = Math.max(1, noProgressTicks);
        this.minDistanceGainSqr = Math.max(0.0, minDistanceGainSqr);
        this.hiddenTargetDistanceGainSqr = Math.max(this.minDistanceGainSqr, hiddenTargetDistanceGainSqr);
    }

    public Decision evaluate(Context context) {
        if (context == null || !context.hasCurrentTarget() || !context.hasCandidate()) {
            return Decision.keep();
        }
        if (context.currentTargetDistanceSqr() <= 0.0 || context.candidateDistanceSqr() <= 0.0) {
            return Decision.keep();
        }
        if (context.nowTick() - context.lastSwitchTick() < cooldownTicks) {
            return Decision.keep();
        }
        if (!context.currentTargetReachable()) {
            return Decision.switchTo(
                StuckReason.UNREACHABLE_TARGET,
                "candidate replaces unreachable current target"
            );
        }
        if (context.targetStallTicks() >= staleTargetTicks
            && context.candidateDistanceSqr() + minDistanceGainSqr < context.currentTargetDistanceSqr()) {
            return Decision.switchTo(
                StuckReason.STALE_TARGET,
                "candidate improves on stale current target"
            );
        }
        if (context.movementStuckTicks() >= noProgressTicks
            && context.candidateDistanceSqr() + minDistanceGainSqr < context.currentTargetDistanceSqr()) {
            return Decision.switchTo(
                StuckReason.NO_PROGRESS_WHILE_MOVING,
                "candidate improves on current target during movement stall"
            );
        }
        if (!context.currentTargetVisible()
            && context.candidateDistanceSqr() + hiddenTargetDistanceGainSqr < context.currentTargetDistanceSqr()) {
            return Decision.switchTo(
                StuckReason.SEARCH_LOOP,
                "candidate visible while current target is stale/out of view"
            );
        }
        return Decision.keep();
    }

    public record Context(
        boolean hasCurrentTarget,
        boolean hasCandidate,
        boolean currentTargetVisible,
        boolean currentTargetReachable,
        double currentTargetDistanceSqr,
        double candidateDistanceSqr,
        int targetStallTicks,
        int movementStuckTicks,
        long nowTick,
        long lastSwitchTick
    ) {
    }

    public record Decision(boolean shouldSwitch, StuckReason reason, String explanation) {
        public static Decision keep() {
            return new Decision(false, StuckReason.NONE, "");
        }

        public static Decision switchTo(StuckReason reason, String explanation) {
            return new Decision(true, reason == null ? StuckReason.NONE : reason, explanation == null ? "" : explanation);
        }
    }
}
