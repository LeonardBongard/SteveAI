# SteveAI Roadmap — research-grounded next moves

> Distilled from [`MINECRAFT_AI_RESEARCH_SURVEY.md`](MINECRAFT_AI_RESEARCH_SURVEY.md) (2023–2026) into proposals we can actually ship on an M3 Pro/Max with Minecraft + Ollama already running. Each item is tied to one or more cited papers from the survey so the rationale is auditable.

## Where we are vs. where the research says we could go

| Capability | Current SteveAI v2 | Survey-recommended | Gap |
|---|---|---|---|
| Skill library | Voyager-style code-gen + auto-test + verified flag | Voyager (arXiv 2305.16291) + Plan4MC dependency graph | No skill dependencies / no curriculum |
| Memory | MrSteve PEM + pinned facts + player-only retrieval | JARVIS-1 multimodal memory + Optimus-1 HDKG + experience pool | No (state, plan, outcome) tuples; no hierarchical org |
| Knowledge RAG | recipes / mobs / blocks via `minecraft-data` | Same; no paper proposes more sophisticated source | ✓ done |
| Grounding | raycast + nearest + held-item + pinned + episodic | ROCKET-1 visual-temporal masks ([arXiv 2410.17856](https://arxiv.org/abs/2410.17856)) | Vision blocked by hardware |
| Planning | Single-turn LLM tool loop; DEPS-style failure injection | DEPS full Describe→Explain→Plan→Select ([arXiv 2302.01560](https://arxiv.org/abs/2302.01560)) | "Explain" step is implicit; should be explicit |
| Goal tracking | Per-turn only; no cross-turn state | GROOT-2 + Optimus-3 long-horizon goal-conditioning | No persistent goal stack |
| Player UX | Chat replies + dedup + silent-failure fallback | Talking-to-Build (ICMI 2025) "plan visible to player before action" | No plan-preview / no after-action review |
| Eval | 2 mock scenarios | MCU 3,452 atomic tasks ([arXiv 2310.08367](https://arxiv.org/abs/2310.08367)) | Need a real progression suite |
| Vision | None | STEVE-1 / ROCKET-1 / MineDreamer | Blocked: no GPU headroom for a second model |
| Multi-agent | None | VillagerAgent / MineLand / TeamCraft | Out of v1 scope |

## Tier 1 — high impact, low cost, on-hardware

### T1.1 — Multi-turn goal tracking
**Grounding:** [GROOT-2 (arXiv 2412.10410)](https://arxiv.org/abs/2412.10410), [Optimus-3 (arXiv 2506.10357)](https://arxiv.org/abs/2506.10357) — both emphasize long-horizon goal-conditioned policies. Our planner is currently single-turn-fresh-context, which is why "get a wooden pickaxe" takes 5+ turns of the LLM rediscovering "wait, what was the goal?"

**Change:** add a persistent `goal_stack` to `PlannerState`, surfaced into the system prompt each turn as `Active goals: [1] get wooden pickaxe (started 3 turns ago, current sub-goal: craft sticks)`. The LLM marks goals as done / pivots / pushes sub-goals.

**Effort:** ~80 lines. New tools: `pushGoal(text)`, `popGoal()`, `completeGoal()`. Schema migration adds a `goals` table.

**Why first:** explains a category of failures we've seen (LLM losing track mid-task) and unblocks T1.2.

### T1.2 — Skill dependency graph + auto-prerequisite chain
**Grounding:** [Plan4MC (arXiv 2303.16563)](https://arxiv.org/abs/2303.16563): "LLM generates skill dependency graph; search algorithm interactively plans skill sequence." Our skills are flat; the LLM rediscovers prerequisites every turn.

**Change:** when `writeSkill` runs, optionally accept a `prerequisites: string[]` and `producesItems: string[]` declaration. Persist in the skill row. When `invokeSkill` is called and prerequisites aren't satisfied (e.g. `craft_wooden_pickaxe` needs `craft_sticks` first AND iron in inventory), planner suggests invoking prerequisites first or chains automatically.

**Effort:** ~120 lines + schema migration v4 → v5. Two new columns on skills. Optional auto-chain mode (off by default; LLM has to ask).

**Why second:** turns "get me a wooden pickaxe" from a 12-step monologue into one invocation that drags along its prerequisite skills.

### T1.3 — DEPS "Explain" step on recurrent failure
**Grounding:** [DEPS (NeurIPS 2023)](https://arxiv.org/abs/2302.01560) §3 — explicit four-stage cycle. We do Describe (context) and Plan (tool calls) and an implicit Select; we skip Explain.

**Change:** when a turn hits ≥3 consecutive tool failures, the next system message is a forced prompt: `"DIAGNOSE before continuing. In one sentence: what is the SHARED root cause of the last 3 failures? Then propose the SIMPLEST test that would confirm the cause."` The LLM is required to chat the diagnosis (so the player can correct it) AND then write a probe via `runOnce` before any new `writeSkill`.

**Effort:** ~25 lines. Detection in planner.ts; injected system message.

**Why third:** cheapest of the three and the most direct way to break the "15 attempts of the same broken pattern" loop we saw with `place_crafting_table_nearby`.

### T1.4 — Plan-visible-to-player (Talking-to-Build pattern)
**Grounding:** [Talking-to-Build (ICMI 2025)](https://arxiv.org/abs/2507.20300): players consistently rated bots higher when the bot announced its plan before acting. Confidence/transparency dominate raw speed.

**Change:** when the LLM is about to invoke 3+ skills in a single turn OR start a multi-turn task (T1.1 goal), force a plan-preview chat first. The LLM produces a short "Here's my plan: 1) X, 2) Y, 3) Z. Should I go?" The player can override with "no, skip Y" before any action runs.

**Effort:** ~30 lines. A small workflow rule + one new injection point.

**Why fourth:** UX win, not capability — but the HCI paper specifically measured that this is what makes a companion feel trustworthy.

### T1.5 — Eval scenario expansion
**Grounding:** [MCU (ICML 2025)](https://arxiv.org/abs/2310.08367): 3,452 atomic tasks with task composition. We can't build 3,452, but the eval surface dictates what bugs we find. Two scenarios = anecdote-driven development.

**Change:** build a 20-scenario suite organized into four families:
- **Progression** (5): oak_log → planks → table → sticks → wooden_pickaxe → cobblestone → stone_pickaxe → iron_ore (the canonical early-game chain)
- **Grounding** (5): deixis tests, pinned-fact recall, episodic recall, ambiguous references that should trigger asks
- **Robustness** (5): adversarial inputs (just "yes", typos, contradicting yourself), failure-recovery scenarios, concurrent turns
- **Conversation** (5): preference setting and recall, multi-turn refinement, persona consistency, after-action explanation

Each gets a JSONL recording + replay. Mock-bot extended where needed (e.g. minimal crafting; minimal furnace).

**Effort:** ~300 lines over a session, mostly scenarios; ~50 lines of mock-bot extensions.

**Why fifth:** unblocks measuring everything else.

## Tier 2 — medium cost, real but secondary value

### T2.1 — Experience-pool memory (state-augmented skill saves)
**Grounding:** [Optimus-1 (NeurIPS 2024)](https://arxiv.org/abs/2408.03615) "Abstracted Multimodal Experience Pool"; [JARVIS-1 (arXiv 2311.05997)](https://arxiv.org/abs/2311.05997) "scenarios + plans" memory module.

**Change:** when a skill verifies, alongside the code we snapshot the world-state context that made it succeed (inventory, position, nearby blocks, time of day). When the LLM searches for a skill, retrieval also surfaces "this worked when you had a stone_pickaxe and were near oak_logs at -120,64,250." Lets the LLM judge applicability beyond just description-match.

**Effort:** ~80 lines + a new `skill_trials` table.

### T2.2 — Periodic world-event feed
**Grounding:** This is what Voyager calls "automatic state report" between iterations. Currently Steve only learns about world changes when he probes. A player who walks to a new biome / dies / loses inventory → Steve doesn't notice until he runs `runOnce`.

**Change:** Mineflayer emits events for `playerCollect`, `entityHurt`, `entityDeath`, `chat` from others, `time` (day/night), etc. We subscribe and accumulate `[ENV]` notes between turns. At the start of the next turn, inject "Since last turn: you took 4 hp damage from a creeper; it's now night." into the context.

**Effort:** ~60 lines.

### T2.3 — Auto-derived tech-tree from minecraft-data
**Grounding:** [Plan4MC](https://arxiv.org/abs/2303.16563)'s skill dependency graph at the *recipe* level — the data is already in `minecraft-data`. We just don't expose it as a tool.

**Change:** new tool `lookupTechTree(item)` that returns the full chain: e.g. `iron_pickaxe → 3x iron_ingot + 2x stick → smelt iron_ore + furnace → cook ... → mine_iron_ore needs stone_pickaxe → ...`. Done as a graph walk over the recipes index. The LLM uses this once to plan a multi-step task, instead of looking up each step.

**Effort:** ~60 lines. Pure knowledge layer extension.

### T2.4 — After-action review (cluster 7)
**Grounding:** [After-Action Review (arXiv 2503.19607, 2025)](https://arxiv.org/abs/2503.19607): LLM-explanation of agent actions post-hoc, for human mental-model alignment.

**Change:** new player command `/explain` or chat trigger "what did you do?" — Steve replays the last turn's trace and explains it in natural language. The transcript is already there; we just need a tool + prompt that surfaces it.

**Effort:** ~30 lines.

## Tier 3 — high impact but blocked by our hardware constraint

### T3.1 — Vision grounding (ROCKET-1 / MineDreamer pattern)
**Grounding:** [ROCKET-1 (CVPR 2025)](https://arxiv.org/abs/2410.17856) — segmentation-mask prompting; +76 pp on open-world tasks. [MineDreamer](https://arxiv.org/abs/2403.12037) — diffusion-imagined visual prompts.

**Why blocked:** gpt-oss:20b is not multimodal. Vision would require either:
- A separate vision-capable LLM running concurrently (LLaVA, Qwen2-VL — adds 7-10GB resident)
- Cloud LLM with vision (violates "no cloud" constraint)
- A vision encoder feeding embeddings to gpt-oss (gpt-oss doesn't have a vision adapter we can wire)

**Lightest path forward (if we ever revisit):** prismarine-viewer is already a dep; we could render the bot's POV to a canvas and ship to a side process running Qwen2-VL-7B (~5GB) only when the player uses spatial deixis Steve can't resolve via raycast. Tight on memory but feasible.

### T3.2 — Multi-agent / collaborative
**Grounding:** [VillagerAgent (ACL 2024)](https://arxiv.org/abs/2406.05720), [MineLand (arXiv 2403.19267)](https://arxiv.org/abs/2403.19267), [TeamCraft (arXiv 2412.05255)](https://arxiv.org/abs/2412.05255).

**Why deferred:** v1 explicitly excluded multi-Steve in [COMPANION_V1_DIRECTION.md](COMPANION_V1_DIRECTION.md) §6. The collaborative-build code from the Forge mod is archived; reviving it is a v3 conversation.

### T3.3 — Code-tuned model for writeSkill
**Grounding:** general post-VLA literature shows code-tuned models (Qwen2.5-Coder-14B, DeepSeek-Coder-V2) outperform general models on JS-API tasks.

**Why deferred:** user explicitly nixed adding a second model. The 40GB RAM target has Minecraft + Ollama + gpt-oss + node_modules competing already.

## Recommended next sprint (~half a day of work)

Pick three from Tier 1 that compound:

1. **T1.3 (DEPS Explain)** — 25 lines, prevents the 15-failure-loop pattern from the playtest. Cheapest single change with concrete observed payoff.
2. **T1.1 (multi-turn goal tracking)** — biggest UX uplift. Steve stops forgetting what you asked.
3. **T1.4 (plan-visible-to-player)** — natural pairing with T1.1: once Steve has a goal, the planner forces him to announce his plan before executing.

Skipping T1.2 (dependency graph) for this sprint — it's higher value but requires the goal-tracking from T1.1 to land first.

Skipping T1.5 (eval expansion) for this sprint — it's prep work; pair it with whichever subsequent T-changes you want to measure.

## Out of scope, explicit and durable

| Direction | Why not |
|---|---|
| Cloud LLMs (OpenAI / Anthropic / Groq) | [v1 direction §6](COMPANION_V1_DIRECTION.md) — local-only is a load-bearing product property |
| Vision / multimodal LLM | Hardware blocked. User confirmed no second model. |
| Multi-agent | v1 scope decision; archived Forge code remains available for v3 |
| RL fine-tuning / VPT-style behavior cloning | Cluster-scale work; not feasible solo |
| World-model neural game engine (Oasis / MineWorld) | Different product entirely; reduces to "Minecraft replacement" not "companion in Minecraft" |
| Pure tool registry of pre-built skills | Survey-recommended approach is Voyager-style code-gen; explicitly NOT hardcoded primitives |

---

## How to drive this

Tier 1 items are each 1–4 hours of careful work. Recommended order:

```
T1.3  →  T1.1  →  T1.4  →  T1.2  →  T1.5  →  Tier 2 items as needed
```

After Tier 1 + T1.5 lands, we'd have:
- Steve recovers from failures with explicit diagnosis instead of brute retry
- Steve tracks player goals across turns
- Steve announces plans before destructive action
- Steve composes skills via declared dependencies
- We can measure all of the above against a 20-scenario suite

Then Tier 2 (experience pool + world event feed + tech-tree) brings memory + situational awareness toward Optimus-1 / JARVIS-1 levels — within our hardware envelope.
