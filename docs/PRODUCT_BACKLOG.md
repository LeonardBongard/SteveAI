# Product Backlog

This is the canonical ordered Product Backlog for Steve AI.

Reference:
- `docs/SCRUM_OPERATING_MODEL.md`
- `docs/ROADMAP_NORTH_STAR.md`

Rules:
1. This file is the single ordered backlog source.
2. Higher items have higher priority.
3. Items must be capability-shaped, not narrow bug phrased.
4. If a bug reveals a generic gap, the backlog item must describe the generic capability to improve.
5. Each active sprint should draw only the minimum backlog slice needed for the Sprint Goal.

## Product Goal
- Build a Minecraft agent that can create, execute, inspect, and update its own task plans in-world, using reusable systems instead of local one-off fixes.

## Ordered Backlog
1. Runtime Replanning Spine
- Capability: detect no-progress, classify stuck reason, locally recover, escalate to validated runtime replanning, preserve top-level goal.
- Why now: this is the main anti-stall/globalization layer and reduces repeated local fixes across all domains.
- Active sprint: `docs/sprints/SPRINT_0001_RUNTIME_REPLANNING_SPINE.md`

2. Safety Evaluator Integration
- Capability: continuous safety state, action gating, retreat/stabilize/re-evaluate behavior.
- Why now: all domain execution should be safety-aware before broader feature expansion.

3. Hunger and Eating Reliability
- Capability: need detection, food ranking, safe auto-eat, task interruption/resume.
- Why now: survival-first behavior must be stable before longer autonomous loops.

4. Swimming and Water Traversal Reliability
- Capability: water-aware pathing, shoreline recovery, no water deadlocks.
- Why now: pathing stalls in water break many higher-level tasks.

5. Crafting Pipeline Generalization
- Capability: dependency decomposition, legal station usage, fuel/time-aware smelting, explicit failures.
- Why now: crafting is already partially proven via iron pickaxe and should be generalized.

6. Mining Normalization
- Capability: generic source selection, better reachability, perception refresh, persona-safe mining behavior.
- Why now: mining is core to many goals and currently contains many historical stall classes.

7. Knowledge/Data Spine
- Capability: canonical data for item sources, recipes, legal actions, food values, tool requirements, animal preferences.
- Why now: planners and resolvers need data completeness to avoid local hand-coded logic.

8. Farming Loop
- Capability: site selection, water-aware farmland setup, seed/crop lifecycle, harvest/replant.
- Why now: renewable food/material loops are part of the north star.

9. Animal Feeding and Breeding
- Capability: species-food mapping, acquire food, approach, feed, verify result.
- Why now: extends living-world interaction and renewable loops.

10. Multi-Steve Coordination
- Capability: independent inventories/memory/debug state, stable multi-agent testing, persona comparison.
- Why now: useful for testing and later collaboration, but lower priority than single-agent reliability.

11. Panic Framework
- Capability: panic states and behavior modulation.
- Status: deferred by product decision until explicitly re-enabled.

## Backlog Hygiene
Before adding a new item, ask:
1. Is this a true Product Backlog item or just a subtask of an existing item?
2. Is it phrased as a reusable capability?
3. Does it link clearly to the Product Goal?
4. Does it avoid embedding one-off implementation detail in the title?
