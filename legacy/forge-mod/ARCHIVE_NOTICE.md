# Archive notice — Forge mod (frozen)

This directory is the **frozen archive** of SteveAI's original Forge mod. It is not maintained. Do not push fixes here.

## Reference points

- **Branch:** [`legacy/forge-mod`](https://github.com/LeonardBongard/SteveAI/tree/legacy/forge-mod) — same content as this directory at the time of the cut.
- **Tag:** `v0.x-forge-final` — the canonical "last Forge state" commit (use this if you want a fixed reference that will never move).
- **Full README** for the Forge mod itself is at [`README.md`](README.md) in this directory.

## Why archived

See [`../../docs/MINECRAFT_AI_RESEARCH_SURVEY.md`](../../docs/MINECRAFT_AI_RESEARCH_SURVEY.md) Appendix A and [`../../docs/COMPANION_V1_DIRECTION.md`](../../docs/COMPANION_V1_DIRECTION.md) §7. The pivot is to a Mineflayer-based Node.js companion bot in [`../../steveai-companion/`](../../steveai-companion/).

## What's still useful here

If you're building the new companion and need a reference for any of these, read the corresponding Forge code in this archive — the *behavior* it captured is still valid, even if the substrate changed:

- `src/main/java/com/steve/ai/action/SafetyEvaluatorManager*` — what counts as a safety threat in Minecraft, and how decisions chained.
- `src/main/java/com/steve/ai/memory/` — the original episodic-memory shape.
- `src/main/java/com/steve/ai/action/actions/MineBlockAction.java`, `PathfindAction.java`, etc. — concrete sequencing patterns for actions.
- `docs/INGAME_VALIDATION_NEW_FEATURES.md`, `docs/EVAL_SCENARIOS.md` — scripted player scenarios; these will inform the new eval harness.
- `docs/SCRUM_OPERATING_MODEL.md`, `docs/MODULARITY_RULES.md` — capability-first discipline; cross-cutting LOGGING_POLICY / MODULARITY_RULES live on `main` directly and were also forked from these.

## How to run the archived mod (if you really want to)

It still works. Switch to the branch:

```bash
git checkout legacy/forge-mod
./gradlew build
./gradlew runClient
```

Or build from this directory after pointing Gradle at it. Java 21, Forge 1.21.11 — see [`README.md`](README.md) here for the full guide.
