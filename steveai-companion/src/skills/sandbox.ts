// Skill sandbox: executes LLM-written JavaScript with a Mineflayer bot in
// scope, in a node:vm context, with a timeout.
//
// Per docs/COMPANION_V2_DIRECTION.md §3.3:
// - The threat model is "LLM is sometimes wrong," NOT "LLM is malicious."
//   node:vm is sufficient for accidental-bug containment; it is NOT a
//   security boundary against an adversarial process.
// - The skill code receives `bot`, `Vec3`, `goals`, `Movements`, `console`,
//   and standard async helpers (setTimeout, Promise) in scope.
// - No `require` is exposed. Skills must use the pre-injected globals.

import vm from 'node:vm';
import type { Bot } from 'mineflayer';
import { Vec3 } from 'vec3';
import pathfinderPkg from 'mineflayer-pathfinder';
const { goals, Movements } = pathfinderPkg;
import { logs } from '../log.js';

const DEFAULT_TIMEOUT_MS = 30_000;
const LOOP_TIMEOUT_MS = 120_000;

/**
 * Choose the timeout for a skill body. Loop-style skills (containing `while`
 * or `for` AND at least one async bot operation) get a much longer budget —
 * the original 30s wasn't enough for "mine 8 oak logs in a sparse forest"
 * which hit timeout on every variant. Single-shot skills stick to 30s.
 */
function pickTimeout(code: string): number {
  const hasLoop = /\b(while|for)\s*\(/.test(code);
  const hasAsyncBot = /\bawait\s+bot\.(dig|placeBlock|equip|attack|craft|lookAt|useOn|activateBlock|pathfinder\.goto)/.test(code);
  return hasLoop && hasAsyncBot ? LOOP_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
}

export interface RunSkillResult {
  ok: boolean;
  /** Truthy result returned by the skill. */
  value?: unknown;
  /** Error message if the skill threw. */
  error?: string;
  /** Wall-clock duration. */
  durationMs: number;
}

/**
 * Execute the body of an async function `(bot, args) => { ...code... }`.
 * The body is whatever the LLM wrote inside `writeSkill(name, description, code)`.
 *
 * On success, returns { ok: true, value }.
 * On error or timeout, returns { ok: false, error }.
 */
export async function runSkillBody(
  code: string,
  bot: Bot,
  args: unknown = {},
  timeoutMs?: number
): Promise<RunSkillResult> {
  const start = Date.now();
  const effectiveTimeout = timeoutMs ?? pickTimeout(code);

  // Pre-loaded helpers in scope. `console` is mapped to pino with a [SKILL] tag
  // so any `console.log` inside skill code lands somewhere visible.
  const skillConsole = {
    log: (...a: unknown[]) => logs.act.info({ src: 'skill' }, a.map(String).join(' ')),
    warn: (...a: unknown[]) => logs.act.warn({ src: 'skill' }, a.map(String).join(' ')),
    error: (...a: unknown[]) => logs.act.error({ src: 'skill' }, a.map(String).join(' ')),
  };

  const sandbox: vm.Context = vm.createContext({
    bot,
    Vec3,
    goals,
    Movements,
    console: skillConsole,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    Promise,
    args,
  });

  // P1: tolerate common LLM wrappings — strip outer `async function (bot, args) {…}`
  // or `(bot, args) => {…}` before our own IIFE wrapper.
  const body = extractBody(code);
  const wrapped = `(async (bot, args) => {\n${body}\n});`;

  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(
      () => reject(new Error(`skill timed out after ${effectiveTimeout}ms`)),
      effectiveTimeout
    );
  });

  try {
    // Compile the wrapped IIFE and grab the function it produces.
    const fn = vm.runInContext(wrapped, sandbox, {
      timeout: 1000, // synchronous compile timeout
      filename: 'skill.js',
    });
    if (typeof fn !== 'function') {
      return {
        ok: false,
        error: 'compiled skill is not a function',
        durationMs: Date.now() - start,
      };
    }
    const value = await Promise.race([fn(bot, args), timeout]);
    return { ok: true, value, durationMs: Date.now() - start };
  } catch (err) {
    const raw = err instanceof Error ? err.message : String(err);
    const decorated = decorateError(raw, body);
    return { ok: false, error: decorated, durationMs: Date.now() - start };
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/**
 * Turn opaque runtime errors into actionable hints. The LLM otherwise sees
 * `TypeError: Cannot read properties of null (reading 'offset')` and has no
 * idea which call returned null. Here we pattern-match the most common
 * Mineflayer-API confusions and append a one-line hint.
 *
 * Returns the decorated error string (original message + hint, or the
 * original message if no pattern matched).
 */
export function decorateError(message: string, code: string): string {
  // Match patterns like: Cannot read properties of null (reading 'X')
  // or:                   Cannot read properties of undefined (reading 'X')
  const nullProp = /Cannot read properties of (null|undefined) \(reading '([^']+)'\)/.exec(message);
  if (nullProp) {
    const prop = nullProp[2] ?? '';
    const hints: string[] = [];

    // findBlocks returns Vec3[]; treating result[0] as a Block fails on .position
    if (/\bbot\.findBlocks\s*\(/.test(code) && (prop === 'position' || prop === 'name' || prop === 'type')) {
      hints.push(
        `bot.findBlocks (plural) returns an ARRAY OF Vec3 POSITIONS, not Block objects. Use bot.findBlock (singular) for one Block, or wrap with bot.blockAt(pos) for each position.`
      );
    }
    // Calling .offset() on a null result of blockAt/findBlock
    if (prop === 'offset' && /\bbot\.(blockAt|findBlock)\s*\(/.test(code)) {
      hints.push(
        `bot.findBlock returns null if no match; bot.blockAt returns null off-loaded-chunks. Null-check before calling .position.offset(...).`
      );
    }
    // Hit .id on a missing registry entry (mostly caught by registry-ref lint;
    // this is the fallback when somehow it slipped through)
    if (prop === 'id' && /\bbot\.registry\.(itemsByName|blocksByName)\./.test(code)) {
      hints.push(
        `bot.registry.{itemsByName,blocksByName}.X returns undefined for unknown names. Verify the exact registry name via lookupBlock / lookupRecipe.`
      );
    }
    // .find on inventory returning undefined, then trying to use .name or .type
    if ((prop === 'name' || prop === 'type' || prop === 'count' || prop === 'slot') && /\binventory\.items\(\)\.find\b/.test(code)) {
      hints.push(
        `bot.inventory.items().find(...) returns undefined if no item matches. Null-check the result before accessing .${prop}.`
      );
    }

    if (hints.length > 0) {
      return `${message}  HINT: ${hints.join(' / ')}`;
    }
  }

  // ReferenceError: global is not defined — the LLM keeps trying to destructure
  // sandbox helpers from `global`.
  if (/ReferenceError: global is not defined/.test(message)) {
    return `${message}  HINT: bot, Vec3, goals, Movements, console are TOP-LEVEL identifiers in this sandbox. Use them directly, not via global.`;
  }

  return message;
}

/** Convenience: just run an arbitrary code expression once. Used by the runOnce tool. */
export async function runOnceCode(
  code: string,
  bot: Bot,
  timeoutMs?: number
): Promise<RunSkillResult> {
  return runSkillBody(code, bot, {}, timeoutMs);
}

/**
 * Static syntax check: does this code parse as the body of an async function?
 * Per robustness P1: catches SyntaxError BEFORE the skill goes into the
 * library. The LLM gets the parse error in the same turn and can patch.
 *
 * Returns null if the code is parseable, or a short error string if not.
 */
export function checkSkillSyntax(code: string): string | null {
  const body = extractBody(code);
  // Wrap exactly as runSkillBody will. Use vm.compileFunction so we don't
  // execute anything — pure syntax validation.
  try {
    vm.compileFunction(`(async (bot, args) => {\n${body}\n});`, [], {
      parsingContext: vm.createContext({}),
    });
    return null;
  } catch (err) {
    if (err instanceof SyntaxError) {
      // Strip "SyntaxError: " prefix; first line only.
      const msg = err.message.split('\n')[0]?.trim() ?? err.message;
      return msg;
    }
    return err instanceof Error ? err.message : String(err);
  }
}

/**
 * P1 — strip common LLM-supplied wrappers so the LLM doesn't get punished
 * for writing `async function (bot, args) {…}` (the v2 playtest failure
 * mode). Returns the body that should go inside our own IIFE wrapper.
 *
 * Patterns handled (most specific first):
 *   async function NAME(bot, args) {…}
 *   async function (bot, args) {…}           ← original parse-error case
 *   function NAME(bot, args) {…}
 *   function (bot, args) {…}                  ← also parse-errors as-is
 *   async (bot, args) => {…}
 *   (bot, args) => {…}
 *
 * Anything else is returned as-is (treated as statement-level code).
 */
export function extractBody(code: string): string {
  const trimmed = code.trim();

  // Try wrapper patterns in priority order. Each captures the inner body.
  const patterns: RegExp[] = [
    // async function NAME(...) { ... }
    /^async\s+function\s+\w+\s*\([^)]*\)\s*\{([\s\S]*)\}\s*;?\s*$/,
    // async function (...) { ... }
    /^async\s+function\s*\([^)]*\)\s*\{([\s\S]*)\}\s*;?\s*$/,
    // function NAME(...) { ... }
    /^function\s+\w+\s*\([^)]*\)\s*\{([\s\S]*)\}\s*;?\s*$/,
    // function (...) { ... }
    /^function\s*\([^)]*\)\s*\{([\s\S]*)\}\s*;?\s*$/,
    // async (...) => { ... }
    /^async\s*\([^)]*\)\s*=>\s*\{([\s\S]*)\}\s*;?\s*$/,
    // (...) => { ... }
    /^\([^)]*\)\s*=>\s*\{([\s\S]*)\}\s*;?\s*$/,
  ];

  for (const re of patterns) {
    const m = re.exec(trimmed);
    if (m && m[1] !== undefined) return m[1];
  }

  return code;
}
