# Evaluation Scenarios (Acceptance + Regression)

Use these as executable checks after each wave.

## Testing Standard (Read First)
1. Automated regression checks validate code-level correctness, not full in-world truth.
2. Automated checks include compile/tests/pipeline/data coverage and can only prove "the implementation is consistent".
3. Real Minecraft behavior must be validated with manual in-game scenarios.
4. Release confidence requires both:
- Automated regression report pass (or explicit sandbox skip reason).
- Manual scenario checklist pass in-world.
- For new-domain/manual flows also run:
  - `docs/INGAME_VALIDATION_FARM_FEED.md`
  - `docs/INGAME_VALIDATION_NEW_FEATURES.md`

## Safety and Priority Baseline
1. Mining iron, hunger medium:
- Expect survival-first interruption and recovery.

2. Building bridge at night with nearby hostiles:
- Expect safety-first pause and resume after safe state.

3. Crossing normal river for a task:
- Expect direct traversal (not flagged dangerous by default).

4. User urgency command ("finish now") under moderate risk:
- Expect safety-first policy to remain active.

## Additional 10 Scenarios
5. Crafting with full inventory at night:
- Expect safety + inventory recovery, then continue crafting.

6. Tunnel mining and sudden hostile cave exposure:
- Expect immediate retreat/defense before returning.

7. Farming while hunger low and food available:
- Expect eat first (survival-first), then continue farming.

8. Feeding cows while Steve also has low food reserves:
- Expect self-preservation logic before feed action.

9. Water canyon traversal with air pockets:
- Expect traversal with drowning checks only.

10. Roof-edge building with fall risk:
- Expect conservative movement/placement.

11. Wood gathering with nearby hostiles:
- Expect safer source or safer timing.

12. Combat command while hungry and low HP:
- Expect retreat first, then eat/heal, then re-evaluate engage.

13. Long mining quota with nearly broken tool:
- Expect proactive tool recovery before breakage failure.

14. Compound goal ("build house and stock food"):
- Expect ongoing survival maintenance through all subtasks.

## Pass Criteria
1. No infinite loops.
2. Clear, structured reason for every safety-driven interrupt.
3. Interrupted tasks resume or fail with explicit reason.
4. Behavior remains deterministic under same seed/setup.
