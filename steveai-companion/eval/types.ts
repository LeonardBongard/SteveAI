// Eval harness types — shared between runner, scenarios, replay.
//
// Scenarios are TypeScript files (per docs/COMPANION_V1_DIRECTION.md §5)
// that export a default Scenario object. The runner imports them by name.

export type ScenarioMode = 'mock' | 'integration';

export interface ScenarioStep {
  type: 'chat' | 'wait' | 'restart_session' | 'reset_world';
  message?: string;          // for type === 'chat'
  ms?: number;               // for type === 'wait'
}

export interface MockWorldBlock {
  x: number;
  y: number;
  z: number;
  name: string;
}

export interface MockPlayer {
  username: string;
  x: number;
  y: number;
  z: number;
  yaw?: number;              // radians
  pitch?: number;            // radians
  heldItem?: string;         // item name
}

export interface MockSetup {
  /** Bot's spawn position. */
  bot: { x: number; y: number; z: number };
  /** Players visible to the bot at start. */
  players?: MockPlayer[];
  /** Static world blocks. */
  blocks?: MockWorldBlock[];
  /** Bot's starting inventory. */
  inventory?: Array<{ name: string; count: number }>;
}

export type GoalCheck =
  | {
      type: 'tool_called';
      tool: string;          // e.g. 'mineBlock'
      /** Optional partial-match on args (substring or coord). */
      argsContain?: Record<string, unknown>;
    }
  | {
      type: 'tool_call_count';
      tool: string;
      atLeast?: number;
      atMost?: number;
    }
  | {
      type: 'chat_contains';
      /** Bot's chat output (any line, case-insensitive substring). */
      text: string;
    }
  | {
      type: 'memory_pinned';
      key: string;
      valueContains?: string;
    }
  | {
      type: 'episodic_recorded';
      eventTag: string;
    }
  | {
      type: 'playbook_captured';
      // any recipe captured during the run
    }
  | {
      type: 'world_block';
      x: number;
      y: number;
      z: number;
      /** Expected post-state. 'air' for "block was mined". */
      expected: string;
    }
  | {
      type: 'inventory_has';
      itemName: string;
      atLeast?: number;
    };

export interface Scenario {
  name: string;             // matches the filename (without .ts)
  description: string;
  mode: ScenarioMode;       // 'mock' covers v1; 'integration' is a future hook
  playerName: string;       // who's "talking" to the bot
  setup: MockSetup;
  script: ScenarioStep[];
  /** All goals must pass for the scenario to pass. */
  goals: GoalCheck[];
}

export interface RecordedToolCall {
  step: number;             // which player chat turn (1-indexed)
  tool: string;
  args: unknown;
  observation: string;
  durationMs: number;
}

export interface RecordedTurn {
  step: number;
  ts: string;
  player: string;
  message: string;
  toolCalls: RecordedToolCall[];
  finalChat: string[];      // bot's chat output during this turn
  steps: number;             // LLM round-trips taken
  durationMs: number;
}

export interface ScenarioResult {
  scenario: string;
  pass: boolean;
  durationMs: number;
  turns: RecordedTurn[];
  goalResults: Array<{ goal: GoalCheck; pass: boolean; reason: string }>;
  /** Path to the JSONL recording (for replay). */
  recordingPath: string;
}

export interface SummaryReport {
  startedAt: string;
  finishedAt: string;
  scenarios: ScenarioResult[];
  passed: number;
  failed: number;
}
