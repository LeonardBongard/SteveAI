# In-Game Validation: Farming + Feeding

## Standard Testing Behavior
1. Run automated regression first:
- `./scripts/run_regression_suite.sh`
- Optional install run: `./scripts/run_regression_suite.sh --install`
2. Treat automated output as "theoretical/engineering validation" only.
3. Use this in-game checklist for actual behavior validation in Minecraft.
4. Final sign-off requires both automated checks and manual in-game pass.

## Preconditions
1. Use a fresh/safe test world in Creative mode.
2. Ensure the latest mod jar is installed.
3. Open chat and run commands as operator.

## Spawn and Baseline
1. Run: `/steve spawn Alex`
2. Run: `/steve list`
Expected:
- `Alex` is listed.

3. Run: `/steve debug Alex status`
Expected:
- Health/food/saturation values are shown.

## Scenario A: Farming Wheat Loop
1. Prepare a small patch near Alex:
- Place dirt blocks and a water source.
- Optionally place a few mature wheat crops.

2. Run: `/steve tell Alex farm wheat and get 12 wheat`
Expected:
- Alex moves to farm area.
- If mature wheat exists: harvest first.
- If farmland exists and empty: plant wheat.
- If only dirt exists: till to farmland, then plant.
- Action completes without infinite loop.

3. Run: `/steve inventory Alex`
Expected:
- Inventory summary should include wheat and/or seeds growth over time.

## Scenario B: Farming Carrots/Potatoes
1. Place carrots and potatoes (or tilled land + starter items in Alex inventory).
2. Run: `/steve tell Alex farm carrots and get 8 carrots`
3. Run: `/steve tell Alex farm potatoes and get 8 potatoes`
Expected:
- Correct crop type is targeted each time.
- Harvest/plant behavior repeats as in wheat loop.

## Scenario C: Feeding Animals (Species Preference)
1. Spawn animals near Alex:
- Cow, pig, chicken (at minimum).

2. Give Alex feed items via nearby drop/pickup or prior farming.
3. Run: `/steve tell Alex feed 2 cows`
Expected:
- Alex approaches cows.
- Uses valid preferred food (wheat for cows).
- Feed count progresses until done or explicit failure reason.

4. Run: `/steve tell Alex feed 2 pigs`
Expected:
- Uses carrot/potato/beetroot when available.

5. Run: `/steve tell Alex feed 2 chickens`
Expected:
- Uses seed items when available.

## Scenario D: Failure Clarity
1. Remove all valid food from Alex:
- `/steve drop Alex wheat 64`
- `/steve drop Alex carrot 64`
- `/steve drop Alex potato 64`
- `/steve drop Alex wheat_seeds 64`

2. Run: `/steve tell Alex feed 2 cows`
Expected:
- Action fails with explicit reason (no preferred food available), no hang.

## Scenario E: Hunger Recovery Regression
1. Run:
- `/steve debug Alex food 2`
- `/steve debug Alex saturation 0`
2. Ensure nearby area has limited direct food and some farmable land.
3. Trigger any work command:
- `/steve tell Alex gather wood for crafting`
Expected:
- Food recovery engages.
- Alex can use farm/gather fallback logic and avoids starvation loops.

## Scenario F: Stop/Resume Safety
1. While farming/feeding in progress, run:
- `/steve stop Alex`
2. Then run new command:
- `/steve tell Alex farm wheat and get 6 wheat`
Expected:
- Previous action cancels cleanly.
- New action starts and completes.

## Pass Criteria
1. No infinite behavior loops in logs.
2. Each failed scenario returns explicit failure reason.
3. Farming and feeding both execute at least one full success path.
4. `stop` reliably interrupts and allows new commands.
