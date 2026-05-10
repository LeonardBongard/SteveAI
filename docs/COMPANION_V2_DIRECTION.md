# SteveAI Companion v2 — Direction Plan

> **Status:** supersedes [COMPANION_V1_DIRECTION.md](COMPANION_V1_DIRECTION.md) §§3.3 + 6 + parts of 3.5. Everything else (substrate, memory, grounding, evaluation harness, hardware budget) carries forward unchanged.
>
> **Why v2 exists:** v1 explicitly rejected Voyager-style code-generation in favor of hand-wrapped LLM tool calls. The survey's Appendix A actually recommended **Cluster 1's LLM-prompted skill-library architecture**, of which Voyager is the headline result. v1 walked away from that. v2 walks it back. After running the v1 eval (5/5 pass) we hit the consequence of the v1 decision live: the bot's capability ceiling = whatever we hand-wrote. v2 makes the bot's capability ceiling = whatever the LLM can compose.
>
> **Reading order:** v1 first (vision, hardware, dependencies stay valid), then this document for what changes.

---

## 1. The pivot in one sentence

**The skill library stops storing natural-language descriptions and starts storing executable JavaScript that the LLM has written, retrieved, and proven works.** Every action capability the bot has — mining, crafting, fishing, smelting, farming, fighting — comes from the LLM writing skill code at runtime, not from us pre-wrapping a tool. The skill library starts empty.

This is what [Voyager (arXiv 2305.16291)](https://arxiv.org/abs/2305.16291) actually does. Cluster 1 of the survey. The v1 plan's argument that "Mineflayer's typed API is complete enough" was wrong — the API exists; LLM-callable wrappers don't, until somebody writes them. v1's choice was: humans write them. v2's choice: the LLM writes them.

## 2. What stays from v1

Unchanged and load-bearing:

- **Substrate** — Mineflayer + Node.js + TypeScript. Local server.
- **LLM** — Ollama + `gpt-oss:20b` primary, `gpt-oss:120b` ceiling, `nomic-embed-text` for embeddings. Same warm + KV-cache configuration. Same `OLLAMA_KEEP_ALIVE=30m` default.
- **Memory layer** — episodic place memory (MrSteve PEM), pinned facts, sliding window + vector retrieval. No LLM-summarized memory. ([memory/store.ts](../steveai-companion/src/memory/store.ts), [memory/episodic.ts](../steveai-companion/src/memory/episodic.ts), [memory/conversation.ts](../steveai-companion/src/memory/conversation.ts) — all stay.)
- **Grounding** — raycast + nearest + held-item + relative offset + pinned-fact recall + episodic recall + chat-back confirmation. ([grounding/raycast.ts](../steveai-companion/src/grounding/raycast.ts), [reference.ts](../steveai-companion/src/grounding/reference.ts), [confirm.ts](../steveai-companion/src/grounding/confirm.ts) — all stay.)
- **Eval harness** — scenario runner + JSONL recordings + replay. ([eval/runner.ts](../steveai-companion/eval/runner.ts), [eval/replay.ts](../steveai-companion/eval/replay.ts) — both stay; new scenarios on top.)
- **Hardware budget** — same M3 Pro/Max-class target.
- **Single-companion v1 scope decision** — no multi-Steve; carries over.

## 3. What changes

### 3.1 The action tools (`actions/handlers.ts`) get archived

The hand-wrapped action tools — `goto`, `mineBlock`, `placeBlock`, `equipItem`, `followPlayer`, `stop`, `sayToPlayer` — were the implementation of v1's "no code-gen" decision. They are removed from the live tool registry and archived in `legacy/v1-action-tools/`. The Mineflayer functions they wrap are still used — they're just used **inside** skill code, not as LLM tools.

The cognitive equivalent for the LLM:

- *v1:* "I want to mine the iron at (10, 65, 5). I'll call the `mineBlock` tool."
- *v2:* "I want to mine the iron at (10, 65, 5). I'll search for an existing skill. None? Then I write one — `mine_block_at_position(bot, x, y, z)` — and invoke it."

### 3.2 Six new tool primitives that ARE the v2 surface

Deliberately small. None are pre-baked behaviors; all are meta-level operations:

| Tool | Purpose |
|---|---|
| `chat(message)` | Direct in-game chat. The one player-facing I/O. |
| `writeSkill(name, description, code)` | Save a new skill. `code` is the body of an async function `(bot, args) => …`. Persists into the skill library after a single test invocation succeeds. |
| `invokeSkill(name, args?)` | Run a skill from the library by name. |
| `searchSkill(query)` | RAG over the skill library by description embedding. Returns top-K candidates. |
| `runOnce(code)` | Execute one-shot Mineflayer code without saving it. For exploration, sanity checks, and getting state. |
| Memory ops | `pinFact`, `recallPinnedFacts`, `recordEpisodicEvent`, `recallEpisodes` — unchanged from v1. |

Plus the **knowledge RAG** (§3.4 below) which adds 5 more, all read-only:

| Tool | Purpose |
|---|---|
| `lookupRecipe(itemName)` | Forward: ingredients + crafting pattern + station. |
| `lookupMob(mobName)` | Spawn conditions, drops with probabilities, AI behavior. |
| `lookupBlock(blockName)` | Hardness, harvest tools, light, drops. |
| `findRecipesContaining(itemName)` | Reverse: which recipes use this item. |
| `findMobsDropping(itemName)` | Reverse: which mobs drop this item. |

**Total LLM tool count: 6 meta + 5 knowledge + 4 memory ≈ 15.** Down from v1's 18, and the *kind* of thing each tool is has changed: none of them are behaviors.

### 3.3 The skill sandbox

Skills are async JavaScript functions executed in a `node:vm` context with:

- `bot` — proxy over the Mineflayer Bot. Exposes a curated method surface (everything the LLM legitimately needs); explicitly does NOT expose `bot.quit`, `bot._client.write`, etc. — the destructive escape hatches.
- `Vec3` — from `vec3`.
- `{ goals, Movements }` — from `mineflayer-pathfinder`.
- `console` — for in-skill debug (logs go through pino with the `[SKILL]` tag).
- A 30-second per-invocation timeout.

`vm` is **not** a security sandbox against an adversarial process — it shares the V8 heap. It IS sufficient for accidental-bug containment and that's our actual threat model: gpt-oss isn't malicious, it's sometimes wrong. We document the limitation and accept it for the local-companion use case.

A skill written by the LLM looks like:

```javascript
// LLM writes this through writeSkill.
// name: "mine_one_oak_log"
// description: "Find the nearest oak_log within 32 blocks, walk to it, and dig it."
async function mine_one_oak_log(bot) {
  const log = bot.findBlock({
    matching: bot.registry.blocksByName.oak_log.id,
    maxDistance: 32,
  });
  if (!log) throw new Error('no oak_log within 32 blocks');
  await bot.pathfinder.goto(new goals.GoalGetToBlock(log.position.x, log.position.y, log.position.z));
  await bot.dig(log);
}
```

The runtime wraps this code such that the last expression is the function reference; `vm.runInNewContext` returns it; the planner awaits `fn(bot, args)`.

### 3.4 The knowledge RAG (the load-bearing piece)

The LLM **must not hallucinate** "fishing rod = 2 sticks + 1 string." Wrong recipes that get hindsight-captured into skills become permanently wrong skills. The RAG is the ground truth.

**Data source:** [`minecraft-data`](https://github.com/PrismarineJS/minecraft-data) — already a transitive dep. Ships per-version JSON for:

- `recipes.json` — every crafting recipe with ingredients + pattern + table
- `mobs.json` + loot tables — drops with probabilities + count ranges + spawn conditions
- `blocks.json` — hardness, light, drops, harvest tools
- `items.json` — stack sizes, food values, durability

We do not scrape the wiki. minecraft-data is structured and version-pinned to whatever the connected server runs.

**At startup:** load minecraft-data for the connected MC version, build:

1. **Forward indexes:** plain hash maps keyed by canonical name.
2. **Reverse indexes:** "items used in recipes" → list of recipes; "mobs dropping X" → list of mobs.
3. **Vector index over short descriptions** (one short text per item/mob/block) for fuzzy lookup like `searchKnowledge("something that drops at night")`.

The five lookup tools (§3.2) are thin wrappers over these indexes — sub-millisecond lookups, no LLM call needed.

**Convention reinforced in the system prompt:** "Before writing a `craft_*` skill, you MUST consult `lookupRecipe` first." This isn't enforceable in code, but the convention is heavily prompted and the skill-library naming convention nudges the LLM toward it.

### 3.5 Auto-injected per-turn bot state

Voyager doesn't make the LLM write a skill just to ask "what's in my inventory?" Each turn includes a compact bot-state snapshot in the system context:

```
[BOT STATE]
position: (12, 64, 5) overworld
health: 18/20  food: 14/20
inventory: 2x oak_log, 4x dirt, 1x stone_pickaxe
nearby: TestPlayer (3m), spider (12m)
time: night, tick 14000
recent skills: mine_one_oak_log (✓), craft_sticks (✓)
```

The LLM gets this for free every turn. It only writes / invokes a skill when it wants to **act**.

### 3.6 The control loop, revised

Per turn:

1. Auto-inject bot state + memory context (pinned + retrieved + window).
2. LLM does its tool calls. Common patterns:
   - **First sight of a task:** `searchSkill(query)` → if a hit exists, `invokeSkill`. If no hit → `lookupRecipe` / `lookupMob` for grounding → `writeSkill(...)` → `invokeSkill`.
   - **Routine retrieve-and-run:** `searchSkill` → `invokeSkill`.
   - **Exploratory probe:** `runOnce(code)` to inspect state without saving anything.
3. Skill failures (thrown errors inside the sandbox) come back as observation strings in the same turn (DEPS-style failure injection). The LLM can `writeSkill` an updated version on the spot — a real *patch*, not a full replan.
4. On verified success of a brand-new skill, the LLM's `writeSkill` call has already persisted it. We don't need a separate hindsight-capture step — the LLM does the naming + describing as part of writing.

This last point is important: v1 had a "hindsight capture" step that called the LLM after success to name + describe the skill. v2 collapses that — the LLM is already writing skills with names and descriptions; the "capture" happens at write time, not after.

---

## 4. New dependencies to add

| Package | Why |
|---|---|
| (already installed) `mineflayer-tool` | Real tool selection + crafting; needed by skills the LLM writes for crafting. Currently in `mineflayer-collectblock`'s deps but we'll use it directly. |
| `mineflayer-collectblock` (already installed) | Nice multi-block collection; LLM may use it inside skills. |
| (`node:vm` — built-in) | Sandbox. No new dep. |
| `mineflayer-armor-manager` | If we decide combat skills need it. **Optional, defer.** |
| (`minecraft-data` — already a transitive dep) | RAG ground truth. Use directly. |

No new heavy deps. The pivot is mostly architecture, not packages.

---

## 5. Migration plan — what code dies, lives, moves

| File | v2 fate |
|---|---|
| [src/actions/handlers.ts](../steveai-companion/src/actions/handlers.ts) | **Archive** to `legacy/v1-action-tools/handlers.ts`. Not used at runtime. |
| [src/llm/tools.ts](../steveai-companion/src/llm/tools.ts) | **Rewrite.** Down to ~15 meta tools, none are pre-baked behaviors. |
| [src/perception/handlers.ts](../steveai-companion/src/perception/handlers.ts) | **Mostly removed.** `findNearestBlock`, `getInventory`, `getNearbyEntities`, `getTimeOfDay`, `getPlayerLocation` all become bot-state auto-injection (§3.5) — the LLM doesn't tool-call them, it reads them in context. `resolveReference` stays as a tool (high-value grounding). Memory tools stay. |
| [src/grounding/*](../steveai-companion/src/grounding/) | **Stays.** `resolveReference` is still a direct tool. |
| [src/memory/*](../steveai-companion/src/memory/) | **Stays.** Schema gets one new column on `playbook` → `code TEXT`. |
| [src/planner.ts](../steveai-companion/src/planner.ts) | **Reworked.** New control loop (§3.6). Auto-injection of bot state. New tool dispatch table. |
| [src/bot.ts](../steveai-companion/src/bot.ts) | **Minor change.** Loads the skill sandbox at startup; passes the `bot` reference to skills/sandbox.ts. |
| (new) `src/skills/sandbox.ts` | vm-based sandbox; bot proxy; skill execution; timeouts. |
| (new) `src/skills/library.ts` | SQLite-backed skill storage; embeddings; search/get/save. (Becomes the v2 form of `memory/playbook.ts`.) |
| (new) `src/knowledge/recipes.ts` | minecraft-data wrapper for crafting + smelting. |
| (new) `src/knowledge/mobs.ts` | minecraft-data wrapper for mobs + drops + spawn conditions. |
| (new) `src/knowledge/blocks.ts` | minecraft-data wrapper for blocks + harvest. |
| (new) `src/knowledge/index.ts` | Forward + reverse + fuzzy search indexes; hot-loaded at bot startup. |
| (modified) [src/llm/prompt.ts](../steveai-companion/src/llm/prompt.ts) | Rewritten system prompt teaches the skill-write/invoke loop, the RAG-first convention, and the naming convention. |
| (modified) `data/memory.db` | Migration: existing tables stay; `playbook` gets a `code TEXT` column (nullable for legacy v1 entries). The 2 v1 recipes (`follow_player`, `mine_block`, etc.) get marked `legacy=1` and aren't retrievable by `searchSkill` (only their names — the LLM regenerates them as proper code skills next time). |

---

## 6. Updated end-state directory tree

```
steveai-companion/
├── README.md
├── package.json
├── tsconfig.json
├── env.sample
├── src/
│   ├── bot.ts                     # connect, lifecycle, loads sandbox
│   ├── log.ts
│   ├── llm/
│   │   ├── ollama.ts              # unchanged
│   │   ├── prompt.ts              # rewritten for v2
│   │   └── tools.ts               # rewritten — 15 meta tools
│   ├── memory/                    # unchanged from v1
│   │   ├── store.ts               # +code column on playbook
│   │   ├── episodic.ts
│   │   ├── conversation.ts
│   │   └── playbook.ts            # superseded by skills/library.ts; deprecated
│   ├── grounding/                 # unchanged
│   │   ├── raycast.ts
│   │   ├── reference.ts
│   │   └── confirm.ts
│   ├── perception/
│   │   └── handlers.ts            # trimmed — most fns become auto-injection
│   ├── skills/                    # NEW
│   │   ├── sandbox.ts             # vm + bot proxy + safe-helpers
│   │   ├── library.ts             # SQLite skill storage, embeddings, retrieval
│   │   └── proxy.ts               # safe Bot proxy (whitelisted methods)
│   ├── knowledge/                 # NEW
│   │   ├── index.ts               # bootstrap; forward + reverse + vector indexes
│   │   ├── recipes.ts             # crafting + smelting from minecraft-data
│   │   ├── mobs.ts                # spawn + drops
│   │   └── blocks.ts              # hardness + harvest
│   └── planner.ts                 # reworked for v2 loop
├── eval/
│   ├── runner.ts                  # unchanged
│   ├── replay.ts                  # unchanged
│   ├── mock-bot.ts                # extended: must satisfy the safe-bot proxy too
│   ├── types.ts                   # add 'skill_written' / 'skill_invoked' goal types
│   └── scenarios/
│       ├── 01-fetch-logs.ts       # rewrite for v2 (skill-write or retrieve)
│       ├── 02-mine-that-ore.ts    # mostly unchanged
│       ├── 03-multi-step-task.ts  # rewrite — skill composition
│       ├── 04-cross-session-memory.ts   # unchanged
│       ├── 05-place-blocks.ts     # rewrite for v2
│       ├── 06-craft-fishing-rod.ts      # NEW — uses RAG + writeSkill + craft
│       ├── 07-night-spider-string.ts    # NEW — wait_for_night + attack + collect
│       ├── 08-skill-retrieval.ts        # NEW — write skill, restart, retrieve+invoke
│       ├── 09-recipe-rag-grounding.ts   # NEW — verifies LLM consults lookupRecipe
│       └── 10-failure-replan.ts         # NEW — DEPS-style: skill fails, LLM patches
├── data/
└── tests/
└── legacy/
    └── v1-action-tools/                 # archived hand-wrapped action handlers
        └── handlers.ts
```

---

## 7. v2 acceptance criteria

Replaces v1 §5's five checks. v2 succeeds when:

1. **Empty start:** Bot boots with zero skills in the library. Player asks for an action. The LLM looks up RAG, writes a skill, invokes it, succeeds. The skill is now in the library.
2. **Retrieval works:** Same task again (or after restart) — `searchSkill` returns the existing skill, `invokeSkill` runs it. No `writeSkill` call.
3. **RAG grounding:** Crafting tasks consult `lookupRecipe` BEFORE writing the skill. The skill's ingredient list matches minecraft-data exactly.
4. **Failure recovery:** A skill that throws an error gets patched in the next turn, not full-replanned.
5. **Cross-session persistence:** Skills survive bot restart (carries over from v1 #3).
6. **Eval harness produces reports** (carries over from v1 #4).
7. **Runs on M3 Pro/Max-class with no API key** (carries over from v1 #5).
8. **Capability scaling:** The fishing-rod-from-spider-string scenario (07) runs to completion — multi-step, RAG-grounded, novel skills written along the way. This was the impossible task in v1.

---

## 8. New eval scenarios (the headline ones)

### 06 — `craft a fishing rod`
- Setup: bot near a crafting table; inventory has 3 sticks + 2 string.
- Expected trace:
  - `searchSkill("craft fishing rod")` → no hit
  - `lookupRecipe("fishing_rod")` → `{ingredients: [3 stick, 2 string], shape: '..s', table: 'crafting_table'}`
  - `writeSkill("craft_fishing_rod", "Open a crafting table within reach and craft 1 fishing_rod from 3 sticks + 2 string.", <code>)`
  - Skill executes, fishing_rod appears in inventory.
- Goals:
  - `lookupRecipe` was called with `fishing_rod`
  - `writeSkill` was called with `craft_fishing_rod` (or close)
  - `inventory_has fishing_rod ≥ 1`
  - `skill_count ≥ 1`

### 07 — the spider-string-fishing-rod chain (the big one)
- Setup: night-time world, spider visible, crafting table, no fishing rod.
- Player: *"kill the spider, get its string, then craft me a fishing rod"*
- Expected trace:
  - `findMobsDropping("string")` → `[spider, ...]` (sanity check)
  - `searchSkill("kill spider")` → no hit
  - `lookupMob("spider")` → spawn / drops info
  - `writeSkill("kill_nearby_mob", ...)` → invoke → spider dies, string in inventory
  - `searchSkill("craft fishing rod")` → no hit
  - `lookupRecipe("fishing_rod")` → ingredients
  - `writeSkill("craft_fishing_rod", ...)` → invoke → fishing rod in inventory
- Goals:
  - 2+ skills written across the turn
  - `inventory_has string ≥ 1`
  - `inventory_has fishing_rod ≥ 1`

### 08 — skill retrieval across sessions
- Session A: player asks for an oak log. LLM writes `mine_one_oak_log`, runs it.
- Session A → restart_session.
- Session B: player asks for the same. Expected: `searchSkill` hits, `invokeSkill` runs, **NO** `writeSkill` call. (This proves the skill is real persistent code, not just a description.)

### 09 — recipe RAG grounding
- Setup: crafting table, pile of cobblestone.
- Player: *"craft a stone pickaxe"*
- Goal: the skill the LLM writes contains a call to `lookupRecipe("stone_pickaxe")` (not pure-vibes "I think this is the recipe"). We verify by inspecting the written skill code.

### 10 — failure-driven replan (DEPS pattern)
- Setup: oak log out of reach because of a wall.
- Skill `mine_one_oak_log` fails with "pathfinder timeout".
- Goal: the next turn either rewrites the skill to handle the obstacle, OR `runOnce`s some exploration code, OR asks the player for help — but does NOT re-invoke the same broken skill blindly.

---

## 9. What's STILL not in v2 (explicit non-goals)

- Pixel / vision grounding (no SAM-2, no MineCLIP). Still spatial-state-only.
- Multi-Steve coordination.
- Cloud LLMs (still Ollama-only).
- Voice input.
- Web UI.
- A persistent autonomous loop ("farm overnight while I'm offline"). The bot still acts only on player chat in v2.
- Skill marketplace / cross-bot skill sharing. The skill library is per-bot.
- Strict security sandbox. We rely on `node:vm` + curated bot proxy + 30s timeouts. Adversarial code escape is not in scope.

---

## 10. Risks specific to v2

| Risk | Mitigation |
|---|---|
| LLM-written skill code has bugs (wrong API call, missing await, infinite loop) | (a) 30s timeout. (b) Errors flow back as observations → LLM patches in next turn. (c) System prompt seeds the bot-API surface as a quick reference. (d) `runOnce` for exploration before committing to `writeSkill`. |
| LLM hallucinates recipes | RAG is mandatory before `craft_*` writes. Convention reinforced in prompt + naming convention. The reverse-lookup tool catches "where does X come from" hallucination too. |
| Skill library grows unboundedly with near-duplicates | Voyager handled this with success-count weighting + retrieval ranking. We start without explicit dedup; add a "merge similar skills" pass later if it becomes a problem. |
| First-time skill-write is slow (multi-second LLM call) | Acceptable. Cached on second use. The bot is a companion, not a benchmark. |
| `node:vm` isn't a real security boundary | Documented limitation. Use case is a single trusted user; threat model is "LLM is sometimes wrong," not "LLM is malicious." |
| gpt-oss:20b might not write Mineflayer code as well as GPT-4 | Real concern. Mitigations: (a) seed prompt with Mineflayer cheat-sheet, (b) allow `runOnce` exploration, (c) fall back to `gpt-oss:120b` for users with the VRAM, (d) measure on the eval scenarios — if 06/07 pass, the model is good enough. |

---

## 11. Reading checklist before implementing

When the implementation plan is written, the author should have read:

- This document, top to bottom.
- [Voyager paper](https://arxiv.org/abs/2305.16291) §3 (the skill library mechanism + iterative prompting + curriculum). The curriculum part we don't need; the skill library + iterative prompting we do.
- [MindCraft source](https://github.com/mindcraft-bots/mindcraft) — `src/agent/coder.js` specifically. They've already debugged a Voyager-style code-gen sandbox in Mineflayer; we lift their stable patterns.
- [`minecraft-data` README](https://github.com/PrismarineJS/minecraft-data) and a quick browse of `data/pc/<version>/recipes.json` to understand the shape we're indexing.
- The five v1 scenarios + their recordings in `eval/recordings/` — the goal-check format carries over.

---

## 12. The honest summary of why this is the right call

The v1 plan leaned on the (correct) observation that hand-wrapping is bug-free and predictable. It missed the (also correct) observation that hand-wrapping is also **bounded by what we hand-write**, forever. The pivot is what the survey said to build in the first place; we walked away because Voyager-style code-gen looked riskier than it actually is for a local single-user companion bot. The risk is real but bounded; the upside is unbounded — Steve actually *learns* his action vocabulary instead of having it dictated.

After this lands and the v2 acceptance scenarios pass, "can Steve craft a fishing rod from spider string" stops being a tools-we-need-to-add question and starts being a "did the model write the right code on the first try, or the second?" question. That's the right question to be asking.
