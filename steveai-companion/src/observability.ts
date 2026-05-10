// Live-testing observability helpers.
//
// What you actually want during a live session:
//   1. See every tool call the LLM makes (terminal, real-time).
//   2. Read the actual JS code Steve wrote, in a regular text editor.
//   3. Have an append-only audit log per session you can `tail -f`.
//
// Skills live in SQLite for retrieval — but a SQLite blob isn't `cat`able.
// So we ALSO write each skill to a plain `.js` file with a metadata header
// the moment it's saved. Per-session transcripts go to `data/transcripts/`.

import path from 'node:path';
import fs from 'node:fs';
import { logs } from './log.js';

const ROOT = process.cwd();
const SKILLS_DIR = path.resolve(ROOT, 'data', 'skills');
const TRANSCRIPTS_DIR = path.resolve(ROOT, 'data', 'transcripts');

let currentSessionPath: string | null = null;

export function startSession(): string {
  fs.mkdirSync(TRANSCRIPTS_DIR, { recursive: true });
  fs.mkdirSync(SKILLS_DIR, { recursive: true });
  const ts = new Date().toISOString().replace(/[:.]/g, '-');
  currentSessionPath = path.join(TRANSCRIPTS_DIR, `${ts}.log`);
  fs.writeFileSync(currentSessionPath, `# session ${ts}\n`);
  logs.bot.info({ transcript: currentSessionPath, skills: SKILLS_DIR }, 'observability: live transcripts enabled');
  return currentSessionPath;
}

export function appendTranscript(line: string): void {
  if (!currentSessionPath) return;
  try {
    fs.appendFileSync(currentSessionPath, line + '\n');
  } catch (err) {
    // Best-effort. Don't crash the bot if we can't write.
    logs.bot.warn({ err: (err as Error).message }, 'transcript append failed');
  }
}

/**
 * Write a skill's source to `data/skills/<name>.js` so it's reviewable in
 * an editor. Includes a metadata header comment. Called every time a skill
 * is saved (or revised).
 */
export function dumpSkillToDisk(args: {
  name: string;
  description: string;
  code: string;
  verified: boolean;
  successCount: number;
  failureCount: number;
}): string {
  fs.mkdirSync(SKILLS_DIR, { recursive: true });
  const file = path.join(SKILLS_DIR, `${args.name}.js`);
  const header = [
    '// SteveAI skill — generated at runtime by gpt-oss:20b.',
    `// name:        ${args.name}`,
    `// description: ${args.description}`,
    `// verified:    ${args.verified}`,
    `// counts:      ✓${args.successCount} / ✗${args.failureCount}`,
    `// updated:     ${new Date().toISOString()}`,
    '',
    '// The code below is the BODY of an async function (bot, args) => { … };',
    '// it runs inside a node:vm sandbox with bot, Vec3, goals, Movements,',
    '// console, setTimeout, Promise in scope.',
    '',
  ].join('\n');
  fs.writeFileSync(file, header + args.code + '\n');
  return file;
}

/** Format a tool call for both pino INFO log and the transcript file. */
export function formatToolCallLine(args: {
  step: number;
  tool: string;
  argsSummary: string;
  observationSummary: string;
  durationMs: number;
}): string {
  return `[step ${args.step}] ${args.tool}(${args.argsSummary}) → ${args.observationSummary} [${args.durationMs}ms]`;
}
