# Execution Model and OOP Scaffold

## Current Baseline
Current runtime flow:
1. Command -> `ActionExecutor.processNaturalLanguageCommand(...)`
2. LLM planning -> `TaskPlanner`
3. Parsed tasks -> `Task`
4. Tick execution -> `BaseAction` subclasses
5. Always-on passive scheduler -> lane-based `BehaviorScheduler`

This is a good base and should be extended, not replaced.

## Target OOP Model
Use explicit domain services to keep `BaseAction` focused on execution:
1. `SafetyEvaluatorManager`
- Computes current safety state every tick from local threats and context.
- Exposes `isSafe()`, `panicLevel()`, `recommendedAction()`, `safetyScore()`.
- Becomes a required dependency for all executable actions.

2. `NeedSystem`
- Owns hunger/health-derived needs.
- Emits actionable intents (eat, seek food).

3. `CapabilitySystem`
- Owns capability checks (can swim, has tool, has food, has seeds).
- Central gate before action start.

4. `BehaviorPolicy`
- Maps world context + agent state -> strategy.
- Includes panic policy hooks but empty behavior in this phase.

5. `TaskDecomposer`
- Converts high-level goals into ordered `Task` groups.
- Keeps LLM output normalized to known action contracts.

6. `ActionContracts`
- Typed parameter schema per action.
- Validation before queue insertion.

7. `BehaviorScheduler` (dynamic lanes, modular passive execution)
- Foreground action execution and passive world-learning run in the same tick loop.
- Behaviors are modules (`BehaviorDefinition`) with:
  - `id`, `lane`, `lanePriority`, `priority`
  - `cooldownTicks`, `budgetCost`
  - `shouldRun(context)`, `run(context)`
- Scheduler order:
  1. Sort by lanePriority -> lane -> priority -> id.
  2. Enforce tick budget.
  3. Skip on cooldown.
  4. Isolate failures per behavior (one failure must not stop others).

Current default passive behaviors:
1. `passive_safety_pulse` (critical lane)
2. `passive_visible_scan` (background observe lane)
3. `passive_chest_scan` (background knowledge lane)

## Safety Evaluator Contract
`SafetyEvaluatorManager` inputs:
1. Health, hunger, armor, active effects.
2. Nearby hostiles and line-of-sight.
3. Terrain danger (lava, fall risk, drowning risk).
4. Navigation status (stuck, trapped, blocked).
5. Current action type and interruptibility.

Outputs:
1. `SafetyState`: `SAFE`, `CAUTION`, `DANGER`, `CRITICAL`.
2. `safetyScore`: numeric 0-100.
3. `recommendedAction`: `CONTINUE`, `RETREAT`, `EAT`, `HEAL`, `REPLAN`, `ABORT`.
4. `panicLvl`: placeholder state update only in this phase.

Hard rule requested:
1. If command is combat and state is unsafe: first retreat to safe position, then eat/heal, then re-evaluate engagement.
2. Do not classify normal river traversal as danger by default.
3. Water becomes danger only under explicit risk signals (drowning trap, lava adjacency, similar).

## Minimal New State Model
Add/normalize agent state fields:
1. `hunger` (0..20 or mapped to MC food level)
2. `saturation`
3. `nutritionInventory` (food item summary)
4. `canSwim` / water navigation flags
5. `panicLvl` (placeholder, no behavior yet)

## Proposed Empty Panic Hooks (Implement Later)
1. Enum:
- `CALM`, `UNEASY`, `ALERT`, `PANIC`

2. Interface:
- `PanicBehaviorHandler`
- Method stubs only, no behavior changes yet.

3. Runtime wiring:
- State updates allowed.
- Decision overrides disabled for now.
- Safety manager may update panic state, but panic handlers stay no-op.

## Action Family Coverage
1. Mining:
- Target select -> navigate -> break -> collect -> verify quantity.

2. Crafting:
- Recipe resolve -> material check -> gather missing -> craft -> validate output.

3. Farming:
- Land select -> till -> plant -> wait/grow loop -> harvest -> store/replant.

4. Swimming:
- Water-aware path segments -> enter/exit water transitions -> continue route.

5. Food/Eating:
- Need trigger -> find edible -> acquire -> consume -> re-evaluate hunger.

6. Animal feeding:
- Target species detect -> preferred food lookup -> acquire food -> approach -> feed -> verify state change.

## Human-Only Gates
Require explicit human input before coding the dependent area when:
1. Behavior quality target is ambiguous (for example "good enough farming").
2. Balance decisions affect gameplay feel (aggressive vs conservative risk policy).
3. We need a canonical source for item/animal preference tables.
