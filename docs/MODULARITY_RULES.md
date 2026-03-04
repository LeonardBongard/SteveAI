# Modularity Rules (SteveAI)

These rules are mandatory for all new features and bug fixes.

## 1) Interface-first design
- Put policy/decision behavior behind interfaces when the behavior may change later.
- Keep execution code (Minecraft actions) independent from strategy code.
- Prefer adding a new module over adding more branching to a monolith class.

## 2) No one-off hardcoding
- Avoid single-item/single-block assumptions in planners and resolvers.
- Use category/tag/source resolvers (for example "any log", "any food", "any valid fuel").
- Use config/constants for thresholds, ranges, and priorities.

## 3) Explicit legality checks
- Actions must be Minecraft-legal before execution (tool, station, material, state).
- Never "instant convert" resources when a station/time/fuel is required.
- If illegal/missing prerequisites, enqueue legal prerequisites and retry.

## 4) Task and memory robustness
- Preserve top-level intent through subtasks; do not lose the main goal.
- New memory behavior must be reusable from core planning (not local-only hacks).
- Candidate selection must handle unreachable/stale targets and fail fast on no-progress.

## 5) Observability by default
- New modules must emit structured logs for decisions and failures.
- Add debug state hooks so in-game and log-based diagnosis are possible.
- Do not add noisy user-facing spam; place verbose details in logs/debug channels.

## 6) Verification gate
- Add or update scenario coverage in `docs/EVAL_SCENARIOS.md` for changed behavior.
- Run local validation before merge:
  - `./gradlew -q test`
  - `./scripts/test_mod_pipeline.sh`
- If behavior changed in-world, include a short playtest note in PR.

## 7) Extension rule
- New feature code must be usable by other domains without copy/paste.
- If adding special handling, document the generic abstraction that future features reuse.
