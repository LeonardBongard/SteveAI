# Execution Board

## Program Rules
1. One wave active at a time.
2. Every wave must have:
- Acceptance tests
- Regression tests
- Demo command scripts
3. No new action merges without contract validation.
4. No behavior merge without safety evaluator integration review.
5. Every wave must declare a single Sprint Goal tied to the Product Goal in `docs/SCRUM_OPERATING_MODEL.md`.
6. Bug fixes must clear the anti-local-solution gate before implementation.

## Scrum Control Loop
Reference:
- `docs/SCRUM_OPERATING_MODEL.md`
- `docs/PRODUCT_BACKLOG.md`
- `docs/sprints/README.md`

Required loop for every active wave:
1. Sprint Planning:
- define Product Goal link, Sprint Goal, selected backlog items, acceptance, regressions.
2. Execution:
- implement only the minimum reusable subsystem changes needed for the Sprint Goal.
3. Review:
- run automated checks and manual in-game validation for the increment.
4. Retrospective:
- record whether we improved a global capability or only patched a local symptom.

Sprint archive rule:
1. Every sprint must be recorded in `docs/sprints/`.
2. The currently active sprint must be marked in `docs/sprints/README.md`.
3. Meaningful playtest/coding checkpoints should use `docs/sprints/SPRINT_CHECKIN_TEMPLATE.md`.

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
6. Increment strengthens a reusable subsystem, not only a single scenario.
7. Sprint Review + Retrospective notes are captured in the implementation discussion or docs update.

Testing note:
- Automated checks are theoretical/code-level validation.
- Real world-behavior validation is always manual in-game.
- Pipeline policy: `./scripts/test_mod_pipeline.sh` must fail jar install if required regression tests are missing or failing.
- Standard in-game checklists:
  - `docs/INGAME_VALIDATION_FARM_FEED.md`
  - `docs/INGAME_VALIDATION_NEW_FEATURES.md`
- Default behavior regression includes:
  - `craft iron pickaxe` end-to-end scenario (`INGAME_VALIDATION_NEW_FEATURES.md`, Scenario 20)
  - Standard command:
    - `/steve test iron_pickaxe Steve 420`
  - Behavior scheduler sanity:
    - `/steve behavior Steve list`
    - Must show critical/background passive modules in `ON` state unless explicitly disabled for a test.

## Human Assistance Checkpoints
1. Domain balancing defaults.
2. Canonical data source approval (recipes, food values, animal preferences).
3. Behavior preference decisions (aggressive vs safe).
4. Safety threshold tuning approval for release.

## Anti-Drift Questions
Ask these before implementation when a bug appears:
1. What Product Goal capability is actually missing?
2. Which reusable subsystem should absorb this fix?
3. Would this solution still work for another biome/material/entity/version?
4. What acceptance and regression checks prove this is global, not local?
