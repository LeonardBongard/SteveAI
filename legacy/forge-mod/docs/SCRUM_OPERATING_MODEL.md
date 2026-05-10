# Scrum Operating Model for Steve AI

This process is aligned to the official Scrum Guide:
- [Scrum Guide](https://scrumguides.org/scrum-guide.html)

The goal of this document is to stop local bug-fix drift and keep work aimed at global agent capability.

## 1. Product Goal
Product Goal:
- Build a Minecraft agent that can take a human goal, create its own task plan, execute it legally in-world, detect when it is stuck, replan with live LLM support, and continue without falling into infinite loops or local-only hacks.

Global capability target:
1. Steve understands long-horizon goals.
2. Steve decomposes goals into reusable subtasks.
3. Steve keeps background world learning active while working.
4. Steve detects no-progress/stuck states explicitly.
5. Steve replans through runtime decision modules, not ad-hoc code paths.
6. Steve improves because core systems get stronger, not because one scenario gets special casing.

## 2. Scrum Artifacts Mapped to This Project
### Product Backlog
The Product Backlog is the ordered list of global capability improvements required to reach the Product Goal.

Canonical location:
- `docs/PRODUCT_BACKLOG.md`

Backlog item types allowed:
1. Capability item:
- Example: "Steve can replan after 30s of no progress using structured stuck context."
2. Architecture item:
- Example: "Add reusable source resolver for any log/food/fuel."
3. Validation item:
- Example: "Add regression scenario and KPI logging for cave explorer ore search."
4. Operational item:
- Example: "Add debug UI and logs to inspect planner state and memory state."

Backlog items must not be phrased as narrow fixes when a reusable capability is the real need.

Bad backlog item:
- "Fix oak log search near jungle tree."

Good backlog item:
- "Generalize gather target resolution so any valid log source is selectable and ranked."

### Sprint Goal
Each sprint must have one coherent capability outcome, not a bag of bugs.

Good Sprint Goal:
- "Steve can recover from ore-search stalls by detecting no-progress and switching search strategy."

Bad Sprint Goal:
- "Fix tree bug, chest bug, cave bug, and random timeout."

### Sprint Backlog
The Sprint Backlog is the selected Product Backlog slice plus the plan to deliver it.

Each sprint item must include:
1. Why this matters for the Product Goal.
2. The reusable abstraction or subsystem being improved.
3. Acceptance scenarios.
4. Regression scenarios.
5. Observability required to inspect/adapt.

### Increment
An increment is only real if it is integrated, testable, and visible in behavior.

For Steve AI, an increment must satisfy:
1. Code is integrated into runtime flow.
2. Logs/debug UI expose the new decision path.
3. At least one manual in-game validation exists.
4. At least one automated regression check exists where possible.
5. The change strengthens a reusable system, not only a single map seed or single scenario.

## 3. Events Mapped to Our Collaboration
### Sprint Planning
Before implementation, define:
1. Product Goal link:
- Which part of the global agent capability does this sprint improve?
2. Sprint Goal:
- One sentence, capability-shaped.
3. Selected backlog items:
- Only the minimum set needed to hit the Sprint Goal.
4. Acceptance:
- Exact in-game scenarios, logs, and automated checks.
5. Global-solution check:
- What reusable subsystem is being improved instead of patched around?

### Daily Scrum Equivalent
Because this is a human + coding-agent loop, every substantial turn should answer:
1. What Sprint Goal are we currently advancing?
2. What changed since the last checkpoint?
3. What is blocking progress?
4. Does the current path still serve the Sprint Goal, or are we drifting into a local fix?

Check-in helper:
- `docs/sprints/SPRINT_CHECKIN_TEMPLATE.md`

### Sprint Review
At the end of a slice, inspect:
1. What increment works in the game?
2. Which scenarios passed?
3. What KPI/log evidence says behavior improved?
4. What remains missing for the Product Goal?

### Sprint Retrospective
After each wave/playtest cycle, inspect the process itself:
1. Did we chase local bugs instead of improving a subsystem?
2. Did we miss an observability hook that would have made debugging easier?
3. Did we add complexity without improving generality?
4. Which rule/process document must change to prevent the same drift again?

## 4. Anti-Local-Solution Gate
Before implementing any fix, ask these questions:
1. Is this bug actually a missing reusable abstraction?
2. Will the fix help other materials, entities, recipes, or environments?
3. If the same issue appears in another biome/version/item, would this solution still work?
4. Does the fix add a new hardcoded exception that the planner cannot reason about?
5. Can this be expressed as data, policy, capability, or resolver logic instead of a one-off branch?

If the answer shows the issue is generic, the fix must be made in the generic layer.

## 5. Runtime Replanning Policy
The runtime AI should adapt plans, not rewrite its own code.

Allowed runtime adaptation:
1. Detect stuck/no-progress using structured signals.
2. Summarize current context:
- goal
- current task
- recent actions
- visible world summary
- memory summary
- safety status
- failure/stuck reason
3. Ask planner/LLM for a revised task plan.
4. Validate revised plan through action contracts and legality checks.
5. Resume execution with explicit logs.

Not allowed as the main runtime strategy:
1. Self-editing mod source code during gameplay.
2. Scenario-specific emergency branches that bypass legality/contract validation.
3. Hidden fallback logic with no logs or validation.

Reason:
- Self-modifying code is the wrong layer. It creates unreviewable local solutions and breaks inspect/adapt discipline.

## 6. Definition of Done for a Sprint Item
An item is done only if all are true:
1. It advances the current Sprint Goal.
2. It improves a reusable subsystem.
3. It has explicit acceptance scenarios.
4. It has required logs/debug state.
5. It passes automated validation where possible.
6. It passes manual in-game validation where behavior matters.
7. It does not introduce a known local-only hack without explicit documentation and follow-up backlog item.

## 7. Mandatory Item Template
Use this template whenever we define the next meaningful task.

### Backlog Item
1. Title:
2. Product Goal link:
3. Sprint Goal link:
4. Problem:
5. Reusable subsystem to improve:
6. Non-goals:
7. Acceptance scenarios:
8. Regression scenarios:
9. Observability required:
10. Risks:

## 8. Current Default Scrum Rhythm for Steve AI
1. Product Goal:
- Reliable autonomous Minecraft job execution with replanning and no local-only hacks.
2. Current default regression:
- `iron_pickaxe`
3. Default Review evidence:
- `./scripts/test_mod_pipeline.sh`
- relevant unit tests
- manual in-game scenario from `docs/INGAME_VALIDATION_NEW_FEATURES.md`
4. Default Retrospective question:
- "Did this change make Steve more generally capable, or did it only make one scene pass?"
