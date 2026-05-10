# Safety Evaluator Manager Plan

## Objective
Introduce `SafetyEvaluatorManager` as a modular, shared decision service that continuously evaluates whether Steve should continue, retreat, eat/heal, or replan.

## Why This Exists
1. Avoid duplicated safety logic in each action.
2. Standardize interruption behavior across mining/crafting/farming/combat/navigation.
3. Provide one place to tune risk posture later.

## MVP Interface
```java
public interface SafetyEvaluatorManager {
    SafetySnapshot evaluate(SteveEntity steve, ActionContext ctx);
    boolean isSafe(SafetySnapshot snapshot);
    SafetyDecision recommend(SafetySnapshot snapshot, String currentActionType);
}
```

Supporting types:
1. `SafetyState`: `SAFE`, `CAUTION`, `DANGER`, `CRITICAL`
2. `SafetyDecision`: `CONTINUE`, `RETREAT`, `EAT`, `HEAL`, `REPLAN`, `ABORT`
3. `SafetySnapshot`: state + score + reasons + panic placeholder

## Hard Behavioral Rules
1. Combat command while unsafe:
- Retreat first.
- Stabilize (eat/heal).
- Re-evaluate safety before re-engaging.

2. Water semantics:
- Normal river crossing is considered safe.
- Treat water as unsafe only with explicit danger signals.

3. Action interruption:
- Safety decisions can interrupt any long-running action.
- Interrupted action must be resumable when safe.

## Integration Points
1. `ActionExecutor.tick()`:
- Evaluate safety before ticking current action.
- If unsafe, inject or switch to safety remediation tasks.

2. `BaseAction` subclasses:
- Query safety snapshot at start and periodically during tick.
- Return structured `ActionResult` reason on safety-driven pause/fail.

3. `NeedSystem`:
- Consumes safety snapshot for hunger decisions.

4. Debug/observability:
- Add `SafetyState`, `safetyScore`, and top reasons in debug status/logs.

## Rollout Sequence
1. Phase A: Data model + interface + no-op default implementation.
2. Phase B: Basic evaluator (health/hunger/hostiles/lava/fall/drowning/stuck).
3. Phase C: Executor integration and interruption/resume flow.
4. Phase D: Domain action hookups and regression tests.

## Out of Scope in This Plan
1. Panic behavior changes (placeholders only).
2. Advanced tactical combat AI.
3. Full biome-specific hazard intelligence.

