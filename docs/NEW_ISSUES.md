# Pending Issue Drafts

## Work log
- 2026-01-31: Added a simulated swing delay (~40 ticks) before each block break so mining feels less instant; the controller now charges before calling `mineAndCollect`.
- 2026-01-31: Introduced blocked-target tracking so we avoid reselecting unreachable blocks and force rescans when Steve stalls.
- 2026-01-31: Added visibility scan stats and logging for debugging, plus more detailed debug overlay output.

## Mining feels instant (no swing delay)
Summary:
- Steve instantly breaks blocks without any swing/movement, which looks unnatural compared to a real player.
Repro:
1. Ask Steve to mine dirt/stone.
2. Observe logs and gameplay: blocks break almost immediately after moving to them.
Expected:
- There should be a brief mining delay or animation per block to match player behavior.

## Tool capability awareness missing
Summary:
- Steve doesn’t check whether he holds an appropriate tool before mining; he will break any block with the default pickaxe.
Repro:
1. Drop Steve into a world without a pickaxe.
2. Command “mine stone”.
3. Steve moves, swings, and breaks the stone even though no proper tool is equipped.
Expected:
- Steve should know which tool(s) can break a block efficiently and refuse or request one if none available.
- A new manager class should be added which can tell steve which tool to use for which block.
- Steve should switch to the correct tool if available in inventory. Else, he should not attempt to mine the block. He should Say something like "I need a [tool] to mine [block]."

## Water flow disrupts navigation
Summary:
- Steve often falters when approaching water or navigating across flowing water blocks; he gets stuck or repeatedly replans.
Repro:
1. Command Steve to go near/through water.
2. Observe that he hesitates, slides, or repeatedly cancels navigation.
Expected:
- Steve should detect flowing water and either swim across or choose a safer path.

## Data completeness: source block transitions from Minecraft Wiki
Summary:
- The block transition CSV is incomplete and currently hand-curated.
Expected:
- Populate/refresh `block_transitions.csv` from a canonical source (Minecraft Wiki) so we have full coverage and fewer missing edge cases.
Notes:
- Decide on a reproducible extraction/verification process so updates are consistent across versions.

## Navigation stalls when approaching reachable mining targets
Summary:
- Steve sometimes gets "stuck approaching target ... forcing rescan" even after reachability filtering, then loops in searching with 0 found.
Repro:
1. Command “mine andesite/stone” in terrain where blocks are nearby.
2. Observe log line: "stuck approaching target ... forcing rescan" followed by searching.
Expected:
- If a target is considered reachable, Steve should path to an adjacent air block and mine it.
Actual:
- Pathing stalls; target is marked blocked; action stays in searching.
Log snippet (example):
- Steve 'Steve' stuck approaching target BlockPos{x=-21, y=61, z=-50}; forcing rescan

## Steve mines blocks behind other blocks (through the floor) (worked on, check logs=2026-01-31)
Summary:
- Steve will sometimes break the block under/above the target before reaching the block the task asked for; he ends up mining through a floor or ceiling.
Repro:
1. Ask Steve to mine a block that is positioned below another solid block.
2. Watch him swing and break the obstructing block while standing on top or beneath it.
Expected:
- Steve should break any obstructing block between him and the target first, but if the obstructing block *is* the requested block he should mark it as part of the task and update progress accordingly.

### Solution sketch
- Cast a Bresenham-style ray from Steve’s eye position to the target and queue every solid block that appears along that ray.
- Process that obstruction queue before the main target so Steve never skips the layer above/below; as soon as the queue is empty we continue with the original block, but we also treat an obstruction that *is* the target as part of the task.
- Update the queue after each mine and rescan if the viewing direction changes, ensuring the next obstruction reflects the actual sightline instead of a cached line.

Notes:
- Regression observed (2026-01-31): mining logs show “mined Dirt at null” and the action stays in “searching” after 1–2 blocks. Likely due to currentTarget being cleared after obstruction handling; track while iterating on this fix.

## Block state transition knowledge (post-mine transformations)
Summary:
- Steve should understand which blocks transform into other blocks after mining (e.g., grass_block -> dirt), so he can plan and count progress correctly.
Repro:
1. Ask Steve to mine multiple grass blocks.
2. After several breaks, remaining targets disappear because the terrain is now dirt.
Expected:
- A knowledge mapping that tells Steve how blocks transform on break, allowing fallback logic (e.g., continue with dirt if grass is exhausted).
Notes:
- This should be structured like the “tool requirements” system (data-driven mapping / CSV / config for clean extensibility).

## Feature request: allow mining “transforming” blocks to satisfy goals
Summary:
- If a requested block can be produced by mining another block (e.g., mining grass_block yields dirt), Steve should accept that substitution so tasks don’t stall when only the parent block exists.
Example:
- User asks “mine dirt”, and the terrain is all grass_block.
Expected:
- Steve is allowed to mine grass_block to fulfill the dirt requirement, using the transition mapping from the issue above.

# Done / Resolved

## Feature request: allow mining “transforming” blocks to satisfy goals
Summary:
- If a requested block can be produced by mining another block (e.g., mining grass_block yields dirt), Steve should accept that substitution so tasks don’t stall when only the parent block exists.
Example:
- User asks “mine dirt”, and the terrain is all grass_block.
Resolution:
- 2026-01-31: Fixed by accepting any acceptable source block during targeting/mining (uses transition map). Verified: “mine dirt” works on grass_block.
