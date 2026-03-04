## Summary
- What changed:
- Why this change is needed:

## Behavior Scenarios
- Scenario(s) validated:
- Expected behavior:
- Actual behavior:

## Modularity & Extensibility Checklist (Required)
- [ ] I used reusable abstractions (interfaces/modules), not one-off branching in monolith code.
- [ ] Strategy/policy logic is separated from action execution logic.
- [ ] I avoided single-item assumptions and used resolver/category-driven selection where relevant.
- [ ] Thresholds/ranges/priorities are config/constants, not buried magic numbers.

## Minecraft Legality Checklist (Required)
- [ ] Actions are Minecraft-legal (required tool/station/material/state checked).
- [ ] No illegal instant conversions (for example smelt/craft/state conversions).
- [ ] Missing prerequisites queue legal setup/recovery steps.

## Robustness Checklist (Required)
- [ ] Main task intent is preserved through subtasks.
- [ ] Stale/unreachable targets are handled (reroute, replace target, or fail-fast path).
- [ ] No-progress/loop guard behavior is present where needed.

## Observability Checklist (Required)
- [ ] Added structured logs for key decisions, retries, and failure reasons.
- [ ] Added/updated debug state hooks if behavior is hard to inspect in gameplay.
- [ ] Avoided noisy player-facing debug spam.

## Validation Checklist (Required)
- [ ] `./gradlew -q test`
- [ ] `./scripts/test_mod_pipeline.sh`
- [ ] Updated scenario docs if behavior changed (`docs/EVAL_SCENARIOS.md`).
- [ ] Included playtest notes when in-world behavior changed.

## Risks / Follow-ups
- Known limitations:
- Follow-up tasks:
