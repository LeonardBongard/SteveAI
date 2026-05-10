# SteveAI Companion v1 — Direction Plan

> **What this document is.** A high-level statement of *what* we are building and *why*, with every concrete dependency named. It is intentionally not a timeline, sprint breakdown, or task list — that comes in a follow-up implementation plan. If a question reads "when do we do X" it does not belong here.

> **What this document is for.** So that when someone (us, future-us, a collaborator) sits down to build, every architectural choice is already justified, every download is named, and the end state is unambiguous.

> **Source of the direction.** Appendix A of [docs/MINECRAFT_AI_RESEARCH_SURVEY.md](MINECRAFT_AI_RESEARCH_SURVEY.md). Read that first if "why this and not Voyager / Optimus-3 / Dreamer 4" is not obvious.

---

## 1. Vision

A Minecraft player-companion that **joins your world as another player**, speaks natural language, **remembers you across sessions**, and **understands what you mean when you point at things**. You log in, Steve joins, you say "hey, mine that iron over there," and Steve mines that specific iron. Tomorrow you log back in and Steve still knows what you asked for, what you don't like, and where the dangerous cave is.

The product is not a tech-tree-completing benchmark agent. It is a *companion* — measured by whether you keep playing with it, not by ObtainDiamond numbers.

---

## 2. Why this direction

Three findings from the survey collapse to one direction:

1. **LLM-prompted skill-library agents are commoditized at the academic frontier**, but no public companion-product nails the integration. Voyager, GITM, Odyssey, Optimus-3, etc. all chase capability benchmarks; the only HCI-measured Minecraft companion paper to date is [Talking-to-Build (ICMI 2025)](https://arxiv.org/abs/2507.20300), and it shows that even a basic LLM interface significantly improves player experience — meaning the bar is much lower than the research literature suggests.
2. **Memory and spatial reference are the two systems where every successful 2024–2025 agent invests, but neither is treated as a product feature in the open-source companion space** (e.g. MindCraft has neither cross-session memory nor "that thing" resolution). [MrSteve (ICLR 2025)](https://arxiv.org/abs/2411.06736) makes the case explicitly: missing episodic memory is the dominant low-level failure mode.
3. **Real-time, single-GPU local inference is now viable.** GPT-OSS-20B's release in August 2025 puts a frontier-tier reasoning model on consumer hardware via Ollama. We do not need API budgets to ship.

So the bet: build a **companion-shaped product** on a **commoditized agent stack** with **two well-engineered differentiators (memory + spatial grounding)**, validated by **HCI metrics not benchmarks**, on **local inference**.

---

## 3. Architecture (the substrate is changing)

### 3.1 Substrate: Mineflayer (Node.js / TypeScript)

We are leaving the Forge mod. The new bot connects to a normal Minecraft Java server as a player.

**Why pivot, restated concretely:**
- The Forge mod represents Steve as a *custom mob entity* (`PathfinderMob`). The companion ought to feel like a *friend on the same server*, not a pet that follows you. Mineflayer's "Steve is another player" model is a closer fit to the product vision.
- Mineflayer eliminates ~6,000 lines of Java that we no longer need: custom entity registration, packet shims, network sync, behavior schedulers, mod-loader plumbing. The runtime cost (one Node process talking to the server's network protocol) is negligible.
- Iteration speed goes from "edit Java → rebuild mod → restart client" to "edit TypeScript → bot reconnects in 2s." This matters more than any tight-game-integration argument the Forge mod can make.
- The closest cultural analogues for a Minecraft companion ([MindCraft](https://github.com/mindcraft-bots/mindcraft) and friends) all use this substrate. Sharing infrastructure with that ecosystem is a force multiplier.

**Trade we accept:** we lose direct rendering hooks, custom on-screen UI, and access to client-only state. Vision-grounding will run off either bot-side raycasts (v1) or external rendering (v2 if we need it). See §3.4.

### 3.2 LLM: Ollama + `gpt-oss:20b` (primary), `gpt-oss:120b` (optional ceiling)

- **Default model:** [`gpt-oss:20b`](https://ollama.com/library/gpt-oss). 21B params (3.6B active, MoE), Apache 2.0, released by OpenAI Aug 2025. Tuned for agentic / reasoning workloads with native chain-of-thought, supports tool-calling. Runs comfortably on a 16GB GPU or M-series Mac with unified memory. This is the brain.
- **Optional ceiling:** `gpt-oss:120b` for users with 64GB+ VRAM / 128GB Macs who want maximum planning quality.
- **Embeddings:** [`nomic-embed-text`](https://ollama.com/library/nomic-embed-text) via Ollama. 137M params, fast, used for memory similarity search.
- **No cloud LLM is in v1.** Easy to add later, but the full companion experience must work fully offline. This is a non-negotiable design constraint, not a fallback.
- **Keep the model warm.** `OLLAMA_KEEP_ALIVE=24h` (or `-1` for indefinite) on the Ollama daemon so the 20B model is not reloaded between turns. First cold-start is 5–15 s; subsequent calls reuse the KV cache for the system-prompt prefix and become sub-second. This is the single biggest latency lever we have. (Engineering, not research, but it directly addresses the real-time constraint flagged in survey §9.4.)
- **Use gpt-oss's native tool-calling format**, not ad-hoc JSON. The model is post-trained for the OpenAI harmony / tool-call schema; sticking to it eliminates a whole class of "LLM emitted slightly-wrong JSON" failures the academic LLM-agent papers spend pages mitigating.

### 3.3 Memory layer (the first differentiator)

Three memory subsystems, all persisted to local disk so they survive restarts and Ollama restarts:

| Memory | What it stores | When it's read | When it's written |
|---|---|---|---|
| **Episodic place memory** | (what, where, when) tuples: ~50 bytes per event. The exact representation [MrSteve, ICLR 2025](https://arxiv.org/abs/2411.06736) §3 introduced as Place Event Memory. ~100× more compact than storing transcripts. | When the agent needs to recall a location ("the cave you found yesterday"). | On every notable event: arriving somewhere new, completing a task, dying, finding a rare resource. |
| **Conversational memory** | Sliding window of last N turns verbatim **+ pinned facts** the LLM extracts once ("user prefers oak", "user's base is at -123 64 456") **+ vector retrieval** over older turns. **No periodic LLM summarization** — summarization loses specifics; this stack does not. | Every plan: last N + all pinned facts + top-K retrieved older turns. | Every chat turn (window); on first mention of a stable preference (pinned facts); embedded immediately on every turn (retrieval index). |
| **Playbook of recipes** | Named natural-language recipes: "to craft iron tools, first…". Captured **hindsightly** from successful trajectories — never speculatively. The bot does the thing; *if it worked*, the LLM names and one-line-describes the action sequence; the recipe is filed. | When the planner asks "have I done something like this before?" | Only after a verified-successful trajectory. One LLM call per real success — never on speculation. |

**Storage stack:** SQLite via `better-sqlite3` for durable metadata + [`sqlite-vec`](https://github.com/asg017/sqlite-vec) for vector similarity. One file in `./data/memory.db`, easy to inspect, easy to back up, no separate server. (We do not need Chroma/LanceDB for this scale.)

**Retrieval pattern (tiered, cheap-first):** embedding similarity returns top-K candidates → optionally an LLM rerank only over those K → full LLM thought only on confirmed hit. This way, *most* memory accesses never load the LLM context at all. Pattern lifted from JARVIS-1 / Optimus-1's retrieval architectures.

**Why no skill library in the Voyager sense:** Voyager grew JS skill snippets because its action substrate was a generic JS interpreter — every capability had to be invented from scratch. We have Mineflayer's curated, typed action API for free (pathfinder, collectblock, dig, place, etc.), so the LLM does not need to *write code* to gain capabilities. The "playbook" above is the right shape for our substrate: capture-what-worked, not invent-what-might-work. Saves ~50% of Voyager's per-task LLM cost and removes a major bug class (LLM-written code that compiles but does the wrong thing).

### 3.4 Spatial-grounding layer (the second differentiator)

The thing that makes "mine *that* ore" work without computer vision.

**v1 approach: spatial reference resolved against Mineflayer's structured world state.** This is broader than raycast — it covers most natural ways a player refers to things — and none of it needs pixels.

| Player phrase | How we resolve it | Cost |
|---|---|---|
| *"that ore"*, *"this tree"*, *"the X I'm looking at"* | Raycast from the *player's* eye position + look direction (`bot.players[name].entity`) up to 64 blocks; first non-air hit. | ms |
| *"over there"* | Same raycast, but the target is the floor block where their cursor lands. | ms |
| *"the closest tree"*, *"the nearest iron"* | `bot.findBlock({ matching, maxDistance })`. | ms |
| *"behind me"*, *"30 blocks north"* | Player position + relative offset. | µs |
| *"this thing in my hand"* | Read the player's held item via the entity equipment slot. | µs |
| *"the chest you opened earlier"* | Episodic place memory lookup. | sub-ms |
| *"my house"*, *"the cave from yesterday"* | Pinned-fact lookup in conversational memory. | sub-ms |

**Confirm before acting.** After the bot resolves a referent, it echoes back in chat: `"the iron ore at (32, 64, -120)? mining now"` and acts. This eliminates the entire "meant the *other* iron ore" failure class at zero ML cost, and matches the explicit player-experience finding in [Talking-to-Build (ICMI 2025)](https://arxiv.org/abs/2507.20300): transparency and predictability dominate raw response speed for player satisfaction. We are choosing to *spend* a fraction of a second on confirmation in exchange for a large drop in trust-breaking errors.

**v2 augmentation, deferred:** for "the cathedral I built", "does this look nice", or "what's that thing in the distance" — i.e. queries that genuinely need pixels — the path is [`prismarine-viewer`](https://github.com/PrismarineJS/prismarine-viewer) (renders the bot's POV to a canvas) plus [SAM-2](https://github.com/facebookresearch/sam2) for segmentation and optionally MineCLIP for text-frame matching. **Not in v1.** v1 ships without any image model.

### 3.5 Action layer

Mineflayer's high-level API plus a small set of curated tools:

- **Movement:** `mineflayer-pathfinder` for goto/follow/avoid.
- **Block ops:** `mineflayer-collectblock`, `bot.dig`, `bot.placeBlock`.
- **Inventory:** `bot.tossStack`, `bot.equip`, smelting / crafting via the inventory windows API.
- **Combat:** `bot.attack`, basic threat avoidance.
- **Chat:** `bot.chat()` for player-facing communication (this is the agent's primary modality).
- **World queries:** `bot.findBlock`, `bot.entities`, `bot.inventory.items()` exposed as LLM tool calls (see active perception below).

We deliberately do not rebuild safety / behavior schedulers / interceptor chains. The Forge mod's elaborate `SafetyEvaluatorManager` was scaffolding for an architecture we're walking away from.

**Active perception by default — pass minimal world state.** Per turn, the LLM gets only: bot position, last few chat turns, top-K relevant memory hits, and the current high-level intent. *Inventory, nearby blocks, entity list, biome, time-of-day* are exposed as **tool calls** the LLM invokes when it actually needs them. This pattern collapses the typical per-turn prompt from ~5–10K tokens to <1K on routine turns. Justification: [MP5 (CVPR 2024)](https://arxiv.org/abs/2312.07472) demonstrated goal-conditioned active perception specifically for Minecraft; [GITM](https://arxiv.org/abs/2305.17144) hit competitive ObtainDiamond numbers using *only* text-state world description on a single CPU node, which is the upper bound on how cheap structured-world-state planning can be.

**Control loop — intent-based, not action-by-action.** The LLM plans an *intent* (3–10 actions), the bot executes them autonomously, and the LLM only re-engages on:
1. **Success** — file the recipe (§3.3 playbook), report to player, await next chat.
2. **Failure** — inject the failure cause as context for the *next* LLM call, *do not full-replan*. Style after [DEPS (NeurIPS 2023)](https://arxiv.org/abs/2302.01560)'s describe-explain-plan-select pattern: the LLM gets `"step 3 failed because the iron block was already mined by another player; world state now: …"` and patches the plan instead of throwing it away. This collapses a typical multi-step task from N LLM calls (one per action) to ~1–2 (one plan + one optional patch).
3. **New player chat** — re-engages immediately to acknowledge / replan around the new input.
4. **Genuine novelty** — e.g. an unexpected hostile mob, a dangerous biome boundary. Use a small fixed list of triggers; do not build a generalized novelty detector.

**Loop guards** (already encoded in `docs/MODULARITY_RULES.md`): if the same action fails 3× in a row, abort and ask the player. Cheap; cuts the worst long-tail spirals.

### 3.6 HCI evaluation harness (the third differentiator)

A scriptable scenario runner that sits next to the bot. Each scenario:

- Spawns the bot into a fresh world or a known save.
- Sends scripted player chat.
- Records each step as a `(player_message, world_state_diff, bot_action, llm_call_metadata)` tuple to a JSONL file.
- Records: time-to-first-action, action sequence, final world diff, conversation transcript, whether the goal was achieved.
- Optionally pauses at the end of a scenario and asks the human evaluator to score on a 1–7 Likert scale: helpfulness, agency, predictability, "would you keep playing with this".

**Replay mode.** Scenarios run a second time deterministically from the recorded JSONL, with the bot's actions and world diffs replayed in lockstep but **without invoking the LLM**. Action / grounding / memory regressions show up as divergence between the original tuple stream and the replay; planner regressions are a separate test. This pattern cuts eval flake to near-zero and makes the scenarios reproducible on a laptop in seconds rather than minutes.

We run this in CI against every meaningful change. The qualitative Likert scores beat tech-tree numbers; the deterministic replay catches regressions in the pieces that should not have changed.

### 3.7 Cross-cutting efficiency principles (the ledger of "80% trades")

Every per-subsystem optimization above is one instance of a smaller set of cross-cutting principles. Listed here so future contributors understand *why* the architecture has its shape — and so we have a checklist when a new feature is proposed.

| # | Principle | Where it lands above | What it saves vs. the obvious approach | Survey reference |
|---|---|---|---|---|
| 1 | **Spatial reference via Mineflayer state, not vision.** | §3.4 | No SAM-2 / MineCLIP / pixel rendering required for ~80% of player references. | [ROCKET-1, CVPR 2025](https://arxiv.org/abs/2410.17856) — the value is *precise reference*, not pixels per se. We get precise reference for free from the game state. |
| 2 | **Hindsight skill capture, not speculative skill generation.** | §3.3 playbook | One LLM call per *real* success, never on speculation. | [STEVE-1, NeurIPS 2023](https://arxiv.org/abs/2306.00937) used hindsight relabeling for the entire model in $60 of compute. |
| 3 | **Active perception: minimal world-state context by default; LLM tool-calls for more.** | §3.2, §3.5 | Per-turn prompt collapses ~5–10× on routine turns. | [MP5, CVPR 2024](https://arxiv.org/abs/2312.07472); [GITM](https://arxiv.org/abs/2305.17144) reached competitive numbers on text-only state on a single CPU node. |
| 4 | **DEPS-style failure injection, not full replanning.** | §3.5 | One LLM call per failure, not a full re-prompt; preserves plan structure. | [DEPS, NeurIPS 2023](https://arxiv.org/abs/2302.01560). |
| 5 | **Confirm-before-act on deixis.** | §3.4 | Eliminates an entire failure class at zero ML cost. | [Talking-to-Build, ICMI 2025](https://arxiv.org/abs/2507.20300): transparency / predictability dominate raw speed for player satisfaction. |
| 6 | **Tiered memory retrieval.** Embedding rank → optional LLM rerank → full thought only on hit. | §3.3 | Most accesses never load the LLM context. | Standard RAG, used implicitly in [JARVIS-1](https://arxiv.org/abs/2311.05997) / [Optimus-1](https://arxiv.org/abs/2408.03615). |
| 7 | **Episodic memory as (what, where, when) tuples.** | §3.3 | ~50 bytes per event vs. ~1 KB for raw transcripts. ~100× compression. | [MrSteve, ICLR 2025](https://arxiv.org/abs/2411.06736) §3 PEM. |
| 8 | **Sliding window + pinned facts, not LLM-summarized memory.** | §3.3 | Preserves specifics that summarization throws away; cuts an LLM-call-per-summary. | Convergent best practice in modern agent-memory work; not a single-paper claim. |
| 9 | **Keep Ollama warm; KV-cache the system-prompt prefix.** | §3.2 | Cold-start (5–15 s) hits at most once per session; subsequent calls reuse the prefix. | Engineering. Addresses the real-time constraint flagged in survey §9.4. |
| 10 | **Use gpt-oss native tool-calling, not ad-hoc JSON.** | §3.2, §3.5 | Eliminates the "model emits slightly-broken JSON" failure mode. | gpt-oss is post-trained for the OpenAI harmony format. |
| 11 | **Replay-mode deterministic eval.** | §3.6 | Regressions in non-LLM components detectable in seconds, no LLM calls needed. | Engineering. |
| 12 | **Lift MindCraft's stable abstractions; replace only what they do badly (memory).** | §3.5, profile system | Don't re-derive what the closest practical analogue already debugged. | [MindCraft source](https://github.com/mindcraft-bots/mindcraft). |

Patterns we **explicitly defer**:

- **DAG task graphs** ([VillagerAgent ACL 2024](https://arxiv.org/abs/2406.05720), [CausalMACE EMNLP 2025](https://arxiv.org/abs/2508.18797)): heavy for single-companion v1; revisit only if subtask-dependency bugs become a real problem.
- **Compositional pre-built skill graphs** ([Plan4MC](https://arxiv.org/abs/2303.16563), [Odyssey](https://arxiv.org/abs/2407.15325)): the playbook *grows* via principle #2 (hindsight capture) instead.
- **Reflexion-style multi-step self-critique loops**: principle #4 (failure injection on the very next turn) is the cheap variant; if it isn't enough, *then* consider Reflexion.
- **Voyager-style auto-curriculum**: not a v1 differentiator. Player chat is our curriculum.

---

## 4. Dependencies — exactly what to download

### 4.1 Runtime
- **[Node.js](https://nodejs.org/) 20 LTS or 22 LTS** — installed system-wide.
- **[pnpm](https://pnpm.io/) 9** — package manager, faster than npm; optional but recommended.
- **[Ollama](https://ollama.com/download)** — local LLM runtime. macOS / Linux / Windows installers.

### 4.2 Models (via Ollama, after install)
```
ollama pull gpt-oss:20b          # ~12 GB on disk; primary planner
ollama pull nomic-embed-text     # ~270 MB; embeddings for memory
ollama pull gpt-oss:120b         # OPTIONAL, ~65 GB, for high-VRAM users
```

Set `OLLAMA_KEEP_ALIVE=24h` (or `-1`) on the Ollama daemon so the model stays loaded between turns. This is the single most important latency setting (§3.2 / §3.7 #9).

### 4.3 Minecraft server
- **[PaperMC](https://papermc.io/) 1.21.x server jar** — drop-in upgrade over vanilla, faster, plugin-compatible. Run locally for development.
- **Or** a Fabric / vanilla 1.21.x server. Both are fine; Mineflayer supports either.
- **Java 21** — to run the server. Already a project requirement.
- A `server.properties` configured with `online-mode=false` for local development convenience.

### 4.4 Node.js dependencies (the manifest, with rationale)
| Package | Why |
|---|---|
| [`mineflayer`](https://github.com/PrismarineJS/mineflayer) | Bot core: connects to the server, exposes world / actions. |
| [`mineflayer-pathfinder`](https://github.com/PrismarineJS/mineflayer-pathfinder) | A* pathfinding. Don't roll our own. |
| [`mineflayer-collectblock`](https://github.com/PrismarineJS/mineflayer-collectblock) | Resilient block collection w/ tool selection. |
| [`prismarine-block`](https://github.com/PrismarineJS/prismarine-block), [`prismarine-item`](https://github.com/PrismarineJS/prismarine-item) | Typed block / item registries. |
| [`vec3`](https://github.com/PrismarineJS/node-vec3) | 3D vector math used everywhere by Mineflayer. |
| [`ollama`](https://github.com/ollama/ollama-js) | Official Node client for the local Ollama HTTP API. |
| [`better-sqlite3`](https://github.com/WiseLibs/better-sqlite3) | Synchronous, fast SQLite for memory persistence. |
| [`sqlite-vec`](https://github.com/asg017/sqlite-vec) | Vector-similarity extension for SQLite. |
| [`zod`](https://github.com/colinhacks/zod) | Runtime schema validation (LLM JSON outputs especially). |
| [`pino`](https://github.com/pinojs/pino) | Structured logging — keep the existing `[FARM]` / `[SAFETY]` prefix discipline. |
| [`vitest`](https://vitest.dev/) | Tests, including the eval harness. |
| [`tsx`](https://github.com/esbuild-kit/tsx) | TypeScript runner for dev. |
| TypeScript, eslint, prettier | Dev tooling baseline. |

### 4.5 Optional / v2-only (do not install in v1)
- `prismarine-viewer` — bot POV rendering (only when we add image models).
- SAM-2 weights, MineCLIP weights, a Python sidecar — only when we move past raycast grounding.

---

## 5. End state — what we will have when v1 is done

A directory in this repo (or a sibling repo, see §7) that, after `pnpm install` and `ollama pull gpt-oss:20b`, ships the following:

```
steveai-companion/
├── README.md                 # how to run, the 5-minute pitch
├── package.json
├── tsconfig.json
├── .env.example              # server host/port, model name, OLLAMA_KEEP_ALIVE, etc.
├── src/
│   ├── bot.ts                # main entry: connect, register handlers, run
│   ├── llm/
│   │   ├── ollama.ts         # warm-keep + KV-cache-aware client
│   │   ├── prompt.ts         # system prompt (cached prefix); per-turn assembly
│   │   └── tools.ts          # gpt-oss-native tool schemas (Zod → JSON Schema)
│   ├── memory/
│   │   ├── store.ts          # better-sqlite3 + sqlite-vec
│   │   ├── episodic.ts       # (what, where, when) place/event memory (PEM)
│   │   ├── conversation.ts   # sliding window + pinned facts + retrieval — NO LLM summarization
│   │   └── playbook.ts       # hindsight-captured NL recipes, NOT learned skill code
│   ├── grounding/
│   │   ├── reference.ts      # resolves any spatial reference against bot/player/world state
│   │   ├── raycast.ts        # the "that / over there" subset
│   │   └── confirm.ts        # produces the chat-back confirmation before action
│   ├── perception/
│   │   └── tools.ts          # active-perception tools the LLM can call (inventory/nearby/entities/biome)
│   ├── actions/
│   │   ├── move.ts           # pathfinder wrappers
│   │   ├── mine.ts           # collectblock wrappers
│   │   ├── place.ts
│   │   ├── chat.ts
│   │   └── ...
│   ├── planner.ts            # intent-based loop: plan → execute → on-fail inject cause → patch
│   └── log.ts                # pino setup, [PLAN]/[ACT]/[MEM]/[GROUND]/[EVAL] tags
├── eval/
│   ├── runner.ts             # scenario runner; records JSONL tuples
│   ├── replay.ts             # deterministic replay over the JSONL, no LLM calls
│   └── scenarios/
│       ├── 01-fetch-logs.ts
│       ├── 02-mine-that-ore.ts            # spatial deixis
│       ├── 03-craft-iron-tools.ts
│       ├── 04-cross-session-memory.ts
│       └── 05-build-small-cabin.ts
├── data/                     # gitignored — runtime memory lives here
│   └── .gitkeep
└── tests/                    # vitest unit tests
```

(Scenarios are TypeScript, not YAML — gives type-checked helpers and IDE completion at the cost of one extra dep already on the list.)

**Running it looks like:**

```bash
# one time
ollama pull gpt-oss:20b
ollama pull nomic-embed-text
pnpm install

# every session
pnpm start
# → bot joins your local server as "Steve", prints log lines on every chat / action
# → you log into the same server in your client, type in chat: "hey Steve, mine that iron over there"
# → Steve walks to the iron block you're looking at and mines it
```

**Eval looks like:**

```bash
pnpm eval                                  # runs all scenarios
pnpm eval -- --scenario 04                 # runs cross-session-memory only
pnpm eval -- --scenario 02 --interactive   # pauses for human Likert scoring
```

**What "done" means for v1, in five concrete checks:**

1. Steve joins your server, says hi, accepts a chat command, executes it.
2. Steve correctly resolves "that" / "this" / "over there" via raycast in scenario 02.
3. Memory persists: scenario 04 passes — Steve answers a question in session B about an event from session A.
4. The eval harness produces a quantitative report (success rate per scenario, time-to-first-action, latency-per-LLM-call) and an optional qualitative one (Likert scores).
5. The thing runs end-to-end on a 16GB GPU or a 32GB Apple Silicon Mac with no API key configured.

---

## 6. Non-goals (explicit, so scope creep doesn't seep in)

- **No tech-tree benchmarks.** ObtainDiamond is not a v1 success criterion. We will not optimize for it.
- **No multi-Steve coordination.** The CollaborativeBuildManager and friends are out of v1 scope and will be archived (§7).
- **No image models.** No SAM-2, no MineCLIP, no pixel rendering of the bot's POV for the agent. Spatial grounding is structured-state-based (§3.4).
- **No cloud LLMs.** No OpenAI / Groq / Gemini in v1. Ollama-only.
- **No custom safety / behavior subsystems.** The Forge mod's `SafetyEvaluatorManager`, `BehaviorScheduler`, interceptor chains, etc. are all going away; we do not reproduce them. Loop guards from `docs/MODULARITY_RULES.md` are sufficient.
- **No new training.** No fine-tuning, no LoRA, no behavior cloning. Pure prompt-and-tool-use against `gpt-oss:20b`.
- **No Voyager-style learned skill code.** The "playbook" (§3.3) is hindsight-captured NL recipes, not LLM-written executable JS. We have Mineflayer's typed action API; we do not need the LLM to invent code.
- **No LLM-summarized conversation memory.** Sliding window + pinned facts + retrieval (§3.3). LLM summarization loses the specifics that make a companion feel like it remembers you.
- **No DAG task graphs / causal models.** [VillagerAgent](https://arxiv.org/abs/2406.05720) and [CausalMACE](https://arxiv.org/abs/2508.18797) are out of scope; revisit only if subtask-dependency bugs become real.
- **No Reflexion-style multi-step self-critique loop.** DEPS-style next-turn failure injection (§3.5 / 3.7 #4) first; if that's not enough, *then* Reflexion.
- **No auto-curriculum.** Voyager's curriculum exists because there's no human in the loop. We have a human player; their chat *is* the curriculum.
- **No long-running persistent agent service (yet).** The bot runs while you're playing; it doesn't run 24/7 doing things in your absence.
- **No voice input.** Whisper-based voice is a v2 candidate, not v1. Chat is the input.
- **No web UI / dashboard.** Logs and scenario reports are CLI / files. A nice UI is later.

---

## 7. What happens to the existing Forge mod

The Forge mod is not deleted. It is preserved on a branch + tagged so we can revisit it if the Mineflayer pivot disappoints.

- **Branch:** `legacy/forge-mod` (created from current `main` HEAD before the pivot starts).
- **Tag:** `v0.x-forge-final` at the same commit, so it's findable by tag forever.
- **In `main`,** the Java sources move under `legacy/forge-mod/` (a directory) and are excluded from the new build. We keep them around for reference (the safety/behavior code, even if we're not reusing it, has captured real game-mechanic knowledge), and the README points there.

We do **not** keep the Forge mod actively maintained. No PRs, no version bumps. It is an archive.

The `docs/` directory stays. The survey, this document, the Scrum / modularity / logging rule documents — all still apply to the new codebase. The structured-logging tag policy (`[FARM]`, `[SAFETY]`, `[FOOD]`, `[SMELT]`, `[SWIM]`) carries over to the Node bot via `pino`.

---

## 8. What this document does not commit to

This is the high-level direction. It deliberately leaves *open*:

- **Exact file-by-file implementation order.** That's the next plan.
- **Prompt design.** The system prompt, the tool schemas, few-shot examples — all to be designed in the implementation plan.
- **Skill-library taxonomy.** What the Voyager-style learned skills look like as code shape.
- **Scenario authoring DSL.** Whether scenarios are YAML, TypeScript, or something else.
- **CI / packaging.** How we ship a build artifact, if we ship one at all.
- **Telemetry / privacy.** Whether the eval harness sends anything anywhere.

Each of these has a defensible default; we're choosing not to commit to defaults in this document because that's a different discussion. Open them in the implementation plan.

---

## 9. Reading checklist before the next plan

When we write the implementation plan, the author should have read:

- This document, top to bottom — *especially* §3.7 (the optimization ledger).
- [docs/MINECRAFT_AI_RESEARCH_SURVEY.md](MINECRAFT_AI_RESEARCH_SURVEY.md) — at least Appendix A and the cross-cutting design axes section (§9 of the survey).
- The [Mineflayer README](https://github.com/PrismarineJS/mineflayer) and the [pathfinder plugin docs](https://github.com/PrismarineJS/mineflayer-pathfinder).
- [MindCraft's source](https://github.com/mindcraft-bots/mindcraft) — read `src/agent/agent.js` and `src/agent/coder.js` once. Their stable abstractions are what we lift; their architectural mistakes (no cross-session memory, no spatial grounding) are exactly the gaps we're filling.
- [MrSteve's paper](https://arxiv.org/abs/2411.06736) §3 — the (what, where, when) Place Event Memory representation. This is the memory model.
- [DEPS (NeurIPS 2023)](https://arxiv.org/abs/2302.01560) §3 — the describe-explain-plan-select pattern that grounds our failure-injection control loop.
- [MP5 (CVPR 2024)](https://arxiv.org/abs/2312.07472) §3 — the active-perception decomposition into "what info do I need next?" tool calls.
- [STEVE-1 (NeurIPS 2023)](https://arxiv.org/abs/2306.00937) §3 — for hindsight relabeling; the playbook (§3.3) is the moral analogue.
- The [`gpt-oss` model card](https://openai.com/index/introducing-gpt-oss/) — for the tool-calling / harmony format the model is post-trained for.
- [Voyager's GitHub](https://github.com/MineDojo/Voyager) — for context on what we are intentionally *not* doing (auto-curriculum, JS-skill-generation), and why.

That is the entire prerequisite reading list. After it, the implementation plan should be writable in one sitting.
