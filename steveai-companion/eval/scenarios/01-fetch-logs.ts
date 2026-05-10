// Scenario 01 — simple action chain: find, go, mine, report.
//
// Player asks the bot to mine an oak log. The bot should:
//   1. Find the nearest oak_log via findNearestBlock.
//   2. Pathfind there (goto).
//   3. Mine it (mineBlock).
//   4. Inventory has at least one oak_log.
//
// We deliberately keep the count to 1 — multi-block fetches need a
// dedicated mineBlocks tool (a Phase-3-follow-up; see runtime gaps §
// in COMPANION_V1_DIRECTION.md).

import type { Scenario } from '../types.js';

const scenario: Scenario = {
  name: '01-fetch-logs',
  description: 'Bot finds the nearest oak log, goes to it, mines it, reports. Inventory should reflect.',
  mode: 'mock',
  playerName: 'TestPlayer',
  setup: {
    bot: { x: 0, y: 64, z: 0 },
    players: [{ username: 'TestPlayer', x: 1, y: 64, z: 0 }],
    blocks: [
      // A small grove of oak logs.
      { x: 8, y: 64, z: 8, name: 'oak_log' },
      { x: 8, y: 65, z: 8, name: 'oak_log' },
      { x: 9, y: 64, z: 8, name: 'oak_log' },
      // Floor.
      { x: 0, y: 63, z: 0, name: 'grass_block' },
    ],
  },
  script: [{ type: 'chat', message: 'mine an oak log for me' }],
  goals: [
    { type: 'tool_called', tool: 'findNearestBlock', argsContain: { blockType: 'oak_log' } },
    { type: 'tool_call_count', tool: 'mineBlock', atLeast: 1 },
    { type: 'inventory_has', itemName: 'oak_log', atLeast: 1 },
  ],
};

export default scenario;
