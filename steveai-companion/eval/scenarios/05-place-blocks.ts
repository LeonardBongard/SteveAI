// Scenario 05 — multi-action build.
//
// "Build a small line of dirt at y=64 starting at (5, 64, 0), three blocks long."
// Exercises sequential placeBlock calls and the planner's ability to chain
// related actions in one intent.
//
// Where the plan said "build a small cabin" — we scoped down to a 3-block
// line because (a) cabin patterns aren't in our tool set and (b) we want a
// scenario that fits in the 12-step LLM budget.

import type { Scenario } from '../types.js';

const scenario: Scenario = {
  name: '05-place-blocks',
  description: 'Place 3 dirt blocks in a row. Tests sequential action chains.',
  mode: 'mock',
  playerName: 'TestPlayer',
  setup: {
    bot: { x: 0, y: 64, z: 0 },
    players: [{ username: 'TestPlayer', x: 1, y: 64, z: 0 }],
    blocks: [
      // Floor under all the future placements (placeBlock requires a
      // non-air block directly below, per actions/handlers.ts).
      { x: 5, y: 63, z: 0, name: 'stone' },
      { x: 6, y: 63, z: 0, name: 'stone' },
      { x: 7, y: 63, z: 0, name: 'stone' },
      // Bot's spawn floor.
      { x: 0, y: 63, z: 0, name: 'stone' },
    ],
    inventory: [{ name: 'dirt', count: 16 }],
  },
  script: [
    { type: 'chat', message: 'place a row of three dirt blocks at y=64 from x=5 to x=7, z=0' },
  ],
  goals: [
    { type: 'tool_call_count', tool: 'placeBlock', atLeast: 3 },
    { type: 'world_block', x: 5, y: 64, z: 0, expected: 'dirt' },
    { type: 'world_block', x: 6, y: 64, z: 0, expected: 'dirt' },
    { type: 'world_block', x: 7, y: 64, z: 0, expected: 'dirt' },
  ],
};

export default scenario;
