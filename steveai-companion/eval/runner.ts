// Eval harness runner.
//
// Loads scenarios, runs them against a MockBot + the real planner + real
// Ollama (gpt-oss:20b) + a per-scenario isolated memory.db, records JSONL
// of every turn + tool call, evaluates goal checks, prints a summary
// report.
//
// Usage:
//   npm run eval                       # all scenarios
//   npm run eval -- --scenario 04      # by number/name
//   npm run eval -- --replay           # use replay mode (no LLM calls)
//
// Notes:
// - Each scenario uses its own SQLite file under eval/data/<name>.db, so
//   runs don't pollute each other or the user's playable memory.db.
// - "Sessions" in scenarios mean memory store close+reopen on the same
//   DB file; nothing else state-wise resets.
// - We DO call gpt-oss for real (no LLM mocks); ensure Ollama is running.

import 'dotenv/config';
import path from 'node:path';
import fs from 'node:fs';
import { performance } from 'node:perf_hooks';

import { logs } from '../src/log.js';
import { handlePlayerChat } from '../src/planner.js';
import { healthCheck } from '../src/llm/ollama.js';
import { openMemoryStore, closeMemoryStore } from '../src/memory/store.js';
import { conversation } from '../src/memory/conversation.js';
import { episodic } from '../src/memory/episodic.js';
import { playbook } from '../src/memory/playbook.js';
import { skills } from '../src/skills/library.js';
import { loadKnowledge } from '../src/knowledge/index.js';

import { MockBot, asBot } from './mock-bot.js';
import type {
  GoalCheck,
  RecordedToolCall,
  RecordedTurn,
  Scenario,
  ScenarioResult,
  SummaryReport,
} from './types.js';

const EVAL_DATA_DIR = path.resolve(process.cwd(), 'eval', 'data');
const RECORDINGS_DIR = path.resolve(process.cwd(), 'eval', 'recordings');

// ============================================================================
// Public entry — run one or many scenarios.
// ============================================================================

export async function runScenarios(scenarios: Scenario[]): Promise<SummaryReport> {
  fs.mkdirSync(EVAL_DATA_DIR, { recursive: true });
  fs.mkdirSync(RECORDINGS_DIR, { recursive: true });

  const startedAt = new Date().toISOString();
  const results: ScenarioResult[] = [];

  // One health check up front; surface the actionable error early.
  const health = await healthCheck();
  if (!health.ok) {
    logs.eval.error({ details: health.details }, 'Ollama health check failed');
    throw new Error(health.details);
  }

  // Load Minecraft-knowledge RAG once for the runner. Same version the bot would use.
  loadKnowledge('1.21.11');

  for (const scenario of scenarios) {
    logs.eval.info({ scenario: scenario.name }, 'starting');
    const result = await runOne(scenario);
    results.push(result);
    logs.eval.info(
      { scenario: scenario.name, pass: result.pass, durationMs: result.durationMs },
      result.pass ? 'pass' : 'FAIL'
    );
  }

  const report: SummaryReport = {
    startedAt,
    finishedAt: new Date().toISOString(),
    scenarios: results,
    passed: results.filter((r) => r.pass).length,
    failed: results.filter((r) => !r.pass).length,
  };

  printSummary(report);
  return report;
}

// ============================================================================
// One scenario.
// ============================================================================

async function runOne(scenario: Scenario): Promise<ScenarioResult> {
  const t0 = performance.now();
  const dbPath = path.join(EVAL_DATA_DIR, `${scenario.name}.db`);
  const recordingPath = path.join(RECORDINGS_DIR, `${scenario.name}.jsonl`);

  // Fresh DB per scenario, unless the scenario explicitly relies on prior state
  // (which v1 doesn't — every scenario is self-contained).
  if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  if (fs.existsSync(recordingPath)) fs.unlinkSync(recordingPath);

  // Mock bot.
  const mock = new MockBot('SteveAI', scenario.setup);
  // Cast through unknown for the planner.
  const bot = asBot(mock);

  // Open isolated memory store. The planner uses the global singleton
  // openMemoryStore() — which we redirect to our per-scenario file.
  closeMemoryStore();
  openMemoryStore(dbPath);

  const turns: RecordedTurn[] = [];
  let stepNumber = 0;

  for (const step of scenario.script) {
    if (step.type === 'chat') {
      stepNumber += 1;
      const message = step.message ?? '';
      const turn = await runChatTurn(mock, bot, scenario.playerName, message, stepNumber);
      turns.push(turn);
    } else if (step.type === 'wait') {
      await sleep(step.ms ?? 0);
    } else if (step.type === 'restart_session') {
      // Close + reopen the same DB file. Simulates bot disconnect+reconnect.
      // The mock bot is also recreated, since real reconnects yield a fresh
      // bot object from mineflayer.
      closeMemoryStore();
      // tiny delay so any in-flight async finishes
      await sleep(50);
      openMemoryStore(dbPath);
      // Reset transient bot state (chatLines history) so we observe ONLY the
      // post-restart turns. The world state persists.
      mock.record.chatLines = [];
      mock.record.toolEvents = [];
    } else if (step.type === 'reset_world') {
      // Out of v1 scope; placeholder.
    }
  }

  // Evaluate goals.
  const goalResults = scenario.goals.map((goal) => evaluateGoal(goal, turns, mock));
  const pass = goalResults.every((g) => g.pass);

  closeMemoryStore();
  writeRecording(recordingPath, scenario.name, turns);

  const durationMs = Math.round(performance.now() - t0);

  return {
    scenario: scenario.name,
    pass,
    durationMs,
    turns,
    goalResults,
    recordingPath,
  };
}

async function runChatTurn(
  mock: MockBot,
  bot: ReturnType<typeof asBot>,
  player: string,
  message: string,
  stepNumber: number
): Promise<RecordedTurn> {
  const ts = new Date().toISOString();
  const t0 = performance.now();

  // Snapshot mock state so we record only THIS turn's chat lines.
  const chatStart = mock.record.chatLines.length;

  const toolCalls: RecordedToolCall[] = [];

  const result = await handlePlayerChat(bot, player, message, {
    awaitCapture: true,
    hooks: {
      onToolCall: ({ tool, args, observation, durationMs, step }) => {
        toolCalls.push({ step, tool, args, observation, durationMs });
      },
    },
  });

  const durationMs = Math.round(performance.now() - t0);

  return {
    step: stepNumber,
    ts,
    player,
    message,
    toolCalls,
    finalChat: mock.record.chatLines.slice(chatStart),
    steps: result.steps,
    durationMs,
  };
}

// ============================================================================
// Goal evaluation.
// ============================================================================

function evaluateGoal(
  goal: GoalCheck,
  turns: RecordedTurn[],
  mock: MockBot
): { goal: GoalCheck; pass: boolean; reason: string } {
  switch (goal.type) {
    case 'tool_called': {
      const matches = turns.flatMap((t) => t.toolCalls).filter((c) => c.tool === goal.tool);
      if (matches.length === 0) {
        return { goal, pass: false, reason: `tool ${goal.tool} never called` };
      }
      if (goal.argsContain) {
        const ok = matches.some((m) => argsContain(m.args, goal.argsContain ?? {}));
        return ok
          ? { goal, pass: true, reason: `${goal.tool} called with matching args` }
          : { goal, pass: false, reason: `${goal.tool} called but args didn't match ${JSON.stringify(goal.argsContain)}` };
      }
      return { goal, pass: true, reason: `${goal.tool} called ${matches.length}x` };
    }

    case 'tool_call_count': {
      const calls = turns.flatMap((t) => t.toolCalls).filter((c) => c.tool === goal.tool);
      const n = calls.length;
      if (goal.atLeast !== undefined && n < goal.atLeast) {
        return { goal, pass: false, reason: `${goal.tool} called ${n}x, expected ≥ ${goal.atLeast}` };
      }
      if (goal.atMost !== undefined && n > goal.atMost) {
        return { goal, pass: false, reason: `${goal.tool} called ${n}x, expected ≤ ${goal.atMost}` };
      }
      return { goal, pass: true, reason: `${goal.tool} called ${n}x (in range)` };
    }

    case 'chat_contains': {
      const target = goal.text.toLowerCase();
      const allChat = turns.flatMap((t) => t.finalChat).join('\n').toLowerCase();
      const ok = allChat.includes(target);
      return ok
        ? { goal, pass: true, reason: `bot said "…${goal.text}…"` }
        : { goal, pass: false, reason: `bot never said "${goal.text}". Chat: ${allChat.slice(0, 200)}` };
    }

    case 'memory_pinned': {
      const fact = conversation.pinnedFact(goal.key);
      if (!fact) return { goal, pass: false, reason: `no pinned fact ${goal.key}` };
      if (goal.valueContains) {
        const ok = fact.value.toLowerCase().includes(goal.valueContains.toLowerCase());
        return ok
          ? { goal, pass: true, reason: `${goal.key}=${fact.value}` }
          : { goal, pass: false, reason: `${goal.key} pinned but value="${fact.value}" doesn't contain "${goal.valueContains}"` };
      }
      return { goal, pass: true, reason: `${goal.key}=${fact.value}` };
    }

    case 'episodic_recorded': {
      const events = episodic.recent({ event: goal.eventTag });
      return events.length > 0
        ? { goal, pass: true, reason: `${events.length} ${goal.eventTag} event(s) recorded` }
        : { goal, pass: false, reason: `no episodic events with tag ${goal.eventTag}` };
    }

    case 'playbook_captured': {
      const recipes = playbook.list(50);
      return recipes.length > 0
        ? { goal, pass: true, reason: `${recipes.length} recipe(s) in playbook (e.g. ${recipes[0]?.name ?? '?'})` }
        : { goal, pass: false, reason: 'no recipes captured' };
    }

    case 'skill_saved': {
      const list = skills.list(50);
      const needle = goal.nameContains?.toLowerCase();
      const filtered = needle ? list.filter((s) => s.name.includes(needle)) : list;
      return filtered.length > 0
        ? {
            goal,
            pass: true,
            reason: `${filtered.length} skill(s) saved (e.g. ${filtered[0]?.name ?? '?'})`,
          }
        : { goal, pass: false, reason: `no skills saved${needle ? ` matching "${needle}"` : ''}` };
    }

    case 'skill_code_contains': {
      const list = skills.list(50);
      const nameNeedle = goal.skillNameContains?.toLowerCase();
      const candidates = nameNeedle ? list.filter((s) => s.name.includes(nameNeedle)) : list;
      const target = goal.text.toLowerCase();
      const hit = candidates.find((s) => s.code.toLowerCase().includes(target));
      return hit
        ? { goal, pass: true, reason: `skill "${hit.name}" code contains "${goal.text}"` }
        : { goal, pass: false, reason: `no skill code contains "${goal.text}"` };
    }

    case 'world_block': {
      const block = mock.blockAt({ x: goal.x, y: goal.y, z: goal.z } as never);
      const got = block?.name ?? 'air';
      return got === goal.expected
        ? { goal, pass: true, reason: `block at (${goal.x},${goal.y},${goal.z}) is ${got}` }
        : { goal, pass: false, reason: `block at (${goal.x},${goal.y},${goal.z}) is ${got}, expected ${goal.expected}` };
    }

    case 'inventory_has': {
      const items = mock.inventory.items();
      const found = items.find((i) => i.name === goal.itemName);
      if (!found) return { goal, pass: false, reason: `no ${goal.itemName} in inventory` };
      if (goal.atLeast !== undefined && found.count < goal.atLeast) {
        return { goal, pass: false, reason: `${found.count}x ${goal.itemName}, expected ≥ ${goal.atLeast}` };
      }
      return { goal, pass: true, reason: `${found.count}x ${goal.itemName}` };
    }
  }
}

function argsContain(actual: unknown, expected: Record<string, unknown>): boolean {
  if (typeof actual !== 'object' || actual === null) return false;
  const a = actual as Record<string, unknown>;
  for (const [k, v] of Object.entries(expected)) {
    if (typeof v === 'string') {
      if (typeof a[k] !== 'string' || !(a[k] as string).toLowerCase().includes(v.toLowerCase())) return false;
    } else {
      if (a[k] !== v) return false;
    }
  }
  return true;
}

// ============================================================================
// Reporting.
// ============================================================================

function writeRecording(file: string, scenarioName: string, turns: RecordedTurn[]): void {
  const lines: string[] = [];
  lines.push(JSON.stringify({ scenario: scenarioName, kind: 'header' }));
  for (const t of turns) {
    lines.push(JSON.stringify({ kind: 'turn', ...t }));
  }
  fs.writeFileSync(file, lines.join('\n') + '\n');
}

function printSummary(r: SummaryReport): void {
  /* eslint-disable no-console */
  console.log('\n=========================================');
  console.log(' Eval summary');
  console.log('=========================================');
  for (const s of r.scenarios) {
    const tag = s.pass ? '✓ PASS' : '✗ FAIL';
    console.log(`${tag}  ${s.scenario.padEnd(40)} ${s.durationMs}ms`);
    if (!s.pass) {
      for (const g of s.goalResults.filter((g) => !g.pass)) {
        console.log(`        - ${describeGoal(g.goal)}: ${g.reason}`);
      }
    }
  }
  console.log('-----------------------------------------');
  console.log(`${r.passed}/${r.scenarios.length} passed (${r.failed} failed)`);
  console.log('=========================================\n');
  /* eslint-enable no-console */
}

function describeGoal(g: GoalCheck): string {
  switch (g.type) {
    case 'tool_called':
      return `tool ${g.tool}${g.argsContain ? ` (${JSON.stringify(g.argsContain)})` : ''}`;
    case 'tool_call_count':
      return `tool ${g.tool} count [${g.atLeast ?? ''}..${g.atMost ?? ''}]`;
    case 'chat_contains':
      return `chat contains "${g.text}"`;
    case 'memory_pinned':
      return `pinned fact ${g.key}${g.valueContains ? ` ~ "${g.valueContains}"` : ''}`;
    case 'episodic_recorded':
      return `episodic event ${g.eventTag}`;
    case 'playbook_captured':
      return 'any playbook recipe captured';
    case 'skill_saved':
      return `skill saved${g.nameContains ? ` (name~"${g.nameContains}")` : ''}`;
    case 'skill_code_contains':
      return `skill${g.skillNameContains ? ` "${g.skillNameContains}"` : ''} code contains "${g.text}"`;
    case 'world_block':
      return `block at (${g.x},${g.y},${g.z}) == ${g.expected}`;
    case 'inventory_has':
      return `inventory has ${g.itemName}${g.atLeast ? ` ≥${g.atLeast}` : ''}`;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((res) => setTimeout(res, ms));
}

// ============================================================================
// CLI entry.
// ============================================================================

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const requested = pickScenarioFilter(args);

  // Load scenarios from eval/scenarios/.
  const scenariosDir = path.resolve(process.cwd(), 'eval', 'scenarios');
  const files = fs.existsSync(scenariosDir)
    ? fs.readdirSync(scenariosDir).filter((f) => f.endsWith('.ts')).sort()
    : [];

  const scenarios: Scenario[] = [];
  for (const file of files) {
    if (requested && !matchesFilter(file, requested)) continue;
    const mod = (await import(path.join(scenariosDir, file))) as { default?: Scenario };
    if (mod.default) scenarios.push(mod.default);
  }

  if (scenarios.length === 0) {
    /* eslint-disable-next-line no-console */
    console.error(`No scenarios matched.${requested ? ` Filter: "${requested}"` : ''}`);
    process.exit(1);
  }

  const report = await runScenarios(scenarios);
  process.exit(report.failed === 0 ? 0 : 1);
}

function pickScenarioFilter(args: string[]): string | undefined {
  const idx = args.findIndex((a) => a === '--scenario');
  if (idx === -1 || idx + 1 >= args.length) return undefined;
  return args[idx + 1];
}

function matchesFilter(filename: string, filter: string): boolean {
  // Matches "04", "04-cross-session-memory", or full filename.
  const base = filename.replace(/\.ts$/, '');
  if (base === filter) return true;
  const num = base.split('-')[0];
  return num === filter || num?.padStart(2, '0') === filter;
}

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main().catch((err) => {
    logs.eval.fatal({ err: (err as Error).message, stack: (err as Error).stack }, 'eval crashed');
    process.exit(1);
  });
}
