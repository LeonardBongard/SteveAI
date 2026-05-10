# Sprint 0001: Runtime Replanning Spine

## Metadata
1. Sprint:
- `SPRINT_0001_RUNTIME_REPLANNING_SPINE`
2. Status:
- Active
3. Milestone:
- `Runtime Replanning Spine`
4. Product Goal link:
- `docs/SCRUM_OPERATING_MODEL.md`
- Build a Minecraft agent that can create its own task plan, detect stuck/no-progress states, and update the plan in-world without local-only hacks.
5. Sprint Goal:
- Steve can detect no-progress and perform structured runtime replanning without stalling.
6. Owner(s):
- Human + Codex
7. Date opened:
- 2026-03-12
8. Date closed:
- Open

## Why This Sprint Exists
1. Problem:
- Steve still has a recurring failure mode where he stops, loops, or silently stalls even when the top-level goal is still achievable.
2. Why it matters for the Product Goal:
- Without a reusable replanning spine, every domain will require local recovery hacks.
3. Reusable subsystem to improve:
- Agent control / runtime replanning / stuck detection / task-tree resume.
4. Non-goals:
- No live source-code mutation during gameplay.
- No panic behavior implementation.
- No unrelated farming/animal feature work unless directly required by runtime replanning.

## Current Engineering Slice
1. Active slice:
- `docs/sprints/SPRINT_0001_SLICE_01_STUCK_SIGNALING_AND_LOCAL_REEVALUATION.md`
2. Slice Goal:
- Steve can detect no-progress and locally re-evaluate nearby viable options before escalating to a full runtime replan.
3. Why this slice first:
- It solves a large class of stalls globally and provides the structured signal required for later LLM-based replanning.

## Sprint Backlog
1. Backlog item:
- Capability: canonical no-progress detector with explicit stuck reasons.
- Acceptance: Steve emits a structured stuck reason instead of silently idling.
- Regression: `iron_pickaxe` scenario can trigger detector without crashing or infinite loops.
- Observability: logs and debug state show stuck category and time since last progress.

2. Backlog item:
- Capability: runtime stuck-context snapshot.
- Acceptance: Steve can summarize goal, current task, recent actions, visible/memory context, and safety state for replanning.
- Regression: snapshot generation must not break existing task execution.
- Observability: snapshot fields are visible in logs/debug output.

3. Backlog item:
- Capability: validated runtime replan request through planner/LLM.
- Acceptance: Steve asks for a revised plan when stuck and validates returned actions through legality/contracts.
- Regression: malformed replan output fails explicitly and safely.
- Observability: `[REPLAN]` logs show request reason, result, and acceptance/rejection.

4. Backlog item:
- Capability: preserve top-level goal while replacing failed subpath.
- Acceptance: Steve resumes the main objective after replan instead of forgetting it.
- Regression: no duplicate goal-tree loops and no empty-queue dead end.
- Observability: goal tree / current goal transitions are logged.

5. Backlog item:
- Capability: review metrics for stall quality.
- Acceptance: playtest logs expose whether Steve improved or only spun longer.
- Regression: metrics collection must not break playtest runner.
- Observability: report counts for no-progress windows, replans, resumed tasks, explicit failures.

6. Backlog item:
- Capability: opportunistic local re-evaluation while moving/searching.
- Acceptance: while walking or searching, Steve continuously inspects immediate surroundings, upgrades to a newly visible viable target, and stops long blind search loops when a local option exists.
- Regression: target switching must not cause oscillation or thrashing between equivalent nearby candidates.
- Observability: logs show when local scan found a better candidate and why the current search path was replaced.

## Acceptance Scenarios
1. `iron_pickaxe`: Steve stalls during ore search, triggers structured replan, and either continues toward iron pickaxe or fails explicitly.
2. Gather task with missing local source: Steve detects no-progress, re-evaluates sources, and preserves the top-level gather goal.
3. Station-locked crafting path: Steve cannot continue current branch, replans legal prerequisites, and resumes the craft goal.
4. Ore search with nearby visible candidate: while moving/searching, Steve notices a newly visible reachable ore/block and switches immediately instead of continuing a stale search branch.

## Regression Scenarios
1. Existing `iron_pickaxe` regression remains the default gate:
- `/steve test iron_pickaxe Steve 420`
2. Behavior scheduler sanity remains active:
- `/steve behavior Steve list`
3. No infinite tick loops in logs after any replan attempt.
4. Malformed or illegal replan output is rejected with explicit reason.
5. Continuous local scan must not create target thrash between multiple equivalent nearby blocks.

## Observability Required
1. `[STUCK]` logs:
- category
- no-progress duration
- current task
- top-level goal
2. `[REPLAN]` logs:
- trigger reason
- plan summary
- validation result
- applied/not applied
3. Debug UI or status output must expose current replan state for manual playtests.
4. Local scan/update logs should expose:
- newly visible candidate
- previous target or search mode
- reason for switching or not switching

## Definition of Done
1. Runtime no-progress detection is integrated into normal execution.
2. Replanning uses structured context and contract validation.
3. Main goal is preserved across replan.
4. Manual in-game validation exists for at least one true stall case.
5. Automated regression coverage is updated where possible.
6. Result is reusable across domains and not tied to one map seed or one material.

## Review
1. Increment delivered:
- Open
2. Scenarios passed:
- Open
3. Scenarios failed:
- Open
4. KPI/log evidence:
- Open

## Retrospective
1. What improved globally:
- Open
2. What drifted local:
- Open
3. What process needs to change next sprint:
- Open
