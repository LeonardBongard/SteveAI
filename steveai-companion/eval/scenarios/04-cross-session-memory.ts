// Scenario 04 — cross-session memory.
//
// Per docs/COMPANION_V1_DIRECTION.md §5 acceptance check #3:
// "session B answers a question about an event from session A".
//
// Session A: player tells Steve their wood preference. Steve should pin
//            the fact (one tool call: pinFact).
// Session B (after a memory-store close + reopen): player asks what they
//            prefer. Steve should look it up and answer "oak".
//
// What this proves: the playbook + pinned-facts persist across the
// equivalent of a bot restart. This is THE differentiator vs. MindCraft
// and friends.

import type { Scenario } from '../types.js';

const scenario: Scenario = {
  name: '04-cross-session-memory',
  description: 'Player tells Steve their wood preference, restarts, asks back. Steve should remember.',
  mode: 'mock',
  playerName: 'TestPlayer',
  setup: {
    bot: { x: 0, y: 64, z: 0 },
    players: [{ username: 'TestPlayer', x: 2, y: 64, z: 0 }],
  },
  script: [
    { type: 'chat', message: 'remember I prefer oak wood for everything' },
    { type: 'wait', ms: 200 },
    { type: 'restart_session' },
    { type: 'chat', message: 'what kind of wood do I prefer?' },
  ],
  goals: [
    // The bot in session A should pin the preference.
    { type: 'tool_called', tool: 'pinFact' },
    // After restart, when asked, the bot's reply must mention oak.
    { type: 'chat_contains', text: 'oak' },
  ],
};

export default scenario;
