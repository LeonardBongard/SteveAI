# Clearing Questions Before Implementation

Answer these once and we can execute wave-by-wave with minimal back-and-forth.

## Decision Snapshot (2026-02-28)
1. Q1 = A (survival first)
2. Q2 = B (auto-eat with exceptions; protect premium food, avoid unsafe food by default)
3. Q3 = A (conservative)
4. Q4 = C (hybrid canonical data strategy)
5. Q5 = B (latest supported minor, manual old-version deprecation)
6. Q6 = A (mirror vanilla hunger)
7. Q7 = B (wheat + carrot + potato)
8. Q8 = C (include animal breeding in first release)
9. Q9 = B (one PR per domain)
10. Q10 = B (unit + integration + manual scripted in-game)
11. Q11 = no (postpone panic framework)
12. Q12 = C (both debug UI and logs, when panic is implemented later)
13. Q13 = B (`ActionExecutor` + per-action safety checks)
14. Q14 = A (retreat -> eat/heal -> re-evaluate)
15. Q15 = A (normal river traversal safe by default)

## A. Product Behavior Defaults
1. Priority order when goals conflict:
- A) survival first (health/hunger/safety > task)
- B) user task first unless near death
- C) configurable per Steve

Resolved:
- Default is A (survival first).

2. Autonomy level for hunger:
- A) always self-feed automatically
- B) ask before consuming rare food
- C) only eat when explicitly commanded

Resolved policy:
- Auto-eat safe food.
- Protect premium food (golden apple variants) from auto-consume.
- Avoid unsafe food by default (for example rotten flesh).

3. Risk posture default:
- A) conservative (avoid water/combat unless necessary)
- B) balanced
- C) aggressive (speed over safety)

## B. Data and Canonical Sources
4. Canonical source for Minecraft item/recipe/animal food data:
- A) Mojang/official data packs bundled at build time
- B) curated local CSV/TOML under `src/main/resources/steve`
- C) hybrid (local defaults + generated refresh scripts)

5. Version target:
- A) strict single version (confirm exact MC/Forge)
- B) latest supported minor only
- C) best-effort compatibility

## C. Gameplay Semantics
6. Hunger model:
- A) mirror vanilla hunger exactly
- B) simplified internal model mapped to vanilla
- C) custom model for AI only

7. Farming scope first:
- A) wheat only bootstrap
- B) wheat + carrot + potato
- C) include animal farming immediately

8. Animal feeding scope first:
- A) cows/sheep/chicken only
- B) all passive mobs with food prefs
- C) breeding support in first release

## D. Engineering Process
9. Delivery cadence:
- A) one PR per wave
- B) one PR per domain
- C) small rolling PRs (2-4 days each)

10. Test expectation for each wave:
- A) unit + manual in-game test
- B) unit + integration + manual script
- C) fast unit first, integration later

## E. Panic Framework (Now No-Op)
11. Panic placeholders:
- Confirm we should add enum/state/logging now with no behavioral effect.
Resolved:
- No. Panic framework is postponed.

12. Panic observability:
- A) debug UI only
- B) logs only
- C) both debug UI and logs
Resolved (for later panic phase):
- C) both debug UI and logs.

## F. Safety Evaluator Manager
13. Safety evaluator placement:
- A) `ActionExecutor` gate only
- B) `ActionExecutor` + per-action checks
- C) fully centralized middleware (executor only, actions unaware)
Resolved:
- B) `ActionExecutor` + per-action checks.

14. Combat unsafe policy:
- A) retreat -> eat/heal -> re-evaluate (recommended)
- B) eat/heal in-place -> re-evaluate
- C) configurable by command context

15. Water safety semantics:
- A) normal river traversal safe by default (recommended)
- B) cautious for all water bodies
- C) dynamic by biome/depth
