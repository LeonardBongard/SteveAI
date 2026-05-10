// Scenario 02 — spatial deixis ("mine that").
//
// Player at (5, 64, 5) looking +X (yaw = -π/2) at iron_ore at (10, 65, 5).
// Player says "mine that ore".
//
// Expected:
//   1. Bot calls resolveReference (the deixis path).
//   2. Bot confirms in chat what it understood ("the iron ore at … on it").
//   3. Bot calls mineBlock at the resolved coords.
//   4. The block becomes air in the mock world.

import type { Scenario } from '../types.js';

const scenario: Scenario = {
  name: '02-mine-that-ore',
  description: 'Spatial deixis: player looks at iron_ore and says "mine that". Bot resolves + confirms + mines.',
  mode: 'mock',
  playerName: 'TestPlayer',
  setup: {
    bot: { x: 0, y: 64, z: 0 },
    players: [
      {
        username: 'TestPlayer',
        x: 5,
        y: 64,
        z: 5,
        // yaw = -π/2 → look vector (+1, 0, 0): facing +X.
        yaw: -Math.PI / 2,
        pitch: 0,
      },
    ],
    blocks: [
      // Iron ore directly along the player's look ray.
      // Eye is at y = 64 + 1.62 ≈ 65.62, so a block at y=65 sits on the ray.
      { x: 10, y: 65, z: 5, name: 'iron_ore' },
      // A floor for the bot to stand on.
      { x: 0, y: 63, z: 0, name: 'stone' },
    ],
    inventory: [
      // Stone pickaxe so the bot CAN mine iron_ore (otherwise harvestTools fails).
      { name: 'stone_pickaxe', count: 1 },
    ],
  },
  script: [{ type: 'chat', message: 'mine that ore' }],
  goals: [
    { type: 'tool_called', tool: 'resolveReference' },
    { type: 'chat_contains', text: 'iron' },
    {
      type: 'tool_called',
      tool: 'mineBlock',
      argsContain: { x: 10, y: 65, z: 5 },
    },
    { type: 'world_block', x: 10, y: 65, z: 5, expected: 'air' },
  ],
};

export default scenario;
