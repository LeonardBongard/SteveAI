# Item Sources Coverage Plan

Goal: maintain a verifiable, versioned map of how Steve can obtain each Minecraft item.

## Current baseline (generated)
Minecraft target: `1.21.11`

Metrics from `reports/item_source_coverage.json`:
1. Total items: 1481
2. Mapped (including identity fallback): 1481
3. Actionable mapped (non-identity sources): 17
4. Actionable coverage: 1.15%

Interpretation:
1. We currently know the full item universe.
2. We do **not** yet have broad actionable source coverage.
3. Resolver/data expansion is required to avoid stalls for most items.

## Artifacts
1. Full item universe: `generated/all_items.csv`
2. Source map: `src/main/resources/steve/item_sources.csv`
3. Manual overrides: `src/main/resources/steve/item_sources_overrides.csv`
4. Station recipe map: `src/main/resources/steve/crafting_recipes.csv`
5. Coverage report: `reports/item_source_coverage.json`
6. Actionable gaps: `reports/unmapped_items.csv`

## Generation workflow
Run:
```bash
python3 scripts/generate_item_sources.py
python3 scripts/generate_crafting_recipes.py
```

This does:
1. Extract all item ids from `Items.java`.
2. Parse recipes, loot tables, and item tags from the local Minecraft jar.
3. Merge with manual overrides.
4. Emit source map + station recipe map + coverage reports.

## Validation gate
Run:
```bash
python3 scripts/validate_item_source_coverage.py --min-actionable 50
```

This fails if actionable coverage is below threshold.

## Expansion strategy
1. Increase auto extraction quality:
- Expand recipe parser for all recipe types and edge cases.
- Improve loot parsing for block-drop item extraction.
- Use tag expansions for equivalence families.

2. Add curated overrides for high-impact items first:
- Wood/planks/sticks/tool chains.
- Core food chains.
- Core ore/tool progression.

3. Raise threshold in stages:
1. 50% actionable
2. 75% actionable
3. 90% actionable
4. 95% actionable

## Definition of done
1. No scenario-critical item appears as `identity_only`.
2. Actionable coverage threshold met for release target.
3. Resolver loads CSV-only data for production behavior (code fallback remains minimal).
