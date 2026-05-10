# SteveAI

A Minecraft player-companion: joins your world as another player, takes natural-language commands, remembers across sessions, and acts on what you say. Local inference only.

This repo is in transition. The original Forge mod has been archived; the active codebase is a Mineflayer-based Node.js companion bot.

## What's where

- **[steveai-companion/](steveai-companion/)** — the active project. Mineflayer + Ollama + `gpt-oss:20b`. v1 (memory + grounding + planner + 5/5 eval scenarios passing) is shipped; v2 pivot (Voyager-style code-gen + RAG) is the next implementation.
- **[docs/](docs/)** — current direction:
  - [docs/COMPANION_V2_DIRECTION.md](docs/COMPANION_V2_DIRECTION.md) — **canonical direction** (v2). Voyager-style runtime code generation + Minecraft-knowledge RAG. Read this for what we're building next.
  - [docs/COMPANION_V1_DIRECTION.md](docs/COMPANION_V1_DIRECTION.md) — v1 plan (superseded; kept as record of the path). Substrate / memory / eval-harness sections still apply to v2.
  - [docs/MINECRAFT_AI_RESEARCH_SURVEY.md](docs/MINECRAFT_AI_RESEARCH_SURVEY.md) — landscape survey of Minecraft-AI work 2023–2026 with citations
  - [docs/RESTRUCTURE_PLAN.md](docs/RESTRUCTURE_PLAN.md) — the execution plan for the Forge → Mineflayer transition
  - [docs/LOGGING_POLICY.md](docs/LOGGING_POLICY.md), [docs/MODULARITY_RULES.md](docs/MODULARITY_RULES.md) — cross-cutting principles (Forge-era; needs adapting for the Node companion as a follow-up)
- **[legacy/forge-mod/](legacy/forge-mod/)** — the archived Forge mod. Frozen reference; no maintenance. Also tagged `v0.x-forge-final` and on branch `legacy/forge-mod`.

## Quick start

```bash
cd steveai-companion
cat README.md         # full setup, prereqs, run instructions
```

You'll need Ollama + `gpt-oss:20b` pulled, a Minecraft Java 1.21.x server (or singleplayer-LAN), and Node 20+. Targets an M3 Pro/Max-class machine with ≥36 GB unified memory while Minecraft runs alongside.

## Why the pivot

In short: the Forge mod worked but didn't feel right for a player-companion product. The survey ([docs/MINECRAFT_AI_RESEARCH_SURVEY.md](docs/MINECRAFT_AI_RESEARCH_SURVEY.md)) maps the post-2022 Minecraft-AI landscape and identifies HCI-anchored companion + cross-session memory + cheap visual grounding as the under-invested differentiation directions. [docs/COMPANION_V1_DIRECTION.md](docs/COMPANION_V1_DIRECTION.md) Appendix A turns that into the v1 plan.

## License

MIT (carried over from the Forge mod).
