# Sprint 0001 Slice 01: Stuck Signaling and Local Re-Evaluation

## Purpose
This is the first engineering slice of `SPRINT_0001_RUNTIME_REPLANNING_SPINE`.

Sprint Goal link:
- Steve can detect no-progress and perform structured runtime replanning without stalling.

Slice Goal:
- Steve can detect that he is making no useful progress and, before invoking a full replan, re-check immediate surroundings while moving/searching and switch to a newly visible viable target.

## Why This Slice Comes First
1. Full runtime replanning is too expensive and too broad as a first move.
2. Many current stalls are not true global dead ends.
3. A large class of failures should be solved by faster belief updates and target replacement before calling the planner again.
4. This creates the signal and observability required for later replan work.

## Scope
### In scope
1. Add canonical no-progress detection signals.
2. Add a stuck-reason taxonomy for common early cases.
3. Re-scan immediate surroundings while Steve is already moving/searching.
4. Switch to a better local candidate when one becomes visible and reachable.
5. Log why Steve switched or why he stayed on the current branch.

### Out of scope
1. Full LLM-based replan insertion.
2. Goal-tree rewriting.
3. Farming/food/animal domain changes unless needed for generic stuck detection plumbing.
4. Panic behavior.
5. Self-modifying code or hot code patching in runtime.

## Reusable Subsystem Being Improved
Agent control:
- no-progress detection
- search-state inspection
- local target refresh
- pre-replan recovery path

This slice must not become an ore-only or wood-only fix.

## Concrete Engineering Tasks
1. Define canonical no-progress signals.
- Examples:
  - no block mined for N ticks while mining task active
  - same target selected repeatedly without net approach progress
  - searching state persists while new visible candidates appear
  - pathing moves but task usefulness does not improve

2. Define initial stuck-reason taxonomy.
- Initial categories:
  - `NO_VISIBLE_TARGET`
  - `STALE_TARGET`
  - `UNREACHABLE_TARGET`
  - `SEARCH_LOOP`
  - `NO_PROGRESS_WHILE_MOVING`

3. Add immediate local re-evaluation hook.
- While moving/searching, inspect visible/local candidates continuously.
- If a candidate is now visible, reachable, and better than the stale one, switch.

4. Add anti-thrash guard.
- Do not bounce between equivalent nearby candidates.
- Require a real improvement or stale-target threshold before switching.

5. Add observability.
- `[STUCK]` for detector output
- `[LOCAL_REEVAL]` for candidate refresh decisions
- include top-level goal, current action, target, reason, and tick counters

## Candidate Code Touchpoints
1. [MineBlockAction.java](/Users/leonardbongard/Projects/SteveAI/src/main/java/com/steve/ai/action/actions/MineBlockAction.java)
2. [PathfindAction.java](/Users/leonardbongard/Projects/SteveAI/src/main/java/com/steve/ai/action/actions/PathfindAction.java)
3. [ActionExecutor.java](/Users/leonardbongard/Projects/SteveAI/src/main/java/com/steve/ai/action/ActionExecutor.java)
4. [SteveMemory.java](/Users/leonardbongard/Projects/SteveAI/src/main/java/com/steve/ai/memory/SteveMemory.java)
5. [StevePlaytestRunner.java](/Users/leonardbongard/Projects/SteveAI/src/main/java/com/steve/ai/testing/StevePlaytestRunner.java)

## Acceptance
1. During ore or resource search, Steve does not continue a stale search branch if an immediate visible viable target appears.
2. Steve emits a structured stuck reason instead of silently standing/searching.
3. Steve can stay on the same top-level goal while switching local target.
4. Switching logic works for generic gather/mining target classes, not only one material.

## Regression
1. `iron_pickaxe` remains the default gate:
- `/steve test iron_pickaxe Steve 420`
2. No target oscillation loop between equivalent nearby candidates.
3. No increase in silent idle stalls.
4. Behavior scheduler still runs normally:
- `/steve behavior Steve list`

## Review Evidence Required
1. One successful playtest where Steve changes target mid-search because a better local option appears.
2. One failed playtest where no local option exists and Steve fails explicitly without silent stall.
3. Logs containing `[STUCK]` and `[LOCAL_REEVAL]` entries.

## Exit Condition
This slice is complete when:
1. We can trust the no-progress signal.
2. Steve reacts locally before escalating.
3. We have enough structured signal to implement full runtime replanning in the next slice.
