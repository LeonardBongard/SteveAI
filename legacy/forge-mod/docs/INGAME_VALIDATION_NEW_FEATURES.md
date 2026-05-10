# In-Game Validation: New Features (Farm/Feed/Fallback/Chest-Risk)

## Preconditions
1. Use a test world with operator permissions.
2. Spawn one Steve:
- `/steve spawn Alex`
3. Verify Steve exists:
- `/steve list`
4. Standard recurring regression command (default goal):
- `/steve test iron_pickaxe Alex 420`

## Scenario 1: Fallback Planner (`feed`) when LLM is unavailable
Setup:
1. Set AI provider to an unavailable backend (example: invalid Ollama endpoint in `steve-common.toml`).
2. Restart world/client after config change.

Command:
- `/steve tell Alex feed the cows`

Expected:
1. Command still creates a valid action plan (no parser crash).
2. Steve attempts `feed` behavior (moves toward valid animals if present).

## Scenario 2: Fallback Planner (`farm`) when LLM is unavailable
Command:
- `/steve tell Alex farm wheat`

Expected:
1. Valid `farm` task is produced via fallback.
2. Steve starts farming behavior.

## Scenario 3: Feed 3 Cows, only 1 valid target nearby
Setup:
1. Keep one adult cow nearby.
2. Ensure Alex has wheat.

Command:
- `/steve tell Alex feed 3 cows`

Expected:
1. Alex feeds available cow(s).
2. Exits with partial-success style completion, no infinite loop.

## Scenario 4: Feed Pigs with wrong food
Setup:
1. Nearby adult pig(s).
2. Alex inventory contains wheat only.

Command:
- `/steve tell Alex feed 2 pigs`

Expected:
1. Explicit failure reason: no valid pig food.
2. Action terminates cleanly.

## Scenario 5: Feed Chickens with seeds
Setup:
1. Nearby adult chickens.
2. Alex has `wheat_seeds` or other valid seeds.

Command:
- `/steve tell Alex feed 2 chickens`

Expected:
1. Uses valid seed items.
2. Stops after required count.

## Scenario 6: Farm Carrots from dirt (with carrot item available)
Setup:
1. Nearby dirt patch + water.
2. Alex has carrots in inventory.

Command:
- `/steve tell Alex farm carrots and get 8 carrots`

Expected:
1. Tills dirt to farmland.
2. Plants carrots.
3. Harvestes mature carrots and progresses quantity.

## Scenario 7: Farm Potatoes without planting item
Setup:
1. Nearby dirt/farmland.
2. Alex has no potato item.

Command:
- `/steve tell Alex farm potatoes and get 8 potatoes`

Expected:
1. No infinite loop.
2. Ends with explicit fail/timeout style reason.

## Scenario 8: Chest risk reject (critical hunger, stale/low-value chest)
Setup:
1. Ensure a known chest memory entry is far, stale, and low value.
2. Force hunger low:
- `/steve debug Alex food 2`
- `/steve debug Alex saturation 0`

Command:
- `/steve tell Alex gather wood for crafting`

Expected:
1. Does not choose bad chest route.
2. Uses local survival recovery flow first.

## Scenario 9: Chest risk accept (fresh/high-value chest)
Setup:
1. Nearby known chest with enough food (freshly scanned).

Command:
- `/steve tell Alex gather wood for crafting`

Expected:
1. Retrieves from chest when value/risk is acceptable.
2. Resumes prior task flow afterward.

## Scenario 10: Legacy payload compatibility (`count`, `blockType`, `playerName`)
Method:
1. Use any existing script/client path still sending legacy keys.

Expected:
1. Mine/gather/craft/follow still execute (alias compatibility works).

## Scenario 11: Canonical payload compatibility (`quantity`, `block`, `player`)
Command examples:
- `/steve tell Alex mine iron`
- `/steve tell Alex follow me`

Expected:
1. Same success behavior with canonical keys.

## Scenario 12: Stop/Resume reliability
Command sequence:
1. Start long action:
- `/steve tell Alex farm wheat and get 20 wheat`
2. Stop:
- `/steve stop Alex`
3. New command:
- `/steve tell Alex feed 2 cows`

Expected:
1. Stop interrupts immediately.
2. New command starts cleanly.

## Scenario 13: River crossing for gather target
Setup:
1. Spawn Steve on one side of a river.
2. Set task target on opposite side.

Command:
- `/steve tell Alex go to x y z` (across river)

Expected:
1. Steve enters water and continues traversal (no panic block for normal river).
2. Logs include `[SWIM] entered water segment` then `[SWIM] exited water segment`.

## Scenario 14: Shoreline stuck recovery
Setup:
1. Place blocks/terrain causing frequent edge-stuck at water exit.

Command:
- `/steve tell Alex go to x y z` (requires exiting water at rough shoreline)

Expected:
1. Steve triggers swim repath and shoreline recovery (no infinite loop).
2. Logs include `[SWIM] repath trigger` and optional `[SWIM] shoreline recovery target=...`.

## Scenario 15: Long water segment continuity
Setup:
1. Route Steve through a longer water section (river bend/lake edge).

Command:
- `/steve tell Alex go to x y z`

Expected:
1. No timeout under normal path length.
2. Navigation completes with explicit success/failure reason; no silent stall.

## Scenario 16: Crafting table required for table recipes
Setup:
1. Ensure no nearby crafting table.
2. Ask Steve to craft bread/chest/tools.

Command:
- `/steve tell Alex craft bread`

Expected:
1. Steve does not perform illegal direct craft without station.
2. Steve queues station setup (craft/place table) or fails with explicit station reason.

## Scenario 17: Furnace required for smelt recipes
Setup:
1. Ensure no nearby furnace.
2. Ask Steve to craft a furnace-only recipe.

Command:
- `/steve tell Alex craft glass`

Expected:
1. Steve enforces furnace requirement (no illegal direct conversion).
2. Station setup is queued (craft/place furnace), then legal smelt starts.
3. Steve inserts valid fuel + input, waits for smelt time, and collects output (no instant conversion).

## Scenario 18: Stonecutter required for stonecutter recipe
Setup:
1. Ensure no nearby stonecutter.

Command:
- `/steve tell Alex craft stone bricks`

Expected:
1. Steve enforces stonecutter requirement.
2. Station setup/retry is visible in task flow and logs.

## Scenario 19: Illegal task blocked by validator
Method:
1. Inject/trigger malformed task payload (unknown action or invalid params) through any integration path.

Expected:
1. Planner/executor rejects task with `[LEGALITY]` reason.
2. No illegal world mutation occurs.

## Scenario 20: End-to-end `craft iron_pickaxe` (default regression)
Setup:
1. Ensure Steve has no iron pickaxe.
2. Remove nearby crafting table (to exercise station setup path).
3. Keep logs visible.

Command:
- `/steve tell Alex craft iron pickaxe`

Expected task flow:
1. Steve validates legal craft action and resolves recipe requirements (`3 iron_ingot`, `2 stick`).
2. If no crafting table is found, Steve queues station setup (`craft crafting_table` -> `place crafting_table` -> retry `craft iron_pickaxe`).
3. If a crafting table exists but is farther than interaction range, Steve pathfinds to it, looks at it, and retries craft.
4. If ingredients are missing, Steve queues dependency plan:
- gather/retrieve requirements first (including chest-memory sourcing),
- smelt intermediates at furnace when required (`raw_iron` -> `iron_ingot`) with legal fuel/time handling,
- craft intermediates (`oak_planks`/`stick`) as needed,
- then retry `iron_pickaxe`.
5. Final craft only succeeds when the required station and ingredients are available.

Outcome checks:
1. Success path: if `iron_ingot` exists in inventory/chests, Steve crafts `iron_pickaxe`.
2. Smelt path: if only iron ore/raw iron is obtainable, Steve queues legal furnace-smelt steps, then crafts `iron_pickaxe`.

## Scenario 21: Smelt requires fuel and time (no instant furnace craft)
Setup:
1. Place/allow one furnace near Steve.
2. Ensure Steve has `raw_iron` but no `iron_ingot`.
3. Ensure Steve has no fuel initially.

Command:
- `/steve tell Alex craft iron pickaxe`

Expected:
1. Steve does not convert `raw_iron` instantly.
2. Steve gathers/retrieves fuel (for example coal), inserts fuel and input into furnace, then waits for output.
3. Logs show `[SMELT]` progress and completion before final pickaxe craft.

## Scenario 22: Wood gather with non-oak variants and distant trees
Setup:
1. Ensure no oak logs are near Steve.
2. Keep only birch/spruce/jungle trees in medium distance (for example 20-60 blocks).

Command:
- `/steve tell Alex gather wood for crafting`

Expected:
1. Steve does not stall on only `oak_log` targeting.
2. Steve accepts alternative log types and mines whichever valid tree is found first.
3. Search behavior expands exploration radius over time and eventually commits to movement/mining.

## Scenario 23: Main-goal resume after subtask failure/deviation
Setup:
1. Trigger a multi-step objective (for example `craft wooden pickaxe` or `craft iron pickaxe`).
2. Force at least one intermediate failure/deviation (temporary missing path/resource or interrupted branch).

Command:
- `/steve tell Alex craft wooden pickaxe`

Expected:
1. Steve keeps a task-tree memory and does not forget the main objective.
2. When queue becomes empty unexpectedly, Steve resumes pending/failed task-tree nodes automatically.
3. Logs include `[GOAL] ... resumed task ...` when recovery path is used.

## Scenario 24: Dynamic behavior lanes (enable/disable runtime)
Setup:
1. Spawn Steve and ensure command perms.
2. Start a long-running task.

Commands:
- `/steve behavior Alex list`
- `/steve behavior Alex disable passive_visible_scan`
- `/steve behavior Alex list`
- `/steve behavior Alex enable passive_visible_scan`
- `/steve behavior Alex list`

Expected:
1. Behavior list shows lane/priority/cooldown/cost and ON/OFF state per module.
2. Disabling `passive_visible_scan` sets it OFF without stopping current foreground task.
3. Re-enabling returns it to ON and visible memory refresh resumes in subsequent ticks.
4. Unknown IDs fail explicitly and print known behavior IDs.

## Pass Criteria
1. No infinite loops in logs.
2. Explicit reasons for failed actions.
3. Fallback farm/feed still produce valid tasks when LLM is unavailable.
4. Chest-risk behavior chooses/avoids chest routes according to urgency/value.
5. Standard structured logs exist for critical branches (`[FARM]`, `[SAFETY]`, `[FOOD]`, `[SMELT]`) so crash reconstruction is possible.
6. Water traversal emits `[SWIM]` logs for segment transitions and recovery branches.
7. Crafting and execution legality checks block illegal actions (`[LEGALITY]`) and station-locked recipes require correct block usage.
8. Scenario 20 (`craft iron_pickaxe`) is run as a default regression check for crafting pipeline changes.
9. Scenario 24 confirms runtime modular behavior control works per Steve instance.
