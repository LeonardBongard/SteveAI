# SteveAI Companion (v1)

Minecraft player-companion bot. Joins your Java-edition world as another player, chats with you, remembers across sessions, and acts on what you say. **Local inference only** — Ollama + `gpt-oss:20b`, no API keys.

For the rationale, architecture, and the list of "80% trades" baked in, see [`../docs/COMPANION_V1_DIRECTION.md`](../docs/COMPANION_V1_DIRECTION.md).

---

## Hardware

Tuned for a single workstation that **also runs Minecraft**. Tested target: M3 Pro/Max-class machine with ≥36 GB unified memory.

Approximate resident memory budget when everything's running:

| Process | Resident |
|---|---|
| Minecraft client | ~6–8 GB |
| Minecraft server (or LAN host) | ~3–4 GB (skip with LAN-from-singleplayer; see below) |
| Ollama + `gpt-oss:20b` loaded | ~13–14 GB |
| This bot (Node) | ~0.3 GB |
| macOS + browser/IDE overhead | ~6–8 GB |

If memory pressure is a problem: drop the model to `llama3.1:8b` in `.env` (saves ~9 GB at the cost of planning quality), or set `OLLAMA_KEEP_ALIVE=0` so the model unloads between turns.

## Prerequisites

1. **Node.js 20 LTS or 22 LTS** — `node --version`.
2. **Ollama** — install from <https://ollama.com/download>.
3. Pull the models:
   ```bash
   ollama pull gpt-oss:20b           # ~12 GB on disk
   ollama pull nomic-embed-text      # ~270 MB
   ```
4. Set the daemon's keep-alive default:
   ```bash
   launchctl setenv OLLAMA_KEEP_ALIVE 30m   # macOS
   # then restart Ollama
   ```
   (Or rely on the `OLLAMA_KEEP_ALIVE` env var the bot passes per request — already wired.)
5. **Minecraft Java Edition 1.21.x** (or whatever version your server runs).
6. A server for the bot to connect to. Two options — pick one:
   - **Easy: LAN from singleplayer.** Open your world, hit Esc → "Open to LAN" → "Allow Cheats: ON" → "Start LAN World". Read the port from the chat message and put it in `MC_PORT` in `.env`. The port changes every time, so this is fine for hacking but annoying for repeated runs.
   - **Stable: a small local server.** Download [PaperMC](https://papermc.io/downloads/paper) for your Minecraft version. Run it once, accept the EULA (`echo eula=true > eula.txt`), set `online-mode=false` in `server.properties`, restart, then keep it running. The bot connects to `localhost:25565`.

## Install

```bash
cd steveai-companion
cp env.sample .env             # then edit if your setup differs
npm install
```

## Run

```bash
npm run dev                    # tsx watch mode — restarts on save
# or
npm start                      # one-shot
```

You should see the bot connect, log into the world, and announce itself in chat. Type a message in-game; the bot replies via `gpt-oss:20b`.

## Watching the bot live (v2 — code-gen visibility)

The v2 architecture has the LLM writing JS skills at runtime. To see what it's actually doing:

**1. The terminal where `npm start` runs** shows a colored pino log line per tool call as it happens:

```
[ACT]  writeSkill(name=mine_one_oak_log, desc="Find the nearest oak_log within 32...", code=312 chars) → saved + test-invoked "mine_one_oak_log" successfully in 412ms. Marked verified.
[ACT]  invokeSkill({"name":"mine_one_oak_log"}) → invoked mine_one_oak_log ok in 1840ms
[ACT]  lookupRecipe({"item":"fishing_rod"}) → 1x fishing_rod = 3x stick + 2x string (crafting_table)
```

**2. The skills Steve has written are saved as plain `.js` files** at `data/skills/<skill_name>.js`, with a metadata header:

```bash
ls data/skills/
cat data/skills/craft_fishing_rod.js
```

```javascript
// SteveAI skill — generated at runtime by gpt-oss:20b.
// name:        craft_fishing_rod
// description: Open a crafting table within 16 blocks and craft 1 fishing_rod...
// verified:    true
// counts:      ✓3 / ✗1
// updated:     2026-05-10T21:14:32.118Z

const tableId = bot.registry.blocksByName.crafting_table.id;
const table = bot.findBlock({ matching: tableId, maxDistance: 16 });
...
```

**3. Per-session transcript** at `data/transcripts/<timestamp>.log` — append-only audit trail. Open another terminal and:

```bash
tail -f data/transcripts/$(ls -t data/transcripts | head -1)
```

You'll see every player turn, every tool call (with summary args), every skill written (with full code inline), and turn boundaries.

```
=== 2026-05-10T21:14:00.000Z TestPlayer: "craft me a fishing rod" ===
[turn] step 1  searchSkill({"query":"craft fishing rod"}) → no matching skills [82ms]
[turn] step 2  lookupRecipe({"item":"fishing_rod"}) → 1x fishing_rod = 3x stick + 2x string (crafting_table) [3ms]
[turn] step 3  writeSkill(name=craft_fishing_rod, desc="...", code=412 chars) → saved + test-invoked successfully [1924ms]
[skill] craft_fishing_rod: WRITTEN + VERIFIED in 1924ms
  desc: Open a crafting table within 16 blocks and craft 1 fishing_rod from 3 sticks + 2 string.
  file: /…/data/skills/craft_fishing_rod.js
  code:
    const tableId = bot.registry.blocksByName.crafting_table.id;
    const table = bot.findBlock({ matching: tableId, maxDistance: 16 });
    …
[end] 4 steps; skills this turn: craft_fishing_rod=ok
```

**4. The persistent skill library** at `data/memory.db` — survives bot restarts. Skills written today are retrievable next session via `searchSkill`.

```bash
sqlite3 data/memory.db "SELECT name, verified, success_count, failure_count FROM skills ORDER BY ts DESC;"
```

## Project layout

```
src/
  bot.ts                   # entry — connect, register handlers
  log.ts                   # pino logger with [PLAN]/[ACT]/[MEM]/[GROUND] tags
  llm/
    ollama.ts              # warm-keep, KV-cache-aware client
    prompt.ts              # system prompt (cached prefix)
    tools.ts               # gpt-oss-native tool schemas (Zod)
  memory/                  # (next phase)
  grounding/               # (next phase)
  perception/              # (next phase)
  actions/                 # (next phase)
  planner.ts               # (next phase)
eval/                      # (next phase)
data/                      # SQLite memory lives here at runtime — gitignored
```

This is **phase 1**. The minimum viable loop: connect → chat in → LLM → chat out. Phases 2 and 3 add memory, grounding, the planner, and the eval harness; tracked in [`../docs/COMPANION_V1_DIRECTION.md`](../docs/COMPANION_V1_DIRECTION.md) §5.

## Troubleshooting

- **`Error: Unsupported protocol version`** — your `MC_VERSION` doesn't match the server. Set it explicitly (e.g. `MC_VERSION=1.21.4`) or leave it `false` and Mineflayer will negotiate.
- **`ECONNREFUSED localhost:11434`** — Ollama isn't running. `ollama serve` or open the Ollama app.
- **Bot connects, never replies** — verify `gpt-oss:20b` is pulled (`ollama list`); check the bot logs for errors from the Ollama call.
- **Minecraft FPS drops while bot thinks** — expected on a 40 GB Mac. Drop to `llama3.1:8b` or set `OLLAMA_KEEP_ALIVE=0`.
