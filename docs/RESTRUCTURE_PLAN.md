# Repo Restructure Plan: Forge Mod → Companion v1

> Execution plan for moving this repository from its current state (Forge mod + uncommitted WIP + new `steveai-companion/` sibling) to the v1 target state (Forge mod archived to `legacy/forge-mod` branch + directory; Mineflayer companion as primary).
>
> Pairs with [COMPANION_V1_DIRECTION.md §7](COMPANION_V1_DIRECTION.md). This document is *only* about the file/branch transition — not about what the new code does.

---

## 1. Decisions already locked

These were chosen up-front; we don't relitigate during the move:

- **WIP handling:** all current uncommitted Forge changes (25+ modified files + untracked `search/`, `behavior/`, persona profiles, network shim, tests, modified docs) get committed as the *final state* of the Forge mod. Nothing is discarded.
- **Archive shape:** branch `legacy/forge-mod` + tag `v0.x-forge-final` from current `codex1` HEAD. A directory `legacy/forge-mod/` on main holds the same code for reference reading.
- **Doc split:**
  - **Stay on main, adapted for Node:** `LOGGING_POLICY.md`, `MODULARITY_RULES.md`.
  - **Stay on main, unchanged:** `MINECRAFT_AI_RESEARCH_SURVEY.md` (+ pdf), `COMPANION_V1_DIRECTION.md`, this file.
  - **Archive to `legacy/forge-mod/docs/`:** everything else in `docs/` (Forge-era arch / process / scenario docs).
- **No Forge maintenance after the cut.** No back-patching. The branch and tag exist as a reference, nothing more.
- **Companion v1 is single-Steve.** The `CollaborativeBuildManager` and multi-Steve code are part of the archive, not the new repo.

## 2. Target end-state tree

```
SteveAI/
├── README.md                              # NEW — top-level umbrella, points to companion + legacy
├── .gitignore                             # NEW root — Node + cross-cutting outputs
├── .claude/                               # KEEP — already populated (settings.json, settings.local.json)
├── .github/
│   └── PULL_REQUEST_TEMPLATE.md           # REPLACED — Node-shaped checklist
├── docs/
│   ├── COMPANION_V1_DIRECTION.md          # KEEP
│   ├── MINECRAFT_AI_RESEARCH_SURVEY.md    # KEEP
│   ├── MINECRAFT_AI_RESEARCH_SURVEY.pdf   # KEEP
│   ├── RESTRUCTURE_PLAN.md                # KEEP — this file
│   ├── LOGGING_POLICY.md                  # ADAPTED — tags refit Node modules
│   └── MODULARITY_RULES.md                # ADAPTED — drops Java specifics
├── steveai-companion/                     # KEEP — phase-1 scaffold already in place
│   └── (existing Node project, untouched)
└── legacy/
    └── forge-mod/                         # NEW directory; same content as legacy/forge-mod branch
        ├── README.md                      # NEW — "this is an archive; see branch v0.x-forge-final"
        ├── build.gradle, settings.gradle, gradle.properties
        ├── gradlew, gradlew.bat, gradle/
        ├── config/
        ├── src/main/java/com/steve/ai/...
        ├── src/test/java/com/steve/ai/...
        ├── scripts/                       # all current scripts/ (Forge-only)
        ├── docs/                          # all archived Forge-era docs (see §3.3)
        ├── DEPENDENCY_FIX.md, JARJAR_MIGRATION.md, OLLAMA_FIX_SUMMARY.md
        ├── RELEASE_NOTES.md, TECHNICAL_DEEP_DIVE.md
        └── README.md (original)           # the Forge-mod README, intact
```

Deleted outright (not archived):

- `temp_memory_chatgpt.txt` — leftover memory dump
- `bin/`, `build/`, `generated/`, `reports/`, `logs/`, `mcmodsrepo/`, `.gradle/` — build outputs / local caches
- `docs/finished/` — empty

## 3. Concrete file lists

### 3.1 Java sources (move to `legacy/forge-mod/src/`)
- `src/main/java/com/steve/ai/...` (entire tree)
- `src/test/java/com/steve/ai/...` (entire tree)

### 3.2 Build files (move to `legacy/forge-mod/`)
- `build.gradle`, `settings.gradle`, `gradle.properties`
- `gradlew`, `gradlew.bat`, `gradle/` (wrapper)
- `config/` (Forge runtime config)

### 3.3 Docs to archive (move to `legacy/forge-mod/docs/`)
From `docs/`:
- `ARCHITECTURE_EXECUTION_MODEL.md`
- `CLEARING_QUESTIONS.md`
- `DOMAIN_PR_SEQUENCE.md`
- `EVAL_SCENARIOS.md`
- `EXECUTION_BOARD.md`
- `IMPLEMENTATION_WAVES.md`
- `INGAME_VALIDATION_FARM_FEED.md`
- `INGAME_VALIDATION_NEW_FEATURES.md`
- `ISSUES.md`, `NEW_ISSUES.md`
- `ITEM_SOURCES_COVERAGE_PLAN.md`
- `MINING_PLAN.md`
- `PRODUCT_BACKLOG.md`
- `ROADMAP_NORTH_STAR.md`
- `SAFETY_EVALUATOR_PLAN.md`
- `SCRUM_OPERATING_MODEL.md`
- `sprints/` (entire directory)

### 3.4 Root-level Forge files (move to `legacy/forge-mod/`)
- `DEPENDENCY_FIX.md`
- `JARJAR_MIGRATION.md`
- `OLLAMA_FIX_SUMMARY.md`
- `RELEASE_NOTES.md`
- `TECHNICAL_DEEP_DIVE.md`
- `README.md` (the existing Forge README — moves to `legacy/forge-mod/README.md`; new umbrella README replaces it at root)

### 3.5 Scripts (move to `legacy/forge-mod/scripts/`)
All current `scripts/` are Forge-only:
- `test_mod_pipeline.sh`
- `mc_autostart_mac.sh`, `mc_minimal_click_flow.sh`
- `run_steve.sh`, `run_regression_suite.sh`, `snapshot_mc_logs.sh`
- `generate_crafting_recipes.py`, `generate_item_sources.py`, `validate_item_source_coverage.py`

### 3.6 Delete (don't archive)
- `temp_memory_chatgpt.txt`
- `bin/`, `build/`, `generated/`, `reports/`, `logs/`, `mcmodsrepo/`, `.gradle/`
- `docs/finished/`

## 4. Migration steps (ordered)

Branch state today: working on `codex1`, ahead of `main` by ~10 commits, working tree dirty.

### Step 1 — Snapshot safely
```bash
git push origin codex1               # ensure remote backup
```

### Step 2 — Commit current WIP on codex1
```bash
git add -A
git commit -m "Final Forge-mod state + new direction docs + companion scaffold"
```
Single commit captures: 25+ modified Java files, untracked `search/`, `behavior/`, persona profiles, network shim, tests, modified docs, the new survey + direction + restructure plan, the entire `steveai-companion/` scaffold.

(Optional: split into two commits — one for "Final Forge state" with the Java edits, one for "Begin Companion v1" with the new docs + Node project. Cleaner history. Add `git add` selectively if doing this.)

### Step 3 — Create the legacy archive
```bash
git checkout -b legacy/forge-mod
git tag v0.x-forge-final
git push origin legacy/forge-mod
git push origin v0.x-forge-final
git checkout codex1
```

### Step 4 — Create the restructure branch
```bash
git checkout -b restructure/companion-v1
```
All file moves happen here. Easy to abandon if something goes sideways.

### Step 5 — Move directories with `git mv` (one chunk per commit)

`git mv` is mandatory — `cp + rm` breaks `git log --follow`.

**5a — Java sources:**
```bash
mkdir -p legacy/forge-mod/src
git mv src/main legacy/forge-mod/src/main
git mv src/test legacy/forge-mod/src/test
git commit -m "Archive Java sources to legacy/forge-mod/src/"
```

**5b — Build files:**
```bash
git mv build.gradle settings.gradle gradle.properties \
       gradlew gradlew.bat gradle config \
       legacy/forge-mod/
git commit -m "Archive Forge/Gradle build files to legacy/forge-mod/"
```

**5c — Forge-era root markdown:**
```bash
git mv DEPENDENCY_FIX.md JARJAR_MIGRATION.md OLLAMA_FIX_SUMMARY.md \
       RELEASE_NOTES.md TECHNICAL_DEEP_DIVE.md README.md \
       legacy/forge-mod/
git commit -m "Archive Forge-era root docs to legacy/forge-mod/"
```

**5d — Scripts:**
```bash
mkdir -p legacy/forge-mod/scripts
git mv scripts/* legacy/forge-mod/scripts/
rmdir scripts
git commit -m "Archive Forge scripts to legacy/forge-mod/scripts/"
```

**5e — Forge-era docs:**
```bash
mkdir -p legacy/forge-mod/docs
git mv docs/ARCHITECTURE_EXECUTION_MODEL.md \
       docs/CLEARING_QUESTIONS.md \
       docs/DOMAIN_PR_SEQUENCE.md \
       docs/EVAL_SCENARIOS.md \
       docs/EXECUTION_BOARD.md \
       docs/IMPLEMENTATION_WAVES.md \
       docs/INGAME_VALIDATION_FARM_FEED.md \
       docs/INGAME_VALIDATION_NEW_FEATURES.md \
       docs/ISSUES.md docs/NEW_ISSUES.md \
       docs/ITEM_SOURCES_COVERAGE_PLAN.md \
       docs/MINING_PLAN.md \
       docs/PRODUCT_BACKLOG.md \
       docs/ROADMAP_NORTH_STAR.md \
       docs/SAFETY_EVALUATOR_PLAN.md \
       docs/SCRUM_OPERATING_MODEL.md \
       docs/sprints \
       legacy/forge-mod/docs/
git commit -m "Archive Forge-era process / scenario docs to legacy/forge-mod/docs/"
```

### Step 6 — Delete build outputs / leftovers
```bash
rm -rf bin build generated reports logs mcmodsrepo .gradle docs/finished
rm -f temp_memory_chatgpt.txt
# Some of these are gitignored (no-op for git); others may be tracked.
git add -A
git commit -m "Drop build outputs and leftovers"
```

### Step 7 — Add new top-level files
- Write new umbrella `README.md` at repo root.
- Write `legacy/forge-mod/README.md` explaining: archive only, see branch + tag, do not push fixes here.
- Replace `.github/PULL_REQUEST_TEMPLATE.md` with a Node-shaped checklist (drop the Forge-specific Validation steps; add `npm test` / `npx tsc --noEmit`).
- Update root `.gitignore` for Node + cross-cutting outputs; old root `.gitignore` patterns for `build/`, `out/`, `.gradle/` are no longer needed at root (they exist inside `legacy/forge-mod/`).
- Adapt `docs/LOGGING_POLICY.md`: tag list becomes the Node bot's `[BOT][PLAN][ACT][MEM][GROUND][LLM][EVAL]` (already wired in [steveai-companion/src/log.ts](../steveai-companion/src/log.ts)).
- Adapt `docs/MODULARITY_RULES.md`: drop Java/Forge specifics, keep the principles.

```bash
git add README.md legacy/forge-mod/README.md \
        .github/PULL_REQUEST_TEMPLATE.md \
        .gitignore \
        docs/LOGGING_POLICY.md docs/MODULARITY_RULES.md
git commit -m "Add umbrella README, replace PR template, adapt cross-cutting docs"
```

### Step 8 — Push and merge
```bash
git push -u origin restructure/companion-v1
# Either open a PR for review, or fast-forward in:
git checkout main
git merge --no-ff restructure/companion-v1
git push origin main
```

### Step 9 — Verify
```bash
# History preserved through git mv:
git log --follow legacy/forge-mod/src/main/java/com/steve/ai/SteveMod.java | head -5

# Archive accessible by tag:
git show v0.x-forge-final --stat | head -10

# Companion still builds:
cd steveai-companion && npx tsc --noEmit

# Tree sanity-check:
find . -type d \( -name node_modules -o -name .git \) -prune -o -type d -print | head -30
```

## 5. Risks and how to handle them

- **`git mv` discipline.** Every move uses `git mv`. A single `cp + rm` mid-step breaks `git log --follow` for the moved file. If a step fails partway, `git reset --hard HEAD` and redo from the chunk's start.
- **PR #12 (the open Forge-mod knob PR) goes stale instantly.** Close it with a comment: "Forge mod is archived; this knob lives at `legacy/forge-mod/src/main/java/com/steve/ai/action/actions/BuildStructureAction.java` on branch `legacy/forge-mod`. Not landing on `main`."
- **GitHub Actions workflows.** None visible in `.github/workflows/`, so probably no break. Confirm with `ls .github/workflows/ 2>/dev/null` before Step 7.
- **External references to source paths** (READMEs, prior PR descriptions, blog posts) will break links to `src/main/java/...`. Acceptable cost — won't fix retroactively.
- **Contributors with existing checkouts** of `codex1` will see a huge diff on next pull. If that's anyone other than yourself, ping them before pushing.
- **The new umbrella README must mention the archive.** Otherwise visitors see a Mineflayer project and assume the survey / Forge-era research is unrelated. The link from main README → `legacy/forge-mod/` and from there → branch + tag is the navigation backbone.

## 6. Out of scope (explicit)

- Updating internal links inside *archived* docs to point to `legacy/forge-mod/...` paths. They stay as-is. Reading them inside the archive is a reference activity, not a navigation activity.
- Removing the `legacy/forge-mod/` directory from main eventually. Defer to a future decision once the Mineflayer rewrite is mature.
- Adopting a git submodule for `legacy/forge-mod/` (would deduplicate vs. branch). More complex; not v1.
- Migrating any Java code to TypeScript. The Forge mod is not being ported; the Mineflayer companion is being built fresh on top of the survey + direction docs.
- CI for the companion. Not in this restructure; falls under Companion v1 implementation.

## 7. After the restructure — what's next

Once the working tree is clean and pushed:

1. Companion v1 phase 2 — implement memory + grounding modules (per [COMPANION_V1_DIRECTION.md](COMPANION_V1_DIRECTION.md) §3.3 / §3.4).
2. Adapt `docs/LOGGING_POLICY.md` and `docs/MODULARITY_RULES.md` for real (the Step-7 commit is an outline; the actual content rewrite is its own small task).
3. Close PR #12 with the comment in §5.
4. Decide whether to make `legacy/forge-mod/` a submodule (defer; revisit in a quarter or two).
