// Static lint over skill code — catches the silent-bug failure mode where
// the LLM forgets `await` on async Mineflayer methods.
//
// Per docs/COMPANION_V2_DIRECTION.md robustness P6. Without this, a missing
// `await bot.dig(block)` returns a Promise immediately, the skill's wrapper
// resolves "ok," but no block was actually mined. Player thinks Steve
// worked. We catch this statically before execution.
//
// We use string scanning instead of full AST parsing — sufficient for the
// common failure mode and avoids pulling in acorn directly. False positives
// are surfaced as warnings, not errors; they don't block save.

const ASYNC_METHODS: ReadonlyArray<string | RegExp> = [
  'dig',
  'placeBlock',
  'equip',
  'unequip',
  'attack',
  'craft',
  'lookAt',
  'useOn',
  'activateBlock',
  'deactivateBlock',
  'tossStack',
  'toss',
  'sleep',
  'wake',
  'consume',
  'fish',
  // method on bot.pathfinder
  /pathfinder\.goto/,
];

export interface LintResult {
  warnings: string[];
}

/** Lint a skill body (the same code that goes into the sandbox). */
export function lintSkillCode(code: string): LintResult {
  const warnings: string[] = [];

  for (const method of ASYNC_METHODS) {
    const escaped = method instanceof RegExp ? method.source : method;
    // Find every `bot.<method>(` occurrence; check the preceding tokens
    // for `await`. We tolerate: `return bot.foo(...)` at end of arrow,
    // `void bot.foo(...)` (intentional fire-and-forget), and assignments
    // to a named promise variable.
    const re = new RegExp(`\\bbot\\.${escaped}\\s*\\(`, 'g');
    let match: RegExpExecArray | null;
    while ((match = re.exec(code))) {
      const idx = match.index;
      // Look ~16 chars before the match for `await ` or `void ` or `=`.
      const window = code.slice(Math.max(0, idx - 32), idx);
      if (/\bawait\s+$/.test(window)) continue;
      if (/\breturn\s+$/.test(window)) continue;
      if (/\bvoid\s+$/.test(window)) continue;
      if (/=\s*$/.test(window)) continue; // `const p = bot.foo(...)`
      const methodName = method instanceof RegExp ? method.source.replace(/\\/g, '') : method;
      const lineNum = code.slice(0, idx).split('\n').length;
      warnings.push(
        `line ${lineNum}: bot.${methodName}(...) is async — likely missing await. ` +
          `Wrap with \`await\` unless you intentionally want fire-and-forget.`
      );
    }
  }

  return { warnings };
}
