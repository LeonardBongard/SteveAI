## Summary
- What changed:
- Why this change is needed:

## Behavior scenarios
- Scenario(s) validated:
- Expected behavior:
- Actual behavior:

## Modularity & extensibility (required)
- [ ] Used reusable abstractions; no one-off branching in monolith code.
- [ ] Strategy / policy logic separated from action execution.
- [ ] Avoided single-item assumptions; resolver / category-driven selection where relevant.
- [ ] Thresholds, ranges, priorities are config / constants — no buried magic numbers.

## Minecraft legality (required)
- [ ] Actions are Minecraft-legal (required tool / station / material / state checked).
- [ ] No illegal instant conversions (e.g. smelt / craft / state shortcuts).
- [ ] Missing prerequisites enqueue legal setup or recovery steps.

## Robustness (required)
- [ ] Top-level task intent preserved through subtasks.
- [ ] Stale / unreachable targets handled (reroute, replace, or fail-fast).
- [ ] No-progress / loop guard present where retries could spin.

## Observability (required)
- [ ] Structured logs (pino, tagged `[BOT][PLAN][ACT][MEM][GROUND][LLM][EVAL]`) for key decisions and failure reasons.
- [ ] Avoided noisy player-facing debug spam.

## Validation (required)
- [ ] `npx tsc --noEmit` (typecheck)
- [ ] `npm test` (unit tests)
- [ ] `npm run eval` (or the affected scenario explicitly) if behavior changed
- [ ] Updated [`docs/COMPANION_V1_DIRECTION.md`](../docs/COMPANION_V1_DIRECTION.md) if architecture-relevant.
- [ ] Manual playtest notes when in-world behavior changed.

## Risks / follow-ups
- Known limitations:
- Follow-up tasks:

---

> If this PR touches `legacy/forge-mod/`, please reconsider — that directory is a frozen archive (see [`legacy/forge-mod/ARCHIVE_NOTICE.md`](../legacy/forge-mod/ARCHIVE_NOTICE.md)). Active development is in [`steveai-companion/`](../steveai-companion/).
