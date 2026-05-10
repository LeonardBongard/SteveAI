# Implementation Waves

## Wave 0: Contracts, Observability, and Safety Spine
Goal: Prevent feature sprawl and regressions.

Deliverables:
1. Action parameter contracts and validators.
2. Domain log tags (`[MINE]`, `[CRAFT]`, `[FARM]`, `[SWIM]`, `[FOOD]`, `[FEED]`).
3. Tick timeout/retry policy for all long-running actions.
4. Baseline test harness for action lifecycle and replanning.
5. `SafetyEvaluatorManager` interface + snapshot model + no-op implementation.
6. Executor wiring point for safety check prior to action tick.
7. Combat unsafe rule: retreat -> stabilize -> re-evaluate.

Exit criteria:
1. Invalid tasks are rejected before execution.
2. All current actions report deterministic start/tick/finish reason.
3. Safety evaluator is active in runtime flow and logs output.

## Wave 1: Survival Core (Hunger + Eat)
Goal: Steve self-maintains hunger and can acquire/consume food.

Deliverables:
1. Hunger state ingestion and thresholds.
2. Food ranking table (best available edible by context).
3. Food acquisition action chain.
4. Eat action with interrupt-safe behavior.

Exit criteria:
1. Steve does not starve during normal gather/build loops.
2. Hunger-triggered interruption resumes prior task afterward.

## Wave 2: Swimming and Water Pathing
Goal: Remove water deadlocks and support water traversal.

Deliverables:
1. Water-aware navigation mode.
2. Swim action segments with enter/exit handling.
3. Hazard filters (lava/deep water constraints as config).

Exit criteria:
1. Steve crosses simple rivers/ocean edges when required by task.
2. No repeated stuck loops at shoreline.

## Wave 3: Crafting as Reliable Job Chain
Goal: Crafting becomes a first-class pipeline, not single action guesswork.

Deliverables:
1. Recipe resolver with missing-material decomposition.
2. Craft queue planner (dependencies first).
3. Workbench usage and placement flow.

Exit criteria:
1. "Craft X" either completes with output in inventory or fails with explicit missing requirement list.

## Wave 4: Farming Loop
Goal: Autonomous renewable food/material loop.

Deliverables:
1. Farm site selection.
2. Soil prep/plant/harvest/replant.
3. Seed and produce inventory policy.

Exit criteria:
1. Steve can bootstrap and maintain a small wheat loop.
2. Farming can be invoked directly or as hunger mitigation source.

## Wave 5: Animal Feeding
Goal: Correct species-food matching and reliable feeding behavior.

Deliverables:
1. Species preferred-food table.
2. Animal targeting and approach behavior.
3. Feed action and success verification.

Exit criteria:
1. Steve can feed requested nearby species with valid food.
2. Explicit failure reason when no valid food/target exists.

## Deferred Wave: Panic Framework
Status: postponed by product decision (Q11 = no).
When reactivated later:
1. Add panic state model and hooks.
2. Add observability to debug UI + logs (Q12 = C).
3. Keep behavior no-op until explicit enable decision.

## Mandatory Validation
After each wave, run scenarios in:
- `docs/EVAL_SCENARIOS.md`
