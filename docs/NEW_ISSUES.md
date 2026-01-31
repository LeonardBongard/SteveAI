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

## Steve mines blocks behind other blocks (through the floor)
Summary:
- Steve will sometimes break the block under/above the target before reaching the block the task asked for; he ends up mining through a floor or ceiling.
Repro:
1. Ask Steve to mine a block that is positioned below another solid block.
2. Watch him swing and break the obstructing block while standing on top or beneath it.
Expected:
- Steve should break any obstructing block between him and the target first, but if the obstructing block *is* the requested block he should mark it as part of the task and update progress accordingly.
