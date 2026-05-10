// Scenario 03 — multi-step task with a relative reference.
//
// Player asks the bot to "go to the iron ore and mine it". This exercises:
//   1. findNearestBlock (the bot looks up where iron is)
//   2. goto (path to it)
//   3. mineBlock (extract)
//   4. report
//
// Where the original plan said "craft iron tools" — we simplified to a
// pure mining sequence. Phase 1+2 don't expose a craft tool; crafting is a
// follow-up (mineflayer-collectblock has it, but it's not yet wrapped).

import type { Scenario } from '../types.js';

const scenario: Scenario = {
  name: '03-multi-step-task',
  description: 'Multi-step plan: locate iron ore, path to it, mine it, report success.',
  mode: 'mock',
  playerName: 'TestPlayer',
  setup: {
    bot: { x: 0, y: 64, z: 0 },
    players: [{ username: 'TestPlayer', x: 1, y: 64, z: 0 }],
    blocks: [
      { x: 12, y: 60, z: 12, name: 'iron_ore' },
      { x: 0, y: 63, z: 0, name: 'stone' },
      // Floor near the iron so pathfinder.goto teleports onto solid ground.
      { x: 12, y: 59, z: 12, name: 'stone' },
    ],
    inventory: [{ name: 'stone_pickaxe', count: 1 }],
  },
  script: [{ type: 'chat', message: 'go to the iron ore and mine it' }],
  goals: [
    { type: 'tool_called', tool: 'findNearestBlock', argsContain: { blockType: 'iron_ore' } },
    { type: 'tool_called', tool: 'goto' },
    { type: 'tool_called', tool: 'mineBlock', argsContain: { x: 12, y: 60, z: 12 } },
    { type: 'world_block', x: 12, y: 60, z: 12, expected: 'air' },
    { type: 'inventory_has', itemName: 'iron_ore', atLeast: 1 },
  ],
};

export default scenario;
