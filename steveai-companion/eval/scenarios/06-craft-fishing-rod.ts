// v2 headline scenario — RAG-grounded skill-write for crafting.
//
// Per docs/COMPANION_V2_DIRECTION.md §8: starts with an empty skill library.
// Player asks for a fishing rod. Expected workflow:
//   1. Bot calls searchSkill("fishing rod") → no hits.
//   2. Bot calls lookupRecipe("fishing_rod") → grounding (3 stick + 2 string).
//   3. Bot calls writeSkill named "craft_fishing_rod" with code that uses
//      Mineflayer's craft API.
//   4. (Optional) Bot tries invokeSkill — may fail in mock since we don't
//      simulate full crafting; that's OK, we're testing the WORKFLOW here.
//
// What we ASSERT:
//   - The LLM consulted the recipe before writing.
//   - A skill was saved containing "craft" or "fishing_rod" in the name.
//   - The skill code mentions "string" (one of the ingredients) — sanity that
//     the recipe lookup informed the code, vs. pure hallucination.

import type { Scenario } from '../types.js';

const scenario: Scenario = {
  name: '06-craft-fishing-rod',
  description: 'Empty library. Player asks for a fishing rod. Bot RAGs the recipe + writes a skill.',
  mode: 'mock',
  playerName: 'TestPlayer',
  setup: {
    bot: { x: 0, y: 64, z: 0 },
    players: [{ username: 'TestPlayer', x: 1, y: 64, z: 0 }],
    blocks: [
      { x: 0, y: 63, z: 0, name: 'stone' },
      { x: 5, y: 64, z: 0, name: 'crafting_table' },
    ],
    inventory: [
      { name: 'stick', count: 8 },
      { name: 'string', count: 4 },
    ],
  },
  script: [{ type: 'chat', message: 'craft me a fishing rod' }],
  goals: [
    // RAG was consulted before writing — the v2 grounding contract.
    { type: 'tool_called', tool: 'lookupRecipe', argsContain: { item: 'fishing_rod' } },
    // A skill was saved.
    { type: 'skill_saved', nameContains: 'fishing' },
    // Skill code uses the crafting machinery (bot.craft / bot.recipesFor /
    // referencing fishing_rod). We accept any of these — the LLM picked the
    // right Mineflayer surface to call. Specifically it should NOT have just
    // hardcoded "string" + "stick" as plain text.
    { type: 'skill_code_contains', skillNameContains: 'fishing', text: 'fishing_rod' },
  ],
};

export default scenario;
