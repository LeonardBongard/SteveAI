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
  timeoutMs = DEFAULT_TIMEOUT_MS
): Promise<RunSkillResult> {
  const start = Date.now();

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

  // The LLM writes the BODY of an async function. We wrap.
  const wrapped = `(async (bot, args) => {\n${code}\n});`;

  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(
      () => reject(new Error(`skill timed out after ${timeoutMs}ms`)),
      timeoutMs
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
    const error = err instanceof Error ? err.message : String(err);
    return { ok: false, error, durationMs: Date.now() - start };
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/** Convenience: just run an arbitrary code expression once. Used by the runOnce tool. */
export async function runOnceCode(
  code: string,
  bot: Bot,
  timeoutMs = DEFAULT_TIMEOUT_MS
): Promise<RunSkillResult> {
  return runSkillBody(code, bot, {}, timeoutMs);
}
