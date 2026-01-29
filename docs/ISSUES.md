# Issue Drafts

Below are ready-to-post issue drafts based on recent playtests/logs.

## 1) LLM "wood" alias rejected by MineBlockAction
Summary:
- When the LLM plans block=wood, MineBlockAction rejects it as invalid.

Repro:
1. Run the mod.
2. Command: "mine wood" or "mine nearby woods".

Expected:
- "wood" resolves to a valid log type (e.g., oak_log) or nearby logs.

Actual:
- Action completes immediately with "Invalid block type: wood".

Log snippet (example):
- [DEBUG] Starting: mine {quantity=16, block=wood}
- Action completed: Invalid block type: wood

Notes:
- Works if the user says "oak log".

## 2) BuildStructureAction cannot find "house" templates
Summary:
- "build a starter house" fails because no structure templates are loaded.

Repro:
1. Run the mod.
2. Command: "build a starter house".

Expected:
- A house template loads/builds.

Actual:
- "Structure 'house' not found. Available structures: []"
- "Cannot generate build plan for: house"

Log snippet (example):
- Structure 'house' not found. Available structures: []
- Action completed: Cannot generate build plan for: house

## 3) "destroy house" mapped to build action
Summary:
- LLM plans "Destroy building" but action becomes build with structure=house.

Repro:
1. Command: "destroy the house I am looking at".

Expected:
- A destruction/tear-down action.

Actual:
- Action runs as build and fails due to missing structure.

Log snippet (example):
- Plan received: Destroy building
- Executing task: action='build' ... structure=house

## 4) Steve can suffocate in walls while mining
Summary:
- Steve suffocates inside blocks during mining tunnels.

Repro:
1. Command Steve to mine stone/cobblestone for a while.

Expected:
- Steve avoids suffocation (safe movement or no-clip while mining).

Actual:
- "Steve suffocated in a wall".

Log snippet (example):
- LivingEntity died: Steve suffocated in a wall

## 5) "unknown location" pathfinding plan
Summary:
- LLM generates plan "Pathfind to unknown location" for unclear user input.

Repro:
1. Command: "go to kow" (or other unknown place).

Expected:
- Ask for clarification or fail gracefully with user feedback.

Actual:
- Plan created but no clear resolution; ambiguous target.

Log snippet (example):
- Plan received: Pathfind to unknown location

## 6) UX: command ignored while planning
Summary:
- New commands are ignored when planning is already in progress.

Repro:
1. Trigger an LLM plan.
2. Immediately send another command.

Expected:
- Queue, cancel, or provide clearer UX feedback.

Actual:
- Warning: "already planning, ignoring command"

Log snippet (example):
- is already planning, ignoring command: mine cobblestone

## 7) Add custom build command for AI-driven planning (not just prebuilts)
Summary:
- Current build flow relies on prebuilt templates or hardcoded generators.
- We want a custom build command so the AI can plan its own structure layout (block-by-block) rather than selecting only from prebuilts.

Motivation:
- Enables more creative builds beyond predefined "house"/"tower"/etc.
- Lets LLM provide a dynamic plan with block placements or blueprint-like steps.

Desired outcome:
- New action (e.g., "build_custom" or "blueprint") that accepts a block placement list or a higher-level plan.
- A parsing/validation layer so AI-produced plans are safe and bounded.

Notes:
- Currently `BuildStructureAction` uses `StructureTemplateLoader` or `StructureGenerators` only.

## 8) Collaborative build can stall with "no more blocks" spam
Summary:
- During collaborative builds (e.g., castle), Steves repeatedly log "has no more blocks" and progress stays stuck.

Repro:
1. Start a collaborative build (e.g., "build a castle") with 2 Steves.
2. Let it run for a while.

Expected:
- Build completes or reassigns remaining blocks; no infinite spam.

Actual:
- Repeated log: "has no more blocks! Build 53% complete" and progress never changes.

Log snippet (example):
- Steve 'SteveAI' has no more blocks! Build 53% complete
- castle build progress: 636/1200 (53%) - 2 Steves working

## 9) Build action description shows 0/0 while building
Summary:
- Build action logs show "Build castle (0/0)" while a build is in progress.

Repro:
1. Start a build action.
2. Observe logs.

Expected:
- Description should reflect actual progress (placed/total).

Actual:
- Logs show "Build castle (0/0)" during ticking.

Log snippet (example):
- Steve 'SteveAI' - Ticking action: Build castle (0/0)

## 10) Make mining target selection customizable (not hardcoded scanning only)
Summary:
- Mining behavior is currently hardcoded (scan + target matching). We want it to be customizable so the AI can use smarter strategies (e.g., soil vs. ores, surface vs. underground, biome-based).

Motivation:
- Enable smarter “find dirt/wood/ore” behavior without rewriting MineBlockAction each time.

Desired outcome:
- A configurable strategy or plugin hook for mining target selection.
- Option to inject AI-provided hints or use world knowledge.

Notes:
- Current logic lives in `MineBlockAction` (scan + select + break).

## Reference: AI generation method link
Notes:
- User-provided link for AI generation reference:
```text
https://sites.google.com/view/steve-1
```

## 11) "screenshot" command goes to LLM instead of local POV capture
Summary:
- Typing "screenshot" in the Steve GUI still triggers LLM planning and pathfinding, rather than taking a Steve POV screenshot.

Repro:
1. Open Steve GUI and type: "screenshot".

Expected:
- Local POV screenshot captured without sending command to LLM.

Actual:
- LLM receives the command and plans "Capture image"; Steve pathfinds to (0,0,0).

Log snippet (example):
- Steve 'Steve' processing command (async): screenshot
- Plan received: Capture image
- Executing task: Task{action='pathfind', parameters={x=0, y=0, z=0}}
