// Static lint over skill code. Two passes:
//
// 1. await-lint: catches the silent-bug failure mode where the LLM forgets
//    `await` on async Mineflayer methods (P6).
// 2. registry-ref check: catches the LLM hallucinating item/block names
//    in `bot.registry.itemsByName.X` / `bot.registry.blocksByName.X`. The
//    runtime would otherwise throw a generic `Cannot read properties of
//    undefined (reading 'id')` and the LLM has no idea which name was bad.
//    With this check, the LLM gets "oak_plank is not a real item — did you
//    mean oak_planks?" up front.
//
// We use string scanning instead of full AST parsing — sufficient for the
// common failure mode and avoids pulling in acorn directly. False positives
// (warnings) don't block save; registry-ref MISSES are hard errors that
// block save so the bad name doesn't leak into the library.

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

export interface RegistryLintResult {
  /** Each entry is a typo'd name (X) the code referenced in registry. */
  errors: Array<{ kind: 'item' | 'block'; name: string; suggestion: string | null }>;
}

/**
 * Check every `bot.registry.itemsByName.X` / `bot.registry.blocksByName.X`
 * reference against the loaded Minecraft-knowledge base. Returns the set
 * of unknown names with suggested corrections. The planner blocks save
 * if any errors come back.
 */
export function lintRegistryReferences(
  code: string,
  knows: { isKnownItem: (n: string) => boolean; isKnownBlock: (n: string) => boolean; suggestSimilar: (n: string, k: 'item' | 'block') => string | null }
): RegistryLintResult {
  const errors: RegistryLintResult['errors'] = [];

  for (const m of code.matchAll(/\bbot\.registry\.itemsByName\.([A-Za-z_][A-Za-z0-9_]*)/g)) {
    const name = m[1];
    if (!name || knows.isKnownItem(name)) continue;
    errors.push({ kind: 'item', name, suggestion: knows.suggestSimilar(name, 'item') });
  }
  for (const m of code.matchAll(/\bbot\.registry\.blocksByName\.([A-Za-z_][A-Za-z0-9_]*)/g)) {
    const name = m[1];
    if (!name || knows.isKnownBlock(name)) continue;
    errors.push({ kind: 'block', name, suggestion: knows.suggestSimilar(name, 'block') });
  }

  return { errors };
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
