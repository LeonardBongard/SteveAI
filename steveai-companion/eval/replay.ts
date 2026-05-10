// Deterministic replay over a recorded scenario JSONL.
//
// Per docs/COMPANION_V1_DIRECTION.md §3.6: replay re-runs a scenario from
// a recorded JSONL WITHOUT invoking the LLM. The point is regression
// detection in the *non-LLM* layers — actions, grounding, memory, tool
// dispatch shape — for free, in seconds, without burning Ollama time.
//
// What we replay: the exact sequence of (player message, observed tool
// calls, observed bot chat). What we DON'T do: re-call gpt-oss, so the
// model isn't part of the regression surface here. If the planner's
// non-LLM logic still produces the same observable outcome from the same
// inputs, replay passes.

import 'dotenv/config';
import path from 'node:path';
import fs from 'node:fs';

import { logs } from '../src/log.js';
import type { RecordedTurn } from './types.js';

interface ReplayLine {
  kind: 'header' | 'turn';
  scenario?: string;
  // for kind === 'turn'
  step?: number;
  ts?: string;
  player?: string;
  message?: string;
  toolCalls?: Array<{ tool: string; args: unknown; observation: string; durationMs: number; step: number }>;
  finalChat?: string[];
  steps?: number;
  durationMs?: number;
}

export interface ReplayResult {
  recordingPath: string;
  scenario: string;
  turns: number;
  toolCalls: number;
  ok: boolean;
}

/** Read a recording file. */
export function loadRecording(file: string): { scenario: string; turns: RecordedTurn[] } {
  const raw = fs.readFileSync(file, 'utf8');
  const lines = raw.split('\n').filter((l) => l.trim().length > 0);
  let scenario = '';
  const turns: RecordedTurn[] = [];
  for (const line of lines) {
    const parsed = JSON.parse(line) as ReplayLine;
    if (parsed.kind === 'header') {
      scenario = parsed.scenario ?? '';
    } else if (parsed.kind === 'turn') {
      turns.push(parsed as unknown as RecordedTurn);
    }
  }
  return { scenario, turns };
}

/**
 * For v1, replay's job is the cheap "did we actually capture sensible state?"
 * check: scenario name present, turns have all expected fields. This is a
 * format-and-structure verifier. Cross-version regression detection — where
 * a shape change in tool args / observations would be flagged — is the next
 * iteration once the recording format stabilizes.
 */
export function replay(file: string): ReplayResult {
  const { scenario, turns } = loadRecording(file);
  let toolCalls = 0;
  let ok = scenario.length > 0;
  for (const t of turns) {
    if (typeof t.step !== 'number') ok = false;
    if (typeof t.message !== 'string') ok = false;
    if (!Array.isArray(t.toolCalls)) ok = false;
    else toolCalls += t.toolCalls.length;
  }
  return {
    recordingPath: file,
    scenario,
    turns: turns.length,
    toolCalls,
    ok,
  };
}

// CLI: `tsx eval/replay.ts [file ...]` — verify one or all recordings.
async function main(): Promise<void> {
  const args = process.argv.slice(2);
  let files: string[];
  if (args.length > 0) {
    files = args;
  } else {
    const dir = path.resolve(process.cwd(), 'eval', 'recordings');
    files = fs.existsSync(dir)
      ? fs.readdirSync(dir).filter((f) => f.endsWith('.jsonl')).map((f) => path.join(dir, f))
      : [];
  }

  if (files.length === 0) {
    /* eslint-disable-next-line no-console */
    console.error('No recordings to replay. Run `npm run eval` first.');
    process.exit(1);
  }

  let allOk = true;
  for (const f of files) {
    const result = replay(f);
    /* eslint-disable-next-line no-console */
    console.log(
      `${result.ok ? '✓' : '✗'}  ${result.scenario.padEnd(40)} turns=${result.turns} toolCalls=${result.toolCalls}`
    );
    if (!result.ok) allOk = false;
  }
  process.exit(allOk ? 0 : 1);
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main().catch((err) => {
    logs.eval.fatal({ err: (err as Error).message }, 'replay crashed');
    process.exit(1);
  });
}
