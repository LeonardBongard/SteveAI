# Release Notes

## Date
- March 4, 2026

## Scope
This release packages the recent stabilization and modularization work for Steve AI, focused on reliable iron-pickaxe progression, legal crafting/smelting behavior, safety-driven execution, extensibility, and testability.

## Highlights

### 1. Iron-Pickaxe Pipeline Stabilization
- Fixed repeated pathing/mining loops that caused timeout behavior.
- Added stronger enqueue guards for repeated `pathfind` tasks.
- Improved station reachability checks for crafting and smelting, with safer fallback behavior when stations are unreachable.
- Added deterministic fallback mining frontier logic to avoid random tunnel drift.
- Added ore-target block TTL handling to prevent immediate reselection loops.
- Added smelt-first recovery path when `raw_iron + fuel` are already available.

### 2. Crafting and Resource Equivalence
- Added equivalent-item handling so wood/plank family variants can satisfy requirements (not just oak placeholders).
- Improved crafting planner consumption logic to use equivalent inventory sources.
- Reduced stalls where Steve gathered unnecessary extra wood despite valid inventory.

### 3. Legal Minecraft Action Enforcement
- Extended legal-action validation coverage for craft/smelt/task execution paths.
- Strengthened station-aware behavior (crafting table/furnace/stonecutter flow).
- Improved recovery behavior to avoid illegal instant conversions.

### 4. Safety and Behavior Architecture
- Added modular safety evaluator framework:
  - `SafetyEvaluatorManager`
  - `SafetySnapshot`
  - `SafetyState`
  - `SafetyDecision`
  - `PanicLevel` (placeholder levels for future behavior differentiation)
- Integrated safety checks into action execution and recovery decisions.

### 5. New Gameplay Capabilities
- Farming scaffolding and actions (`FarmCropAction`, farming resolver).
- Animal feeding scaffolding and resolver.
- Chest retrieval and remembered resource usage improvements.
- Fuel policy and source-risk management utilities.

### 6. Debugging and Developer UX
- Added debug renderer/settings screen support and runtime settings hooks.
- Expanded logging and diagnostics around planning, mining, pathing, and recovery.
- Added playtest runner flow improvements and reporting.

### 7. Knowledge/Data Pipeline
- Added generated and curated Minecraft knowledge datasets:
  - item sources
  - block transitions
  - block tool requirements
  - crafting recipe dataset
- Added scripts to generate/validate coverage and run regression suites.
- Added stored regression reports and coverage outputs for traceability.

### 8. Tests
- Added regression tests for:
  - crafting planner
  - resource target resolver
  - safety evaluator behavior
  - farming/animal resolver behavior
  - legality checker
  - LLM fallback parser behavior

### 9. Documentation and Process
- Added architecture, roadmap, execution-board, scenarios, modularity rules, mining plan, logging policy, and validation docs.
- Added PR template and planning artifacts for consistent collaboration.

## Commit Map
- `6c3d4b4` Stabilize iron-pickaxe pipeline and crafting equivalence
- `e15aa40` Add architecture, roadmap, and validation documentation
- `d9b45a0` Add data pipeline scripts and Minecraft knowledge datasets
- `fea919c` Implement modular safety, farming, memory, and debug systems
- `27bd412` Add regression tests for planning, safety, and resource resolution

## Validation
- Full pipeline script passes:
  - `./scripts/test_mod_pipeline.sh`
- Jar build and deployment to local mod folder verified during pipeline run.

## Known Notes
- Existing Gradle deprecation warnings are still present (non-blocking for this release).
- Further iteration is expected for cave exploration heuristics and long-run scenario reliability.
