// Playbook of recipes — see docs/COMPANION_V1_DIRECTION.md §3.3 + §3.7 #2.
//
// "Recipe" here is intentionally NOT a Voyager-style executable JS skill.
// It's a named, embedded, natural-language description of a task we've
// successfully completed, paired with the ordered list of steps the LLM
// took. Two reasons:
//
// 1. Mineflayer already provides a stable typed action API (pathfinder,
//    collectblock, dig, place, ...). The LLM doesn't need to invent code
//    to gain capability; it composes existing tools. So "recipes" are
//    descriptions, not scripts.
// 2. Hindsight capture (cf. STEVE-1's hindsight relabeling): we only ever
//    name and describe a recipe AFTER it has actually worked. No
//    speculative skill generation. One LLM call per real success.

import { embed, chat } from '../llm/ollama.js';
import { getDb, nowIso, encodeEmbedding } from './store.js';
import { logs } from '../log.js';

export interface Recipe {
  id: number;
  ts: string;
  name: string;
  description: string;
  steps: string[];
  successCount: number;
}

interface RecipeRow {
  id: number;
  ts: string;
  name: string;
  description: string;
  steps: string;     // JSON-encoded array
  success_count: number;
}

function rowToRecipe(row: RecipeRow): Recipe {
  let parsed: unknown;
  try {
    parsed = JSON.parse(row.steps);
  } catch {
    parsed = [];
  }
  const steps = Array.isArray(parsed) ? parsed.filter((s): s is string => typeof s === 'string') : [];
  return {
    id: row.id,
    ts: row.ts,
    name: row.name,
    description: row.description,
    steps,
    successCount: row.success_count,
  };
}

const NAMING_SYSTEM_PROMPT = `You are an assistant that names task recipes for a Minecraft companion bot.

Given an intent (the player's request) and the ordered steps the bot took to satisfy it, produce a JSON object with two fields:

- "name": a short snake_case identifier, max 40 characters. Generic over task variants — e.g. for "mine 12 oak logs" use "gather_logs", not "gather_12_oak_logs". No item counts, no specific block types if a category fits.
- "description": one sentence, present tense, describing what the recipe does at a generic level. No specific quantities or coordinates.

Reply with ONLY the JSON object, no preamble.`;

export class Playbook {
  /**
   * Capture a verified-successful trajectory as a named recipe.
   * One LLM call (for naming + one-line description) + one embed call.
   * If a recipe with the generated name already exists, increment its
   * success_count instead of creating a duplicate.
   */
  async capture(args: { intent: string; steps: string[] }): Promise<Recipe | null> {
    if (args.steps.length === 0) return null;

    const naming = await this.askForName(args.intent, args.steps);
    if (!naming) {
      logs.mem.warn({ intent: args.intent }, 'playbook naming returned null; skipping capture');
      return null;
    }

    const existing = this.get(naming.name);
    if (existing) {
      const ts = nowIso();
      getDb()
        .prepare<[string, number]>(
          'UPDATE playbook SET success_count = success_count + 1, ts = ? WHERE id = ?'
        )
        .run(ts, existing.id);
      logs.mem.info({ name: naming.name, count: existing.successCount + 1 }, 'playbook hit');
      return { ...existing, ts, successCount: existing.successCount + 1 };
    }

    const ts = nowIso();
    const row = getDb()
      .prepare<[string, string, string, string], RecipeRow>(
        `INSERT INTO playbook (ts, name, description, steps)
         VALUES (?, ?, ?, ?)
         RETURNING id, ts, name, description, steps, success_count`
      )
      .get(ts, naming.name, naming.description, JSON.stringify(args.steps));

    if (!row) throw new Error('playbook insert returned no row');

    void this.embedRecipe(row.id, naming.description).catch((err) =>
      logs.mem.warn({ err: (err as Error).message, id: row.id }, 'embed playbook failed')
    );

    logs.mem.info({ name: naming.name }, 'playbook captured');
    return rowToRecipe(row);
  }

  /** "Have I done something like this before?" Embedding similarity over recipes. */
  async search(query: string, k = 3): Promise<Recipe[]> {
    let queryVec: number[];
    try {
      queryVec = await embed(query);
    } catch (err) {
      logs.mem.warn({ err: (err as Error).message }, 'playbook search embed failed');
      return [];
    }
    const queryBlob = encodeEmbedding(queryVec);

    const rows = getDb()
      .prepare<[Buffer, number], RecipeRow>(
        `SELECT id, ts, name, description, steps, success_count
         FROM playbook
         WHERE embedding IS NOT NULL
         ORDER BY vec_distance_cosine(embedding, ?) ASC
         LIMIT ?`
      )
      .all(queryBlob, k);

    return rows.map(rowToRecipe);
  }

  get(name: string): Recipe | null {
    const row = getDb()
      .prepare<[string], RecipeRow>(
        `SELECT id, ts, name, description, steps, success_count
         FROM playbook WHERE name = ?`
      )
      .get(name);
    return row ? rowToRecipe(row) : null;
  }

  list(limit = 50): Recipe[] {
    const rows = getDb()
      .prepare<[number], RecipeRow>(
        `SELECT id, ts, name, description, steps, success_count
         FROM playbook ORDER BY success_count DESC, ts DESC LIMIT ?`
      )
      .all(limit);
    return rows.map(rowToRecipe);
  }

  // --- internals ---

  private async askForName(
    intent: string,
    steps: string[]
  ): Promise<{ name: string; description: string } | null> {
    const stepsText = steps.map((s, i) => `${i + 1}. ${s}`).join('\n');
    const result = await chat([
      { role: 'system', content: NAMING_SYSTEM_PROMPT },
      {
        role: 'user',
        content: `Intent: ${intent}\n\nSteps taken:\n${stepsText}\n\nReply with the JSON object only.`,
      },
    ]);

    const raw = result.content.trim();
    const jsonStart = raw.indexOf('{');
    const jsonEnd = raw.lastIndexOf('}');
    if (jsonStart < 0 || jsonEnd < 0) return null;

    let parsed: unknown;
    try {
      parsed = JSON.parse(raw.slice(jsonStart, jsonEnd + 1));
    } catch {
      return null;
    }
    if (!parsed || typeof parsed !== 'object') return null;
    const obj = parsed as Record<string, unknown>;
    const name = typeof obj.name === 'string' ? sanitizeName(obj.name) : '';
    const description = typeof obj.description === 'string' ? obj.description.trim() : '';
    if (!name || !description) return null;
    return { name, description };
  }

  private async embedRecipe(id: number, description: string): Promise<void> {
    const vec = await embed(description);
    const blob = encodeEmbedding(vec);
    getDb().prepare<[Buffer, number]>('UPDATE playbook SET embedding = ? WHERE id = ?').run(blob, id);
  }
}

function sanitizeName(raw: string): string {
  // The LLM occasionally wraps in quotes / adds spaces.
  return raw
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 40);
}

export const playbook = new Playbook();
