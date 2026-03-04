# Mining Plan

Goal: make mining robust, player-like, and safe while handling missing resources.

## Work order (top-down, recursive)
1) Audit current framework (weakness mapping)
   - Review current mining path (target selection, obstruction queue, scan radius, progress tick).
   - Identify failure modes (searching loops, wrong target selection, block depletion).
   - Output: short list of concrete weak points that block next features.
2) Build the core data layer (reusable for all features)
   - Block transition mapping (e.g., grass_block -> dirt).
   - Tool capability mapping (blocks -> valid tools).
   - Keep data-driven (CSV/TOML) with minimal defaults.
3) Stabilize mining state machine
   - Cleanly separate: target selection → obstruction queue → mining execution → progress.
   - Add mid-scan idle recovery (rescan + expand radius).
   - Add deterministic fallback selection when target vanishes.
4) Player-like mining loop
   - Apply correct break timing (already started).
   - Add block break sound + particles in sync with break timing.
   - Respect hardness/tool speed to compute break duration.
5) Safe mining behavior
   - Avoid 1x1 tunnels unless explicit.
   - Ensure 2-block headroom before moving forward.
   - Prevent suffocation (back-off, widen tunnel, or disable block entry).
6) Autonomy when no target exists
   - Explore for caves and exposed veins.
   - If none found, run strip-mine pattern with safety rules.

## Recursive sub-issues (add + solve in order)
### 1) Framework audit (blocking)
Findings (2026-01-31):
- Searching loops happen when the target block depletes (e.g., grass_block -> dirt) and no transition mapping exists; mining continues in “searching” even though the terrain changed.
- Stone mining often stalls with 0 found: scan radius is still too small in some worlds; when expanded, targets can still be blocked or out of sight.
- Target selection is not LOS-aware; obstruction queue can’t fix cases where the selected target is unreachable (behind walls or below floors).
- Obstruction queue uses eye-to-target ray sampling, but target selection doesn’t validate reachability; this leads to repeated “searching” without deterministic fallback.
Updates (2026-01-31):
- Block transition mapping implemented and verified (grass_block -> dirt; recursive acceptable-source lookup).
- Mining now accepts acceptable source blocks when target is a transition result (fixes “mine dirt” on grass).
- Visible scan radius now syncs to adaptive scan radius during searches (perception + targeting aligned).
- Added reachability-aware target selection (exposed face + adjacent-air path check) to reduce reselecting unreachable targets.

### 2) Data layer (foundational)
- Create `BlockTransitionMap` (block -> result block).
- Create `ToolCapabilityMap` (block -> valid tools).
- Decide storage format and loader.
Progress:
- `BlockTransitionMap` implemented with CSV loader and recursive source lookup.
- `block_transitions.csv` expanded to 100+ early-game entries (identity + true transitions).
- `ToolCapabilityMap` implemented with CSV loader (`block_tool_requirements.csv`).

### 3) State machine cleanup
- Separate mining states: SEARCHING → NAVIGATING → MINING → COMPLETE.
- Add timed rescan triggers and deterministic fallback target selection.

### 4) Player-like mining
- Break time based on hardness + tool.
- Sync sounds and particles with the final break tick.

### 5) Safety constraints
- Enforce 2-block high tunnels.
- Detect suffocation risk and back-off.

### 6) Autonomy
- Cave detection + vein following.
- Strip-mine fallback with safe patterns.

## Phase 1 — Stabilize targets (short-term)
1) Block transition knowledge
   - Data-driven mapping of block -> post-mine block (e.g., grass_block -> dirt).
   - Allow “equivalent” blocks to satisfy a mining goal (grass satisfies dirt).
2) Adaptive scanning & mid-scan fallback
   - Expand scan radius when idle.
   - Periodic rescan if no progress for N ticks.
3) Obstruction handling
   - Keep the Bresenham-style obstruction queue.
   - Ensure target selection never “skips” blockers.
Status:
- 1) Done (transition map + acceptable-source mining).
- 2) Partially done (adaptive radius + rescan + scan radius sync); still stalls when no local targets exist.
- 3) Resolved (reachability-aware target selection now filters unreachable blocks).

## Phase 2 — Player-like mining (medium-term)
1) Tool awareness
   - Map blocks -> valid tools (data-driven).
   - If tool missing, request or switch tools before mining.
Status:
- Tool awareness implemented: Steve warns when a required tool is missing and prefers better tools when available.
2) Mining physics/FX
   - Use a timed break sequence per block (already partially done).
   - Add correct swing timing, block break sound, and break particles.
   - Respect block hardness and tool speed.

## Phase 3 — Autonomy when no targets exist (long-term)
1) Exploration
   - Search for caves / exposed veins.
   - If none found, dig a safe exploratory tunnel (avoid 1x1).
2) Vein creation / strip mining logic
   - Basic vein-following behavior.
   - Strip-mine pattern with safety rules.

## Safety requirements (always-on)
- No 1x1 tunnels unless explicitly requested.
- Never trap itself in a single-block hole.
- Always ensure headroom (2-block height) before moving.
- Use safe pathing around water/lava; avoid suffocation.

## Open questions
- CSV vs. TOML for data-driven mappings?
- Should “equivalent” blocks be controlled by config or hardcoded defaults?
- How aggressive should exploration be if target is missing?
