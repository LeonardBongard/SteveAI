# Domain PR Sequence

Delivery model: one PR per domain (Q9 = B).

## Order
1. PR-1 Safety Spine
- `SafetyEvaluatorManager` contracts + executor integration + combat retreat rule.
- Blocks all later domain PRs.

2. PR-2 Hunger and Eating
- Vanilla hunger model + food policy exceptions + interruption/resume.

3. PR-3 Swimming and Water Traversal
- Water-safe routing with river-safe default semantics.

4. PR-4 Crafting Pipeline
- Recipe decomposition + dependency crafting flow.

5. PR-5 Farming (wheat/carrot/potato)
- Plant/harvest/replant loop tied to hunger recovery.

6. PR-6 Animal Feeding and Breeding
- Preferred food mapping + feed + breed flow.

7. PR-7 Mining Normalization
- Align current mining WIP to safety/contract framework.

## Gate for Every PR
1. Unit tests
2. Integration tests
3. Manual scripted in-game checks
4. Impacted scenarios from `docs/EVAL_SCENARIOS.md`

## Human Checkpoint
Before merging each PR:
1. Review behavior videos/log snippets.
2. Approve threshold/config defaults.
3. Confirm no regression against previous PR scenarios.

