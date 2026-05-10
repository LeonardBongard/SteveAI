// SQLite-backed skill library — v2.
//
// Stores executable JavaScript that the LLM has written, named, and
// (ideally) verified. Embedded by description for retrieval. Tracks
// success and failure counts. Schema is in memory/store.ts (skills table).
//
// API:
//   save(name, description, code)        → embed + persist
//   get(name)                             → exact lookup
//   search(query, k)                      → vector-similarity retrieval
//   recordSuccess(name) / recordFailure(name, error)
//   list(limit)                           → recent + most-used

import { embed } from '../llm/ollama.js';
import { getDb, nowIso, encodeEmbedding } from '../memory/store.js';
import { logs } from '../log.js';

export interface Skill {
  id: number;
  ts: string;
  name: string;
  description: string;
  code: string;
  successCount: number;
  failureCount: number;
  lastInvokedAt: string | null;
}

interface SkillRow {
  id: number;
  ts: string;
  name: string;
  description: string;
  code: string;
  success_count: number;
  failure_count: number;
  last_invoked_at: string | null;
}

function rowToSkill(r: SkillRow): Skill {
  return {
    id: r.id,
    ts: r.ts,
    name: r.name,
    description: r.description,
    code: r.code,
    successCount: r.success_count,
    failureCount: r.failure_count,
    lastInvokedAt: r.last_invoked_at,
  };
}

export class SkillLibrary {
  /**
   * Persist a new skill. If a skill with the same name already exists, this
   * overwrites the code and description (the LLM has decided to revise its
   * implementation — last write wins).
   */
  async save(name: string, description: string, code: string): Promise<Skill> {
    const ts = nowIso();
    const cleanName = sanitizeName(name);
    if (!cleanName) throw new Error(`invalid skill name: "${name}"`);

    const upsert = getDb().prepare<
      [string, string, string, string],
      SkillRow
    >(`
      INSERT INTO skills (ts, name, description, code, success_count, failure_count)
      VALUES (?, ?, ?, ?, 0, 0)
      ON CONFLICT(name) DO UPDATE SET
        ts = excluded.ts,
        description = excluded.description,
        code = excluded.code
      RETURNING id, ts, name, description, code, success_count, failure_count, last_invoked_at
    `);
    const row = upsert.get(ts, cleanName, description, code);
    if (!row) throw new Error(`skill insert returned no row for ${cleanName}`);

    // Embed the description so we can retrieve later. Fire-and-forget.
    void this.embed(row.id, description).catch((err) =>
      logs.mem.warn({ err: (err as Error).message, name: cleanName }, 'embed skill failed')
    );

    logs.act.info({ skill: cleanName, ts }, 'skill saved');
    return rowToSkill(row);
  }

  get(name: string): Skill | null {
    const cleanName = sanitizeName(name);
    const row = getDb()
      .prepare<[string], SkillRow>(
        `SELECT id, ts, name, description, code, success_count, failure_count, last_invoked_at
         FROM skills WHERE name = ?`
      )
      .get(cleanName);
    return row ? rowToSkill(row) : null;
  }

  /** Vector retrieval over description embedding. Returns most similar first. */
  async search(query: string, k = 4): Promise<Skill[]> {
    let queryVec: number[];
    try {
      queryVec = await embed(query);
    } catch (err) {
      logs.mem.warn({ err: (err as Error).message }, 'skill search embed failed');
      return [];
    }
    const queryBlob = encodeEmbedding(queryVec);

    const rows = getDb()
      .prepare<[Buffer, number], SkillRow>(
        `SELECT id, ts, name, description, code, success_count, failure_count, last_invoked_at
         FROM skills
         WHERE embedding IS NOT NULL
         ORDER BY vec_distance_cosine(embedding, ?) ASC
         LIMIT ?`
      )
      .all(queryBlob, k);

    return rows.map(rowToSkill);
  }

  /** Track success after a successful invocation. Light-touch; doesn't fail. */
  recordSuccess(name: string): void {
    const cleanName = sanitizeName(name);
    const ts = nowIso();
    getDb()
      .prepare<[string, string]>(
        `UPDATE skills
         SET success_count = success_count + 1,
             last_invoked_at = ?
         WHERE name = ?`
      )
      .run(ts, cleanName);
  }

  recordFailure(name: string, error: string): void {
    const cleanName = sanitizeName(name);
    const ts = nowIso();
    getDb()
      .prepare<[string, string]>(
        `UPDATE skills
         SET failure_count = failure_count + 1,
             last_invoked_at = ?
         WHERE name = ?`
      )
      .run(ts, cleanName);
    logs.act.warn({ skill: cleanName, error }, 'skill recorded failure');
  }

  list(limit = 50): Skill[] {
    const rows = getDb()
      .prepare<[number], SkillRow>(
        `SELECT id, ts, name, description, code, success_count, failure_count, last_invoked_at
         FROM skills
         ORDER BY (success_count - failure_count) DESC, ts DESC
         LIMIT ?`
      )
      .all(limit);
    return rows.map(rowToSkill);
  }

  count(): number {
    const row = getDb()
      .prepare<[], { c: number }>('SELECT COUNT(*) AS c FROM skills')
      .get();
    return row?.c ?? 0;
  }

  // --- internals ---

  private async embed(id: number, description: string): Promise<void> {
    const vec = await embed(description);
    const blob = encodeEmbedding(vec);
    getDb()
      .prepare<[Buffer, number]>('UPDATE skills SET embedding = ? WHERE id = ?')
      .run(blob, id);
  }
}

function sanitizeName(raw: string): string {
  return raw
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_]/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 60);
}

export const skills = new SkillLibrary();
