# Logging Policy (Standard)

## Scope
This policy is mandatory for all safety-critical or failure-prone execution flows (combat, hunger recovery, farming prep, chest sourcing, mining pathing, crafting prerequisites).

## Required log points
For each such flow, add structured logs for:
1. Start of action/context (inputs, key parameters).
2. Precondition evaluation (what is missing / what is risky).
3. Branch decision (why branch A vs B).
4. Recovery/retry enqueue (what tasks were injected).
5. Terminal outcome (success/failure with reason).

## Format
- Use stable tag prefixes, e.g. `[FARM]`, `[SAFETY]`, `[FOOD]`, `[CHEST]`, `[SMELT]`.
- Include Steve name and key identifiers (`crop`, `item`, `state`, `panic`, etc).
- Prefer `info` for expected branching, `warn` for degraded/failure paths.

## Crash/debug expectation
- Logs must be sufficient to reconstruct the last decision chain before crash/stall.
- “Silent fail” is not allowed in prerequisite or safety paths.
