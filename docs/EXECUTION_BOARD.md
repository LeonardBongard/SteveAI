# Execution Board

## Program Rules
1. One wave active at a time.
2. Every wave must have:
- Acceptance tests
- Regression tests
- Demo command scripts
3. No new action merges without contract validation.
4. No behavior merge without safety evaluator integration review.

## Backlog by Domain
1. Mining:
- Stabilize ongoing mining work from `docs/MINING_PLAN.md`.
- Normalize with new action contracts.

2. Crafting:
- Add recipe decomposition and material dependency chain.

3. Farming:
- Add farm lifecycle actions and inventory rules.

4. Swimming:
- Add water path segments and stuck recovery.

5. Hunger/Eating:
- Add need thresholds, food selection, and consume action.

6. Animal feeding:
- Add species-food mapping and feed loop.

7. Panic:
- Deferred until explicitly re-enabled (see decision Q11 = no).

## Definition of Done (Per Wave)
1. Automated regression checks pass (build/tests/data coverage scripts).
2. In-game scripted repro passes on at least one fresh world.
3. Failure states are explicit and user-visible.
4. No infinite tick loops in logs.
5. Scenario suite passes for impacted domains (`docs/EVAL_SCENARIOS.md`).

Testing note:
- Automated checks are theoretical/code-level validation.
- Real world-behavior validation is always manual in-game.
- Standard in-game checklists:
  - `docs/INGAME_VALIDATION_FARM_FEED.md`
  - `docs/INGAME_VALIDATION_NEW_FEATURES.md`
- Default behavior regression includes:
  - `craft iron pickaxe` end-to-end scenario (`INGAME_VALIDATION_NEW_FEATURES.md`, Scenario 20)
  - Standard command:
    - `/steve test iron_pickaxe Steve 420`

## Human Assistance Checkpoints
1. Domain balancing defaults.
2. Canonical data source approval (recipes, food values, animal preferences).
3. Behavior preference decisions (aggressive vs safe).
4. Safety threshold tuning approval for release.
